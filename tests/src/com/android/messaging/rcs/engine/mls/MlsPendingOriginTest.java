/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsPendingOperation.Origin;

/**
 * Rework item 5.1 — the four origin categories and the category-dependent death remedy (§4.4).
 */
public class MlsPendingOriginTest {

    @Test public void exactlyOneCategoryEscalates() {
        // The whole point of the axis. If more than one escalated we would be back to churn; if
        // none did, a conversation that dies mid-inbound-processing stays wedged with nothing
        // watching it.
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
        // The upgrade case: a record written before the field existed decodes to UNKNOWN. Escalating
        // every one of those on the first boot after an update is exactly the churn the categories
        // exist to prevent.
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
        // Deliberately a property of WHERE the work came from, not of WHAT it was: what makes a
        // death worth recovering from is whether anyone is left to notice it.
        for (final MlsPendingOperation.Kind k : MlsPendingOperation.Kind.values()) {
            assertTrue(new MlsPendingOperation(k, Origin.PROCESS_MESSAGE_API, 1, 0L, null, "")
                    .escalatesOnDeath());
            assertFalse(new MlsPendingOperation(k, Origin.USER_ACTION, 1, 0L, null, "")
                    .escalatesOnDeath());
        }
    }

    @Test public void retryPreservesTheOrigin() {
        // A retry must not launder provenance: the second attempt of inbound-raised work is still
        // inbound-raised work.
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
        // The field exists to survive a restart; a codec that dropped it would leave the type
        // correct and the behaviour unchanged.
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
     * The two bounds Stage 6 moved onto this class, at their shipping values.
     *
     * <p>Both arms of {@link MlsPendingOperation#spent} are asserted separately and then together,
     * because the transport wrote them out longhand at three call sites and all three had to agree:
     * one question with two answers is how two of those three come to disagree later.
     */
    @Test public void theSlotIsSpentOnAttemptsOrOnAge() {
        assertEquals(5, MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS);
        assertEquals(10 * 60_000L, MlsPendingOperation.PENDING_OP_MAX_AGE_MS);

        final MlsPendingOperation fresh = MlsPendingOperation.start(
                MlsPendingOperation.Kind.EPOCH_ADVANCEMENT, Origin.PROCESS_MESSAGE_API,
                1_000L, null, "peer");
        assertFalse(fresh.attemptsExhausted());
        assertFalse(fresh.spent(1_000L));

        // The AGE arm alone: one attempt, but ten minutes gone.
        assertFalse(fresh.spent(1_000L + MlsPendingOperation.PENDING_OP_MAX_AGE_MS - 1));
        assertTrue(fresh.spent(1_000L + MlsPendingOperation.PENDING_OP_MAX_AGE_MS));

        // The ATTEMPT arm alone: five attempts inside the window.
        MlsPendingOperation op = fresh;
        for (int i = 1; i < MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS; i++) op = op.retried();
        assertEquals(MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS, op.attemptCount);
        assertTrue(op.attemptsExhausted());
        assertTrue(op.spent(1_000L));
    }

    /**
     * The wedge predicate fires one attempt EARLIER than exhaustion, and that is the whole point.
     *
     * <p>Its caller decides whether to enter §9.7j {@code CannotHealDuringEndMls}, a terminal state
     * that forbids both a fresh downgrade and a Phoenix run. It asks "would one more attempt exhaust
     * the bound", so the ordering below is the assertion: {@code lastAttemptExhaustsTheBound} must
     * be true while {@code attemptsExhausted} is still false.
     */
    @Test public void theWedgePredicateLeadsExhaustionByExactlyOne() {
        MlsPendingOperation op = MlsPendingOperation.start(
                MlsPendingOperation.Kind.END_MLS, Origin.USER_ACTION, 0L, null, "peer");
        for (int i = 1; i < MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS - 1; i++) op = op.retried();
        assertEquals(MlsPendingOperation.PENDING_OP_MAX_ATTEMPTS - 1, op.attemptCount);
        assertTrue(op.lastAttemptExhaustsTheBound());
        assertFalse("the wedge must lead exhaustion, never coincide with it", op.attemptsExhausted());
    }

}
