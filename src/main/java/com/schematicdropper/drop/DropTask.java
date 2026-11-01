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
 * Drives a drop operation: decides what to send, when, and how much.
 *
 * <p>Keeps no Minecraft types. Everything that touches the game goes through
 * {@link Actions}, which makes the whole scheduler - including its rate limits,
 * cancellation and resume behaviour - testable without a client.
 *
 * <p>The scheduler is deliberately conservative: it never sends more than what is
 * still required, never sends a whole stack when fewer items are outstanding, and
 * never counts anything until the server has produced the item entity.
 */
public final class DropTask {
    /** Lifecycle of the operation. */
    public enum State {
        IDLE("Idle"),
        RUNNING("Running"),
        PAUSED("Paused"),
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
            CANCELLED
        }
    }

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

    public boolean isRunning() {
        return state == State.RUNNING;
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
     * <p>Refuses to start a second time while one is already running, which is
     * what stops a double-click from launching two competing operations.
     *
     * @return false when a run is already active or there is nothing to do
     */
    public boolean start() {
        if (state == State.RUNNING) return false;
        if (materials.isEmpty()) return false;

        state = State.RUNNING;
        requiresRestart = false;
        lastError = null;
        itemBudget = 0;
        nextSendDelay = 0;
        ledger.clear();
        return true;
    }

    /** Pauses, leaving outstanding requests to be resolved on resume. */
    public boolean pause() {
        if (state != State.RUNNING) return false;
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
     * never leave the progress figures overstated.
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
        notices.add(new Notice(Notice.Kind.CANCELLED, null, "Drop operation stopped"));
    }

    /**
     * Aborts because the world or connection changed.
     *
     * <p>Marks the task as needing an explicit restart: silently continuing after
     * a reconnect would resume dropping in a world the user may not be looking at.
     */
    public void abortForWorldChange(String reason) {
        if (state != State.RUNNING && state != State.PAUSED) return;

        state = State.STOPPED;
        requiresRestart = true;
        ledger.clear();
        lastError = reason;
        notices.add(new Notice(Notice.Kind.ERROR, null, reason));
    }

    /** Clears progress and returns to idle. */
    public void reset() {
        state = State.IDLE;
        ledger.clear();
        requiresRestart = false;
        lastError = null;
        itemBudget = 0;
        materials.resetProgress();
    }

    // ---------------------------------------------------------------- TICKING

    /**
     * Advances the operation by one client tick.
     *
     * <p>Order matters: confirmations are resolved first so a material that
     * finished this tick is not sent again, then rate limits are applied.
     *
     * @param tick       the client tick counter, used for confirmation timeouts
     * @param nowMillis  wall clock, used for the minimum delay between sends
     */
    public void tick(long tick, long nowMillis) {
        if (state == State.RUNNING) {
            resolvePending(tick);

            if (state == State.RUNNING) {
                refillBudget();
                sendDue(tick, nowMillis);
            }

            if (state == State.RUNNING) checkCompletion();
        } else if (state == State.PAUSED) {
            // Still resolve confirmations while paused: items already in flight
            // must be counted, otherwise progress would be understated.
            resolvePending(tick);
        }
    }

    /** Handles retries, stalls and completions from the ledger. */
    private void resolvePending(long tick) {
        List<DropLedger.Resolution> resolutions =
            ledger.poll(tick, Math.max(1, config.confirmTimeoutTicks), Math.max(1, config.maxAttempts));

        for (DropLedger.Resolution resolution : resolutions) {
            if (resolution.outcome() == DropLedger.Outcome.RETRY) {
                // Re-send the same request. The ledger kept it in the queue and
                // counted the attempt, so a new request must NOT be recorded here
                // or the same items would be counted twice.
                send(resolution.itemId(), resolution.count());
            } else if (resolution.outcome() == DropLedger.Outcome.STALLED) {
                materials.markStalled(resolution.itemId());
                notices.add(new Notice(Notice.Kind.STALLED, resolution.itemId(),
                    "Server never confirmed " + resolution.count() + " x " + resolution.itemId()
                        + "; stopping to avoid dropping duplicates"));
                state = State.ERROR;
                lastError = "Unconfirmed drops for " + resolution.itemId();
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
        if (nowMillis - lastSendMillis < nextSendDelay) return;

        int perTickCap = config.maxDropsPerTick <= 0
            ? SERVER_MAX_DROPS_PER_TICK
            : Math.min(config.maxDropsPerTick, SERVER_MAX_DROPS_PER_TICK);

        // One snapshot per tick rather than one per send: rebuilding the pending
        // list inside the loop would allocate a fresh list for every candidate on
        // every tick, which a few hundred materials makes expensive.
        List<MaterialEntry> candidates = materials.pending();
        if (candidates.isEmpty()) return;

        int sendsThisTick = 0;
        // Bounds the scan so a saturated list cannot spin forever. Four passes per
        // pending material is enough to split it into its stacks and then stop.
        int examined = 0;
        int scanLimit = Math.max(4, candidates.size() * 4);
        int index = Math.min(materials.cursor(), candidates.size() - 1);

        while (sendsThisTick < perTickCap && examined < scanLimit) {
            examined++;

            MaterialEntry entry = candidates.get(index % candidates.size());
            index++;

            // Items already requested but not yet confirmed count against the
            // target, so a slow confirmation cannot cause an over-drop.
            int inFlight = ledger.outstandingItems(entry.id());
            int sendable = entry.remaining() - inFlight;
            if (sendable <= 0) continue;

            int maxStack = Math.max(1, actions.maxStackSize(entry.id()));
            int size = StackMath.nextDropSize(sendable, maxStack, config.maxPerDrop);
            if (size <= 0) continue;

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
                // The request could not be sent; retry on the next tick rather
                // than spinning here.
                break;
            }

            ledger.send(entry.id(), size, tick);
            materials.markRunning(entry.id());

            itemBudget -= size;
            sendsThisTick++;
            lastSendMillis = nowMillis;
            nextSendDelay = computeDelay();
        }

        // Remember where the scan stopped so the next tick resumes fairly rather
        // than always favouring the first material.
        materials.cursor(index % candidates.size());
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
