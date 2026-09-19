/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsGroupEstablishSplitTest {


    private static final java.util.List<String> MEMBERS = java.util.Arrays.asList("+2", "+3");

    private static MlsGroupArtifacts art() {
        return new MlsGroupArtifacts(new byte[] {2}, new byte[] {9}, new byte[] {8}, new byte[] {7},
                GID, null, 1L, MlsWelcomeAction.NEW_GROUP, null);
    }

    /**
     * No local group; every member has a KeyPackage; the engine builds {@code art}; the provider
     * answers as given.
     */
    private static FakeShellPort groupPort(final MlsProviderRpc.ControlResult created,
            final long[] serverEra, final boolean[] rolledBack) {
        final java.util.Map<String, Group> groups = new java.util.HashMap<>();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("getGroup", null)
                .returns("groups", groups)
                .on("putGroup", a -> { groups.put((String) a[0], (Group) a[1]); return null; })
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .on("claimAll",
                        a -> Claim.asked(java.util.Collections.singletonList(new byte[] {4})))
                .returns("keyPackageUsable", true)
                .returns("lookServerEraEpoch", Look.asked(serverEra))
                .returns("lookServerEpochAuthenticator", Look.asked(new byte[] {1}));
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "allowJoiningPeer", true));
        f.returns("session", f.stub(MlsSession.class, "exportGroupSnapshot", new byte[] {5},
                "epochAuth", new byte[] {1},
                "createGroupPlanned", art(), "groupInfoExt", new byte[] {0, 0, 0, 1}, "eraEpoch",
                new byte[12], "discardGroup",
                (Function<Object[], Object>) a -> { rolledBack[0] = true; return true; },
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { rolledBack[0] = true; return true; }));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "createMlsConversation", created,
                "sendMlsNegativeDeliveryImdn", true));
        return f;
    }

    private static int establish(final FakeShellPort f) {
        return MlsGroupEstablish.establishGroup(f.port(), MlsLogSink.NONE, "grp", MEMBERS, null,
                null);
    }

    private static final MlsProviderRpc.ControlResult OK =
            new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null, null);

    @Test
    public void anAcceptedCreateTheServerConfirmsIsAdopted() {
        final boolean[] rolled = {false};
        final FakeShellPort f = groupPort(OK, new long[] {1, 0}, rolled);
        assertEquals(1, establish(f));
        assertFalse(rolled[0]);
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("moveHealth(g:grp, " + MlsHealthStates.HEALTHY)));
    }

    @Test
    public void aRefusedCreateOrOneTheServerDidNotTakeAtOurEraIsRolledBack() {
        final boolean[] refused = {false};
        assertEquals(-1, establish(groupPort(
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"),
                new long[] {1, 0}, refused)));
        assertTrue(refused[0]);
        final boolean[] wrongEra = {false};
        final FakeShellPort f = groupPort(OK, new long[] {5, 0}, wrongEra);
        assertEquals(-1, establish(f));
        assertTrue(wrongEra[0]);
        assertTrue("and a re-Welcome is asked for",
                f.calls.contains("MlsProviderRpc.sendMlsNegativeDeliveryImdn"));
    }

    @Test
    public void nothingIsCreatedForBadArgumentsARefusedJoinOrAGroupWeAlreadyHold() {
        final FakeShellPort f = groupPort(OK, new long[] {1, 0}, new boolean[] {false});
        assertEquals(-1, MlsGroupEstablish.establishGroup(f.port(), MlsLogSink.NONE, "", MEMBERS,
                null, null));
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "allowJoiningPeer", false));
        assertEquals(-1, establish(f));
        final Group held = grp();
        final FakeShellPort h =
                groupPort(OK, new long[] {1, 0}, new boolean[] {false}).returns("getGroup", held);
        assertEquals((int) held.era, establish(h));
        assertFalse(h.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }


    @Test
    public void theThreeArgumentEstablishHasNoPreClaim() {
        final FakeShellPort f = new FakeShellPort();
        assertEquals(-1,
                MlsGroupEstablish.establishGroup(f.port(), MlsLogSink.NONE, "", MEMBERS, null));
    }
}
