/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.RosterClaim;
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
import com.android.messaging.rcs.log.LogMask;
import org.junit.Test;

public final class MlsTransportDiagnosticsSplitTest {

    private static Group group(final byte[] id) {
        final Group g = new Group();
        g.groupId = id;
        return g;
    }

    private static byte[] packed(final String... records) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (final String r : records) {
            final byte[] b = r.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.write(0); o.write(0); o.write(0); o.write(b.length);
            o.write(b, 0, b.length);
        }
        return o.toByteArray();
    }

    @Test
    public void logIdCandidatesLogsOnlyWhenThereIsALine() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsTransportDiagnostics.logIdCandidates(log, null, null, null);
        assertTrue("nothing to say, nothing said", log.lines.isEmpty());
        MlsTransportDiagnostics.logIdCandidates(log, null, "cpim-1", null);
        assertEquals(1, log.lines.size());
        assertTrue(log.lines.get(0).startsWith("I MlsTransportDiagnostics: "));
    }


    @Test
    public void probeAnchorReadsUnderTheLockThenAsksTheServerAndReportsARefusal() {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .on("lock", a -> null).on("unlock", a -> null)
                .returns("getGroup", group(new byte[] {1}))
                .returns("lookMissedCommits", Look.asked(new byte[0]));
        f.returns("session",
                f.stub(MlsSession.class, "eraEpoch", null, "epochAuth", new byte[] {1, 2, 3}));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertSame(AnchorProbe.PROBED,
                MlsTransportDiagnostics.probeAnchor(f.port(), log, "grp", null));
        assertTrue(log.said("I", "auth=010203/3B"));
        assertTrue(f.calls.indexOf("unlock(g:grp)") < f.calls.indexOf(f.calls.stream()
                .filter(c -> c.startsWith("lookMissedCommits")).findFirst().get()));
        f.returns("lookMissedCommits", Look.refusedByLedger("no"));
        assertSame(AnchorProbe.REFUSED_BY_LEDGER, MlsTransportDiagnostics.probeAnchor(f.port(),
                MlsLogSink.NONE, "grp", null));
        assertSame(AnchorProbe.NO_LOCAL_STATE, MlsTransportDiagnostics.probeAnchor(
                new FakeShellPort()
                .returns("ensureSession", false).port(), MlsLogSink.NONE, "grp", null));
    }


    @Test
    public void seedUsageCounterSeedsBothCountersUnderTheLockAndPersists() {
        final Group g = group(new byte[] {1});
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .on("lock", a -> null).on("unlock", a -> null).returns("getGroup", g)
                .on("putGroup", a -> null);
        assertTrue(
                MlsTransportDiagnostics.seedUsageCounter(f.port(), MlsLogSink.NONE, "grp", null));
        assertEquals(MlsRekeyPolicy.seedForImminentRotation(), g.sendsSinceLeafRotation);
        assertTrue(g.sendsThisEpoch >= MlsRekeyPolicy.seedForImminentRotation());
        assertTrue(f.calls.indexOf("unlock(g:grp)") > f.calls.stream()
                .filter(c -> c.startsWith("putGroup")).map(f.calls::indexOf).findFirst().get());
    }


    @Test
    public void probeProcessResultsDemuxesByContextAndNamesTheGoverningAction() {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", "g:x").returns("getGroup", null);
        f.returns("session", f.stub(MlsSession.class, "processResults", java.util.Arrays.asList(
                new MlsEngineResult("ctx", MlsProcStatus.APP, 0, new byte[0], new byte[] {1}),
                new MlsEngineResult("other", MlsProcStatus.COMMIT, 0, new byte[0],
                        new byte[] {2}))));
        final String out = MlsTransportDiagnostics.probeProcessResults(f.port(), "grp", null,
                new byte[] {7}, "ctx");
        assertTrue(out, out.startsWith("raw=2 "));
        assertTrue(out, out.contains("ours=1"));
        assertTrue(out, out.contains("others=[other]"));
        assertEquals("no session", MlsTransportDiagnostics.probeProcessResults(new FakeShellPort()
                .returns("ensureSession", false).port(), "grp", null, new byte[0], "ctx"));
    }


    @Test
    public void dumpMemberValidityCountsExpiredAndUnreadableLeaves() {
        final Group g = new Group();
        g.groupId = new byte[] {1};
        final long now = System.currentTimeMillis() / 1000L;
        final java.util.Map<Integer, long[]> v = new java.util.TreeMap<>();
        v.put(0, new long[] {now - 86400L, now - 10});
        v.put(1, new long[] {0L, 0L});
        v.put(2, new long[] {now - 86400L, now + 90 * 86400L});
        final FakeShellPort f =
                new FakeShellPort().returns("ensureSession", true).returns("getGroup", g);
        f.returns("session", f.stub(MlsSession.class, "memberValidity", v, "memberParticipantKeys",
                null, "selfLeafStatus", null));
        final String out = MlsTransportDiagnostics.dumpMemberValidity(MlsConfig.defaults(),
                f.port(), MlsLogSink.NONE, "grp", null);
        assertTrue(out, out.startsWith("members=3 expired=1 unreadable=1"));
        assertTrue(out, out.contains("leaf=1 UNREADABLE"));
        assertTrue(out, out.contains("selfLeaf=UNREADABLE"));
        assertEquals("no group for g:grp", MlsTransportDiagnostics.dumpMemberValidity(
                MlsConfig.defaults(),
                new FakeShellPort().returns("ensureSession", true).returns("getGroup", null).port(),
                MlsLogSink.NONE, "grp", null));
    }


    @Test
    public void dumpAdvancerElectionNeedsARosterToEvaluateAnything() {
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).returns("resolveInbound", KEY);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        assertTrue(MlsTransportDiagnostics.dumpAdvancerElection(MlsConfig.defaults(), f.port(),
                MlsLogSink.NONE, "grp", null).contains("no stored roster"));
        final FakeShellPort r = SplitFixtures.port(
                storeWith(b -> b.putMembership(0, 1, new String[] {"+1", "+2"})))
                .returns("resolveInbound", KEY).returns("conv", new ConvState());
        r.returns("session", r.stub(MlsSession.class, "eraEpoch", new byte[12]));
        final String out = MlsTransportDiagnostics.dumpAdvancerElection(MlsConfig.defaults(),
                r.port(), MlsLogSink.NONE, "grp", null);
        assertTrue(out, out.startsWith("g:grp: self=" + LogMask.number("+1") + " order="));
        assertTrue(out, out.contains("ledger=EMPTY"));
    }


    @Test
    public void theContinuityLeverWritesThenReadsBack() {
        final FakeRecords s = storeWith(b -> b);
        final FakeShellPort f =
                SplitFixtures.port(s).on("continuityTokenFor", a -> rec(s).continuityToken);
        assertEquals("continuity g:grp: wrote=true readback=2B match=true",
                MlsTransportDiagnostics.debugInjectContinuityToken(f.port(), MlsLogSink.NONE, "grp",
                        null, new byte[] {1, 2}));
        assertTrue(MlsTransportDiagnostics.debugInjectContinuityToken(new FakeShellPort()
                .returns("ensureSession", false).port(), MlsLogSink.NONE, "grp", null, null)
                .startsWith("continuity: no MLS session"));
    }


    @Test
    public void theKnownGroupInfoExtensionTypesAreTheOnesWeCanName() {
        assertTrue(MlsTransportDiagnostics.isKnownExtType(0xF001));
        assertTrue(MlsTransportDiagnostics.isKnownExtType(0x000A));
        assertFalse(MlsTransportDiagnostics.isKnownExtType(0xF00F));
    }


    @Test
    public void theGroupExtensionDumpSaysWhenThereIsNoGroupOrNoServerGroupInfo() {
        final FakeShellPort none = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", KEY)
                .returns("getGroup", null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsTransportDiagnostics.dumpGroupExtensions(none.port(), log, "grp", "+2");
        assertTrue(log.said("W", "no group for " + LogMask.number("+2")));
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", KEY)
                .returns("getGroup", grp()).returns("fetchServerPack", Look.asked(null));
        f.returns("session", f.stub(MlsSession.class, "groupExt", new byte[] {1, 2}));
        final FakeShellPort.Log dump = new FakeShellPort.Log();
        MlsTransportDiagnostics.dumpGroupExtensions(f.port(), dump, "grp", "+2");
        assertTrue(dump.said("I", "0xF001=2B"));
        assertTrue(dump.said("W", "SERVER: no GroupInfo returned"));
    }


    @Test
    public void onlyTheServerExtensionTypesWeCannotNameAreReported() {
        final FakeShellPort f = new FakeShellPort();
        final MlsSession session = f.stub(MlsSession.class, "groupInfoExtTypes",
                new byte[] {(byte) 0xF0, 0x01, 0, 2, (byte) 0xF0, 0x0F, 0, 5});
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(java.util.Collections.singletonList(0xF00F),
                MlsTransportDiagnostics.reportUnknownServerExtTypes(session, log, KEY,
                        new byte[] {1}));
        assertTrue(log.said("W", "0xF00F (5B)"));
        assertTrue(MlsTransportDiagnostics.reportUnknownServerExtTypes(session, MlsLogSink.NONE,
                KEY, new byte[0]).isEmpty());
    }


    /** {@code [len][part]...}, the server pack's framing. */
    private static byte[] packOf(final byte[]... parts) {
        return MlsArtifactBundle.joinLenPrefixed(java.util.Arrays.asList(parts));
    }

    private static FakeShellPort probe(final Look<byte[]> look) {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", grp())
                .returns("fetchServerPack", look);
        f.returns("session", f.stub(MlsSession.class,
                "treeMemberValidity", java.util.Collections.<MlsTreeLeaf>emptyList()));
        return f;
    }

    private static String dump(final FakeShellPort f) {
        return MlsTransportDiagnostics.dumpServerValidity(MlsConfig.defaults(), f.port(), "grp",
                "+2");
    }

    @Test
    public void theValidityProbeNeverReadsCouldNotLookAsNothingThere() {
        assertEquals("no session", dump(new FakeShellPort().returns("ensureSession", false)));
        assertTrue(
                dump(probe(null).returns("getGroup", null)).startsWith("NO LOCAL GROUP for g:grp"));
        assertTrue(dump(probe(Look.refusedByLedger("budget")))
                .startsWith("FETCH REFUSED by the ledger (budget)"));
        assertTrue(dump(probe(Look.asked(null))).startsWith("FETCH FAILED"));
        assertTrue(dump(probe(Look.asked(packOf(new byte[] {7}))))
                .contains("NO RATCHET TREE IN THE PACK"));
        assertTrue(dump(probe(Look.asked(packOf(new byte[] {7}, new byte[] {8, 9}))))
                .contains("TREE WOULD NOT PARSE (2B)"));
    }


    private static java.util.Map<Integer, MlsParticipantKeyResync.Leaf> leaves(
            final String... msisdns) {
        final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> m =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < msisdns.length; i++) {
            m.put(i, new MlsParticipantKeyResync.Leaf(i, msisdns[i],
                    "aabbccddeeff00112233445566778899"));
        }
        return m;
    }

    private static FakeShellPort engine(
            final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> leaves) {
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", grp())
                .on("lock", a -> null).on("unlock", a -> null);
        f.returns("openMlsSession", f.stub(MlsSession.class, "memberParticipantKeys", leaves));
        return f;
    }

    @Test
    public void theParticipantKeyDumpNamesEachLeafAndSaysWhyWhenItCannot() {
        final String d = MlsTransportDiagnostics.dumpMemberParticipantKeys(
                engine(leaves("+2")).port(), "grp", "+2");
        assertTrue(d, d.startsWith("1 leaf/leaves"));
        assertTrue(d, d.contains("[0] +2  participantKey=aabbccdd…"));
        assertEquals("engine is not OpenMLS", MlsTransportDiagnostics.dumpMemberParticipantKeys(
                new FakeShellPort().returns("ensureSession", true).returns("openMlsSession", null)
                .port(), "grp", "+2"));
        assertEquals("no group", MlsTransportDiagnostics.dumpMemberParticipantKeys(
                engine(leaves("+2")).returns("getGroup", null).port(), "grp", "+2"));
    }


    @Test
    public void theDebugEraEpochLookIsTheChargedLookOrNothing() {
        assertArrayEquals(new long[] {3, 4}, MlsTransportDiagnostics.debugServerEraEpoch(
                new FakeShellPort()
                .returns("lookServerEraEpoch", Look.asked(new long[] {3, 4})).port(), "grp", "+2"));
        assertNull(MlsTransportDiagnostics.debugServerEraEpoch(new FakeShellPort()
                .returns("lookServerEraEpoch", Look.refusedByLedger("no")).port(), "grp", "+2"));
    }


    @Test
    public void theCooldownProbeAsksBothGuardsAndSaysWhatEachAnswered() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "rebuildEpisodeAgeMs", 5L,
                "claimRebuildEpisode", true,
                "rebuildEpisodeWindowMs", 60000L, "claimReestablishAttempt", false,
                "reestablishCooldownMs", 30000L));
        final String d = MlsTransportDiagnostics.debugProbeDurableCooldowns(f.port(), "grp", "+2");
        assertTrue(d, d.contains("g:grp") && d.contains("60000") && d.contains("30000"));
        assertTrue(f.calls.contains("MlsPeerGuards.claimRebuildEpisode"));
        assertTrue(f.calls.contains("MlsPeerGuards.claimReestablishAttempt"));
    }
}
