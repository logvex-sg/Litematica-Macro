package com.schematicdropper.material;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackMathTest {
    @Test
    void splitsExactStacksLargestFirst() {
        assertEquals(List.of(64, 64, 2), StackMath.stacks(130));
        assertEquals(List.of(64, 6), StackMath.stacks(70));
        assertEquals(List.of(17), StackMath.stacks(17));
    }

    @Test
    void splitsAnExactMultipleWithoutATrailingEmptyStack() {
        assertEquals(List.of(64, 64), StackMath.stacks(128));
    }

    @Test
    void zeroAndNegativeProduceNoStacks() {
        assertEquals(List.of(), StackMath.stacks(0));
        assertEquals(List.of(), StackMath.stacks(-5));
    }

    @Test
    void respectsNonStandardStackSizes() {
        // Ender pearls stack to 16.
        assertEquals(List.of(16, 16, 4), StackMath.stacks(36, 16));
        // Buckets stack to 1.
        assertEquals(List.of(1, 1, 1), StackMath.stacks(3, 1));
    }

    @Test
    void countsStacksIncludingPartial() {
        assertEquals(3, StackMath.stackCount(130, 64));
        assertEquals(2, StackMath.stackCount(128, 64));
        assertEquals(0, StackMath.stackCount(0, 64));
    }

    @Test
    void partialRemainderIsZeroWhenItDividesEvenly() {
        assertEquals(2, StackMath.partialRemainder(130, 64));
        assertEquals(0, StackMath.partialRemainder(128, 64));
        assertEquals(0, StackMath.partialRemainder(0, 64));
    }

    @Test
    void nextDropNeverExceedsWhatIsLeft() {
        // The classic bug: a whole stack dropped when only two items are wanted.
        assertEquals(2, StackMath.nextDropSize(2, 64, 64));
        assertEquals(1, StackMath.nextDropSize(1, 64, 64));
        assertEquals(64, StackMath.nextDropSize(130, 64, 64));
        assertEquals(0, StackMath.nextDropSize(0, 64, 64));
    }

    @Test
    void nextDropHonoursThePerDropCap() {
        assertEquals(8, StackMath.nextDropSize(130, 64, 8));
        assertEquals(64, StackMath.nextDropSize(130, 64, 512));
    }

    @Test
    void nextDropHonoursTheStackSizeForSmallStacks() {
        assertEquals(16, StackMath.nextDropSize(36, 16, 64));
    }

    @Test
    void rejectsNonPositiveMaxStack() {
        assertThrows(IllegalArgumentException.class, () -> StackMath.stacks(10, 0));
    }

    @Test
    void fitsOneStackBoundary() {
        assertTrue(StackMath.fitsOneStack(64, 64));
        assertFalse(StackMath.fitsOneStack(65, 64));
        assertFalse(StackMath.fitsOneStack(0, 64));
    }
}
