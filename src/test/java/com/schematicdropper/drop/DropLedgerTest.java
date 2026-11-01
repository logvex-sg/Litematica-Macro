package com.schematicdropper.drop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger is what stops the module counting drops the server never accepted,
 * so these cases are the most important in the suite.
 */
class DropLedgerTest {
    @Test
    void aSendIsNotADropUntilTheServerSpawnsItems() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        // Nothing observed yet: the drop must not be counted.
        assertFalse(ledger.isEmpty());
        assertEquals(1, ledger.outstandingRequests("minecraft:stone"));
        assertEquals(64, ledger.outstandingItems("minecraft:stone"));
    }

    @Test
    void observingTheExactAmountConfirms() {
        DropLedger ledger = new DropLedger();
        DropLedger.Request request = ledger.send("minecraft:stone", 64, 0);

        List<DropLedger.Resolution> resolutions = ledger.observe("minecraft:stone", 64, 2);

        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.CONFIRMED, resolutions.get(0).outcome());
        assertEquals(request.id(), resolutions.get(0).id());
        assertEquals(64, resolutions.get(0).count());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void observingTooFewItemsConfirmsNothing() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        // A single item entity carrying 10 items is not 64.
        assertTrue(ledger.observe("minecraft:stone", 10, 1).isEmpty());
        assertEquals(1, ledger.outstandingRequests("minecraft:stone"));

        // The remaining 54 complete it.
        List<DropLedger.Resolution> resolutions = ledger.observe("minecraft:stone", 54, 2);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.CONFIRMED, resolutions.get(0).outcome());
    }

    @Test
    void observationsAccumulateAcrossSeveralEntities() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        assertTrue(ledger.observe("minecraft:stone", 20, 1).isEmpty());
        assertTrue(ledger.observe("minecraft:stone", 20, 2).isEmpty());

        List<DropLedger.Resolution> resolutions = ledger.observe("minecraft:stone", 24, 3);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.CONFIRMED, resolutions.get(0).outcome());
    }

    @Test
    void confirmationsAreHandedOutOldestFirst() {
        DropLedger ledger = new DropLedger();
        DropLedger.Request first = ledger.send("minecraft:stone", 10, 0);
        DropLedger.Request second = ledger.send("minecraft:stone", 10, 0);

        List<DropLedger.Resolution> resolutions = ledger.observe("minecraft:stone", 10, 1);

        assertEquals(1, resolutions.size());
        assertEquals(first.id(), resolutions.get(0).id());
        assertEquals(1, ledger.outstandingRequests("minecraft:stone"));

        resolutions = ledger.observe("minecraft:stone", 10, 2);
        assertEquals(1, resolutions.size());
        assertEquals(second.id(), resolutions.get(0).id());
    }

    @Test
    void aSingleEntityCanConfirmSeveralRequests() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 10, 0);
        ledger.send("minecraft:stone", 10, 0);

        // The server merged both drops into one stack of 20.
        List<DropLedger.Resolution> resolutions = ledger.observe("minecraft:stone", 20, 1);

        assertEquals(2, resolutions.size());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void observationsForAnUnknownItemAreIgnored() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 10, 0);

        assertTrue(ledger.observe("minecraft:dirt", 64, 1).isEmpty());
        // The stray observation must not be banked for a later stone request.
        assertTrue(ledger.observe("minecraft:stone", 10, 2).size() == 1);
    }

    @Test
    void unconfirmedRequestsRetryThenStall() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        // Still inside the timeout.
        assertTrue(ledger.poll(5, 10, 3).isEmpty());

        // Timed out on the first attempt.
        List<DropLedger.Resolution> resolutions = ledger.poll(10, 10, 3);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.RETRY, resolutions.get(0).outcome());
        assertEquals(1, ledger.outstandingRequests("minecraft:stone"));

        // Second attempt expires.
        resolutions = ledger.poll(20, 10, 3);
        assertEquals(DropLedger.Outcome.RETRY, resolutions.get(0).outcome());

        // Third attempt expires and the budget is spent.
        resolutions = ledger.poll(30, 10, 3);
        assertEquals(DropLedger.Outcome.STALLED, resolutions.get(0).outcome());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void aRetryThatIsLaterConfirmedStillCounts() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);
        ledger.poll(10, 10, 3);

        List<DropLedger.Resolution> resolutions = ledger.observe("minecraft:stone", 64, 11);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.CONFIRMED, resolutions.get(0).outcome());
    }

    @Test
    void pollingOnlyExpiresTheOldestRequest() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 10, 0);
        ledger.send("minecraft:stone", 10, 9);

        List<DropLedger.Resolution> resolutions = ledger.poll(10, 10, 3);

        // Only the first is due; the second was sent one tick ago.
        assertEquals(1, resolutions.size());
        assertEquals(2, ledger.outstandingRequests("minecraft:stone"));
    }

    @Test
    void abandonForgetsEverythingForAnItem() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 10, 0);
        ledger.observe("minecraft:stone", 5, 1);

        ledger.abandon("minecraft:stone");

        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.outstandingItems("minecraft:stone"));
        // A late entity from the abandoned attempt must not confirm anything.
        assertTrue(ledger.observe("minecraft:stone", 5, 2).isEmpty());
    }

    @Test
    void clearResetsTheWholeLedger() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 10, 0);
        ledger.send("minecraft:dirt", 10, 0);

        ledger.clear();

        assertTrue(ledger.isEmpty());
        assertTrue(ledger.snapshot().isEmpty());
    }

    @Test
    void sendRejectsNonsense() {
        DropLedger ledger = new DropLedger();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> ledger.send("minecraft:stone", 0, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> ledger.send(null, 1, 0));
    }

    @Test
    void idsAreUniqueAcrossItems() {
        DropLedger ledger = new DropLedger();
        DropLedger.Request a = ledger.send("minecraft:stone", 1, 0);
        DropLedger.Request b = ledger.send("minecraft:dirt", 1, 0);
        DropLedger.Request c = ledger.send("minecraft:stone", 1, 0);

        assertEquals(3, java.util.Set.of(a.id(), b.id(), c.id()).size());
    }

    @Test
    void aDuplicatedEntityEventCannotOverCredit() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 5, 0);

        // The same item entity reported twice. Only 5 items are outstanding, so
        // the second report must be ignored rather than counted again.
        assertEquals(1, ledger.observe("minecraft:stone", 5, 0).size());
        assertTrue(ledger.observe("minecraft:stone", 5, 0).isEmpty());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void confirmationsNeverExceedWhatWasSent() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);
        ledger.send("minecraft:stone", 64, 0);

        // Far more items are reported than were ever asked for. The ledger may
        // confirm at most the 128 that were sent.
        int confirmed = 0;
        for (int i = 0; i < 10; i++) {
            for (DropLedger.Resolution resolution : ledger.observe("minecraft:stone", 64, 0)) {
                confirmed += resolution.count();
            }
        }

        assertEquals(128, confirmed);
        assertTrue(ledger.isEmpty());
    }

    @Test
    void aRetriedDropIsCreditedOnceAndNotTwice() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        // The first attempt times out and is resent, so 128 items were asked for.
        assertEquals(DropLedger.Outcome.RETRY, ledger.poll(10, 10, 3).get(0).outcome());

        // The server then delivers both batches. The request is satisfied by the
        // first one; the second is a real over-delivery that the ledger must not
        // also count, or the module would think it still owed nothing while 64
        // extra items sit on the floor.
        int confirmed = 0;
        for (int i = 0; i < 4; i++) {
            for (DropLedger.Resolution resolution : ledger.observe("minecraft:stone", 64, 11)) {
                confirmed += resolution.count();
            }
        }

        assertEquals(64, confirmed);
        assertTrue(ledger.isEmpty());
    }

    @Test
    void aSingleAttemptNeverRetries() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        List<DropLedger.Resolution> resolutions = ledger.poll(100, 10, 1);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.STALLED, resolutions.get(0).outcome());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void aTimeoutWithResendingDisabledIsReportedOnceAndKeepsTheRequest() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        // Resending is off, so the first expiry is reported as TIMED_OUT...
        List<DropLedger.Resolution> resolutions = ledger.poll(10, 10, 3, false);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.TIMED_OUT, resolutions.get(0).outcome());

        // ...but the request stays outstanding: nothing was removed and no second
        // send was recorded, so a slow server can still confirm it.
        assertEquals(1, ledger.outstandingRequests("minecraft:stone"));
        assertEquals(64, ledger.outstandingItems("minecraft:stone"));
        assertTrue(ledger.hasTimedOut());

        // The same expiry is not reported again while it remains timed out.
        assertTrue(ledger.poll(20, 10, 3, false).isEmpty());

        // A late confirmation is still honoured.
        resolutions = ledger.observe("minecraft:stone", 64, 21);
        assertEquals(1, resolutions.size());
        assertEquals(DropLedger.Outcome.CONFIRMED, resolutions.get(0).outcome());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void aTimeoutNeverProducesASecondSendWhenResendingIsDisabled() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        for (int tick = 0; tick < 100; tick++) {
            ledger.poll(tick, 5, 3, false);
        }

        // The send ceiling never rose, which is what proves no resend happened.
        assertEquals(64, ledger.sentTotal("minecraft:stone"));
        assertEquals(1, ledger.outstandingRequests("minecraft:stone"));
    }

    @Test
    void hasTimedOutIsFalseWhileAResendIsAllowed() {
        DropLedger ledger = new DropLedger();
        ledger.send("minecraft:stone", 64, 0);

        ledger.poll(10, 10, 3, true);
        // A resend resets the timer, so the request is not stuck waiting.
        assertFalse(ledger.hasTimedOut());
    }
}
