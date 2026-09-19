/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.port;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.AnchorProbe;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsAdvancerElectionSplitTest {


    @Test
    public void eraYieldMaxObservationsIsTheConfiguredLooks() {
        assertEquals(MlsConfig.defaults().eraYieldLooks,
                MlsAdvancerElection.eraYieldMaxObservations(MlsConfig.defaults()));
    }


    @Test
    public void clearEraYieldClearsAnExistingYieldAndCreatesNoState() {
        final ConvState cs = new ConvState();
        cs.eraYield = new MlsTransportTypes.EraYield(new MlsAppMessage.Moment(1, 1), 0, 0L,
                java.util.Collections.<String>emptyList());
        MlsAdvancerElection.clearEraYield(new FakeShellPort().returns("convIfAny", cs).port(),
                "g:x");
        assertNull(cs.eraYield);
        MlsAdvancerElection.clearEraYield(new FakeShellPort().returns("convIfAny", null).port(),
                "g:y");
    }


    @Test
    public void noteHeardFromSequencesPeersAndIgnoresOurselves() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("selfE164", "+1").returns("conv", cs);
        MlsAdvancerElection.noteHeardFrom(f.port(), "g:x", "+2");
        MlsAdvancerElection.noteHeardFrom(f.port(), "g:x", "+3");
        MlsAdvancerElection.noteHeardFrom(f.port(), "g:x", "+2");
        MlsAdvancerElection.noteHeardFrom(f.port(), "g:x", "+1");
        assertEquals(3L, cs.heardSeq);
        assertEquals(Long.valueOf(3), cs.heardAtSeq.get("+2"));
        assertEquals(Long.valueOf(2), cs.heardAtSeq.get("+3"));
        assertFalse(cs.heardAtSeq.containsKey("+1"));
    }


    @Test
    public void takeoverReachabilityIsReportedExactlyWhenTheReachSaysWhy() {
        final MlsConfig cfg = MlsConfig.defaults();
        final java.util.List<String> electorate = java.util.Arrays.asList("+1", "+2", "+3");
        for (final String self : electorate) {
            final MlsAdvancerElection.Decision d = MlsAdvancerElection.decide(self, electorate,
                    new java.util.HashMap<>(), 0L, cfg.eraYieldLooks);
            final MlsSelfHealPass.Reach reach = MlsSelfHealPass.reach(d, cfg.eraYieldLooks,
                    cfg.selfHealRetryLimit);
            final FakeShellPort.Log log = new FakeShellPort.Log();
            MlsAdvancerElection.reportTakeoverReachability(cfg, log, "g:x", "an era advance", d);
            assertEquals(self, reach.why != null, !log.lines.isEmpty());
            if (reach.why != null) {
                assertTrue(log.lines.get(0).startsWith(reach.isACollision ? "W " : "I "));
            }
        }
    }


    @Test
    public void relookLiveYieldEndsTheYieldWhenTheGroupMovedAndOtherwiseRecordsALook() {
        assertNull(MlsAdvancerElection.relookLiveYield(MlsConfig.defaults(), new FakeShellPort()
                .returns("convIfAny", null).port(), MlsLogSink.NONE, "g:x"));
        final ConvState cs = new ConvState();
        final MlsAppMessage.Moment at = new MlsAppMessage.Moment(1, 1);
        cs.eraYield = new EraYield(at, 0, 0L, java.util.Arrays.asList("+2", "+3"));
        final Group g = new Group();
        g.groupId = new byte[] {1};
        final byte[] moved = new byte[12];
        moved[3] = 2;                                   // era 2: the group moved
        final FakeShellPort f = new FakeShellPort().returns("convIfAny", cs).returns("getGroup", g)
                .returns("selfE164", "+1");
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", moved));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsAdvancerElection.relookLiveYield(MlsConfig.defaults(), f.port(), log, "g:x"));
        assertTrue(log.said("I", "the yield worked"));
        assertNull("the yield is cleared", cs.eraYield);
    }


    @Test
    public void theYieldStartsLooksAndTakesOverOnlyWhenExhausted() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = port(storeWith(b -> b)).returns("conv", cs);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        final java.util.List<String> electorate = java.util.Arrays.asList("+2");
        assertFalse(MlsAdvancerElection.eraYieldExhausted(f.port(), MlsLogSink.NONE, KEY, "x", 2,
                electorate));
        assertNotNull("the first call starts the yield", cs.eraYield);
        boolean tookOver = false;
        final MlsTelemetry t = f.stub(MlsTelemetry.class, "count",
                (java.util.function.Function<Object[], Object>) a -> null);
        f.returns("telemetry", t);
        for (int i = 0; i < 10 && !tookOver; i++) {
            tookOver = MlsAdvancerElection.eraYieldExhausted(f.port(), MlsLogSink.NONE, KEY, "x", 2,
                    electorate);
        }
        assertTrue("with the group unmoved, the look budget runs out", tookOver);
        assertNull("taking over clears the yield", cs.eraYield);
        assertTrue(f.calls.contains("MlsTelemetry.count"));
    }


    @Test
    public void theDesignatedAdvancerAdvancesAndEveryoneElseYields() {
        final java.util.List<String> members = java.util.Arrays.asList("+1", "+2", "+3");
        final String first = MlsAdvancerElection.order(members).get(0);
        final ConvState cs = new ConvState();
        cs.eraYield = new EraYield(new MlsAppMessage.Moment(0, 0), 1, 0L, members);
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).returns("conv", cs).returns("convIfAny", cs);
        assertTrue(MlsAdvancerElection.advanceOrYield(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, KEY, first, members, "an advance"));
        assertNull("the designated advancer clears any yield", cs.eraYield);
        final String last = MlsAdvancerElection.order(members).get(2);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        assertFalse(MlsAdvancerElection.advanceOrYield(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, KEY, last, members, "an advance"));
        assertNotNull("a non-advancer starts yielding", cs.eraYield);
    }


    @Test
    public void shouldAdvanceEraIsTheElectionBetweenUsAndThePeerUnderTheLock() {
        final ConvState cs = new ConvState();
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).returns("conv", cs).returns("convIfAny", cs);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        final java.util.List<String> pair = java.util.Arrays.asList("+1", "+2");
        final String first = MlsAdvancerElection.order(pair).get(0);
        final String other = first.equals("+1") ? "+2" : "+1";
        assertTrue(MlsAdvancerElection.shouldAdvanceEra(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, KEY, first, other));
        assertTrue(f.calls.indexOf("lock(g:grp)") < f.calls.indexOf("unlock(g:grp)"));
    }
}
