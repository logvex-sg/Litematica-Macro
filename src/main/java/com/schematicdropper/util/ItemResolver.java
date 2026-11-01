package com.schematicdropper.util;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolves item ids to stacks.
 *
 * <p>Also owns the special cases that come from reading schematics: some block
 * states have no matching item at all, and some need a different item than their
 * own name suggests. Resolutions are cached because a large schematic asks the
 * same question millions of times.
 */
public final class ItemResolver {
    /** Blocks whose item is not simply the block of the same name. */
    private static final Map<String, Item> BLOCK_ITEM_OVERRIDES = Map.ofEntries(
        Map.entry("minecraft:water", Items.WATER_BUCKET),
        Map.entry("minecraft:lava", Items.LAVA_BUCKET),
        Map.entry("minecraft:powder_snow", Items.POWDER_SNOW_BUCKET),
        Map.entry("minecraft:redstone_wire", Items.REDSTONE),
        Map.entry("minecraft:redstone_torch", Items.REDSTONE_TORCH),
        Map.entry("minecraft:wall_torch", Items.TORCH),
        Map.entry("minecraft:soul_wall_torch", Items.SOUL_TORCH),
        Map.entry("minecraft:redstone_wall_torch", Items.REDSTONE_TORCH),
        Map.entry("minecraft:nether_portal", Items.AIR),
        Map.entry("minecraft:end_portal", Items.AIR),
        Map.entry("minecraft:end_gateway", Items.AIR),
        Map.entry("minecraft:fire", Items.AIR),
        Map.entry("minecraft:soul_fire", Items.AIR),
        Map.entry("minecraft:void_air", Items.AIR),
        Map.entry("minecraft:cave_air", Items.AIR),
        Map.entry("minecraft:moving_piston", Items.AIR),
        Map.entry("minecraft:bubble_column", Items.AIR),
        Map.entry("minecraft:structure_void", Items.AIR)
    );

    /**
     * Blocks that genuinely have no item form.
     *
     * <p>Kept deliberately short. This is a creative-mode tool, so blocks such as
     * barriers, command blocks and light blocks must stay in the list - they do
     * have items and are common in schematics. Anything not listed here is
     * resolved against the registry, where {@code asItem()} is authoritative.
     */
    private static final Map<String, Boolean> NO_ITEM_BLOCKS = Map.ofEntries(
        Map.entry("minecraft:air", true),
        Map.entry("minecraft:cave_air", true),
        Map.entry("minecraft:void_air", true),
        Map.entry("minecraft:fire", true),
        Map.entry("minecraft:soul_fire", true),
        Map.entry("minecraft:nether_portal", true),
        Map.entry("minecraft:end_portal", true),
        Map.entry("minecraft:end_gateway", true),
        Map.entry("minecraft:moving_piston", true),
        Map.entry("minecraft:piston_head", true),
        Map.entry("minecraft:bubble_column", true),
        Map.entry("minecraft:water", true),
        Map.entry("minecraft:lava", true),
        Map.entry("minecraft:powder_snow", true)
    );

    private static final Map<String, Item> CACHE = new HashMap<>();

    private ItemResolver() {
    }

    /**
     * Finds the item registered under {@code id}.
     *
     * @return the item, or {@code null} when this client has no such item
     */
    public static Item findItem(String id) {
        if (id == null || id.isBlank()) return null;

        Item cached = CACHE.get(id);
        if (cached != null) return cached == Items.AIR ? null : cached;

        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null || !Registries.ITEM.containsId(identifier)) {
            // Remember misses too; a big schematic asks about the same missing id often.
            CACHE.put(id, Items.AIR);
            return null;
        }

        Item item = Registries.ITEM.get(identifier);
        CACHE.put(id, item);
        return item;
    }

    public static boolean exists(String id) {
        return findItem(id) != null;
    }

    /** True when the id names air or another block that cannot be obtained as an item. */
    public static boolean isNoItemBlock(String id) {
        return id != null && NO_ITEM_BLOCKS.containsKey(id);
    }

    /**
     * The item a block id corresponds to.
     *
     * <p>Falls back to the block's own item, then to an explicit override for the
     * handful of blocks whose item differs.
     *
     * @return the item, or {@code null} when the block yields nothing placeable
     */
    public static Item itemForBlock(String blockId) {
        if (blockId == null || isNoItemBlock(blockId)) return null;

        Item direct = findItem(blockId);
        if (direct != null) return direct;

        return BLOCK_ITEM_OVERRIDES.get(blockId);
    }

    /** A single stack of {@code id}, or an empty stack when it cannot be resolved. */
    public static ItemStack stackFor(String id, int count) {
        Item item = findItem(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, count);
    }

    /** Clears the resolution cache, used when the connection's registries change. */
    public static void clearCache() {
        CACHE.clear();
    }
}
