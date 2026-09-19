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

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Op;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Health;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ServerComparison;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.EraYield;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsAheadChainCheckSplitTest {


    @Test
    public void aheadOrForkedNamesWhyTheChainTestDidNotRunOrWhatItFound() {
        final FakeShellPort f =
                port(storeWith(b -> b)).returns("lookServerEpochAuthenticator", Look.asked(null));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final ServerComparison untested = MlsAheadChainCheck.aheadOrForked(f.port(), log,
                MlsFetchLedger.Caller.values()[0], "grp", null, grp(), new long[] {5, 3});
        assertSame(Health.AHEAD, untested.health);
        assertTrue(untested.identityLine().startsWith("CHAIN "));
        assertTrue(log.said("I", "CHAIN TEST WAS NOT RUN"));
    }

    private static byte[] ee(final int era, final long epoch) {
        final byte[] b = new byte[12];
        b[0] = (byte) (era >>> 24); b[1] = (byte) (era >>> 16); b[2] = (byte) (era >>> 8); b[3] =
                (byte) era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (epoch >>> (56 - 8 * i));
        return b;
    }

    private static ServerComparison health(final FakeShellPort f, final long[] server) {
        return MlsAheadChainCheck.healthAgainstServer(f.port(), MlsLogSink.NONE,
                MlsFetchLedger.Caller.values()[0], "grp", null, grp(), server);
    }

    @Test
    public void everyVerdictOfTheHealthComparison() {
        assertSame(Health.REJOIN, MlsAheadChainCheck.healthAgainstServer(new FakeShellPort().port(),
                MlsLogSink.NONE, MlsFetchLedger.Caller.values()[0], "grp", null, null,
                new long[] {1, 1}).health);
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b));
        f.returns("session",
                f.stub(MlsSession.class, "eraEpoch", ee(3, 5), "epochAuth", new byte[] {7}));
        assertSame(Health.ERA_GAP, health(f, new long[] {4, 5}).health);
        assertSame(Health.LOWER_EPOCH_CHAIN_UNKNOWN, health(f, new long[] {3, 6}).health);
        f.returns("lookServerEpochAuthenticator", Look.asked(new byte[] {7}));
        assertSame(Health.IN_SYNC, health(f, new long[] {3, 5}).health);
        f.returns("lookServerEpochAuthenticator", Look.asked(new byte[] {8}));
        assertSame("same position, different authenticator: a different group", Health.DIVERGED,
                health(f, new long[] {3, 5}).health);
        f.returns("lookServerEpochAuthenticator", Look.refusedByLedger("no"));
        assertSame(Health.IN_SYNC_UNVERIFIED, health(f, new long[] {3, 5}).health);
        final FakeShellPort unknown = SplitFixtures.port(storeWith(b -> b));
        unknown.returns("session", unknown.stub(MlsSession.class, "eraEpoch", null));
        assertSame(Health.UNKNOWN, health(unknown, new long[] {3, 5}).health);
    }


    @Test
    public void aRefusedLookIsLookRefusedAndAnEmptyOneIsNotFoundOnlyWithoutAGroup() {
        final FakeShellPort refused = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", "g:grp")
                .returns("getGroup", grp())
                .returns("lookServerEraEpoch", Look.refusedByLedger("no"));
        assertSame(Health.LOOK_REFUSED, MlsAheadChainCheck.detectHealth(refused.port(),
                MlsLogSink.NONE, MlsFetchLedger.Caller.DEBUG_HEALTH, "grp", "+2"));
        final FakeShellPort empty = new FakeShellPort().returns("ensureSession", true)
                .returns("resolveInbound", "g:grp")
                .returns("getGroup", null).returns("lookServerEraEpoch", Look.asked(null));
        assertSame(Health.NOT_FOUND, MlsAheadChainCheck.detectHealth(empty.port(), MlsLogSink.NONE,
                MlsFetchLedger.Caller.DEBUG_HEALTH, "grp", "+2"));
        empty.returns("getGroup", grp());
        assertSame(Health.UNKNOWN, MlsAheadChainCheck.detectHealth(empty.port(), MlsLogSink.NONE,
                MlsFetchLedger.Caller.DEBUG_HEALTH, "grp", "+2"));
    }


    private static FakeShellPort aheadPort(final int engineEra, final Look<long[]> server) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("lookServerEraEpoch", server)
                .on("forget", a -> true);
        final byte[] eraEpoch = new byte[12];
        eraEpoch[3] = (byte) engineEra;
        f.returns("session", f.stub(MlsSession.class, "eraEpoch", eraEpoch));
        return f;
    }

    @Test
    public void onlyAnEngineProvablyAheadOfTheServerIsDropped() {
        final FakeShellPort ahead = aheadPort(2, Look.asked(new long[] {1L, 0L}));
        assertTrue(MlsAheadChainCheck.quarantineIfAheadOfServer(ahead.port(), MlsLogSink.NONE,
                grp(), "grp", "+2", 2, "test").quarantined);
        assertTrue(ahead.calls.stream().anyMatch(c -> c.startsWith("forget(")));
        final FakeShellPort level = aheadPort(1, Look.asked(new long[] {1L, 0L}));
        assertFalse(MlsAheadChainCheck.quarantineIfAheadOfServer(level.port(), MlsLogSink.NONE,
                grp(), "grp", "+2", 2, "test").quarantined);
        final FakeShellPort refused = aheadPort(2, Look.refusedByLedger("no"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertFalse(MlsAheadChainCheck.quarantineIfAheadOfServer(refused.port(), log, grp(), "grp",
                "+2", 2, "test").quarantined);
        assertTrue(log.said("W", "the fetch ledger refused the look"));
        assertFalse(level.calls.stream().anyMatch(c -> c.startsWith("forget(")));
        assertFalse(refused.calls.stream().anyMatch(c -> c.startsWith("forget(")));
    }
}
