/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsEraAdvanceSplitTest {


    private static FakeShellPort eraPort(final MlsGroupArtifacts art,
            final MlsProviderRpc.ControlResult r, final boolean[] restored) {
        final FakeShellPort f = new FakeShellPort().on("putGroup", a -> null)
                .returns("conv", new ConvState())
                .returns("quarantineIfAheadOfServer", EraReconcile.unknown());
        f.returns("session", f.stub(MlsSession.class, "exportGroupSnapshot", new byte[] {5},
                "epochAuth", new byte[] {1}, "commitEraAdvance", art,
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { restored[0] = true; return true; }));
        f.returns("rpc",
                f.stub(MlsProviderRpc.class, "applyMlsControl", r, "createMlsConversation", r));
        return f;
    }

    private static MlsGroupArtifacts art() {
        return new MlsGroupArtifacts(null, new byte[] {9}, new byte[] {8}, new byte[] {7}, GID);
    }

    @Test
    public void anAcceptedAdvanceTakesTheNewEraAndARefusedOneRollsBack() {
        final Group g = grp();
        final boolean[] restored = {false};
        final MlsProviderRpc.ControlResult ok = new MlsProviderRpc.ControlResult(
                MlsProviderRpc.ControlResult.VERDICT_OK, null, null);
        assertEquals(3, MlsEraAdvance.eraAdvancePreserving(eraPort(art(), ok, restored).port(),
                MlsLogSink.NONE, KEY, g, "grp", "+2", 2, 3, MlsEraAdvance.ERA_MODE_PRESERVE_CTRL));
        assertEquals(3, g.era);
        assertArrayEquals(new byte[] {7}, g.epochAuth);
        assertFalse(restored[0]);
        final MlsProviderRpc.ControlResult no = new MlsProviderRpc.ControlResult(
                MlsProviderRpc.ControlResult.VERDICT_REJECTED, null, "no");
        final FakeShellPort refused = eraPort(art(), no, restored);
        assertEquals(-1, MlsEraAdvance.eraAdvancePreserving(refused.port(), MlsLogSink.NONE, KEY,
                grp(), "grp", "+2", 2, 3, 0));
        assertTrue("the engine is put back where it was", restored[0]);
        assertTrue("the non-CTRL mode creates",
                refused.calls.contains("MlsProviderRpc.createMlsConversation"));
    }

    @Test
    public void anAdvanceTheEngineCouldNotBuildIsRolledBackBeforeAnythingIsSent() {
        final boolean[] restored = {false};
        final FakeShellPort f = eraPort(null, null, restored);
        assertEquals(MlsEraAdvance.BUILD_FAILED, MlsEraAdvance.eraAdvancePreserving(f.port(),
                MlsLogSink.NONE, KEY, grp(), "grp", "+2", 2, 3, 0));
        assertTrue(restored[0]);
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("rpc(")));
    }


    @Test
    public void theAdvanceModeIsTheConfiguredOne() {
        assertEquals(MlsConfig.defaults().eraAdvanceMode,
                MlsEraAdvance.eraAdvanceMode(MlsConfig.defaults()));
    }


    @Test
    public void theLeverDiagnosticNamesTheModeTheOperatorSet() {
        final String d = MlsEraAdvance.eraAdvanceLeverDiagnostic(new FakeShellPort()
                .returns("sysprops",
                        new FakeSysProps().set("debug.rcs.mls_advance_fresh_ctxid", "all")).port());
        assertTrue(d, d.contains("all"));
    }


    @Test
    public void theEraAskedForIsAHighWaterMark() {
        final java.util.Map<String, Integer> asked = new java.util.HashMap<>();
        final FakeShellPort f = new FakeShellPort().returns("eraAsked", asked);
        MlsEraAdvance.noteEraAsked(f.port(), "g:grp", 3);
        MlsEraAdvance.noteEraAsked(f.port(), "g:grp", 2);
        MlsEraAdvance.noteEraAsked(f.port(), "g:grp", 0);
        assertEquals(Integer.valueOf(3), asked.get("g:grp"));
    }


    @Test
    public void aReportedQuotaIsRememberedPerConversation() {
        final java.util.Set<String> reported = new java.util.HashSet<>();
        MlsEraAdvance.noteEraQuotaReported(
                new FakeShellPort().returns("eraQuotaReported", reported).port(),
                MlsLogSink.NONE, "g:grp");
        assertTrue(reported.contains("g:grp"));
    }


    /**
     * A 1:1 advance of {@code +2}: the server is at era 1, the verify look answers {@code
     * verified}.
     */
    private static FakeShellPort advancePort(final MlsGroupArtifacts art,
            final MlsProviderRpc.ControlResult created,
            final long[] verified, final boolean[] restored) {
        return advancePort(art, created, verified, restored, false, false);
    }

    /**
     * As above, with {@code 0xF002} present {@code before} and in the built group per
     * {@code after}.
     */
    private static FakeShellPort advancePort(final MlsGroupArtifacts art,
            final MlsProviderRpc.ControlResult created,
            final long[] verified, final boolean[] restored, final boolean before,
            final boolean after) {
        final int[] endMlsReads = {0};
        final int[] looks = {0};
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "p:+2")
                .on("putGroup", a -> null).returns("conv", new ConvState())
                .returns("convIfAny", null)
                .returns("eraAsked", new java.util.HashMap<String, Integer>())
                .returns("sysprops", new FakeSysProps())
                .on("lookServerEraEpoch",
                        a -> Look.asked(looks[0]++ == 0 ? new long[] {1, 0} : verified))
                .on("claimOne", a -> Claim.asked(new byte[] {4})).returns("keyPackageUsable", true)
                .returns("quarantineIfAheadOfServer", EraReconcile.unknown());
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", new byte[12], "epochAuth",
                new byte[] {1}, "exportGroupSnapshot", new byte[] {5}, "createGroupPlanned", art,
                "inspectKeyPackage", new MlsSession.KeyPackageInfo(false, 0L, "", 0, null, 0L,
                        System.currentTimeMillis() / 1000L + 90 * 86400L),
                "restoreGroupSnapshot",
                (Function<Object[], Object>) a -> { restored[0] = true; return true; },
                "endMlsPresent",
                (Function<Object[], Object>) a -> endMlsReads[0]++ == 0 ? before : after));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "createMlsConversation", created));
        return f;
    }

    private static MlsGroupArtifacts era2() {
        return new MlsGroupArtifacts(new byte[] {2}, new byte[] {9}, new byte[] {8}, new byte[] {7},
                GID, null, 2L, MlsWelcomeAction.NEW_ERA_EXISTING_GROUP, null);
    }

    private static int advance(final FakeShellPort f) {
        return MlsEraAdvance.eraAdvanceLocked(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, null,
                "+2", null, MlsAdvanceEraKind.NORMAL, false);
    }

    private static final MlsProviderRpc.ControlResult OK =
            new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_OK, null, null);

    @Test
    public void anAdvanceTheServerVerifiesTakesTheNewEra() {
        final boolean[] restored = {false};
        final FakeShellPort f = advancePort(era2(), OK, new long[] {2, 0}, restored);
        assertEquals(2, advance(f));
        assertFalse(restored[0]);
        assertTrue(f.calls.stream().anyMatch(c -> c.startsWith("putGroup(p:+2")));
    }

    @Test
    public void aBuiltGroupWhoseEndMlsDisagreesWithTheKindIsRolledBackUnsent() {
        final boolean[] restored = {false};
        final FakeShellPort dropped = advancePort(era2(), OK, new long[] {2, 0}, restored,
                /*before=*/ true, /*after=*/ false);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals("a NORMAL advance that lost end_mls would silently revive the group", -1,
                MlsEraAdvance.eraAdvanceLocked(MlsConfig.defaults(), dropped.port(), log, null,
                        "+2", null, MlsAdvanceEraKind.NORMAL, false));
        assertTrue(restored[0]);
        assertFalse(dropped.calls.contains("MlsProviderRpc.createMlsConversation"));
        assertTrue(log.said("E", "built a group with end_mls ABSENT"));

        final boolean[] kept = {false};
        assertEquals("a revival that kept it is the other skew", -1,
                MlsEraAdvance.eraAdvanceLocked(MlsConfig.defaults(), advancePort(era2(), OK,
                        new long[] {2, 0}, kept, true, true).port(), MlsLogSink.NONE, null,
                        "+2", null, MlsAdvanceEraKind.REVIVAL, false));
        assertTrue(kept[0]);

        final boolean[] revived = {false};
        assertEquals("a revival that removed it proceeds", 2,
                MlsEraAdvance.eraAdvanceLocked(MlsConfig.defaults(), advancePort(era2(), OK,
                        new long[] {2, 0}, revived, true, false).port(), MlsLogSink.NONE, null,
                        "+2", null, MlsAdvanceEraKind.REVIVAL, false));
        assertFalse(revived[0]);
    }

    @Test
    public void theCreateRecordsItsVerdictForTheDriveLoop() {
        final ConvState cs = new ConvState();
        final FakeShellPort f = advancePort(era2(), null, new long[] {2, 0}, new boolean[] {false})
                .returns("conv", cs);
        assertEquals(-1, advance(f));
        assertEquals("a null result is a transport failure",
                MlsProviderRpc.ControlResult.VERDICT_TRANSPORT_FAILED, cs.lastControlVerdict);
    }

    @Test
    public void aRefusedAdvanceRollsTheEngineBack() {
        final boolean[] restored = {false};
        assertEquals(-1, advance(advancePort(era2(),
                new MlsProviderRpc.ControlResult(MlsProviderRpc.ControlResult.VERDICT_REJECTED,
                        null, "no"),
                new long[] {2, 0}, restored)));
        assertTrue(restored[0]);
    }

    @Test
    public void noGroupOrARefusedBaseLookAdvancesNothing() {
        assertEquals(-1, advance(advancePort(era2(), OK, new long[] {2, 0}, new boolean[] {false})
                .returns("getGroup", null)));
        final FakeShellPort refused = advancePort(era2(), OK, new long[] {2, 0},
                new boolean[] {false})
                .returns("lookServerEraEpoch", Look.refusedByLedger("no"));
        assertEquals(MlsEraAdvance.ERA_ADVANCE_LOOK_UNAVAILABLE, advance(refused));
        assertFalse(refused.calls.stream()
                .anyMatch(c -> c.startsWith("rpc(") || c.startsWith("claimOne")));
    }


    @Test
    public void theQuotaBindsWhenTheServerStaysBelowTheEraWeAskedFor() {
        final java.util.Map<String, Integer> asked = new java.util.HashMap<>();
        asked.put("g:grp", 4);
        final FakeShellPort f = new FakeShellPort().returns("eraAsked", asked)
                .returns("eraQuotaReported", new java.util.HashSet<String>())
                .returns("lookServerEraEpoch", Look.asked(new long[] {3, 0}));
        assertTrue(MlsEraAdvance.eraQuotaBound(f.port(), MlsLogSink.NONE, "g:grp", "grp", "+2"));
        f.returns("lookServerEraEpoch", Look.asked(new long[] {4, 0}));
        assertFalse(MlsEraAdvance.eraQuotaBound(f.port(), MlsLogSink.NONE, "g:grp", "grp", "+2"));
        asked.clear();
        assertFalse("we never asked",
                MlsEraAdvance.eraQuotaBound(f.port(), MlsLogSink.NONE, "g:grp", "grp", "+2"));
    }


    @Test
    public void eraAdvanceWithKindAdvancesWithoutACarriedGroupInfoOrARosterRequirement() {
        final Object[][] seen = {null};
        final FakeShellPort f =
                new FakeShellPort().on("eraAdvance", a -> { seen[0] = a; return 7; });
        assertEquals(7,
                MlsEraAdvance.eraAdvanceWithKind(f.port(), "grp", "+2", MlsAdvanceEraKind.REVIVAL));
        assertNull(seen[0][2]);
        assertEquals(MlsAdvanceEraKind.REVIVAL, seen[0][3]);
        assertEquals(false, seen[0][4]);
    }


    private static int funnelAdvance(final FakeShellPort f) {
        return MlsEraAdvance.eraAdvance(MlsConfig.defaults(), f.port(), MlsLogSink.NONE, "grp",
                "+2", null, MlsAdvanceEraKind.NORMAL, false);
    }

    @Test
    public void theFunnelRefusesBeforeItClaimsTheSlot() {
        assertEquals(-1, funnelAdvance(
                SplitFixtures.port(storeWith(b -> b)).returns("ensureSession", false)));
        final FakeShellPort guarded =
                SplitFixtures.port(storeWith(b -> b)).returns("sysprops", new FakeSysProps());
        guarded.returns("peerGuard", guarded.stub(MlsPeerGuards.class, "allowEraAdvance", false));
        assertEquals(-1, funnelAdvance(guarded));
    }

    @Test
    public void aSecondAdvanceWhileOneIsPendingIsRefused() {
        final FakeRecords s =
                storeWith(b -> b.pendingOperation(op(MlsPendingOperation.Kind.ERA_ADVANCEMENT)));
        final FakeShellPort f = SplitFixtures.port(s).returns("sysprops", new FakeSysProps())
                .returns("pendingOpsClaimedThisProcess",
                        new java.util.HashSet<>(java.util.Collections.singleton(KEY)));
        f.returns("peerGuard", f.stub(MlsPeerGuards.class, "allowEraAdvance", true));
        f.returns("session", f.stub(MlsSession.class, "commitRequired", false, "lastStatus",
                MlsSession.OpStatus.OK));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertEquals(-1, MlsEraAdvance.eraAdvance(MlsConfig.defaults(), f.port(), log, "grp", "+2",
                null, MlsAdvanceEraKind.NORMAL, false));
        assertTrue(log.said("W", "era advancement already pending"));
    }
}
