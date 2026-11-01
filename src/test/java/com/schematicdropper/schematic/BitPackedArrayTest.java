package com.schematicdropper.schematic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The packing is a port of Litematica's own array, so it is checked against an
 * independent transcription of the original algorithm as well as by round trip.
 */
class BitPackedArrayTest {
    /** Straight transcription of LitematicaBitArray.getAt, used as the oracle. */
    private static int referenceGet(long[] data, int bitsPerEntry, long index) {
        long maxEntryValue = (1L << bitsPerEntry) - 1L;
        long startOffset = index * (long) bitsPerEntry;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((index + 1L) * (long) bitsPerEntry - 1L) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);

        if (startArrIndex == endArrIndex) {
            return (int) (data[startArrIndex] >>> startBitOffset & maxEntryValue);
        }

        int endOffset = 64 - startBitOffset;
        return (int) ((data[startArrIndex] >>> startBitOffset | data[endArrIndex] << endOffset) & maxEntryValue);
    }

    /** Straight transcription of LitematicaBitArray.setAt, used as the oracle. */
    private static void referenceSet(long[] data, int bitsPerEntry, long index, int value) {
        long maxEntryValue = (1L << bitsPerEntry) - 1L;
        long startOffset = index * (long) bitsPerEntry;
        int startArrIndex = (int) (startOffset >> 6);
        int endArrIndex = (int) (((index + 1L) * (long) bitsPerEntry - 1L) >> 6);
        int startBitOffset = (int) (startOffset & 0x3F);

        data[startArrIndex] = data[startArrIndex] & ~(maxEntryValue << startBitOffset)
            | ((long) value & maxEntryValue) << startBitOffset;

        if (startArrIndex != endArrIndex) {
            int endOffset = 64 - startBitOffset;
            int shift = bitsPerEntry - endOffset;
            data[endArrIndex] = data[endArrIndex] >>> shift << shift
                | ((long) value & maxEntryValue) >> endOffset;
        }
    }

    @Test
    void bitsForMatchesLitematica() {
        // Litematica uses max(2, bits needed for size-1).
        assertEquals(2, BitPackedArray.bitsFor(0));
        assertEquals(2, BitPackedArray.bitsFor(1));
        assertEquals(2, BitPackedArray.bitsFor(2));
        assertEquals(2, BitPackedArray.bitsFor(3));
        assertEquals(2, BitPackedArray.bitsFor(4));
        assertEquals(3, BitPackedArray.bitsFor(5));
        assertEquals(4, BitPackedArray.bitsFor(9));
        assertEquals(5, BitPackedArray.bitsFor(17));
        assertEquals(8, BitPackedArray.bitsFor(256));
        assertEquals(9, BitPackedArray.bitsFor(257));
    }

    @Test
    void roundTripsValuesThatStraddleALongBoundary() {
        // 5 bits packs 12 entries per long, so entry 12 crosses the boundary.
        int bits = 5;
        long size = 40;
        long[] data = BitPackedArray.allocate(bits, size);
        BitPackedArray array = new BitPackedArray(bits, size, data);

        int[] values = new int[(int) size];
        for (int i = 0; i < size; i++) {
            values[i] = (i * 7 + 3) & 0x1F;
            array.setAt(i, values[i]);
        }

        for (int i = 0; i < size; i++) {
            assertEquals(values[i], array.getAt(i), "index " + i);
            assertEquals(values[i], referenceGet(data, bits, i), "reference index " + i);
        }
    }

    @Test
    void matchesTheReferenceImplementationForEveryBitWidth() {
        Random random = new Random(1234);

        for (int bits = 1; bits <= 32; bits++) {
            long size = 300;
            long[] mine = BitPackedArray.allocate(bits, size);
            long[] theirs = BitPackedArray.allocate(bits, size);

            BitPackedArray array = new BitPackedArray(bits, size, mine);
            // A long mask avoids overflowing nextInt at 32 bits.
            long mask = (1L << bits) - 1L;

            for (long i = 0; i < size; i++) {
                int value = (int) (random.nextLong() & mask);
                array.setAt(i, value);
                referenceSet(theirs, bits, i, value);
            }

            for (long i = 0; i < size; i++) {
                assertEquals(referenceGet(theirs, bits, i), array.getAt(i),
                    "bits=" + bits + " index=" + i);
            }
        }
    }

    @Test
    void readsBeyondTheEndAsZeroRatherThanThrowing() {
        // A truncated file must degrade to "air", not crash the reader.
        long[] data = BitPackedArray.allocate(4, 100);
        BitPackedArray array = new BitPackedArray(4, 100, data);

        assertEquals(0, array.getAt(500));
    }

    @Test
    void rejectsAnImpossibleBitWidth() {
        assertThrows(IllegalArgumentException.class, () -> new BitPackedArray(0, 10, new long[1]));
        assertThrows(IllegalArgumentException.class, () -> new BitPackedArray(33, 10, new long[1]));
    }

    @Test
    void allocateSizesTheBackingArrayForTheEntryCount() {
        // 5 bits * 100 entries = 500 bits, which needs 8 longs.
        assertEquals(8, BitPackedArray.allocate(5, 100).length);
    }

    @Test
    void roundUpBehavesLikeLitematica() {
        assertEquals(64, BitPackedArray.roundUp(1, 64));
        assertEquals(64, BitPackedArray.roundUp(64, 64));
        assertEquals(128, BitPackedArray.roundUp(65, 64));
        assertEquals(0, BitPackedArray.roundUp(5, 0));
        assertEquals(64, BitPackedArray.roundUp(0, 64));
    }

    // ------------------------------------------------------- SPONGE VARINTS

    @Test
    void unpacksVarIntsIntoThePackedLayout() {
        // Three small values, palette of 5 so bits = 3.
        byte[] data = new byte[]{1, 4, 0};
        int bits = BitPackedArray.bitsFor(5);
        assertEquals(3, bits);

        long[] packed = BitPackedArray.unpackVarInts(data, bits, 3);
        BitPackedArray array = new BitPackedArray(bits, 3, packed);

        assertEquals(1, array.getAt(0));
        assertEquals(4, array.getAt(1));
        assertEquals(0, array.getAt(2));
    }

    @Test
    void unpacksMultiByteVarInts() {
        // 300 encodes as two varint bytes: 0xAC 0x02.
        byte[] data = new byte[]{(byte) 0xAC, 0x02};
        int bits = BitPackedArray.bitsFor(301);

        long[] packed = BitPackedArray.unpackVarInts(data, bits, 1);
        BitPackedArray array = new BitPackedArray(bits, 1, packed);

        assertEquals(300, array.getAt(0));
    }

    @Test
    void unpacksAPaletteIndexRun() {
        // A palette of 9 needs 4 bits, and every index in the run is 8.
        byte[] data = new byte[]{8, 8, 8, 8, 8};
        int bits = BitPackedArray.bitsFor(9);
        assertEquals(4, bits);

        long[] packed = BitPackedArray.unpackVarInts(data, bits, 5);
        BitPackedArray array = new BitPackedArray(bits, 5, packed);

        for (int i = 0; i < 5; i++) {
            assertEquals(8, array.getAt(i), "index " + i);
        }
    }

    @Test
    void unpackingStopsAtTheVolumeEvenIfBytesRemain() {
        byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        int bits = BitPackedArray.bitsFor(9);

        long[] packed = BitPackedArray.unpackVarInts(data, bits, 3);
        BitPackedArray array = new BitPackedArray(bits, 3, packed);

        assertEquals(1, array.getAt(0));
        assertEquals(2, array.getAt(1));
        assertEquals(3, array.getAt(2));
    }

    @Test
    void toListReturnsEveryEntry() {
        long[] data = BitPackedArray.allocate(4, 6);
        BitPackedArray array = new BitPackedArray(4, 6, data);
        for (int i = 0; i < 6; i++) array.setAt(i, i);

        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 6; i++) expected.add(i);

        assertEquals(expected, BitPackedArray.toList(array));
    }
}
