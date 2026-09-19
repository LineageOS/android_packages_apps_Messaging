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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Host-side unit tests for {@link MlsRecoveryPolicy} — the MLS recovery decisions.
 *
 * <p>These exist because every rule here only fires in a failure the fleet does not produce on
 * demand: convergence that never arrives, an external commit that fails to build, a peer that
 * advances its era while we are the higher-sorting side. During the 2026-07-25/26 fleet run each of
 * these was implemented but left UNEXERCISED for exactly that reason, and the only on-device
 * alternative was shipping fault-injection hooks in the send path. Testing the decision here covers
 * the logic permanently, with no device, no OTP burn, and nothing test-only in production code.
 *
 * <p>Each case below is tied to a real device-observed failure, cited in the comments.
 */
public class MlsRecoveryPolicyTest {

    // ------------------------------------------------------------------
    // Send-gate deadline. Device context: gates were seen holding 7-8 messages with 0 sent, and
    // BUG-03 produced a gate whose release paths could NEVER fire (it outlived its group).
    // ------------------------------------------------------------------

    @Test
    public void gate_notExpired_beforeDeadline() {
        // opened at t=1000, now t=50_000, deadline 90s → 49s elapsed, still waiting
        assertFalse(MlsRecoveryPolicy.gateExpired(1_000L, 50_000L, 90_000L));
    }

    @Test
    public void gate_expires_afterDeadline() {
        // 91s elapsed against a 90s deadline → release and send anyway
        assertTrue(MlsRecoveryPolicy.gateExpired(1_000L, 92_000L, 90_000L));
    }

    @Test
    public void gate_exactlyAtDeadline_isNotYetExpired() {
        // boundary: strictly greater-than, so exactly 90s has NOT expired.
        // NB openedAt must be > 0 — 0 is the "no gate open" sentinel (see gate_noGateOpen_* below),
        // which is exactly the trap the first version of this test fell into.
        assertFalse(MlsRecoveryPolicy.gateExpired(1_000L, 91_000L, 90_000L));
        assertTrue(MlsRecoveryPolicy.gateExpired(1_000L, 91_001L, 90_000L));
    }

    @Test
    public void gate_noGateOpen_neverExpires() {
        // openedAt <= 0 means no gate; must not be reported as expired (would cause a bogus flush)
        assertFalse(MlsRecoveryPolicy.gateExpired(0L, Long.MAX_VALUE / 2, 90_000L));
        assertFalse(MlsRecoveryPolicy.gateExpired(-1L, Long.MAX_VALUE / 2, 90_000L));
    }

    @Test
    public void gate_deadlineDisabled_neverExpires() {
        // deadline <= 0 disables the feature entirely (documented escape hatch)
        assertFalse(MlsRecoveryPolicy.gateExpired(1L, Long.MAX_VALUE / 2, 0L));
        assertFalse(MlsRecoveryPolicy.gateExpired(1L, Long.MAX_VALUE / 2, -5L));
    }

    // ------------------------------------------------------------------
    // Transport profile. These encode the difference between our two REAL transports:
    // Tachyon is server-mediated and ACKs convergence; the carrier MSRP path is peer-to-peer and
    // never will. Carrying Tachyon's assumption into the carrier path is a permanent silent stall.
    // ------------------------------------------------------------------

    @Test
    public void sendGate_appliesOnlyWhereConvergenceIsSignalled() {
        // Tachyon: a 66-byte control ACK exists, so the gate has something to wait for.
        assertTrue(MlsRecoveryPolicy.sendGateApplies(true));
        // Carrier MSRP (peer-to-peer, Welcome delivered directly): NOTHING can ever close a gate.
        // Opening one would stall every send forever — a hang with no error anywhere.
        assertFalse(MlsRecoveryPolicy.sendGateApplies(false));
    }

    @Test
    public void externalCommitResync_skippedWhenTheTransportRefusesMemberExternalCommits() {
        // Device-proven Tachyon behaviour: a member's ExternalInit is refused ("Invalid proposal type
        // for sender"). Attempting it anyway costs a round trip AND runs the resync path that deletes
        // the local group before it knows the commit lands (the BUG-04 shape).
        assertFalse(MlsRecoveryPolicy.externalCommitResyncApplies(false, /*member=*/ true));
        // A true NON-member may always external-commit — that is the RFC 9420 §12.4.3.2 case.
        assertTrue(MlsRecoveryPolicy.externalCommitResyncApplies(false, /*member=*/ false));
        // A transport that does permit member external commits may try it as a member.
        assertTrue(MlsRecoveryPolicy.externalCommitResyncApplies(true, /*member=*/ true));
    }

    // ------------------------------------------------------------------
    // Era tie-break. Device context: 00RU kept advancing its era past every era we created — an
    // era war. Neither RFC 9420 nor RCC.16 defines a tie-break, so we impose one.
    // ------------------------------------------------------------------

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
        // The whole point: for any distinct pair, exactly ONE side advances. If both advanced we
        // would duel; if neither advanced the conversation would stall forever.
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

    // ------------------------------------------------------------------
    // Bounded yield — the hole I put in my own tie-break: yielding must never deadlock.
    // ------------------------------------------------------------------

    @Test
    public void yield_stillWaiting_beforeBound() {
        assertFalse(MlsRecoveryPolicy.eraYieldExpired(1_000L, 60_000L, 120_000L));
    }

    @Test
    public void yield_expires_soAYieldCannotDeadlock() {
        // The designated advancer never advanced → after the bound we advance anyway.
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

    // ------------------------------------------------------------------
    // RCC.16 body-counter stamping. Device context: BOTH framing paths hardcoded this uint32 to 2 on
    // the belief it was a content-type enum. A real capture disproved that — across five TEXT
    // messages it walked 00000000..00000004, tracking sender_data.generation — and the mismatch is
    // what made every message after the first fail to decrypt on a Google Messages peer.
    // ------------------------------------------------------------------

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
        // a long-lived epoch can exceed a byte — the field is a uint32, so all four bytes matter
        final byte[] out = MlsRecoveryPolicy.stampBodyGeneration(framed(0), 0x01020304);
        assertEquals(0x01, out[4]);
        assertEquals(0x02, out[5]);
        assertEquals(0x03, out[6]);
        assertEquals(0x04, out[7]);
    }

    @Test
    public void stamp_overwritesTheHardcodedPlaceholder() {
        // the exact production bug: a body framed with the old constant 2 must come out as the
        // real generation, not 2
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
        // a raw/unframed payload must pass through untouched — we must never corrupt a body we do
        // not understand
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

    // ------------------------------------------------------------------
    // Resync-failure action (BUG-04). Device context: a forced resync probe failed to BUILD after
    // the local group was already deleted, leaving a record pointing at missing state — every
    // subsequent send died with "encrypt returned null" and NEVER self-healed.
    // ------------------------------------------------------------------

    @Test
    public void resyncFailure_withSnapshot_restoresIt() {
        assertEquals(MlsRecoveryPolicy.ResyncFailureAction.RESTORE_SNAPSHOT,
                MlsRecoveryPolicy.onResyncFailure(true));
    }

    @Test
    public void resyncFailure_withoutSnapshot_dropsRecordSoTheNextSendReestablishes() {
        // The critical case: NEVER leave a record pointing at deleted group state.
        assertEquals(MlsRecoveryPolicy.ResyncFailureAction.DROP_RECORD_AND_REESTABLISH,
                MlsRecoveryPolicy.onResyncFailure(false));
    }

    @Test
    public void resyncFailure_alwaysLeavesARecoverableState() {
        // Whatever the inputs, the outcome must be one of the two recoverable actions — there is no
        // "do nothing" branch, because doing nothing is what silently destroyed a live conversation.
        for (final boolean haveSnapshot : new boolean[] {true, false}) {
            final MlsRecoveryPolicy.ResyncFailureAction a =
                    MlsRecoveryPolicy.onResyncFailure(haveSnapshot);
            assertTrue(a == MlsRecoveryPolicy.ResyncFailureAction.RESTORE_SNAPSHOT
                    || a == MlsRecoveryPolicy.ResyncFailureAction.DROP_RECORD_AND_REESTABLISH);
        }
    }

    // ===================== asking the user =====================

    /**
     * BOTH conditions are required, and the two single-condition cases are the ones that matter.
     *
     * <p>Interrupting a user for ordinary backoff is how an alert gets trained into noise, and asking
     * while repair attempts are still owed cuts off a recovery that has not finished trying. Only the
     * conjunction is a real "we are out of moves and the choice costs you something".
     */
    @Test
    public void needsUserChoice_requiresBothExhaustedAndUnreachable() {
        // Out of attempts AND waiting on something we can never reach: ask.
        assertTrue(MlsRecoveryPolicy.needsUserChoice(true, false));

        // Out of attempts but the moment is reachable — an epoch gap we close by replaying commits.
        // This is backoff. Do NOT ask.
        assertFalse(MlsRecoveryPolicy.needsUserChoice(true, true));

        // Unreachable, but we still owe this conversation attempts. Do NOT ask yet.
        assertFalse(MlsRecoveryPolicy.needsUserChoice(false, false));

        // Healthy on both counts.
        assertFalse(MlsRecoveryPolicy.needsUserChoice(false, true));
    }

    /**
     * The arm the caller raises must be terminal-for-us, not a retry — a retry status would put the
     * decision back in the drive loop's hands, which is the thing this exists to stop.
     */
    @Test
    public void userActionRequired_isTerminalForTheMachine() {
        assertEquals(MlsResultStatus.FAIL_NO_RETRY,
                MlsHostAction.Kind.USER_ACTION_REQUIRED.defaultStatus);
    }

    // ------------------------------------------------------------------
    // The three thresholds Stage 6 moved here out of MlsProviderTransport, asserted at the
    // SHIPPING numbers. That is the point of the move: the tests above walk the boundary at windows
    // a test chose, and until now nothing said the transport was spending 90s, 16 and 3.
    // ------------------------------------------------------------------

    /** The shipping overload is the 90-second deadline, not a different rule at the same name. */
    @Test
    public void gate_shippingOverload_isTheDeclaredDeadline() {
        assertEquals(90_000L, MlsRecoveryPolicy.GATE_DEADLINE_MS);
        assertFalse(MlsRecoveryPolicy.gateExpired(1_000L, 1_000L + MlsRecoveryPolicy.GATE_DEADLINE_MS));
        assertTrue(MlsRecoveryPolicy.gateExpired(1_000L,
                1_001L + MlsRecoveryPolicy.GATE_DEADLINE_MS));
        // The sentinel survives the overload: no gate open is not an expired gate.
        assertFalse(MlsRecoveryPolicy.gateExpired(0L, Long.MAX_VALUE / 2));
    }

    /**
     * The parked-resend queue refuses AT the cap, not one past it.
     *
     * <p>Boundary stated both ways because the production call passes the size BEFORE the add — an
     * off-by-one here is a 17th resend parked at a gate that lives 90 seconds.
     */
    @Test
    public void gatedResendQueue_isFullAtTheCap() {
        assertEquals(16, MlsRecoveryPolicy.MAX_GATED_RESENDS);
        assertFalse(MlsRecoveryPolicy.gatedResendQueueFull(MlsRecoveryPolicy.MAX_GATED_RESENDS - 1));
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
     * Both refusal lines NAME the threshold they refused against.
     *
     * <p>Not decoration: the whole reason the sentences moved here with the constants is that an
     * operator reading the log is the only consumer of the number, and a line that stopped saying it
     * would leave the cap invisible everywhere. Asserted against the constant rather than against a
     * typed "16"/"3", so raising a cap cannot leave the log claiming the old one.
     */
    @Test
    public void theRefusalLinesQuoteTheirOwnThresholds() {
        final String gated = MlsRecoveryPolicy.gatedResendRefusalLine("conv:+15551234567", "m-42");
        assertTrue(gated, gated.contains(Integer.toString(MlsRecoveryPolicy.MAX_GATED_RESENDS)));
        assertTrue(gated, gated.contains("conv:+15551234567") && gated.contains("m-42"));
        final String reset = MlsRecoveryPolicy.unconvergedRunResetLine();
        assertTrue(reset,
                reset.contains(Integer.toString(MlsRecoveryPolicy.UNCONVERGED_BEFORE_REBUILD)));
    }
}
