/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The out-of-order queue (RCC.16 §10.8): admission by guards G1–G3, the moment ordering, exact-key
 * drain, store validation, persistence and the silent-drop rule, as a pure class so each property
 * is asserted without a device interleaving. See docs/mls/health-and-recovery.md.
 */
public class MlsPendingQueueTest {

    private static final byte[] GID = new byte[] { 1, 2, 3, 4 };
    private static final byte[] OTHER_GID = new byte[] { 9, 9, 9, 9 };

    private static Moment m(final int era, final long epoch) { return new Moment(era, epoch); }

    /** A valid entry: PublicMessage(1), our group, a moment. */
    private static MlsPendingQueue.Entry e(final String id, final int era, final long epoch) {
        return new MlsPendingQueue.Entry(id, m(era, epoch), 1, GID, new byte[] { 7, 7 });
    }

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

    /** A HEALTHY group never takes G1; inbound is processed inline. */
    @Test
    public void g1_neverFiresOnAHealthyGroup() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 5), m(1, 5),
                        false, false, false, false, false));
    }

    /** G1 is checked first: it needs no failure, and it wins over the other two. */
    @Test
    public void g1_isCheckedBeforeTheFailureArms() {
        assertEquals(MlsPendingQueue.Admission.GROUP_LOCKED,
                MlsPendingQueue.admit(MlsHealthStates.ONGOINGERAADVANCEMENT,
                        m(9, 0), m(1, 5), m(1, 5), /*failed=*/ true, true, true, false, false));
    }

    /**
     * Era is the major key: era N+1 epoch 0 is ahead of era N epoch 40. An epoch-only comparison
     * would drop the first message of every new era, the one announcing the advance.
     */
    @Test
    public void g2_eraIsTheMajorKey() {
        final Moment newEraLowEpoch = m(2, 0);
        final Moment oldEraHighEpoch = m(1, 40);
        assertTrue("era 2 epoch 0 is AFTER era 1 epoch 40",
                MlsPendingQueue.strictlyAfter(newEraLowEpoch, oldEraHighEpoch));
        // The epoch-only reading would say the opposite.
        assertTrue(0L < 40L);
        assertEquals(MlsPendingQueue.Admission.FROM_FUTURE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, newEraLowEpoch, oldEraHighEpoch,
                        oldEraHighEpoch,
                        true, true, false, false, false));
    }

    /** G2 is strictly greater; equal is not admitted. */
    @Test
    public void g2_refusesAMessageAtOurOwnMoment() {
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, 5), m(1, 5)));
        assertEquals("a message at our moment is an ERROR, not a deferral",
                MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 5), m(1, 5),
                        true, true, false, false, false));
    }

    /** Behind is never admitted, even when OutOfOrderCommit was reported. */
    @Test
    public void g2_refusesAMessageBehindUsEvenWithOutOfOrderCommit() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 4), m(1, 5), m(1, 5),
                        true, /*outOfOrderCommit=*/ true, false, false, false));
    }

    /** G2 requires the failure: a message from the future that processed is not queued. */
    @Test
    public void g2_requiresProcessingToHaveFailed() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(2, 0), m(1, 5), m(1, 5),
                        /*failed=*/ false, true, true, false, false));
    }

    @Test
    public void g3_admitsAResentFtdAtOrAfterOurMoment() {
        assertEquals(MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 5), m(1, 5),
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /** G3 uses {@code >=}, unlike G2; the asymmetry matches other clients. */
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

    /** Across the whole end-MLS family: G3's exclusion set is {@code is_downgraded}. */
    @Test
    public void g3_isRefusedAcrossTheEndMlsFamily() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (!MlsHealthPredicates.isDowngraded(s)) continue;
            final MlsPendingQueue.Admission a = MlsPendingQueue.admit(s, m(1, 6), m(1, 5), m(1, 5),
                    false, false, false, /*resentFtd=*/ true, false);
            // 12, 16 and 17 are downgraded and buffer, so they take G1 first; G1's mask is not the
            // complement of is_downgraded.
            assertNotEquals("status " + s + " must not reach G3",
                    MlsPendingQueue.Admission.RESENT_FTD, a);
        }
    }

    /**
     * G3 compares against {@code SelfHealState.recovered_at}, not the live group moment G2 uses.
     * Passing the group moment would refuse this resend, so we would report it as undecryptable
     * while other clients queue it.
     */
    @Test
    public void g3_comparesAgainstRecoveredAtNotTheLiveGroupMoment() {
        final Moment group = m(1, 10);          // live: the group has committed on since recovery
        final Moment recoveredAt = m(1, 4);     // where the last recovery landed
        final Moment msg = m(1, 6);             // behind the group, ahead of the recovery landing
        assertEquals("a resend at or after recovered_at is QUEUED, even though it is behind the "
                        + "live group moment",
                MlsPendingQueue.Admission.RESENT_FTD,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, msg, group, recoveredAt,
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /**
     * With {@code msg} after {@code recoveredAt} and before {@code group}, no single shared moment
     * makes one arm admit and the other refuse; only the different sources explain the split.
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
     * An unset {@code recovered_at} admits: it reads as the all-zero default {@code (0,0)}, unlike
     * the "a null moment is never after anything" rule {@link MlsPendingQueue#strictlyAfter}
     * applies to the group moment.
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
     * A resend behind the recovery landing is refused, so the gate is not satisfiable by always
     * admitting.
     */
    @Test
    public void g3_refusesAResendBehindRecoveredAt() {
        assertEquals(MlsPendingQueue.Admission.NONE,
                MlsPendingQueue.admit(MlsHealthStates.HEALTHY, m(1, 5), m(1, 10), m(1, 6),
                        false, false, false, /*resentFtd=*/ true, false));
    }

    /** Era stays the major key on G3's comparand. */
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

    /**
     * No capacity, eviction, TTL or far-future cutoff, matching the behaviour observed in other
     * clients. The queue is fed by the network, so this pins an absence: if a bound is found there,
     * these tests should assert it instead, and a bound of our own would be a defensible
     * divergence.
     */
    @Test
    public void thereIsNoCapacityBound() {
        final MlsPendingQueue q = new MlsPendingQueue();
        for (int i = 0; i < 500; i++) {
            assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("m" + i, 1, i), GID));
        }
        assertEquals("no bound, no silent drop", 500, q.size());
    }

    /** A message claiming era 9 999 is queued like one at era N+1. */
    @Test
    public void thereIsNoFarFutureCutoff() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("far", 9999, 0), GID));
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("near", 2, 0), GID));
        assertEquals(2, q.size());
        assertEquals(1, q.take(9999, 0).size());
    }

    /**
     * Drain takes only the exact moment: a sweep would re-attempt messages parked at N+2 when the
     * group reaches N and N+1, manufacturing failures.
     */
    @Test
    public void takeIsExactKeyAndLeavesOtherMomentsAlone() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("at-5", 1, 5), GID);
        q.store(e("at-6", 1, 6), GID);
        q.store(e("at-7", 1, 7), GID);

        // At epoch 5 only epoch 5 is taken.
        final List<MlsPendingQueue.Entry> got = q.take(1, 5);
        assertEquals(1, got.size());
        assertEquals("at-5", got.get(0).messageId);
        assertEquals("6 and 7 must still be parked", 2, q.size());

        // Epoch 6 arrives; 7 still waits.
        assertEquals(1, q.take(1, 6).size());
        assertEquals(1, q.size());
    }

    /** A take with nothing parked is empty, not an error and not a sweep. */
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

    /** Within a bucket, insertion order. */
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

    @Test
    public void validation1_variantTagMustBeUnderThree() {
        final MlsPendingQueue q = new MlsPendingQueue();
        for (int tag : new int[] { 3, 4, 5, 99 }) {
            assertEquals("wire_format " + tag + " is a Welcome/GroupInfo/KeyPackage, not a message",
                    MlsPendingQueue.StoreResult.INVALID_WIRE_FORMAT,
                    q.store(new MlsPendingQueue.Entry("x", m(1, 1), tag, GID, new byte[] { 1 }),
                            GID));
        }
        // 1 and 2 are the two that are messages to a group.
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
     * The order is not numeric: group-id-matches ({@code 28}) is checked before epoch-present
     * ({@code 27}), so a message for the wrong group with no epoch reports 28.
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

    /** The variant tag is checked before everything, including a wrong group id. */
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
        assertFalse("a duplicate is NOT an error — some peers return Ok",
                MlsPendingQueue.StoreResult.DUPLICATE.isError());
    }

    /** Dedup is by message id within a bucket, and a duplicate is not an error. */
    @Test
    public void dedupIsPerBucketAndIsNotAnError() {
        final MlsPendingQueue q = new MlsPendingQueue();
        assertEquals(MlsPendingQueue.StoreResult.STORED, q.store(e("dup", 1, 5), GID));
        assertEquals(MlsPendingQueue.StoreResult.DUPLICATE, q.store(e("dup", 1, 5), GID));
        assertEquals("the duplicate was not inserted", 1, q.size());
    }

    /** The same id at a different moment is a different delivery attempt and is kept. */
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

    /** The exact-key drain leaves an abandoned era's partition; only a prune removes it. */
    @Test
    public void anAbandonedEraPartitionIsRetained() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("old", 1, 5), GID);
        q.store(e("new", 2, 0), GID);
        q.take(2, 0);                       // the group moves on into era 2 and drains it
        assertEquals("era 1's partition survives", 1, q.size());
        assertTrue(q.eras().contains(1));
    }

    /** The codec round trip is what makes the queue survive process death. */
    @Test
    public void theQueueRoundTripsThroughItsCodec() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("a", 1, 5), GID);
        q.store(e("b", 1, 5), GID);
        q.store(e("c", 2, 0), GID);
        final MlsPendingQueue back = MlsPendingQueue.fromBytes(q.toBytes());
        assertEquals(3, back.size());
        assertEquals(q.debugLayout(), back.debugLayout());
        // The blobs survive, since replaying them is the point.
        final List<MlsPendingQueue.Entry> got = back.take(1, 5);
        assertEquals(2, got.size());
        assertTrue(Arrays.equals(new byte[] { 7, 7 }, got.get(0).blob));
    }

    /**
     * The plane survives the codec: it alone distinguishes a parked application message from a
     * parked commit (both are PrivateMessage), and losing it would decrypt the message and then
     * discard it.
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

    /** The 5-arg constructor is the CONTROL plane, the safe default. */
    @Test
    public void anEntryWithNoStatedPlaneIsControl() {
        assertEquals(MlsPendingQueue.Plane.CONTROL, e("a", 1, 5).plane);
    }

    /**
     * A v1 blob decodes with every entry CONTROL. That is knowingly wrong for a v1-parked
     * application message, and preferred to sniffing a plane from the wire.
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
        // No plane byte: that makes it a v1 record.
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

    /**
     * A malformed blob degrades to empty rather than throwing: losing parked messages costs a
     * resend, refusing to start costs the conversation.
     */
    @Test
    public void aMalformedBlobDegradesToEmpty() {
        assertTrue(MlsPendingQueue.fromBytes(new byte[] { 1, 2, 3 }).isEmpty());
        assertTrue(MlsPendingQueue.fromBytes(new byte[] { -1, -1, -1, -1, 5, 5, 5, 5 }).isEmpty());
    }

    @Test
    public void wireFormatIsReadFromBytesTwoAndThree() {
        assertEquals(1, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 1, 9 }));
        assertEquals(2, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 2, 9 }));
        assertEquals(3, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 3, 9 }));
        assertEquals(4, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0, 4, 9 }));
    }

    /** A blob whose version is not {@code 00 01} is not an MLSMessage. */
    @Test
    public void wireFormatRefusesANonMlsMessage() {
        assertEquals(-1, MlsWireScan.wireFormatOf(new byte[] { 9, 9, 0, 1 }));
        assertEquals(-1, MlsWireScan.wireFormatOf(new byte[] { 0, 1, 0 }));
        assertEquals(-1, MlsWireScan.wireFormatOf(null));
    }

    /**
     * The epoch follows a variable-length group-id vector; a one-byte length read would take the
     * epoch from the middle of the group id, a plausible number rather than an error.
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

    /** A group id longer than the blob is not trusted into an out-of-bounds read. */
    @Test
    public void epochIsRefusedOnAnOverlongGroupId() {
        assertEquals(-1, MlsWireScan.epochOf(new byte[] { 0, 1, 0, 1, 63, 1, 2 }));
    }

    @Test
    public void momentComparisonIsUnsignedAndEraMajor() {
        assertTrue(MlsPendingQueue.strictlyAfter(m(2, 0), m(1, Long.MAX_VALUE)));
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, Long.MAX_VALUE), m(2, 0)));
        assertTrue(MlsPendingQueue.strictlyAfter(m(1, 6), m(1, 5)));
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, 5), m(1, 5)));
        assertTrue(MlsPendingQueue.atOrAfterRecoveredAt(m(1, 5), m(1, 5)));
    }

    /**
     * A null moment is never after anything, except on G3's comparand; see
     * {@link #g3_anUnsetRecoveredAtAdmits}.
     */
    @Test
    public void aNullMomentIsNeverAfterAnything() {
        assertFalse(MlsPendingQueue.strictlyAfter(null, m(1, 1)));
        assertFalse(MlsPendingQueue.strictlyAfter(m(1, 1), null));
        assertFalse(MlsPendingQueue.atOrAfterRecoveredAt(null, null));
        assertFalse("a message with no moment still refuses — some peers test an Ok-sentinel "
                + "and branches away from the compare",
                MlsPendingQueue.atOrAfterRecoveredAt(null, m(1, 1)));
    }

    /**
     * {@code ExpectedSelfHealToBeOngoing(52)} without {@code same_application_message_failing}
     * produces no FTD, no receipt and no queue entry. Our engine has no producer for error 52, so
     * this pins the rule rather than an exercised path.
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

    /**
     * The prune takes every era before the current one, compared unsigned, and nothing at or after
     * it.
     */
    @Test
    public void takeSupersededErasTakesOnlyEarlierEras() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("e1a", 1, 9), GID);
        q.store(e("e1b", 1, 2), GID);
        q.store(e("e2", 2, 0), GID);
        q.store(e("e3", 3, 1), GID);
        final List<MlsPendingQueue.Entry> taken = q.takeSupersededEras(2);
        assertEquals(2, taken.size());
        assertEquals("e1b", taken.get(0).messageId);
        assertEquals("e1a", taken.get(1).messageId);
        assertEquals(new java.util.TreeSet<>(Arrays.asList(2, 3)), q.eras());
        assertTrue(q.takeSupersededEras(2).isEmpty());
        final MlsPendingQueue big = new MlsPendingQueue();
        big.store(e("huge", 0x8000_0000, 1), GID);
        assertTrue("2^31 is ahead of era 5 as a u32", big.takeSupersededEras(5).isEmpty());
        assertEquals(1, big.takeSupersededEras(0x8000_0001).size());
    }

    /** The sender survives the codec; it is who a dropped application message is reported to. */
    @Test
    public void theSenderSurvivesTheCodec() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(new MlsPendingQueue.Entry("s", m(1, 5), 2, GID, new byte[] { 7 },
                MlsPendingQueue.Plane.APPLICATION, "+3"), GID);
        q.store(new MlsPendingQueue.Entry("u", m(1, 5), 2, GID, new byte[] { 7 },
                MlsPendingQueue.Plane.APPLICATION), GID);
        final List<MlsPendingQueue.Entry> got = MlsPendingQueue.fromBytes(q.toBytes()).take(1, 5);
        assertEquals("+3", got.get(0).sender);
        assertNull("unknown stays unknown, not an empty string", got.get(1).sender);
    }

    /** A v2 blob, written before the sender was recorded, decodes with none. */
    @Test
    public void aV2BlobDecodesWithNoSender() throws Exception {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        d.writeInt(2);
        d.writeInt(1);
        d.writeUTF("v2");
        d.writeInt(1);
        d.writeLong(5L);
        d.writeInt(2);
        d.writeInt(GID.length); d.write(GID);
        d.writeInt(1); d.write(7);
        d.writeInt(MlsPendingQueue.Plane.APPLICATION.ordinal());
        final List<MlsPendingQueue.Entry> got =
                MlsPendingQueue.fromBytes(out.toByteArray()).take(1, 5);
        assertEquals(1, got.size());
        assertEquals(MlsPendingQueue.Plane.APPLICATION, got.get(0).plane);
        assertNull(got.get(0).sender);
    }

    @Test
    public void clearDropsEveryEra() {
        final MlsPendingQueue q = new MlsPendingQueue();
        q.store(e("a", 1, 1), GID);
        q.store(e("b", 5, 9), GID);
        q.clear();
        assertTrue(q.isEmpty());
        assertTrue(q.eras().isEmpty());
    }

    /**
     * An epoch gap is reachable and an era gap is not: "park quietly, we will catch up" versus
     * "park and report, because we never will".
     */
    @Test
    public void awaitedMoment_epochAheadIsReachable_eraAheadIsNot() {
        final Moment group = new Moment(5, 1);
        // Same era, later epoch: we replay the commits we missed.
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(5, 9), group));
        // The current moment, and anything behind us, are reachable.
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(5, 1), group));
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(4, 99), group));
        // An era ahead is a new group, which needs a Welcome we cannot ask for.
        assertFalse(MlsPendingQueue.awaitedMomentIsReachable(new Moment(6, 1), group));
        // Era ahead at a lower epoch: a new era restarts the epoch, so an epoch-only comparison
        // would read it as behind and wait forever.
        assertFalse(MlsPendingQueue.awaitedMomentIsReachable(new Moment(6, 0), group));
    }

    /** A missing moment is never turned into a report. */
    @Test
    public void awaitedMoment_unknownMomentsGetNoOpinion() {
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(null, new Moment(5, 1)));
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(new Moment(6, 1), null));
        assertTrue(MlsPendingQueue.awaitedMomentIsReachable(null, null));
    }

    /**
     * {@code isFromASupersededEra} names messages behind us that can never be read: an era advance
     * clears the prior era's epoch secrets on the advancer and on every member joining by Welcome,
     * and the epoch id restarts. A superseded era at a higher epoch is still superseded; the same
     * era at a lower epoch is not.
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
        assertFalse(
                "an era AHEAD is unreadable for the opposite reason — that is the other predicate",
                MlsPendingQueue.isFromASupersededEra(new Moment(6, 0), group));
        // The two predicates never both claim the same message.
        assertFalse(MlsPendingQueue.isFromASupersededEra(new Moment(4, 1), group)
                && !MlsPendingQueue.awaitedMomentIsReachable(new Moment(4, 1), group));
    }

    /** A missing moment gets no opinion here either, and the default is the silent one. */
    @Test
    public void supersededEra_unknownMomentsGetNoOpinion() {
        assertFalse(MlsPendingQueue.isFromASupersededEra(null, new Moment(5, 1)));
        assertFalse(MlsPendingQueue.isFromASupersededEra(new Moment(4, 1), null));
        assertFalse(MlsPendingQueue.isFromASupersededEra(null, null));
    }

    /**
     * The era is a u32 compared unsigned, as {@code Moment.compareTo} does; a signed comparison
     * would report an era past 2^31 as superseded by every ordinary era.
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
     * Phoenix may supersede a held {@code END_MLS}, and nothing else escalates: Phoenix exists to
     * rescue a failed end_mls, and refusing it as "already pending" would leave the conversation in
     * {@code PhoenixModeRequested} behind a dead {@code END_MLS}.
     */
    @Test
    public void phoenixSupersedesAHeldEndMls_andNothingElseEscalates() {
        assertTrue("phoenix rescues a failed end_mls; waiting out its budget blocks the rescue "
                + "with the wreck",
                MlsPendingOperation.supersedesAsEscalation(
                        MlsPendingOperation.Kind.END_MLS, MlsPendingOperation.Kind.PHOENIX_MODE));

        // The reverse does not hold: an end_mls does not barge past a running Phoenix.
        assertFalse(MlsPendingOperation.supersedesAsEscalation(
                MlsPendingOperation.Kind.PHOENIX_MODE, MlsPendingOperation.Kind.END_MLS));

        // One directed pair, not a general "urgent wins" rule, which would re-open the races the
        // single slot prevents.
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
