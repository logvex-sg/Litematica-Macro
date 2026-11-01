package com.schematicdropper.material;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialManagerTest {
    private static MaterialManager manager() {
        Map<String, Integer> materials = new LinkedHashMap<>();
        materials.put("minecraft:stone", 130);
        materials.put("minecraft:dirt", 70);
        materials.put("minecraft:glass", 17);

        MaterialManager manager = new MaterialManager();
        manager.setFrom(materials);
        return manager;
    }

    @Test
    void tracksRequiredDroppedAndRemaining() {
        MaterialManager manager = manager();

        assertEquals(3, manager.size());
        assertEquals(217, manager.totalRequired());
        assertEquals(0, manager.totalDropped());
        assertEquals(217, manager.totalRemaining());

        manager.confirm("minecraft:stone", 128);

        MaterialEntry stone = manager.get("minecraft:stone");
        assertEquals(128, stone.dropped());
        assertEquals(2, stone.remaining());
        assertEquals(128, manager.totalDropped());
        assertEquals(89, manager.totalRemaining());
        assertFalse(manager.isComplete());
    }

    @Test
    void confirmsAreClampedToTheRequirement() {
        MaterialManager manager = manager();

        // A duplicated server acknowledgement must not overshoot.
        manager.confirm("minecraft:glass", 17);
        manager.confirm("minecraft:glass", 17);

        assertEquals(17, manager.get("minecraft:glass").dropped());
        assertEquals(0, manager.get("minecraft:glass").remaining());
        assertTrue(manager.get("minecraft:glass").isComplete());
    }

    @Test
    void completionIsOnlyReportedWhenEveryMaterialIsDone() {
        MaterialManager manager = manager();
        assertFalse(manager.isComplete());

        manager.confirm("minecraft:stone", 130);
        assertFalse(manager.isComplete());

        manager.confirm("minecraft:dirt", 70);
        manager.confirm("minecraft:glass", 17);

        assertTrue(manager.isComplete());
        assertEquals(1.0, manager.overallProgress(), 1e-9);
    }

    @Test
    void overallProgressReflectsConfirmedDropsOnly() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 65);

        // 65 of 217.
        assertEquals(65.0 / 217.0, manager.overallProgress(), 1e-9);
    }

    @Test
    void anEmptyManagerIsNotComplete() {
        MaterialManager manager = new MaterialManager();
        assertFalse(manager.isComplete());
        assertEquals(1.0, manager.overallProgress(), 1e-9);
    }

    @Test
    void missingAndStalledMaterialsAreNotScheduled() {
        MaterialManager manager = manager();
        manager.markMissing("minecraft:stone");
        manager.markStalled("minecraft:dirt");

        List<MaterialEntry> pending = manager.pending();
        assertEquals(1, pending.size());
        assertEquals("minecraft:glass", pending.get(0).id());

        assertEquals(1, manager.missingCount());
    }

    @Test
    void nextCyclesThroughPendingMaterialsSoNoneStarve() {
        MaterialManager manager = manager();

        String first = manager.next().id();
        String second = manager.next().id();
        String third = manager.next().id();
        String fourth = manager.next().id();

        assertEquals(3, java.util.Set.of(first, second, third).size());
        assertEquals(first, fourth);
    }

    @Test
    void nextReturnsNullWhenNothingIsLeft() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);
        manager.confirm("minecraft:dirt", 70);
        manager.confirm("minecraft:glass", 17);

        assertNull(manager.next());
        assertTrue(manager.pending().isEmpty());
    }

    @Test
    void resetProgressKeepsRequirementsAndClearsCounts() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);

        manager.resetProgress();

        assertEquals(0, manager.totalDropped());
        assertEquals(217, manager.totalRequired());
        assertFalse(manager.get("minecraft:stone").isComplete());
        assertEquals(MaterialEntry.Status.PENDING, manager.get("minecraft:stone").status());
    }

    @Test
    void revokeRollsBackAFalseConfirmation() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);
        assertTrue(manager.get("minecraft:stone").isComplete());

        manager.revoke("minecraft:stone", 130);

        assertEquals(0, manager.get("minecraft:stone").dropped());
        assertFalse(manager.get("minecraft:stone").isComplete());
    }

    @Test
    void clearEmptiesTheList() {
        MaterialManager manager = manager();
        manager.clear();

        assertTrue(manager.isEmpty());
        assertEquals(0, manager.totalRequired());
        assertNull(manager.next());
    }

    @Test
    void setFromIgnoresNonPositiveQuantities() {
        Map<String, Integer> materials = new LinkedHashMap<>();
        materials.put("minecraft:stone", 10);
        materials.put("minecraft:dirt", 0);
        materials.put("minecraft:glass", -4);

        MaterialManager manager = new MaterialManager();
        manager.setFrom(materials);

        assertEquals(1, manager.size());
        assertNotNull(manager.get("minecraft:stone"));
        assertNull(manager.get("minecraft:dirt"));
    }

    // -------------------------------------------------------------- VIEWING

    @Test
    void viewFiltersBySubstring() {
        MaterialManager manager = manager();

        List<MaterialEntry> filtered = manager.view("stone", MaterialManager.SortMode.NAME, false);
        assertEquals(1, filtered.size());
        assertEquals("minecraft:stone", filtered.get(0).id());
    }

    @Test
    void viewCanShowOnlyOutstandingMaterials() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:glass", 17);

        assertEquals(3, manager.view("", MaterialManager.SortMode.NAME, false).size());
        assertEquals(2, manager.view("", MaterialManager.SortMode.NAME, true).size());
    }

    @Test
    void viewSortsByEachMode() {
        MaterialManager manager = manager();

        assertEquals("minecraft:stone",
            manager.view("", MaterialManager.SortMode.REQUIRED, false).get(0).id());
        assertEquals("minecraft:dirt",
            manager.view("", MaterialManager.SortMode.NAME, false).get(0).id());

        // Give dirt a partial head start so progress ordering is unambiguous.
        manager.confirm("minecraft:dirt", 35);
        // Least progress first: stone and glass are both at zero, so the stable
        // id tie-break decides, and "glass" sorts before "stone".
        assertEquals("minecraft:glass",
            manager.view("", MaterialManager.SortMode.PROGRESS, false).get(0).id());
        assertEquals("minecraft:dirt",
            manager.view("", MaterialManager.SortMode.PROGRESS, false).get(2).id());
    }

    @Test
    void viewSortsByRemaining() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:glass", 17);

        // Stone has the most left, glass has none and is excluded by onlyLeft.
        assertEquals("minecraft:stone",
            manager.view("", MaterialManager.SortMode.REMAINING, false).get(0).id());
    }

    // --------------------------------------------------------- PERSISTENCE

    @Test
    void serialiseAndRestoreRoundTrip() {
        MaterialManager manager = manager();
        String data = manager.serialize();

        MaterialManager restored = new MaterialManager();
        restored.deserialize(data);

        assertEquals(3, restored.size());
        assertEquals(130, restored.get("minecraft:stone").required());
        assertEquals(70, restored.get("minecraft:dirt").required());
        assertEquals(17, restored.get("minecraft:glass").required());
    }

    @Test
    void restoringDoesNotResumeAnInterruptedOperation() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);

        MaterialManager restored = new MaterialManager();
        restored.deserialize(manager.serialize());

        // The user must explicitly restart; nothing is silently resumed.
        assertEquals(0, restored.totalDropped());
    }

    @Test
    void deserialisingGarbageYieldsAnEmptyList() {
        MaterialManager manager = new MaterialManager();
        manager.deserialize("not a material list at all");
        assertTrue(manager.isEmpty());

        manager.deserialize(null);
        assertTrue(manager.isEmpty());

        manager.deserialize("");
        assertTrue(manager.isEmpty());
    }

    @Test
    void removingNoItemBlocksKeepsEverythingElse() {
        MaterialManager manager = new MaterialManager();
        manager.setFrom(java.util.Map.of(
            "minecraft:air", 100,
            "minecraft:stone", 10,
            "minecraft:barrier", 4));

        int removed = manager.removeNoItemBlocks(id -> id.equals("minecraft:air"));

        assertEquals(1, removed);
        assertEquals(2, manager.size());
        assertEquals(10, manager.get("minecraft:stone").required());
        assertEquals(4, manager.get("minecraft:barrier").required());
    }

    @Test
    void removingEverythingLeavesAnEmptyList() {
        MaterialManager manager = new MaterialManager();
        manager.setFrom(java.util.Map.of("minecraft:air", 100));

        assertEquals(1, manager.removeNoItemBlocks(id -> true));
        assertTrue(manager.isEmpty());
        assertTrue(manager.pending().isEmpty());

        // An empty list is not a finished one: nothing was ever dropped.
        assertFalse(manager.isComplete());
    }

    // ----------------------------------------------------------- ORDERING

    @Test
    void schematicOrderIsTheSourceOrder() {
        MaterialManager manager = manager();
        assertEquals(
            List.of("minecraft:stone", "minecraft:dirt", "minecraft:glass"),
            manager.order());
        assertEquals("minecraft:stone", manager.firstPending().id());
    }

    @Test
    void alphabeticalOrderIsAppliedToTheQueue() {
        Map<String, Integer> materials = new LinkedHashMap<>();
        materials.put("minecraft:stone", 1);
        materials.put("minecraft:apple", 1);
        materials.put("minecraft:zombie_head", 1);

        MaterialManager manager = new MaterialManager();
        manager.setFrom(materials, MaterialOrder.ALPHABETICAL);

        assertEquals(
            List.of("minecraft:apple", "minecraft:stone", "minecraft:zombie_head"),
            manager.order());
    }

    @Test
    void requiredQuantityOrderIsLargestFirst() {
        Map<String, Integer> materials = new LinkedHashMap<>();
        materials.put("minecraft:stone", 10);
        materials.put("minecraft:dirt", 128);
        materials.put("minecraft:glass", 64);

        MaterialManager manager = new MaterialManager();
        manager.setFrom(materials, MaterialOrder.REQUIRED_QUANTITY);

        assertEquals(
            List.of("minecraft:dirt", "minecraft:glass", "minecraft:stone"),
            manager.order());
    }

    @Test
    void firstPendingSkipsCompletedMaterialsButKeepsBlockedOnes() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);
        manager.markBlocked("minecraft:dirt", com.schematicdropper.drop.FailureKind.MATERIAL_UNAVAILABLE);

        // The completed material is skipped; the blocked one keeps its place.
        assertEquals("minecraft:dirt", manager.firstPending().id());
        // Best effort walks past the blocked material.
        assertEquals("minecraft:glass", manager.firstProcessable().id());
    }

    @Test
    void blockedMaterialsKeepTheirQueuePositionAndRemaining() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 64);
        manager.markBlocked("minecraft:stone", com.schematicdropper.drop.FailureKind.SERVER_TIMEOUT);

        assertEquals(
            List.of("minecraft:stone", "minecraft:dirt", "minecraft:glass"),
            manager.order(),
            "blocking must not reorder the queue");
        assertEquals(66, manager.get("minecraft:stone").remaining());
        assertEquals(1, manager.blockedCount());
        assertTrue(manager.hasBlocked());
        assertEquals(66, manager.blockedRemaining());

        manager.unblock("minecraft:stone");
        assertEquals(0, manager.blockedCount());
        assertEquals(MaterialEntry.Status.PENDING, manager.get("minecraft:stone").status());
        // The confirmed progress is untouched by blocking and unblocking.
        assertEquals(64, manager.get("minecraft:stone").dropped());
    }

    @Test
    void aRunIsNotCompleteWhileAnythingIsBlocked() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);
        manager.confirm("minecraft:dirt", 70);
        manager.confirm("minecraft:glass", 17);
        assertTrue(manager.isComplete());

        // Revoke a little and block it: the run is no longer complete.
        manager.revoke("minecraft:glass", 1);
        manager.markBlocked("minecraft:glass", com.schematicdropper.drop.FailureKind.MATERIAL_UNAVAILABLE);
        assertFalse(manager.isComplete());
    }

    // ------------------------------------------------- RUN PERSISTENCE

    @Test
    void serializeRunRoundTripsOrderAndProgress() {
        MaterialManager manager = manager();
        manager.confirm("minecraft:stone", 130);
        manager.confirm("minecraft:dirt", 35);

        MaterialManager restored = new MaterialManager();
        int count = restored.deserializeRun(manager.serializeRun());

        assertEquals(3, count);
        assertEquals(manager.order(), restored.order());
        assertEquals(130, restored.get("minecraft:stone").dropped());
        assertEquals(35, restored.get("minecraft:dirt").dropped());
        assertEquals(0, restored.get("minecraft:glass").dropped());
        assertTrue(restored.get("minecraft:stone").isComplete());
        assertEquals(217 - 165, restored.totalRemaining());
    }

    @Test
    void deserializeRunSkipsMalformedLines() {
        MaterialManager manager = new MaterialManager();
        int count = manager.deserializeRun(
            "0|minecraft:stone|64|32\n"
                + "garbage\n"
                + "1||64|0\n"
                + "2|minecraft:dirt|notanumber|0\n"
                + "3|minecraft:glass|0|0\n"
                + "4|minecraft:sand|16|4");

        assertEquals(2, count);
        assertEquals(List.of("minecraft:stone", "minecraft:sand"), manager.order());
        assertEquals(32, manager.get("minecraft:stone").dropped());
        assertEquals(4, manager.get("minecraft:sand").dropped());
    }

    @Test
    void deserializeRunClampsProgressToTheRequirement() {
        MaterialManager manager = new MaterialManager();
        manager.deserializeRun("0|minecraft:stone|64|9999");

        assertEquals(64, manager.get("minecraft:stone").dropped());
        assertEquals(0, manager.get("minecraft:stone").remaining());
    }

    @Test
    void deserializeRunOfNothingLeavesAnEmptyList() {
        MaterialManager manager = new MaterialManager();
        assertEquals(0, manager.deserializeRun(null));
        assertEquals(0, manager.deserializeRun(""));
        assertTrue(manager.isEmpty());
    }
}
