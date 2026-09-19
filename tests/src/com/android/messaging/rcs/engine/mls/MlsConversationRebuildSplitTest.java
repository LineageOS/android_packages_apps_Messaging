/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerPack;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsConversationRebuildSplitTest {


    /** A 1:1 rebuild of {@code +2}: every bound says yes unless a test says otherwise. */
    private static FakeShellPort rebuildPort() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "p:+2")
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .returns("lookServerEraEpoch", Look.asked(new long[] {3, 0}))
                .returns("sysprops", new FakeSysProps());
        f.returns("peerGuard",
                f.stub(MlsPeerGuards.class, "claimRebuildEpisode", true, "allowEraAdvance", true));
        f.returns("rebuildLimiter", f.stub(MlsRebuildLimits.class, "claim", false));
        return f;
    }

    private static MlsRebuildOutcome rebuild(final FakeShellPort f) {
        return MlsConversationRebuild.rebuildConversation(f.port(), MlsLogSink.NONE, null, "+2",
                "test", MlsRecreationEpisode.priorCharge(false, false));
    }

    @Test
    public void everyBoundIsAskedBeforeAnythingIsForgotten() {
        assertSame(MlsRebuildOutcome.COULD_NOT_ATTEMPT,
                rebuild(rebuildPort().returns("ensureSession", false)));
        final FakeShellPort episode = rebuildPort();
        episode.returns("peerGuard",
                episode.stub(MlsPeerGuards.class, "claimRebuildEpisode", false));
        assertSame(MlsRebuildOutcome.SUPPRESSED_AS_ONE_EPISODE, rebuild(episode));
        assertSame(MlsRebuildOutcome.DEFERRED_BY_OUR_OWN_LEDGER,
                rebuild(rebuildPort().returns("lookServerEraEpoch", Look.refusedByLedger("no"))));
        final FakeShellPort limited = rebuildPort();
        assertSame(MlsRebuildOutcome.RATE_LIMITED, rebuild(limited));
        assertFalse("a refused rebuild forgets nothing",
                limited.calls.stream()
                .anyMatch(c -> c.startsWith("forget(") || c.startsWith("rpc(")));
    }


    private static FakeShellPort leverPort() {
        final FakeShellPort f = new FakeShellPort();
        f.returns("rebuildLimiter", f.stub(MlsRebuildLimits.class, "reset", null));
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "resetRebuildEpisode", null));
        return f;
    }

    @Test
    public void resettingTheRebuildBoundClearsBothTheRateAndTheEpisode() {
        final FakeShellPort f = leverPort();
        MlsConversationRebuild.resetRebuildRateBound(f.port(), "g:grp");
        assertTrue(f.calls.contains("MlsRebuildLimits.reset"));
        assertTrue(f.calls.contains("MlsPeerGuards.resetRebuildEpisode"));
        final FakeShellPort none = new FakeShellPort();
        MlsConversationRebuild.resetRebuildRateBound(none.port(), null);
        assertTrue(none.calls.isEmpty());
    }


    @Test
    public void theDebugLeverResetsTheSameTwoBoundsForAConversation() {
        final FakeShellPort f = leverPort();
        MlsConversationRebuild.debugResetRebuildAllowance(f.port(), MlsLogSink.NONE, "grp", "+2");
        assertTrue(f.calls.contains("MlsRebuildLimits.reset"));
        assertTrue(f.calls.contains("MlsPeerGuards.resetRebuildEpisode"));
    }


    @Test
    public void aStrandedConversationIsRebuiltAndARefusedRebuildIsSurfacedAndReDrivenLater() {
        final FakeShellPort f = rebuildPort().returns("getGroup", null).on("raiseStall", a -> null);
        final MlsHostAction a =
                MlsConversationRebuild.reconcileAction(f.port(), MlsLogSink.NONE, null, "+2");
        assertSame(MlsHostAction.Kind.ERA_ADVANCE_REQUIRED, a.kind);
        assertSame(MlsResultStatus.FAIL_RETRY, a.status);
        assertTrue("RATE_LIMITED needs a person: the stall alert goes up",
                f.calls.stream().anyMatch(c -> c.startsWith("raiseStall(p:+2")));
    }

    @Test
    public void aRefusedOrUnreadableHealthLookIsRetriedAfterACooldownWithoutRebuilding() {
        final MlsHostAction refused = MlsConversationRebuild.reconcileAction(rebuildPort()
                .returns("lookServerEraEpoch", Look.refusedByLedger("no")).port(), MlsLogSink.NONE,
                null, "+2");
        assertSame(MlsHostAction.Kind.NONE, refused.kind);
        assertSame(MlsHostAction.Redrive.AFTER_A_COOLDOWN, refused.redrive);
        final FakeShellPort unknown = rebuildPort().returns("lookServerEraEpoch", Look.asked(null));
        assertSame(MlsResultStatus.PENDING, MlsConversationRebuild.reconcileAction(unknown.port(),
                MlsLogSink.NONE, null, "+2").status);
        assertFalse(unknown.calls.stream()
                .anyMatch(c -> c.startsWith("forget(") || c.startsWith("rpc(")));
    }


    @Test
    public void theDurableCooldownLeverClearsTheRebuildBoundsAndTheReestablishCooldown() {
        final FakeShellPort f = leverPort();
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "resetRebuildEpisode", null,
                "resetReestablishCooldown", null));
        final String d = MlsConversationRebuild.debugResetDurableCooldowns(f.port(),
                MlsLogSink.NONE, "grp", "+2");
        assertTrue(d, d.startsWith("cleared"));
        assertTrue(f.calls.contains("MlsPeerGuards.resetReestablishCooldown"));
        assertTrue(
                MlsConversationRebuild.debugResetDurableCooldowns(f.port(), MlsLogSink.NONE, null,
                        null)
                .startsWith("NO_KEY"));
    }
}
