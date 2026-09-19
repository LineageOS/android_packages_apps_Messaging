/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsStallAlertSplitTest {


    @Test
    public void onlyADeliberateStopAPersonCanLiftRaisesTheAlert() {
        final ConvState cs = new ConvState();
        cs.stallAlertDown = true;
        final FakeShellPort f = new FakeShellPort().returns("conv", cs).on("raiseStall", a -> null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsStallAlert.surfaceIfNobodyElseWill(f.port(), log, "grp", "+2", Health.DIVERGED,
                MlsRebuildOutcome.REFUSED_BY_GUARD);
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("raiseStall(g:grp, +2, grp, health=DIVERGED")));
        assertFalse("raising re-arms the one clear that withdraws it", cs.stallAlertDown);
        assertTrue(log.said("E", "STOPPED repairing g:grp"));
        for (final MlsRebuildOutcome o : new MlsRebuildOutcome[] {null,
                MlsRebuildOutcome.NOT_CONVERGED, MlsRebuildOutcome.SUPPRESSED_AS_ONE_EPISODE}) {
            final FakeShellPort quiet = new FakeShellPort();
            MlsStallAlert.surfaceIfNobodyElseWill(quiet.port(), MlsLogSink.NONE, "grp", "+2",
                    Health.DIVERGED, o);
            assertTrue(o + " interrupts nobody", quiet.calls.isEmpty());
        }
    }


    @Test
    public void theAlertIsWithdrawnOncePerRaising() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("conv", cs).on("clearStall", a -> null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsStallAlert.stallCleared(f.port(), log, "grp", "+2", "healed");
        assertTrue(cs.stallAlertDown);
        assertTrue(log.said("I", "clearing any stall alert on g:grp — healed"));
        MlsStallAlert.stallCleared(f.port(), MlsLogSink.NONE, "grp", "+2", "again");
        assertEquals(1, java.util.Collections.frequency(f.calls, "clearStall(g:grp)"));
    }


    private static FakeShellPort healthPort(final Look<long[]> look) {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", "g:grp")
                .returns("getGroup", null).returns("lookServerEraEpoch", look)
                .on("clearStall", a -> null).on("raiseStall", a -> null);
        return f;
    }

    @Test
    public void aRefusedLookNeitherRaisesNorClearsAndAMissingGroupRaises() {
        final FakeShellPort refused = healthPort(Look.refusedByLedger("no"));
        MlsStallAlert.refreshStallNotification(refused.port(), MlsLogSink.NONE, "grp", "+2",
                "g:grp");
        assertFalse(refused.calls.stream()
                .anyMatch(c -> c.startsWith("clearStall") || c.startsWith("raiseStall")));
        final FakeShellPort gone = healthPort(Look.asked(null));
        MlsStallAlert.refreshStallNotification(gone.port(), MlsLogSink.NONE, "grp", "+2", "g:grp");
        assertTrue(gone.calls.stream().anyMatch(c -> c.startsWith("raiseStall(g:grp, +2, grp")));
    }


    @Test
    public void aStrandedConversationWhoseLastRebuildIsRefusedRaisesTheChoice() {
        final FakeShellPort f = strandedPort();
        MlsStallAlert.offerTheStallChoiceOffThread(MlsConfig.defaults(), f.port(), MlsLogSink.NONE,
                "p:+2", 3, /*exhaustedWhenAsked=*/ true);
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("raiseStall(p:+2, +2, null")));
        final FakeShellPort bad = new FakeShellPort();
        MlsStallAlert.offerTheStallChoiceOffThread(MlsConfig.defaults(), bad.port(),
                MlsLogSink.NONE, "not-a-key", 3, true);
        assertTrue("an unreadable key asks nobody", bad.calls.isEmpty());
    }

    /**
     * The budget is re-read off-thread from the record: a heal that landed after the escalation
     * asked has cleared it, and then nothing is asked, fetched or rebuilt. The server answer is
     * unreadable, so the health is UNKNOWN (reachable) and only the budget decides.
     */
    @Test
    public void aBudgetClearedSinceTheEscalationAsksNobody() {
        final FakeShellPort f = heldPort(storeWith(b -> b));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsStallAlert.offerTheStallChoiceOffThread(MlsConfig.defaults(), f.port(), log, "p:+2", 3,
                /*exhaustedWhenAsked=*/ true);
        assertTrue(String.join("\n", log.lines), log.said("I", "is no longer spent"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("fetchServerPack(")));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("raiseStall(")));
    }

    /** The same conversation with the budget still spent goes on to the behind checks. */
    @Test
    public void aBudgetStillSpentInTheFreshRecordGoesOnToTheBehindChecks() {
        final int limit = MlsConfig.defaults().selfHealRetryLimit;
        final FakeShellPort f = heldPort(storeWith(b -> b.selfHealBudget(
                new MlsConversationRecord.SelfHealBudget(limit, System.currentTimeMillis(),
                        false))));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsStallAlert.offerTheStallChoiceOffThread(MlsConfig.defaults(), f.port(), log, "p:+2",
                limit, /*exhaustedWhenAsked=*/ false);
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("fetchServerPack(")));
        assertTrue(String.join("\n", log.lines), log.said("I", "budget exhausted on p:***"));
    }

    /** A held group, a store, and a server era look that answers nothing (health UNKNOWN). */
    private static FakeShellPort heldPort(final FakeRecords store) {
        return SplitFixtures.port(store).returns("resolveInbound", "p:+2")
                .returns("lookServerEraEpoch", Look.asked(null))
                .returns("fetchServerPack", Look.refusedByLedger("no"))
                .on("raiseStall", a -> null);
    }

    /** No group anywhere; the server holds era 3; every rebuild is refused by the guard. */
    private static FakeShellPort strandedPort() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "p:+2")
                .returns("getGroup", null)
                .returns("lookServerEraEpoch", Look.asked(new long[] {3, 0}))
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .on("raiseStall", a -> null);
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "claimRebuildEpisode", false));
        return f;
    }


    @Test
    public void theStallChoiceIsOfferedOffThread() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("offThread", f.stub(MlsOffThread.class, "run", null));
        MlsStallAlert.offerTheStallChoice(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, KEY,
                null);
        assertTrue(f.calls.contains("MlsOffThread.run"));
    }
}
