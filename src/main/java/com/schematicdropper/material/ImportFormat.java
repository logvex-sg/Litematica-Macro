package com.schematicdropper.material;

import java.util.Locale;

/** File formats the material list importer understands. */
public enum ImportFormat {
    TXT("txt"),
    JSON("json"),
    CSV("csv");

    private final String extension;

    ImportFormat(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public String filterName() {
        return "*." + extension;
    }

    /**
     * Picks a format from a file name.
     *
     * @return the matching format, or {@code null} when the extension is not one
     *         the importer handles.
     */
    public static ImportFormat fromFileName(String fileName) {
        if (fileName == null) return null;

        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return null;

        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (ImportFormat format : values()) {
            if (format.extension.equals(ext)) return format;
        }
        return null;
    }

    /** All formats as a TinyFileDialogs filter string, e.g. {@code *.txt;*.json;*.csv}. */
    public static String allFilters() {
        StringBuilder sb = new StringBuilder();
        for (ImportFormat format : values()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(format.filterName());
        }
        return sb.toString();
    }
}
