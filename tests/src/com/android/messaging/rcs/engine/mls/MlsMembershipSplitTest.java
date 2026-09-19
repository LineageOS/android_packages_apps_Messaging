/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupMembershipRouting;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.GroupPlane;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsMembershipSplitTest {


    @Test
    public void theRcsRosterRpcIsTheOneTheCallerAskedFor() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("rpc",
                f.stub(MlsProviderRpc.class, "addGroupUsers", true, "removeGroupUsers", false));
        assertTrue(MlsMembership.rcsMembershipChange(f.port(), MlsLogSink.NONE, "grp", "+3", true));
        assertTrue(f.calls.contains("MlsProviderRpc.addGroupUsers"));
        assertFalse(
                MlsMembership.rcsMembershipChange(f.port(), MlsLogSink.NONE, "grp", "+3", false));
        assertTrue(f.calls.contains("MlsProviderRpc.removeGroupUsers"));
    }


    private static MlsGroupArtifacts art() {
        return new MlsGroupArtifacts(new byte[] {2}, new byte[] {9}, new byte[] {8}, new byte[] {7},
                GID);
    }

    /**
     * The fixture group and store; the engine builds {@code art}; the provider answers {@code r}.
     */
    private static FakeShellPort changePort(final FakeRecords s, final MlsGroupArtifacts art,
            final MlsProviderRpc.ControlResult r, final boolean[] restored) {
        final FakeShellPort f = SplitFixtures.port(s).returns("resolveInbound", KEY)
                .on("putGroup", a -> null)
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .returns("quarantineIfAheadOfServer", EraReconcile.unknown())
                .on("scheduleRetry", a -> null);
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1}, "eraEpoch",
                new byte[12],
                "exportGroupSnapshot", new byte[] {5}, "addMember", art, "removeMemberByMsisdn",
                art, "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { restored[0] = true; return true; }));
        f.returns("rpc",
                f.stub(MlsProviderRpc.class, "addGroupUsersMls", r, "removeGroupUsersMls", r));
        return f;
    }

    @Test
    public void anAcceptedAddAdvancesTheGroupAndRecordsTheNewMember() {
        final FakeRecords s = storeWith(b -> b.putMembership(0, 0L, new String[] {"+2", "+1"}));
        final boolean[] restored = {false};
        final FakeShellPort f = changePort(s, art(), new MlsProviderRpc.ControlResult(
                MlsProviderRpc.ControlResult.VERDICT_OK, null, null), restored);
        assertEquals(0, MlsMembership.mlsMembershipChange(f.port(), MlsLogSink.NONE, "grp", "+2",
                "+3", Op.ADD, new byte[] {4}, null));
        assertFalse(restored[0]);
        assertTrue(java.util.Arrays.asList(rec(s).membershipHistory.get(0).get(0L)).contains("+3"));
    }

    @Test
    public void aRefusedChangeRollsTheEngineBackAndARetryableOneIsScheduled() {
        final boolean[] restored = {false};
        final FakeShellPort refused = changePort(storeWith(b -> b), art(),
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"), restored);
        assertEquals(-1, MlsMembership.mlsMembershipChange(refused.port(), MlsLogSink.NONE, "grp",
                "+2", "+3", Op.REMOVE, null, null));
        assertTrue(restored[0]);
        assertFalse("a rejection is not retried",
                refused.calls.stream().anyMatch(c -> c.startsWith("scheduleRetry")));
        final FakeShellPort failed = changePort(storeWith(b -> b), art(),
                new MlsProviderRpc.ControlResult(
                        MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED, null, null),
                new boolean[] {false});
        assertEquals(-1, MlsMembership.mlsMembershipChange(failed.port(), MlsLogSink.NONE, "grp",
                "+2", "+3", Op.REMOVE, null, null));
        assertTrue("a transport failure is",
                failed.calls.stream().anyMatch(c -> c.startsWith("scheduleRetry(grp, +3, 1")));
    }

    @Test
    public void aChangeTheEngineCouldNotBuildNeverReachesTheProvider() {
        final boolean[] restored = {false};
        final FakeShellPort f = changePort(storeWith(b -> b), null, null, restored);
        assertEquals(-1, MlsMembership.mlsMembershipChange(f.port(), MlsLogSink.NONE, "grp", "+2",
                "+3", Op.ADD, new byte[] {4}, null));
        assertTrue(restored[0]);
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }


    private static MlsGroupArtifacts created() {
        return new MlsGroupArtifacts(new byte[] {2}, new byte[] {9}, new byte[] {8}, new byte[] {7},
                GID, null, 4L, MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP,
                java.util.Arrays.asList("+2", "+3"));
    }

    private static FakeShellPort addPort(final MlsProviderRpc.ControlResult r,
            final Look<byte[]> serverAuth, final boolean[] rolledBack) {
        final java.util.Map<String, Group> groups = new java.util.HashMap<>();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("groups", groups)
                .on("putGroup", a -> { groups.put((String) a[0], (Group) a[1]); return null; })
                .returns("lookServerEpochAuthenticator", serverAuth).returns("selfHeal", 4);
        f.returns("session", f.stub(MlsSession.class, "epochAuth", new byte[] {1}, "eraEpoch",
                new byte[12], "discardGroup",
                (Function<Object[], Object>) a -> { rolledBack[0] = true; return true; },
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { rolledBack[0] = true; return true; }));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "addGroupUsersMls", r));
        return f;
    }

    @Test
    public void addingToAGroupWeJustCreatedAdoptsItOnlyWhenTheServerHoldsTheSameOne() {
        final boolean[] rolled = {false};
        assertEquals(4, MlsMembership.addMembersToExistingGroup(
                addPort(new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK,
                        null, null), Look.asked(new byte[] {1}), rolled).port(),
                MlsLogSink.NONE, "grp", KEY, java.util.Arrays.asList("+2", "+3"), created(),
                new byte[] {0}, new byte[] {6}));
        assertEquals(-1, MlsMembership.addMembersToExistingGroup(
                addPort(new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK,
                        null, null), Look.refusedByLedger("no"), rolled).port(),
                MlsLogSink.NONE, "grp", KEY, java.util.Arrays.asList("+2", "+3"), created(),
                new byte[] {0}, new byte[] {6}));
        assertEquals(-1, MlsMembership.addMembersToExistingGroup(
                addPort(new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK,
                        null, null), Look.asked(null), rolled).port(),
                MlsLogSink.NONE, "grp", KEY, java.util.Arrays.asList("+2", "+3"), created(),
                new byte[] {0}, new byte[] {6}));
        assertFalse(rolled[0]);
    }

    @Test
    public void aRefusedAddRollsTheDiscardedCreateBack() {
        final boolean[] rolled = {false};
        assertEquals(-1, MlsMembership.addMembersToExistingGroup(addPort(
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"),
                Look.asked(new byte[] {1}), rolled).port(), MlsLogSink.NONE, "grp", KEY,
                java.util.Arrays.asList("+2", "+3"), created(), new byte[] {0}, new byte[] {6}));
        assertTrue(rolled[0]);
    }


    @Test
    public void addingAMemberIsTheMembershipChangeWithTheirKeyPackage() {
        final FakeShellPort f = changePort(storeWith(b -> b), art(),
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null,
                        null), new boolean[] {false});
        assertEquals(0, MlsMembership.addMemberToMlsGroup(f.port(), MlsLogSink.NONE, "grp", "+2",
                "+3", new byte[] {4}));
        assertTrue(f.calls.contains("MlsSession.addMember"));
    }


    @Test
    public void removingAMemberIsTheMembershipChangeWithTheirSignatureKey() {
        final FakeShellPort f = changePort(storeWith(b -> b), art(),
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null,
                        null), new boolean[] {false});
        assertEquals(0, MlsMembership.removeMemberFromMlsGroup(f.port(), MlsLogSink.NONE, "grp",
                "+2", "+3", new byte[] {6}));
        assertTrue(f.calls.contains("MlsSession.removeMemberByMsisdn"));
    }


    /** Peer guards that allow everything except {@code refused}, recording every call. */
    private static FakeShellPort guarded(final FakeShellPort f, final String refused) {
        f.returns("peerGuard", f.stub(MlsPeerGuards.class,
                "allowJoiningPeer", (Function<Object[], Object>) a -> !a[1].equals(refused),
                "allowStateChange", (Function<Object[], Object>) a -> !a[1].equals(refused),
                "resetReestablishCooldown", null, "rebuildEpisodeAgeMs", 5L, "claimRebuildEpisode",
                true, "rebuildEpisodeWindowMs", 60000L, "claimReestablishAttempt", false,
                "reestablishCooldownMs", 30000L));
        return f;
    }

    @Test
    public void oneMemberTheJoinGuardRefusesStopsTheWholeGroup() {
        assertTrue(MlsMembership.allowedToJoinAll(guarded(new FakeShellPort(), "+9").port(),
                MlsLogSink.NONE, "op", "grp", java.util.Arrays.asList("+2", "", "+3")));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsMembership.allowedToJoinAll(guarded(new FakeShellPort(), "+3").port(), log,
                "op", "grp", java.util.Arrays.asList("+2", "+3")));
        assertTrue(log.lines.stream().anyMatch(l -> l.startsWith("E ")));
        assertFalse(MlsMembership.allowedToJoinAll(new FakeShellPort().port(), MlsLogSink.NONE,
                "op", "grp", null));
    }


    private static FakeShellPort addMemberPort(final Claim<byte[]> claim, final String refused) {
        final FakeShellPort f = guarded(new FakeShellPort(), refused).returns("ensureSession", true)
                .on("claimOne", a -> claim).returns("keyPackageUsable", true)
                .returns("resolveInbound", null)
                .returns("commitAndSend", 3);
        f.returns("rpc", f.stub(MlsProviderRpc.class, "addGroupUsers", true));
        return f;
    }

    @Test
    public void addingAMemberToANonMlsGroupUpdatesTheRcsRosterAndCommits() {
        final FakeShellPort f = addMemberPort(Claim.asked(new byte[] {4}), "+9");
        assertEquals(3, MlsMembership.addMember(f.port(), MlsLogSink.NONE, "grp", "+2", "+3"));
        assertTrue(f.calls.contains("MlsProviderRpc.addGroupUsers"));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("commitAndSend(grp, +2, 04, null, ADD")));
    }

    @Test
    public void aRefusedJoinOrAClaimWeCouldNotMakeAddsNobody() {
        final FakeShellPort guard = addMemberPort(Claim.asked(new byte[] {4}), "+3");
        assertEquals(-1, MlsMembership.addMember(guard.port(), MlsLogSink.NONE, "grp", "+2", "+3"));
        assertFalse("the guard refuses before anything is claimed",
                guard.calls.stream().anyMatch(c -> c.startsWith("claimOne")));
        assertEquals(-1, MlsMembership.addMember(
                addMemberPort(Claim.refusedByLedger("budget"), "+9").port(),
                MlsLogSink.NONE, "grp", "+2", "+3"));
        assertEquals(-1, MlsMembership.addMember(addMemberPort(Claim.asked(null), "+9").port(),
                MlsLogSink.NONE, "grp", "+2", "+3"));
    }


    @Test
    public void removingFromANonMlsGroupNeedsTheRcsRosterFirst() {
        final FakeShellPort ok = guarded(new FakeShellPort(), "+9").returns("ensureSession", true)
                .returns("resolveInbound", null).returns("commitAndSend", 2);
        ok.returns("rpc", ok.stub(MlsProviderRpc.class, "removeGroupUsers", true));
        assertEquals(2, MlsMembership.removeMember(ok.port(), MlsLogSink.NONE, "grp", "+2",
                new byte[] {6}, "+3"));
        final FakeShellPort rcsNo = guarded(new FakeShellPort(), "+9")
                .returns("ensureSession", true)
                .returns("resolveInbound", null);
        rcsNo.returns("rpc", rcsNo.stub(MlsProviderRpc.class, "removeGroupUsers", false));
        assertEquals(-1, MlsMembership.removeMember(rcsNo.port(), MlsLogSink.NONE, "grp", "+2",
                new byte[] {6}, "+3"));
        assertEquals("the state-change guard refuses first", -1, MlsMembership.removeMember(
                guarded(new FakeShellPort(), "+3").port(), MlsLogSink.NONE, "grp", "+2",
                new byte[] {6}, "+3"));
    }


    /** A port whose engine reports {@code after} as the post-commit roster (null: unreadable). */
    private static FakeShellPort departurePort(final String... after) {
        final FakeShellPort f =
                SplitFixtures.port(storeWith(b -> b)).on("applyGroupDeparture", a -> true);
        final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> leaves =
                new java.util.HashMap<>();
        for (int i = 0; after != null && i < after.length; i++) {
            leaves.put(i, new MlsParticipantKeyResync.Leaf(i, after[i],
                    "aabbccddeeff00112233445566778899"));
        }
        f.returns("openMlsSession", f.stub(MlsSession.class, "memberParticipantKeys",
                after == null ? null : leaves));
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12]));
        return f;
    }

    @Test
    public void aMemberTheCommitRemovedIsMirroredIntoTheConversationButNeverOurselves() {
        final FakeShellPort f = departurePort("+1", "+2");
        MlsMembership.applyDepartures(f.port(), MlsLogSink.NONE, KEY, grp(), "grp",
                java.util.Arrays.asList("+1", "+2", "+3"));
        assertTrue(f.calls.contains("applyGroupDeparture(grp, +3)"));
        assertEquals(1, f.calls.stream().filter(c -> c.startsWith("applyGroupDeparture(")).count());
    }

    @Test
    public void anUnreadableRosterReportsNoDepartures() {
        final FakeShellPort f = departurePort((String[]) null);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsMembership.applyDepartures(f.port(), log, KEY, grp(), "grp",
                java.util.Arrays.asList("+1", "+2"));
        assertTrue(log.said("W", "not fully readable"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("applyGroupDeparture(")));
    }


    /**
     * conversationIsMls asks the port's resolveInbound: it answers {@code KEY} when we hold the
     * group.
     */
    private static FakeShellPort planePort(final FakeRecords s) {
        return SplitFixtures.port(s).returns("conversationIdFor", "c1")
                .returns("loadIdentity", null)
                .returns("resolveInbound", s.records.isEmpty() ? null : KEY);
    }

    @Test
    public void theGroupPlaneIsReadFromOurStateAndThenFromTheConversationsBit() {
        assertEquals(GroupPlane.MLS,
                MlsMembership.groupPlane(planePort(storeWith(b -> b.healthStatus(
                MlsHealthStates.HEALTHY))).port(), MlsLogSink.NONE, "grp", "test"));
        assertEquals(GroupPlane.MLS_DOWNGRADED,
                MlsMembership.groupPlane(planePort(storeWith(b -> b.healthStatus(
                MlsHealthStates.DONEENDMLS))).port(), MlsLogSink.NONE, "grp", "test"));
        final FakeShellPort locked = planePort(new FakeRecords()).returns("getGroup", null)
                .returns("conversationMlsBit", Boolean.TRUE);
        assertEquals(GroupPlane.MLS_LOCKED_OUT,
                MlsMembership.groupPlane(locked.port(), MlsLogSink.NONE, "grp", "test"));
        final FakeShellPort clear = planePort(new FakeRecords()).returns("getGroup", null)
                .returns("conversationMlsBit", Boolean.FALSE);
        assertEquals(GroupPlane.PLAINTEXT,
                MlsMembership.groupPlane(clear.port(), MlsLogSink.NONE, "grp", "test"));
        final FakeShellPort noSession =
                planePort(new FakeRecords()).returns("ensureSession", false);
        assertEquals(GroupPlane.PLAINTEXT,
                MlsMembership.groupPlane(noSession.port(), MlsLogSink.NONE, "grp", "test"));
    }


    @Test
    public void leavingClearsTheBitAndTellsTheConversationUnlessTheBitIsAlreadyClear() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("conversationIdFor", "c1")
                .returns("conversationMlsBit", Boolean.TRUE).on("downgradeMlsScheme", a -> null)
                .returns("applyGroupDeparture", true);
        MlsMembership.announceSelfDeparture(f.port(), MlsLogSink.NONE, KEY, "grp");
        assertTrue(f.calls.contains("downgradeMlsScheme(c1)"));
        assertTrue(f.calls.contains("applyGroupDeparture(grp, +1)"));
        final FakeShellPort clear = SplitFixtures.port(storeWith(b -> b))
                .returns("conversationIdFor", "c1")
                .returns("conversationMlsBit", Boolean.FALSE).returns("applyGroupDeparture", true);
        MlsMembership.announceSelfDeparture(clear.port(), MlsLogSink.NONE, KEY, "grp");
        assertTrue(clear.calls.contains("applyGroupDeparture(grp, +1)"));
    }


    private static FakeShellPort leavePort(final int verdict) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("resolveInbound", KEY)
                .returns("conversationIdFor", "c1").returns("conversationMlsBit", Boolean.FALSE)
                .returns("applyGroupDeparture", true);
        // resolveInbound has moved, so leave runs the real one: it probes the engine
        f.returns("session", f.stub(MlsSession.class, "commitRequired", false,
                "lastStatus", MlsSession.OpStatus.OK, "epochAuth", new byte[] {1}, "eraEpoch",
                new byte[12], "exportGroupSnapshot", new byte[] {5}, "restoreGroupSnapshot", true,
                "selfLeave", new MlsGroupArtifacts(null, new byte[] {9}, null, null, GID)));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "selfLeaveGroupMls",
                new MlsProviderRpc.ControlResult(verdict, null, "t")));
        return f;
    }

    @Test
    public void aRefusedSelfLeaveIsRolledBackAndA1to1HasNothingToLeave() {
        final FakeShellPort f = leavePort(MlsProviderRpc.ControlResult.VERDICT_REJECTED);
        assertFalse(MlsMembership.leave(f.port(), MlsLogSink.NONE, "grp", null));
        assertTrue(f.calls.contains("MlsSession.restoreGroupSnapshot"));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("applyGroupDeparture(")));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsMembership.leave(new FakeShellPort().port(), log, null, "+2"));
        assertTrue(log.said("W", "self-leave on a 1:1 is not a thing"));
    }

    @Test
    public void anAcceptedSelfLeaveIsAnnounced() {
        final FakeShellPort f = leavePort(MlsProviderRpc.ControlResult.VERDICT_OK);
        assertTrue(MlsMembership.leave(f.port(), MlsLogSink.NONE, "grp", null));
        assertTrue(f.calls.contains("applyGroupDeparture(grp, +1)"));
    }


    @Test
    public void theOneArgumentLeaveIsA1to1AndSoRefused() {
        assertFalse(MlsMembership.leave(new FakeShellPort().port(), MlsLogSink.NONE, "+2"));
    }


    @Test
    public void leaveGroupRoutesByPlaneAndRefusesWhatItCannotTell() {
        final FakeShellPort guarded = new FakeShellPort();
        guarded.returns("peerGuard",
                guarded.stub(MlsPeerGuards.class, "allowSelfDeparture", false));
        assertEquals(GroupMembershipRouting.REFUSED,
                MlsMembership.leaveGroup(guarded.port(), MlsLogSink.NONE, "grp"));
        assertEquals(GroupMembershipRouting.REFUSED,
                MlsMembership.leaveGroup(guarded.port(), MlsLogSink.NONE, ""));
        final FakeShellPort noIdentity = new FakeShellPort().returns("ensureSession", false)
                .returns("loadIdentity", null);
        noIdentity.returns("peerGuard",
                noIdentity.stub(MlsPeerGuards.class, "allowSelfDeparture", true));
        assertEquals(GroupMembershipRouting.PLAINTEXT,
                MlsMembership.leaveGroup(noIdentity.port(), MlsLogSink.NONE, "grp"));
    }


    @Test
    public void anIconChangeIsOnlyEverAnEncryptedFlow() {
        assertEquals(GroupMembershipRouting.PLAINTEXT, MlsMembership.iconChangeRouting(
                new FakeShellPort().port(), MlsLogSink.NONE, "", new byte[] {1}, "image/png"));
        final FakeShellPort plain = planePort(new FakeRecords()).returns("getGroup", null)
                .returns("conversationMlsBit", Boolean.FALSE);
        assertEquals("a plaintext group has no icon RPC on this path",
                GroupMembershipRouting.REFUSED,
                MlsMembership.iconChangeRouting(plain.port(), MlsLogSink.NONE, "grp",
                        new byte[] {1}, "image/png"));
        final FakeShellPort mls =
                planePort(storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY)));
        assertEquals(GroupMembershipRouting.REFUSED,
                MlsMembership.iconChangeRouting(mls.port(), MlsLogSink.NONE, "grp", new byte[0],
                        "image/png"));
    }


    @Test
    public void aRenameOfAPlaintextOrDowngradedGroupTakesTheBareRpcAndALockedOutOneIsRefused() {
        final FakeShellPort plain = planePort(new FakeRecords()).returns("getGroup", null)
                .returns("conversationMlsBit", Boolean.FALSE);
        assertEquals(GroupMembershipRouting.PLAINTEXT, MlsMembership.renameGroupRouting(
                plain.port(), MlsLogSink.NONE, "grp", "New"));
        final FakeShellPort down =
                planePort(storeWith(b -> b.healthStatus(MlsHealthStates.DONEENDMLS)));
        assertEquals(GroupMembershipRouting.PLAINTEXT, MlsMembership.renameGroupRouting(down.port(),
                MlsLogSink.NONE, "grp", "New"));
        final FakeShellPort locked = planePort(new FakeRecords()).returns("getGroup", null)
                .returns("conversationMlsBit", Boolean.TRUE);
        assertEquals(GroupMembershipRouting.REFUSED, MlsMembership.renameGroupRouting(locked.port(),
                MlsLogSink.NONE, "grp", "New"));
    }


    @Test
    public void aMembershipChangeRoutesByPlaneAndTakesOneMemberAtATime() {
        assertEquals(GroupMembershipRouting.REFUSED, MlsMembership.changeGroupMembership(
                new FakeShellPort().port(),
                MlsLogSink.NONE, "grp", java.util.Collections.emptyList(), true));
        final FakeShellPort plain = planePort(new FakeRecords()).returns("getGroup", null)
                .returns("conversationMlsBit", Boolean.FALSE);
        assertEquals(GroupMembershipRouting.PLAINTEXT, MlsMembership.changeGroupMembership(
                plain.port(),
                MlsLogSink.NONE, "grp", java.util.Collections.singletonList("+3"), true));
        final FakeShellPort mls =
                planePort(storeWith(b -> b.healthStatus(MlsHealthStates.HEALTHY)));
        assertEquals(GroupMembershipRouting.REFUSED, MlsMembership.changeGroupMembership(mls.port(),
                MlsLogSink.NONE, "grp", java.util.Arrays.asList("+3", "+4"), true));
    }
}
