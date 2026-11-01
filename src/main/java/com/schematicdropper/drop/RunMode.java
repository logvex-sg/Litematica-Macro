package com.schematicdropper.drop;

/**
 * How the scheduler treats a material it cannot currently process.
 *
 * <p>The material order itself is unaffected: both modes walk the same ordered
 * queue. The mode only decides what happens when the material at the front
 * cannot be dropped right now.
 */
public enum RunMode {
    /**
     * Never advance past a material that still owes items. The queue waits for it
     * to become processable and resumes from its exact remaining quantity. Later
     * materials are not touched. This is the default, and the only mode that
     * guarantees the drops match the schematic's own order.
     */
    STRICT_ORDER("Strict order"),
    /**
     * Skip a material that cannot currently be processed, continue with the ones
     * that can, and revisit the blocked material once it becomes available. The
     * run is not complete while anything is still blocked.
     */
    BEST_EFFORT("Best effort");

    private final String title;

    RunMode(String title) {
        this.title = title;
    }

    @Override
    public String toString() {
        return title;
    }
}
