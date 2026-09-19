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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The out-of-order queue — §10.8, rework item {@code 6.7}.
 *
 * <p>Item {@code 6.7} itemised <b>ten</b> conflicts between what we had and what §10.8 proves. This
 * file is those ten as assertions, which is the point of having built the queue as a pure class: the
 * old buffer's properties could only be observed by running a device through a specific interleaving
 * of commits, and several of them (the silent drop past 8, the strand escalation) were reachable only
 * after a sequence nobody would produce deliberately.
 */
public class MlsPendingQueueTest {

    private static final byte[] GID = new byte[] { 1, 2, 3, 4 };
    private static final byte[] OTHER_GID = new byte[] { 9, 9, 9, 9 };

    private static Moment m(final int era, final long epoch) { return new Moment(era, epoch); }

    /** A valid entry: PublicMessage(1), our group, a moment. */
    private static MlsPendingQueue.Entry e(final String id, final int era, final long epoch) {
        return new MlsPendingQueue.Entry(id, m(era, epoch), 1, GID, new byte[] { 7, 7 });
    }

    // ================= CONFLICT 2 — ADMISSION: G1 exists and is pre-decrypt =================

    @Test
    public void g1_buffersWheneverTheGroupIsMidTransition() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            final MlsPendingQueue.Admission a = MlsPendingQueue.admit(s, m(1, 5), m(1, 5), m(1, 5),
                    /*failed=*/ false, false, false, false, false);
            assertEquals("status " + s,
                    MlsHealthPredicates.buffersInbound(s)
                            ? MlsPendingQueue.Admission.GROUP_LOCKED
                            : MlsPendingQueue.Admission.NONE,
                    a);
        }
    }

    /** A HEALTHY group never takes G1 — inbound is processed inline. */
    @Test
    public void g1_neverFiresOnAHealthyGroup() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 5), m(1, 5),
                        false, false, false, false, false));
    }

    /** G1 is checked FIRST — it does not need a failure, and it wins over the other two. */
    @Test
    public void g1_isCheckedBeforeTheFailureArms() {
        assertEquals(MlsPendingQueue.Admission.GROUP_LOCKED,
                MlsPendingQueue.admit(MlsHealthStates.ONGOINGERAADVANCEMENT,
                        m(9, 0), m(1, 5), m(1, 5), /*failed=*/ true, true, true, false, false));
    }

    // ============== CONFLICT 3 — MOMENT: era MAJOR, and STRICTLY greater for G2 ==============

    /**
     * <b>The single most consequential fix in this file.</b> The old classification compared epochs
     * only, so era N+1 epoch 0 read as PAST against era N epoch 40 and was DROPPED — losing the
     * first message of every new era, which is exactly the message that would have told us the era
     * had advanced.
     */
    @Test
    public void g2_eraIsTheMajorKey() {
        final Moment newEraLowEpoch = m(2, 0);
        final Moment oldEraHighEpoch = m(1, 40);
        assertTrue("era 2 epoch 0 is AFTER era 1 epoch 40",
                MlsPendingQueue.strictlyAfter(newEraLowEpoch, oldEraHighEpoch));
        // ...and the epoch-only reading would have said the opposite.
        assertTrue(0L < 40L);
        assertEquals(MlsPendingQueue.Admission.FROM_FUTURE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, newEraLowEpoch, oldEraHighEpoch,
                        oldEraHighEpoch,
                        true, true, false, false, false));
    }

    /** G2 is STRICTLY greater. Equal is not admitted. */
    @Test
    public void g2_refusesAMessageAtOurOwnMoment() {
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, 5), m(1, 5)));
        assertEquals("a message at our moment is an ERROR, not a deferral",
                MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 5), m(1, 5),
                        true, true, false, false, false));
    }

    /** And behind is never admitted, even when OutOfOrderCommit was reported. */
    @Test
    public void g2_refusesAMessageBehindUsEvenWithOutOfOrderCommit() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 4), m(1, 5), m(1, 5),
                        true, /*outOfOrderCommit=*/ true, false, false, false));
    }

    /** G2 requires the failure. A message from the future that PROCESSED is not queued. */
    @Test
    public void g2_requiresProcessingToHaveFailed() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(2, 0), m(1, 5), m(1, 5),
                        /*failed=*/ false, true, true, false, false));
    }

    // ===================== CONFLICT 4 — G3, the resend arm, was absent =====================

    @Test
    public void g3_admitsAResentFtdAtOrAfterOurMoment() {
        assertEquals(MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 5), m(1, 5),
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /** <b>G3 uses {@code >=}, unlike G2.</b> The asymmetry is Google Messages' and is easy to "tidy" away. */
    @Test
    public void g3_usesAtOrAfterWhereG2UsesStrictlyAfter() {
        final Moment same = m(1, 5);
        // Same inputs, different arm, opposite answers.
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, same, same, same,
                        true, true, false, /*resentFtd=*/ false, false));
        assertEquals(MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, same, same, same,
                        false, false, false, /*resentFtd=*/ true, false));
    }

    @Test
    public void g3_isRefusedWhenTheSameApplicationMessageIsFailing() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 6), m(1, 5), m(1, 5),
                        false, false, false, true, /*sameApplicationMessageFailing=*/ true));
    }

    /** And across the WHOLE end-MLS family — G3's exclusion set is {@code is_downgraded}. */
    @Test
    public void g3_isRefusedAcrossTheEndMlsFamily() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (!MlsHealthPredicates.isDowngraded(s)) continue;
            final MlsPendingQueue.Admission a = MlsPendingQueue.admit(s, m(1, 6), m(1, 5), m(1, 5),
                    false, false, false, /*resentFtd=*/ true, false);
            // 12, 16 and 17 are downgraded AND buffer, so they take G1 first — which is correct and
            // is precisely why G1's mask is not the complement of is_downgraded.
            assertNotEquals("status " + s + " must not reach G3",
                    MlsPendingQueue.Admission.RESENT_FTD, a);
        }
    }

    // ============== §22.1-39 — G3's MOMENT SOURCE IS recovered_at, NOT THE GROUP ==============

    /**
     * <b>The §22.1-39 fix, and the assertion that fails without it.</b>
     *
     * <p>G3 compares the message moment against {@code SelfHealState.recovered_at}, not
     * against the live group moment that G2 uses.
     *
     * <p><b>The state that makes this FAIL is reachable and was shipped:</b> passing {@code group}
     * where {@code recoveredAt} now goes — which is exactly what {@code admit} did until
     * 2026-09-11 — returns {@code NONE} here, because {@code (1,6)} is behind the live
     * {@code (1,10)}. That is the too-strict failure: Google Messages queues this resend and we would FTD
     * it, so the sender resends, and neither side's logs look wrong.
     */
    @Test
    public void g3_comparesAgainstRecoveredAtNotTheLiveGroupMoment() {
        final Moment group = m(1, 10);          // live: the group has committed on since recovery
        final Moment recoveredAt = m(1, 4);     // where the last recovery LANDED
        final Moment msg = m(1, 6);             // behind the group, ahead of the recovery landing
        assertEquals("a resend at or after recovered_at is QUEUED, even though it is behind the "
                        + "live group moment",
                MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, msg, group, recoveredAt,
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /**
     * <b>The operator cannot explain this split — only the SOURCE can.</b>
     *
     * <p>With {@code msg} strictly AFTER {@code recoveredAt} and strictly BEFORE {@code group},
     * neither {@code >} nor {@code >=} against a single shared moment produces one arm admitting
     * and the other refusing. This is the test that stops the two arms being re-collapsed into
     * "same value, different operator" — the shape they had for six weeks.
     */
    @Test
    public void g2AndG3ReadDifferentMomentsNotJustDifferentOperators() {
        final Moment group = m(1, 10);
        final Moment recoveredAt = m(1, 4);
        final Moment msg = m(1, 6);
        assertEquals("G2 reads the LIVE group moment: (1,6) is not from the future",
                MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, msg, group, recoveredAt,
                        /*failed=*/ true, /*outOfOrderCommit=*/ true, false, false, false));
        assertEquals("G3 reads recovered_at: the same (1,6) IS admitted",
                MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, msg, group, recoveredAt,
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /**
     * <b>An unset {@code recovered_at} admits.</b> Google Messages' accessors read {@code +0x2c}/
     * {@code +0x30} raw with no has-bit test, and a null submessage yields the all-zero default
     * instance — so a group that has never completed a recovery compares against {@code (0,0)} and
     * the gate always passes.
     *
     * <p>This fails under BOTH wrong readings: against the live group moment {@code (1,10)}, and
     * under the natural-looking "a null moment is never after anything" rule that
     * {@link MlsPendingQueue#strictlyAfter} rightly applies to the GROUP moment.
     */
    @Test
    public void g3_anUnsetRecoveredAtAdmits() {
        assertEquals("no recovery has ever landed, so recovered_at reads (0,0) and everything "
                        + "clears the gate",
                MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 0), m(1, 10),
                        /*recoveredAt=*/ null, false, false, false, /*resentFtd=*/ true, false));
    }

    /**
     * The gate is still a gate. A resend BEHIND the recovery landing is refused — so the fix cannot
     * be satisfied by making {@code atOrAfterRecoveredAt} unconditionally true, which is the
     * degenerate way to pass the three tests above.
     */
    @Test
    public void g3_refusesAResendBehindRecoveredAt() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 10), m(1, 6),
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /** Era stays the major key on G3's comparand too. */
    @Test
    public void g3_eraIsTheMajorKeyAgainstRecoveredAt() {
        assertEquals("era 2 epoch 0 is at-or-after era 1 epoch 40",
                MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(2, 0), m(2, 9), m(1, 40),
                        false, false, false, /*resentFtd=*/ true, false));
        assertEquals("and era 1 epoch 40 is NOT at-or-after era 2 epoch 0",
                MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 40), m(2, 9), m(2, 0),
                        false, false, false, /*resentFtd=*/ true, false));
    }

    // ==================== CONFLICT 5 — NO BOUND, NO EVICTION, NO CUTOFF ====================

    /**
     * §10.8's observed negative: no capacity, no eviction, no TTL, no far-future cutoff. The old
     * buffer capped at 8 and dropped silently past it.
     *
     * <p><b>THIS TEST ASSERTS AN ABSENCE WE INHERITED, AND IT IS THE ONE WITH REAL STAKES.</b> The
     * negative comes from a whole-binary scan of Google Messages that found no bound; we then built
     * an unbounded queue on the strength of it. If the scan missed a bound — inlined, expressed as a
     * constant with no string, or enforced by the CALLER rather than the queue — then we have an
     * unbounded-growth bug in a structure fed by the network, and this test is pinning it in place.
     *
     * <p><b>FALSIFIER:</b> a bound found in any Google Messages build (a capacity constant reaching the
     * queue, an eviction path, a TTL sweep), or a device observation of Google Messages DROPPING a pending
     * message that we would have kept. Any of those and these three tests invert: they should then
     * assert the bound, not its absence.
     *
     * <p><b>ORTHOGONALLY — the absence being real does not make it right for us.</b> Google Messages having
     * no bound is a fact about Google Messages, not a safety argument for us: it has different memory
     * headroom, a different process lifetime and a different drain cadence. A bound of our own is a
     * defensible divergence and would need no falsifier at all; what would need one is the claim
     * that we must not add one. The queue's undrilled edges are tracked separately.
     */
    @Test
    public void thereIsNoCapacityBound() {
        final MlsPendingQueue q = new MlsPendingQueue();
        for (int i = 0; i < 500; i++) {
            assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("m" + i, 1, i), GID));
        }
        assertEquals("no bound, no silent drop", 500, q.size());
    }

    /** <i>"A message claiming era 9 999 is queued exactly like one at era N+1."</i> */
    @Test
    public void thereIsNoFarFutureCutoff() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("far", 9999, 0), GID));
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("near", 2, 0), GID));
        assertEquals(2, q.size());
        assertEquals(1, q.take(9999, 0).size());
    }

    // ================ CONFLICT 6 — DRAIN is an EXACT-KEY take, not a sweep ================

    /**
     * <b>The fix that made deleting the strand counter safe.</b> A sweep re-attempts messages parked
     * at N+2 when the group reaches N and again at N+1; each attempt fails, and three of those
     * failures declared the conversation permanently stranded. The sweep manufactured the failures
     * the counter acted on.
     */
    @Test
    public void takeIsExactKeyAndLeavesOtherMomentsAlone() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("at-5", 1, 5), GID);
        q.store(e("at-6", 1, 6), GID);
        q.store(e("at-7", 1, 7), GID);

        // At epoch 5 we take ONLY epoch 5.
        final List<MlsPendingQueue.Entry> got = q.take(1, 5);
        assertEquals(1, got.size());
        assertEquals("at-5", got.get(0).messageId);
        assertEquals("6 and 7 must still be parked", 2, q.size());

        // Epoch 6 arrives; 7 still waits.
        assertEquals(1, q.take(1, 6).size());
        assertEquals(1, q.size());
    }

    /** A take at a moment with nothing parked is empty, not an error and not a sweep. */
    @Test
    public void takeAtAnEmptyMomentTakesNothing() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("later", 1, 9), GID);
        assertTrue(q.take(1, 5).isEmpty());
        assertEquals(1, q.size());
    }

    /** Era is part of the key: the same epoch in a different era is a different bucket. */
    @Test
    public void takeIsKeyedByEraAsWellAsEpoch() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("era1", 1, 3), GID);
        q.store(e("era2", 2, 3), GID);
        assertEquals(1, q.take(1, 3).size());
        assertEquals("the era-2 entry at the same epoch is untouched", 1, q.size());
    }

    /** Within a bucket, insertion order — which is why a bucket is a List. */
    @Test
    public void aBucketDrainsInInsertionOrder() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("first", 1, 5), GID);
        q.store(e("second", 1, 5), GID);
        q.store(e("third", 1, 5), GID);
        final List<MlsPendingQueue.Entry> got = q.take(1, 5);
        assertEquals(Arrays.asList("first", "second", "third"),
                Arrays.asList(got.get(0).messageId, got.get(1).messageId, got.get(2).messageId));
    }

    // ============ CONFLICT 7 — the FOUR store validations and per-bucket dedup ============

    @Test
    public void validation1_variantTagMustBeUnderThree() {
        final MlsPendingQueue q = new MlsPendingQueue();
        for (int tag : new int[] { 3, 4, 5, 99 }) {
            assertEquals("wire_format " + tag + " is a Welcome/GroupInfo/KeyPackage, not a message",
                    MlsPendingQueue.StoreResult.INVALID_WIRE_FORMAT,
                    q.store(new MlsPendingQueue.Entry("x", m(1, 1), tag, GID, new byte[] { 1 }),
                            GID));
        }
        // 1 and 2 are the two that ARE messages to a group.
        assertEquals(MlsPendingQueue.StoreResult.STORED,
                q.store(new MlsPendingQueue.Entry("pub", m(1, 1), 1, GID, new byte[] { 1 }), GID));
        assertEquals(MlsPendingQueue.StoreResult.STORED,
                q.store(new MlsPendingQueue.Entry("priv", m(1, 1), 2, GID, new byte[] { 1 }), GID));
    }

    @Test
    public void validation2_groupIdMustBePresent() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.MISSING_REQUIRED_GROUP_ID,
                q.store(new MlsPendingQueue.Entry("x", m(1, 1), 1, null, new byte[] { 1 }), GID));
        assertEquals(MlsPendingQueue.StoreResult.MISSING_REQUIRED_GROUP_ID,
                q.store(new MlsPendingQueue.Entry("x", m(1, 1), 1, new byte[0], new byte[] { 1 }),
                        GID));
    }

    @Test
    public void validation3_groupIdMustMatch() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.INVALID_GROUP_ID,
                q.store(new MlsPendingQueue.Entry("x", m(1, 1), 1, OTHER_GID, new byte[] { 1 }),
                        GID));
    }

    @Test
    public void validation4_epochMustBePresent() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.MISSING_REQUIRED_EPOCH,
                q.store(new MlsPendingQueue.Entry("x", null, 1, GID, new byte[] { 1 }), GID));
    }

    /**
     * <b>THE ORDER, which is not the numeric order.</b> group-id-MATCHES ({@code 28}) is checked
     * BEFORE epoch-present ({@code 27}). A message for the wrong group with no epoch reports 28.
     *
     * <p>This is the assertion most likely to be "fixed" into agreement with the numbering, and the
     * one Google Messages is explicit about.
     */
    @Test
    public void theValidationOrderIsNotTheNumericOrder() {
        final MlsPendingQueue q = new MlsPendingQueue();
        final MlsPendingQueue.StoreResult r =
                q.store(new MlsPendingQueue.Entry("x", null, 1, OTHER_GID, new byte[] { 1 }), GID);
        assertEquals("wrong group AND no epoch must report INVALID_GROUP_ID",
                MlsPendingQueue.StoreResult.INVALID_GROUP_ID, r);
        assertEquals(28, MlsPendingQueue.StoreResult.INVALID_GROUP_ID.errorOrdinal);
        assertEquals(27, MlsPendingQueue.StoreResult.MISSING_REQUIRED_EPOCH.errorOrdinal);
        assertTrue("...even though 28 > 27", 28 > 27);
    }

    /** And the variant tag is checked before everything, including a wrong group id. */
    @Test
    public void theVariantTagIsCheckedFirstOfAll() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.INVALID_WIRE_FORMAT,
                q.store(new MlsPendingQueue.Entry("x", null, 4, OTHER_GID, new byte[] { 1 }), GID));
    }

    @Test
    public void theErrorOrdinalsMatchTheReferenceClient() {
        assertEquals(25, MlsPendingQueue.StoreResult.INVALID_WIRE_FORMAT.errorOrdinal);
        assertEquals(26, MlsPendingQueue.StoreResult.MISSING_REQUIRED_GROUP_ID.errorOrdinal);
        assertEquals(27, MlsPendingQueue.StoreResult.MISSING_REQUIRED_EPOCH.errorOrdinal);
        assertEquals(28, MlsPendingQueue.StoreResult.INVALID_GROUP_ID.errorOrdinal);
        assertFalse(MlsPendingQueue.StoreResult.STORED.isError());
        assertFalse("a duplicate is NOT an error — Google Messages returns Ok",
                MlsPendingQueue.StoreResult.DUPLICATE.isError());
    }

    /** Dedup is by message id WITHIN a bucket, and a duplicate is not an error. */
    @Test
    public void dedupIsPerBucketAndIsNotAnError() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("dup", 1, 5), GID));
        assertEquals(MlsPendingQueue.StoreResult.DUPLICATE, q.store(e("dup", 1, 5), GID));
        assertEquals("the duplicate was not inserted", 1, q.size());
    }

    /**
     * The same id at a DIFFERENT moment is a different delivery attempt and must be kept. Deduping
     * globally would drop the one that can actually be applied.
     */
    @Test
    public void theSameIdInADifferentBucketIsNotADuplicate() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("same", 1, 5), GID));
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("same", 1, 6), GID));
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("same", 2, 5), GID));
        assertEquals(3, q.size());
    }

    @Test
    public void theDuplicateLineIsTheReferenceClientsVerbatim() {
        assertEquals("Pending message already exists for message id: abc",
                MlsPendingQueue.duplicateLine("abc"));
    }

    // =================== An era advance does NOT purge a stale partition ===================

    /**
     * §10.8, observed: only two functions in Google Messages touch the era map and neither prunes. An
     * abandoned era's partition is retained until the whole per-group blob is deleted.
     */
    @Test
    public void anAbandonedEraPartitionIsRetained() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("old", 1, 5), GID);
        q.store(e("new", 2, 0), GID);
        q.take(2, 0);                       // the group moves on into era 2 and drains it
        assertEquals("era 1's partition survives", 1, q.size());
        assertTrue(q.eras().contains(1));
    }

    // ================================ persistence (conflict 1) ================================

    /**
     * The old buffer was in-memory, so a message deferred at 11:59 was gone at midnight. Round-trip
     * through the codec is what makes the queue survive process death.
     */
    @Test
    public void theQueueRoundTripsThroughItsCodec() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("a", 1, 5), GID);
        q.store(e("b", 1, 5), GID);
        q.store(e("c", 2, 0), GID);
        final MlsPendingQueue back = MlsPendingQueue.fromBytes(q.toBytes());
        assertEquals(3, back.size());
        assertEquals(q.debugLayout(), back.debugLayout());
        // ...and the blobs survive, since replaying them is the whole point.
        final List<MlsPendingQueue.Entry> got = back.take(1, 5);
        assertEquals(2, got.size());
        assertTrue(Arrays.equals(new byte[] { 7, 7 }, got.get(0).blob));
    }

    /**
     * The PLANE survives the codec.
     *
     * <p>Not a formality. The plane is the only thing distinguishing a parked application message
     * from a parked commit ({@code variantTag} cannot: both are PrivateMessage), and losing it in
     * the codec restores the exact defect this field was added to fix — with the message decrypting
     * successfully and then being discarded, which no failure test would catch.
     */
    @Test
    public void thePlaneSurvivesTheCodec() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(new MlsPendingQueue.Entry("app", m(1, 5), 2, GID, new byte[] { 7, 7 },
                MlsPendingQueue.Plane.APPLICATION), GID);
        q.store(new MlsPendingQueue.Entry("ctrl", m(1, 5), 2, GID, new byte[] { 7, 7 },
                MlsPendingQueue.Plane.CONTROL), GID);
        final List<MlsPendingQueue.Entry> got =
                MlsPendingQueue.fromBytes(q.toBytes()).take(1, 5);
        assertEquals(2, got.size());
        assertEquals(MlsPendingQueue.Plane.APPLICATION, got.get(0).plane);
        assertEquals(MlsPendingQueue.Plane.CONTROL, got.get(1).plane);
    }

    /** The 5-arg constructor is the CONTROL plane — the historical shape, and the safe default. */
    @Test
    public void anEntryWithNoStatedPlaneIsControl() {
        assertEquals(MlsPendingQueue.Plane.CONTROL, e("a", 1, 5).plane);
    }

    /**
     * A v1 blob still decodes, and every entry in one comes back CONTROL.
     *
     * <p>Asserted rather than left implicit because it is a KNOWN-WRONG answer for a v1-parked
     * application message (it will replay to the control door and be discarded, as it does today).
     * The alternative — sniffing a plane out of the wire — would be confidently wrong on someone's
     * message instead of knowably wrong on ours, so the guess is pinned here where it is visible.
     */
    @Test
    public void aV1BlobDecodesAsControl() throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        d.writeInt(1);                       // CODEC_VERSION 1
        d.writeInt(1);                       // one entry
        d.writeUTF("legacy");
        d.writeInt(1);                       // era
        d.writeLong(5L);                     // epoch
        d.writeInt(2);                       // variantTag
        d.writeInt(GID.length); d.write(GID);
        d.writeInt(2); d.write(new byte[] { 7, 7 });
        // ...and NO plane byte: that is what makes it a v1 record.
        final List<MlsPendingQueue.Entry> got =
                MlsPendingQueue.fromBytes(out.toByteArray()).take(1, 5);
        assertEquals(1, got.size());
        assertEquals("legacy", got.get(0).messageId);
        assertEquals(MlsPendingQueue.Plane.CONTROL, got.get(0).plane);
    }

    @Test
    public void anEmptyQueueRoundTrips() {
        final MlsPendingQueue q = MlsPendingQueue.fromBytes(new MlsPendingQueue().toBytes());
        assertTrue(q.isEmpty());
        assertTrue(MlsPendingQueue.fromBytes(null).isEmpty());
        assertTrue(MlsPendingQueue.fromBytes(new byte[0]).isEmpty());
    }

    /** A malformed blob degrades to empty rather than throwing — losing parked messages costs a
     *  resend, refusing to start costs the conversation. */
    @Test
    public void aMalformedBlobDegradesToEmpty() {
        assertTrue(MlsPendingQueue.fromBytes(new byte[] { 1, 2, 3 }).isEmpty());
        assertTrue(MlsPendingQueue.fromBytes(new byte[] { -1, -1, -1, -1, 5, 5, 5, 5 }).isEmpty());
    }

    // ==================== the wire-format / epoch readers the host feeds it ====================

    @Test
    public void wireFormatIsReadFromBytesTwoAndThree() {
        assertEquals(1, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 1, 9 }));
        assertEquals(2, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 2, 9 }));
        assertEquals(3, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 3, 9 }));
        assertEquals(4, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 4, 9 }));
    }

    /** A blob whose version is not {@code 00 01} is not an MLSMessage, and must not pass. */
    @Test
    public void wireFormatRefusesANonMlsMessage() {
        assertEquals(-1, MlsWireScan.wireFormatOf(new byte[] { 9, 9, 0, 1 }));
        assertEquals(-1, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0 }));
        assertEquals(-1, MlsWireScan.wireFormatOf(null));
    }

    /**
     * The epoch sits after a variable-length group-id vector. Reading the length prefix as one byte
     * unconditionally would take the epoch from the middle of the group id — a plausible-looking
     * number rather than an error, which is the worst failure shape available here.
     */
    @Test
    public void epochIsReadPastAVariableLengthGroupId() {
        // 1-byte prefix (top bits 00): len 4, then epoch 7.
        final byte[] oneBytePrefix = new byte[] {
                0, 1, 0, 1,                       // version, wire_format = PublicMessage
                4, 11, 12, 13, 14,                // group id: len 4
                0, 0, 0, 0, 0, 0, 0, 7 };         // epoch = 7
        assertEquals(7L, MlsWireScan.epochOf(oneBytePrefix));

        // 2-byte prefix (top bits 01): 0x40 | 0x00, 0x04 => len 4.
        final byte[] twoBytePrefix = new byte[] {
                0, 1, 0, 2,                       // PrivateMessage
                0x40, 4, 11, 12, 13, 14,
                0, 0, 0, 0, 0, 0, 0, 9 };
        assertEquals(9L, MlsWireScan.epochOf(twoBytePrefix));
    }

    /** Only PublicMessage and PrivateMessage have an epoch at that position. */
    @Test
    public void epochIsRefusedForNonGroupMessages() {
        final byte[] welcome = new byte[] { 0, 1, 0, 3, 4, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 1 };
        assertEquals(-1, MlsWireScan.epochOf(welcome));
        final byte[] groupInfo = new byte[] { 0, 1, 0, 4, 4, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 1 };
        assertEquals(-1, MlsWireScan.epochOf(groupInfo));
    }

    @Test
    public void epochIsRefusedWhenTruncated() {
        assertEquals(-1, MlsWireScan.epochOf(new byte[] { 0, 1, 0, 1, 4, 1, 2, 3, 4, 0, 0 }));
        assertEquals(-1, MlsWireScan.epochOf(new byte[] { 0, 1, 0, 1 }));
    }

    /** A group id longer than the blob must not be trusted into an out-of-bounds read. */
    @Test
    public void epochIsRefusedOnAnOverlongGroupId() {
        assertEquals(-1, MlsWireScan.epochOf(new byte[] { 0, 1, 0, 1, 63, 1, 2 }));
    }

    // ================================ ordering helpers ================================

    @Test
    public void momentComparisonIsUnsignedAndEraMajor() {
        assertTrue(MlsPendingQueue.strictlyAfter(m(2, 0), m(1, Long.MAX_VALUE)));
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, Long.MAX_VALUE), m(2, 0)));
        assertTrue(MlsPendingQueue.strictlyAfter(m(1, 6), m(1, 5)));
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, 5), m(1, 5)));
        assertTrue(MlsPendingQueue.atOrAfterRecoveredAt(m(1, 5), m(1, 5)));
    }

    /**
     * A null moment is never after anything — "cannot prove" defaults to the error path.
     *
     * <p><b>Except on G3's comparand</b>, which is the one place that reading is wrong; see
     * {@link #g3_anUnsetRecoveredAtAdmits}.
     */
    @Test
    public void aNullMomentIsNeverAfterAnything() {
        assertFalse(MlsPendingQueue.strictlyAfter(null, m(1, 1)));
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, 1), null));
        assertFalse(MlsPendingQueue.atOrAfterRecoveredAt(null, null));
        assertFalse("a message with no moment still refuses — Google Messages tests an Ok-sentinel "
                + "and branches away from the compare",
                MlsPendingQueue.atOrAfterRecoveredAt(null, m(1, 1)));
    }

    // ============ CONFLICT 10 — the silent-drop case that would have emitted an FTD ============

    /**
     * {@code ExpectedSelfHealToBeOngoing(52)} + {@code !same_application_message_failing} produces
     * no FTD, no receipt and no queue entry.
     *
     * <p>The predicate has no producer in our stack — error 52 is a Google MLS engine error and our engine is
     * mls-rs — so this is the rule implemented and pinned, not a path exercised. Stated that way
     * deliberately: "implemented but unreachable" and "not implemented" are different, and so are
     * "unreachable" and "not provoked".
     */
    @Test
    public void theSilentDropIsExactlyErrorFiftyTwoWithoutTheSameMessageFailing() {
        assertTrue(MlsPendingQueue.silentDrop(52, /*sameApplicationMessageFailing=*/ false));
        assertFalse("with the same message failing it is NOT silent",
                MlsPendingQueue.silentDrop(52, true));
        assertFalse("any other error is not the silent drop",
                MlsPendingQueue.silentDrop(29, false));
        assertFalse(MlsPendingQueue.silentDrop(0, false));
        assertEquals(52, MlsPendingQueue.ERROR_EXPECTED_SELF_HEAL_TO_BE_ONGOING);
    }

    // ================================ clear ================================

    @Test
    public void clearIsTheOnlyPurge() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("a", 1, 1), GID);
        q.store(e("b", 5, 9), GID);
        q.clear();
        assertTrue(q.isEmpty());
        assertTrue(q.eras().isEmpty());
    }

    // ======================= is the awaited moment reachable? =======================

    /**
     * An EPOCH gap is reachable and an ERA gap is not.
     *
     * <p>This is the distinction that separates "park quietly, we will catch up" from "park AND
     * report, because we never will". Getting it wrong in either direction is a real failure: too
     * eager and every ordinary out-of-order message provokes a pointless resend; too shy and the
     * conversation stalls forever with both sides believing they are fine.
     */
    @Test
    public void awaitedMoment_epochAheadIsReachable_eraAheadIsNot() {
        final Moment group = new Moment(5, 1);
        // Same era, later epoch: we replay the commits we missed. Waiting alone works.
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(5, 9), group));
        // The current moment, and anything behind us, are trivially reachable.
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(5, 1), group));
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(4, 99), group));
        // An era ahead: a new era is a new group, and joining one needs a Welcome we cannot ask for.
        assertFalse(MlsPendingQueue.awaitedMomentIsReachable(new Moment(6, 1), group));
        // Era ahead at a LOWER epoch too — a new era restarts the epoch, so an epoch-only comparison
        // would read this as "behind us" and wait forever. This is the case that actually bit us:
        // Google Messages was at (era=6 epoch=1) while we sat at (era=5 epoch=1).
        assertFalse(MlsPendingQueue.awaitedMomentIsReachable(new Moment(6, 0), group));
    }

    /** A missing moment must never be turned into a report. */
    @Test
    public void awaitedMoment_unknownMomentsGetNoOpinion() {
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(null, new Moment(5, 1)));
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(6, 1), null));
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(null, null));
    }

    /**
     * THE OTHER SIDE OF THE SAME BOUNDARY.
     *
     * <p>{@code awaitedMomentIsReachable} names the messages ahead of us we cannot reach.
     * {@code isFromASupersededEra} names the ones behind us that can never be read: an era advance
     * builds a new MLS group under the same group id, clearing the prior era's epoch secrets on the
     * advancer ({@code delete_group}) and on every member that joins the new era by Welcome
     * ({@code purge_prior_epochs_on_join}). Retaining them would not help — the epoch id restarts,
     * so {@code (group_id, epoch_id)} would address two different secrets across the boundary.
     *
     * <p>The epoch cases are the ones that bite: a superseded era at a HIGHER epoch is still
     * superseded, and the SAME era at a lower epoch is not — an epoch-only comparison inverts both.
     */
    @Test
    public void supersededEra_isTheEraAxisOnly_andSurvivesAnEpochInversion() {
        final Moment group = new Moment(5, 1);
        assertTrue("an era behind is superseded",
                MlsPendingQueue.isFromASupersededEra(new Moment(4, 1), group));
        assertTrue("still superseded at a much HIGHER epoch — the old era ran further than we have",
                MlsPendingQueue.isFromASupersededEra(new Moment(4, 99), group));
        assertFalse("our own era is not superseded, however far behind the epoch is",
                MlsPendingQueue.isFromASupersededEra(new Moment(5, 0), group));
        assertFalse("the current moment is not superseded",
                MlsPendingQueue.isFromASupersededEra(new Moment(5, 1), group));
        assertFalse("an era AHEAD is unreadable for the opposite reason — that is the other predicate",
                MlsPendingQueue.isFromASupersededEra(new Moment(6, 0), group));
        // The two predicates must not both claim the same message: nothing is at once behind a
        // boundary we cannot cross back over and ahead of one we cannot cross forward.
        assertFalse(MlsPendingQueue.isFromASupersededEra(new Moment(4, 1), group)
                && !MlsPendingQueue.awaitedMomentIsReachable(new Moment(4, 1), group));
    }

    /** A missing moment gets no opinion here either — and the default must be the SILENT one. */
    @Test
    public void supersededEra_unknownMomentsGetNoOpinion() {
        assertFalse(MlsPendingQueue.isFromASupersededEra(null, new Moment(5, 1)));
        assertFalse(MlsPendingQueue.isFromASupersededEra(new Moment(4, 1), null));
        assertFalse(MlsPendingQueue.isFromASupersededEra(null, null));
    }

    /**
     * The era is a u32 and is compared UNSIGNED, matching {@code Moment.compareTo}.
     *
     * <p>Unreachable on our fleet — Google Messages panics at {@code u32::MAX} rather than wrapping, and
     * eras increment by one — but a signed comparison here would report an era past 2^31 as
     * superseded by every ordinary era, which is the inversion that turns "cannot read this" into
     * "cannot read anything".
     */
    @Test
    public void supersededEra_comparesTheEraUnsigned() {
        final Moment huge = new Moment(0x8000_0000, 1);      // 2^31 as a u32, negative as an int
        assertFalse("2^31 is AHEAD of era 5, not behind it",
                MlsPendingQueue.isFromASupersededEra(huge, new Moment(5, 1)));
        assertTrue("...and era 5 really is behind it",
                MlsPendingQueue.isFromASupersededEra(new Moment(5, 1), huge));
    }

    /**
     * PHOENIX MAY SUPERSEDE A HELD END_MLS, and nothing else escalates.
     *
     * <p>Device-found 2026-08-06 (group {@code 38244b05}): an {@code end_mls} commit that failed
     * {@code InvalidEpoch} kept the single pending slot, and Phoenix — the operation that exists
     * specifically to rescue a failed {@code end_mls} — was refused by it and logged as an ordinary
     * "already pending". The conversation sat in {@code PhoenixModeRequested} with a dead
     * {@code END_MLS} still held. That refusal reads like correctness, which is why it needs a test
     * rather than a comment.
     */
    @Test
    public void phoenixSupersedesAHeldEndMls_andNothingElseEscalates() {
        assertTrue("phoenix rescues a failed end_mls; waiting out its budget blocks the rescue "
                + "with the wreck",
                MlsPendingOperation.supersedesAsEscalation(
                        MlsPendingOperation.Kind.END_MLS, MlsPendingOperation.Kind.PHOENIX_MODE));

        // The reverse must NOT hold: an end_mls must not barge past a running Phoenix, which is
        // already further along the same ladder.
        assertFalse(MlsPendingOperation.supersedesAsEscalation(
                MlsPendingOperation.Kind.PHOENIX_MODE, MlsPendingOperation.Kind.END_MLS));

        // And it is ONE directed pair, not a general "urgent wins" rule — a general rule here would
        // re-open the races the single slot exists to prevent.
        for (final MlsPendingOperation.Kind held : MlsPendingOperation.Kind.values()) {
            for (final MlsPendingOperation.Kind claiming : MlsPendingOperation.Kind.values()) {
                final boolean expected = held == MlsPendingOperation.Kind.END_MLS
                        && claiming == MlsPendingOperation.Kind.PHOENIX_MODE;
                assertEquals(held + " -> " + claiming, expected,
                        MlsPendingOperation.supersedesAsEscalation(held, claiming));
            }
        }
    }
}
