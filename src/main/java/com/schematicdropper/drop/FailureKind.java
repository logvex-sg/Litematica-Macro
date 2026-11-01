package com.schematicdropper.drop;

/**
 * Why an operation could not be completed.
 *
 * <p>A failure is classified so the scheduler can decide what to do about it: a
 * transient problem is retried after a delay, an invalid target is waited out, an
 * unavailable material is blocked and revisited, and a permanent error is
 * reported without burning the retry budget.
 */
public enum FailureKind {
    /** A one-off hiccup; retry after the backoff delay. */
    TEMPORARY_FAILURE(true),
    /** The creative inventory or the requested item changed under us; revalidate and retry. */
    INVENTORY_CHANGED(true),
    /** The drop target is gone or no longer valid; wait for one to reappear. */
    TARGET_INVALID(true),
    /** The server did not confirm the drop in time; wait, then retry without duplicating. */
    SERVER_TIMEOUT(true),
    /** The server refused the request outright; retry, but expect it may never succeed. */
    SERVER_REJECTION(true),
    /** The material cannot be obtained right now (no item, none in reach); block and revisit. */
    MATERIAL_UNAVAILABLE(true),
    /** Something that will not fix itself; do not retry, keep the work unfinished. */
    PERMANENT_ERROR(false);

    private final boolean recoverable;

    FailureKind(boolean recoverable) {
        this.recoverable = recoverable;
    }

    /** True when waiting and revalidating could plausibly let the operation succeed. */
    public boolean recoverable() {
        return recoverable;
    }
}
