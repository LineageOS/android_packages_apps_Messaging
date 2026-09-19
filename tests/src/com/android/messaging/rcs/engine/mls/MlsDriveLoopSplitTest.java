/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

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

public final class MlsDriveLoopSplitTest {


    @Test
    public void schedulingForIsNormalWithoutState() {
        assertEquals(MlsSchedulingType.NORMAL, MlsDriveLoop.schedulingFor(new FakeShellPort()
                .returns("convIfAny", null).port(), "g:x"));
    }


    @Test
    public void stampSchedulingSetsAMarkerAndNormalClearsItWithoutCreatingState() {
        final ConvState cs = new ConvState();
        final MlsSchedulingType marker =
                MlsSchedulingType.values()[MlsSchedulingType.values().length - 1];
        final FakeShellPort f = new FakeShellPort().returns("conv", cs).returns("convIfAny", cs);
        MlsDriveLoop.stampScheduling(f.port(), "g:x", marker);
        assertSame(marker, MlsDriveLoop.schedulingFor(f.port(), "g:x"));
        MlsDriveLoop.stampScheduling(f.port(), "g:x", MlsSchedulingType.NORMAL);
        assertNull(cs.scheduling);
        assertEquals(MlsSchedulingType.NORMAL, MlsDriveLoop.schedulingFor(f.port(), "g:x"));
        MlsDriveLoop.stampScheduling(new FakeShellPort().returns("convIfAny", null).port(), "g:y",
                null);
    }


    @Test
    public void mayScheduleOnlyFromANormalFlowAndSaysWhyNot() {
        assertTrue(MlsDriveLoop.maySchedule(new FakeShellPort().returns("convIfAny", null).port(),
                MlsLogSink.NONE, "g:x", "a rekey"));
        final ConvState cs = new ConvState();
        for (final MlsSchedulingType t : MlsSchedulingType.values()) {
            if (t.allowsScheduling()) continue;
            cs.scheduling = t;
            final FakeShellPort.Log log = new FakeShellPort.Log();
            assertFalse(MlsDriveLoop.maySchedule(
                    new FakeShellPort().returns("convIfAny", cs).port(), log, "g:x", "a rekey"));
            assertTrue(log.said("I", "already part of a " + t + " flow"));
        }
    }


    @Test
    public void theReconcileDriveIsBoundedByTheRecoveryLookBudget() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "g:grp")
                .returns("driveLoop", new MlsDriveLoop(MlsTelemetry.NONE))
                .returns("conv", new ConvState())
                .returns("lookServerEraEpoch", Look.refusedByLedger("no"));
        assertNotNull(
                MlsDriveLoop.driveReconcileInner(f.port(), MlsLogSink.NONE, "g:grp", "grp", "+2"));
        final long looks =
                f.calls.stream().filter(c -> c.startsWith("lookServerEraEpoch(")).count();
        assertTrue(looks >= 1 && looks <= MlsFetchBudget.RECOVERY_LOOKS);
    }


    @Test
    public void theReconcileDriveRunsForTheConversationsCanonicalKey() {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "g:grp")
                .returns("driveLoop", new MlsDriveLoop(MlsTelemetry.NONE))
                .returns("conv", new ConvState())
                .returns("lookServerEraEpoch", Look.refusedByLedger("no"));
        assertNotNull(MlsDriveLoop.driveReconcile(f.port(), MlsLogSink.NONE, "grp", "+2"));
    }

    private static MlsHostAction retry(final String why) {
        return MlsHostAction.withRedrive(MlsHostAction.Kind.NONE, MlsResultStatus.FAIL_RETRY,
                MlsGroupSnapshot.NONE, MlsHostAction.Redrive.NOT_IN_THIS_DRIVE, why);
    }

    /** A pass that ends FAIL_RETRY after a provider call lost connectivity leaves the drive. */
    @Test
    public void aRetryAfterALostConnectionThrowsSoTheDriveIsAbandoned() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("conv", cs);
        try {
            MlsDriveLoop.livePass(f.port(), "g:grp", "reconcile pass", () -> {
                MlsDriveLoop.noteControlVerdict(f.port(), "g:grp", null);
                return retry("create failed");
            });
            org.junit.Assert.fail("a transport failure must surface as ConnectivityLost");
        } catch (final MlsTransportDisposition.ConnectivityLost lost) {
            assertEquals(MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED, lost.verdict);
            assertTrue(lost.getMessage().contains("reconcile pass"));
        }
    }

    /** A refusal on the merits, a success, or no call at all is returned as it was. */
    @Test
    public void onlyALostConnectionUnderARetryIsTurnedIntoAnAbandon() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = new FakeShellPort().returns("conv", cs);
        final MlsHostAction refused = retry("refused");
        assertSame(refused, MlsDriveLoop.livePass(f.port(), "g:grp", "p", () -> {
            MlsDriveLoop.noteControlVerdict(f.port(), "g:grp", new MlsProviderRpc.ControlResult(
                    MlsProviderRpc.ControlResult.VERDICT_REJECTED, null, "no"));
            return refused;
        }));
        final MlsHostAction healed = MlsHostAction.withStatus(MlsHostAction.Kind.NONE,
                MlsResultStatus.SUCCESS, MlsGroupSnapshot.NONE, "ok");
        assertSame(healed, MlsDriveLoop.livePass(f.port(), "g:grp", "p", () -> {
            MlsDriveLoop.noteControlVerdict(f.port(), "g:grp", null);
            return healed;
        }));
        cs.lastControlVerdict = MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED;
        final MlsHostAction behind = retry("behind; no call made");
        assertSame("a verdict left by an earlier pass is cleared first", behind,
                MlsDriveLoop.livePass(f.port(), "g:grp", "p", () -> behind));
        assertEquals(-1, cs.lastControlVerdict);
    }

    /**
     * The reconcile drive runs every pass through livePass, so the catch in driveReconcile can
     * fire.
     */
    @Test
    public void theReconcileDriveRunsEachPassThroughLivePass() throws java.io.IOException {
        final String src = com.android.messaging.rcs.SourceScan.codeOnly(
                com.android.messaging.rcs.SourceScan.read(
                        "engine/src/com/android/messaging/rcs/engine/mls/MlsDriveLoop.java"));
        final String inner = com.android.messaging.rcs.SourceScan.bodyOf(src,
                "driveReconcileInner");
        final int live = inner.indexOf("MlsDriveLoop.livePass(");
        final int action = inner.indexOf("MlsConversationRebuild.reconcileAction(");
        assertEquals(1, com.android.messaging.rcs.SourceScan.count(inner,
                "MlsConversationRebuild.reconcileAction("));
        assertTrue("reconcileAction is called inside the livePass call (" + live + " < " + action
                + ")", live >= 0 && live < action && action < inner.indexOf(");", live) + 400);
        final String outer = com.android.messaging.rcs.SourceScan.bodyOf(src, "driveReconcile");
        assertTrue(outer.indexOf("driveReconcileInner(")
                < outer.indexOf("catch (final MlsTransportDisposition.ConnectivityLost"));
    }

    /**
     * Every create RPC records its verdict in the next statement, into the variable it assigned;
     * without it {@link MlsDriveLoop#livePass} reads a cleared verdict and cannot fire.
     */
    @Test
    public void everyCreateRpcRecordsItsVerdictNext() throws java.io.IOException {
        int creates = 0;
        for (final java.io.File file :
                com.android.messaging.rcs.SourceScan.javaSourcesUnder("engine/src")) {
            final String code = com.android.messaging.rcs.SourceScan.codeOnly(new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8));
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(\\w+)\\s*=\\s*pt\\.createMlsConversation\\(").matcher(code);
            while (m.find()) {
                creates++;
                final int end = code.indexOf(';', m.end());
                final int next = code.indexOf(';', end + 1);
                // A create inside an if/else is recorded after the closing brace.
                final String stmt = code.substring(end + 1, next + 1).replaceFirst("^[}\\s]+", "");
                assertEquals(file.getName() + " at " + m.start(),
                        "MlsDriveLoop.noteControlVerdict(shell, key, " + m.group(1) + ");", stmt);
            }
            if (!file.getName().equals("MlsProviderRpc.java")) {
                assertEquals(file.getName() + ": a create not assigned to a variable escapes the "
                        + "scan above", com.android.messaging.rcs.SourceScan.count(code,
                        "pt.createMlsConversation("), countMatches(code));
            }
        }
        assertEquals("the create sites: era advance (2), fresh-context re-dial, group establish, "
                + "1:1 establish (3)", 7, creates);
    }

    private static int countMatches(final String code) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\w+)\\s*=\\s*pt\\.createMlsConversation\\(").matcher(code);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
