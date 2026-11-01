package com.schematicdropper.drop;

import com.schematicdropper.material.MaterialEntry;
import com.schematicdropper.material.MaterialManager;
import com.schematicdropper.material.StackMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Drives a drop operation: decides which material to work on, when, and how much.
 *
 * <p>Keeps no Minecraft types. Everything that touches the game goes through
 * {@link Actions}, which makes the whole scheduler - including its ordering,
 * rate limits, failure recovery, cancellation and resume behaviour - testable
 * without a client.
 *
 * <h2>Deterministic order</h2>
 * Materials are processed in a fixed queue order (see
 * {@link com.schematicdropper.material.MaterialOrder}). In
 * {@link RunMode#STRICT_ORDER} - the default - the material at the head of the
 * queue is finished, including all of its stacks, before the next one is touched.
 * A material that cannot be processed keeps its position and is resumed from its
 * exact remaining quantity once it can be.
 *
 * <h2>Failure recovery</h2>
 * A failed operation never terminates the run. Each failure is classified (see
 * {@link FailureKind}), recorded, and either retried after a bounded backoff,
 * waited out (inventory, target, server), or used to mark the material BLOCKED so
 * it can be revisited. The scheduler distinguishes the waiting states so it never
 * re-sends an identical request while it is waiting.
 */
public final class DropTask {
    /** Lifecycle of the operation. */
    public enum State {
        IDLE("Idle"),
        RUNNING("Running"),
        PAUSED("Paused"),
        /** A transient failure is being retried after a backoff. */
        RETRYING("Retrying"),
        /** Blocked materials are being revisited after a wait. */
        RECOVERING("Recovering"),
        /** A material cannot currently be processed and keeps its queue position. */
        BLOCKED("Blocked"),
        /** The creative inventory is not usable right now. */
        WAITING_FOR_INVENTORY("Waiting for inventory"),
        /** The looked-at target is missing; drops are paused until one is found. */
        WAITING_FOR_TARGET("Waiting for target"),
        /** A drop is unconfirmed and is not being resent; waiting for the server. */
        WAITING_FOR_SERVER("Waiting for server"),
        COMPLETED("Complete"),
        STOPPED("Stopped"),
        ERROR("Error");

        private final String title;

        State(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** What the scheduler is allowed to do to the world. */
    public interface Actions {
        /**
         * Asks the server to drop one stack at the player's feet.
         *
         * @return true when the request was sent
         */
        boolean dropAtFeet(String itemId, int count);

        /**
         * Asks for the item to be thrown toward the current target.
         *
         * @return true when the request was sent
         */
        boolean throwAtTarget(String itemId, int count);

        /** The stack size of an item, used to size drops. */
        int maxStackSize(String itemId);

        /** True when the item exists in this client's registry. */
        boolean itemAvailable(String itemId);

        /** True when the creative inventory can be used right now. */
        boolean inventoryReady();

        /** True when the configured drop target is currently valid. */
        boolean targetValid();
    }

    /** Tunables, read fresh each tick so setting changes take effect immediately. */
    public static final class Config {
        public int itemsPerSecond = 128;
        public int maxDropsPerTick = 4;
        public int minDelayMillis = 0;
        public int randomDelayMillis = 0;
        public int confirmTimeoutTicks = 20;
        public int maxAttempts = 3;
        public int maxPerDrop = 64;
        public DropTargetManager.Mode targetMode = DropTargetManager.Mode.FEET;
        public RunMode runMode = RunMode.STRICT_ORDER;

        /** Whether a timed-out request may be resent. Off, to avoid duplicate drops. */
        public boolean allowResend = false;
        /** Base delay before retrying a failed operation, in ticks. */
        public int retryDelayTicks = 10;
        /** Ceiling for the exponential backoff, in ticks. */
        public int maxRetryDelayTicks = 100;
        /** Failed attempts for one material before it is marked BLOCKED. */
        public int maxRetriesPerOperation = 5;
        /** How often blocked materials are revisited, in ticks. */
        public int revisitBlockedTicks = 100;

        public Config copy() {
            Config copy = new Config();
            copy.itemsPerSecond = itemsPerSecond;
            copy.maxDropsPerTick = maxDropsPerTick;
            copy.minDelayMillis = minDelayMillis;
            copy.randomDelayMillis = randomDelayMillis;
            copy.confirmTimeoutTicks = confirmTimeoutTicks;
            copy.maxAttempts = maxAttempts;
            copy.maxPerDrop = maxPerDrop;
            copy.targetMode = targetMode;
            copy.runMode = runMode;
            copy.allowResend = allowResend;
            copy.retryDelayTicks = retryDelayTicks;
            copy.maxRetryDelayTicks = maxRetryDelayTicks;
            copy.maxRetriesPerOperation = maxRetriesPerOperation;
            copy.revisitBlockedTicks = revisitBlockedTicks;
            return copy;
        }
    }

    /**
     * The server only performs ten creative drops per tick. Its budget adds 20
     * per tick and each drop spends 20, refusing anything once the budget is
     * full, so a faster schedule would silently lose drops rather than speed
     * things up. The scheduler clamps to this rather than pretending otherwise.
     */
    public static final int SERVER_MAX_DROPS_PER_TICK = 10;

    /** A change worth telling the user about. */
    public record Notice(Kind kind, String itemId, String message) {
        public enum Kind {
            COMPLETED,
            STALLED,
            MISSING_ITEM,
            ERROR,
            CANCELLED,
            /** A material could not be processed and keeps its queue position. */
            BLOCKED,
            /** A blocked material is being retried. */
            RECOVERED,
            /** The scheduler is waiting for a condition before it can continue. */
            WAITING
        }
    }

    /** A recorded operation failure, so the run can explain itself. */
    public record Failure(String itemId, FailureKind kind, String reason, long tick) {
    }

    /** Caps the failure log so a long run cannot grow without bound. */
    private static final int MAX_FAILURE_LOG = 256;

    private final MaterialManager materials;
    private final DropLedger ledger = new DropLedger();
    private final Random random = new Random();
    private final Actions actions;

    private State state = State.IDLE;
    private Config config = new Config();

    /** Fractional item budget, so a low items-per-second rate still makes progress. */
    private double itemBudget;
    /** Wall-clock timestamp of the last send, for the minimum delay. */
    private long lastSendMillis;
    /** Millis to wait before the next send, refreshed on each send. */
    private long nextSendDelay;

    /** Failed attempts per material, reset on a successful send. */
    private final Map<String, Integer> failureCounts = new HashMap<>();
    /** Tick before which no send is attempted, used for the retry backoff. */
    private long retryUntilTick;
    /** Tick at which blocked materials are revisited. */
    private long nextRevisitTick;
    /** Tick until which the scheduler reports itself as RECOVERING. */
    private long recoveringUntilTick;

    private final List<Failure> failureLog = new ArrayList<>();
    private Failure lastFailure;

    private String lastError;
    private final List<Notice> notices = new ArrayList<>();

    /** Set when the world or connection changed, so the run stops rather than desyncing. */
    private boolean requiresRestart;

    public DropTask(MaterialManager materials, Actions actions) {
        this.materials = materials;
        this.actions = actions;
    }

    public State state() {
        return state;
    }

    /** True while a run is in progress, including its recovery and waiting states. */
    public boolean isRunning() {
        return state == State.RUNNING || state == State.RETRYING || state == State.RECOVERING
            || state == State.BLOCKED || state == State.WAITING_FOR_INVENTORY
            || state == State.WAITING_FOR_TARGET || state == State.WAITING_FOR_SERVER;
    }

    /** True while a run is in progress or paused; false in every terminal state. */
    public boolean isActive() {
        return isRunning() || state == State.PAUSED;
    }

    public String lastError() {
        return lastError;
    }

    public boolean requiresRestart() {
        return requiresRestart;
    }

    public DropLedger ledger() {
        return ledger;
    }

    public MaterialManager materials() {
        return materials;
    }

    /** The most recent operation failure, or {@code null}. */
    public Failure lastFailure() {
        return lastFailure;
    }

    /** Every recorded failure, oldest first. */
    public List<Failure> failures() {
        return List.copyOf(failureLog);
    }

    /** How many materials are currently blocked. */
    public int blockedCount() {
        return materials.blockedCount();
    }

    /** The material the scheduler will work on next, or {@code null}. */
    public MaterialEntry currentMaterial() {
        return nextTarget();
    }

    public void config(Config config) {
        this.config = config == null ? new Config() : config;
    }

    public Config config() {
        return config;
    }

    /** Drains the notices raised since the last call. */
    public List<Notice> drainNotices() {
        if (notices.isEmpty()) return List.of();

        List<Notice> out = List.copyOf(notices);
        notices.clear();
        return out;
    }

    // ------------------------------------------------------------- LIFECYCLE

    /**
     * Starts a run.
     *
     * <p>Refuses to start a second time while one is already active, which is
     * what stops a double-click from launching two competing operations. Progress
     * already recorded is kept, so starting after a stop resumes the unfinished
     * queue rather than restarting it.
     *
     * @return false when a run is already active or there is nothing to do
     */
    public boolean start() {
        if (isActive()) return false;
        if (materials.isEmpty()) return false;

        state = State.RUNNING;
        requiresRestart = false;
        lastError = null;
        itemBudget = 0;
        nextSendDelay = 0;
        retryUntilTick = 0;
        nextRevisitTick = 0;
        failureCounts.clear();
        ledger.clear();
        // Give any previously blocked material another chance.
        unblockAll();
        return true;
    }

    /** Pauses, leaving outstanding requests to be resolved on resume. */
    public boolean pause() {
        if (!isRunning()) return false;
        state = State.PAUSED;
        return true;
    }

    /** Resumes a paused run. */
    public boolean resume() {
        if (state != State.PAUSED) return false;
        state = State.RUNNING;
        return true;
    }

    /**
     * Stops the operation and discards everything in flight.
     *
     * <p>Outstanding requests are abandoned rather than counted, so a stop can
     * never leave the progress figures overstated. The unfinished queue and each
     * material's confirmed progress are kept, so the run can be resumed.
     */
    public void stop() {
        if (state == State.IDLE || state == State.STOPPED) return;

        state = State.STOPPED;
        for (MaterialEntry entry : materials.all()) {
            if (entry.status() == MaterialEntry.Status.RUNNING) {
                entry.status(entry.isComplete() ? MaterialEntry.Status.DONE : MaterialEntry.Status.PENDING);
            }
        }
        ledger.clear();
        failureCounts.clear();
        notices.add(new Notice(Notice.Kind.CANCELLED, null, "Drop operation stopped"));
    }

    /**
     * Aborts because the world or connection changed.
     *
     * <p>Marks the task as needing an explicit restart: silently continuing after
     * a reconnect would resume dropping in a world the user may not be looking at.
     * The unfinished queue is preserved so the restart resumes where it stopped.
     */
    public void abortForWorldChange(String reason) {
        if (!isActive()) return;

        state = State.STOPPED;
        requiresRestart = true;
        ledger.clear();
        failureCounts.clear();
        lastError = reason;
        notices.add(new Notice(Notice.Kind.ERROR, null, reason));
    }

    /** Clears progress and returns to idle. */
    public void reset() {
        state = State.IDLE;
        ledger.clear();
        failureCounts.clear();
        failureLog.clear();
        lastFailure = null;
        requiresRestart = false;
        lastError = null;
        retryUntilTick = 0;
        nextRevisitTick = 0;
        itemBudget = 0;
        materials.resetProgress();
    }

    // ---------------------------------------------------------------- TICKING

    /**
     * Advances the operation by one client tick.
     *
     * <p>Order matters: confirmations and timeouts are resolved first so a
     * material that finished this tick is not sent again, then the scheduler
     * decides whether it is allowed to send, and finally completion is checked.
     *
     * @param tick      the client tick counter, used for confirmation timeouts
     * @param nowMillis wall clock, used for the minimum delay between sends
     */
    public void tick(long tick, long nowMillis) {
        if (state == State.PAUSED) {
            // Still resolve confirmations while paused: items already in flight
            // must be counted, otherwise progress would be understated.
            resolvePending(tick);
            return;
        }

        if (!isRunning()) return;

        resolvePending(tick);
        if (!isRunning()) return;

        revisitBlockedIfDue(tick);

        refreshState(tick);

        // The waiting states are informational: a send is gated by the concrete
        // condition, so a material blocked on a slow server does not stop other
        // materials from being processed in Best Effort mode.
        if (canSend(tick)) {
            refillBudget();
            sendDue(tick, nowMillis);
        }

        checkCompletion();
        if (isRunning()) refreshState(tick);
    }

    /** Whether the conditions required to send a drop currently hold. */
    private boolean canSend(long tick) {
        if (tick < retryUntilTick) return false;
        if (!actions.inventoryReady()) return false;
        return !requiresTarget() || actions.targetValid();
    }

    /** Handles retries, timeouts and confirmations from the ledger. */
    private void resolvePending(long tick) {
        List<DropLedger.Resolution> resolutions =
            ledger.poll(tick, Math.max(1, config.confirmTimeoutTicks),
                Math.max(1, config.maxAttempts), config.allowResend);

        for (DropLedger.Resolution resolution : resolutions) {
            switch (resolution.outcome()) {
                case CONFIRMED -> {
                    materials.confirm(resolution.itemId(), resolution.count());
                    clearFailures(resolution.itemId());
                }
                case RETRY -> {
                    // A genuine second send. The ledger already counted the attempt
                    // and reset its timer, so the request must not be re-recorded.
                    if (!send(resolution.itemId(), resolution.count())) {
                        handleSendFailure(resolution.itemId(), tick);
                    } else {
                        state = State.RETRYING;
                    }
                }
                case TIMED_OUT -> {
                    // Resending is disabled: the server may simply be slow, and a
                    // resend could duplicate items that were in fact created. Wait
                    // and keep the request outstanding.
                    recordFailure(resolution.itemId(), FailureKind.SERVER_TIMEOUT,
                        "no confirmation after " + resolution.count() + " x " + resolution.itemId(), tick);
                    notices.add(new Notice(Notice.Kind.WAITING, resolution.itemId(),
                        "Waiting for the server to confirm " + resolution.count() + " x "
                            + resolution.itemId() + "; not resending to avoid duplicates"));
                }
                case STALLED -> {
                    // Out of retries. Do not count it, and do not stop the whole
                    // run: block the material so it keeps its place and is revisited.
                    blockMaterial(resolution.itemId(), FailureKind.SERVER_REJECTION, tick);
                    recordFailure(resolution.itemId(), FailureKind.SERVER_REJECTION,
                        "server never confirmed " + resolution.count() + " x " + resolution.itemId(), tick);
                    notices.add(new Notice(Notice.Kind.BLOCKED, resolution.itemId(),
                        "Blocked " + resolution.itemId() + " after repeated unconfirmed drops"));
                }
            }
        }
    }

    /** Adds this tick's share of the items-per-second allowance. */
    private void refillBudget() {
        if (config.itemsPerSecond <= 0) {
            itemBudget = Integer.MAX_VALUE;
            return;
        }
        itemBudget = Math.min(itemBudget + config.itemsPerSecond / 20.0, config.itemsPerSecond);
    }

    /** Sends as many drops as the budget, the per-tick cap and the delay allow. */
    private void sendDue(long tick, long nowMillis) {
        if (tick < retryUntilTick) return;
        if (nowMillis - lastSendMillis < nextSendDelay) return;

        int perTickCap = config.maxDropsPerTick <= 0
            ? SERVER_MAX_DROPS_PER_TICK
            : Math.min(config.maxDropsPerTick, SERVER_MAX_DROPS_PER_TICK);

        int sendsThisTick = 0;
        // Bounds the loop so a saturated list cannot spin forever.
        int scanLimit = Math.max(4, materials.size() * 4 + 4);
        int examined = 0;

        while (sendsThisTick < perTickCap && examined < scanLimit) {
            examined++;

            MaterialEntry entry = nextTarget();
            if (entry == null) break;
            // In Strict Order the head material may be blocked; wait for it rather
            // than advancing. Best Effort never returns a blocked entry here.
            if (entry.isBlocked()) break;

            // Items already requested but not yet confirmed count against the
            // target, so a slow confirmation cannot cause an over-drop.
            int inFlight = ledger.outstandingItems(entry.id());
            int sendable = entry.remaining() - inFlight;
            if (sendable <= 0) break;

            int maxStack = Math.max(1, actions.maxStackSize(entry.id()));
            int size = StackMath.nextDropSize(sendable, maxStack, config.maxPerDrop);
            if (size <= 0) break;

            if (itemBudget < size) {
                // Carry the leftover budget to the next tick rather than splitting
                // a material mid-stack, which keeps the reported breakdown tidy:
                // 130 stone reads as 64 + 64 + 2, not 36 + 94.
                if (sendsThisTick > 0) break;

                // Nothing has been sent this tick, so shrink to what is available
                // instead of stalling. This is what makes a very low
                // items-per-second setting still make progress.
                int affordable = (int) Math.floor(itemBudget);
                if (affordable < 1) break;
                size = Math.min(affordable, sendable);
            }

            if (!send(entry.id(), size)) {
                handleSendFailure(entry.id(), tick);
                break;
            }

            ledger.send(entry.id(), size, tick);
            materials.markRunning(entry.id());
            clearFailures(entry.id());

            itemBudget -= size;
            sendsThisTick++;
            lastSendMillis = nowMillis;
            nextSendDelay = computeDelay();
        }
    }

    private long computeDelay() {
        long delay = Math.max(0, config.minDelayMillis);
        if (config.randomDelayMillis > 0) {
            delay += random.nextInt(config.randomDelayMillis + 1);
        }
        return delay;
    }

    /** Dispatches one request through the configured drop mode. */
    private boolean send(String itemId, int count) {
        return switch (config.targetMode) {
            case FEET -> actions.dropAtFeet(itemId, count);
            case LOOKED_AT_ENTITY -> actions.throwAtTarget(itemId, count);
        };
    }

    /** The material the scheduler works on next, honouring the run mode. */
    private MaterialEntry nextTarget() {
        return config.runMode == RunMode.BEST_EFFORT
            ? materials.firstProcessable()
            : materials.firstPending();
    }

    private boolean requiresTarget() {
        return config.targetMode == DropTargetManager.Mode.LOOKED_AT_ENTITY;
    }

    // -------------------------------------------------------------- RECOVERY

    /**
     * Classifies why a send failed, so the scheduler can respond appropriately.
     *
     * <p>The order matters: an unusable inventory or a missing target is checked
     * before the item itself, because those are the conditions the module can wait
     * for rather than block on.
     */
    private FailureKind classifyFailure(String itemId) {
        if (!actions.inventoryReady()) return FailureKind.INVENTORY_CHANGED;
        if (requiresTarget() && !actions.targetValid()) return FailureKind.TARGET_INVALID;
        if (!actions.itemAvailable(itemId)) return FailureKind.MATERIAL_UNAVAILABLE;
        return FailureKind.SERVER_REJECTION;
    }

    /** Records a failed operation and decides whether to retry or to block it. */
    private void handleSendFailure(String itemId, long tick) {
        FailureKind kind = classifyFailure(itemId);
        recordFailure(itemId, kind, "could not drop " + itemId, tick);

        if (!kind.recoverable()) {
            blockMaterial(itemId, kind, tick);
            notices.add(new Notice(Notice.Kind.BLOCKED, itemId,
                "Blocked " + itemId + ": " + kind));
            return;
        }

        int attempts = failureCounts.merge(itemId, 1, Integer::sum);
        if (attempts >= Math.max(1, config.maxRetriesPerOperation)) {
            failureCounts.remove(itemId);
            blockMaterial(itemId, kind, tick);
            notices.add(new Notice(Notice.Kind.BLOCKED, itemId,
                "Blocked " + itemId + " after " + attempts + " failed attempts (" + kind + ")"));
            return;
        }

        retryUntilTick = tick + backoffTicks(attempts);
        state = State.RETRYING;
    }

    /**
     * Blocks a material and arms its revisit timer.
     *
     * <p>Arming the timer here is what stops the revisit pass from immediately
     * un-blocking a material in the very tick it was blocked, which would spin.
     */
    private void blockMaterial(String itemId, FailureKind kind, long tick) {
        materials.markBlocked(itemId, kind);
        nextRevisitTick = tick + Math.max(1, config.revisitBlockedTicks);
    }

    /** Exponential backoff, bounded by the configured ceiling. */
    private long backoffTicks(int attempt) {
        long base = Math.max(1, config.retryDelayTicks);
        long ceiling = Math.max(base, config.maxRetryDelayTicks);
        long delay = base << Math.min(16, Math.max(0, attempt - 1));
        return Math.min(delay, ceiling);
    }

    private void clearFailures(String itemId) {
        failureCounts.remove(itemId);
    }

    private void recordFailure(String itemId, FailureKind kind, String reason, long tick) {
        lastFailure = new Failure(itemId, kind, reason, tick);
        failureLog.add(lastFailure);
        if (failureLog.size() > MAX_FAILURE_LOG) {
            failureLog.remove(0);
        }
    }

    /** Revisits blocked materials once the revisit delay has elapsed. */
    private void revisitBlockedIfDue(long tick) {
        if (!materials.hasBlocked()) return;
        if (tick < nextRevisitTick) return;

        int count = unblockAll();
        nextRevisitTick = tick + Math.max(1, config.revisitBlockedTicks);
        if (count > 0) {
            // Keep the recovery state visible for a short window so the user can
            // see that blocked materials are being retried, not just forgotten.
            recoveringUntilTick = tick + Math.max(1, config.retryDelayTicks);
            state = State.RECOVERING;
            notices.add(new Notice(Notice.Kind.RECOVERED, null,
                "Retrying " + count + " blocked material(s)"));
        }
    }

    /** Clears every block and its failure count, giving materials another chance. */
    private int unblockAll() {
        int count = 0;
        for (MaterialEntry entry : materials.all()) {
            if (entry.isBlocked()) {
                materials.unblock(entry.id());
                failureCounts.remove(entry.id());
                count++;
            }
        }
        return count;
    }

    /**
     * Sets the state to the reason the scheduler is (not) working, so the HUD and
     * the user can see why nothing is being sent.
     *
     * <p>The head material's own wait is taken into account, so a material that is
     * waiting on a slow server is reported as such even while later materials
     * continue to make progress in Best Effort mode.
     */
    private void refreshState(long tick) {
        if (!isRunning()) return;

        if (tick < retryUntilTick) {
            state = State.RETRYING;
            return;
        }
        if (tick < recoveringUntilTick) {
            state = State.RECOVERING;
            return;
        }
        if (!actions.inventoryReady()) {
            state = State.WAITING_FOR_INVENTORY;
            return;
        }
        if (requiresTarget() && !actions.targetValid()) {
            state = State.WAITING_FOR_TARGET;
            return;
        }

        MaterialEntry head = nextTarget();
        if (head != null && ledger.hasTimedOut(head.id())) {
            state = State.WAITING_FOR_SERVER;
            return;
        }
        if (head == null || head.isBlocked()) {
            state = materials.hasBlocked() ? State.BLOCKED : State.RUNNING;
            return;
        }

        state = State.RUNNING;
    }

    private void checkCompletion() {
        if (!ledger.isEmpty()) return;
        if (!materials.isComplete()) return;

        state = State.COMPLETED;
        notices.add(new Notice(Notice.Kind.COMPLETED, null,
            "Dropped all " + materials.totalRequired() + " items"));
    }

    // ---------------------------------------------------------- CONFIRMATION

    /**
     * Reports an item entity the server spawned.
     *
     * <p>This is the only path that increases the dropped count.
     */
    public void onItemObserved(String itemId, int count) {
        if (itemId == null || count <= 0) return;

        for (DropLedger.Resolution resolution : ledger.observe(itemId, count, 0)) {
            materials.confirm(resolution.itemId(), resolution.count());
            clearFailures(resolution.itemId());
        }
    }

    /**
     * Rolls back a confirmation that turned out to be wrong.
     *
     * <p>Exposed so an unexpected desync can be corrected rather than leaving the
     * progress figures permanently overstated.
     */
    public void revoke(String itemId, int count) {
        materials.revoke(itemId, count);
    }

    /** Marks every material that has no item in this client's registry. */
    public int validateMaterials(java.util.function.Predicate<String> exists) {
        int missing = 0;
        for (MaterialEntry entry : materials.all()) {
            if (!exists.test(entry.id())) {
                materials.markMissing(entry.id());
                missing++;
            }
        }

        if (missing > 0) {
            notices.add(new Notice(Notice.Kind.MISSING_ITEM, null,
                missing + " material" + (missing == 1 ? " has" : "s have")
                    + " no matching item in this client and will be skipped"));
        }
        return missing;
    }

    /** A per-material view of what is still in flight, for the HUD and GUI. */
    public Map<String, Integer> outstanding() {
        Map<String, Integer> out = new HashMap<>();
        for (DropLedger.Request request : ledger.snapshot()) {
            out.merge(request.itemId(), request.count(), Integer::sum);
        }
        return out;
    }
}
