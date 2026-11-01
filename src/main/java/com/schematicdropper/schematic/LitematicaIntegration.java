package com.schematicdropper.schematic;

import net.fabricmc.loader.api.FabricLoader;

import java.util.List;
import java.util.Map;

/**
 * Reads materials from a schematic loaded in Litematica.
 *
 * <p>Litematica is an optional dependency, so nothing in this class touches a
 * Litematica type directly. Every reference lives in {@link Bridge}, which is
 * only class-loaded once {@link #isAvailable()} has confirmed the mod is present.
 * That keeps the addon loadable on a client without Litematica installed.
 */
public final class LitematicaIntegration {
    private static final String MOD_ID = "litematica";

    private LitematicaIntegration() {
    }

    /** True when Litematica is installed. */
    public static boolean isAvailable() {
        try {
            return FabricLoader.getInstance().isModLoaded(MOD_ID);
        } catch (Throwable t) {
            // A missing loader at class-init time must not break the module.
            return false;
        }
    }

    /** True when Litematica is installed and a placement is selected. */
    public static boolean hasSelectedPlacement() {
        if (!isAvailable()) return false;
        try {
            return Bridge.selectedPlacementName() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The name of the selected placement, or {@code null} when there is none.
     *
     * <p>Shown in the module's info string so the user can see what will be read.
     */
    public static String selectedPlacementName() {
        if (!isAvailable()) return null;
        try {
            return Bridge.selectedPlacementName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Names of every loaded placement, for a selection dropdown. */
    public static List<String> placementNames() {
        if (!isAvailable()) return List.of();
        try {
            return Bridge.placementNames();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Extracts the materials of the selected placement.
     *
     * <p>Litematica's own material list is used rather than walking the block
     * container, because it already handles the awkward cases: waterlogged blocks
     * needing a bucket, and blocks such as doors and slabs that yield more than
     * one item per block. Air is excluded by that conversion.
     *
     * <p>Must be called on the client thread: the conversion consults the local
     * player's inventory.
     *
     * @throws SchematicReader.SchematicException when Litematica is missing or
     *         nothing is selected
     */
    public static SchematicReader.Result readSelectedPlacement() throws SchematicReader.SchematicException {
        if (!isAvailable()) {
            throw new SchematicReader.SchematicException("Litematica is not installed");
        }

        try {
            return Bridge.readSelectedPlacement();
        } catch (SchematicReader.SchematicException e) {
            throw e;
        } catch (Throwable t) {
            throw new SchematicReader.SchematicException("Failed to read the Litematica placement: " + t, t);
        }
    }

    /**
     * Isolates every Litematica type reference.
     *
     * <p>Class loading is lazy, so this class is only resolved when one of the
     * guarded methods above actually runs, which only happens once the mod is
     * known to be present.
     */
    private static final class Bridge {
        private Bridge() {
        }

        static Object selectedPlacement() {
            var manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            return manager == null ? null : manager.getSelectedSchematicPlacement();
        }

        static String selectedPlacementName() {
            Object placement = selectedPlacement();
            if (!(placement instanceof fi.dy.masa.litematica.schematic.placement.SchematicPlacement typed)) {
                return null;
            }
            if (!typed.isEnabled()) return null;

            var schematic = typed.getSchematic();
            if (schematic == null) return null;

            String name = typed.getName();
            return name == null || name.isBlank() ? "unnamed" : name;
        }

        static List<String> placementNames() {
            var manager = fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();
            if (manager == null) return List.of();

            List<String> names = new java.util.ArrayList<>();
            for (var placement : manager.getAllSchematicsPlacements()) {
                if (placement == null) continue;
                String name = placement.getName();
                names.add(name == null || name.isBlank() ? "unnamed" : name);
            }
            return names;
        }

        static SchematicReader.Result readSelectedPlacement() throws SchematicReader.SchematicException {
            Object placementObject = selectedPlacement();
            if (!(placementObject instanceof fi.dy.masa.litematica.schematic.placement.SchematicPlacement placement)) {
                throw new SchematicReader.SchematicException("No Litematica placement is selected");
            }

            var schematic = placement.getSchematic();
            if (schematic == null) {
                throw new SchematicReader.SchematicException("The selected placement has no schematic");
            }

            // Only the regions the placement has enabled. Reading every area would
            // count regions the user has switched off, which is not what the
            // placement is going to build.
            var enabledRegions = placement.getEnabledRelativeSubRegionPlacements();
            if (enabledRegions == null || enabledRegions.isEmpty()) {
                throw new SchematicReader.SchematicException("The placement has no enabled regions");
            }

            List<fi.dy.masa.litematica.materials.MaterialListEntry> entries =
                fi.dy.masa.litematica.materials.MaterialListUtils.createMaterialListFor(
                    schematic, enabledRegions.keySet());

            if (entries == null || entries.isEmpty()) {
                throw new SchematicReader.SchematicException("The schematic contains no placeable blocks");
            }

            Map<String, Integer> materials = new java.util.LinkedHashMap<>();
            List<String> warnings = new java.util.ArrayList<>();
            int unresolved = 0;

            for (var entry : entries) {
                if (entry == null) continue;

                var stack = entry.getStack();
                int count = entry.getCountTotal();

                if (stack == null || stack.isEmpty() || count <= 0) continue;

                var itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                if (itemId == null) {
                    unresolved++;
                    continue;
                }

                materials.merge(itemId.toString(), count, Integer::sum);
            }

            if (unresolved > 0) {
                warnings.add(unresolved + " entr" + (unresolved == 1 ? "y" : "ies")
                    + " could not be mapped to an item and were ignored");
            }

            if (materials.isEmpty()) {
                throw new SchematicReader.SchematicException("The schematic contains no placeable blocks");
            }

            long totalBlocks = 0;
            for (int count : materials.values()) totalBlocks += count;

            return new SchematicReader.Result(materials, enabledRegions.size(), totalBlocks, 0, warnings);
        }
    }
}
