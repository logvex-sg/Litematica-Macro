package com.schematicdropper.material;

import com.schematicdropper.drop.FailureKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live material list: what is required, what has been confirmed dropped, and
 * what is left.
 *
 * <p>The list also owns the <em>processing queue</em>: the exact, deterministic
 * order in which materials are worked through. The queue is fixed when the list is
 * imported (or when a saved run is reloaded) and never reshuffled while a run is
 * active, so a material that is temporarily unavailable keeps its position and is
 * resumed at the same point.
 *
 * <p>Holds no Minecraft types, so progress tracking, ordering, resume and
 * cancellation can be tested directly.
 */
public final class MaterialManager {
    /** How the GUI orders the list. */
    public enum SortMode {
        NAME("Name"),
        REQUIRED("Required"),
        REMAINING("Remaining"),
        PROGRESS("Progress");

        private final String title;

        SortMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private final Map<String, MaterialEntry> entries = new LinkedHashMap<>();

    /** The processing order: the ids of {@link #entries}, in the chosen order. */
    private final List<String> queue = new ArrayList<>();

    private int cursor = 0;

    /** Replaces the whole list from an import, in the map's own (source) order. */
    public void setFrom(Map<String, Integer> materials) {
        setFrom(materials, MaterialOrder.SCHEMATIC_ORDER);
    }

    /**
     * Replaces the whole list from an import and fixes its processing order.
     *
     * @param materials the required materials, in source order
     * @param order     how the queue should be ordered
     */
    public void setFrom(Map<String, Integer> materials, MaterialOrder order) {
        entries.clear();
        queue.clear();
        cursor = 0;

        if (materials == null) return;
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            entries.put(entry.getKey(), new MaterialEntry(entry.getKey(), entry.getValue()));
        }

        MaterialOrder chosen = order == null ? MaterialOrder.SCHEMATIC_ORDER : order;
        for (MaterialEntry entry : chosen.order(new ArrayList<>(entries.values()))) {
            queue.add(entry.id());
        }
    }

    public void clear() {
        entries.clear();
        queue.clear();
        cursor = 0;
    }

    /**
     * Drops every entry the predicate rejects.
     *
     * <p>Used to discard blocks that have no item form, so the player is never
     * shown a material that could never be dropped.
     *
     * @return how many entries were removed
     */
    public int removeNoItemBlocks(java.util.function.Predicate<String> shouldRemove) {
        if (entries.isEmpty()) return 0;

        int before = entries.size();
        entries.entrySet().removeIf(entry -> shouldRemove.test(entry.getKey()));
        queue.removeIf(id -> !entries.containsKey(id));
        if (cursor >= queue.size()) cursor = 0;
        return before - entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public MaterialEntry get(String id) {
        return entries.get(id);
    }

    /** Every entry, in source order (not the processing order). */
    public List<MaterialEntry> all() {
        return new ArrayList<>(entries.values());
    }

    /** The processing order as an immutable list of ids. */
    public List<String> order() {
        return List.copyOf(queue);
    }

    /**
     * Every entry that still has something outstanding and is still droppable,
     * in processing order.
     *
     * <p>MISSING and STALLED entries are excluded: neither can make progress
     * without the user's intervention. BLOCKED entries are included - they are
     * recoverable and must keep their place in the queue.
     */
    public List<MaterialEntry> pending() {
        List<MaterialEntry> out = new ArrayList<>();
        for (String id : queue) {
            MaterialEntry entry = entries.get(id);
            if (entry == null) continue;
            if (entry.remaining() <= 0) continue;
            if (entry.status() == MaterialEntry.Status.MISSING
                || entry.status() == MaterialEntry.Status.STALLED) continue;
            out.add(entry);
        }
        return out;
    }

    /**
     * The first material in processing order that still needs work.
     *
     * <p>This is the head of the strict-order queue: while it is unfinished,
     * nothing after it is processed.
     *
     * @return the head entry, or {@code null} when nothing is left
     */
    public MaterialEntry firstPending() {
        for (String id : queue) {
            MaterialEntry entry = entries.get(id);
            if (entry == null) continue;
            if (entry.remaining() <= 0) continue;
            if (entry.status() == MaterialEntry.Status.MISSING
                || entry.status() == MaterialEntry.Status.STALLED) continue;
            return entry;
        }
        return null;
    }

    /**
     * The first material in processing order that is <em>not</em> blocked and
     * still needs work, scanning from the head of the queue.
     *
     * <p>Used by Best Effort mode to walk past a blocked material to the next one
     * that can actually be processed, without changing the underlying order.
     *
     * @return the next processable entry, or {@code null} when none is available
     */
    public MaterialEntry firstProcessable() {
        for (String id : queue) {
            MaterialEntry entry = entries.get(id);
            if (entry == null) continue;
            if (entry.remaining() <= 0 || entry.isBlocked()) continue;
            if (entry.status() == MaterialEntry.Status.MISSING
                || entry.status() == MaterialEntry.Status.STALLED) continue;
            return entry;
        }
        return null;
    }

    /**
     * The next candidate to consider, advancing the round-robin cursor.
     *
     * <p>Rebuilds the pending list on each call. Callers that scan repeatedly in
     * one pass should take {@link #pending()} once and step the cursor themselves
     * with {@link #cursor(int)} instead, or a large list allocates on every send.
     */
    public MaterialEntry next() {
        List<MaterialEntry> candidates = pending();
        if (candidates.isEmpty()) return null;

        if (cursor >= candidates.size()) cursor = 0;
        MaterialEntry entry = candidates.get(cursor);
        cursor = (cursor + 1) % candidates.size();
        return entry;
    }

    /** The round-robin position, so a caller can scan a snapshot and keep fairness. */
    public int cursor() {
        return cursor;
    }

    /** Sets the round-robin position, wrapping it into range. */
    public void cursor(int position) {
        cursor = position < 0 ? 0 : position;
    }

    /** Restarts progress from zero, keeping the requirement and the queue order. */
    public void resetProgress() {
        for (MaterialEntry entry : entries.values()) entry.reset();
        cursor = 0;
    }

    public void markMissing(String id) {
        MaterialEntry entry = entries.get(id);
        if (entry != null) entry.status(MaterialEntry.Status.MISSING);
    }

    public void markStalled(String id) {
        MaterialEntry entry = entries.get(id);
        if (entry != null) entry.status(MaterialEntry.Status.STALLED);
    }

    public void markRunning(String id) {
        MaterialEntry entry = entries.get(id);
        if (entry != null && !entry.isComplete()) entry.status(MaterialEntry.Status.RUNNING);
    }

    /**
     * Marks a material as blocked, keeping its position and progress.
     *
     * <p>A blocked material is not abandoned: the run is not complete while it
     * remains, and it is revisited once the reason may have cleared.
     */
    public void markBlocked(String id, FailureKind reason) {
        MaterialEntry entry = entries.get(id);
        if (entry == null || entry.isComplete()) return;
        entry.block(reason);
    }

    /** Clears a block, returning the material to the queue as PENDING. */
    public void unblock(String id) {
        MaterialEntry entry = entries.get(id);
        if (entry == null || entry.isComplete()) return;
        if (entry.isBlocked()) entry.status(MaterialEntry.Status.PENDING);
    }

    /** How many materials are currently blocked. */
    public int blockedCount() {
        int count = 0;
        for (MaterialEntry entry : entries.values()) {
            if (entry.isBlocked()) count++;
        }
        return count;
    }

    /** True when any material is blocked and still owes items. */
    public boolean hasBlocked() {
        for (MaterialEntry entry : entries.values()) {
            if (entry.isBlocked() && entry.remaining() > 0) return true;
        }
        return false;
    }

    /** Total items still owed by blocked materials. */
    public int blockedRemaining() {
        int total = 0;
        for (MaterialEntry entry : entries.values()) {
            if (entry.isBlocked()) total += entry.remaining();
        }
        return total;
    }

    /** Records a server-confirmed drop. */
    public void confirm(String id, int count) {
        MaterialEntry entry = entries.get(id);
        if (entry != null) entry.confirmDropped(count);
    }

    /** Rolls back a confirmation that turned out to be wrong. */
    public void revoke(String id, int count) {
        MaterialEntry entry = entries.get(id);
        if (entry != null) entry.revokeDropped(count);
    }

    public int totalRequired() {
        int total = 0;
        for (MaterialEntry entry : entries.values()) total += entry.required();
        return total;
    }

    public int totalDropped() {
        int total = 0;
        for (MaterialEntry entry : entries.values()) total += entry.dropped();
        return total;
    }

    public int totalRemaining() {
        int total = 0;
        for (MaterialEntry entry : entries.values()) total += entry.remaining();
        return total;
    }

    public double overallProgress() {
        int required = totalRequired();
        return required == 0 ? 1.0 : (double) totalDropped() / required;
    }

    public boolean isComplete() {
        for (MaterialEntry entry : entries.values()) {
            if (entry.remaining() > 0) return false;
        }
        return !entries.isEmpty();
    }

    public int missingCount() {
        int count = 0;
        for (MaterialEntry entry : entries.values()) {
            if (entry.status() == MaterialEntry.Status.MISSING) count++;
        }
        return count;
    }

    /**
     * The list as shown in the GUI: filtered, then sorted.
     *
     * @param filter    case-insensitive substring match on the id; blank keeps everything
     * @param sort      ordering to apply
     * @param onlyLeft  keep only entries with items still outstanding
     */
    public List<MaterialEntry> view(String filter, SortMode sort, boolean onlyLeft) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);

        List<MaterialEntry> out = new ArrayList<>();
        for (MaterialEntry entry : entries.values()) {
            if (onlyLeft && entry.remaining() <= 0) continue;
            if (!needle.isEmpty() && !entry.id().toLowerCase(Locale.ROOT).contains(needle)) continue;
            out.add(entry);
        }

        Comparator<MaterialEntry> comparator = switch (sort == null ? SortMode.NAME : sort) {
            case NAME -> Comparator.comparing(MaterialEntry::id);
            case REQUIRED -> Comparator.comparingInt(MaterialEntry::required).reversed();
            case REMAINING -> Comparator.comparingInt(MaterialEntry::remaining).reversed();
            case PROGRESS -> Comparator.comparingDouble(MaterialEntry::progress);
        };

        // A stable tie-break keeps the list from jumping around between frames.
        out.sort(comparator.thenComparing(MaterialEntry::id));
        return out;
    }

    /** Serialises the list as a compact {@code id=count} line for persistence. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (MaterialEntry entry : entries.values()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(entry.id()).append('=').append(entry.required());
        }
        return sb.toString();
    }

    /**
     * Restores a list written by {@link #serialize()}.
     *
     * <p>Progress is intentionally not restored: a session that ended mid-drop
     * must be reviewed by the user before it resumes.
     */
    public void deserialize(String data) {
        entries.clear();
        queue.clear();
        cursor = 0;
        if (data == null || data.isBlank()) return;

        MaterialListParser.Result parsed = MaterialListParser.parseTxt(data);
        setFrom(parsed.materials());
    }

    /**
     * Serialises the full run: the queue order, and per material the requirement
     * and how much was already confirmed.
     *
     * <p>Format, one material per line, in processing order:
     * <pre>index|id|required|dropped</pre>
     * A {@code |} separator is used because item ids never contain it, so the
     * parser stays trivial and cannot mistake a count for an id.
     */
    public String serializeRun() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < queue.size(); i++) {
            MaterialEntry entry = entries.get(queue.get(i));
            if (entry == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(i).append('|')
                .append(entry.id()).append('|')
                .append(entry.required()).append('|')
                .append(entry.dropped());
        }
        return sb.toString();
    }

    /**
     * Restores a run written by {@link #serializeRun()}, preserving the exact
     * queue order and every material's confirmed progress, so an interrupted run
     * resumes from where it stopped rather than from zero.
     *
     * <p>A malformed line is skipped rather than discarding the whole run.
     *
     * @return how many materials were restored
     */
    public int deserializeRun(String data) {
        entries.clear();
        queue.clear();
        cursor = 0;
        if (data == null || data.isBlank()) return 0;

        List<String> orderedIds = new ArrayList<>();
        Map<String, int[]> counts = new LinkedHashMap<>();

        for (String rawLine : data.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\|");
            if (parts.length != 4) continue;

            String id = MaterialIds.normalize(parts[1]);
            if (id == null) continue;

            int required;
            int dropped;
            try {
                required = Integer.parseInt(parts[2].trim());
                dropped = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (required <= 0) continue;

            orderedIds.add(id);
            counts.put(id, new int[]{required, dropped});
        }

        for (String id : orderedIds) {
            int[] value = counts.get(id);
            MaterialEntry entry = new MaterialEntry(id, value[0]);
            entry.restore(value[1]);
            entries.put(id, entry);
            queue.add(id);
        }

        return queue.size();
    }
}
