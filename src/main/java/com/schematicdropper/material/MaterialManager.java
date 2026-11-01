package com.schematicdropper.material;

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
 * <p>Holds no Minecraft types, so progress tracking, resume and cancellation can
 * be tested directly.
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
    private int cursor = 0;

    /** Replaces the whole list from an import, merging duplicates is the parser's job. */
    public void setFrom(Map<String, Integer> materials) {
        entries.clear();
        cursor = 0;

        if (materials == null) return;
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) continue;
            entries.put(entry.getKey(), new MaterialEntry(entry.getKey(), entry.getValue()));
        }
    }

    public void clear() {
        entries.clear();
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
        if (cursor >= entries.size()) cursor = 0;
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

    public List<MaterialEntry> all() {
        return new ArrayList<>(entries.values());
    }

    /** Every entry that still has something outstanding and is still droppable. */
    public List<MaterialEntry> pending() {
        List<MaterialEntry> out = new ArrayList<>();
        for (MaterialEntry entry : entries.values()) {
            if (entry.remaining() > 0
                && entry.status() != MaterialEntry.Status.MISSING
                && entry.status() != MaterialEntry.Status.STALLED) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * The next material to work on, advancing a cursor so a material that is
     * waiting on a confirmation does not starve the others.
     *
     * @return the next droppable entry, or {@code null} when nothing is left
     */
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

    /** Restarts progress from zero, keeping the requirement. */
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
        cursor = 0;
        if (data == null || data.isBlank()) return;

        MaterialListParser.Result parsed = MaterialListParser.parseTxt(data);
        setFrom(parsed.materials());
    }
}
