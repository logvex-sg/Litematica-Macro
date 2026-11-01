package com.schematicdropper.schematic;

import com.schematicdropper.material.MaterialIds;
import com.schematicdropper.util.ItemResolver;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads schematic files into a material list.
 *
 * <p>Handles the two formats that can be decoded reliably client-side:
 * Litematica's own {@code .litematic} and the Sponge {@code .schem}. Both store
 * a palette plus a packed index array, so they share the same decoding path.
 *
 * <p>WorldEdit's legacy {@code .schematic} is reported as unsupported rather than
 * being guessed at: it uses a flat numeric block id table whose mapping to modern
 * block states is not recoverable from the file alone.
 *
 * <p>This class is Minecraft-facing but never touches the world, so it is safe to
 * call from a worker thread. Only registry lookups and string parsing happen here.
 */
public final class SchematicReader {
    /** Refuses absurd dimensions before allocating anything. */
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_VOLUME = 64L * 1024 * 1024;

    private SchematicReader() {
    }

    /** A parsed schematic. */
    public record Result(Map<String, Integer> materials,
                         int regions,
                         long totalBlocks,
                         long skippedBlocks,
                         List<String> warnings) {
        public boolean isEmpty() {
            return materials.isEmpty();
        }
    }

    /** Raised when a file cannot be read; the message is user-facing. */
    public static final class SchematicException extends Exception {
        public SchematicException(String message) {
            super(message);
        }

        public SchematicException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The schematic formats this module can read. */
    public enum Format {
        LITEMATIC("litematic", true),
        SPONGE("schem", true),
        SPONGE_ALT("nbt", true),
        LEGACY("schematic", false);

        private final String extension;
        private final boolean supported;

        Format(String extension, boolean supported) {
            this.extension = extension;
            this.supported = supported;
        }

        public boolean isSupported() {
            return supported;
        }

        public static Format fromFileName(String fileName) {
            if (fileName == null) return null;
            int dot = fileName.lastIndexOf('.');
            if (dot < 0 || dot == fileName.length() - 1) return null;

            String ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            for (Format format : values()) {
                if (format.extension.equals(ext)) return format;
            }
            return null;
        }

        /** Filter string for a file dialog. */
        public static String supportedFilters() {
            return "*.litematic;*.schem";
        }
    }

    /**
     * Reads a schematic file.
     *
     * @throws SchematicException when the file is missing, unsupported, or corrupt
     */
    public static Result read(Path path) throws SchematicException {
        if (path == null) throw new SchematicException("No file selected");
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new SchematicException("Cannot read " + path.getFileName());
        }

        Format format = Format.fromFileName(path.getFileName().toString());
        if (format == null) {
            throw new SchematicException("Unsupported file type: " + path.getFileName()
                + " (expected " + Format.supportedFilters() + ")");
        }
        if (!format.isSupported()) {
            throw new SchematicException("The legacy .schematic format is not supported; "
                + "re-save the schematic as .litematic or .schem");
        }

        NbtCompound root;
        try {
            // Bounded so a malformed file cannot exhaust memory.
            root = NbtIo.readCompressed(path, new NbtSizeTracker(256L * 1024 * 1024, 512));
        } catch (IOException | RuntimeException e) {
            throw new SchematicException("Failed to read " + path.getFileName() + ": " + e.getMessage(), e);
        }

        return read(root, format);
    }

    /** Reads an already-decoded NBT tree. Separated so it can be exercised in tests. */
    public static Result read(NbtCompound root, Format format) throws SchematicException {
        if (root == null) throw new SchematicException("Schematic file is empty");

        return switch (format) {
            case LITEMATIC -> readLitematic(root);
            case SPONGE, SPONGE_ALT -> readSponge(root);
            case LEGACY -> throw new SchematicException("The legacy .schematic format is not supported");
        };
    }

    // ------------------------------------------------------------- LITEMATIC

    /**
     * Reads a Litematica schematic.
     *
     * <p>Structure: {@code Regions -> <name> -> {BlockStatePalette, BlockStates, Size}}.
     * Every region is read, so multi-region schematics report their full material
     * requirement, and duplicate block types across regions are summed by the
     * shared extractor.
     */
    private static Result readLitematic(NbtCompound root) throws SchematicException {
        if (!root.contains("Regions", NbtElement.COMPOUND_TYPE)) {
            throw new SchematicException("Not a Litematica schematic: no Regions tag");
        }

        NbtCompound regions = root.getCompound("Regions");
        List<String> warnings = new ArrayList<>();

        Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        int regionCount = 0;
        long totalBlocks = 0;
        long skipped = 0;

        for (String regionName : regions.getKeys()) {
            NbtElement element = regions.get(regionName);
            if (element == null || element.getType() != NbtElement.COMPOUND_TYPE) continue;

            NbtCompound region = regions.getCompound(regionName);
            long[] blockStates = region.getLongArray("BlockStates");
            NbtList palette = region.getList("BlockStatePalette", NbtElement.COMPOUND_TYPE);

            if (blockStates.length == 0 || palette.isEmpty()) {
                warnings.add("Region '" + regionName + "' has no block data; skipped");
                continue;
            }

            int[] size = readSize(region.getCompound("Size"));
            long volume = (long) size[0] * size[1] * size[2];

            if (volume <= 0) {
                warnings.add("Region '" + regionName + "' has an invalid size; skipped");
                continue;
            }
            if (volume > MAX_VOLUME) {
                throw new SchematicException("Region '" + regionName + "' is too large (" + volume + " blocks)");
            }

            List<String> paletteItems = resolvePalette(palette, warnings, regionName);
            if (paletteItems.isEmpty()) {
                warnings.add("Region '" + regionName + "' has an empty palette; skipped");
                continue;
            }

            int bits = BitPackedArray.bitsFor(paletteItems.size());
            BitPackedArray array = new BitPackedArray(bits, volume, blockStates);

            RegionCounts counted = countRegion(array, volume, paletteItems, warnings, regionName);
            merge(totals, counted.materials());

            totalBlocks += volume;
            skipped += counted.skipped();
            regionCount++;
        }

        if (regionCount == 0) {
            throw new SchematicException("No readable regions found in the schematic");
        }

        return new Result(totals, regionCount, totalBlocks, skipped, warnings);
    }

    /** Materials and skipped-block count for one region. */
    private record RegionCounts(Map<String, Integer> materials, long skipped) {
    }

    /**
     * Counts one region's blocks.
     *
     * <p>Reads the packed array directly rather than materialising block states,
     * so memory use does not scale with the schematic volume.
     */
    private static RegionCounts countRegion(BitPackedArray array, long volume, List<String> paletteItems,
                                            List<String> warnings, String regionName) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        long skipped = 0;

        for (long index = 0; index < volume; index++) {
            int paletteIndex = array.getAt(index);

            if (paletteIndex < 0 || paletteIndex >= paletteItems.size()) {
                skipped++;
                continue;
            }

            String itemId = paletteItems.get(paletteIndex);
            if (itemId == null) {
                // Air and other item-less blocks: counted as skipped, not reported.
                skipped++;
                continue;
            }

            counts.merge(itemId, 1, Integer::sum);
        }

        if (skipped > 0) {
            warnings.add("Region '" + regionName + "': " + skipped + " block(s) were air or had no item");
        }

        return new RegionCounts(counts, skipped);
    }

    private static void merge(Map<String, Integer> target, Map<String, Integer> source) {
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            target.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    /** Reads a {@code Size} tag, which is stored as a block pos with x/y/z ints. */
    private static int[] readSize(NbtCompound size) {
        if (size == null || size.isEmpty()) return new int[]{0, 0, 0};

        int x = Math.abs(size.getInt("x"));
        int y = Math.abs(size.getInt("y"));
        int z = Math.abs(size.getInt("z"));

        if (x > MAX_DIMENSION || y > MAX_DIMENSION || z > MAX_DIMENSION) return new int[]{0, 0, 0};

        return new int[]{x, y, z};
    }

    /**
     * Converts a Litematica block state palette into item ids.
     *
     * <p>Each palette entry is a block state tag. Blocks that yield no item (air,
     * portal blocks, technical blocks) become {@code null} so they are skipped
     * rather than being reported as a material the player can never obtain.
     */
    private static List<String> resolvePalette(NbtList palette, List<String> warnings, String regionName) {
        List<String> items = new ArrayList<>(palette.size());
        int unresolved = 0;

        for (int i = 0; i < palette.size(); i++) {
            NbtCompound stateTag = palette.getCompound(i);
            String itemId = itemIdForStateTag(stateTag);

            if (itemId == null) {
                items.add(null);
                unresolved++;
            } else {
                items.add(itemId);
            }
        }

        if (unresolved > 0) {
            warnings.add("Region '" + regionName + "': " + unresolved
                + " palette entr" + (unresolved == 1 ? "y" : "ies") + " produce no item and were ignored");
        }

        return items;
    }

    /**
     * Maps one block state tag to an item id.
     *
     * <p>Parses the state through the vanilla block argument parser so the block
     * and its properties resolve exactly as they would in a command, then asks
     * {@link ItemResolver} for the item.
     */
    private static String itemIdForStateTag(NbtCompound stateTag) {
        String name = stateTag.getString("Name");
        if (name.isEmpty()) return null;

        // The raw name is enough to reject air and other item-less blocks without
        // paying for a full parse.
        String normalized = MaterialIds.normalize(name);
        if (normalized == null || ItemResolver.isNoItemBlock(normalized)) return null;

        BlockState state = parseState(stateTag, normalized);
        if (state == null) {
            // Unknown block (typically a mod that is not installed). Fall back to
            // the raw name so a plausible item id is still reported.
            return ItemResolver.exists(normalized) ? normalized : null;
        }

        Item item = state.getBlock().asItem();
        if (item == net.minecraft.item.Items.AIR) {
            // No item form; try the override table before giving up.
            item = ItemResolver.itemForBlock(normalized);
        }

        if (item == null || item == net.minecraft.item.Items.AIR) return null;

        Identifier id = Registries.ITEM.getId(item);
        return id == null ? null : id.toString();
    }

    /** Rebuilds a block state from its NBT form, or {@code null} when it is unknown here. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState parseState(NbtCompound stateTag, String normalizedName) {
        Identifier blockId = Identifier.tryParse(normalizedName);
        if (blockId == null || !Registries.BLOCK.containsId(blockId)) return null;

        Block block = Registries.BLOCK.get(blockId);
        BlockState state = block.getDefaultState();

        NbtCompound properties = stateTag.getCompound("Properties");
        if (properties.isEmpty()) return state;

        for (String key : properties.getKeys()) {
            Property<?> property = block.getStateManager().getProperty(key);
            if (property == null) continue;

            String raw = properties.getString(key);
            Optional<?> value = property.parse(raw);
            if (value.isEmpty()) continue;

            state = state.with((Property) property, (Comparable) value.get());
        }

        return state;
    }

    // ---------------------------------------------------------------- SPONGE

    /**
     * Reads a Sponge {@code .schem}.
     *
     * <p>Version 3 nests the palette and data under {@code Blocks}; versions 1 and
     * 2 keep them at the root as {@code Palette} and {@code BlockData}.
     */
    private static Result readSponge(NbtCompound root) throws SchematicException {
        List<String> warnings = new ArrayList<>();

        NbtCompound blocksTag;
        NbtCompound paletteTag;
        byte[] data;

        int version = root.contains("Version", NbtElement.INT_TYPE) ? root.getInt("Version") : 2;

        if (version >= 3) {
            if (!root.contains("Blocks", NbtElement.COMPOUND_TYPE)) {
                throw new SchematicException("Not a Sponge schematic: version 3 has no Blocks tag");
            }
            blocksTag = root.getCompound("Blocks");
            paletteTag = blocksTag.getCompound("Palette");
            data = blocksTag.getByteArray("Data");
        } else {
            blocksTag = root;
            paletteTag = root.getCompound("Palette");
            data = root.getByteArray("BlockData");
        }

        if (paletteTag.isEmpty() || data.length == 0) {
            throw new SchematicException("Sponge schematic has no palette or block data");
        }

        int[] size = readSpongeSize(root);
        long volume = (long) size[0] * size[1] * size[2];

        if (volume <= 0) throw new SchematicException("Sponge schematic has an invalid size");
        if (volume > MAX_VOLUME) throw new SchematicException("Schematic is too large (" + volume + " blocks)");

        // The palette maps a block state string to its index, so the id list must
        // be rebuilt in index order rather than read sequentially.
        int paletteSize = paletteTag.getKeys().size();
        List<String> paletteItems = new ArrayList<>(java.util.Collections.nCopies(paletteSize, null));
        int unresolved = 0;

        for (String key : paletteTag.getKeys()) {
            int index = paletteTag.getInt(key);
            if (index < 0 || index >= paletteSize) {
                warnings.add("Palette entry '" + key + "' has an invalid index " + index);
                continue;
            }

            String itemId = itemIdForStateString(key);
            if (itemId == null) {
                unresolved++;
                continue;
            }
            paletteItems.set(index, itemId);
        }

        if (unresolved > 0) {
            warnings.add(unresolved + " palette entr" + (unresolved == 1 ? "y" : "ies")
                + " produce no item and were ignored");
        }

        int bits = BitPackedArray.bitsFor(paletteSize);
        long[] packed = BitPackedArray.unpackVarInts(data, bits, volume);
        BitPackedArray array = new BitPackedArray(bits, volume, packed);

        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        long skipped = 0;

        for (long index = 0; index < volume; index++) {
            int paletteIndex = array.getAt(index);

            if (paletteIndex < 0 || paletteIndex >= paletteItems.size()) {
                skipped++;
                continue;
            }

            String itemId = paletteItems.get(paletteIndex);
            if (itemId == null) {
                skipped++;
                continue;
            }

            counts.merge(itemId, 1, Integer::sum);
        }

        return new Result(counts, 1, volume, skipped, warnings);
    }

    private static int[] readSpongeSize(NbtCompound root) {
        if (root.contains("Width", NbtElement.SHORT_TYPE)) {
            return new int[]{
                Math.abs(root.getShort("Width")),
                Math.abs(root.getShort("Height")),
                Math.abs(root.getShort("Length"))
            };
        }
        return new int[]{0, 0, 0};
    }

    /**
     * Maps a Sponge palette key such as {@code minecraft:oak_log[axis=y]} to an item id.
     *
     * <p>The properties are parsed so a state that resolves to a different item
     * than its block name is still handled, but a failure to parse is not fatal:
     * the bare block name is used instead.
     */
    private static String itemIdForStateString(String key) {
        if (key == null || key.isBlank()) return null;

        String name = key;
        int bracket = key.indexOf('[');
        if (bracket >= 0) name = key.substring(0, bracket);

        String normalized = MaterialIds.normalize(name);
        if (normalized == null || ItemResolver.isNoItemBlock(normalized)) return null;

        BlockState state = parseStateString(key);
        if (state != null) {
            Item item = state.getBlock().asItem();
            if (item == null || item == net.minecraft.item.Items.AIR) {
                item = ItemResolver.itemForBlock(normalized);
            }
            if (item != null && item != net.minecraft.item.Items.AIR) {
                Identifier id = Registries.ITEM.getId(item);
                if (id != null) return id.toString();
            }
        }

        return ItemResolver.exists(normalized) ? normalized : null;
    }

    private static BlockState parseStateString(String key) {
        try {
            // The vanilla parser accepts the exact "namespace:block[prop=value]" syntax
            // the Sponge palette uses.
            return BlockArgumentParser.block(
                net.minecraft.registry.Registries.BLOCK.getReadOnlyWrapper(), key, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** Formats a stack size, used in the summary. */
    public static String describeStacks(int count, int maxStack) {
        List<Integer> stacks = com.schematicdropper.material.StackMath.stacks(count, maxStack);
        return count + " (" + stacks.size() + " stack" + (stacks.size() == 1 ? "" : "s") + ")";
    }

    /** Exposes the cache reset for the reader's palette resolution. */
    public static void clearCaches() {
        ItemResolver.clearCache();
    }
}
