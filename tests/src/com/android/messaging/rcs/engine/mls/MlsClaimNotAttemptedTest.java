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

import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Attribution;
import com.android.messaging.rcs.engine.mls.MlsClaimLedger.Caller;

import org.junit.Test;

/**
 * <b>A CLAIM THAT WAS NEVER ATTEMPTED MUST NOT BE CHARGED, AND MUST NOT BE CALLED "MADE".</b>
 *
 * <h2>The two faults, one cause</h2>
 *
 * <p>{@code MlsProviderTransport.spendOneClaim} charges a peer's ledger BEFORE it dials, which is
 * deliberate and right (a claim that HAPPENED and went uncounted leaves the next
 * caller told the pool is fuller than it is). Nothing ever handled the mirror. Contract v60's
 * {@code OUTCOME_NOT_ATTEMPTED} asserts that <b>no dial was spent</b> — an unbound provider, or one
 * predating the contract — and there was no refund anywhere, so:
 *
 * <ul>
 *   <li>a claim nobody made spent one of the peer's four-per-ten-minutes, and four of them make the
 *       ledger REFUSE a real claim at exactly the moment a provider has come back;</li>
 *   <li>{@link MlsClaimLedger#describeEmptyClaim}'s frame hard-codes <i>"was MADE and came back
 *       EMPTY"</i> and printed it for an outcome whose entire meaning is that nothing was made.</li>
 * </ul>
 *
 * <p>Device-measured on 010T 2026-09-11: two claims that spent zero dials and zero KeyPackages took
 * {@code +15715550103} from 1 to 2 of 4.
 *
 * <h2>What these tests pin, and the state that makes each RED</h2>
 *
 * <p>A check whose failing state nobody can name is not
 * evidence, so every test below names it. Two are worth reading before the rest:
 *
 * <ul>
 *   <li>{@link #reversingAChargeReproducesTheRecordExactly} is a ROUND TRIP, not a count. A count
 *       is satisfied by removing the wrong entry; the round trip is not.</li>
 *   <li>{@link #anUnreadableLedgerNeverClaimsARefundItCouldNotMake} exists because the first draft
 *       of {@code describeNotAttempted} printed {@code Math.max(0, count)} for the unreadable case,
 *       which reads as <i>"back at 0 of 4"</i> — a refund asserted on a record nobody could read,
 *       i.e. this very defect inside its own fix.</li>
 * </ul>
 */
public final class MlsClaimNotAttemptedTest {

    private static final String PEER = "+15715550103";
    private static final Caller ANY = Caller.DEBUG_KP_COUNT;
    private static final int CEILING = 4;

    /** The frame that belongs to a claim that WAS sent. No not-attempted line may carry it. */
    private static final String THE_MADE_FRAME = "was MADE and came back EMPTY";

    private static String refundedLine(final int afterRefund) {
        return MlsClaimLedger.describeNotAttempted(ANY, PEER, afterRefund, CEILING, null);
    }

    private static String unreadableLine() {
        return MlsClaimLedger.describeNotAttempted(
                ANY, PEER, MlsClaimLedger.NO_COUNT, CEILING, null);
    }

    // ---- 1. the frame: a third word for a third thing ---------------------------------------------

    /**
     * <b>THE DEFECT ITSELF.</b> The not-attempted line must not say the claim was MADE, in either of
     * its two accounting arms.
     *
     * <p>Asserted against the LITERAL frame rather than against
     * {@link MlsClaimLedger#describeEmptyClaim}'s output, deliberately. Commit {@code 9497aab8}
     * found a guard in this same cluster that could not fail because two of its three arms called
     * the one shared method they were supposed to be distinguishing; comparing the new line to the
     * old one through a shared helper would repeat that.
     *
     * <p>RED WHEN: {@code describeNotAttempted} is repointed at {@code describeEmptyClaim}, or its
     * frame is reworded back into the MADE vocabulary. Reachable — that is precisely the code this
     * replaced, and it is one delegation away.
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
     * It says what NOT_ATTEMPTED actually asserts — no dial, no KeyPackage — and forbids the
     * reading that cost the peer its allowance: that this is an empty answer.
     *
     * <p>RED WHEN: the line is shortened to "not attempted" alone, leaving the reader to guess
     * whether the KDS was reached.
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

    /**
     * It points at OUR side, never at the peer's device — the attribution rule, which this line
     * inherits rather than re-deciding.
     *
     * <p>RED WHEN: the arm is reworded to blame the pool, or the peer-facing disclaimer is dropped
     * on the theory that "not attempted" is self-evident.
     */
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

    // ---- 2. the accounting: a refund that is stated only when it is made ---------------------------

    /**
     * The refunded arm prints the count AFTER the refund, against the live ceiling, and says the
     * charge line it supersedes is superseded.
     *
     * <p>The count is asserted for a value that {@code Math.max(0, …)} would hide and for one it
     * would not, so a clamp cannot pass this by accident.
     *
     * <p>RED WHEN: the count is dropped (that number is the whole subject), the
     * ceiling is hard-coded past {@code debug.rcs.mls_claim_ceiling}, or the supersession clause
     * goes — without it an operator reading logcat sees the charge line's higher count last.
     */
    @Test
    public void aRefundedChargeNamesTheCountItLeftBehind() {
        final String s = refundedLine(3);
        assertTrue(s, s.contains("REFUNDED"));
        assertTrue("the post-refund count is missing: " + s, s.contains("back at 3 of 4"));
        assertTrue("nothing tells the reader the charge line above is stale: " + s,
                s.contains("SUPERSEDED BY THIS ONE"));
        assertTrue(s, s.contains(ANY.name()) && s.contains(PEER));
        assertTrue(s, s.contains("last " + (MlsClaimLedger.WINDOW_MS / 60000L) + " min"));
        assertEquals("the same call twice does not produce the same line", s, refundedLine(3));
        assertNotEquals("two different post-refund counts produce the same line, so the count is "
                + "not actually in it", refundedLine(0), refundedLine(1));
    }

    /**
     * <b>The arm that caught this very defect inside its own fix.</b> When the peer's ledger
     * is unreadable there is no count and no accounted-for charge, so the line must not use the word
     * REFUNDED and must not print a number that reads as one.
     *
     * <p>RED WHEN: {@link MlsClaimLedger#NO_COUNT} is clamped with {@code Math.max(0, …)} — the
     * literal first draft, which printed "back at 0 of 4" — or the two arms are merged back into
     * one sentence. Reachable by anyone tidying a negative out of a count.
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
     * A missing caller or peer degrades to a placeholder rather than printing "null", which would
     * read as a peer identifier in a line an operator acts on.
     *
     * <p>RED WHEN: {@code safe()}/{@code name()} are bypassed by a reworded frame.
     */
    @Test
    public void anAbsentCallerOrPeerNeverPrintsNull() {
        final String s = MlsClaimLedger.describeNotAttempted(null, null, 0, CEILING, null);
        assertFalse(s, s.contains("null"));
        assertTrue(s, s.contains("<no caller declared>") && s.contains("<no peer>"));
    }

    /**
     * The provider's diagnostic is APPENDED and marked unparseable, exactly as
     * {@code describeEmptyClaim} does it — the vocabulary firewall says the backend's own words may
     * ride along in a log and may never be branched on.
     *
     * <p>RED WHEN: {@code detail} is interpolated into the sentence (where a future reader would
     * parse it), or the do-not-parse marker is dropped.
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

    // ---- 3. the ledger record: the refund is a reversal, not a credit ------------------------------

    private static MlsClaimLedgerRecord decoded(final String s) {
        return MlsClaimLedgerRecord.decode(s);
    }

    /**
     * <b>THE ASSERTION THAT MATTERS.</b> Reversing a charge reproduces the record EXACTLY, so
     * "only the one charge was removed" is checked rather than intended.
     *
     * <p>A count going back down by one is satisfied by removing somebody ELSE's charge. The round
     * trip is not: any wrong entry, any reordering, any accidental prune fails it.
     *
     * <p>RED WHEN: {@code refunded} removes the first match instead of the last, prunes on the way
     * through, or drops more than one entry. All three are what a plausible one-line implementation
     * does.
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
     * The refund is matched on BOTH the caller and the stamp, so it cannot reverse a charge that is
     * not its own.
     *
     * <p>RED WHEN: the match is on the stamp alone (two callers inside one millisecond is ordinary —
     * {@code elapsedRealtime} is not a unique key) or on the caller alone, which would silently
     * reverse that caller's most recent charge instead.
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
     * A refund with nothing to reverse is a NO-OP, so a double refund credits once.
     *
     * <p>This is the direction that matters: handing back an allowance that cannot be matched to a
     * charge under-counts a pool that really was drained, which is the failure {@link MlsClaimLedger}
     * exists to prevent (see {@code claimLedgerFor}'s UNREADABLE posture).
     *
     * <p>RED WHEN: {@code refunded} falls back to "drop the newest entry" when its match fails.
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
     * A refund survives the store, because the ledger does — the record is durable precisely
     * because the packages are gone (see {@link MlsClaimLedgerRecord}'s header), so a refund that
     * only existed in memory would be undone by the next process death.
     *
     * <p>RED WHEN: {@code refunded} returns a record whose {@code encode()} does not round-trip —
     * e.g. by leaving a zero-length array that {@code decode} rejects.
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
     *
     * <p>RED WHEN: the null guard is dropped — a NullPointerException inside a refund would leave
     * the phantom charge standing AND lose the line that explains it.
     */
    @Test
    public void aNullCallerRefundsNothingAndDoesNotThrow() {
        final long t = 5_000_000L;
        final MlsClaimLedgerRecord charged = MlsClaimLedgerRecord.EMPTY.charged(ANY, t);
        assertEquals(charged.encode(), charged.refunded(null, t).encode());
    }

    // ---- 4. the attribution is NOT what was wrong, and must not be "fixed" -------------------------

    /**
     * {@link Attribution} stays at three values. A never-sent claim genuinely reduces to
     * {@link Attribution#NOT_ABOUT_THE_PEER} — that arm's tail already ends "…or it was never sent"
     * — so the fix is a FRAME, not a fourth constant.
     *
     * <p>This is a deliberate anti-regression: the vocabulary firewall
     * ({@code RcsMlsControlResult}) keeps the app's reduction to "whose fault is an empty answer",
     * and NOT_ATTEMPTED is not a report about anything the provider observed — it is synthesised on
     * our side of the binder.
     *
     * <p>RED WHEN: a fourth constant is added. That is not forbidden, but it makes every
     * {@code switch} in {@link MlsClaimLedger} fall to its {@code default} for the new value, and
     * this test is where that decision gets made on purpose rather than by omission.
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
