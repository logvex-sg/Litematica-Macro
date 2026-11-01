package com.schematicdropper.material;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and normalisation of item identifiers.
 *
 * <p>Identifiers are kept as strings here rather than {@code Identifier} so the
 * parser can be exercised without a running game.
 */
public final class MaterialIds {
    /** Vanilla-shaped {@code namespace:path}. */
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final String DEFAULT_NAMESPACE = "minecraft";

    private MaterialIds() {
    }

    /**
     * Normalises a raw identifier: trims, lower-cases, and defaults a missing
     * namespace to {@code minecraft}.
     *
     * @return the normalised identifier, or {@code null} when it is not a
     *         plausible item id.
     */
    public static String normalize(String raw) {
        if (raw == null) return null;

        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return null;

        // Users frequently paste "minecraft:stone" with stray quotes.
        id = stripQuotes(id);

        if (!id.contains(":")) id = DEFAULT_NAMESPACE + ":" + id;
        if (id.startsWith(":")) id = DEFAULT_NAMESPACE + id;
        if (id.endsWith(":")) return null;

        return VALID.matcher(id).matches() ? id : null;
    }

    public static boolean isValid(String id) {
        return id != null && VALID.matcher(id).matches();
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? DEFAULT_NAMESPACE : id.substring(0, colon);
    }

    public static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}
