package com.schematicdropper.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialIdsTest {
    @Test
    void keepsAFullyQualifiedId() {
        assertEquals("minecraft:stone", MaterialIds.normalize("minecraft:stone"));
    }

    @Test
    void defaultsTheNamespaceWhenMissing() {
        assertEquals("minecraft:stone", MaterialIds.normalize("stone"));
    }

    @Test
    void trimsAndLowerCases() {
        assertEquals("minecraft:stone", MaterialIds.normalize("  MINECRAFT:Stone  "));
    }

    @Test
    void stripsSurroundingQuotes() {
        assertEquals("minecraft:stone", MaterialIds.normalize("\"minecraft:stone\""));
        assertEquals("minecraft:stone", MaterialIds.normalize("'minecraft:stone'"));
    }

    @Test
    void keepsModdedNamespacesAndPaths() {
        assertEquals("create:crushed_raw_iron", MaterialIds.normalize("create:crushed_raw_iron"));
        assertEquals("minecraft:oak_log", MaterialIds.normalize("oak_log"));
    }

    @Test
    void rejectsInvalidIdentifiers() {
        assertNull(MaterialIds.normalize(null));
        assertNull(MaterialIds.normalize(""));
        assertNull(MaterialIds.normalize("   "));
        assertNull(MaterialIds.normalize("minecraft:"));
        assertNull(MaterialIds.normalize("has space:stone"));
        assertNull(MaterialIds.normalize("minecraft:stone!"));
        assertNull(MaterialIds.normalize("minecraft:stone,"));
    }

    @Test
    void repairsALeadingColon() {
        assertEquals("minecraft:stone", MaterialIds.normalize(":stone"));
    }

    @Test
    void isValidMatchesNormalize() {
        assertTrue(MaterialIds.isValid("minecraft:stone"));
        assertFalse(MaterialIds.isValid("stone"));
        assertFalse(MaterialIds.isValid(null));
    }

    @Test
    void splitsNamespaceAndPath() {
        assertEquals("minecraft", MaterialIds.namespace("minecraft:stone"));
        assertEquals("stone", MaterialIds.path("minecraft:stone"));
        assertEquals("minecraft", MaterialIds.namespace("stone"));
        assertEquals("stone", MaterialIds.path("stone"));
    }
}
