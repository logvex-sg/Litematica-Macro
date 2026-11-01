package com.schematicdropper.material;

import com.schematicdropper.drop.FailureKind;

import java.util.Objects;

/**
 * One material line: how many of an item the schematic needs and how many of
 * them have been confirmed dropped so far.
 *
 * <p>{@link #dropped} is only ever advanced from a confirmed server drop, never
 * from the act of sending a packet.
 */
public final class MaterialEntry {
    public enum Status {
        /** Not started yet. */
        PENDING,
        /** Drop in flight, awaiting server confirmation. */
        RUNNING,
        /** Fully dropped. */
        DONE,
        /** Item does not exist in this client's registry. */
        MISSING,
        /** Drops were rejected repeatedly; needs user attention. */
        STALLED,
        /**
         * Cannot be processed right now, but may recover. The material keeps its
         * place in the queue and is revisited rather than being abandoned.
         */
        BLOCKED
    }

    private final String id;
    private int required;
    private int dropped;
    private Status status = Status.PENDING;
    /** Why the entry is BLOCKED, or {@code null} when it is not. */
    private FailureKind blockedReason;

    public MaterialEntry(String id, int required) {
        if (required <= 0) throw new IllegalArgumentException("required must be positive, got " + required);
        this.id = Objects.requireNonNull(id, "id");
        this.required = required;
    }

    public String id() {
        return id;
    }

    public int required() {
        return required;
    }

    public int dropped() {
        return dropped;
    }

    public int remaining() {
        return Math.max(0, required - dropped);
    }

    public boolean isComplete() {
        return dropped >= required;
    }

    public Status status() {
        return status;
    }

    public void status(Status status) {
        this.status = status;
        if (status != Status.BLOCKED) this.blockedReason = null;
    }

    /** Why the entry is blocked, or {@code null} when it is not blocked. */
    public FailureKind blockedReason() {
        return blockedReason;
    }

    /** Marks the entry blocked with a reason, keeping its position and progress. */
    public void block(FailureKind reason) {
        this.status = Status.BLOCKED;
        this.blockedReason = reason;
    }

    public boolean isBlocked() {
        return status == Status.BLOCKED;
    }

    /** Adds to the requirement, used when merging duplicate imports. */
    public void addRequired(int extra) {
        if (extra <= 0) throw new IllegalArgumentException("extra must be positive, got " + extra);
        this.required += extra;
    }

    /**
     * Records a confirmed drop. Clamped at {@code required} so a duplicated
     * server acknowledgement can never push a material past its target.
     */
    public void confirmDropped(int count) {
        if (count <= 0) return;
        this.dropped = Math.min(required, this.dropped + count);
        if (isComplete()) {
            this.status = Status.DONE;
            this.blockedReason = null;
        }
    }

    /** Rolls back a confirmation that turned out to be a false positive. */
    public void revokeDropped(int count) {
        if (count <= 0) return;
        this.dropped = Math.max(0, this.dropped - count);
        if (!isComplete() && status == Status.DONE) status = Status.PENDING;
    }

    public void reset() {
        this.dropped = 0;
        this.status = Status.PENDING;
        this.blockedReason = null;
    }

    /**
     * Restores a saved quantity without counting it as a fresh confirmation.
     *
     * <p>Used when a run is reloaded: the unfinished queue, and how much of each
     * material was already confirmed, are preserved exactly. The count is clamped
     * to the requirement, so a corrupt value cannot overshoot.
     */
    public void restore(int dropped) {
        this.dropped = Math.max(0, Math.min(required, dropped));
        if (isComplete()) {
            this.status = Status.DONE;
        } else if (status == Status.DONE || status == Status.MISSING) {
            this.status = Status.PENDING;
        }
    }

    public double progress() {
        return required == 0 ? 1.0 : (double) dropped / required;
    }

    @Override
    public String toString() {
        String base = id + " " + dropped + "/" + required + " (" + status + ")";
        return blockedReason == null ? base : base + "[" + blockedReason + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialEntry other)) return false;
        return required == other.required && dropped == other.dropped && status == other.status
            && blockedReason == other.blockedReason && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, required, dropped, status, blockedReason);
    }
}
