/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static org.junit.Assert.assertArrayEquals;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.RosterClaim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
import org.junit.Test;

public final class MlsFloorRebuildSplitTest {


    private static final long NOW_S = System.currentTimeMillis() / 1000L;

    /**
     * A port that claims {@code kp} for every member; its session dates each at {@code
     * certNotAfter}.
     */
    private static FakeShellPort claimPort(final Claim<byte[]> claim, final long certNotAfter) {
        final FakeShellPort f =
                new FakeShellPort().on("claimOne", a -> claim).returns("keyPackageUsable", true);
        f.returns("session", f.stub(MlsSession.class, "inspectKeyPackage",
                new MlsSession.KeyPackageInfo(false, 0L, "", 0, null, 0L, certNotAfter)));
        return f;
    }

    @Test
    public void aRosterAboveTheFloorIsClaimedWhole() {
        final RosterClaim c = MlsFloorRebuild.claimRosterForAdvance(MlsConfig.defaults(),
                claimPort(Claim.asked(new byte[] {1}), NOW_S + 90 * 86400L).port(), MlsLogSink.NONE,
                KEY, java.util.Arrays.asList("+2", "+3"), true);
        assertEquals(2, c.kps.size());
    }

    @Test
    public void aStaleRosterRefusesARebuildButNotAnOrdinaryAdvance() {
        final FakeShellPort f = claimPort(Claim.asked(new byte[] {1}), NOW_S + 86400L);
        assertEquals(MlsFloorRebuild.ERA_ADVANCE_ROSTER_NOT_READY,
                MlsFloorRebuild.claimRosterForAdvance(
                MlsConfig.defaults(), f.port(), MlsLogSink.NONE, KEY, java.util.Arrays.asList("+2"),
                true).failure);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(1, MlsFloorRebuild.claimRosterForAdvance(MlsConfig.defaults(), f.port(), log,
                KEY, java.util.Arrays.asList("+2"), false).kps.size());
        assertTrue(log.said("W", "PROCEEDING"));
    }

    @Test
    public void ourOwnLedgerRefusingAClaimAbortsWithoutAsking() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsFloorRebuild.claimRosterForAdvance(MlsConfig.defaults(),
                claimPort(Claim.refusedByLedger("budget"), 0L).port(), log, KEY,
                java.util.Arrays.asList("+2"), true).failure);
        assertTrue(log.said("W", "era advance ABORTED"));
    }


    /** Leaf 0 is us with a fresh certificate; leaf 1 is a peer inside the floor. */
    private static FakeShellPort wedgedPort(final ConvState cs, final int era) {
        final java.util.Map<Integer, long[]> v = new java.util.HashMap<>();
        v.put(0, new long[] {NOW_S - 86400L, NOW_S + 90 * 86400L});
        v.put(1, new long[] {NOW_S - 86400L, NOW_S + 86400L});
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("conv", cs)
                .returns("eraAdvanceLeverRefusal", null).returns("eraAdvance", era);
        f.returns("session", f.stub(MlsSession.class, "memberValidity", v,
                "selfLeafStatus", new MlsSelfLeafStatus(0, NOW_S - 86400L, NOW_S + 90 * 86400L,
                        NOW_S - 86400L, NOW_S + 90 * 86400L, false)));
        return f;
    }

    @Test
    public void aWedgedGroupIsRebuiltOncePerCertificateUnlessTheLeverIgnoresTheMarker() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = wedgedPort(cs, 7);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(7, MlsFloorRebuild.floorRebuild(MlsConfig.defaults(), f.port(), log, KEY,
                grp(), "grp", "+2", "test", false));
        assertTrue(log.said("I", "SUCCEEDED → era 7"));
        final FakeShellPort.Log again = new FakeShellPort.Log();
        assertEquals(-1, MlsFloorRebuild.floorRebuild(MlsConfig.defaults(), f.port(), again, KEY,
                grp(), "grp", "+2", "test", false));
        assertTrue(again.said("I", "already attempted"));
        assertEquals(7, MlsFloorRebuild.floorRebuild(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, KEY, grp(), "grp", "+2", "lever", true));
    }

    @Test
    public void theDeclinesThatNeverAdvance() {
        final FakeShellPort down =
                SplitFixtures.port(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)));
        down.returns("session", down.stub(MlsSession.class));
        final FakeShellPort.Log ed1 = new FakeShellPort.Log();
        assertEquals(-1, MlsFloorRebuild.floorRebuild(MlsConfig.defaults(), down.port(), ed1, KEY,
                grp(), "grp", "+2", "test", false));
        assertTrue(ed1.said("I", "INVARIANT ED-1"));
        final FakeShellPort blind = SplitFixtures.port(storeWith(b -> b));
        blind.returns("session", blind.stub(MlsSession.class, "selfLeafStatus", null));
        final FakeShellPort.Log unevaluable = new FakeShellPort.Log();
        assertEquals(-1, MlsFloorRebuild.floorRebuild(MlsConfig.defaults(), blind.port(),
                unevaluable, KEY, grp(), "grp", "+2", "test", false));
        assertTrue(unevaluable.said("W", "UN-EVALUABLE"));
        final FakeShellPort refused =
                wedgedPort(new ConvState(), 7).returns("eraAdvanceLeverRefusal", "no");
        assertEquals(-1, MlsFloorRebuild.floorRebuild(MlsConfig.defaults(), refused.port(),
                MlsLogSink.NONE, KEY, grp(), "grp", "+2", "test", false));
    }


    /** A config with the automatic floor rebuild ON; every other key at its default. */
    private static MlsConfig floorOn() {
        return MlsConfig.from((MlsConfig.Source) java.lang.reflect.Proxy.newProxyInstance(
                MlsConfig.Source.class.getClassLoader(), new Class<?>[] {MlsConfig.Source.class},
                (p, m, a) -> MlsConfig.KEY_FLOOR_REBUILD.equals(a[0]) ? (Object) 1 : a[1]));
    }

    @Test
    public void withTheRebuildOffAWedgedGroupIsOnlyReportedAndWithItOnItIsRebuilt() {
        final java.util.Map<Integer, long[]> v = new java.util.HashMap<>();
        v.put(1, new long[] {NOW_S - 86400L, NOW_S + 86400L});
        final MlsCredentialFloor.Report wedged = MlsCredentialFloor.classify(v,
                java.util.Collections.emptyMap(), NOW_S, MlsConfig.defaults().kpMinRemainingDays);
        final ConvState cs = new ConvState();
        final FakeShellPort.Log off = new FakeShellPort.Log();
        MlsFloorRebuild.maybeFloorRebuild(MlsConfig.defaults(), wedgedPort(cs, 7).port(), off, KEY,
                grp(), "grp", "+2", wedged, "test");
        assertTrue(off.said("W", "is off, so nothing automatic will attempt one"));
        assertEquals("the off path never reaches the rebuild", 0L, cs.floorRebuildAttemptedFor);
        MlsFloorRebuild.maybeFloorRebuild(floorOn(), wedgedPort(cs, 7).port(), MlsLogSink.NONE, KEY,
                grp(), "grp", "+2", wedged, "test");
        assertTrue(cs.floorRebuildAttemptedFor > 0L);
    }


    /**
     * With the rebuild off, a group wedged only by OUR leaf is not reported as needing a rebuild:
     * the §9.5.3 Self-Update is its repair, and the old line sent readers looking for a rebuild.
     */
    @Test
    public void withTheRebuildOffOurOwnStaleLeafIsReportedAsASelfUpdateNotARebuild() {
        final java.util.Map<Integer, long[]> v = new java.util.HashMap<>();
        v.put(0, new long[] {NOW_S - 86400L, NOW_S + 86400L});
        v.put(1, new long[] {NOW_S - 86400L, NOW_S + 90 * 86400L});
        final MlsCredentialFloor.Report ours = MlsCredentialFloor.classify(v,
                java.util.Collections.emptyMap(), NOW_S, MlsConfig.defaults().kpMinRemainingDays);
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).returns("conv", new ConvState());
        f.returns("session", f.stub(MlsSession.class, "memberValidity", v,
                "selfLeafStatus", new MlsSelfLeafStatus(0, NOW_S - 86400L, NOW_S + 86400L,
                        NOW_S - 86400L, NOW_S + 90 * 86400L, true)));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFloorRebuild.maybeFloorRebuild(MlsConfig.defaults(), f.port(), log, KEY, grp(), "grp",
                "+2", ours, "test");
        assertTrue(log.said("W", "only OUR OWN leaf is inside the"));
        assertFalse(log.said("W", "the only repair that exists"));
    }


    @Test
    public void theDebugLeverRebuildsIgnoringTheMarkerOrSaysThereIsNoGroup() {
        final FakeShellPort f = wedgedPort(new ConvState(), 7).returns("resolveInbound", KEY);
        assertEquals(7, MlsFloorRebuild.debugFloorRebuild(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, "grp", "+2"));
        assertEquals(7, MlsFloorRebuild.debugFloorRebuild(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, "grp", "+2"));
        f.returns("getGroup", null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsFloorRebuild.debugFloorRebuild(MlsConfig.defaults(), f.port(), log,
                "grp", "+2"));
        assertTrue(log.said("W", "no MLS group for " + LogMask.number("+2")));
    }


    @Test
    public void theLeverRefusalReadsBothDebugLevers() {
        final FakeShellPort f = new FakeShellPort().returns("sysprops", new FakeSysProps());
        assertEquals(MlsFloorRebuild.leverRefusal(true, "off", KEY),
                MlsFloorRebuild.eraAdvanceLeverRefusal(f.port(), KEY));
    }
}
