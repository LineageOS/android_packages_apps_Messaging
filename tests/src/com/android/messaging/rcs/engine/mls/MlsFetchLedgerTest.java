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

import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Caller;
import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Primitive;
import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Verdict;

import org.junit.Test;

/**
 * The ledger's arithmetic.
 *
 * <p>Everything here is a pure function of counts, which is the whole reason this class is in the
 * engine: the fault it prevents needs a conversation stuck BEHIND, a burst of look-ups, and a server
 * willing to answer {@code RESOURCE_EXHAUSTED}. That combination cost a device session to observe
 * once and cannot be asked for again.
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
     * The two bounds must be TELLABLE APART, because they point a reader at different things: one
     * says this caller is repeating itself, the other says the conversation is busy.
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
                Verdict.DENIED_CALLER_RATION, 3, 3, CEILING, "g:abc", Primitive.FETCH_MISSED_COMMITS);
        final String ceiling = MlsFetchLedger.describeRefusal(Caller.SELF_HEAL,
                Verdict.DENIED_SHARED_CEILING, 1, CEILING, CEILING, "g:abc",
                Primitive.FETCH_MISSED_COMMITS);
        assertTrue("the per-caller refusal must say THIS caller is the one repeating itself",
                ration.contains("THIS CALLER"));
        assertTrue("the ceiling refusal must say other callers spent it, or a reader will hunt for a "
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
     * <b>The ration refusal must not speak for the ceiling it did not check.</b>
     *
     * <p>{@link MlsFetchLedger#mayFetch} tests the caller's ration FIRST, so
     * {@code DENIED_CALLER_RATION} comes back even when the shared ceiling is gone too. The message
     * for that verdict used to assert "the conversation's shared allowance still has room"
     * unconditionally, and it was observed on device printing that sentence with "(8 of 8)" in it —
     * the ceiling exactly. Both halves were then wrong: the caller may be a minor contributor, and
     * the remedy is to wait out the window rather than to hunt for a loop in this caller.
     *
     * <p>Asserted per case, and the three cases are asserted to differ, because a fix that made all
     * three say the safest thing would pass a "does not contain" check while telling an operator
     * nothing.
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

        // The case the old sentence was written for is unchanged: the ceiling really does have room.
        final String roomLeft = MlsFetchLedger.describeRefusal(c, Verdict.DENIED_CALLER_RATION,
                c.ration, 1, CEILING, "g:abc", Primitive.GET_MLS_SERVER_ERA_EPOCH);
        assertTrue("with the ceiling genuinely unspent the line must still say so — the fix is to "
                + "check, not to stop reporting", roomLeft.contains("still has room"));
        assertTrue(roomLeft.contains("THIS CALLER is"));

        // A caller that does not charge the ceiling draws on ONE bound, so the ceiling's state had
        // no part in the decision and reporting it would invite a reader to weigh an irrelevant
        // number. HEALTH_PROBE is such a caller; the assertion is that it IS one, not that it is
        // spelled that way.
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

    /**
     * A budget of zero would fail a conversation that was never allowed to ask anything — a
     * self-inflicted outage rather than a tight bound, the trap {@link MlsFetchBudget#mayLook}
     * already clamps for.
     */
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

    /**
     * A charge with no declared caller is refused rather than defaulted.
     *
     * <p>Defaulting would give a new call site a free ration by omission, which is the shape of the
     * defect this ledger closes.
     */
    @Test
    public void aNullCallerIsRefusedNotDefaulted() {
        assertEquals(Verdict.DENIED_CALLER_RATION, MlsFetchLedger.mayFetch(null, 0, 0, CEILING));
        assertFalse(MlsFetchLedger.mayFetch(null, 0, 0, CEILING).permitted());
    }

    // ---- the exempt arms -------------------------------------------------------------------------

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

    // ---- the fail-open asymmetry -----------------------------------------------------------------

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

    // ---- the rations themselves ------------------------------------------------------------------

    @Test
    public void everyNonExemptCallerHasAPositiveRation() {
        for (final Caller c : Caller.values()) {
            if (c.isExempt()) continue;
            assertTrue(c + " has ration " + c.ration + "; a non-exempt door needs at least one look "
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
        assertFalse("HEALTH_PROBE must not compete with recovery: starving the DIVERGED health test "
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

    // ---- the window, and the one datum behind it -------------------------------------------------

    @Test
    public void theWindowIsMlsFetchBudgetsMeasuredCooldown() {
        assertEquals("one datum, one home: 200s on deviceA, 2026-09-08",
                MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS, MlsFetchLedger.WINDOW_MS);
    }

    @Test
    public void theCeilingIsBelowTheBurstThatExhaustedTheQuota() {
        assertTrue("ten fetches drew RESOURCE_EXHAUSTED; a bound set at the value that failed is a "
                + "bound that fails", CEILING < 10);
        assertTrue("the drive + self-heal sequence the device check runs back to back must fit WITH "
                + "ROOM TO SPARE — measured on device, the real sequence carries a MAINTENANCE "
                + "charge on the same conversation too, and a ceiling at the fixture's own size "
                + "finishes at exactly the bound and refuses the next healthy look",
                CEILING > Caller.RECONCILE_DRIVE.ration + Caller.SELF_HEAL.ration);
    }

    // ---- the primitives --------------------------------------------------------------------------

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
     * The unreadable-look line must add what only the ledger knows, and must keep
     * {@link MlsFetchBudget}'s classification rather than restating it.
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
        // Not decoration: these run on a path where the conversation key can be null (a 1:1 with no
        // group), and a log line that throws inside a refusal turns a bounded refusal into a crash.
        MlsFetchLedger.describeRefusal(null, Verdict.DENIED_CALLER_RATION, 0, 0, 0, null, null);
        MlsFetchLedger.describeExemption(null, null, null, 0, 0);
        MlsFetchLedger.describeCharge(Caller.SELF_HEAL, null, null, 0, 0, 0);
        MlsFetchLedger.describeFailOpen(null, null, null);
        MlsFetchLedger.describeUnreadableLook(null, null, null, -1);
    }
}
