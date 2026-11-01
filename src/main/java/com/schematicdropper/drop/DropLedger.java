package com.schematicdropper.drop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which drops the server has actually accepted.
 *
 * <p>This is the heart of the module's reliability contract. A creative drop is
 * sent as a client packet with no acknowledgement, and the server is free to
 * silently discard it - for example once its own creative anti-spam budget is
 * exhausted. The only trustworthy evidence that a drop happened is the server
 * spawning an {@code ItemEntity}, which the client observes as an entity add.
 *
 * <p>So nothing is ever counted as dropped on send. A request is recorded as
 * outstanding, and only a matching observed entity converts it into a
 * confirmation. Requests that are never confirmed are retried and finally
 * reported as stalled rather than being counted.
 *
 * <p>Contains no Minecraft types, so the whole confirmation model is testable.
 */
public final class DropLedger {
    /** What happened to a request. */
    public enum Outcome {
        /** The server spawned the items; safe to count. */
        CONFIRMED,
        /** Not confirmed in time; the caller should resend. */
        RETRY,
        /**
         * Not confirmed in time, and resending is disabled. The request stays
         * outstanding: the server may simply be slow, and a resend could duplicate
         * items that were in fact created. The caller waits and revalidates.
         */
        TIMED_OUT,
        /** Out of retries; the caller must not count this and should stop. */
        STALLED
    }

    /** A single drop the client asked the server to perform. */
    public static final class Request {
        private final long id;
        private final String itemId;
        private final int count;

        private long sentTick;
        private int attempts = 1;
        /** Set once the request has exceeded the confirmation window. */
        private boolean timedOut;

        Request(long id, String itemId, int count, long sentTick) {
            this.id = id;
            this.itemId = itemId;
            this.count = count;
            this.sentTick = sentTick;
        }

        public long id() {
            return id;
        }

        public String itemId() {
            return itemId;
        }

        public int count() {
            return count;
        }

        public long sentTick() {
            return sentTick;
        }

        public int attempts() {
            return attempts;
        }

        /** True once this request has waited longer than the confirmation window. */
        public boolean timedOut() {
            return timedOut;
        }

        @Override
        public String toString() {
            return "#" + id + " " + itemId + " x" + count + " (try " + attempts + ")"
                + (timedOut ? " [timed out]" : "");
        }
    }

    /** The ledger's verdict on one request. */
    public record Resolution(long id, String itemId, int count, Outcome outcome) {
    }

    /** Outstanding requests per item id, oldest first. */
    private final Map<String, Deque<Request>> outstanding = new LinkedHashMap<>();

    /** Items observed spawning per item id since the current batch began. */
    private final Map<String, Integer> observed = new HashMap<>();

    /** Items already committed to confirmations, per item id. */
    private final Map<String, Integer> committed = new HashMap<>();

    /** Items ever sent for an item id, counting a retry as a second send. */
    private final Map<String, Integer> sentTotal = new HashMap<>();

    private long nextId = 1;

    /**
     * Records a drop that has just been sent to the server.
     *
     * @return the new request, which stays outstanding until confirmed
     */
    public Request send(String itemId, int count, long tick) {
        if (itemId == null) throw new IllegalArgumentException("itemId is required");
        if (count <= 0) throw new IllegalArgumentException("count must be positive, got " + count);

        Request request = new Request(nextId++, itemId, count, tick);
        outstanding.computeIfAbsent(itemId, key -> new ArrayDeque<>()).addLast(request);
        sentTotal.merge(itemId, count, Integer::sum);
        return request;
    }

    /**
     * Reports an item entity the server spawned.
     *
     * <p>Confirmations are handed out greedily oldest-first, and only for the
     * number of items actually seen. Observing fewer items than requested
     * confirms nothing, which keeps a partially accepted drop from being
     * counted in full.
     *
     * @return every request this observation completed
     */
    public List<Resolution> observe(String itemId, int count, long tick) {
        List<Resolution> resolutions = new ArrayList<>();
        if (itemId == null || count <= 0) return resolutions;

        Deque<Request> queue = outstanding.get(itemId);
        if (queue == null || queue.isEmpty()) return resolutions;

        // Never credit more items than were actually sent. A retry legitimately
        // produces a second batch of entities, so the ceiling is the running send
        // total, not the amount still queued. Without this, one duplicated entity
        // event would be counted twice and could confirm a later request that was
        // never dropped - progress would be overstated and items never delivered.
        int headroom = sentTotal.getOrDefault(itemId, 0) - observed.getOrDefault(itemId, 0);
        int credit = Math.min(count, Math.max(0, headroom));
        if (credit <= 0) return resolutions;

        observed.merge(itemId, credit, Integer::sum);
        int seen = observed.get(itemId);
        int used = committed.getOrDefault(itemId, 0);

        while (!queue.isEmpty()) {
            Request head = queue.peekFirst();
            if (seen - used < head.count) break;

            queue.removeFirst();
            used += head.count;
            resolutions.add(new Resolution(head.id, head.itemId, head.count, Outcome.CONFIRMED));
        }
        committed.put(itemId, used);
        return resolutions;
    }

    /**
     * Expires requests that were never confirmed.
     *
     * <p>Called once per client tick. A request still inside {@code timeoutTicks}
     * is left alone. One that has run out of time is retried, declared timed out,
     * or declared stalled, depending on the retry budget and on whether resending
     * is allowed at all.
     *
     * <p>A retry is a genuine second send, so it raises the item's send ceiling.
     * That matters because a retry can produce a second batch of item entities:
     * if both are seen, both are real items in the world and both should be
     * credited.
     *
     * <p>When {@code resendAllowed} is false the request is reported as
     * {@link Outcome#TIMED_OUT} <em>without</em> being resent and <em>without</em>
     * being removed: a server that is merely slow would receive a duplicate drop,
     * so the safe behaviour is to keep waiting. This is what stops a timeout from
     * ever duplicating a drop.
     *
     * @param timeoutTicks  how long to wait for a confirmation before acting
     * @param maxAttempts   total sends allowed per request, including the first
     * @param resendAllowed whether a timed-out request may be resent at all
     */
    public List<Resolution> poll(long tick, int timeoutTicks, int maxAttempts, boolean resendAllowed) {
        List<Resolution> resolutions = new ArrayList<>();

        for (Map.Entry<String, Deque<Request>> entry : outstanding.entrySet()) {
            String itemId = entry.getKey();
            Deque<Request> queue = entry.getValue();

            while (!queue.isEmpty()) {
                Request head = queue.peekFirst();
                if (tick - head.sentTick < timeoutTicks) break;

                if (!resendAllowed) {
                    // Report the timeout once, then leave the request queued. It is
                    // still outstanding, so its items stay counted against the
                    // target and a late confirmation is still honoured.
                    if (!head.timedOut) {
                        head.timedOut = true;
                        resolutions.add(new Resolution(head.id, itemId, head.count, Outcome.TIMED_OUT));
                    }
                    break;
                }

                if (head.attempts >= maxAttempts) {
                    // Give up on this one but leave the rest of the queue intact.
                    queue.removeFirst();
                    resolutions.add(new Resolution(head.id, itemId, head.count, Outcome.STALLED));
                    continue;
                }

                head.attempts++;
                head.sentTick = tick;
                sentTotal.merge(itemId, head.count, Integer::sum);
                resolutions.add(new Resolution(head.id, itemId, head.count, Outcome.RETRY));
                // The retry is for the head only; later requests keep waiting.
                break;
            }
        }

        return resolutions;
    }

    /**
     * Backwards-compatible overload: allows resending, as the original ledger did.
     */
    public List<Resolution> poll(long tick, int timeoutTicks, int maxAttempts) {
        return poll(tick, timeoutTicks, maxAttempts, true);
    }

    /** Drops every request and observation for an item, used when a material is abandoned. */
    public void abandon(String itemId) {
        outstanding.remove(itemId);
        observed.remove(itemId);
        committed.remove(itemId);
        sentTotal.remove(itemId);
    }

    public void clear() {
        outstanding.clear();
        observed.clear();
        committed.clear();
        sentTotal.clear();
    }

    /** Requests still waiting for a confirmation for this item. */
    public int outstandingRequests(String itemId) {
        Deque<Request> queue = outstanding.get(itemId);
        return queue == null ? 0 : queue.size();
    }

    /** Items still waiting for a confirmation for this item. */
    public int outstandingItems(String itemId) {
        Deque<Request> queue = outstanding.get(itemId);
        if (queue == null) return 0;

        int total = 0;
        for (Request request : queue) total += request.count;
        return total;
    }

    /**
     * Total items ever sent for an item, counting a retry as a second send.
     *
     * <p>Exposed so a caller (or a test) can confirm that a timeout did not
     * secretly resend, which would raise this figure.
     */
    public int sentTotal(String itemId) {
        return sentTotal.getOrDefault(itemId, 0);
    }

    /** True when nothing is in flight, so the caller may safely advance. */
    public boolean isEmpty() {
        for (Deque<Request> queue : outstanding.values()) {
            if (!queue.isEmpty()) return false;
        }
        return true;
    }

    /** True when any outstanding request has exceeded the confirmation window. */
    public boolean hasTimedOut() {
        for (Deque<Request> queue : outstanding.values()) {
            for (Request request : queue) {
                if (request.timedOut) return true;
            }
        }
        return false;
    }

    /** True when an outstanding request for this item has exceeded the window. */
    public boolean hasTimedOut(String itemId) {
        Deque<Request> queue = outstanding.get(itemId);
        if (queue == null) return false;

        for (Request request : queue) {
            if (request.timedOut) return true;
        }
        return false;
    }

    /** Snapshot of every outstanding request, for diagnostics. */
    public List<Request> snapshot() {
        List<Request> all = new ArrayList<>();
        for (Deque<Request> queue : outstanding.values()) all.addAll(queue);
        return all;
    }
}
