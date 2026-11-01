package com.schematicdropper.drop;

import com.schematicdropper.util.ItemResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Talks to the creative inventory.
 *
 * <p>Two facts drive the design, both verified against the 1.21.1 server:
 *
 * <ul>
 *   <li>{@code dropCreativeStack} sends a {@code CreativeInventoryActionC2SPacket}
 *       with slot {@code -1}. The server validates the item, then drops it. No
 *       acknowledgement is sent back, so the caller must confirm via the spawned
 *       item entity - see {@link DropLedger}.</li>
 *   <li>The server only performs a drop while its own anti-spam budget allows it,
 *       which refills at 20 per tick and is spent 20 per drop - ten drops per
 *       tick, or two hundred per second. Beyond that, drops are discarded
 *       silently. {@code DropTask} clamps to that ceiling rather than firing into
 *       the void.</li>
 * </ul>
 *
 * <p>Giving an item to a hotbar slot uses the same packet with a real slot index,
 * and that <em>does</em> produce a server-to-client slot update, so a give can be
 * confirmed by observing the inventory change.
 */
public final class CreativeInventoryHandler {
    /**
     * How an item is moved from the creative catalog into the world.
     *
     * <p>Both routes are real, server-performed actions; neither fabricates an
     * item client-side.
     */
    public enum Strategy {
        /**
         * A single creative drop packet naming the item.
         *
         * <p>Cheapest: one packet per drop, and the server both validates the item
         * and performs the drop. This is the default.
         */
        DIRECT("Direct drop"),
        /**
         * Give the item to a hotbar slot, then throw it on the following tick.
         *
         * <p>Two packets instead of one, and the drop goes through the same code
         * path as a normal player throwing an item, which some servers treat more
         * kindly.
         *
         * <p>The throw cannot go out in the same tick as the give. The client only
         * sends {@code UpdateSelectedSlotC2SPacket} from
         * {@code ClientPlayerInteractionManager.tick}, which runs before the
         * tick's packets are flushed, so a throw issued in the same tick would be
         * executed by the server against the <em>previous</em> slot and would
         * throw the wrong item. The give is therefore issued on one tick and the
         * throw on the next, which is what {@link #tick()} sequences.
         */
        HOTBAR("Hotbar then drop");

        private final String title;

        Strategy(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /**
     * The server's drop budget, from {@code ServerPlayNetworkHandler}: it adds 20
     * per tick and spends 20 per drop, refusing drops once it reaches 200. That
     * is ten drops per tick sustained, or 200 per second.
     */
    private static final int DROPS_PER_TICK_BUDGET = 10;

    private final MinecraftClient mc;

    /** Which route drops take. Settable so the user can pick one. */
    private Strategy strategy = Strategy.DIRECT;

    /** The hotbar slot the HOTBAR strategy uses. */
    private int hotbarSlot;

    /** Item given to the hotbar and awaiting its throw, or {@code null}. */
    private String pendingThrowItem;
    /** How many of {@link #pendingThrowItem} the throw should cover. */
    private int pendingThrowCount;
    /** Set on the tick after the give, when the server knows the selected slot. */
    private boolean throwReady;

    public CreativeInventoryHandler(MinecraftClient mc) {
        this.mc = mc;
    }

    /**
     * Advances the HOTBAR handshake. Called once per client tick, before the
     * scheduler runs, so the slot packet has already gone out by the time the
     * throw is dispatched.
     */
    public void tick() {
        if (pendingThrowItem != null && !throwReady) throwReady = true;
    }

    /** Abandons a half-finished HOTBAR handshake, used when the run stops. */
    public void cancelPending() {
        pendingThrowItem = null;
        pendingThrowCount = 0;
        throwReady = false;
    }

    /** True when a hotbar give is waiting to be thrown. */
    public boolean hasPendingThrow() {
        return pendingThrowItem != null;
    }

    public void strategy(Strategy strategy) {
        if (strategy != null) this.strategy = strategy;
    }

    public Strategy strategy() {
        return strategy;
    }

    /** Rotates the hotbar slot used by the HOTBAR strategy, spreading the churn. */
    public void cycleHotbarSlot() {
        hotbarSlot = (hotbarSlot + 1) % PlayerInventory.getHotbarSize();
    }

    public int hotbarSlot() {
        return hotbarSlot;
    }

    /**
     * True when the player can use the creative inventory right now.
     *
     * <p>Checked before every send: the server ignores creative packets from a
     * non-creative player, so sending them would just burn the drop budget.
     */
    public boolean isCreative() {
        ClientPlayerInteractionManager manager = mc.interactionManager;
        return manager != null && manager.hasCreativeInventory();
    }

    /** A snapshot of the state a send depends on, so callers can check before acting. */
    public boolean canAct() {
        return mc.player != null && mc.world != null && isCreative();
    }

    /**
     * Asks the server to drop one stack of {@code itemId}.
     *
     * <p>Returns only whether the packet was sent. The drop itself is unconfirmed
     * until the item entity appears; the caller records it with
     * {@link DropLedger#send} and waits.
     *
     * @return {@code false} when the item is unknown or the player is not in creative
     */
    public boolean dropStack(String itemId, int count) {
        if (!canAct() || count <= 0) return false;

        ItemStack stack = ItemResolver.stackFor(itemId, count);
        if (stack.isEmpty()) return false;

        ClientPlayerInteractionManager manager = mc.interactionManager;
        if (manager == null) return false;

        if (strategy == Strategy.HOTBAR) {
            return dropViaHotbar(itemId, count, stack);
        }

        manager.dropCreativeStack(stack);
        return true;
    }

    /**
     * Runs the two-tick hotbar handshake.
     *
     * <p>Returns {@code false} on the tick the give is issued, which tells the
     * scheduler nothing was sent yet and to ask again next tick. That is the same
     * path a rejected send already takes, so no new scheduling behaviour is
     * needed.
     *
     * @return true only on the tick the throw is actually dispatched
     */
    private boolean dropViaHotbar(String itemId, int count, ItemStack stack) {
        // A give is already outstanding for a different item. Let it be thrown
        // first rather than overwriting the slot and throwing the wrong thing.
        if (pendingThrowItem != null && !pendingThrowItem.equals(itemId)) return false;

        if (pendingThrowItem == null) {
            if (!giveStackToHotbar(stack, hotbarSlot)) return false;

            ClientPlayerEntity player = mc.player;
            if (player == null) return false;

            player.getInventory().selectedSlot = hotbarSlot;
            pendingThrowItem = itemId;
            pendingThrowCount = count;
            throwReady = false;
            return false;
        }

        // Same item, and the slot packet has had a tick to reach the server.
        if (!throwReady) return false;

        ClientPlayerEntity player = mc.player;
        if (player == null) {
            cancelPending();
            return false;
        }

        player.dropSelectedItem(true);

        pendingThrowItem = null;
        pendingThrowCount = 0;
        throwReady = false;
        cycleHotbarSlot();
        return true;
    }

    /**
     * Places an already-built stack into a hotbar slot.
     *
     * <p>The server writes into {@code playerScreenHandler} and sends a slot
     * update back, which is what {@link #hotbarMatches} observes.
     *
     * @param hotbarSlot 0-8
     */
    public boolean giveStackToHotbar(ItemStack stack, int hotbarSlot) {
        if (!canAct() || stack.isEmpty()) return false;
        if (hotbarSlot < 0 || hotbarSlot > 8) return false;

        ClientPlayerInteractionManager manager = mc.interactionManager;
        if (manager == null) return false;

        // Player screen handler slots 36-44 are the hotbar, so the slot id sent on
        // the wire is 36 + index.
        int serverSlot = PlayerInventory.getHotbarSize() * 3 + hotbarSlot;
        manager.clickCreativeStack(stack, serverSlot);
        return true;
    }
}
