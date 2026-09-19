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

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ImdnCheck;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.MlsAdoptionUndo;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsRecordStateSplitTest {

    private static final byte[] GID = {1, 2};

    private static Group grp() {
        final Group g = new Group();
        g.groupId = GID;
        g.rcsGroupId = "grp";
        g.peerE164 = "+2";
        return g;
    }

    /** A port with a group under g:grp, identity +1, and the given store. */
    private static FakeShellPort port(final FakeRecords store) {
        return new FakeShellPort().returns("getGroup", grp()).returns("selfE164", "+1")
                .returns("records", store).on("lock", a -> null).on("unlock", a -> null);
    }

    @Test
    public void recordForIsTheStoredRecordOrAFreshInitialOneButNeverAnUnreadableOne() {
        final FakeRecords store = new FakeRecords();
        final MlsConversationRecord fresh =
                MlsRecordState.recordFor(port(store).port(), MlsLogSink.NONE, "g:grp");
        assertNotNull(fresh);
        assertEquals("+1", fresh.identity);
        assertTrue("an initial record is NOT written by a read", store.records.isEmpty());
        store.put(fresh.toBuilder().sendsThisEpoch(5).build());
        assertEquals(5, MlsRecordState.recordFor(port(store).port(), MlsLogSink.NONE,
                "g:grp").sendsThisEpoch);
        store.unreadable = "corrupt";
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull("Err is NOT NotFound: starting fresh would discard an in-flight operation",
                MlsRecordState.recordFor(port(store).port(), log, "g:grp"));
        assertTrue(log.said("E", "record unreadable for g:grp — corrupt"));
    }


    @Test
    public void recordForGroupNeverInventsAnInitialRecord() {
        final FakeRecords store = new FakeRecords();
        assertNull("no stored record, no anchor to compare against", MlsRecordState.recordForGroup(
                port(store).port(), MlsLogSink.NONE, grp()));
        store.put(MlsConversationRecord.initial("+1", GID, "grp", "+2"));
        assertNotNull(MlsRecordState.recordForGroup(port(store).port(), MlsLogSink.NONE, grp()));
        store.unreadable = "x";
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsRecordState.recordForGroup(port(store).port(), log, grp()));
        assertTrue(log.said("E", "record unreadable while testing"));
    }


    @Test
    public void ensureRecordCreatesOnlyWhenNotFound() {
        final FakeRecords store = new FakeRecords();
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecordState.ensureRecord(port(store).port(), log, "g:grp");
        assertEquals(1, store.records.size());
        assertTrue(log.said("I", "created the initial MlsConversationRecord"));
        store.put(MlsConversationRecord.initial("+1", GID, "grp", "+2").toBuilder()
                .sendsThisEpoch(3).build());
        MlsRecordState.ensureRecord(port(store).port(), MlsLogSink.NONE, "g:grp");
        assertEquals("an existing record is left alone", 3,
                ((StoreRead.Ok<MlsConversationRecord>) store.get("+1", GID)).value.sendsThisEpoch);
        store.unreadable = "x";
        // Err: not overwritten
        MlsRecordState.ensureRecord(port(store).port(), MlsLogSink.NONE, "g:grp");
    }


    @Test
    public void writeRecordWritesTheGroupWholeOverThePriorRecord() {
        final FakeRecords store = new FakeRecords();
        store.put(MlsConversationRecord.initial("+1", GID, "grp", "+2").toBuilder()
                .continuityToken(new byte[] {7}).build());
        final Group g = grp();
        g.sendsThisEpoch = 4;
        g.sendsSinceLeafRotation = 6;
        final FakeShellPort f = port(store).returns("session", null);
        MlsRecordState.writeRecord(f.port(), MlsLogSink.NONE, g);
        final MlsConversationRecord r =
                ((StoreRead.Ok<MlsConversationRecord>) store.get("+1", GID)).value;
        assertEquals(4, r.sendsThisEpoch);
        assertEquals(6, r.sendsSinceLeafRotation);
        assertArrayEquals("fields the Group does not carry survive the write", new byte[] {7},
                r.continuityToken);
        store.failWrites = "disk full";
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecordState.writeRecord(port(store).returns("session", null).port(), log, g);
        assertTrue(log.said("W", "record write failed — disk full"));
    }


    @Test
    public void loadFromRecordRebuildsTheGroupFromItsAliasAndRecord() {
        final FakeRecords store = new FakeRecords();
        assertNull(MlsRecordState.loadFromRecord(port(store).port(), MlsLogSink.NONE, "conv1"));
        store.putAlias("+1", "conv1", GID);
        store.put(MlsConversationRecord.initial("+1", GID, "grp", "").toBuilder().sendsThisEpoch(2)
                .build());
        final Group g = MlsRecordState.loadFromRecord(port(store).port(), MlsLogSink.NONE, "conv1");
        assertArrayEquals(GID, g.groupId);
        assertEquals("grp", g.rcsGroupId);
        assertNull("an empty peer is no peer", g.peerE164);
        assertEquals(2, g.sendsThisEpoch);
        store.unreadable = "x";
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsRecordState.loadFromRecord(port(store).port(), log, "conv1"));
        assertTrue(log.said("W", "falling back to the legacy scalars"));
    }


    @Test
    public void captureAdoptionRecordsWhatExistedBeforeSoTheUndoRemovesOnlyWhatItAdded() {
        final FakeRecords store = new FakeRecords();
        MlsAdoptionUndo u = MlsRecordState.captureAdoption(port(store).port(), "g:grp", GID);
        assertFalse(u.hadRecord);
        assertFalse(u.hadAlias);
        store.put(MlsConversationRecord.initial("+1", GID, "grp", "+2"));
        store.putAlias("+1", "g:grp", GID);
        u = MlsRecordState.captureAdoption(port(store).port(), "g:grp", GID);
        assertTrue(u.hadRecord);
        assertTrue(u.hadAlias);
        u = MlsRecordState.captureAdoption(port(store).port(), null, GID);
        assertTrue("with nothing to key on, claim everything existed so nothing is removed",
                u.hadRecord && u.hadAlias);
    }


    @Test
    public void rollBackAdoptionRemovesOnlyWhatTheAdoptionAddedAndVerifiesItIsGone() {
        final FakeRecords store = new FakeRecords();
        store.put(MlsConversationRecord.initial("+1", GID, "grp", "+2"));
        store.putAlias("+1", "g:grp", GID);
        final java.util.Map<String, Group> groups = new java.util.HashMap<>();
        groups.put("g:grp", grp());
        final FakeShellPort f = port(store).returns("groups", groups).returns("getGroup", null);
        assertTrue(MlsRecordState.rollBackAdoption(f.port(), MlsLogSink.NONE, "g:grp", GID,
                new MlsAdoptionUndo(false, false)));
        assertTrue(store.records.isEmpty());
        assertTrue(store.aliases.isEmpty());
        assertTrue(groups.isEmpty());

        final FakeRecords kept = new FakeRecords();
        kept.put(MlsConversationRecord.initial("+1", GID, "grp", "+2"));
        MlsRecordState.rollBackAdoption(
                port(kept).returns("groups", new java.util.HashMap<String, Group>())
                .returns("getGroup", null).port(), MlsLogSink.NONE, "g:grp", GID,
                new MlsAdoptionUndo(true, true));
        assertEquals("a record that existed BEFORE the adoption is not removed", 1,
                kept.records.size());

        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsRecordState.rollBackAdoption(port(new FakeRecords())
                .returns("groups", new java.util.HashMap<String, Group>()).port(), log, "g:grp",
                GID, new MlsAdoptionUndo(false, false)));
        assertTrue("getGroup still answering is reported, not assumed away",
                log.said("E", "SURVIVED"));
    }


    @Test
    public void isDowngradedStatusIsThePredicateOverTheRecordsHealth() {
        for (int st = 0; st <= 20; st++) {
            final int s = st;
            assertEquals("status " + st, MlsHealthPredicates.isDowngraded(
                    st), MlsRecordState.isDowngradedStatus(
                    SplitFixtures.port(storeWith(b -> b.healthStatus(s))).port(), MlsLogSink.NONE,
                    KEY));
        }
        assertFalse("no record is not downgraded", MlsRecordState.isDowngradedStatus(
                SplitFixtures.port(new FakeRecords()).returns("getGroup", null).port(),
                MlsLogSink.NONE, KEY));
    }


    @Test
    public void hasEndMlsStatusIsThePredicateOverTheRecordsHealth() {
        for (int st = 0; st <= 20; st++) {
            final int s = st;
            assertEquals("status " + st, MlsHealthPredicates.hasEndMls(
                    st), MlsRecordState.hasEndMlsStatus(
                    SplitFixtures.port(storeWith(b -> b.healthStatus(s))).port(), MlsLogSink.NONE,
                    KEY));
        }
    }


    @Test
    public void weLeftReadsTheSelfLeaveMark() {
        assertTrue(MlsRecordState.weLeft(SplitFixtures.port(storeWith(b -> b.selfLeftAtMs(5L)))
                .port(), MlsLogSink.NONE, KEY));
        assertFalse(MlsRecordState.weLeft(SplitFixtures.port(storeWith(b -> b)).port(),
                MlsLogSink.NONE, KEY));
    }


    @Test
    public void aGroupIsInitializingWhileItsRecordHoldsAPendingOperation() {
        assertTrue(MlsRecordState.groupIsInitializing(
                SplitFixtures.port(storeWith(b -> b.pendingOperation(
                op(MlsPendingOperation.Kind.EPOCH_ADVANCEMENT)))).port(), MlsLogSink.NONE, "grp"));
        assertFalse(MlsRecordState.groupIsInitializing(SplitFixtures.port(storeWith(b -> b)).port(),
                MlsLogSink.NONE, "grp"));
        assertFalse(MlsRecordState.groupIsInitializing(
                SplitFixtures.port(storeWith(b -> b)).returns("ensureSession", false)
                .port(), MlsLogSink.NONE, "grp"));
    }


    @Test
    public void recordedRosterIsTheNewestMomentOfTheNewestEraWithoutUs() {
        final FakeRecords s = storeWith(b -> b.putMembership(1, 9, new String[] {"+1", "+7"})
                .putMembership(2, 3, new String[] {"+1", "+2", "", "+3"})
                .putMembership(2, 1, new String[] {"+9"}));
        assertEquals(java.util.Arrays.asList("+2", "+3"),
                MlsRecordState.recordedRoster(SplitFixtures.port(s).port(), MlsLogSink.NONE, KEY,
                        "+1"));
        assertNull(MlsRecordState.recordedRoster(SplitFixtures.port(storeWith(b -> b)).port(),
                MlsLogSink.NONE, KEY, "+1"));
    }


    @Test
    public void aRejoinClearsTheSelfLeaveMarkAndSaysWhenItCannot() {
        final FakeRecords s = storeWith(b -> b.selfLeftAtMs(42L));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecordState.clearSelfLeftOnRejoin(SplitFixtures.port(s).port(), log, KEY, "+2");
        assertFalse(rec(s).selfLeft());
        assertTrue(log.said("W", "was marked LEFT at 42"));
        final FakeRecords stuck = storeWith(b -> b.selfLeftAtMs(42L));
        stuck.failWrites = "full";
        final FakeShellPort.Log err = new FakeShellPort.Log();
        MlsRecordState.clearSelfLeftOnRejoin(SplitFixtures.port(stuck).port(), err, KEY, "+2");
        assertTrue(err.said("E", "could NOT clear the self-leave mark: full"));
    }

    private static byte[] eeA(final int era, final long epoch) {
        final byte[] b = new byte[12];
        b[0] = (byte) (era >>> 24); b[1] = (byte) (era >>> 16); b[2] = (byte) (era >>> 8); b[3] =
                (byte) era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (epoch >>> (56 - 8 * i));
        return b;
    }

    @Test
    public void adoptGroupWritesTheRowTheRecordAndTakesTheWelcomesToken() {
        final FakeRecords s = new FakeRecords();
        final FakeShellPort f =
                SplitFixtures.port(s).on("putGroup", a -> null).returns("getGroup", grp());
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", eeA(6, 1), "epochAuth",
                new byte[] {3}, "takeWelcomeContinuityToken", new byte[0]));
        assertEquals(KEY, MlsRecordState.adoptGroup(f.port(), MlsLogSink.NONE, "grp", null, GID));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(g:grp")));
        assertEquals("the record is created", 1, s.records.size());
        assertNull(MlsRecordState.adoptGroup(f.port(), MlsLogSink.NONE, null, null, GID));
    }


    @Test
    public void aForkThatWillNotHealIsUnadoptedSoALaterEstablishCannotReportIt() {
        final FakeRecords s = new FakeRecords();
        final java.util.Map<String, Group> groups = new java.util.HashMap<>();
        final FakeShellPort f = SplitFixtures.port(s).returns("groups", groups)
                .on("putGroup", a -> null)
                .returns("selfHeal", -1).returns("getGroup", null);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12], "epochAuth",
                new byte[] {1}, "takeWelcomeContinuityToken", new byte[0]));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsRecordState.healOntoServerGroupOrUnadopt(f.port(), log, "grp", "+2",
                KEY, GID, 4L));
        assertTrue(log.said("W", "host adoption was ROLLED BACK"));
        assertTrue("the record the adoption created is gone again", s.records.isEmpty());
        f.returns("selfHeal", 5);
        assertEquals(5, MlsRecordState.healOntoServerGroupOrUnadopt(f.port(), MlsLogSink.NONE,
                "grp", "+2", KEY, GID, 4L));
    }


    /** A port whose session sits at era 0 epoch 0 of the fixture group. */
    private static FakeShellPort memberPort(final FakeRecords s) {
        final FakeShellPort f = SplitFixtures.port(s);
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        return f;
    }

    @Test
    public void membershipIsRecordedWithUsIncludedUnlessWeAreTheOneWhoLeft() {
        final FakeRecords s = storeWith(b -> b);
        MlsRecordState.recordMembership(memberPort(s).port(), MlsLogSink.NONE, KEY, grp(),
                java.util.Arrays.asList("+2", "+3"), true);
        assertArrayEquals(new String[] {"+2", "+3", "+1"}, rec(s).membershipHistory.get(0).get(0L));
        MlsRecordState.recordMembership(memberPort(s).port(), MlsLogSink.NONE, KEY, grp(),
                java.util.Arrays.asList("+2"), false);
        assertArrayEquals(new String[] {"+2"}, rec(s).membershipHistory.get(0).get(0L));
    }

    @Test
    public void aRefusedWriteRecordsNothingAndSaysSo() {
        final FakeRecords s = storeWith(b -> b);
        s.failWrites = "disk full";
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecordState.recordMembership(memberPort(s).port(), log, KEY, grp(),
                java.util.Arrays.asList("+2"), true);
        assertTrue(log.said("W", "could not record membership"));
        assertNull(rec(s).membershipHistory.get(0));
    }


    @Test
    public void theThreeArgumentFormIncludesUs() {
        final FakeRecords s = storeWith(b -> b);
        MlsRecordState.recordMembership(memberPort(s).port(), MlsLogSink.NONE, KEY, grp(),
                java.util.Arrays.asList("+2"));
        assertArrayEquals(new String[] {"+2", "+1"}, rec(s).membershipHistory.get(0).get(0L));
    }


    @Test
    public void leavingMarksTheRecordLeftRecordsNoMembersAndDropsTheEngineGroup() {
        final FakeRecords s = storeWith(b -> b);
        final FakeShellPort f = SplitFixtures.port(s);
        f.returns("session",
                f.stub(MlsSession.class, "eraEpoch", new byte[12], "deleteGroup", true));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecordState.recordSelfDeparture(f.port(), log, KEY, grp());
        assertTrue(rec(s).selfLeftAtMs > 0L);
        assertArrayEquals("we are not put back into the group we left", new String[0],
                rec(s).membershipHistory.get(0).get(0L));
        assertTrue(log.said("I", "is marked LEFT"));
    }

    @Test
    public void leavingAConversationWeHoldNoGroupForSaysItCannotBeMarked() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsRecordState.recordSelfDeparture(
                SplitFixtures.port(new FakeRecords()).returns("getGroup", null).port(),
                log, KEY, grp());
        assertTrue(log.said("W", "hold no record"));
    }


    private static FakeShellPort healthPort(final FakeRecords s) {
        return MlsDowngradeFlowSplitTest.flowPort(s, 0).returns("telemetry", MlsTelemetry.NONE)
                .returns("session", null);
    }

    @Test
    public void aLegalMoveIsWrittenUnderTheLockAndAnIllegalOneIsRefused() {
        final FakeRecords s = storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS));
        final FakeShellPort f = healthPort(s);
        assertNotNull(MlsRecordState.moveHealth(f.port(), MlsLogSink.NONE, KEY,
                MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE, "test"));
        assertEquals(MlsHealthStates.ONGOINGERAADVANCEMENTFORREVIVE, rec(s).healthStatus);
        assertTrue(f.calls.indexOf("lock(" + KEY + ")") < f.calls.indexOf("unlock(" + KEY + ")"));
        int illegal = -1;
        for (int to = 0; to < 32 && illegal < 0; to++) {
            if (to != MlsHealthStates.HEALTHY
                    && !MlsHealthStates.isLegal(MlsHealthStates.HEALTHY, to)) illegal = to;
        }
        assertTrue("precondition: some state is unreachable from Healthy", illegal >= 0);
        final FakeRecords h = storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsRecordState.moveHealth(healthPort(h).port(), log, KEY, illegal, "test"));
        assertTrue(log.said("E", "REFUSED health transition"));
        assertEquals(MlsHealthStates.HEALTHY, rec(h).healthStatus);
        assertNull(MlsRecordState.moveHealth(healthPort(h).port(), log, null, illegal, "test"));
    }


    @Test
    public void theRecordsTokenIsTheAnswerAndALegacyPreferenceIsFoldedIntoTheRecordOnce() {
        final FakeRecords held = storeWith(b -> b.continuityToken(new byte[] {4, 4}));
        assertArrayEquals(new byte[] {4, 4}, MlsRecordState.continuityTokenFor(
                SplitFixtures.port(held).port(), MlsLogSink.NONE, "grp", null));
        final FakePrefs prefs = new FakePrefs();
        prefs.edit().putString("mls_continuity_token_" + KEY, "AQID").apply();
        final FakeRecords s = storeWith(b -> b);
        final FakeShellPort f = SplitFixtures.port(s).returns("prefs", prefs)
                .on("base64Decode", a -> java.util.Base64.getDecoder().decode((String) a[0]));
        assertArrayEquals(new byte[] {1, 2, 3}, MlsRecordState.continuityTokenFor(f.port(),
                MlsLogSink.NONE, "grp", null));
        assertArrayEquals(new byte[] {1, 2, 3}, rec(s).continuityToken);
        assertFalse(prefs.contains("mls_continuity_token_" + KEY));
    }
}
