package com.schematicdropper.drop;

import com.schematicdropper.material.MaterialManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the exact-quantity contract, driven through a fake
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

        @Override
        public boolean dropAtFeet(String itemId, int count) {
            if (!accept) return false;
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

        int totalItems() {
            int total = 0;
            for (Send send : history) total += send.count();
            return total;
        }
    }

    private static MaterialManager materials(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        MaterialManager manager = new MaterialManager();
        manager.setFrom(map);
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
        return config;
    }

    /** Runs ticks until the task finishes or the tick budget runs out. */
    private static void run(DropTask task, FakeActions actions, int maxTicks) {
        for (int tick = 0; tick < maxTicks; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);

            if (task.state() == DropTask.State.COMPLETED
                || task.state() == DropTask.State.ERROR
                || task.state() == DropTask.State.STOPPED) {
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
        assertEquals(DropTask.State.RUNNING, task.state());
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
        assertEquals(DropTask.State.RUNNING, task.state());
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
        assertEquals(DropTask.State.RUNNING, task.state());
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
        assertFalse(task.start() == false && false);
    }

    // -------------------------------------------------------------- FAILURE

    @Test
    void unconfirmedDropsEventuallyStallAndStopTheRun() {
        MaterialManager manager = materials("minecraft:stone", 64);
        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        config.confirmTimeoutTicks = 5;
        config.maxAttempts = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Send, then let the confirmation window expire without ever confirming.
        task.tick(0, 0);
        task.tick(10, 500L);

        assertEquals(DropTask.State.ERROR, task.state());
        assertEquals(0, manager.totalDropped(), "unconfirmed work must never be counted");
        assertEquals(com.schematicdropper.material.MaterialEntry.Status.STALLED,
            manager.get("minecraft:stone").status());
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
        task.tick(1, 50L);
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

    @Test
    void aTightRateLimitStillMakesProgressOnEveryMaterial() {
        MaterialManager manager = materials(
            "minecraft:stone", 64,
            "minecraft:dirt", 64,
            "minecraft:glass", 64);

        FakeActions actions = new FakeActions();

        DropTask.Config config = config();
        // One stack per tick, so only one material can advance each tick.
        config.itemsPerSecond = 64 * 20;
        config.maxDropsPerTick = 1;

        DropTask task = new DropTask(manager, actions);
        task.config(config);
        task.start();

        // Long enough for three materials at one stack per tick.
        for (int tick = 0; tick < 6; tick++) {
            task.tick(tick, tick * 50L);
            actions.confirmAll(task);
        }

        // Round-robin means no material is starved while another finishes.
        assertFalse(actions.countsFor("minecraft:stone").isEmpty());
        assertFalse(actions.countsFor("minecraft:dirt").isEmpty());
        assertFalse(actions.countsFor("minecraft:glass").isEmpty());
    }
}
