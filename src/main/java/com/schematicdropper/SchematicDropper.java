package com.schematicdropper;

import com.schematicdropper.drop.CreativeInventoryHandler;
import com.schematicdropper.drop.DropTargetManager;
import com.schematicdropper.drop.DropTask;
import com.schematicdropper.gui.SchematicDropperScreen;
import com.schematicdropper.hud.SchematicDropperHud;
import com.schematicdropper.material.ImportFormat;
import com.schematicdropper.material.MaterialListParser;
import com.schematicdropper.material.MaterialManager;
import com.schematicdropper.schematic.LitematicaIntegration;
import com.schematicdropper.schematic.SchematicReader;
import com.schematicdropper.util.ItemResolver;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drops the exact material quantities a schematic or material list requires.
 *
 * <h2>How a drop is confirmed</h2>
 * Sending a creative drop packet does not create the item - the server decides
 * whether to accept it, and it tells the client nothing either way. The only
 * evidence a drop actually happened is the item entity the server spawns and
 * sends back. So nothing is counted here until that entity is seen, via
 * {@link EntityAddedEvent}. A drop the server silently refuses therefore shows
 * up as a material that never progresses, and after a few retries the run stops
 * with an explanation instead of quietly over- or under-dropping.
 *
 * <h2>Threading</h2>
 * Reading a schematic is CPU-bound and can take seconds, so it runs on a worker
 * thread and the result is handed back to the client thread. Nothing here touches
 * the world off-thread.
 */
public class SchematicDropper extends Module {
    /** Shared worker for file parsing; daemon so it never blocks shutdown. */
    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SchematicDropper-IO");
        thread.setDaemon(true);
        return thread;
    });

    private final SchematicDropperSettings settings = new SchematicDropperSettings(this);
    private final MaterialManager materials = new MaterialManager();
    private final CreativeInventoryHandler inventory = new CreativeInventoryHandler(mc);
    private final DropTargetManager targets = new DropTargetManager(mc);

    private final DropTask.Config config = new DropTask.Config();
    private final DropTask task;

    /** Guards against overlapping imports, and is the token for discarding stale results. */
    private final AtomicInteger importGeneration = new AtomicInteger();

    /**
     * How close an item entity must spawn to count as one of ours, in blocks.
     *
     * <p>Generous enough to cover a drop thrown toward a target a few blocks away,
     * tight enough that another player's drop across the room is not mistaken for
     * one of ours.
     */
    private static final double OBSERVATION_RADIUS = 8.0;

    /** Counts client ticks, used for drop confirmation timeouts. */
    private long tickCounter;

    /** The entity targeted when the run started, for the target-change notification. */
    private Entity lastNotifiedTarget;

    public SchematicDropper() {
        super(SchematicDropperAddon.CATEGORY, "schematic-dropper",
            "Drops the exact material quantities a schematic requires.");

        this.task = new DropTask(materials, new SchedulerActions());
    }

    public MaterialManager materials() {
        return materials;
    }

    public DropTask task() {
        return task;
    }

    public SchematicDropperSettings options() {
        return settings;
    }

    public DropTargetManager targetManager() {
        return targets;
    }

    // ----------------------------------------------------------- MODULE HOOKS

    @Override
    public void onActivate() {
        tickCounter = 0;
        lastNotifiedTarget = null;

        if (materials.isEmpty()) {
            importFromConfiguredSource(false);
        }
    }

    @Override
    public void onDeactivate() {
        // Stop cleanly so nothing is left half-applied, and so no task can keep
        // running after the module is switched off.
        task.stop();
        task.reset();
        inventory.cancelPending();
        targets.clearLock();
        lastNotifiedTarget = null;

        if (settings.hudEnabled.get()) {
            SchematicDropperHud.removeElement();
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WButton open = list.add(theme.button("Open Material Manager")).expandX().widget();
        open.action = () -> SchematicDropperScreen.open(theme, this);

        return list;
    }

    @Override
    public String getInfoString() {
        return switch (task.state()) {
            case RUNNING, PAUSED -> (int) Math.round(materials.overallProgress() * 100) + "%";
            case COMPLETED -> "done";
            case ERROR -> "error";
            default -> materials.isEmpty() ? null : materials.size() + " mats";
        };
    }

    // ------------------------------------------------------------ EVENT WIRING

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tickCounter++;

        if (mc.player == null || mc.world == null) return;

        settings.applyTo(config, materials);
        task.config(config);
        inventory.strategy(settings.inventoryStrategy.get());
        // Advances the hotbar give/throw handshake, which needs one tick between
        // the slot packet and the throw.
        inventory.tick();

        if (settings.hudEnabled.get()) {
            SchematicDropperHud.ensureElement();
            SchematicDropperHud.applyOptions(
                settings.hudScale.get(),
                settings.hudBackgroundOpacity.get(),
                settings.hudCompact.get(),
                settings.hudOnlyRemaining.get(),
                settings.hudShowTarget.get());
        }

        if (task.isRunning() && !inventory.isCreative()) {
            // The server ignores creative packets from a non-creative player, so
            // carrying on would only produce unconfirmable drops.
            task.abortForWorldChange("Creative mode is required; stopping");
            reportNotices();
            return;
        }

        updateTargeting();
        task.tick(tickCounter, System.currentTimeMillis());
        reportNotices();

        if (settings.hudEnabled.get()) {
            SchematicDropperHud.refresh(task, materials, currentTargetLabel());
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof ItemEntity itemEntity)) return;
        if (task.state() != DropTask.State.RUNNING && task.state() != DropTask.State.PAUSED) return;
        if (mc.player == null) return;

        // Only items that appear close to the player can be ours. Without this,
        // another player dropping the same item - or a mob dying and dropping it -
        // would be credited as a drop of ours, and the run would think a material
        // was delivered that never was.
        double distance = itemEntity.squaredDistanceTo(mc.player);
        if (distance > OBSERVATION_RADIUS * OBSERVATION_RADIUS) return;

        ItemStack stack = itemEntity.getStack();
        if (stack.isEmpty()) return;

        Item item = stack.getItem();
        Identifier id = Registries.ITEM.getId(item);
        if (id == null) return;

        // This is the only place progress increases.
        task.onItemObserved(id.toString(), stack.getCount());
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (task.state() == DropTask.State.RUNNING || task.state() == DropTask.State.PAUSED) {
            task.abortForWorldChange("Disconnected; the drop was stopped");
            reportNotices();
        }

        // Registry lookups are per-connection, so the cache must not survive a
        // reconnect into a world with a different mod set.
        ItemResolver.clearCache();
        targets.clearLock();
        lastNotifiedTarget = null;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        ItemResolver.clearCache();

        if (task.requiresRestart()) {
            // Deliberately not restarting: the user must confirm.
            info("Drop was interrupted by a disconnect. Press Start to resume.");
        }
    }

    // ------------------------------------------------------------- TARGETING

    private void updateTargeting() {
        DropTargetManager.Mode mode = settings.targetMode.get();

        if (mode != DropTargetManager.Mode.LOOKED_AT_ENTITY) {
            targets.clearLock();
            lastNotifiedTarget = null;
            return;
        }

        if (task.isRunning() && settings.lockTarget.get() && !targets.isLocked()) {
            DropTargetManager.Target target = targets.resolve(mode, settings.targetRange.get());
            if (target.isOk() && target.entity() != null) {
                targets.lock(target.entity());
                lastNotifiedTarget = target.entity();
                info("Target locked: %s", target.entity().getName().getString());
            }
        }

        DropTargetManager.Target target = targets.resolve(mode, settings.targetRange.get());

        // Aim every tick, so the server's copy of the view angle is already on the
        // target by the time a drop is dispatched.
        if (task.isRunning() && target.isOk() && target.entity() != null) {
            aimAt(target.entity());
        }

        if (settings.notifyTargetChange.get() && task.isRunning()) {
            Entity current = target.isOk() ? target.entity() : null;

            if (current != lastNotifiedTarget) {
                if (current == null) {
                    warning("No target in range; drops are paused until one is found");
                } else {
                    info("Target: %s", current.getName().getString());
                }
                lastNotifiedTarget = current;
            }
        }
    }

    /** A short description of where drops are currently going, for the HUD. */
    public String currentTargetLabel() {
        DropTargetManager.Mode mode = settings.targetMode.get();
        if (mode == DropTargetManager.Mode.FEET) return "Feet";

        DropTargetManager.Target target = targets.resolve(mode, settings.targetRange.get());
        return target.isOk() ? target.describe() : "no target";
    }

    // ---------------------------------------------------------------- ACTIONS

    /**
     * Bridges the scheduler to the game.
     *
     * <p>A creative drop travels along the player's view vector, so throwing at a
     * target is a matter of where the player is aimed. The aim itself is
     * maintained by {@link #updateTargeting()} on every tick; by the time a send
     * reaches here the server is already looking at the target.
     */
    private final class SchedulerActions implements DropTask.Actions {
        @Override
        public boolean dropAtFeet(String itemId, int count) {
            return inventory.dropStack(itemId, count);
        }

        @Override
        public boolean throwAtTarget(String itemId, int count) {
            DropTargetManager.Target target =
                targets.resolve(settings.targetMode.get(), settings.targetRange.get());

            if (!target.isOk()) {
                // No valid target: report it rather than silently dropping at the
                // player's feet, which is not what the user asked for.
                return false;
            }

            return inventory.dropStack(itemId, count);
        }

        @Override
        public int maxStackSize(String itemId) {
            Item item = ItemResolver.findItem(itemId);
            return item == null ? 64 : Math.max(1, item.getMaxCount());
        }
    }

    /**
     * Points the player at an entity so a drop travels toward it.
     *
     * <p>The rotation goes through Meteor's rotation queue, which rewrites the
     * outgoing movement packet. That packet is sent during the client tick, before
     * this module's tick handler runs, so a rotation set here only reaches the
     * server on the <em>following</em> tick - while a drop packet queued here goes
     * out at the end of this one.
     *
     * <p>That is why {@link #updateTargeting()} aims on every tick rather than
     * only on the tick a drop happens: by the time a drop is dispatched, the aim
     * set on the previous tick is already the server's current view angle.
     */
    private void aimAt(Entity entity) {
        if (mc.player == null) return;

        double dx = entity.getX() - mc.player.getX();
        double dy = entity.getEyeY() - mc.player.getEyeY();
        double dz = entity.getZ() - mc.player.getZ();

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        double pitch = -Math.toDegrees(Math.atan2(dy, horizontal));

        Rotations.rotate(yaw, pitch);
    }

    // ---------------------------------------------------------------- IMPORT

    /** Re-reads whatever the configured source points at. */
    public void importFromConfiguredSource(boolean announce) {
        SchematicDropperSettings.Source source = settings.source.get();

        switch (source) {
            case LITEMATICA -> importFromLitematica(announce);
            case SCHEMATIC_FILE -> importSchematicFile(Path.of(settings.schematicPath.get()), announce);
            case MATERIAL_LIST -> importMaterialList(Path.of(settings.materialListPath.get()), announce);
        }
    }

    /** Reads the selected Litematica placement. Runs on the client thread. */
    public void importFromLitematica(boolean announce) {
        if (!LitematicaIntegration.isAvailable()) {
            error("Litematica is not installed");
            return;
        }

        try {
            SchematicReader.Result result = LitematicaIntegration.readSelectedPlacement();
            applyResult(result, "Litematica placement", announce);
        } catch (SchematicReader.SchematicException e) {
            error("%s", e.getMessage());
        }
    }

    /**
     * Reads a schematic file on a worker thread.
     *
     * <p>The generation counter makes a superseded import a no-op, so a slow read
     * cannot overwrite the result of a newer one.
     */
    public void importSchematicFile(Path path, boolean announce) {
        if (path == null || path.toString().isBlank()) {
            error("No schematic file selected");
            return;
        }

        int generation = importGeneration.incrementAndGet();
        String name = path.getFileName().toString();
        info("Reading %s...", name);

        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return SchematicReader.read(path);
                } catch (SchematicReader.SchematicException e) {
                    throw new ImportFailure(e.getMessage());
                }
            }, IO_EXECUTOR)
            .whenComplete((result, error) -> mc.execute(() -> {
                if (importGeneration.get() != generation) return;

                if (error != null) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    error("Import failed: %s", cause.getMessage());
                    return;
                }

                applyResult(result, name, announce);
            }));
    }

    /** Parses a material list file. Small enough to read inline. */
    public void importMaterialList(Path path, boolean announce) {
        if (path == null || path.toString().isBlank()) {
            error("No material list selected");
            return;
        }

        ImportFormat format = ImportFormat.fromFileName(path.getFileName().toString());
        if (format == null) {
            error("Unsupported list format. Use %s", ImportFormat.allFilters());
            return;
        }

        MaterialListParser.Result parsed;
        try {
            if (!Files.isReadable(path)) {
                error("Cannot read %s", path.getFileName());
                return;
            }
            parsed = MaterialListParser.parseFile(path);
        } catch (RuntimeException e) {
            error("Import failed: %s", e.getMessage());
            return;
        }

        for (String problem : parsed.errors()) {
            error("%s", problem);
        }
        for (String warning : parsed.warnings()) {
            warning("%s", warning);
        }

        if (parsed.isEmpty()) {
            error("No valid materials found in %s", path.getFileName());
            return;
        }

        applyMaterials(parsed.materials(), path.getFileName().toString(), announce);
    }

    /** Applies a parsed schematic result. Must run on the client thread. */
    private void applyResult(SchematicReader.Result result, String sourceName, boolean announce) {
        for (String warning : result.warnings()) {
            warning("%s", warning);
        }

        applyMaterials(result.materials(), sourceName, announce);

        if (announce && !result.isEmpty()) {
            info("Read %d region(s), %d blocks from %s",
                result.regions(), result.totalBlocks(), sourceName);
        }
    }

    private void applyMaterials(Map<String, Integer> materialsMap, String sourceName, boolean announce) {
        task.stop();
        materials.setFrom(materialsMap);

        if (settings.ignoreAir.get()) {
            materials.removeNoItemBlocks(ItemResolver::isNoItemBlock);
        }

        int missing = task.validateMaterials(ItemResolver::exists);

        if (materials.isEmpty()) {
            error("Nothing to drop from %s", sourceName);
            return;
        }

        if (announce) {
            info("Loaded %d material(s), %d items from %s",
                materials.size(), materials.totalRequired(), sourceName);
            if (missing > 0) {
                warning("%d material(s) have no matching item and will be skipped", missing);
            }
        }
    }

    // ---------------------------------------------------------------- CONTROL

    /** Starts the drop operation. */
    public void start() {
        if (materials.isEmpty()) {
            error("No materials loaded");
            return;
        }

        if (!inventory.isCreative()) {
            error("Creative mode is required");
            return;
        }

        if (settings.targetMode.get() == DropTargetManager.Mode.LOOKED_AT_ENTITY) {
            DropTargetManager.Target target =
                targets.resolve(settings.targetMode.get(), settings.targetRange.get());

            if (!target.isOk()) {
                // Refuse rather than silently dropping at the feet.
                error("No target entity in range");
                return;
            }
            if (settings.lockTarget.get()) targets.lock(target.entity());
        }

        settings.applyTo(config, materials);
        task.config(config);
        inventory.strategy(settings.inventoryStrategy.get());

        if (task.start()) {
            info("Dropping %d items across %d material(s)", materials.totalRemaining(), materials.size());
        } else {
            warning("A drop is already running");
        }
    }

    public void pause() {
        if (task.pause()) info("Paused");
    }

    public void resume() {
        if (task.resume()) info("Resumed");
    }

    public void stop() {
        task.stop();
        inventory.cancelPending();
        reportNotices();
    }

    /** Clears the material list and any progress. */
    public void clear() {
        task.stop();
        task.reset();
        materials.clear();
        info("Material list cleared");
    }

    /** Reloads from the configured source. */
    public void refresh() {
        importFromConfiguredSource(true);
    }

    /** Surfaces scheduler notices as chat messages. */
    private void reportNotices() {
        for (DropTask.Notice notice : task.drainNotices()) {
            switch (notice.kind()) {
                case COMPLETED -> info("%s", notice.message());
                case STALLED, ERROR -> error("%s", notice.message());
                case MISSING_ITEM, CANCELLED -> warning("%s", notice.message());
            }
        }
    }

    /** Wraps a reader failure so it survives the worker-thread hop. */
    private static final class ImportFailure extends RuntimeException {
        ImportFailure(String message) {
            super(message);
        }
    }

    /** Convenience for the module screen: the placements Litematica has loaded. */
    public List<String> availablePlacements() {
        return new ArrayList<>(LitematicaIntegration.placementNames());
    }
}
