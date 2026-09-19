/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsPendingOperation.Origin;

/** A pending operation's four origin categories and the remedy when it dies. */
public class MlsPendingOriginTest {

    @Test public void exactlyOneCategoryEscalates() {
        // More than one escalating category would churn; none would leave a conversation that died
        // mid inbound processing wedged with nothing watching it.
        int escalating = 0;
        for (final Origin o : Origin.values()) {
            if (o.escalatesOnDeath) escalating++;
        }
        assertEquals(1, escalating);
        assertTrue(Origin.PROCESS_MESSAGE_API.escalatesOnDeath);
    }

    @Test public void theNonEscalatingThreeAllHaveSomebodyToReportTo() {
        assertFalse("a caller is waiting", Origin.EXPLICIT_API.escalatesOnDeath);
        assertFalse("the UI reports it", Origin.USER_ACTION.escalatesOnDeath);
        assertFalse("not load-bearing", Origin.OTHER.escalatesOnDeath);
    }

    @Test public void unknownDoesNotEscalate() {
        // A record written before the field existed decodes to UNKNOWN; escalating those on the
        // first boot after an update would churn.
        assertFalse(Origin.UNKNOWN.escalatesOnDeath);
        assertEquals(Origin.UNKNOWN, Origin.fromWire(0));
    }

    @Test public void wireNumbersRoundTripAndUnknownWireIsUnknown() {
        for (final Origin o : Origin.values()) {
            assertEquals(o, Origin.fromWire(o.wire));
        }
        assertEquals(Origin.UNKNOWN, Origin.fromWire(99));
        assertEquals(Origin.UNKNOWN, Origin.fromWire(-1));
    }

    @Test public void escalatesOnDeathDelegatesToTheOrigin() {
        // A property of where the work came from, not what it was: whether anyone is left to
        // notice.
        for (final MlsPendingOperation.Kind k : MlsPendingOperation.Kind.values()) {
            assertTrue(new MlsPendingOperation(k, Origin.PROCESS_MESSAGE_API, 1, 0L, null, "")
                    .escalatesOnDeath());
            assertFalse(new MlsPendingOperation(k, Origin.USER_ACTION, 1, 0L, null, "")
                    .escalatesOnDeath());
        }
    }

    @Test public void retryPreservesTheOrigin() {
        // A retry of inbound-raised work is still inbound-raised work.
        final MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT, Origin.PROCESS_MESSAGE_API,
                1000L, null, "peer");
        final MlsPendingOperation again = op.retried();
        assertEquals(Origin.PROCESS_MESSAGE_API, again.origin);
        assertEquals(2, again.attemptCount);
        assertEquals("the start time must not move", 1000L, again.startedAtMs);
    }

    @Test public void theLegacyConstructorDefaultsToUnknown() {
        assertEquals(Origin.UNKNOWN,
                new MlsPendingOperation(MlsPendingOperation.Kind.END_MLS, 1, 0L, null, "").origin);
        assertEquals(Origin.UNKNOWN, MlsPendingOperation.start(
                MlsPendingOperation.Kind.END_MLS, 0L, null, "").origin);
    }

    @Test public void aNullOriginIsUnknownNotACrash() {
        assertEquals(Origin.UNKNOWN,
                new MlsPendingOperation(MlsPendingOperation.Kind.END_MLS, null, 1, 0L, null, "")
                        .origin);
    }

    @Test public void theOriginSurvivesARecordRoundTrip() {
        // The field exists to survive a restart.
        final MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS, Origin.PROCESS_MESSAGE_API,
                4242L, null, "+15551234567");
        final MlsConversationRecord rec = MlsConversationRecord.builder()
                .pendingOperation(op).build();
        final MlsConversationRecord back = MlsConversationRecord.decode(rec.encode());
        assertNotNull(back);
        assertNotNull(back.pendingOperation);
        assertEquals(Origin.PROCESS_MESSAGE_API, back.pendingOperation.origin);
        assertEquals(MlsPendingOperation.Kind.COMMIT_PENDING_PROPOSALS, back.pendingOperation.kind);
        assertEquals(4242L, back.pendingOperation.startedAtMs);
        assertTrue(back.pendingOperation.escalatesOnDeath());
    }

    @Test public void toStringNamesTheOrigin() {
        assertTrue(MlsPendingOperation.start(MlsPendingOperation.Kind.END_MLS,
                Origin.USER_ACTION, 0L, null, "").toString().contains("USER_ACTION"));
    }

    /**
     * {@link MlsPendingOperation#spent} is one question with two arms, attempts and age, asked from
     * several call sites.
     */
    @Test public void theSlotIsSpentOnAttemptsOrOnAge() {
        assertEquals(5, MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS);
        assertEquals(10 * 60_000L, MlsPendingOperation.PENDING_OP_MAX_AGE_MS);

        final MlsPendingOperation fresh = MlsPendingOperation.start(
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT, Origin.PROCESS_MESSAGE_API,
                1_000L, null, "peer");
        assertFalse(fresh.attemptsExhausted());
        assertFalse(fresh.spent(1_000L));

        // The age arm alone: one attempt, ten minutes gone.
        assertFalse(fresh.spent(1_000L + MlsPendingOperation.PENDING_OP_MAX_AGE_MS - 1));
        assertTrue(fresh.spent(1_000L + MlsPendingOperation.PENDING_OP_MAX_AGE_MS));

        // The attempt arm alone: five attempts inside the window.
        MlsPendingOperation op = fresh;
        for (int i = 1; i < MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS; i++) op = op.retried();
        assertEquals(MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS, op.attemptCount);
        assertTrue(op.attemptsExhausted());
        assertTrue(op.spent(1_000L));
    }

    /**
     * The wedge predicate fires one attempt before exhaustion: its caller decides whether to enter
     * {@code CannotHealDuringEndMls} and asks whether one more attempt would exhaust the bound.
     */
    @Test public void theWedgePredicateLeadsExhaustionByExactlyOne() {
        MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.END_MLS, Origin.USER_ACTION, 0L, null, "peer");
        for (int i = 1; i < MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS - 1; i++) op = op.retried();
        assertEquals(MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS - 1, op.attemptCount);
        assertTrue(op.lastAttemptExhaustsTheBound());
        assertFalse("the wedge must lead exhaustion, never coincide with it",
                op.attemptsExhausted());
    }

}
