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
import com.android.messaging.rcs.log.LogMask;
import org.junit.Test;

public final class MlsServerBundleSplitTest {

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
    public void rosterFromPackSkipsTheTwoHeaderRecordsOurselvesEmptiesAndRepeats() {
        final FakeShellPort f = new FakeShellPort().returns("selfE164", "+1");
        assertEquals(java.util.Arrays.asList("+2", "+3"), MlsServerBundle.rosterFromPack(f.port(),
                packed("gi", "hdr", "+2", "+1", "", "+3", "+2")));
        assertNull(MlsServerBundle.rosterFromPack(f.port(), null));
    }


    @Test
    public void selfInServerRosterIsTriStateAndNullMeansTheRosterWasEmptyOrWeHaveNoIdentity() {
        final FakeShellPort f = new FakeShellPort().returns("selfE164", "+1");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(Boolean.TRUE, MlsServerBundle.selfInServerRoster(f.port(), log,
                packed("gi", "hdr", "+2", "+1")));
        assertTrue(log.said("I", "present=true"));
        assertEquals(Boolean.FALSE, MlsServerBundle.selfInServerRoster(f.port(), MlsLogSink.NONE,
                packed("gi", "hdr", "+2")));
        assertNull("an empty roster is not evidence we were removed",
                MlsServerBundle.selfInServerRoster(f.port(), MlsLogSink.NONE, packed("gi", "hdr")));
        assertNull(MlsServerBundle.selfInServerRoster(new FakeShellPort().returns("selfE164", "")
                .port(), MlsLogSink.NONE, packed("gi", "hdr", "+2")));
    }


    @Test
    public void serverPackForRebuildNamesEveryWayItCanComeBackEmpty() {
        assertEquals(MlsServerPackOutcome.NOT_A_GROUP, MlsServerBundle.serverPackForRebuild(
                new FakeShellPort().port(), MlsLogSink.NONE, null, "+1", "p:+1").outcome());
        assertEquals(MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH,
                MlsServerBundle.serverPackForRebuild(
                new FakeShellPort().returns("getGroup", null).port(), MlsLogSink.NONE, "grp", null,
                "g:grp").outcome());
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(MlsServerPackOutcome.REFUSED_BY_LEDGER, MlsServerBundle.serverPackForRebuild(
                new FakeShellPort().returns("getGroup", group(new byte[] {1}))
                        .returns("fetchServerPack", Look.refusedByLedger("rationed")).port(), log,
                "grp", null, "g:grp").outcome());
        assertTrue(log.said("W", "Nothing was asked of the server"));
        assertEquals(MlsServerPackOutcome.SERVER_HAD_NOTHING, MlsServerBundle.serverPackForRebuild(
                new FakeShellPort().returns("getGroup", group(new byte[] {1}))
                        .returns("fetchServerPack", Look.asked(null)).port(), MlsLogSink.NONE,
                "grp", null, "g:grp").outcome());
        final ServerPack ok = MlsServerBundle.serverPackForRebuild(new FakeShellPort()
                .returns("getGroup", group(new byte[] {1})).returns("fetchServerPack",
                        Look.asked(new byte[] {5})).port(), MlsLogSink.NONE, "grp", null, "g:grp");
        assertEquals(MlsServerPackOutcome.FETCHED, ok.outcome());
        assertArrayEquals(new byte[] {5}, ok.bytes());
        assertEquals(MlsServerPackOutcome.LOOK_FAILED, MlsServerBundle.serverPackForRebuild(
                new FakeShellPort().returns("getGroup", group(new byte[] {1})).on("fetchServerPack",
                        a -> { throw new IllegalStateException(); }).port(), MlsLogSink.NONE,
                "grp", null, "g:grp").outcome());
    }


    @Test
    public void rosterFloorReportNamesLeavesWhenItCanAndFallsBackToIndices() {
        final long now = System.currentTimeMillis() / 1000L;
        final java.util.Map<Integer, long[]> v = new java.util.HashMap<>();
        v.put(0, new long[] {now - 86400L, now + 3 * 86400L});
        final FakeShellPort f = new FakeShellPort();
        final MlsSession s = f.stub(MlsSession.class, "memberValidity", v,
                "memberParticipantKeys",
                (Function<Object[], Object>) a -> { throw new IllegalStateException(); });
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final MlsCredentialFloor.Report r = MlsServerBundle.rosterFloorReport(MlsConfig.defaults(),
                s, log, group(new byte[] {1}));
        assertNotNull(r);
        assertEquals(1, r.insideFloor);
        assertTrue(log.said("I", "reporting by leaf index instead"));
        assertNull(MlsServerBundle.rosterFloorReport(MlsConfig.defaults(), null, MlsLogSink.NONE,
                group(new byte[] {1})));
        assertNull("no validity is 'cannot tell', not an empty healthy roster",
                MlsServerBundle.rosterFloorReport(MlsConfig.defaults(),
                        f.stub(MlsSession.class, "memberValidity", null), MlsLogSink.NONE,
                        group(new byte[] {1})));
    }


    @Test
    public void auditRosterAfterPeerCommitIsSilentWhenHealthyAndNamesDriftWhenNot() {
        final long now = System.currentTimeMillis() / 1000L;
        final FakeShellPort f = new FakeShellPort();
        final java.util.Map<Integer, long[]> ok = new java.util.HashMap<>();
        ok.put(0, new long[] {now - 86400L, now + 40 * 86400L});
        final FakeShellPort.Log quiet = new FakeShellPort.Log();
        MlsServerBundle.auditRosterAfterPeerCommit(f.stub(MlsSession.class, "memberValidity", ok),
                quiet, group(new byte[] {1}), "m1", "+2");
        assertTrue(quiet.lines.isEmpty());
        final java.util.Map<Integer, long[]> bad = new java.util.HashMap<>(ok);
        bad.put(1, new long[] {now - 100 * 86400L, now - 2 * 86400L});
        bad.put(2, new long[] {0L, 0L});
        final FakeShellPort.Log loud = new FakeShellPort.Log();
        MlsServerBundle.auditRosterAfterPeerCommit(f.stub(MlsSession.class, "memberValidity", bad),
                loud, group(new byte[] {1}), "m1", "+2");
        assertTrue(loud.said("W", "leaf=1(EXPIRED"));
        assertTrue(loud.said("W", "leaf=2(UNREADABLE)"));
        assertTrue(loud.said("W", "credential DRIFT"));
    }


    @Test
    public void onlyAddsAndRemovesAreGatedByTheFloorAndOnlyWhenPrechecking() {
        final MlsConfig cfg = MlsConfig.defaults();
        final Group g = new Group();
        g.groupId = new byte[] {1};
        final long now = System.currentTimeMillis() / 1000L;
        final java.util.Map<Integer, long[]> v = new java.util.HashMap<>();
        v.put(0, new long[] {now - 86400L, now + 2 * 86400L});
        final FakeShellPort f = new FakeShellPort();
        final MlsSession s =
                f.stub(MlsSession.class, "memberValidity", v, "memberParticipantKeys", null);
        for (final Op op : Op.values()) {
            final FakeShellPort.Log log = new FakeShellPort.Log();
            final boolean allowed = MlsServerBundle.membershipChangeAllowedByFloor(cfg, s, log,
                    "g:x", g, op, "the change");
            final boolean gated = (op == Op.ADD || op == Op.REMOVE) && cfg.floorPrecheck;
            assertEquals(op.name(), !gated, allowed);
            if (gated) assertTrue(log.said("E", "REFUSING the change"));
        }
    }


    private static byte[] roster(final String... e164) {
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        final String[] all = new String[e164.length + 2];
        all[0] = "gi";
        all[1] = "hdr";
        System.arraycopy(e164, 0, all, 2, e164.length);
        for (final String r : all) {
            final byte[] b = r.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            o.write(0); o.write(0); o.write(0); o.write(b.length);
            o.write(b, 0, b.length);
        }
        return o.toByteArray();
    }

    @Test
    public void reporterIsAMemberFailsOpenOnEveryCannotTellAndClosedOnlyOnAnAbsentMember() {
        final Group g = new Group();
        g.groupId = new byte[] {1};
        final FakeShellPort f = new FakeShellPort().returns("ensureSession", true)
                .returns("getGroup", g)
                .returns("selfE164", "+1")
                .returns("fetchServerPack", Look.asked(roster("+1", "+2")));
        assertTrue(
                MlsServerBundle.reporterIsAMember(f.port(), MlsLogSink.NONE, "grp", "+2", "g:grp"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsServerBundle.reporterIsAMember(f.port(), log, "grp", "+9", "g:grp"));
        assertTrue(log.said("W", LogMask.number("+9") + " is NOT in the server roster"));
        f.returns("fetchServerPack", Look.refusedByLedger("rationed"));
        assertTrue("a refused look fails OPEN", MlsServerBundle.reporterIsAMember(f.port(),
                MlsLogSink.NONE, "grp", "+9", "g:grp"));
        f.returns("fetchServerPack", Look.asked(null));
        assertTrue("an unreadable roster fails OPEN", MlsServerBundle.reporterIsAMember(f.port(),
                MlsLogSink.NONE, "grp", "+9", "g:grp"));
        assertTrue("a 1:1 has no roster to check", MlsServerBundle.reporterIsAMember(
                new FakeShellPort().port(), MlsLogSink.NONE, null, "+9", "p:+9"));
    }


    @Test
    public void ourRosterIsTheNewestMomentOfTheSessionsCurrentEraWithoutUs() {
        final FakeRecords s = storeWith(b -> b.putMembership(0, 1, new String[] {"+1", "+9"})
                .putMembership(0, 4, new String[] {"+1", "+2", "+3"}));
        final FakeShellPort f = port(s);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));   // era 0
        assertEquals(java.util.Arrays.asList("+2", "+3"),
                MlsServerBundle.ourRoster(f.port(), MlsLogSink.NONE, KEY, grp()));
        final FakeShellPort none = port(storeWith(b -> b));
        none.returns("session", none.stub(MlsSession.class, "eraEpoch", new byte[12]));
        assertNull("no recorded membership is 'no baseline', not an empty group",
                MlsServerBundle.ourRoster(none.port(), MlsLogSink.NONE, KEY, grp()));
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
    public void theMlsRosterIsEveryLeafsMsisdnOrNothingAtAll() {
        assertEquals(java.util.Arrays.asList("+1", "+2"),
                MlsServerBundle.mlsRosterMsisdns(engine(leaves("+1", "+2")).port(), MlsLogSink.NONE,
                        GID));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull("a short roster would read a missing member as departed",
                MlsServerBundle.mlsRosterMsisdns(engine(leaves("+1", "")).port(), log, GID));
        assertTrue(log.said("W", "carries no readable MSISDN"));
        assertNull(MlsServerBundle.mlsRosterMsisdns(
                new FakeShellPort().returns("openMlsSession", null).port(), MlsLogSink.NONE, GID));
    }


    @Test
    public void theRebuildRosterIsTheServersThenOursThenTheRcsConversations() {
        final FakeShellPort f = SplitFixtures.port(new FakeRecords())
                .returns("bugleRoster", java.util.Collections.singletonList("+4"));
        assertEquals(java.util.Collections.singletonList("+2"), MlsServerBundle.rosterForRebuild(
                f.port(), MlsLogSink.NONE, null, "+2", "p:+2",
                ServerPack.none(MlsServerPackOutcome.NOT_A_GROUP)));
        final byte[] pack = MlsArtifactBundle.joinLenPrefixed(java.util.Arrays.asList(
                new byte[] {7}, new byte[] {8},
                "+1".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "+2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "+3".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(java.util.Arrays.asList("+2", "+3"), MlsServerBundle.rosterForRebuild(f.port(),
                MlsLogSink.NONE, "grp", "+2", "g:grp", ServerPack.of(pack)));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals("no MLS-side source survived: the RCS conversation's",
                java.util.Collections.singletonList("+4"),
                MlsServerBundle.rosterForRebuild(f.port(), log, "grp", "+2", "g:grp",
                        ServerPack.none(MlsServerPackOutcome.NO_LOCAL_STATE_TO_ASK_WITH)));
        assertTrue(log.said("W", "taken from the RCS CONVERSATION participants"));
    }


    @Test
    public void theServerPackIsAskedForFromOurCurrentEraAndAuthenticator() {
        final Object[][] seen = {null};
        final FakeShellPort f = new FakeShellPort().on("lookMissedCommits", a -> {
            seen[0] = a;
            return Look.refusedByLedger("no");
        });
        f.returns("session",
                f.stub(MlsSession.class, "eraEpoch", new byte[12], "epochAuth", new byte[] {6}));
        MlsServerBundle.fetchServerPack(f.port(), MlsFetchLedger.Caller.values()[0], "grp", "+2",
                grp());
        assertEquals(KEY, seen[0][1]);
        assertEquals(0, seen[0][4]);
        assertArrayEquals(new byte[] {6}, (byte[]) seen[0][5]);
    }
}
