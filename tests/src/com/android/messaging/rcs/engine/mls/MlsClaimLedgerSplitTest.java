/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.PendingKeyUpdate;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraReconcile;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ClaimOutcomeSink;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import org.junit.Test;

public final class MlsClaimLedgerSplitTest {


    private static FakeShellPort prefsPort(final FakePrefs p) {
        return new FakeShellPort().returns("prefs", p);
    }

    private static FakeShellPort noPrefs() {
        return new FakeShellPort()
                .on("prefs", a -> { throw new IllegalStateException("no file"); });
    }

    @Test
    public void anUnwrittenClaimLedgerIsEmptyAndAnUnreadableOneIsSpent() {
        final FakePrefs p = new FakePrefs();
        assertSame(MlsClaimLedgerRecord.EMPTY,
                MlsClaimLedger.claimLedgerFor(prefsPort(p).port(), MlsLogSink.NONE, "+2"));
        p.values.put(MlsClaimLedger.claimLedgerPrefKey("+2"), "garbage");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsClaimLedger.claimLedgerFor(prefsPort(p).port(), log, "+2"));
        assertTrue(log.said("W", "STORED and UNREADABLE (7 chars)"));
        final FakeShellPort.Log unread = new FakeShellPort.Log();
        assertNull(MlsClaimLedger.claimLedgerFor(noPrefs().port(), unread, "+2"));
        assertTrue(unread.said("W", "could not READ the claim ledger"));
    }


    @Test
    public void aStoredClaimLedgerReadsBackAsWritten() {
        final FakePrefs p = new FakePrefs();
        final MlsClaimLedgerRecord r =
                MlsClaimLedgerRecord.EMPTY.charged(MlsClaimLedger.Caller.ERA_ADVANCE, 1000L);
        MlsClaimLedger.storeClaimLedger(prefsPort(p).port(), MlsLogSink.NONE, "+2", r);
        assertEquals(r.encode(),
                MlsClaimLedger.claimLedgerFor(prefsPort(p).port(), MlsLogSink.NONE, "+2").encode());
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsClaimLedger.storeClaimLedger(noPrefs().port(), log, "+2", r);
        assertTrue(log.said("W", "could not WRITE the claim ledger"));
    }


    @Test
    public void aClaimNeverSentIsRefundedAndAnUnreadableLedgerIsLeftAlone() {
        final FakePrefs p = new FakePrefs();
        final long now = 5000L;
        final MlsClaimLedgerRecord charged =
                MlsClaimLedgerRecord.EMPTY.charged(MlsClaimLedger.Caller.ERA_ADVANCE, now);
        MlsClaimLedger.storeClaimLedger(prefsPort(p).port(), MlsLogSink.NONE, "+2", charged);
        final ClaimOutcomeSink out = new ClaimOutcomeSink();
        out.detail = "provider down";
        final Claim<byte[]> c = MlsClaimLedger.notAttemptedAfterCharge(prefsPort(p).port(),
                MlsLogSink.NONE, MlsClaimLedger.Caller.ERA_ADVANCE, "+2", now, 3, out);
        assertTrue(c.notAttempted());
        assertEquals(charged.refunded(MlsClaimLedger.Caller.ERA_ADVANCE, now).encode(),
                p.values.get(MlsClaimLedger.claimLedgerPrefKey("+2")));
        p.values.put(MlsClaimLedger.claimLedgerPrefKey("+2"), "garbage");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsClaimLedger.<byte[]>notAttemptedAfterCharge(prefsPort(p).port(), log,
                MlsClaimLedger.Caller.ERA_ADVANCE, "+2", now, 3, out).notAttempted());
        assertTrue(log.said("W", "became UNREADABLE between the charge and the refund"));
        assertEquals("our view is not written over a record we cannot read", "garbage",
                p.values.get(MlsClaimLedger.claimLedgerPrefKey("+2")));
    }


    @Test
    public void theClaimCeilingIsTheSharedOneUnlessTheOperatorSetsIt() {
        final FakeSysProps knobs = new FakeSysProps();
        final FakeShellPort f = new FakeShellPort().returns("sysprops", knobs);
        assertEquals(MlsClaimLedger.SHARED_CEILING, MlsClaimLedger.claimLedgerCeiling(f.port()));
        knobs.set("debug.rcs.mls_claim_ceiling", 3);
        assertEquals(3, MlsClaimLedger.claimLedgerCeiling(f.port()));
    }


    /**
     * The ledger's port: its preferences file, the operator's knobs, and a clock at {@code now}.
     */
    private static FakeShellPort ledgerPort(final FakePrefs p, final FakeSysProps knobs,
            final long now) {
        return new FakeShellPort().returns("prefs", p).returns("sysprops", knobs)
                .returns("elapsedRealtime", now);
    }

    private static MlsClaimLedger.Caller refusable() {
        for (final MlsClaimLedger.Caller c : MlsClaimLedger.Caller.values()) if (
                !c.isUnrefusable()) return c;
        throw new AssertionError("no refusable caller");
    }

    @Test
    public void aClaimIsChargedAndAClaimTheProviderNeverSentIsRefunded() {
        final FakePrefs p = new FakePrefs();
        final Claim<byte[]> asked = MlsClaimLedger.spendOneClaim(
                ledgerPort(p, new FakeSysProps(), 1000L).port(),
                MlsLogSink.NONE, refusable(), "+2", new ClaimOutcomeSink(), () -> new byte[] {1});
        assertArrayEquals(new byte[] {1}, asked.orNull());
        assertEquals(1, MlsClaimLedger.claimLedgerFor(
                ledgerPort(p, new FakeSysProps(), 1000L).port(),
                MlsLogSink.NONE, "+2").spentAgainstCeiling(1000L));
        final ClaimOutcomeSink never = new ClaimOutcomeSink();
        final Claim<byte[]> unsent = MlsClaimLedger.spendOneClaim(
                ledgerPort(p, new FakeSysProps(), 2000L).port(),
                MlsLogSink.NONE, refusable(), "+3", never,
                () -> { never.notAttempted = true; return null; });
        assertTrue(unsent.notAttempted());
        assertEquals("the charge is given back", 0, MlsClaimLedger.claimLedgerFor(
                ledgerPort(p, new FakeSysProps(), 2000L).port(), MlsLogSink.NONE, "+3")
                .spentAgainstCeiling(2000L));
    }


    /**
     * The charge is on disk before the claim is sent, and so is the refund of a claim never sent.
     * A claim spends one of the peer's KeyPackages whether or not we survive to record it.
     */
    @Test
    public void theChargeAndTheRefundAreOnDisk() {
        final FakePrefs p = new FakePrefs();
        final String k = MlsClaimLedger.claimLedgerPrefKey("+2");
        final Claim<byte[]> asked = MlsClaimLedger.spendOneClaim(
                ledgerPort(p, new FakeSysProps(), 1000L).port(), MlsLogSink.NONE, refusable(),
                "+2", new ClaimOutcomeSink(), () -> {
                    assertTrue("the charge was not COMMITTED before the claim, so a crash during "
                            + "the claim loses it", p.onDisk.containsKey(k));
                    assertEquals(1, MlsClaimLedgerRecord.decode((String) p.onDisk.get(k))
                            .spentAgainstCeiling(1000L));
                    return new byte[] {1};
                });
        assertArrayEquals(new byte[] {1}, asked.orNull());
        final ClaimOutcomeSink never = new ClaimOutcomeSink();
        MlsClaimLedger.spendOneClaim(ledgerPort(p, new FakeSysProps(), 2000L).port(),
                MlsLogSink.NONE, refusable(), "+3", never,
                () -> { never.notAttempted = true; return null; });
        assertEquals("the refund is on disk too", 0, MlsClaimLedgerRecord.decode((String)
                p.onDisk.get(MlsClaimLedger.claimLedgerPrefKey("+3"))).spentAgainstCeiling(2000L));
    }

    /**
     * An unreadable record refuses one claim and is then discarded, so the next claim starts from
     * an empty ledger rather than being refused until something replaces the record.
     */
    @Test
    public void anUnreadableClaimLedgerRefusesOnceAndIsThenDiscarded() {
        final FakePrefs p = new FakePrefs();
        final String k = MlsClaimLedger.claimLedgerPrefKey("+2");
        p.values.put(k, "garbage");
        p.onDisk.put(k, "garbage");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsClaimLedger.spendOneClaim(ledgerPort(p, new FakeSysProps(), 1000L).port(),
                log, refusable(), "+2", new ClaimOutcomeSink(),
                () -> { throw new AssertionError("refused"); }).refused());
        assertFalse("the refusal left the unreadable record in place, so it refuses every "
                + "later claim as well", p.values.containsKey(k) || p.onDisk.containsKey(k));
        assertTrue(log.said("W", "DISCARDED"));
        assertFalse("the log promised the record ages out, which an unparseable record cannot do",
                log.said("W", "ages out"));
        assertArrayEquals(new byte[] {1}, MlsClaimLedger.spendOneClaim(
                ledgerPort(p, new FakeSysProps(), 2000L).port(), MlsLogSink.NONE, refusable(),
                "+2", new ClaimOutcomeSink(), () -> new byte[] {1}).orNull());
    }

    @Test
    public void theThreeArgumentClaimAssertsNoOutcome() {
        final Claim<byte[]> c = MlsClaimLedger.spendOneClaim(
                ledgerPort(new FakePrefs(), new FakeSysProps(), 1000L).port(),
                MlsLogSink.NONE, refusable(), "+2", () -> new byte[] {1});
        assertFalse(c.refused());
        assertArrayEquals(new byte[] {1}, c.orNull());
    }


    @Test
    public void onlyAPeerWithNoneIsEvidenceAboutThePeer() {
        assertSame(MlsClaimLedger.Attribution.PEER_HAS_NONE,
                MlsClaimLedger.attributionOf(MlsProviderRpc.ClaimResult.OUTCOME_PEER_HAS_NONE));
        for (final int o : new int[] {MlsProviderRpc.ClaimResult.OUTCOME_SERVED,
                MlsProviderRpc.ClaimResult.OUTCOME_NOT_AUTHORIZED,
                MlsProviderRpc.ClaimResult.OUTCOME_REFUSED,
                MlsProviderRpc.ClaimResult.OUTCOME_TRANSPORT_FAILED,
                MlsProviderRpc.ClaimResult.OUTCOME_NOT_ATTEMPTED}) {
            assertSame(MlsClaimLedger.Attribution.NOT_ABOUT_THE_PEER,
                    MlsClaimLedger.attributionOf(o));
        }
    }

    @Test
    public void claimOneTakesTheFirstPackageAndReportsTheOutcome() {
        final FakePrefs p = new FakePrefs();
        final FakeShellPort f = ledgerPort(p, new FakeSysProps(), 1000L);
        final byte[] packed = MlsArtifactBundle.joinLenPrefixed(
                java.util.Arrays.asList(new byte[] {4}, new byte[] {5}));
        f.returns("rpc", f.stub(MlsProviderRpc.class, "claimPeerKeyPackagesWithOutcome",
                new MlsProviderRpc.ClaimResult(MlsProviderRpc.ClaimResult.OUTCOME_SERVED, packed,
                        null)));
        assertArrayEquals(new byte[] {4},
                MlsClaimLedger.claimOne(f.port(), MlsLogSink.NONE, refusable(), "+2").orNull());
        assertTrue(f.calls.contains("rpc(claimPeerKeyPackagesWithOutcome)"));

        final FakeShellPort none = ledgerPort(p, new FakeSysProps(), 2000L);
        none.returns("rpc", none.stub(MlsProviderRpc.class, "claimPeerKeyPackagesWithOutcome",
                new MlsProviderRpc.ClaimResult(MlsProviderRpc.ClaimResult.OUTCOME_NOT_ATTEMPTED,
                        null, "unbound")));
        final Claim<byte[]> unsent =
                MlsClaimLedger.claimOne(none.port(), MlsLogSink.NONE, refusable(), "+3");
        assertTrue("a claim the provider never sent is refunded, not charged",
                unsent.notAttempted());
    }

    @Test
    public void claimAllReadsTheOutcomeOnlyWhenTheProviderWroteOne() {
        final FakeShellPort f = ledgerPort(new FakePrefs(), new FakeSysProps(), 1000L);
        f.returns("rpc", f.stub(MlsProviderRpc.class, "claimPeerKeyPackages",
                (java.util.function.Function<Object[], Object>) a -> {
                    assertEquals("the sink starts at the no-outcome value",
                            MlsClaimLedger.NO_CLAIM_OUTCOME, ((int[]) a[1])[0]);
                    return java.util.Collections.singletonList(new byte[] {6});
                }));
        final Claim<java.util.List<byte[]>> c =
                MlsClaimLedger.claimAll(f.port(), MlsLogSink.NONE, refusable(), "+2");
        assertEquals(1, c.orNull().size());
        assertFalse(c.notAttempted());

        final FakeShellPort unbound = ledgerPort(new FakePrefs(), new FakeSysProps(), 1000L);
        unbound.returns("rpc", unbound.stub(MlsProviderRpc.class, "claimPeerKeyPackages",
                (java.util.function.Function<Object[], Object>) a -> {
                    ((int[]) a[1])[0] = MlsProviderRpc.ClaimResult.OUTCOME_NOT_ATTEMPTED;
                    return null;
                }));
        assertTrue(MlsClaimLedger.claimAll(unbound.port(), MlsLogSink.NONE, refusable(), "+2")
                .notAttempted());
    }
}
