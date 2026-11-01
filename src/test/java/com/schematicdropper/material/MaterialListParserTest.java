package com.schematicdropper.material;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialListParserTest {
    // ------------------------------------------------------------------ TXT

    @Test
    void parsesTheDocumentedTxtExample() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            minecraft:stone, 128
            minecraft:dirt, 64
            minecraft:glass, 32
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(3, result.materials().size());
        assertEquals(128, result.materials().get("minecraft:stone"));
        assertEquals(64, result.materials().get("minecraft:dirt"));
        assertEquals(32, result.materials().get("minecraft:glass"));
        assertEquals(224, result.totalItems());
    }

    @Test
    void txtAcceptsSeveralSeparators() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            minecraft:stone, 10
            minecraft:dirt;20
            minecraft:glass = 30
            minecraft:sand 40
            minecraft:gravel\t50
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(5, result.materials().size());
        assertEquals(10, result.materials().get("minecraft:stone"));
        assertEquals(20, result.materials().get("minecraft:dirt"));
        assertEquals(30, result.materials().get("minecraft:glass"));
        assertEquals(40, result.materials().get("minecraft:sand"));
        assertEquals(50, result.materials().get("minecraft:gravel"));
    }

    @Test
    void txtToleratesCountBeforeId() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("128 minecraft:stone");
        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void txtSkipsBlankLinesAndComments() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            # a comment

            minecraft:stone, 128 // trailing comment
               \s
            minecraft:dirt, 64
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(2, result.materials().size());
    }

    @Test
    void txtReportsAMalformedLineButKeepsTheRest() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            minecraft:stone, 128
            this line is nonsense
            minecraft:dirt, 64
            """);

        assertTrue(result.hasErrors());
        assertEquals(2, result.materials().size());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("line 2"), result.errors().get(0));
    }

    // ---------------------------------------------------------------- MERGE

    @Test
    void mergesDuplicateEntriesAcrossLines() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            minecraft:stone, 100
            minecraft:stone, 30
            """);

        assertEquals(1, result.materials().size());
        assertEquals(130, result.materials().get("minecraft:stone"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("merged")), result.warnings().toString());
    }

    @Test
    void mergingIsCaseAndNamespaceInsensitive() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            STONE, 10
            minecraft:stone, 5
            """);

        assertEquals(1, result.materials().size());
        assertEquals(15, result.materials().get("minecraft:stone"));
    }

    // ------------------------------------------------------------ VALIDATION

    @Test
    void rejectsZeroAndNegativeQuantities() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("""
            minecraft:stone, 0
            minecraft:dirt, -5
            """);

        assertTrue(result.isEmpty());
        assertEquals(2, result.errors().size());
    }

    @Test
    void rejectsNonNumericQuantities() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("minecraft:stone, lots");
        assertTrue(result.isEmpty());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("not a valid quantity"));
    }

    @Test
    void rejectsQuantitiesAboveTheCap() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("minecraft:stone, 99999999999");
        assertTrue(result.isEmpty());
        assertTrue(result.errors().get(0).contains("exceeds"));
    }

    @Test
    void rejectsInvalidIdentifiers() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("minecraft:stone!, 10");
        assertTrue(result.isEmpty());
        assertTrue(result.errors().get(0).contains("not a valid item id"));
    }

    @Test
    void anEmptyFileIsReported() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("\n\n   \n");
        assertTrue(result.isEmpty());
        assertTrue(result.hasErrors());
    }

    // ----------------------------------------------------------------- CSV

    @Test
    void parsesCsvWithAHeader() {
        MaterialListParser.Result result = MaterialListParser.parseCsv("""
            id,count
            minecraft:stone,128
            minecraft:dirt,64
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(2, result.materials().size());
        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void parsesCsvWithoutAHeader() {
        MaterialListParser.Result result = MaterialListParser.parseCsv("""
            minecraft:stone,128
            minecraft:dirt,64
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(2, result.materials().size());
    }

    @Test
    void parsesCsvWithReorderedColumns() {
        MaterialListParser.Result result = MaterialListParser.parseCsv("""
            count,item
            128,minecraft:stone
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void parsesCsvWithQuotedFieldsAndSemicolons() {
        MaterialListParser.Result result = MaterialListParser.parseCsv("""
            "item";"quantity"
            "minecraft:stone";"128"
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void csvMergesDuplicates() {
        MaterialListParser.Result result = MaterialListParser.parseCsv("""
            minecraft:stone,100
            minecraft:stone,30
            """);

        assertEquals(130, result.materials().get("minecraft:stone"));
    }

    @Test
    void csvReportsShortRows() {
        MaterialListParser.Result result = MaterialListParser.parseCsv("""
            id,count
            minecraft:stone
            """);

        assertTrue(result.hasErrors());
    }

    // ---------------------------------------------------------------- JSON

    @Test
    void parsesTheDocumentedJsonExample() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            {
              "materials": [
                {"id": "minecraft:stone", "count": 128},
                {"id": "minecraft:dirt", "count": 64}
              ]
            }
            """);

        assertFalse(result.hasErrors(), result.errors().toString());
        assertEquals(2, result.materials().size());
        assertEquals(128, result.materials().get("minecraft:stone"));
        assertEquals(64, result.materials().get("minecraft:dirt"));
    }

    @Test
    void parsesABareArray() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            [{"id": "minecraft:stone", "count": 128}]
            """);

        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void parsesAnIdToCountMap() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            {"minecraft:stone": 128, "minecraft:dirt": 64}
            """);

        assertEquals(2, result.materials().size());
        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void parsesANestedIdToCountMap() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            {"materials": {"minecraft:stone": 128}}
            """);

        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void jsonMergesDuplicatesAcrossEntries() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            {"materials": [
              {"id": "minecraft:stone", "count": 100},
              {"id": "minecraft:stone", "count": 30}
            ]}
            """);

        assertEquals(1, result.materials().size());
        assertEquals(130, result.materials().get("minecraft:stone"));
    }

    @Test
    void jsonAcceptsAlternateFieldNames() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            [{"item": "minecraft:stone", "quantity": 128}]
            """);

        assertEquals(128, result.materials().get("minecraft:stone"));
    }

    @Test
    void jsonReportsMalformedSyntax() {
        MaterialListParser.Result result = MaterialListParser.parseJson("{\"materials\": [");
        assertTrue(result.isEmpty());
        assertTrue(result.errors().get(0).contains("invalid JSON"));
    }

    @Test
    void jsonReportsMissingFields() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            {"materials": [{"id": "minecraft:stone"}]}
            """);

        assertTrue(result.isEmpty());
        assertTrue(result.errors().get(0).contains("missing a count"));
    }

    @Test
    void jsonRejectsANonObjectElement() {
        MaterialListParser.Result result = MaterialListParser.parseJson("""
            {"materials": ["minecraft:stone"]}
            """);

        assertTrue(result.isEmpty());
        assertTrue(result.hasErrors());
    }

    @Test
    void jsonEmptyMaterialsIsReported() {
        MaterialListParser.Result result = MaterialListParser.parseJson("{\"materials\": []}");
        assertTrue(result.isEmpty());
        assertTrue(result.hasErrors());
    }

    // ----------------------------------------------------------- DISPATCHING

    @Test
    void parseDispatchesOnFormat() {
        Map<String, Integer> txt = MaterialListParser.parse("minecraft:stone, 5", ImportFormat.TXT).materials();
        assertEquals(5, txt.get("minecraft:stone"));

        Map<String, Integer> csv = MaterialListParser.parse("minecraft:stone,5", ImportFormat.CSV).materials();
        assertEquals(5, csv.get("minecraft:stone"));

        Map<String, Integer> json = MaterialListParser.parse("{\"minecraft:stone\":5}", ImportFormat.JSON).materials();
        assertEquals(5, json.get("minecraft:stone"));
    }

    @Test
    void unknownFormatIsReportedNotThrown() {
        MaterialListParser.Result result = MaterialListParser.parse("minecraft:stone, 5", null);
        assertTrue(result.isEmpty());
        assertTrue(result.hasErrors());
    }

    @Test
    void importFormatIsPickedFromTheExtension() {
        assertEquals(ImportFormat.TXT, ImportFormat.fromFileName("list.txt"));
        assertEquals(ImportFormat.JSON, ImportFormat.fromFileName("LIST.JSON"));
        assertEquals(ImportFormat.CSV, ImportFormat.fromFileName("a.b.csv"));
        assertEquals(null, ImportFormat.fromFileName("list.litematic"));
        assertEquals(null, ImportFormat.fromFileName("noext"));
    }

    @Test
    void summaryIsReadable() {
        MaterialListParser.Result result = MaterialListParser.parseTxt("minecraft:stone, 10");
        assertEquals("1 material, 10 items", result.summary());
    }
}
