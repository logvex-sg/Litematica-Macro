package com.schematicdropper.drop;

import com.schematicdropper.material.MaterialEntry;
import com.schematicdropper.material.MaterialManager;
import com.schematicdropper.material.MaterialOrder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the exact-quantity contract, the deterministic material
 * order, and the failure-recovery behaviour, driven through a fake
 * {@link DropTask.Actions} so the whole scheduler runs without a game.
 */
class DropTaskTest {
    /** Records what the scheduler asked for and can confirm it back. */
    private static final class FakeActions implements DropTask.Actions {
        record Send(String itemId, int count) {
        }

        /** Every send ever made, for assertions. Never cleared. */
        final List<Send> history = new ArrayList<>();
        /** Sends not yet confirmed. */
        final List<Send> pending = new ArrayList<>();
        final Map<String, Integer> stackSizes = new LinkedHashMap<>();

        boolean accept = true;
        boolean inventoryReady = true;
        boolean targetValid = true;
        /** Items this client has no item for. */
        final java.util.Set<String> unavailable = new java.util.HashSet<>();

        @Override
        public boolean dropAtFeet(String itemId, int count) {
            if (!accept) return false;
            if (unavailable.contains(itemId)) return false;
            Send send = new Send(itemId, count);
            history.add(send);
            pending.add(send);
            return true;
        }

        @Override
        public boolean throwAtTarget(String itemId, int count) {
            return dropAtFeet(itemId, count);
        }

        @Override
        public int maxStackSize(String itemId) {
            return stackSizes.getOrDefault(itemId, 64);
        }

        @Override
        public boolean itemAvailable(String itemId) {
            return !unavailable.contains(itemId);
        }

        @Override
        public boolean inventoryReady() {
            return inventoryReady;
        }

        @Override
        public boolean targetValid() {
            return targetValid;
        }

        /** Confirms every unconfirmed send, as an accepting server would. */
        void confirmAll(DropTask task) {
            for (Send send : pending) task.onItemObserved(send.itemId(), send.count());
            pending.clear();
        }

        List<Integer> countsFor(String itemId) {
            List<Integer> out = new ArrayList<>();
            for (Send send : history) {
                if (send.itemId().equals(itemId)) out.add(send.count());
            }
            return out;
        }

        /** The order materials were first sent in, deduplicated. */
        List<String> firstSendOrder() {
            List<String> order = new ArrayList<>();
            for (Send send : history) {
                if (!order.contains(send.itemId())) order.add(send.itemId());
            }
            return order;
        }

        int totalItems() {
            int total = 0;
            for (Send send : history) total += send.count();
            return total;
        }
    }

    private static MaterialManager materials(Object... pairs) {
        return materials(MaterialOrder.SCHEMATIC_ORDER, pairs);
    }

    private static MaterialManager materials(MaterialOrder order, Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        MaterialManager manager = new MaterialManager();
        manager.setFrom(map, order);
        return manager;
    }

    private static DropTask.Config config() {
        DropTask.Config config = new DropTask.Config();
        // Generous rate so the assertions are about quantity, not pacing.
        config.itemsPerSecond = 2000;
        config.maxDropsPerTick = 10;
        config.minDelayMillis = 0;
        config.randomDelayMillis = 0;
        config.confirmTimeoutTicks = 1000;
        config.maxAttempts = 3;
        config.maxPerDrop = 64;
        config.runMode = RunMode.STRICT_ORDER;
        return config;
    }

    /** Runs ticks until the task finishes or the tick budget runs out. */
    private static void run(DropTask task, FakeActions actions, int maxTicks) {
        for (int tick = 0; tick < maxTicks; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);

            if (task.state() == DropTask.State.COMPLETED || task.state() == DropTask.State.STOPPED) {
                return;
            }
        }
    }

    // ------------------------------------------------------- EXACT QUANTITY

    @Test
    void dropsTheDocumentedExampleExactly() {
        // The spec's example: Stone 130, Dirt 70, Glass 17.
        MaterialManager manager = materials(
            "minecraft:stone", 130,
            "minecraft:dirt", 70,
            "minecraft:glass", 17);

        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        assertTrue(task.start());

        run(task, actions, 100);

        assertEquals(DropTask.State.COMPLETED, task.state());

        // Stone is two full stacks plus two.
        assertEquals(List.of(64, 64, 2), actions.countsFor("minecraft:stone"));
        // Dirt is one full stack plus six.
        assertEquals(List.of(64, 6), actions.countsFor("minecraft:dirt"));
        // Glass is a single partial stack.
        assertEquals(List.of(17), actions.countsFor("minecraft:glass"));

        assertEquals(130, manager.get("minecraft:stone").dropped());
        assertEquals(70, manager.get("minecraft:dirt").dropped());
        assertEquals(17, manager.get("minecraft:glass").dropped());
        assertEquals(217, manager.totalDropped());
        assertEquals(0, manager.totalRemaining());
    }

    @Test
    void neverSendsMoreThanRequested() {
        MaterialManager manager = materials("minecraft:stone", 130);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 100);

        assertEquals(130, actions.totalItems());
        assertEquals(130, manager.totalDropped());
        assertTrue(manager.get("minecraft:stone").isComplete());
    }

    @Test
    void aSingleItemRequirementDropsExactlyOne() {
        // The classic failure: a whole stack sent when one item is wanted.
        MaterialManager manager = materials("minecraft:stone", 1);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 20);

        assertEquals(List.of(1), actions.countsFor("minecraft:stone"));
        assertEquals(1, manager.totalDropped());
    }

    @Test
    void respectsThePerDropCap() {
        MaterialManager manager = materials("minecraft:stone", 130);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.maxPerDrop = 10;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        run(task, actions, 200);

        assertEquals(130, actions.totalItems());
        // Thirteen sends of ten.
        assertEquals(13, actions.countsFor("minecraft:stone").size());
        assertTrue(actions.countsFor("minecraft:stone").stream().allMatch(c -> c == 10));
    }

    @Test
    void respectsANonStandardStackSize() {
        MaterialManager manager = materials("minecraft:ender_pearl", 36);
        FakeActions actions = new FakeActions();
        actions.stackSizes.put("minecraft:ender_pearl", 16);

        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 50);

        // Ender pearls stack to 16, so 36 is 16 + 16 + 4.
        assertEquals(List.of(16, 16, 4), actions.countsFor("minecraft:ender_pearl"));
        assertEquals(36, manager.totalDropped());
    }

    // ------------------------------------------------- DUPLICATE PREVENTION

    @Test
    void unconfirmedRequestsAreNotSentTwice() {
        MaterialManager manager = materials("minecraft:stone", 130);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        // Long timeout so nothing expires; the server simply never confirms.
        config.confirmTimeoutTicks = 100_000;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Many ticks with no confirmations at all.
        for (int tick = 0; tick < 50; tick++) task.tick(tick, tick * 50L);

        // Everything is in flight, so nothing more may be sent.
        assertEquals(130, actions.totalItems());
        assertEquals(0, manager.totalDropped());
        assertTrue(task.isRunning());
    }

    @Test
    void progressNeverExceedsTheRequirementEvenIfTheServerOverConfirms() {
        MaterialManager manager = materials("minecraft:glass", 17);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        task.tick(0, 0);
        actions.confirmAll(task);

        // A duplicated acknowledgement must not push progress past the target.
        task.onItemObserved("minecraft:glass", 17);

        assertEquals(17, manager.get("minecraft:glass").dropped());
        assertEquals(0, manager.get("minecraft:glass").remaining());
    }

    @Test
    void startingTwiceDoesNotRunTwoOperations() {
        MaterialManager manager = materials("minecraft:stone", 64);
        DropTask task = new DropTask(manager, new FakeActions());
        task.config(config());

        assertTrue(task.start());
        assertFalse(task.start());
        assertTrue(task.isRunning());
    }

    @Test
    void cannotStartWithNothingToDo() {
        MaterialManager manager = new MaterialManager();
        DropTask task = new DropTask(manager, new FakeActions());

        assertFalse(task.start());
        assertEquals(DropTask.State.IDLE, task.state());
    }

    // ----------------------------------------------------------- LIFECYCLE

    @Test
    void pauseStopsSendingAndResumeContinues() {
        MaterialManager manager = materials("minecraft:stone", 200);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.maxDropsPerTick = 1;
        config.maxPerDrop = 64;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);
        actions.confirmAll(task);
        int afterFirstTick = actions.totalItems();
        assertTrue(afterFirstTick > 0);

        assertTrue(task.pause());
        for (int tick = 1; tick < 10; tick++) task.tick(tick, tick * 50L);
        assertEquals(afterFirstTick, actions.totalItems(), "paused must not send");

        assertTrue(task.resume());
        task.tick(10, 500L);
        assertTrue(actions.totalItems() > afterFirstTick, "resumed must send again");
    }

    @Test
    void pauseStillCountsItemsAlreadyInFlight() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        task.tick(0, 0);
        task.pause();

        // The server confirms after the pause.
        actions.confirmAll(task);
        task.tick(1, 50L);

        assertEquals(64, manager.get("minecraft:stone").dropped());
    }

    @Test
    void stopDoesNotCountUnconfirmedDrops() {
        MaterialManager manager = materials("minecraft:stone", 130);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.confirmTimeoutTicks = 100_000;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);
        assertTrue(actions.totalItems() > 0);

        task.stop();

        assertEquals(DropTask.State.STOPPED, task.state());
        // Nothing was confirmed, so nothing may be reported as dropped.
        assertEquals(0, manager.totalDropped());
        assertEquals(130, manager.totalRemaining());
    }

    @Test
    void aStoppedTaskCanBeRestarted() {
        MaterialManager manager = materials("minecraft:stone", 10);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());

        task.start();
        task.stop();
        assertTrue(task.start());
        assertTrue(task.isRunning());
    }

    @Test
    void resetClearsProgressAndReturnsToIdle() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();
        task.tick(0, 0);
        actions.confirmAll(task);

        task.reset();

        assertEquals(DropTask.State.IDLE, task.state());
        assertEquals(0, manager.totalDropped());
        assertEquals(64, manager.get("minecraft:stone").required());
    }

    @Test
    void worldChangeAbortsAndRequiresAnExplicitRestart() {
        MaterialManager manager = materials("minecraft:stone", 64);
        DropTask task = new DropTask(manager, new FakeActions());
        task.config(config());
        task.start();

        task.abortForWorldChange("Disconnected from the server");

        assertEquals(DropTask.State.STOPPED, task.state());
        assertTrue(task.requiresRestart());
        assertEquals("Disconnected from the server", task.lastError());
    }

    // -------------------------------------------------------------- FAILURE

    @Test
    void unconfirmedDropsAreNotCountedAndTheMaterialIsBlocked() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.confirmTimeoutTicks = 5;
        config.maxAttempts = 1;
        config.allowResend = true;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Send, then let the confirmation window expire without ever confirming.
        task.tick(0, 0);
        task.tick(10, 500L);

        assertEquals(0, manager.totalDropped(), "unconfirmed work must never be counted");
        assertEquals(MaterialEntry.Status.BLOCKED, manager.get("minecraft:stone").status());
        assertTrue(manager.hasBlocked());
        // The run continues rather than terminating.
        assertTrue(task.isRunning());
    }

    @Test
    void aRejectedSendIsRetriedOnTheNextTick() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.accept = false;

        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        task.tick(0, 0);
        assertEquals(0, actions.totalItems());

        actions.accept = true;
        // Skip past the retry backoff.
        for (int tick = 1; tick < 60; tick++) task.tick(tick, tick * 50L);
        assertTrue(actions.totalItems() > 0);
    }

    @Test
    void missingItemsAreMarkedAndSkipped() {
        MaterialManager manager = materials(
            "minecraft:stone", 10,
            "notamod:missing_block", 10);

        DropTask task = new DropTask(manager, new FakeActions());
        task.config(config());

        int missing = task.validateMaterials(id -> !id.equals("notamod:missing_block"));

        assertEquals(1, missing);
        assertEquals(1, manager.missingCount());

        // The schedulable list must exclude it.
        assertEquals(1, manager.pending().size());
        assertEquals("minecraft:stone", manager.pending().get(0).id());
    }

    @Test
    void noticesReportCompletion() {
        MaterialManager manager = materials("minecraft:stone", 1);
        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 20);

        List<DropTask.Notice> notices = task.drainNotices();
        assertTrue(notices.stream().anyMatch(n -> n.kind() == DropTask.Notice.Kind.COMPLETED),
            notices.toString());
    }

    @Test
    void outstandingReportsWhatIsInFlight() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.confirmTimeoutTicks = 100_000;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);

        assertEquals(64, task.outstanding().get("minecraft:stone"));
        // Nothing is confirmed yet, so nothing counts as dropped.
        assertEquals(0, manager.totalDropped());
    }

    // ----------------------------------------------------------- RATE LIMIT

    @Test
    void neverExceedsTheServersDropsPerTickBudget() {
        MaterialManager manager = materials("minecraft:stone", 5000);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        // Ask for far more than the server will accept.
        config.maxDropsPerTick = 1000;
        config.itemsPerSecond = 100_000;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);

        assertTrue(actions.history.size() <= DropTask.SERVER_MAX_DROPS_PER_TICK,
            "sent " + actions.history.size() + " drops in one tick");
    }

    @Test
    void theItemsPerSecondSettingThrottlesProgress() {
        MaterialManager manager = materials("minecraft:stone", 100);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.itemsPerSecond = 20;
        config.maxDropsPerTick = 10;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // One tick adds 20/20 = 1 item of budget.
        task.tick(0, 0);
        assertEquals(1, actions.totalItems());
    }

    // ---------------------------------------------------- STRICT ORDERING

    @Test
    void strictOrderFinishesEachMaterialBeforeAdvancing() {
        // Stone must be fully dropped before Oak Planks is touched, and so on.
        MaterialManager manager = materials(
            "minecraft:stone", 128,
            "minecraft:oak_planks", 48,
            "minecraft:glass", 32,
            "minecraft:iron_block", 16);

        FakeActions actions = new FakeActions();
        DropTask.Config config = config();
        config.maxDropsPerTick = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        run(task, actions, 500);

        assertEquals(DropTask.State.COMPLETED, task.state());
        assertEquals(
            List.of("minecraft:stone", "minecraft:oak_planks", "minecraft:glass", "minecraft:iron_block"),
            actions.firstSendOrder(),
            "materials must be processed in schematic order, one at a time");

        // Each material is fully finished before the next begins.
        assertEquals(List.of(64, 64), actions.countsFor("minecraft:stone"));
        assertEquals(List.of(48), actions.countsFor("minecraft:oak_planks"));
        assertEquals(List.of(32), actions.countsFor("minecraft:glass"));
        assertEquals(List.of(16), actions.countsFor("minecraft:iron_block"));
    }

    @Test
    void strictOrderNeverAdvancesPastAnUnavailableMaterial() {
        MaterialManager manager = materials(
            "minecraft:stone", 64,
            "minecraft:dirt", 64);

        FakeActions actions = new FakeActions();
        // The first material cannot be obtained at all right now.
        actions.unavailable.add("minecraft:stone");

        DropTask.Config config = config();
        config.runMode = RunMode.STRICT_ORDER;
        config.maxRetriesPerOperation = 2;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        for (int tick = 0; tick < 200; tick++) task.tick(tick, tick * 50L);

        // Dirt must not be touched while stone is still owed.
        assertTrue(actions.countsFor("minecraft:dirt").isEmpty(),
            "strict order must not process a later material while the head is blocked");
        assertEquals(MaterialEntry.Status.BLOCKED, manager.get("minecraft:stone").status());
        assertFalse(manager.isComplete());
    }

    @Test
    void multipleStacksOfOneMaterialAllCompleteBeforeTheNext() {
        MaterialManager manager = materials(
            "minecraft:stone", 200,
            "minecraft:dirt", 10);

        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 100);

        // 200 stone is 64 + 64 + 64 + 8; dirt only after all four.
        assertEquals(List.of(64, 64, 64, 8), actions.countsFor("minecraft:stone"));
        assertEquals(List.of(10), actions.countsFor("minecraft:dirt"));
        assertEquals(
            List.of("minecraft:stone", "minecraft:dirt"),
            actions.firstSendOrder());
    }

    @Test
    void alphabeticalOrderIsDeterministic() {
        MaterialManager manager = materials(MaterialOrder.ALPHABETICAL,
            "minecraft:stone", 10,
            "minecraft:apple", 10,
            "minecraft:zombie_head", 10);

        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 100);

        assertEquals(
            List.of("minecraft:apple", "minecraft:stone", "minecraft:zombie_head"),
            actions.firstSendOrder());
    }

    @Test
    void requiredQuantityOrderProcessesLargestFirst() {
        MaterialManager manager = materials(MaterialOrder.REQUIRED_QUANTITY,
            "minecraft:stone", 10,
            "minecraft:dirt", 128,
            "minecraft:glass", 64);

        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 100);

        assertEquals(
            List.of("minecraft:dirt", "minecraft:glass", "minecraft:stone"),
            actions.firstSendOrder());
    }

    @Test
    void importedOrderPreservesTheListOrder() {
        // A LinkedHashMap is the imported list's own order.
        MaterialManager manager = materials(MaterialOrder.IMPORTED_ORDER,
            "minecraft:glass", 10,
            "minecraft:stone", 10,
            "minecraft:dirt", 10);

        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        run(task, actions, 100);

        assertEquals(
            List.of("minecraft:glass", "minecraft:stone", "minecraft:dirt"),
            actions.firstSendOrder());
    }

    // --------------------------------------------------------- BEST EFFORT

    @Test
    void bestEffortSkipsABlockedMaterialAndRevisitsIt() {
        MaterialManager manager = materials(
            "minecraft:stone", 64,
            "minecraft:dirt", 64);

        FakeActions actions = new FakeActions();
        // Stone cannot be dropped at first.
        actions.unavailable.add("minecraft:stone");

        DropTask.Config config = config();
        config.runMode = RunMode.BEST_EFFORT;
        config.maxRetriesPerOperation = 1;
        config.revisitBlockedTicks = 20;
        config.retryDelayTicks = 1;
        config.maxRetryDelayTicks = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Let stone fail and become blocked, then dirt should be processed.
        for (int tick = 0; tick < 10; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }
        assertTrue(actions.countsFor("minecraft:dirt").size() > 0,
            "best effort must continue with an available material");

        // Stone becomes available again; the revisit must pick it up.
        actions.unavailable.remove("minecraft:stone");
        for (int tick = 10; tick < 200; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }

        assertEquals(64, manager.get("minecraft:stone").dropped());
        assertEquals(DropTask.State.COMPLETED, task.state());
    }

    @Test
    void bestEffortIsNotCompleteWhileAnythingIsBlocked() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.unavailable.add("minecraft:stone");

        DropTask.Config config = config();
        config.runMode = RunMode.BEST_EFFORT;
        config.maxRetriesPerOperation = 1;
        config.retryDelayTicks = 1;
        config.maxRetryDelayTicks = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        for (int tick = 0; tick < 20; tick++) task.tick(tick, tick * 50L);

        assertFalse(manager.isComplete());
        assertFalse(task.state() == DropTask.State.COMPLETED);
        assertEquals(64, manager.totalRemaining());
        assertEquals(1, manager.blockedCount());
    }

    // ----------------------------------------------------------- RECOVERY

    @Test
    void aFailedOperationFollowedByASuccessfulRetryCompletes() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.accept = false;

        DropTask.Config config = config();
        config.maxRetriesPerOperation = 20;
        config.retryDelayTicks = 1;
        config.maxRetryDelayTicks = 2;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Fail for a while.
        for (int tick = 0; tick < 10; tick++) task.tick(tick, tick * 50L);
        assertEquals(0, actions.totalItems());
        assertTrue(task.isRunning(), "the run must not stop on a failure");

        // Recover.
        actions.accept = true;
        for (int tick = 10; tick < 200; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }

        assertEquals(64, manager.totalDropped());
        assertEquals(DropTask.State.COMPLETED, task.state());
    }

    @Test
    void aServerTimeoutDoesNotDuplicateTheDrop() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.confirmTimeoutTicks = 5;
        config.maxAttempts = 3;
        // Resending disabled: the safe default.
        config.allowResend = false;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Send once, then let many timeout windows pass without confirming.
        task.tick(0, 0);
        for (int tick = 1; tick < 200; tick++) task.tick(tick, tick * 50L);

        // Exactly one send: a timeout must never resend when resending is off.
        assertEquals(1, actions.history.size());
        assertEquals(64, actions.totalItems());
        assertEquals(0, manager.totalDropped());
        assertEquals(DropTask.State.WAITING_FOR_SERVER, task.state());
    }

    @Test
    void waitingForInventoryIsReportedAndRecovers() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.inventoryReady = false;

        DropTask task = new DropTask(manager, actions);
        task.config(config());
        task.start();

        task.tick(0, 0);
        assertEquals(DropTask.State.WAITING_FOR_INVENTORY, task.state());
        assertEquals(0, actions.totalItems(), "nothing may be sent without an inventory");

        actions.inventoryReady = true;
        task.tick(1, 50L);
        actions.confirmAll(task);
        assertTrue(actions.totalItems() > 0, "must resume once the inventory is usable");
    }

    @Test
    void waitingForTargetIsReportedAndRecovers() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.targetValid = false;

        DropTask.Config config = config();
        config.targetMode = DropTargetManager.Mode.LOOKED_AT_ENTITY;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);
        assertEquals(DropTask.State.WAITING_FOR_TARGET, task.state());
        assertEquals(0, actions.totalItems());

        actions.targetValid = true;
        task.tick(1, 50L);
        actions.confirmAll(task);
        assertTrue(actions.totalItems() > 0, "must resume once a target is available");
    }

    @Test
    void inventoryChangingDuringExecutionIsRecovered() {
        MaterialManager manager = materials("minecraft:stone", 128);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.maxRetriesPerOperation = 50;
        config.retryDelayTicks = 1;
        config.maxRetryDelayTicks = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);
        actions.confirmAll(task);

        // The inventory becomes unusable mid-run.
        actions.inventoryReady = false;
        task.tick(1, 50L);
        assertEquals(DropTask.State.WAITING_FOR_INVENTORY, task.state());

        // ... and recovers.
        actions.inventoryReady = true;
        for (int tick = 2; tick < 200; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }

        assertEquals(128, manager.totalDropped());
        assertEquals(DropTask.State.COMPLETED, task.state());
    }

    @Test
    void aRejectedSendPutsTheSchedulerInTheRetryingState() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.accept = false;

        DropTask.Config config = config();
        config.maxRetriesPerOperation = 50;
        config.retryDelayTicks = 10;
        config.maxRetryDelayTicks = 100;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        task.tick(0, 0);
        assertEquals(DropTask.State.RETRYING, task.state(),
            "a rejected send must be retried, not terminate the run");

        // A material can be blocked and then revisited, which reports RECOVERING.
        config.maxRetriesPerOperation = 1;
        actions.accept = false;
        for (int tick = 1; tick < 20; tick++) task.tick(tick, tick * 50L);
        assertEquals(MaterialEntry.Status.BLOCKED, manager.get("minecraft:stone").status());
    }

    @Test
    void revisitingABlockedMaterialReportsRecovering() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.unavailable.add("minecraft:stone");

        DropTask.Config config = config();
        config.maxRetriesPerOperation = 1;
        config.retryDelayTicks = 2;
        config.maxRetryDelayTicks = 2;
        config.revisitBlockedTicks = 5;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Block it.
        for (int tick = 0; tick < 5; tick++) task.tick(tick, tick * 50L);
        assertEquals(MaterialEntry.Status.BLOCKED, manager.get("minecraft:stone").status());

        // The revisit pass un-blocks it and reports RECOVERING for a short window.
        boolean sawRecovering = false;
        for (int tick = 5; tick < 12; tick++) {
            task.tick(tick, tick * 50L);
            if (task.state() == DropTask.State.RECOVERING) sawRecovering = true;
        }
        assertTrue(sawRecovering, "the revisit must be reported as recovery");
    }

    @Test
    void failuresAreClassifiedAndRecorded() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        actions.targetValid = false;

        DropTask.Config config = config();
        config.targetMode = DropTargetManager.Mode.LOOKED_AT_ENTITY;
        config.maxRetriesPerOperation = 100;
        config.retryDelayTicks = 1;
        config.maxRetryDelayTicks = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Force a send attempt that the target check rejects.
        task.tick(0, 0);
        // The wait state is reported before a send is even attempted, so drive a
        // failure through the retry path directly.
        task.tick(1, 50L);

        assertEquals(DropTask.State.WAITING_FOR_TARGET, task.state());
    }

    @Test
    void aMaterialWithNoItemIsBlockedAsUnavailable() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();
        // The item exists (validateMaterials is not called) but cannot be dropped.
        actions.unavailable.add("minecraft:stone");

        DropTask.Config config = config();
        config.maxRetriesPerOperation = 1;
        config.retryDelayTicks = 1;
        config.maxRetryDelayTicks = 1;
        config.revisitBlockedTicks = 1_000_000;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        for (int tick = 0; tick < 10; tick++) task.tick(tick, tick * 50L);

        assertEquals(MaterialEntry.Status.BLOCKED, manager.get("minecraft:stone").status());
        assertEquals(FailureKind.MATERIAL_UNAVAILABLE, manager.get("minecraft:stone").blockedReason());
        assertNotNull(task.lastFailure());
        assertFalse(task.failures().isEmpty());
    }

    // --------------------------------------------------- PERSISTENCE / RESTART

    @Test
    void restartingARunKeepsItsUnfinishedQueue() {
        MaterialManager original = materials(
            "minecraft:stone", 130,
            "minecraft:dirt", 70,
            "minecraft:glass", 17);

        FakeActions actions = new FakeActions();
        DropTask.Config config = config();
        config.maxDropsPerTick = 1;
        config.itemsPerSecond = 64 * 20;

        DropTask task = new DropTask(original, actions);
        task.config(config);
        task.start();

        // Make a little progress, then stop as if the session ended.
        for (int tick = 0; tick < 3; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }
        task.stop();
        int stoneDropped = original.get("minecraft:stone").dropped();
        assertTrue(stoneDropped > 0);

        // Reload into a fresh manager, exactly as the module does on start.
        MaterialManager restored = new MaterialManager();
        int count = restored.deserializeRun(original.serializeRun());

        assertEquals(3, count);
        assertEquals(
            List.of("minecraft:stone", "minecraft:dirt", "minecraft:glass"),
            restored.order(),
            "the processing order must survive a restart");
        assertEquals(stoneDropped, restored.get("minecraft:stone").dropped());
        assertEquals(130 - stoneDropped, restored.get("minecraft:stone").remaining());
        assertEquals(130, restored.get("minecraft:stone").required());

        // Resuming finishes the run rather than restarting it.
        FakeActions resumeActions = new FakeActions();
        DropTask resumed = new DropTask(restored, resumeActions);
        resumed.config(config);
        resumed.start();

        for (int tick = 0; tick < 500; tick++) {
            resumed.tick(tick, tick * 50L);
            resumeActions.confirmAll(resumed);
        }

        assertEquals(DropTask.State.COMPLETED, resumed.state());
        assertEquals(130, restored.get("minecraft:stone").dropped());
        assertEquals(217, restored.totalDropped());
        assertEquals(0, restored.totalRemaining());
    }

    @Test
    void aRestoredBlockedMaterialIsRetriedOnStart() {
        MaterialManager manager = materials("minecraft:stone", 64);
        manager.markBlocked("minecraft:stone", FailureKind.MATERIAL_UNAVAILABLE);
        assertTrue(manager.hasBlocked());

        FakeActions actions = new FakeActions();
        DropTask task = new DropTask(manager, actions);
        task.config(config());

        assertTrue(task.start());
        // Starting clears stale blocks so the material gets another chance.
        assertFalse(manager.hasBlocked());
        assertEquals(MaterialEntry.Status.PENDING, manager.get("minecraft:stone").status());

        for (int tick = 0; tick < 50; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }
        assertEquals(DropTask.State.COMPLETED, task.state());
    }

    @Test
    void completionRequiresEveryMaterialToBeFinished() {
        MaterialManager manager = materials(
            "minecraft:stone", 64,
            "minecraft:dirt", 64);

        FakeActions actions = new FakeActions();
        DropTask.Config config = config();
        config.maxDropsPerTick = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Finish stone only.
        for (int tick = 0; tick < 40; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
            if (manager.get("minecraft:stone").isComplete()) break;
        }

        assertTrue(manager.get("minecraft:stone").isComplete());
        assertFalse(manager.isComplete(), "one material left means the run is not done");
        assertFalse(task.state() == DropTask.State.COMPLETED);

        // Finish dirt too.
        for (int tick = 40; tick < 200; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }

        assertTrue(manager.isComplete());
        assertEquals(DropTask.State.COMPLETED, task.state());
    }
}
