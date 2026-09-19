/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * The 21-value downgrade-reason table, its two constructor invariants, and the reason gate. The
 * table is written out a second time here rather than read back from the enum, because each column
 * drives a behaviour and a wrong boolean is a silently wrong downgrade. See docs/mls/downgrade.md.
 */
public class MlsDowngradeReasonTest {

    /** One row of the reason table, transcribed independently of the enum. */
    private static void row(final MlsDowngradeReason r, final int a, final Integer v,
            final boolean w, final boolean x, final boolean y, final boolean z, final int b) {
        assertEquals(r + ".A (wire)", a, r.wire);
        assertEquals(r + ".v (metric)", v, r.metric);
        assertEquals(r + ".w (expected)", w, r.expected);
        assertEquals(r + ".x (postprocessResult)", x, r.postprocessResult);
        assertEquals(r + ".y (zinniaAlreadyEnded)", y, r.zinniaAlreadyEnded);
        assertEquals(r + ".z (suppressEagerLocalDowngrade)", z, r.suppressEagerLocalDowngrade);
        assertEquals(r + ".B (zinniaCode)", b, r.zinniaCode);
    }

    @Test
    public void everyRowMatchesTheDesignDocTable() {
        //                                                     A    v      w      x      y      z
        //                                                     B
        row(MlsDowngradeReason.SELF_NOT_MLS_CAPABLE,           2,    0,  true,  true, false, false,
                11);
        row(MlsDowngradeReason.CLIENT_LOST_MLS,                3,    1,  true,  true, false, false,
                5);
        row(MlsDowngradeReason.NON_MLS_CLIENT_ADDED,           4,    2,  true,  true, false, false,
                15);
        row(MlsDowngradeReason.ERA_ADVANCEMENT_QUOTA_REACHED,  5,    3, false,  true, false, false,
                0);
        row(MlsDowngradeReason.UNRECOVERABLE_SERVER_FAILURE,   6,    4, false,  true, false, false,
                3);
        row(MlsDowngradeReason.PLAINTEXT_DELIVERY_RECEIPT,     7,    5, false,  true, false, false,
                4);
        row(MlsDowngradeReason.RECEIVED_END_MLS_COMMIT,        8,    6, false, false,  true,  true,
                0);
        row(MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON,       1, null, false,  true, false, false,
                0);
        row(MlsDowngradeReason.DEBUG_MENU,                     9,    7, false,  true, false, false,
                17);
        row(MlsDowngradeReason.FAILED_TO_GET_KEYPACKAGES_FOR_EXPIRED_MEMBERS,
                                                              10,    8,  true,  true, false, false,
                                                              10);
        row(MlsDowngradeReason.ZINNIA_END_MLS_ONGOING,        11,    9, false,  true, false,  true,
                0);
        row(MlsDowngradeReason.SERVER_GROUP_STATE_NOT_FOUND,  12,   10,  true, false, false,  true,
                0);
        row(MlsDowngradeReason.COULD_NOT_ENCRYPT_MESSAGE,     13,   11, false,  true, false, false,
                11);
        row(MlsDowngradeReason.TACHYGRAM_REQUESTED_DOWNGRADE, 14,   12, false,  true, false,  true,
                0);
        row(MlsDowngradeReason.RCS_GROUP_HAS_MEMBER_NOT_SYNCED_WITH_MLS,
                                                              15,   13,  true,  true, false, false,
                                                              0);
        row(MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE,
                                                              16,   14,  true, false,  true, false,
                                                              0);
        row(MlsDowngradeReason.SELF_CERTIFICATE_EXPIRED,      17,   15,  true,  true, false, false,
                14);
        row(MlsDowngradeReason.CANNOT_HEAL_DURING_END_MLS,    18,   16, false, false,  true, false,
                0);
        row(MlsDowngradeReason.ZINNIA_REQUESTED_END_MLS,      19,   17,  true, false, false, false,
                0);
        row(MlsDowngradeReason.MLS_NOT_ADVERTISED,            20,   18,  true,  true, false, false,
                14);
        row(MlsDowngradeReason.ZINNIA_DELETING_GROUP,         21,   19,  true, false, false, false,
                0);
    }

    @Test
    public void thereAreExactlyTwentyOneReasons() {
        assertEquals(21, MlsDowngradeReason.values().length);
    }

    /** The wire numbers are a bijection, so the trace record says which downgrade happened. */
    @Test
    public void wireNumbersAreUnique() {
        final Set<Integer> seen = new HashSet<>();
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            assertTrue("duplicate wire number " + r.wire + " at " + r, seen.add(r.wire));
        }
        assertEquals(21, seen.size());
    }

    /** The same for the telemetry buckets, over the twenty that have one. */
    @Test
    public void metricBucketsAreUniqueAndOnlyOneIsNull() {
        final Set<Integer> seen = new HashSet<>();
        int nulls = 0;
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            if (r.metric == null) {
                nulls++;
                assertFalse(r + " has no metric, so emitsMetric() must be false", r.emitsMetric());
                continue;
            }
            assertTrue("duplicate metric bucket " + r.metric + " at " + r, seen.add(r.metric));
        }
        assertEquals("exactly UNKNOWN_DOWNGRADE_REASON has v == null", 1, nulls);
        assertNull(MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON.metric);
        assertEquals(20, seen.size());
    }

    /**
     * Numbers, not ordinals: the table's numbering does not follow declaration order, so an
     * ordinal-based transcription would match for the first few values and then diverge.
     */
    @Test
    public void wireNumbersAreNotOrdinals() {
        int coincidences = 0;
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            if (r.wire == r.ordinal()) coincidences++;
        }
        assertTrue("if every wire == ordinal, someone has re-derived the table from declaration "
                + "order — §9.7a's numbering is deliberately offset", coincidences < 21);
        // UNKNOWN_DOWNGRADE_REASON is declared 8th and numbered 1.
        assertEquals(7, MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON.ordinal());
        assertEquals(1, MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON.wire);
    }

    /**
     * {@code y && x} is forbidden: a reason cannot both skip generating the commit and postprocess
     * its result. The static block enforces it at class init; this checks the shipped table.
     */
    @Test
    public void noReasonBothSkipsTheCommitAndPostprocessesItsResult() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            assertFalse(r + " violates 'y && x is forbidden'",
                    r.zinniaAlreadyEnded && r.postprocessResult);
        }
    }

    /** {@code B != 0 ⇒ !y && !z}. */
    @Test
    public void aReasonCarryingAnEngineCodeNeitherSkipsNorSuppresses() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            if (r.zinniaCode == 0) continue;
            assertFalse(r + " has B != 0 but y == true", r.zinniaAlreadyEnded);
            assertFalse(r + " has B != 0 but z == true", r.suppressEagerLocalDowngrade);
        }
    }

    @Test
    public void theEngineFunnelWhitelistIsExactlyFive() {
        assertEquals(5, MlsDowngradeReason.ENGINE_FUNNEL_WHITELIST.size());
        assertTrue(MlsDowngradeReason.ENGINE_FUNNEL_WHITELIST.containsAll(java.util.Arrays.asList(
                MlsDowngradeReason.SERVER_GROUP_STATE_NOT_FOUND,
                MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE,
                MlsDowngradeReason.CANNOT_HEAL_DURING_END_MLS,
                MlsDowngradeReason.ZINNIA_END_MLS_ONGOING,
                MlsDowngradeReason.ZINNIA_REQUESTED_END_MLS)));
    }

    /**
     * All five whitelisted engine-driven reasons have {@code B == 0}, one of the checks that pin
     * the polarity of {@link MlsDowngradeReason#zinniaReasonFor}.
     */
    @Test
    public void everyWhitelistedReasonHasNoEngineCode() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.ENGINE_FUNNEL_WHITELIST) {
            assertEquals(r + " is engine-driven, so B must be 0", 0, r.zinniaCode);
        }
    }

    @Test
    public void theWhitelistThrowsRatherThanDegrading() {
        // A whitelisted one passes.
        MlsDowngradeReason.requireEngineFunnelReason(MlsDowngradeReason.CANNOT_HEAL_DURING_END_MLS);
        // Anything else is a routing defect in the caller and throws.
        try {
            MlsDowngradeReason.requireEngineFunnelReason(MlsDowngradeReason.DEBUG_MENU);
            fail("expected the whitelist to throw");
        } catch (final IllegalStateException e) {
            assertEquals("Unexpected failure reason: DEBUG_MENU", e.getMessage());
        }
    }

    @Test
    public void theFourEngineStatusesMapToTheirArms() {
        assertEquals(MlsDowngradeReason.ZINNIA_REQUESTED_END_MLS,
                MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.ENDMLSREQUESTED));
        assertEquals(MlsDowngradeReason.ZINNIA_END_MLS_ONGOING,
                MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.DONEENDMLS));
        assertEquals(MlsDowngradeReason.CANNOT_HEAL_DURING_END_MLS,
                MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.CANNOTHEALDURINGENDMLS));
        assertEquals(MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE,
                MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.PHOENIXMODEREQUESTED));
    }

    /**
     * {@code ZINNIA_REQUESTED_END_MLS} answers {@code EndMlsRequested(8)}, not
     * {@code DoneEndMls(10)}; swapping them records "the engine asked to end MLS" for a group that
     * has already ended it.
     */
    @Test
    public void theRequestedAndDoneArmsAreNotInverted() {
        assertNotEquals(MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.ENDMLSREQUESTED),
                MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.DONEENDMLS));
        assertEquals("ZINNIA_REQUESTED_END_MLS belongs to EndMlsRequested(8)",
                MlsDowngradeReason.ZINNIA_REQUESTED_END_MLS,
                MlsDowngradeReason.forEngineHealthStatus(8));
        assertEquals("ZINNIA_END_MLS_ONGOING belongs to DoneEndMls(10)",
                MlsDowngradeReason.ZINNIA_END_MLS_ONGOING,
                MlsDowngradeReason.forEngineHealthStatus(10));
    }

    /**
     * Everything the funnel returns is whitelisted by construction, so the caller can assert it.
     * Checked over every slot.
     */
    @Test
    public void everyStatusEitherMapsToAWhitelistedReasonOrToNothing() {
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            final MlsDowngradeReason r = MlsDowngradeReason.forEngineHealthStatus(s);
            if (r == null) continue;
            assertTrue("status " + s + " maps to " + r + ", which is NOT whitelisted — the funnel "
                    + "would throw at runtime (invariant 94)",
                    MlsDowngradeReason.ENGINE_FUNNEL_WHITELIST.contains(r));
            MlsDowngradeReason.requireEngineFunnelReason(r);   // must not throw
        }
    }

    /** Most statuses map to nothing: recovery in progress is not a reason to leave MLS. */
    @Test
    public void recoveryStatusesProduceNoDowngrade() {
        assertNull(MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.HEALTHY));
        assertNull(MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.INITIALIZING));
        assertNull(MlsDowngradeReason.forEngineHealthStatus(
                MlsHealthStates.EPOCHADVANCEMENTREQUESTED));
        assertNull(MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.ONGOINGERAADVANCEMENT));
        assertNull(MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.SELFHEALFAILED));
        // The in-flight downgrade states produce nothing: OngoingEndMls is our own commit in
        // flight, and the host already downgraded on the way in.
        assertNull(MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.ONGOINGENDMLS));
        // Phoenix ongoing maps to nothing: the local downgrade happened at request time, so another
        // would double-count.
        assertNull("OngoingPhoenixMode must NOT re-downgrade — invariant 95 already did it",
                MlsDowngradeReason.forEngineHealthStatus(MlsHealthStates.ONGOINGPHOENIXMODE));
    }

    /**
     * Exactly four statuses map; a fifth widens the funnel, leaving only the whitelist between it
     * and a silent downgrade.
     */
    @Test
    public void exactlyFourStatusesMap() {
        int mapped = 0;
        for (int s = 0; s < MlsHealthStates.STATE_SLOTS; s++) {
            if (MlsDowngradeReason.forEngineHealthStatus(s) != null) mapped++;
        }
        assertEquals(4, mapped);
    }

    /**
     * The reason is shipped when the context has not round-tripped an engine health status: the
     * engine is not told what it just told us.
     */
    @Test
    public void zinniaReasonIsSentOnlyWhenTheContextCarriesNoEngineStatus() {
        final MlsDowngradeReason r = MlsDowngradeReason.SELF_NOT_MLS_CAPABLE;   // B == 11
        assertEquals(11, r.zinniaReasonFor(/*contextCarriesEngineHealthStatus=*/ false));
        assertEquals(0, r.zinniaReasonFor(/*contextCarriesEngineHealthStatus=*/ true));
    }

    @Test
    public void aReasonWithNoCodeSendsNothingEitherWay() {
        final MlsDowngradeReason r = MlsDowngradeReason.ZINNIA_END_MLS_ONGOING;   // B == 0
        assertEquals(0, r.zinniaReasonFor(false));
        assertEquals(0, r.zinniaReasonFor(true));
    }

    /**
     * Under the opposite polarity the field would be permanently dead for the engine-driven set.
     */
    @Test
    public void underTheOppositePolarityTheFieldWouldBeDead() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.ENGINE_FUNNEL_WHITELIST) {
            // These arrive with the hasbit set; with B == 0 they send nothing. Only the
            // app-originated B != 0 set can send one.
            assertEquals(0, r.zinniaReasonFor(true));
            assertEquals(0, r.zinniaReasonFor(false));
        }
        boolean anyAppOriginatedCanSend = false;
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            if (r.zinniaReasonFor(false) != 0) anyAppOriginatedCanSend = true;
        }
        assertTrue("some reason must be able to carry a code, or the gate is pointless",
                anyAppOriginatedCanSend);
    }

    // EndMlsRequest.reason = B - 2.

    @Test
    public void theWireReasonIsTheCodeMinusTwo() {
        // Observed B ∈ {3,4,5,10,11,14,15,17} ⇒ wire {1,2,3,8,9,12,13,15}.
        assertEquals(1, MlsDowngradeReason.endMlsRequestWireReason(3));
        assertEquals(2, MlsDowngradeReason.endMlsRequestWireReason(4));
        assertEquals(3, MlsDowngradeReason.endMlsRequestWireReason(5));
        assertEquals(8, MlsDowngradeReason.endMlsRequestWireReason(10));
        assertEquals(9, MlsDowngradeReason.endMlsRequestWireReason(11));
        assertEquals(12, MlsDowngradeReason.endMlsRequestWireReason(14));
        assertEquals(13, MlsDowngradeReason.endMlsRequestWireReason(15));
        assertEquals(15, MlsDowngradeReason.endMlsRequestWireReason(17));
    }

    /**
     * Every shipped code decodes to a non-negative wire value, i.e. none is the dead {@code 1}.
     */
    @Test
    public void everyShippedCodeDecodesCleanly() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            if (r.zinniaCode == 0) continue;
            final int wire = r.endMlsRequestWireReason();
            assertTrue(r + " decodes to a negative wire reason", wire >= 0);
        }
    }

    /**
     * {@code B == 1} is {@code UNRECOGNIZED}, which a peer's protobuf-lite {@code getNumber()}
     * throws on; nothing ships with it, and introducing one fails loudly rather than emitting
     * reason -1.
     */
    @Test
    public void codeOneThrowsRatherThanEmittingMinusOne() {
        try {
            MlsDowngradeReason.endMlsRequestWireReason(1);
            fail("expected the UNRECOGNIZED ordinal to throw");
        } catch (final IllegalArgumentException e) {
            assertEquals("Can't get the number of an unknown enum value.", e.getMessage());
        }
    }

    /** 0 is not a reason; callers gate on {@code zinniaReasonFor} first. */
    @Test
    public void codeZeroIsNotAWireReason() {
        try {
            MlsDowngradeReason.endMlsRequestWireReason(0);
            fail("expected code 0 to be rejected");
        } catch (final IllegalStateException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void fromWireRoundTripsAndDefaultsToUnknown() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            assertEquals(r, MlsDowngradeReason.fromWire(r.wire));
        }
        assertEquals(MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON, MlsDowngradeReason.fromWire(999));
        // 0 is not a wire number in this vocabulary (they run 1..21).
        assertEquals(MlsDowngradeReason.UNKNOWN_DOWNGRADE_REASON, MlsDowngradeReason.fromWire(0));
    }

    /**
     * The column that drives {@link MlsReupgradeState}: {@code RECEIVED_END_MLS_COMMIT} is
     * unexpected ({@code w == false}), so a peer's downgrade is eligible for automatic re-upgrade,
     * while Phoenix's own is expected and is not.
     */
    @Test
    public void theExpectedColumnSeparatesPeerDowngradesFromOurOwn() {
        assertFalse("a peer ended MLS on us — we want to come back",
                MlsDowngradeReason.RECEIVED_END_MLS_COMMIT.expected);
        assertTrue("we chose Phoenix — do not treat it as an unexpected downgrade",
                MlsDowngradeReason.EAGERLY_DOWNGRADE_LOCALLY_DURING_PHOENIX_MODE.expected);
    }


    @Test
    public void eagerForIsTheReasonsOwnColumnAndNullIsNeverEager() {
        for (final MlsDowngradeReason r : MlsDowngradeReason.values()) {
            assertEquals(r.name(), !r.suppressEagerLocalDowngrade, MlsDowngradeReason.eagerFor(r));
        }
        assertFalse(MlsDowngradeReason.eagerFor(null));
    }
}
