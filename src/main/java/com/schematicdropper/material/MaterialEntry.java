package com.schematicdropper.material;

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
        STALLED
    }

    private final String id;
    private int required;
    private int dropped;
    private Status status = Status.PENDING;

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
        if (isComplete()) this.status = Status.DONE;
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
    }

    public double progress() {
        return required == 0 ? 1.0 : (double) dropped / required;
    }

    @Override
    public String toString() {
        return id + " " + dropped + "/" + required + " (" + status + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterialEntry other)) return false;
        return required == other.required && dropped == other.dropped && status == other.status && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, required, dropped, status);
    }
}
