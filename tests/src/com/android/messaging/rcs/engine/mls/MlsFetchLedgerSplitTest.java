/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ClaimOutcomeSink;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsFetchLedgerSplitTest {


    private static FakeShellPort prefsPort(final FakePrefs p) {
        return new FakeShellPort().returns("prefs", p);
    }

    private static FakeShellPort noPrefs() {
        return new FakeShellPort()
                .on("prefs", a -> { throw new IllegalStateException("no file"); });
    }

    @Test
    public void anUnwrittenFetchLedgerIsEmptyAndAnUnreadableOneIsSpent() {
        final FakePrefs p = new FakePrefs();
        assertSame(MlsFetchLedgerRecord.EMPTY,
                MlsFetchLedger.ledgerFor(prefsPort(p).port(), MlsLogSink.NONE, "g:grp"));
        p.values.put(MlsFetchLedger.ledgerPrefKey("g:grp"), "garbage");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsFetchLedger.ledgerFor(prefsPort(p).port(), log, "g:grp"));
        assertTrue(log.said("W", "STORED and UNREADABLE (7 chars)"));
        final FakeShellPort.Log unread = new FakeShellPort.Log();
        assertNull(MlsFetchLedger.ledgerFor(noPrefs().port(), unread, "g:grp"));
        assertTrue(unread.said("W", "could not READ the fetch ledger"));
    }


    @Test
    public void aStoredFetchLedgerReadsBackAsWritten() {
        final FakePrefs p = new FakePrefs();
        final MlsFetchLedgerRecord r =
                MlsFetchLedgerRecord.EMPTY.charged(MlsFetchLedger.Caller.MAINTENANCE, 1000L);
        MlsFetchLedger.storeLedger(prefsPort(p).port(), MlsLogSink.NONE, "g:grp", r);
        assertEquals(r.encode(),
                MlsFetchLedger.ledgerFor(prefsPort(p).port(), MlsLogSink.NONE, "g:grp").encode());
        MlsFetchLedger.storeLedger(prefsPort(p).port(), MlsLogSink.NONE, "g:grp", null);
        assertEquals("a null record writes nothing", r.encode(),
                p.values.get(MlsFetchLedger.ledgerPrefKey("g:grp")));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsFetchLedger.storeLedger(noPrefs().port(), log, "g:grp", r);
        assertTrue(log.said("W", "could not WRITE the fetch ledger"));
    }


    @Test
    public void theFetchCeilingIsTheSharedOneUnlessTheOperatorSetsIt() {
        final FakeSysProps knobs = new FakeSysProps();
        final FakeShellPort f = new FakeShellPort().returns("sysprops", knobs);
        assertEquals(MlsFetchLedger.SHARED_CEILING, MlsFetchLedger.fetchLedgerCeiling(f.port()));
        knobs.set("debug.rcs.mls_fetch_ceiling", 3);
        assertEquals(3, MlsFetchLedger.fetchLedgerCeiling(f.port()));
    }


    /**
     * The ledger's port: its preferences file, the operator's knobs, and a clock at {@code now}.
     */
    private static FakeShellPort ledgerPort(final FakePrefs p, final FakeSysProps knobs,
            final long now) {
        return new FakeShellPort().returns("prefs", p).returns("sysprops", knobs)
                .returns("elapsedRealtime", now);
    }

    private static java.util.List<MlsFetchLedger.Caller> charging() {
        final java.util.List<MlsFetchLedger.Caller> out = new java.util.ArrayList<>();
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) {
            if (!c.isExempt() && c.chargesTheSharedCeiling) out.add(c);
        }
        return out;
    }

    private static MlsFetchLedger.Caller exempt() {
        for (final MlsFetchLedger.Caller c : MlsFetchLedger.Caller.values()) if (
                c.isExempt()) return c;
        throw new AssertionError("no exempt caller");
    }

    @Test
    public void aLookIsChargedThenRefusedAtTheSharedCeilingWithoutAsking() {
        final FakePrefs p = new FakePrefs();
        final FakeSysProps knobs = new FakeSysProps().set("debug.rcs.mls_fetch_ceiling", 1);
        final java.util.List<MlsFetchLedger.Caller> two = charging();
        assertTrue("two callers share the ceiling", two.size() >= 2);
        final Look<byte[]> first = MlsFetchLedger.spendOneLook(ledgerPort(p, knobs, 1000L).port(),
                MlsLogSink.NONE,
                two.get(0), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH, "g:grp",
                () -> new byte[] {7});
        assertArrayEquals(new byte[] {7}, first.orNull());
        assertEquals(1,
                MlsFetchLedger.ledgerFor(ledgerPort(p, knobs, 1000L).port(), MlsLogSink.NONE,
                        "g:grp")
                .spentAgainstCeiling(1000L));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final Look<byte[]> second = MlsFetchLedger.spendOneLook(ledgerPort(p, knobs, 1001L).port(),
                log, two.get(1), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH, "g:grp",
                () -> { throw new AssertionError("a refused look asks nothing"); });
        assertTrue(second.refused());
        assertTrue(log.said("W", "MLS fetch ledger REFUSED"));
    }

    /**
     * The charge is on disk before the look is asked. The loop this ledger bounds can kill the
     * process, and a charge that {@code apply()} only scheduled dies with it.
     */
    @Test
    public void theChargeIsOnDiskBeforeTheLookIsAsked() {
        final FakePrefs p = new FakePrefs();
        final String k = MlsFetchLedger.ledgerPrefKey("g:grp");
        final Look<byte[]> look = MlsFetchLedger.spendOneLook(
                ledgerPort(p, new FakeSysProps(), 1000L).port(), MlsLogSink.NONE,
                charging().get(0), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH, "g:grp",
                () -> {
                    assertTrue("the charge was not COMMITTED before the look, so a crash during "
                            + "the look loses it", p.onDisk.containsKey(k));
                    assertEquals(1, MlsFetchLedgerRecord.decode((String) p.onDisk.get(k))
                            .spentAgainstCeiling(1000L));
                    return new byte[] {7};
                });
        assertArrayEquals(new byte[] {7}, look.orNull());
    }

    @Test
    public void anUnreadableLedgerRefusesEveryoneButAnExemptCaller() {
        final FakePrefs p = new FakePrefs();
        p.values.put(MlsFetchLedger.ledgerPrefKey("g:grp"), "garbage");
        assertTrue(MlsFetchLedger.spendOneLook(ledgerPort(p, new FakeSysProps(), 1000L).port(),
                MlsLogSink.NONE,
                charging().get(0), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH, "g:grp",
                () -> { throw new AssertionError("refused"); }).refused());
        assertArrayEquals(new byte[] {7}, MlsFetchLedger.spendOneLook(
                ledgerPort(p, new FakeSysProps(), 1000L).port(),
                MlsLogSink.NONE, exempt(), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH,
                "g:grp", () -> new byte[] {7}).orNull());
    }

    /**
     * An unreadable record refuses one look and is then discarded, so the next look starts from an
     * empty ledger. Nothing else would ever replace it: a refusal writes nothing, a conversation's
     * teardown keeps the ledger, and Try again does not reach it.
     */
    @Test
    public void anUnreadableLedgerRefusesOnceAndIsThenDiscarded() {
        final FakePrefs p = new FakePrefs();
        final String k = MlsFetchLedger.ledgerPrefKey("g:grp");
        p.values.put(k, "garbage");
        p.onDisk.put(k, "garbage");
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertTrue(MlsFetchLedger.spendOneLook(ledgerPort(p, new FakeSysProps(), 1000L).port(),
                log, charging().get(0), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH,
                "g:grp", () -> { throw new AssertionError("refused"); }).refused());
        assertFalse("the refusal left the unreadable record in place, so it refuses every "
                + "later look as well", p.values.containsKey(k) || p.onDisk.containsKey(k));
        assertTrue(log.said("W", "DISCARDED"));
        assertFalse("the log promised the record ages out, which an unparseable record cannot do",
                log.said("W", "ages out"));
        assertArrayEquals(new byte[] {7}, MlsFetchLedger.spendOneLook(
                ledgerPort(p, new FakeSysProps(), 2000L).port(), MlsLogSink.NONE,
                charging().get(0), MlsFetchLedger.Primitive.GET_MLS_SERVER_ERA_EPOCH, "g:grp",
                () -> new byte[] {7}).orNull());
        assertEquals(1, MlsFetchLedger.ledgerFor(ledgerPort(p, new FakeSysProps(), 2000L).port(),
                MlsLogSink.NONE, "g:grp").spentAgainstCeiling(2000L));
    }

    /** Each charged wrapper spends its own primitive's look and forwards to the port member. */
    @Test
    public void theChargedWrappersSpendOneLookAndReachTheProviderThroughThePort() {
        final FakePrefs p = new FakePrefs();
        final Object[][] seen = new Object[4][];
        final FakeShellPort f = ledgerPort(p, new FakeSysProps(), 1000L);
        f.returns("rpc", f.stub(MlsProviderRpc.class,
                "fetchMissedCommits", (java.util.function.Function<Object[], Object>) a -> {
                    seen[0] = a; return new byte[] {1};
                },
                "fetchServerEpochAuthenticator",
                (java.util.function.Function<Object[], Object>) a -> {
                    seen[1] = a; return new byte[] {2};
                },
                "getMlsServerEraEpoch", (java.util.function.Function<Object[], Object>) a -> {
                    seen[2] = a; return new long[] {3, 4};
                },
                "getMlsGroupInfo", (java.util.function.Function<Object[], Object>) a -> {
                    seen[3] = a; return new MlsProviderRpc.ControlResult(0, null, null);
                }));
        final MlsFetchLedger.Caller c = charging().get(0);
        assertArrayEquals(new byte[] {1}, MlsFetchLedger.lookMissedCommits(f.port(),
                MlsLogSink.NONE, c, "g:grp", "+2", "grp", 5, new byte[] {9}).orNull());
        assertEquals(java.util.Arrays.asList("+2", "grp", 5L), java.util.Arrays.asList(
                seen[0][0], seen[0][1], seen[0][2]));
        assertArrayEquals(new byte[] {2}, MlsFetchLedger.lookServerEpochAuthenticator(f.port(),
                MlsLogSink.NONE, c, "g:grp", "+2", "grp").orNull());
        assertArrayEquals(new long[] {3, 4}, MlsFetchLedger.lookServerEraEpoch(f.port(),
                MlsLogSink.NONE, c, "g:grp", "+2", "grp").orNull());
        assertEquals(0, MlsFetchLedger.lookGroupInfo(f.port(), MlsLogSink.NONE, exempt(),
                "p:+2", "+2").orNull().verdict);
        assertEquals("+2", seen[3][0]);
        assertEquals("three charged looks on g:grp", 3, MlsFetchLedger.ledgerFor(
                ledgerPort(p, new FakeSysProps(), 1000L).port(), MlsLogSink.NONE, "g:grp")
                .spentAgainstCeiling(1000L));
        for (final String what : new String[] {"rpc(fetchMissedCommits)",
                "rpc(fetchServerEpochAuthenticator)", "rpc(getMlsServerEraEpoch)",
                "rpc(getMlsGroupInfo)"}) {
            assertTrue(what, f.calls.contains(what));
        }
    }

    /** A refused look never reaches the port. */
    @Test
    public void aRefusedWrapperDoesNotReachThePort() {
        final FakeSysProps knobs = new FakeSysProps().set("debug.rcs.mls_fetch_ceiling", 1);
        final FakePrefs p = new FakePrefs();
        final FakeShellPort f = ledgerPort(p, knobs, 1000L);
        f.returns("rpc", f.stub(MlsProviderRpc.class, "getMlsServerEraEpoch", new long[] {1, 0}));
        assertFalse(MlsFetchLedger.lookServerEraEpoch(f.port(), MlsLogSink.NONE, charging().get(0),
                "g:grp", "+2", "grp").refused());
        assertTrue(MlsFetchLedger.lookServerEraEpoch(f.port(), MlsLogSink.NONE, charging().get(1),
                "g:grp", "+2", "grp").refused());
        assertEquals("the first look spends the one allowed; the second is refused", 1,
                f.calls.stream().filter(c -> c.startsWith("rpc(")).count());
    }
}
