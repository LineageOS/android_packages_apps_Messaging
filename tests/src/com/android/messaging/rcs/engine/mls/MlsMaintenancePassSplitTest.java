/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ClaimOutcomeSink;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import java.util.concurrent.atomic.AtomicBoolean;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsMaintenancePassSplitTest {


    /** {@code [len][GroupInfo][len][tree][len]member...} — the server pack the look returns. */
    private static byte[] pack(final String... members) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        final java.util.List<byte[]> parts = new java.util.ArrayList<>();
        parts.add(new byte[] {7});
        parts.add(new byte[] {8});
        for (final String m : members) parts.add(
                m.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (final byte[] p : parts) {
            o.write(0); o.write(0); o.write(0); o.write(p.length);
            o.write(p, 0, p.length);
        }
        return o.toByteArray();
    }

    /**
     * A pass over {@code grp}: the look returns {@code look}; the session reads nothing it need
     * not.
     */
    private static FakeShellPort passPort(final FakeRecords s, final Look<byte[]> look,
            final EraReconcile rec) {
        return passPort(s, look, rec, new byte[0]);
    }

    private static FakeShellPort passPort(final FakeRecords s, final Look<byte[]> look,
            final EraReconcile rec, final byte[] serverExtTypes) {
        final FakeShellPort f = SplitFixtures.port(s).returns("resolveInbound", KEY)
                .returns("conv", new ConvState())
                .returns("fetchServerPack", look).returns("quarantineIfAheadOfServer", rec)
                .returns("eraAdvance", 5);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12], "selfLeafStatus",
                null, "memberValidity", null, "groupInfoExtTypes", serverExtTypes));
        return f;
    }

    private static final int KEYS_EXT = 0xF0AB;

    /**
     * A config naming the metadata-keys request extension, which NEW_MEMBERS needs. Only a debug
     * build reads the override.
     */
    private static final MlsConfig CFG = MlsConfig.from((
            MlsConfig.Source) java.lang.reflect.Proxy.newProxyInstance(
            MlsConfig.Source.class.getClassLoader(), new Class<?>[] {MlsConfig.Source.class},
            (p, m, a) -> MlsConfig.KEY_METADATA_KEYS_EXT.equals(a[0]) ? (Object) KEYS_EXT
                    : "ro.debuggable".equals(a[0]) ? (Object) 1 : a[1]));

    private static MlsMaintenancePolicy.Refresh pass(final FakeShellPort f, final MlsLogSink log) {
        return MlsMaintenancePass.runMaintenanceOnce(CFG, f.port(), log, "grp", "+2", "test");
    }

    @Test
    public void aNewMemberOnTheServerAdvancesTheEraAndRecordsTheServerRoster() {
        final FakeRecords s = storeWith(b -> b.putMembership(0, 0L, new String[] {"+2", "+1"}));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertSame(MlsMaintenancePolicy.Refresh.NEW_MEMBERS,
                pass(passPort(s, Look.asked(pack("+1", "+2", "+3")), EraReconcile.unknown(),
                        new byte[] {(byte) 0xF0, (byte) 0xAB, 0, 1}), log));
        assertArrayEquals("written only once the advance returned an era", new String[] {"+2", "+3",
                "+1"}, rec(s).membershipHistory.get(0).get(0L));
    }

    @Test
    public void theSameRosterWarrantsNothing() {
        final FakeRecords s = storeWith(b -> b.putMembership(0, 0L, new String[] {"+2", "+1"}));
        final FakeShellPort f = passPort(s, Look.asked(pack("+1", "+2")), EraReconcile.unknown(),
                new byte[] {(byte) 0xF0, (byte) 0xAB, 0, 1})
                .on("eraAdvance",
                        a -> { throw new AssertionError("nothing warranted an advance"); });
        assertSame(MlsMaintenancePolicy.Refresh.NOT_NEEDED, pass(f, MlsLogSink.NONE));
    }

    @Test
    public void theDeclinesThatNeverReachTheServerComparison() {
        final FakeShellPort.Log noGroup = new FakeShellPort.Log();
        assertSame(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                pass(passPort(storeWith(b -> b), null, null).returns("getGroup", null), noGroup));
        final FakeShellPort.Log refused = new FakeShellPort.Log();
        assertSame(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                pass(passPort(storeWith(b -> b), Look.refusedByLedger("budget"), null), refused));
        final FakeShellPort.Log empty = new FakeShellPort.Log();
        assertSame(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                pass(passPort(storeWith(b -> b), Look.asked(null), null), empty));
        final FakeShellPort.Log dropped = new FakeShellPort.Log();
        assertSame(MlsMaintenancePolicy.Refresh.NOT_NEEDED, pass(passPort(storeWith(b -> b),
                Look.asked(pack("+1", "+2", "+3")), EraReconcile.dropped(new long[] {1, 0})),
                dropped));
    }


    @Test
    public void theSweepMaintainsEachGroupOnceAndDeclinesWhatItMustNot() {
        final FakeRecords s = storeWith(b -> b);
        s.putAlias("+1", KEY, GID);
        s.putAlias("+1", "g:alias", GID);
        final FakeShellPort f = SplitFixtures.port(s).returns("ensureSession", false);
        final java.util.Set<String> seen = new java.util.HashSet<>();
        assertTrue(MlsMaintenancePass.sweepOne(CFG, f.port(), MlsLogSink.NONE, "+1", KEY, "test",
                seen));
        assertFalse("the same MLS group under a second id is not maintained twice",
                MlsMaintenancePass.sweepOne(CFG, f.port(), MlsLogSink.NONE, "+1", "g:alias", "test",
                        seen));
        assertFalse(MlsMaintenancePass.sweepOne(CFG, f.port(), MlsLogSink.NONE, "+1", "g:none",
                "test", new java.util.HashSet<>()));
        s.unreadable = "corrupt";
        final FakeShellPort.Log unreadable = new FakeShellPort.Log();
        assertFalse(MlsMaintenancePass.sweepOne(CFG, f.port(), unreadable, "+1", KEY, "test",
                new java.util.HashSet<>()));
        assertTrue(unreadable.said("W", "is unreadable (corrupt)"));
    }

    @Test
    public void theSweepNeverMaintainsADowngradedConversation() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS));
        s.putAlias("+1", KEY, GID);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsMaintenancePass.sweepOne(CFG, SplitFixtures.port(s).port(), log, "+1", KEY,
                "test", new java.util.HashSet<>()));
        assertTrue(log.said("I", "INVARIANT ED-1"));
    }


    /** The page's own session check answers yes; every pass it then runs declines at once. */
    private static FakeShellPort sweepPort(final FakeRecords s, final FakePrefs p,
            final AtomicBoolean inFlight) {
        final int[] n = {0};
        return SplitFixtures.port(s).returns("prefs", p).returns("sweepInFlight", inFlight)
                .on("ensureSession", a -> n[0]++ == 0);
    }

    @Test
    public void aSweepPageMaintainsEachGroupOnceAndDisarmsAtTheEnd() {
        final FakeRecords s = storeWith(b -> b);
        s.putAlias("+1", "g:a", GID);
        s.putAlias("+1", "g:b", GID);
        final FakePrefs p = new FakePrefs();
        final AtomicBoolean inFlight = new AtomicBoolean();
        assertEquals("not armed", 0, MlsMaintenancePass.sweepOnePage(CFG,
                sweepPort(s, p, inFlight).port(), MlsLogSink.NONE, "test"));
        p.values.put(MlsMaintenancePass.PREF_SWEEP_ARMED, true);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(1, MlsMaintenancePass.sweepOnePage(CFG, sweepPort(s, p, inFlight).port(), log,
                "test"));
        assertTrue(log.said("I", "group sweep COMPLETE"));
        assertEquals(false, p.values.get(MlsMaintenancePass.PREF_SWEEP_ARMED));
        assertFalse(p.values.containsKey(MlsMaintenancePass.PREF_SWEEP_CURSOR));
        assertFalse("the in-flight flag is released", inFlight.get());
        p.values.put(MlsMaintenancePass.PREF_SWEEP_ARMED, true);
        inFlight.set(true);
        assertEquals("another page is in flight", 0, MlsMaintenancePass.sweepOnePage(CFG,
                sweepPort(s, p, inFlight).port(), MlsLogSink.NONE, "test"));
    }


    /** The same config with the group sweep off. */
    private static final MlsConfig SWEEP_OFF = MlsConfig.from((
            MlsConfig.Source) java.lang.reflect.Proxy.newProxyInstance(
            MlsConfig.Source.class.getClassLoader(), new Class<?>[] {MlsConfig.Source.class},
            (p, m, a) -> MlsConfig.KEY_GROUP_SWEEP.equals(a[0]) ? (Object) 0 : a[1]));

    @Test
    public void continuingTheSweepRunsAPageOffThreadOnlyWhenArmedAndIdle() throws Exception {
        final FakeShellPort off = new FakeShellPort();
        MlsMaintenancePass.continueGroupSweep(SWEEP_OFF, off.port(), MlsLogSink.NONE, "test");
        assertTrue("off reads nothing", off.calls.isEmpty());
        final FakePrefs p = new FakePrefs();
        final FakeShellPort disarmed = new FakeShellPort().returns("prefs", p);
        MlsMaintenancePass.continueGroupSweep(CFG, disarmed.port(), MlsLogSink.NONE, "test");
        assertFalse(disarmed.calls.contains("sweepInFlight()"));
        p.values.put(MlsMaintenancePass.PREF_SWEEP_ARMED, true);
        final FakeShellPort busy = new FakeShellPort().returns("prefs", p)
                .returns("sweepInFlight", new AtomicBoolean(true));
        MlsMaintenancePass.continueGroupSweep(CFG, busy.port(), MlsLogSink.NONE, "test");
        assertEquals("a page in flight is not joined by a second", 1,
                java.util.Collections.frequency(busy.calls, "sweepInFlight()"));
        final java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        final FakeShellPort idle = new FakeShellPort().returns("prefs", p)
                .returns("sweepInFlight", new AtomicBoolean())
                .on("ensureSession", a -> { ran.countDown(); return false; });
        MlsMaintenancePass.continueGroupSweep(CFG, idle.port(), MlsLogSink.NONE, "test");
        assertTrue("armed and idle: a page runs, on its own thread",
                ran.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }


    @Test
    public void armingTheSweepIsDurableIdempotentAndRefusedWhenOff() {
        final FakeShellPort.Log off = new FakeShellPort.Log();
        MlsMaintenancePass.armGroupSweep(SWEEP_OFF, new FakeShellPort().port(), off, "test");
        assertTrue(off.said("I", "the group sweep is OFF"));
        final FakePrefs p = new FakePrefs();
        final FakeShellPort f = new FakeShellPort().returns("prefs", p)
                .returns("sweepInFlight", new AtomicBoolean(true));
        final FakeShellPort.Log arm = new FakeShellPort.Log();
        MlsMaintenancePass.armGroupSweep(CFG, f.port(), arm, "test");
        assertEquals(true, p.values.get(MlsMaintenancePass.PREF_SWEEP_ARMED));
        assertTrue(arm.said("I", "group sweep ARMED by"));
        final FakeShellPort.Log again = new FakeShellPort.Log();
        MlsMaintenancePass.armGroupSweep(CFG, f.port(), again, "test");
        assertTrue(again.said("I", "group sweep already armed"));
    }


    @Test
    public void everyMaintenancePassContinuesTheSweepEvenWhenItThrows() {
        final FakeShellPort quiet = new FakeShellPort().returns("ensureSession", false)
                .returns("prefs", new FakePrefs());
        assertSame(MlsMaintenancePolicy.Refresh.NOT_NEEDED,
                MlsMaintenancePass.runMaintenance(CFG, quiet.port(), MlsLogSink.NONE, "grp", "+2",
                        "test"));
        assertTrue(quiet.calls.contains("prefs()"));
        final FakeShellPort boom = new FakeShellPort()
                .on("ensureSession", a -> { throw new IllegalStateException("boom"); })
                .returns("prefs", new FakePrefs());
        try {
            MlsMaintenancePass.runMaintenance(CFG, boom.port(), MlsLogSink.NONE, "grp", "+2",
                    "test");
            fail("the pass's throw propagates");
        } catch (final IllegalStateException expected) {
            assertTrue("the continuation ran in the finally", boom.calls.contains("prefs()"));
        }
    }
}
