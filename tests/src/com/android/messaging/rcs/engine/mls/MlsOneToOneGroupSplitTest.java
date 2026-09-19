/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsOneToOneGroupSplitTest {


    /**
     * A port whose cache holds {@code cached}, whose provider knows {@code providerGid}, and whose
     * claim and create answer as given.
     */
    private static FakeShellPort readyPort(final Group cached, final byte[] providerGid,
            final Claim<byte[]> claim,
            final MlsGroupArtifacts art, final MlsProviderRpc.ControlResult created,
            final boolean[] rolledBack) {
        final java.util.Map<String, Group> groups = new java.util.HashMap<>();
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b)).returns("getGroup", cached)
                .returns("convAlias", new java.util.HashMap<String, String>())
                .returns("keyToConvId", new java.util.HashMap<String, String>())
                .returns("groups", groups).returns("conv", new ConvState())
                .on("putGroup", a -> { groups.put((String) a[0], (Group) a[1]); return null; })
                .on("claimOne", a -> claim).returns("keyPackageUsable", true)
                .returns("lookServerEraEpoch", Look.asked(null));
        f.returns("session", f.stub(MlsSession.class, "commitRequired", false, "lastStatus",
                MlsSession.OpStatus.OK,
                "eraEpoch", new byte[12], "epochAuth", new byte[] {1}, "createGroupPlanned", art,
                "discardGroup",
                (Function<Object[], Object>) a -> { rolledBack[0] = true; return true; },
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { rolledBack[0] = true; return true; }));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "getMlsGroupIdForPeer", providerGid,
                "createMlsConversation", created));
        return f;
    }

    private static MlsGroupArtifacts art() {
        return new MlsGroupArtifacts(new byte[] {2}, new byte[] {9}, new byte[] {8}, new byte[] {7},
                GID, null, 1L, MlsWelcomeAction.NEW_GROUP, null);
    }

    private static boolean ready(final FakeShellPort f) {
        return MlsOneToOneGroup.ensureReady(f.port(), MlsLogSink.NONE, "conv1", 1,
                java.util.Collections.singletonList("+2"), false, false);
    }

    @Test
    public void aCachedGroupThatStillLoadsIsReadyWithoutAskingAnyone() {
        final FakeShellPort f = readyPort(grp(), null, null, null, null, new boolean[] {false});
        assertTrue(ready(f));
        assertFalse(
                f.calls.stream().anyMatch(c -> c.startsWith("rpc(") || c.startsWith("claimOne")));
    }

    @Test
    public void aLoadableGroupTheProviderKnowsIsAdoptedNotRecreated() {
        final FakeShellPort f = readyPort(null, GID, null, null, null, new boolean[] {false});
        assertTrue(ready(f));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("claimOne")));
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(p:+2")));
    }

    @Test
    public void aFreshGroupIsCreatedAroundTheClaimedKeyPackage() {
        final FakeShellPort f = readyPort(null, null, Claim.asked(new byte[] {4}), art(),
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null,
                        null), new boolean[] {false});
        assertTrue(ready(f));
        assertTrue(f.calls.contains("MlsProviderRpc.createMlsConversation"));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("moveHealth(p:+2, " + MlsHealthStates.HEALTHY)));
    }

    @Test
    public void aRefusedClaimOrARefusedCreateLeavesNoGroup() {
        assertFalse(ready(readyPort(null, null, Claim.refusedByLedger("budget"), null, null,
                new boolean[] {false})));
        final boolean[] rolled = {false};
        final FakeShellPort f = readyPort(null, null, Claim.asked(new byte[] {4}), art(),
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"), rolled);
        assertFalse(ready(f));
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("putGroup")));
        assertFalse(ready(new FakeShellPort().returns("ensureSession", false)));
    }
}
