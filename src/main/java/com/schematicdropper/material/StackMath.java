package com.schematicdropper.material;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact stack arithmetic.
 *
 * <p>Every quantity the module drops is derived here, so the rules live in one
 * place: a partial stack is never rounded up, and a full stack is never sent
 * when fewer items are still outstanding.
 */
public final class StackMath {
    /** Default vanilla stack size. Items with a different max are handled by the caller. */
    public static final int DEFAULT_STACK = 64;

    private StackMath() {
    }

    /**
     * Splits {@code count} into the stack sizes a player would actually hold,
     * largest first: 130 with a max of 64 becomes {@code [64, 64, 2]}.
     */
    public static List<Integer> stacks(int count, int maxStack) {
        if (maxStack <= 0) throw new IllegalArgumentException("maxStack must be positive, got " + maxStack);

        List<Integer> out = new ArrayList<>();
        int left = count;
        while (left > 0) {
            int take = Math.min(left, maxStack);
            out.add(take);
            left -= take;
        }
        return out;
    }

    public static List<Integer> stacks(int count) {
        return stacks(count, DEFAULT_STACK);
    }

    /** Number of stacks a quantity needs, counting a partial stack as one. */
    public static int stackCount(int count, int maxStack) {
        if (count <= 0) return 0;
        return (count + maxStack - 1) / maxStack;
    }

    /** The size of the final, possibly partial stack. Zero when the count divides evenly. */
    public static int partialRemainder(int count, int maxStack) {
        if (count <= 0) return 0;
        return count % maxStack;
    }

    /**
     * Size of the next drop for a material.
     *
     * <p>Never exceeds what is still required and never exceeds one stack, which
     * is what stops "drop a whole stack when one item is left" bugs.
     */
    public static int nextDropSize(int remaining, int maxStack, int maxPerDrop) {
        if (remaining <= 0) return 0;
        int limit = Math.max(1, Math.min(maxStack, maxPerDrop));
        return Math.min(remaining, limit);
    }

    /** True when a quantity can be dropped in a single stack. */
    public static boolean fitsOneStack(int count, int maxStack) {
        return count > 0 && count <= maxStack;
    }
}
