/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link MlsRecoveryPolicy}, the MLS recovery decisions. Each rule fires only on a failure that
 * cannot be produced on demand, so these tests are its coverage. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsRecoveryPolicyTest {

    // Send-gate deadline: a gate must not hold messages forever, including one that outlives its
    // group.

    @Test
    public void gate_notExpired_beforeDeadline() {
        // Opened at t=1000, now t=50_000, deadline 90s: 49s elapsed, still waiting.
        assertFalse(MlsRecoveryPolicy.gateExpired(1_000L, 50_000L, 90_000L));
    }

    @Test
    public void gate_expires_afterDeadline() {
        // 91s elapsed against a 90s deadline: release and send anyway.
        assertTrue(MlsRecoveryPolicy.gateExpired(1_000L, 92_000L, 90_000L));
    }

    @Test
    public void gate_exactlyAtDeadline_isNotYetExpired() {
        // Strictly greater-than, so exactly 90s has not expired. openedAt must be > 0: 0 is the "no
        // gate open" sentinel.
        assertFalse(MlsRecoveryPolicy.gateExpired(1_000L, 91_000L, 90_000L));
        assertTrue(MlsRecoveryPolicy.gateExpired(1_000L, 91_001L, 90_000L));
    }

    @Test
    public void gate_noGateOpen_neverExpires() {
        // openedAt <= 0 means no gate; reporting it expired would cause a bogus flush.
        assertFalse(MlsRecoveryPolicy.gateExpired(0L, Long.MAX_VALUE / 2, 90_000L));
        assertFalse(MlsRecoveryPolicy.gateExpired(-1L, Long.MAX_VALUE / 2, 90_000L));
    }

    @Test
    public void gate_deadlineDisabled_neverExpires() {
        // deadline <= 0 disables the feature.
        assertFalse(MlsRecoveryPolicy.gateExpired(1L, Long.MAX_VALUE / 2, 0L));
        assertFalse(MlsRecoveryPolicy.gateExpired(1L, Long.MAX_VALUE / 2, -5L));
    }

    // Transport profile: a server-mediated transport acknowledges convergence; the carrier MSRP
    // path is peer-to-peer and never will, so a gate there would stall silently.

    @Test
    public void sendGate_appliesOnlyWhereConvergenceIsSignalled() {
        // Server-mediated: a control ACK exists, so the gate has something to wait for.
        assertTrue(MlsRecoveryPolicy.sendGateApplies(true));
        // Carrier MSRP: nothing can close a gate, so opening one would stall every send.
        assertFalse(MlsRecoveryPolicy.sendGateApplies(false));
    }

    @Test
    public void externalCommitResync_skippedWhenTheTransportRefusesMemberExternalCommits() {
        // The provider's server refuses a member's ExternalInit; attempting it costs a round trip
        // and runs the resync path that deletes the local group before it knows the commit lands.
        assertFalse(MlsRecoveryPolicy.externalCommitResyncApplies(false, /*member=*/ true));
        // A non-member may always external-commit (RFC 9420 §12.4.3.2).
        assertTrue(MlsRecoveryPolicy.externalCommitResyncApplies(false, /*member=*/ false));
        // A transport that permits member external commits may try it as a member.
        assertTrue(MlsRecoveryPolicy.externalCommitResyncApplies(true, /*member=*/ true));
    }

    // Era tie-break: neither RFC 9420 nor RCC.16 defines one, so we impose one to stop an era war.

    @Test
    public void era_lowerE164_advances_higherYields() {
        final String lower = "+15715550100";
        final String higher = "+15715550107";
        assertTrue("lower E.164 must be the advancer",
                MlsRecoveryPolicy.weAreEraAdvancer(lower, higher));
        assertFalse("higher E.164 must yield",
                MlsRecoveryPolicy.weAreEraAdvancer(higher, lower));
    }

    @Test
    public void era_tieBreak_isAntisymmetric_soExactlyOneSideAdvances() {
        // For any distinct pair exactly one side advances: both would duel, neither would stall.
        final String[] peers = {"+15715550100", "+15715550103", "+15715550104", "+15715550107"};
        for (final String a : peers) {
            for (final String b : peers) {
                if (a.equals(b)) continue;
                final boolean aAdvances = MlsRecoveryPolicy.weAreEraAdvancer(a, b);
                final boolean bAdvances = MlsRecoveryPolicy.weAreEraAdvancer(b, a);
                assertTrue("exactly one of " + a + "/" + b + " must advance",
                        aAdvances ^ bAdvances);
            }
        }
    }

    @Test
    public void era_unknownIdentity_failsOpen_byAdvancing() {
        // A conversation that never recovers is worse than a possible duel, and a duel is
        // self-limiting (first create wins; the loser then sees IN_SYNC).
        assertTrue(MlsRecoveryPolicy.weAreEraAdvancer(null, "+15715550100"));
        assertTrue(MlsRecoveryPolicy.weAreEraAdvancer("", "+15715550100"));
        assertTrue(MlsRecoveryPolicy.weAreEraAdvancer("+15715550100", null));
        assertTrue(MlsRecoveryPolicy.weAreEraAdvancer("+15715550100", ""));
    }

    // Bounded yield: yielding never deadlocks.

    @Test
    public void yield_stillWaiting_beforeBound() {
        assertFalse(MlsRecoveryPolicy.eraYieldExpired(1_000L, 60_000L, 120_000L));
    }

    @Test
    public void yield_expires_soAYieldCannotDeadlock() {
        // The designated advancer never advanced; after the bound we advance anyway.
        assertTrue(MlsRecoveryPolicy.eraYieldExpired(1_000L, 200_000L, 120_000L));
    }

    @Test
    public void yield_notYielding_neverExpires() {
        assertFalse(MlsRecoveryPolicy.eraYieldExpired(0L, Long.MAX_VALUE / 2, 120_000L));
    }

    @Test
    public void yield_zeroBound_meansYieldForever() {
        assertFalse(MlsRecoveryPolicy.eraYieldExpired(1L, Long.MAX_VALUE / 2, 0L));
    }

    // RCC.16 body-counter stamping: the uint32 tracks sender_data.generation, not a content type;
    // a constant makes every message after the first fail to decrypt at the peer.

    private static byte[] framed(final int trailingCounterByte) {
        return new byte[] {0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, (byte) trailingCounterByte,
                0x34, 'h', 'i'};
    }

    @Test
    public void stamp_writesTheGenerationBigEndian() {
        final byte[] out = MlsRecoveryPolicy.stampBodyGeneration(framed(2), 4);
        assertEquals(0x00, out[4]);
        assertEquals(0x00, out[5]);
        assertEquals(0x00, out[6]);
        assertEquals(4, out[7]);
    }

    @Test
    public void stamp_handlesMultiByteGenerations() {
        // A long-lived epoch can exceed a byte; all four bytes matter.
        final byte[] out = MlsRecoveryPolicy.stampBodyGeneration(framed(0), 0x01020304);
        assertEquals(0x01, out[4]);
        assertEquals(0x02, out[5]);
        assertEquals(0x03, out[6]);
        assertEquals(0x04, out[7]);
    }

    @Test
    public void stamp_overwritesTheHardcodedPlaceholder() {
        // A body framed with the constant 2 comes out carrying the real generation.
        final byte[] out = MlsRecoveryPolicy.stampBodyGeneration(framed(2), 7);
        assertEquals(7, out[7]);
    }

    @Test
    public void stamp_doesNotMutateTheInput() {
        final byte[] in = framed(2);
        MlsRecoveryPolicy.stampBodyGeneration(in, 9);
        assertEquals("input must be left untouched", 2, in[7]);
    }

    @Test
    public void stamp_preservesEverythingAfterTheHeader() {
        final byte[] out = MlsRecoveryPolicy.stampBodyGeneration(framed(2), 5);
        assertEquals(0x34, out[8]);
        assertEquals('h', out[9]);
        assertEquals('i', out[10]);
        assertEquals(11, out.length);
    }

    @Test
    public void stamp_leavesNonRcc16BodiesAlone() {
        // A raw payload passes through untouched: never corrupt a body we do not understand.
        final byte[] raw = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99};
        assertEquals(raw, MlsRecoveryPolicy.stampBodyGeneration(raw, 3));
    }

    @Test
    public void stamp_toleratesNullShortAndNegative() {
        assertEquals(null, MlsRecoveryPolicy.stampBodyGeneration(null, 1));
        final byte[] tooShort = {0x00, 0x01, 0x00};
        assertEquals(tooShort, MlsRecoveryPolicy.stampBodyGeneration(tooShort, 1));
        final byte[] body = framed(2);
        assertEquals("negative generation → no stamping", body,
                MlsRecoveryPolicy.stampBodyGeneration(body, -1));
    }

    // Resync-failure action: a resync that fails to build after the local group was deleted must
    // not leave a record pointing at missing state.

    @Test
    public void resyncFailure_withSnapshot_restoresIt() {
        assertEquals(MlsRecoveryPolicy.ResyncFailureAction.RESTORE_SNAPSHOT,
                MlsRecoveryPolicy.onResyncFailure(true));
    }

    @Test
    public void resyncFailure_withoutSnapshot_dropsRecordSoTheNextSendReestablishes() {
        // Never leave a record pointing at deleted group state.
        assertEquals(MlsRecoveryPolicy.ResyncFailureAction.DROP_RECORD_AND_REESTABLISH,
                MlsRecoveryPolicy.onResyncFailure(false));
    }

    @Test
    public void resyncFailure_alwaysLeavesARecoverableState() {
        // Every input yields one of the two recoverable actions; there is no "do nothing" branch.
        for (final boolean haveSnapshot : new boolean[] {true, false}) {
            final MlsRecoveryPolicy.ResyncFailureAction a =
                    MlsRecoveryPolicy.onResyncFailure(haveSnapshot);
            assertTrue(a == MlsRecoveryPolicy.ResyncFailureAction.RESTORE_SNAPSHOT
                    || a == MlsRecoveryPolicy.ResyncFailureAction.DROP_RECORD_AND_REESTABLISH);
        }
    }

    /**
     * Both conditions are required: asking during ordinary backoff trains the alert into noise, and
     * asking while attempts are still owed cuts off a recovery that has not finished.
     */
    @Test
    public void needsUserChoice_requiresBothExhaustedAndUnreachable() {
        // Out of attempts and waiting on something we cannot reach: ask.
        assertTrue(MlsRecoveryPolicy.needsUserChoice(true, false));

        // Out of attempts but reachable by replaying commits: backoff, do not ask.
        assertFalse(MlsRecoveryPolicy.needsUserChoice(true, true));

        // Unreachable, but attempts are still owed: do not ask yet.
        assertFalse(MlsRecoveryPolicy.needsUserChoice(false, false));

        // Healthy on both counts.
        assertFalse(MlsRecoveryPolicy.needsUserChoice(false, true));
    }

    /**
     * The raised arm is terminal for us; a retry status would hand the decision back to the drive
     * loop.
     */
    @Test
    public void userActionRequired_isTerminalForTheMachine() {
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.USER_ACTION_REQUIRED.defaultStatus);
    }

    // The shipped thresholds: 90s, 16 and 3.

    /** The shipped overload is the 90-second deadline. */
    @Test
    public void gate_shippingOverload_isTheDeclaredDeadline() {
        assertEquals(90_000L, MlsRecoveryPolicy.GATE_DEADLINE_MS);
        assertFalse(
                MlsRecoveryPolicy.gateExpired(1_000L, 1_000L + MlsRecoveryPolicy.GATE_DEADLINE_MS));
        assertTrue(MlsRecoveryPolicy.gateExpired(1_000L,
                1_001L + MlsRecoveryPolicy.GATE_DEADLINE_MS));
        // No gate open is not an expired gate.
        assertFalse(MlsRecoveryPolicy.gateExpired(0L, Long.MAX_VALUE / 2));
    }

    /**
     * The parked-resend queue refuses at the cap, not one past it; the production call passes the
     * size before the add.
     */
    @Test
    public void gatedResendQueue_isFullAtTheCap() {
        assertEquals(16, MlsRecoveryPolicy.MAX_GATED_RESENDS);
        assertFalse(
                MlsRecoveryPolicy.gatedResendQueueFull(MlsRecoveryPolicy.MAX_GATED_RESENDS - 1));
        assertTrue(MlsRecoveryPolicy.gatedResendQueueFull(MlsRecoveryPolicy.MAX_GATED_RESENDS));
        assertTrue(MlsRecoveryPolicy.gatedResendQueueFull(MlsRecoveryPolicy.MAX_GATED_RESENDS + 1));
    }

    /** Three consecutive non-convergences rebuild; two do not. */
    @Test
    public void rebuild_afterThreeConsecutiveNonConvergences() {
        assertEquals(3, MlsRecoveryPolicy.UNCONVERGED_BEFORE_REBUILD);
        assertFalse(MlsRecoveryPolicy.rebuildAfterUnconverged(0));
        assertFalse(MlsRecoveryPolicy.rebuildAfterUnconverged(2));
        assertTrue(MlsRecoveryPolicy.rebuildAfterUnconverged(3));
        assertTrue(MlsRecoveryPolicy.rebuildAfterUnconverged(9));
    }

    /**
     * Both refusal lines name the threshold, asserted against the constant so raising a cap cannot
     * leave the log claiming the old one.
     */
    @Test
    public void theRefusalLinesQuoteTheirOwnThresholds() {
        final String gated = MlsRecoveryPolicy.gatedResendRefusalLine("conv:+15551234567", "m-42");
        assertTrue(gated, gated.contains(Integer.toString(MlsRecoveryPolicy.MAX_GATED_RESENDS)));
        assertTrue(gated, gated.contains("conv:***4567") && gated.contains("m-42"));
        assertTrue("the key's number is masked: " + gated, !gated.contains("5551234567"));
        final String reset = MlsRecoveryPolicy.unconvergedRunResetLine();
        assertTrue(reset,
                reset.contains(Integer.toString(MlsRecoveryPolicy.UNCONVERGED_BEFORE_REBUILD)));
    }
}
