/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Caller;
import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Primitive;
import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Verdict;

import org.junit.Test;

/**
 * {@link MlsFetchLedger}'s verdicts and log lines, as pure functions of counts. See
 * docs/mls/budgets.md.
 */
public final class MlsFetchLedgerTest {

    private static final int CEILING = MlsFetchLedger.SHARED_CEILING;

    @Test
    public void aFirstLookIsPermitted() {
        assertEquals(Verdict.SPEND, MlsFetchLedger.mayFetch(Caller.SELF_HEAL, 0, 0, CEILING));
    }

    @Test
    public void aCallerIsRefusedAtItsOwnRation() {
        final int r = Caller.SELF_HEAL.ration;
        assertEquals("the last permitted look is ration-1",
                Verdict.SPEND, MlsFetchLedger.mayFetch(Caller.SELF_HEAL, r - 1, r - 1, CEILING));
        assertEquals(Verdict.DENIED_CALLER_RATION,
                MlsFetchLedger.mayFetch(Caller.SELF_HEAL, r, r, CEILING));
    }

    /**
     * One refusal says this caller is repeating itself, the other that the conversation is busy.
     */
    @Test
    public void theTwoRefusalsAreDistinct() {
        assertNotEquals(Verdict.DENIED_CALLER_RATION, Verdict.DENIED_SHARED_CEILING);
        // Within its own ration, over the shared ceiling.
        assertEquals(Verdict.DENIED_SHARED_CEILING,
                MlsFetchLedger.mayFetch(Caller.SELF_HEAL, 0, CEILING, CEILING));
        // Over its own ration, under the shared ceiling.
        assertEquals(Verdict.DENIED_CALLER_RATION,
                MlsFetchLedger.mayFetch(Caller.SELF_HEAL, Caller.SELF_HEAL.ration, 0, CEILING));
    }

    @Test
    public void theRefusalLinesSayWhichBoundRefused() {
        final String ration = MlsFetchLedger.describeRefusal(Caller.SELF_HEAL,
                Verdict.DENIED_CALLER_RATION, 3, 3, CEILING, "g:abc",
                Primitive.FETCH_MISSED_COMMITS);
        final String ceiling = MlsFetchLedger.describeRefusal(Caller.SELF_HEAL,
                Verdict.DENIED_SHARED_CEILING, 1, CEILING, CEILING, "g:abc",
                Primitive.FETCH_MISSED_COMMITS);
        assertTrue("the per-caller refusal must say THIS caller is the one repeating itself",
                ration.contains("THIS CALLER"));
        assertTrue(
                "the ceiling refusal must say other callers spent it, or a reader will hunt for a "
                + "loop in the wrong place", ceiling.contains("OTHER CALLERS"));
        assertNotEquals("the two refusals must not read the same", ration, ceiling);
        for (final String s : new String[] {ration, ceiling}) {
            assertTrue("every refusal must say the quota is OURS, not the server's",
                    s.contains("OUR bound, not the server's"));
            assertTrue("every refusal must say NOTHING WAS ASKED — the whole point is that the "
                    + "caller must not read it as the server having nothing to say",
                    s.contains("NOTHING WAS ASKED"));
            assertTrue("every refusal must name the primitive it refused",
                    s.contains(Primitive.FETCH_MISSED_COMMITS.aidlName));
            assertTrue("every refusal must name the caller", s.contains("SELF_HEAL"));
            assertTrue("every refusal must name the conversation", s.contains("g:abc"));
        }
    }

    /**
     * {@link MlsFetchLedger#mayFetch} tests the ration first, so a ration refusal must check the
     * ceiling before speaking for it. The three cases must also read differently, so a line that
     * says the safest thing in every case does not pass.
     */
    @Test
    public void theRationRefusalReportsBothBoundsWhenBothAreGone() {
        final Caller c = Caller.SELF_HEAL;
        final String bothGone = MlsFetchLedger.describeRefusal(c, Verdict.DENIED_CALLER_RATION,
                c.ration, CEILING, CEILING, "g:abc", Primitive.GET_MLS_SERVER_ERA_EPOCH);
        assertFalse("the ceiling is spent exactly, so this line must not claim it has room: "
                + bothGone, bothGone.contains("still has room"));
        assertTrue("with both bounds gone the line must say so, or an operator reads one refusal "
                + "and looks for a loop that is not there", bothGone.contains("BOTH bounds"));
        assertFalse("and it must NOT tell the operator this caller is the one repeating itself, "
                + "which is the instruction that was wrong", bothGone.contains("THIS CALLER is"));

        // The ceiling genuinely has room.
        final String roomLeft = MlsFetchLedger.describeRefusal(c, Verdict.DENIED_CALLER_RATION,
                c.ration, 1, CEILING, "g:abc", Primitive.GET_MLS_SERVER_ERA_EPOCH);
        assertTrue("with the ceiling genuinely unspent the line must still say so — the fix is to "
                + "check, not to stop reporting", roomLeft.contains("still has room"));
        assertTrue(roomLeft.contains("THIS CALLER is"));

        // A caller outside the ceiling draws on one bound, so the ceiling's state had no part in
        // the decision.
        assertFalse("this test needs a caller outside the shared ceiling and HEALTH_PROBE is no "
                + "longer one", Caller.HEALTH_PROBE.chargesTheSharedCeiling);
        final String outsideCeiling = MlsFetchLedger.describeRefusal(Caller.HEALTH_PROBE,
                Verdict.DENIED_CALLER_RATION, Caller.HEALTH_PROBE.ration, CEILING, CEILING,
                "g:abc", Primitive.GET_MLS_SERVER_ERA_EPOCH);
        assertFalse("a caller outside the ceiling must not report the ceiling as having room",
                outsideCeiling.contains("still has room"));
        assertTrue("it must say the ceiling had no part in the refusal, rather than silently "
                + "omitting it", outsideCeiling.contains("had no part in this refusal"));
        assertTrue("its own ration IS the reason, so the instruction is correct for it",
                outsideCeiling.contains("THIS CALLER is"));

        assertNotEquals("the three cases must not read the same", bothGone, roomLeft);
        assertNotEquals("the three cases must not read the same", bothGone, outsideCeiling);
        assertNotEquals("the three cases must not read the same", roomLeft, outsideCeiling);
    }

    /** A ceiling of zero would fail a conversation that was never allowed to ask anything. */
    @Test
    public void aZeroCeilingIsClampedRatherThanObeyed() {
        assertEquals(Verdict.SPEND, MlsFetchLedger.mayFetch(Caller.SELF_HEAL, 0, 0, 0));
        assertEquals(Verdict.SPEND, MlsFetchLedger.mayFetch(Caller.SELF_HEAL, 0, 0, -5));
        assertEquals("one look is permitted at a clamped ceiling and no more",
                Verdict.DENIED_SHARED_CEILING, MlsFetchLedger.mayFetch(Caller.SELF_HEAL, 0, 1, 0));
    }

    @Test
    public void negativeCountsReadAsNone() {
        assertEquals(Verdict.SPEND, MlsFetchLedger.mayFetch(Caller.SELF_HEAL, -3, -9, CEILING));
    }

    /** Defaulting would give a new call site a free ration by omission. */
    @Test
    public void aNullCallerIsRefusedNotDefaulted() {
        assertEquals(Verdict.DENIED_CALLER_RATION, MlsFetchLedger.mayFetch(null, 0, 0, CEILING));
        assertFalse(MlsFetchLedger.mayFetch(null, 0, 0, CEILING).permitted());
    }

    @Test
    public void anExemptCallerIsNeverRefusedAndNeverCharged() {
        for (final Caller c : Caller.values()) {
            if (!c.isExempt()) continue;
            final Verdict v = MlsFetchLedger.mayFetch(c, 9999, 9999, CEILING);
            assertEquals(c + " must always be permitted — an operator runs a debug arm precisely "
                    + "when something is already wrong", Verdict.SPEND_EXEMPT, v);
            assertTrue(v.permitted());
            assertFalse(c + " must not be charged, or a debug arm would silently spend a recovery "
                    + "look", v.charges());
        }
    }

    @Test
    public void theExemptionLineSaysItIsNotFree() {
        final String s = MlsFetchLedger.describeExemption(
                Caller.DEBUG_DUMP, Primitive.FETCH_MISSED_COMMITS, "g:abc", 3, CEILING);
        assertTrue("the line must say the arm is exempt", s.contains("EXEMPT"));
        assertTrue("the line must say it is NOT charged", s.contains("NOT charged"));
        assertTrue("the line must say it is not free — the server still counts it",
                s.contains("not free"));
        assertTrue("the line must report what the conversation has already spent, which is the "
                + "number that tells an operator whether their probe matters", s.contains("3 of "));
    }

    @Test
    public void exactlyOneCallerFailsOpen() {
        int n = 0;
        for (final Caller c : Caller.values()) {
            if (c.failsOpenOnRefusal()) {
                n++;
                assertEquals(Caller.PEER_REPORT_VERIFY, c);
            }
        }
        assertEquals("fail-open is a single deliberate exception (D3), not a posture", 1, n);
    }

    @Test
    public void theFailOpenLineSaysNothingWasVerified() {
        final String s = MlsFetchLedger.describeFailOpen(
                Caller.PEER_REPORT_VERIFY, "g:abc", "treating the reporter as a member");
        assertTrue("the line must say it proceeded", s.contains("PROCEEDING ANYWAY"));
        assertTrue("the line must say nothing was verified, or a reader will believe a check "
                + "happened", s.contains("NOTHING WAS VERIFIED"));
        assertTrue("the line must say our budget is not evidence about the peer",
                s.contains("not evidence about the peer"));
    }

    @Test
    public void everyNonExemptCallerHasAPositiveRation() {
        for (final Caller c : Caller.values()) {
            if (c.isExempt()) continue;
            assertTrue(c + " has ration " + c.ration
                    + "; a non-exempt door needs at least one look "
                    + "or it is an outage, not a bound", c.ration >= 1);
        }
    }

    @Test
    public void everyRationFitsUnderTheCeiling() {
        for (final Caller c : Caller.values()) {
            if (c.isExempt() || !c.chargesTheSharedCeiling) continue;
            assertTrue(c + "'s ration (" + c.ration + ") exceeds the shared ceiling (" + CEILING
                    + "), so its own bound can never be the one that refuses it — the constant is "
                    + "decorative and a reader would believe it", c.ration <= CEILING);
        }
    }

    @Test
    public void theHealthReadersAreOutsideTheCeiling() {
        assertFalse(
                "HEALTH_PROBE must not compete with recovery: starving the DIVERGED health test "
                + "escalates the ladder to a heavier remedy than the fault needed (D3)",
                Caller.HEALTH_PROBE.chargesTheSharedCeiling);
        assertEquals("a health reader held outside the ceiling is still bounded by its own ration, "
                + "or it is not bounded at all",
                Verdict.DENIED_CALLER_RATION,
                MlsFetchLedger.mayFetch(Caller.HEALTH_PROBE, Caller.HEALTH_PROBE.ration,
                        /*spentAgainstCeiling=*/ 0, CEILING));
        assertEquals("and it is NOT refused when recovery has spent the conversation's ceiling",
                Verdict.SPEND,
                MlsFetchLedger.mayFetch(Caller.HEALTH_PROBE, 0, CEILING + 50, CEILING));
    }

    @Test
    public void theWindowIsMlsFetchBudgetsMeasuredCooldown() {
        assertEquals("one datum, one home: the 200s throttle cooldown",
                MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS, MlsFetchLedger.WINDOW_MS);
    }

    @Test
    public void theCeilingIsBelowTheBurstThatExhaustedTheQuota() {
        assertTrue("ten fetches drew RESOURCE_EXHAUSTED; a bound set at the value that failed is a "
                + "bound that fails", CEILING < 10);
        assertTrue(
                "the drive + self-heal sequence the device check runs back to back must fit WITH "
                + "ROOM TO SPARE — on a device the real sequence carries a MAINTENANCE "
                + "charge on the same conversation too, and a ceiling at the fixture's own size "
                + "finishes at exactly the bound and refuses the next healthy look",
                CEILING > Caller.RECONCILE_DRIVE.ration + Caller.SELF_HEAL.ration);
    }

    @Test
    public void everyPrimitiveNamesADistinctAidlMethod() {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final Primitive p : Primitive.values()) {
            assertTrue(p + " has an empty aidlName; the source scan matches on it",
                    p.aidlName != null && !p.aidlName.isEmpty());
            assertTrue("two primitives share the aidlName " + p.aidlName + ", so the scan would "
                    + "attribute one's call sites to the other", seen.add(p.aidlName));
        }
        assertEquals("five provider methods each dial MlsControlClient.getGroupInfo exactly once. "
                + "If this number changed, the resource has a new door and MlsFetchLedger's own "
                + "javadoc table is now wrong too.", 5, Primitive.values().length);
    }

    @Test
    public void theChargeLineNamesBothCounters() {
        final String s = MlsFetchLedger.describeCharge(
                Caller.SELF_HEAL, Primitive.GET_MLS_SERVER_ERA_EPOCH, "g:abc", 1, 4, CEILING);
        assertTrue(s.contains("SELF_HEAL"));
        assertTrue(s.contains(Primitive.GET_MLS_SERVER_ERA_EPOCH.aidlName));
        assertTrue("the caller's own count must be visible", s.contains("1 of "));
        assertTrue("and the shared one, because a refusal can come from either",
                s.contains("4 of " + CEILING + " shared"));
    }

    /**
     * The unreadable-look line keeps {@link MlsFetchBudget}'s classification rather than restating
     * it.
     */
    @Test
    public void theUnreadableLookLineCountsTheWholeConversation() {
        final String s = MlsFetchLedger.describeUnreadableLook(
                Caller.SELF_HEAL, Primitive.FETCH_MISSED_COMMITS, "g:abc", 5);
        assertTrue("it must report the CONVERSATION's spend across all callers — a throttle is "
                + "provoked by the conversation, not by one operation", s.contains("5 shared"));
        assertTrue("it must keep the throttle classification rather than restating it",
                s.contains("RESOURCE_EXHAUSTED"));
    }

    @Test
    public void everyLineToleratesMissingInputs() {
        // The conversation key can be null (a 1:1 with no group), and a log line that throws inside
        // a refusal turns a bounded refusal into a crash.
        MlsFetchLedger.describeRefusal(null, Verdict.DENIED_CALLER_RATION, 0, 0, 0, null, null);
        MlsFetchLedger.describeExemption(null, null, null, 0, 0);
        MlsFetchLedger.describeCharge(Caller.SELF_HEAL, null, null, 0, 0, 0);
        MlsFetchLedger.describeFailOpen(null, null, null);
        MlsFetchLedger.describeUnreadableLook(null, null, null, -1);
    }


    @Test
    public void ledgerPrefKeyIsStableAndNamesAMissingKey() {
        assertEquals("mls_fetch_ledger_g:x", MlsFetchLedger.ledgerPrefKey("g:x"));
        assertEquals("mls_fetch_ledger_<none>", MlsFetchLedger.ledgerPrefKey(null));
    }
}
