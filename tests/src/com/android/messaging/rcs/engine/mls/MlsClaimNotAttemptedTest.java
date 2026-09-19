/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Attribution;
import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Caller;
import com.android.messaging.rcs.log.LogMask;

import org.junit.Test;

/**
 * A claim that was never attempted ({@code OUTCOME_NOT_ATTEMPTED}: no dial was spent) is refunded
 * and not described as made. {@code spendOneClaim} charges the ledger before it dials, so without a
 * refund a claim nobody made spends the peer's allowance and can make the ledger refuse a real
 * claim once the provider is back. See docs/mls/budgets.md.
 */
public final class MlsClaimNotAttemptedTest {

    private static final String PEER = "+15715550103";
    private static final Caller ANY = Caller.DEBUG_KP_COUNT;
    private static final int CEILING = 4;

    /** The frame for a claim that was sent. No not-attempted line may carry it. */
    private static final String THE_MADE_FRAME = "was MADE and came back EMPTY";

    private static String refundedLine(final int afterRefund) {
        return MlsClaimLedger.describeNotAttempted(ANY, PEER, afterRefund, CEILING, null);
    }

    private static String unreadableLine() {
        return MlsClaimLedger.describeNotAttempted(
                ANY, PEER, MlsClaimLedger.NO_COUNT, CEILING, null);
    }

    /**
     * The not-attempted line does not say the claim was made, in either accounting arm. Asserted
     * against the literal frame, not through {@link MlsClaimLedger#describeEmptyClaim}, so the
     * comparison cannot be a shared method against itself.
     */
    @Test
    public void aClaimThatWasNeverSentIsNeverDescribedAsMade() {
        for (final String s : new String[] {refundedLine(1), unreadableLine()}) {
            assertFalse("a claim the provider NEVER SENT is described as one that was MADE, which "
                    + "is the whole of the second fault: " + s, s.contains(THE_MADE_FRAME));
            assertTrue("the line does not say the claim was NOT ATTEMPTED, so a reader has no word "
                    + "for the third thing that can happen to a claim: " + s,
                    s.contains("was NOT ATTEMPTED"));
        }
    }

    /**
     * It says what NOT_ATTEMPTED asserts (no dial, no KeyPackage) and that this is not an empty
     * answer.
     */
    @Test
    public void itSaysNoDialWasSpentAndNoKeyPackageLeftThePool() {
        final String s = refundedLine(0);
        assertTrue(s, s.contains("No dial was spent"));
        assertTrue(s, s.contains("NO KeyPackage left their pool"));
        assertTrue("the line does not forbid reading a never-sent claim as an empty answer, which "
                + "is the collision this is about: " + s,
                s.contains("not an empty answer") && s.contains("must not be read as one"));
    }

    /** It points at our side, never at the peer's device. */
    @Test
    public void itNeverSendsAnyoneToThePeersDevice() {
        for (final String s : new String[] {refundedLine(2), unreadableLine()}) {
            assertTrue(s, s.contains("THIS IS NOT A FACT ABOUT THE PEER"));
            assertTrue("the line does not say whose side to look at: " + s,
                    s.contains("OUR OWN side"));
            assertFalse("the line suggests the peer's pool is the problem: " + s,
                    s.contains("IS a fact about their pool"));
        }
    }

    /**
     * The refunded arm prints the count after the refund against the live ceiling, and says the
     * charge line it supersedes is superseded. Asserted for a value {@code Math.max(0, …)} would
     * hide.
     */
    @Test
    public void aRefundedChargeNamesTheCountItLeftBehind() {
        final String s = refundedLine(3);
        assertTrue(s, s.contains("REFUNDED"));
        assertTrue("the post-refund count is missing: " + s, s.contains("back at 3 of 4"));
        assertTrue("nothing tells the reader the charge line above is stale: " + s,
                s.contains("SUPERSEDED BY THIS ONE"));
        assertTrue(s, s.contains(ANY.name()) && s.contains(LogMask.number(PEER)));
        assertTrue(s, s.contains("last " + (MlsClaimLedger.WINDOW_MS / 60000L) + " min"));
        assertEquals("the same call twice does not produce the same line", s, refundedLine(3));
        assertNotEquals("two different post-refund counts produce the same line, so the count is "
                + "not actually in it", refundedLine(0), refundedLine(1));
    }

    /**
     * An unreadable ledger has no count and no accounted-for charge, so the line never says
     * refunded or prints a number that reads as one ({@link MlsClaimLedger#NO_COUNT} is not clamped
     * to 0).
     */
    @Test
    public void anUnreadableLedgerNeverClaimsARefundItCouldNotMake() {
        final String s = unreadableLine();
        assertFalse("the line claims a REFUND on a record nobody could read: " + s,
                s.contains("REFUNDED"));
        assertFalse("the unreadable arm prints a post-refund count it does not have: " + s,
                s.contains("back at"));
        assertFalse("the sentinel leaked into the line as a count: " + s, s.contains("-1"));
        assertTrue("the line does not say WHY no count is stated: " + s,
                s.contains("UNREADABLE") && s.contains("no count can be stated"));
        assertTrue("the line does not say the stuck charge clears on its own, which is the only "
                + "thing the reader can act on: " + s,
                s.contains((MlsClaimLedger.WINDOW_MS / 60000L) + "-minute window"));
        assertNotEquals("the unreadable arm and the refunded arm print the same sentence, so a "
                + "refund that did not happen is indistinguishable from one that did",
                s, refundedLine(0));
    }

    /**
     * A missing caller or peer degrades to a placeholder rather than "null", which would read as a
     * peer identifier.
     */
    @Test
    public void anAbsentCallerOrPeerNeverPrintsNull() {
        final String s = MlsClaimLedger.describeNotAttempted(null, null, 0, CEILING, null);
        assertFalse(s, s.contains("null"));
        assertTrue(s, s.contains("<no caller declared>") && s.contains("<no peer>"));
    }

    /**
     * The provider's diagnostic is appended and marked unparseable, as {@code describeEmptyClaim}
     * does.
     */
    @Test
    public void theProvidersDetailRidesAlongAndIsMarkedUnparseable() {
        final String bare = refundedLine(1);
        final String withDetail = MlsClaimLedger.describeNotAttempted(
                ANY, PEER, 1, CEILING, "the RCS provider is not bound");
        assertTrue(withDetail, withDetail.startsWith(bare));
        assertTrue(withDetail, withDetail.contains("[provider detail, do not parse: "
                + "the RCS provider is not bound]"));
        assertEquals("an empty detail still appends a bracket", bare,
                MlsClaimLedger.describeNotAttempted(ANY, PEER, 1, CEILING, ""));
    }

    private static MlsClaimLedgerRecord decoded(final String s) {
        return MlsClaimLedgerRecord.decode(s);
    }

    /**
     * Reversing a charge reproduces the record exactly. A count going down by one is satisfied by
     * removing someone else's charge; the round trip is not.
     */
    @Test
    public void reversingAChargeReproducesTheRecordExactly() {
        final long t0 = 1_000_000L;
        MlsClaimLedgerRecord before = MlsClaimLedgerRecord.EMPTY;
        before = before.charged(Caller.UPGRADE_PROBE, t0);
        before = before.charged(Caller.ADD_MEMBER, t0 + 1);
        before = before.charged(Caller.ERA_ADVANCE, t0 + 2);
        final long now = t0 + 3;
        final String expected = before.pruned(now).encode();

        final MlsClaimLedgerRecord charged = before.charged(ANY, now);
        assertEquals("the charge did not land, so the refund is being tested against nothing",
                before.size() + 1, charged.size());

        assertEquals("reversing the charge did not reproduce the record it was applied to — the "
                + "refund removed something other than exactly its own entry",
                expected, charged.refunded(ANY, now).encode());
    }

    /**
     * The refund matches both caller and stamp, so it cannot reverse a charge that is not its own
     * ({@code elapsedRealtime} is not a unique key).
     */
    @Test
    public void aRefundCannotReverseSomebodyElsesCharge() {
        final long t = 2_000_000L;
        final MlsClaimLedgerRecord both = MlsClaimLedgerRecord.EMPTY
                .charged(Caller.UPGRADE_PROBE, t)
                .charged(ANY, t);
        assertEquals(2, both.size());

        final MlsClaimLedgerRecord mine = both.refunded(ANY, t);
        assertEquals("the refund did not remove a charge at all", 1, mine.size());
        assertEquals("the refund reversed the OTHER caller's charge from the same millisecond",
                1, mine.spentBy(Caller.UPGRADE_PROBE, t));
        assertEquals("the refund left its own charge standing", 0, mine.spentBy(ANY, t));

        assertEquals("a refund for a caller with no charge at this stamp removed something anyway",
                both.encode(), both.refunded(Caller.ERA_ADVANCE, t).encode());
        assertEquals("a refund at the wrong stamp removed something anyway",
                both.encode(), both.refunded(ANY, t + 5).encode());
    }

    /**
     * A refund with nothing to reverse is a no-op, so a double refund credits once; crediting an
     * unmatched refund would under-count a pool that really was drained.
     */
    @Test
    public void aSecondRefundCreditsNothing() {
        final long t = 3_000_000L;
        final MlsClaimLedgerRecord charged = MlsClaimLedgerRecord.EMPTY
                .charged(Caller.UPGRADE_PROBE, t - 10)
                .charged(ANY, t);
        final MlsClaimLedgerRecord once = charged.refunded(ANY, t);
        assertEquals(1, once.size());
        assertEquals("a second refund of the same charge credited again",
                once.encode(), once.refunded(ANY, t).encode());
    }

    /**
     * A refund survives the store: the record is durable because the claimed packages are gone, so
     * an in-memory refund would be undone by the next process death.
     */
    @Test
    public void aRefundedRecordStillRoundTripsThroughTheStore() {
        final long t = 4_000_000L;
        final MlsClaimLedgerRecord emptied =
                MlsClaimLedgerRecord.EMPTY.charged(ANY, t).refunded(ANY, t);
        assertEquals("the last charge was refunded and the record is not empty", 0, emptied.size());
        final MlsClaimLedgerRecord reread = decoded(emptied.encode());
        assertTrue("a refunded-to-empty record no longer decodes, so the store would read it as "
                + "UNREADABLE — which this ledger treats as FULLY SPENT, turning a refund into a "
                + "refusal", reread != null);
        assertEquals(0, reread.size());

        final MlsClaimLedgerRecord partial = MlsClaimLedgerRecord.EMPTY
                .charged(Caller.ADD_MEMBER, t)
                .charged(ANY, t + 1)
                .refunded(ANY, t + 1);
        final MlsClaimLedgerRecord back = decoded(partial.encode());
        assertTrue(back != null);
        assertEquals(partial.encode(), back.encode());
        assertEquals(1, back.spentBy(Caller.ADD_MEMBER, t + 2));
        assertEquals(0, back.spentBy(ANY, t + 2));
    }

    /**
     * A null caller is a no-op rather than a throw, matching {@link MlsClaimLedgerRecord#charged}.
     */
    @Test
    public void aNullCallerRefundsNothingAndDoesNotThrow() {
        final long t = 5_000_000L;
        final MlsClaimLedgerRecord charged = MlsClaimLedgerRecord.EMPTY.charged(ANY, t);
        assertEquals(charged.encode(), charged.refunded(null, t).encode());
    }

    /**
     * {@link Attribution} stays at three values: a never-sent claim reduces to
     * {@link Attribution#NOT_ABOUT_THE_PEER}, and NOT_ATTEMPTED is synthesised on the app's side of
     * the binder rather than observed by the provider. A fourth constant would send every
     * {@code switch} in {@link MlsClaimLedger} to its {@code default}.
     */
    @Test
    public void theAttributionStaysThreeValuedAndNotAttemptedIsNotOneOfThem() {
        assertEquals("MlsClaimLedger.Attribution has gained a constant. Check every switch over it "
                + "— describeEmptyClaim and blockedByEmptyClaim both have a default arm that will "
                + "silently absorb it — and check that the new value is genuinely an ANSWER to "
                + "'whose fault is an empty answer', which NOT_ATTEMPTED is not.",
                3, Attribution.values().length);
        for (final Attribution a : Attribution.values()) {
            assertFalse("an Attribution constant is named for a DISPOSITION rather than for whose "
                    + "fault an empty answer is: " + a, a.name().contains("NOT_ATTEMPTED"));
        }
    }
}
