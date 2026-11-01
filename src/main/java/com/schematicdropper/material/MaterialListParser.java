package com.schematicdropper.material;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses imported material lists.
 *
 * <p>Deliberately free of Minecraft types so the whole importer can be tested
 * without a game instance.
 *
 * <p>Every parse returns a {@link Result} rather than throwing: a malformed line
 * is reported and skipped so one bad row cannot lose an otherwise good list.
 */
public final class MaterialListParser {
    /** Guards against a typo like {@code minecraft:stone, 99999999999}. */
    public static final int MAX_COUNT = 1_000_000;

    private static final String[] ID_KEYS = {"id", "item", "name", "block", "material", "type"};
    private static final String[] COUNT_KEYS = {"count", "quantity", "amount", "qty", "required", "num"};

    private MaterialListParser() {
    }

    /** Outcome of an import: merged materials plus everything that went wrong. */
    public static final class Result {
        private final Map<String, Integer> materials = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        /** Merged, de-duplicated materials in first-seen order. */
        public Map<String, Integer> materials() {
            return materials;
        }

        public List<String> warnings() {
            return warnings;
        }

        public List<String> errors() {
            return errors;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean isEmpty() {
            return materials.isEmpty();
        }

        public int totalItems() {
            int total = 0;
            for (int count : materials.values()) total += count;
            return total;
        }

        /** Single-line summary suitable for a chat message. */
        public String summary() {
            return materials.size() + " material" + (materials.size() == 1 ? "" : "s")
                + ", " + totalItems() + " items"
                + (errors.isEmpty() ? "" : ", " + errors.size() + " error" + (errors.size() == 1 ? "" : "s"))
                + (warnings.isEmpty() ? "" : ", " + warnings.size() + " warning" + (warnings.size() == 1 ? "" : "s"));
        }

        /**
         * Adds a material, merging it with an existing entry of the same id.
         * This is the single place duplicates are handled, so every format
         * behaves identically.
         */
        void add(String id, int count) {
            Integer existing = materials.get(id);
            if (existing == null) {
                materials.put(id, count);
                return;
            }

            int merged = existing + count;
            if (merged > MAX_COUNT) {
                merged = MAX_COUNT;
                warnings.add("'" + id + "' exceeded the " + MAX_COUNT + " cap and was clamped");
            }
            materials.put(id, merged);
            warnings.add("duplicate '" + id + "' merged: " + existing + " + " + count + " = " + merged);
        }

        void error(String message) {
            errors.add(message);
        }

        void warn(String message) {
            warnings.add(message);
        }
    }

    /**
     * Parses {@code content} using {@code format}.
     *
     * @return a result that may contain materials, warnings, errors, or all three
     */
    public static Result parse(String content, ImportFormat format) {
        if (format == null) {
            Result result = new Result();
            result.error("unsupported file type; expected " + ImportFormat.allFilters());
            return result;
        }

        return switch (format) {
            case TXT -> parseTxt(content);
            case CSV -> parseCsv(content);
            case JSON -> parseJson(content);
        };
    }

    /** Reads and parses a file as UTF-8. */
    public static Result parseFile(Path path) {
        Result result = new Result();

        if (path == null) {
            result.error("no file selected");
            return result;
        }

        ImportFormat format = ImportFormat.fromFileName(path.getFileName().toString());
        if (format == null) {
            result.error("unsupported file type: " + path.getFileName() + " (expected " + ImportFormat.allFilters() + ")");
            return result;
        }

        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            result.error("cannot read file: " + path);
            return result;
        }

        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            result.error("failed to read " + path.getFileName() + ": " + e.getMessage());
            return result;
        }

        return parse(content, format);
    }

    // ------------------------------------------------------------------ TXT

    /**
     * Parses lines of {@code minecraft:stone, 128}.
     *
     * <p>Accepts comma, semicolon, equals or whitespace as the separator, and
     * tolerates the count appearing first.
     */
    public static Result parseTxt(String content) {
        Result result = new Result();
        if (content == null) {
            result.error("empty input");
            return result;
        }

        int lineNumber = 0;
        for (String rawLine : content.split("\r?\n")) {
            lineNumber++;

            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) continue;

            String[] tokens = line.split("[,;=\\s]+");
            List<String> parts = new ArrayList<>();
            for (String token : tokens) {
                if (!token.isBlank()) parts.add(token);
            }

            if (parts.size() != 2) {
                result.error("line " + lineNumber + ": expected '<id>, <count>' but found "
                    + parts.size() + " field(s): " + line);
                continue;
            }

            String first = parts.get(0);
            String second = parts.get(1);

            String id;
            String countText;
            if (isInteger(first) && !isInteger(second)) {
                countText = first;
                id = second;
            } else {
                id = first;
                countText = second;
            }

            addEntry(result, "line " + lineNumber, id, countText);
        }

        if (result.materials().isEmpty() && !result.hasErrors()) {
            result.error("no materials found in file");
        }
        return result;
    }

    // ------------------------------------------------------------------ CSV

    /**
     * Parses CSV with or without a header row.
     *
     * <p>Recognised headers: {@code id/item/name/block/material/type} and
     * {@code count/quantity/amount/qty/required}.
     */
    public static Result parseCsv(String content) {
        Result result = new Result();
        if (content == null) {
            result.error("empty input");
            return result;
        }

        List<String> lines = new ArrayList<>();
        for (String rawLine : content.split("\r?\n")) {
            String line = stripComment(rawLine).trim();
            if (!line.isEmpty()) lines.add(line);
        }

        if (lines.isEmpty()) {
            result.error("no materials found in file");
            return result;
        }

        int idColumn = 0;
        int countColumn = 1;
        int startLine = 0;

        String[] header = splitCsvLine(lines.get(0));
        int headerId = indexOfHeader(header, ID_KEYS);
        int headerCount = indexOfHeader(header, COUNT_KEYS);

        if (headerId >= 0 || headerCount >= 0) {
            if (headerId >= 0 && headerCount >= 0) {
                idColumn = headerId;
                countColumn = headerCount;
            } else {
                result.warn("header row is missing an id or count column; assuming <id>, <count>");
            }
            startLine = 1;
        }

        for (int i = startLine; i < lines.size(); i++) {
            int lineNumber = i + 1;
            String[] fields = splitCsvLine(lines.get(i));
            int needed = Math.max(idColumn, countColumn);

            if (fields.length <= needed) {
                result.error("line " + lineNumber + ": expected at least " + (needed + 1) + " column(s)");
                continue;
            }

            addEntry(result, "line " + lineNumber, fields[idColumn].trim(), fields[countColumn].trim());
        }

        if (result.materials().isEmpty() && !result.hasErrors()) {
            result.error("no materials found in file");
        }
        return result;
    }

    /** Splits one CSV record, honouring double-quoted fields. */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                // "" inside a quoted field is an escaped quote.
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if ((c == ',' || c == ';' || c == '\t') && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static int indexOfHeader(String[] header, String[] keys) {
        for (int i = 0; i < header.length; i++) {
            String field = header[i].trim().toLowerCase(Locale.ROOT);
            for (String key : keys) {
                if (field.equals(key)) return i;
            }
        }
        return -1;
    }

    // ----------------------------------------------------------------- JSON

    /**
     * Parses JSON. Accepted shapes:
     * <pre>
     * {"materials":[{"id":"minecraft:stone","count":128}]}
     * {"materials":{"minecraft:stone":128}}
     * [{"id":"minecraft:stone","count":128}]
     * {"minecraft:stone":128}
     * </pre>
     */
    public static Result parseJson(String content) {
        Result result = new Result();
        if (content == null || content.isBlank()) {
            result.error("empty input");
            return result;
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(content);
        } catch (JsonSyntaxException e) {
            result.error("invalid JSON: " + e.getMessage());
            return result;
        }

        if (root == null || root.isJsonNull()) {
            result.error("invalid JSON: empty document");
            return result;
        }

        if (root.isJsonArray()) {
            readArray(result, root.getAsJsonArray(), "materials");
            return finishJson(result);
        }

        if (!root.isJsonObject()) {
            result.error("invalid JSON: expected an object or array at the top level");
            return result;
        }

        JsonObject object = root.getAsJsonObject();

        // The documented wrapper first.
        for (String key : new String[]{"materials", "items", "blocks"}) {
            JsonElement wrapped = object.get(key);
            if (wrapped == null) continue;

            if (wrapped.isJsonArray()) {
                readArray(result, wrapped.getAsJsonArray(), key);
                return finishJson(result);
            }
            if (wrapped.isJsonObject()) {
                readMap(result, wrapped.getAsJsonObject(), key);
                return finishJson(result);
            }

            result.error("invalid JSON: '" + key + "' must be an array or object");
            return result;
        }

        // Fall back to a bare id -> count map.
        readMap(result, object, "root");
        return finishJson(result);
    }

    private static Result finishJson(Result result) {
        if (result.materials().isEmpty() && !result.hasErrors()) {
            result.error("no materials found in file");
        }
        return result;
    }

    private static void readArray(Result result, JsonArray array, String where) {
        int index = 0;
        for (JsonElement element : array) {
            index++;

            if (!element.isJsonObject()) {
                result.error(where + "[" + index + "]: expected an object");
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            JsonElement idElement = firstPresent(entry, ID_KEYS);
            JsonElement countElement = firstPresent(entry, COUNT_KEYS);

            if (idElement == null) {
                result.error(where + "[" + index + "]: missing an id field");
                continue;
            }
            if (countElement == null) {
                result.error(where + "[" + index + "]: missing a count field");
                continue;
            }

            addEntry(result, where + "[" + index + "]", asString(idElement), asString(countElement));
        }
    }

    private static void readMap(Result result, JsonObject object, String where) {
        if (object.isEmpty()) {
            result.error(where + ": no materials found");
            return;
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();

            // Skip metadata keys a hand-written file may carry alongside materials.
            if (isMetadataKey(entry.getKey())) continue;

            if (!value.isJsonPrimitive()) {
                result.error(where + ": '" + entry.getKey() + "' must map to a number");
                continue;
            }

            addEntry(result, where + " '" + entry.getKey() + "'", entry.getKey(), asString(value));
        }
    }

    private static boolean isMetadataKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("name") || lower.equals("author") || lower.equals("description")
            || lower.equals("version") || lower.equals("format");
    }

    private static JsonElement firstPresent(JsonObject object, String[] keys) {
        for (String key : keys) {
            JsonElement element = object.get(key);
            if (element != null && !element.isJsonNull()) return element;
        }
        return null;
    }

    private static String asString(JsonElement element) {
        if (element == null) return null;
        if (element.isJsonPrimitive()) return element.getAsString();
        return null;
    }

    // ---------------------------------------------------------------- shared

    /**
     * Validates one id/count pair and records it.
     *
     * @param location human-readable position, used in error messages
     */
    private static void addEntry(Result result, String location, String rawId, String rawCount) {
        if (rawCount == null) {
            result.error(location + ": missing quantity");
            return;
        }

        // Parsed as a long so a wildly out-of-range value is reported as "too
        // large" rather than as a parse failure.
        long parsed;
        try {
            // Tolerate "128.0" from JSON, but nothing else.
            String trimmed = rawCount.trim();
            parsed = trimmed.contains(".")
                ? Math.round(Double.parseDouble(trimmed))
                : Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            result.error(location + ": '" + rawCount + "' is not a valid quantity");
            return;
        }

        if (parsed <= 0) {
            result.error(location + ": quantity must be greater than zero, got " + parsed);
            return;
        }

        if (parsed > MAX_COUNT) {
            result.error(location + ": quantity " + parsed + " exceeds the " + MAX_COUNT + " cap");
            return;
        }

        int count = (int) parsed;

        String id = MaterialIds.normalize(rawId);
        if (id == null) {
            result.error(location + ": '" + rawId + "' is not a valid item id (expected namespace:path)");
            return;
        }

        result.add(id, count);
    }

    /** Removes {@code #} and {@code //} trailing comments. */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        if (hash >= 0) line = line.substring(0, hash);

        int slashes = line.indexOf("//");
        if (slashes >= 0) line = line.substring(0, slashes);

        return line;
    }

    private static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
