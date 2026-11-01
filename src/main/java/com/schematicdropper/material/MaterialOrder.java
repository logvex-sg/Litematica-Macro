package com.schematicdropper.material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The deterministic order in which materials are processed.
 *
 * <p>The order is chosen once, when a run starts, and does not change while it is
 * running: a material keeps its position in the queue even if it is temporarily
 * unavailable, so recovering from a failure resumes the exact same sequence.
 *
 * <p>Every ordering is a stable sort of the list's natural order, so two entries
 * that compare equal (or an ordering that does not distinguish them) keep the
 * order they were imported in. That is what makes the result reproducible.
 */
public enum MaterialOrder {
    /** The order the schematic (or Litematica's material list) produced. Default. */
    SCHEMATIC_ORDER("Schematic order"),
    /** The order the material list file listed them in. */
    IMPORTED_ORDER("Imported list order"),
    /** Ascending item id. */
    ALPHABETICAL("Alphabetical"),
    /** Largest requirement first, so the longest jobs are not left until last. */
    REQUIRED_QUANTITY("Required quantity");

    private final String title;

    MaterialOrder(String title) {
        this.title = title;
    }

    @Override
    public String toString() {
        return title;
    }

    /**
     * Orders {@code entries} for processing.
     *
     * <p>Both the schematic and the imported list arrive as a {@link java.util.LinkedHashMap},
     * so their natural iteration order <em>is</em> the source order. For those two
     * modes this returns the list unchanged; the other modes sort a copy.
     *
     * @return a new list in the requested order; never {@code null}
     */
    public List<MaterialEntry> order(List<MaterialEntry> entries) {
        List<MaterialEntry> ordered = new ArrayList<>(entries);
        if (ordered.size() < 2) return ordered;

        Comparator<MaterialEntry> comparator = switch (this) {
            case SCHEMATIC_ORDER, IMPORTED_ORDER -> null;
            case ALPHABETICAL -> Comparator.comparing(
                entry -> entry.id().toLowerCase(Locale.ROOT));
            case REQUIRED_QUANTITY -> Comparator.comparingInt(MaterialEntry::required).reversed();
        };

        if (comparator != null) {
            // List.sort is stable, so equal entries keep their source order and a
            // tie-break is unnecessary.
            ordered.sort(comparator);
        }
        return ordered;
    }
}
