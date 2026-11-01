package com.schematicdropper.schematic;

import java.util.List;

/**
 * The bit-packed long array layout used by Litematica's block state containers.
 *
 * <p>Ported from {@code fi.dy.masa.litematica.schematic.container.LitematicaBitArray}
 * so a {@code .litematic} decodes identically to the mod that wrote it. Entries
 * are packed little-endian within each long and may straddle a long boundary.
 *
 * <p>Kept separate from the NBT reading so the packing can be tested with plain
 * arrays.
 */
public final class BitPackedArray {
    private final long[] data;
    private final int bitsPerEntry;
    private final long maxEntryValue;
    private final long size;

    public BitPackedArray(int bitsPerEntry, long size, long[] data) {
        if (bitsPerEntry < 1 || bitsPerEntry > 32) {
            throw new IllegalArgumentException("bitsPerEntry must be 1..32, got " + bitsPerEntry);
        }
        this.bitsPerEntry = bitsPerEntry;
        this.size = size;
        this.maxEntryValue = (1L << bitsPerEntry) - 1L;
        this.data = data;
    }

    /** Allocates a backing array large enough for {@code size} entries. */
    public static long[] allocate(int bitsPerEntry, long size) {
        return new long[(int) (roundUp(size * bitsPerEntry, 64L) / 64L)];
    }

    public int getAt(long index) {
        long startOffset = index * bitsPerEntry;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((index + 1L) * bitsPerEntry - 1L) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);

        if (startArrIndex >= data.length) return 0;

        if (startArrIndex == endArrIndex) {
            return (int) (data[startArrIndex] >>> startBitOffset & maxEntryValue);
        }

        int endOffset = 64 - startBitOffset;
        long low = startArrIndex < data.length ? data[startArrIndex] >>> startBitOffset : 0L;
        long high = endArrIndex < data.length ? data[endArrIndex] << endOffset : 0L;
        return (int) ((low | high) & maxEntryValue);
    }

    public void setAt(long index, int value) {
        long startOffset = index * bitsPerEntry;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((index + 1L) * bitsPerEntry - 1L) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);

        if (startArrIndex >= data.length) return;

        data[startArrIndex] = data[startArrIndex] & ~(maxEntryValue << startBitOffset)
            | ((long) value & maxEntryValue) << startBitOffset;

        if (startArrIndex != endArrIndex && endArrIndex < data.length) {
            int endOffset = 64 - startBitOffset;
            int shift = bitsPerEntry - endOffset;
            data[endArrIndex] = data[endArrIndex] >>> shift << shift
                | ((long) value & maxEntryValue) >> endOffset;
        }
    }

    public long size() {
        return size;
    }

    public int bitsPerEntry() {
        return bitsPerEntry;
    }

    /**
     * Bits needed to index {@code paletteSize} entries, with a floor of two.
     *
     * <p>Matches Litematica: a single-entry palette still uses two bits.
     */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(1, paletteSize) - 1));
    }

    /**
     * Decodes a Sponge schematic's varint byte stream into the packed layout.
     *
     * <p>Sponge stores palette indices back to back as varints rather than in
     * fixed-width slots, so the bytes must be re-packed before the normal
     * accessor can be used.
     */
    public static long[] unpackVarInts(byte[] bytes, int bitsPerEntry, long volume) {
        long[] out = allocate(bitsPerEntry, volume);
        BitPackedArray array = new BitPackedArray(bitsPerEntry, volume, out);

        long index = 0;
        int byteIndex = 0;

        while (byteIndex < bytes.length && index < volume) {
            int value = 0;
            int shift = 0;

            // A varint is at most five bytes for a 32-bit value.
            while (byteIndex < bytes.length && shift < 35) {
                byte b = bytes[byteIndex++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }

            array.setAt(index, value);
            index++;
        }

        return out;
    }

    /** Rounds {@code value} up to the next multiple of {@code interval}. */
    public static long roundUp(long value, long interval) {
        if (interval == 0L) return 0L;
        if (value == 0L) return interval;

        long remainder = value % interval;
        return remainder == 0L ? value : value + interval - remainder;
    }

    /** Convenience for tests and readers: decode a whole array into a list. */
    public static List<Integer> toList(BitPackedArray array) {
        List<Integer> out = new java.util.ArrayList<>((int) Math.min(array.size(), Integer.MAX_VALUE));
        for (long i = 0; i < array.size(); i++) out.add(array.getAt(i));
        return out;
    }
}
