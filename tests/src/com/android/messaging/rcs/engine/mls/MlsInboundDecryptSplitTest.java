/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.op;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.rec;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsInboundDecryptSplitTest {


    /**
     * A decrypt from {@code +2} in {@code grp}: the engine yields {@code plain}, signed by {@code
     * signer}.
     */
    private static FakeShellPort decryptPort(final byte[] plain, final String signer,
            final MlsRendezvous.Stored already) {
        final FakeShellPort f = SplitFixtures.port(storeWith(b -> b))
                .returns("resolveInbound", "g:grp")
                .returns("conv", new ConvState()).returns("convIfAny", null)
                .on("putGroup", a -> null)
                .returns("prefs", new FakePrefs()).returns("sysprops", new FakeSysProps());
        f.returns("rendezvous", f.stub(MlsRendezvousAccess.class, "get", already, "put", null));
        f.returns("session", engine(f, plain, null, false, signer));
        return f;
    }

    /**
     * An engine yielding {@code plain} with inbound AAD {@code aad}, whose own §7.5.3.1 check
     * reports {@code engineMismatch}. The armed ids land in {@code ARMED}.
     */
    private static MlsSession engine(final FakeShellPort f, final byte[] plain, final byte[] aad,
            final boolean engineMismatch, final String signer) {
        ARMED.clear();
        return f.stub(MlsSession.class, "process", plain, "lastInboundAad", aad,
                "lastInboundSenderMsisdn", signer, "eraEpoch", new byte[12], "epochAuth",
                new byte[] {1},
                "setRequestMessageId", (Function<Object[], Object>) a -> {
                    ARMED.add(a[0] == null ? null : new String((byte[]) a[0],
                            java.nio.charset.StandardCharsets.UTF_8));
                    return null;
                },
                "lastMessageIdMismatch", engineMismatch,
                "lastMessageIdMismatchDetail", engineMismatch
                        ? "m1\0other".getBytes(java.nio.charset.StandardCharsets.UTF_8) : null);
    }

    private static final java.util.List<String> ARMED = new java.util.ArrayList<>();

    private static RccMlsBody.Parsed decrypt(final FakeShellPort f) {
        return MlsInboundDecrypt.decryptInbound(MlsConfig.defaults(), f.port(), MlsLogSink.NONE,
                "+2", "m1", new byte[] {9}, "grp", null);
    }

    @Test
    public void aMessageFromItsSignerIsDecryptedRecordedAndTheGroupMarkedHealthy() {
        final byte[] plain = RccMlsBody.frameText("hi");
        final FakeShellPort f = decryptPort(plain, "+2", null);
        final RccMlsBody.Parsed p = decrypt(f);
        assertEquals(RccMlsBody.parse(plain).contentType, p.contentType);
        assertArrayEquals(RccMlsBody.parse(plain).body, p.body);
        assertTrue(f.calls.contains("MlsRendezvousAccess.put"));
        assertTrue(f.calls.stream()
                .anyMatch(c -> c.startsWith("moveHealth(g:grp, " + MlsHealthStates.HEALTHY)));
    }

    @Test
    public void aReplayIsAnsweredFromTheRendezvousWithoutTouchingTheEngine() {
        final byte[] framed = RccMlsBody.frameText("again");
        final FakeShellPort f =
                decryptPort(null, "+2", new MlsRendezvous.Stored(MlsProcStatus.APP, framed, 1L));
        assertArrayEquals(RccMlsBody.parse(framed).body, decrypt(f).body);
        assertFalse(f.calls.contains("MlsSession.process"));
    }

    @Test
    public void anImpersonatingSignerIsRefusedAndAnUndecryptableOrUnknownMessageYieldsNothing() {
        final RccMlsBody.Parsed refused =
                decrypt(decryptPort(RccMlsBody.frameText("hi"), "+9", null));
        assertEquals(MlsInboundRefusal.marker(MlsInboundRefusal.Reason.SENDER_IMPERSONATION),
                refused.contentType);
        assertNull(decrypt(decryptPort(null, "+2", null)));
        assertNull(decrypt(decryptPort(RccMlsBody.frameText("hi"), "+2", null)
                .returns("resolveInbound", null)));
    }


    /** An RCC.16 §7.5.3.1 AAD: version 1, message id {@code id}, era 1, no resend component. */
    private static byte[] aadFor(final String id) {
        final byte[] b = id.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0); o.write(1); o.write(b.length); o.write(b, 0, b.length);
        o.write(0); o.write(0); o.write(0); o.write(1); o.write(0);
        return o.toByteArray();
    }

    @Test
    public void theEngineCheckIsArmedWithTheEnvelopeIdAroundTheDecryptAndThenDisarmed() {
        final FakeShellPort f = decryptPort(RccMlsBody.frameText("hi"), "+2", null);
        assertEquals(RccMlsBody.parse(RccMlsBody.frameText("hi")).contentType,
                decrypt(f).contentType);
        assertEquals(java.util.Arrays.asList("m1", null), ARMED);
        final int arm = f.calls.indexOf("MlsSession.setRequestMessageId");
        final int process = f.calls.indexOf("MlsSession.process");
        final int read = f.calls.indexOf("MlsSession.lastMessageIdMismatch");
        final int disarm = f.calls.lastIndexOf("MlsSession.setRequestMessageId");
        assertTrue("arm " + arm + " < process " + process + " < read " + read + " < disarm "
                + disarm, arm >= 0 && arm < process && process < read && read < disarm);
    }

    /**
     * The engine's verdict alone refuses: here the AAD names the envelope's id, which the removed
     * host parse would have passed. The refusal names the id from the engine's detail.
     */
    @Test
    public void aMismatchIsRefusedOnTheEnginesVerdict() {
        final FakeShellPort f = decryptPort(null, "+2", null);
        f.returns("session", engine(f, RccMlsBody.frameText("hi"), aadFor("m1"), true, "+2"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final RccMlsBody.Parsed p = MlsInboundDecrypt.decryptInbound(MlsConfig.defaults(),
                f.port(), log, "+2", "m1", new byte[] {9}, "grp", null);
        assertEquals(MlsInboundRefusal.marker(MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING),
                p.contentType);
        assertTrue(log.said("E", MlsInboundRefusal.MARKER_MISBINDING));
        assertTrue(log.said("E", "names message_id 'other'"));
    }

    /** An engine match is delivered even when the AAD names another id: no second parser. */
    @Test
    public void anEngineMatchIsDeliveredWhateverTheAadNames() {
        final FakeShellPort f = decryptPort(null, "+2", null);
        f.returns("session", engine(f, RccMlsBody.frameText("hi"), aadFor("other"), false, "+2"));
        assertEquals(RccMlsBody.parse(RccMlsBody.frameText("hi")).contentType,
                decrypt(f).contentType);
    }

    /** A mismatch naming an id we already decrypted from this peer is reported as a replay. */
    @Test
    public void aMismatchNamingAnIdAlreadyDecryptedIsAReplay() {
        final FakeShellPort f = decryptPort(null, "+2", null);
        final MlsRendezvous.Stored stored =
                new MlsRendezvous.Stored(MlsProcStatus.APP, RccMlsBody.frameText("old"), 1L);
        f.returns("rendezvous", f.stub(MlsRendezvousAccess.class,
                "get", (Function<Object[], Object>) a -> "other".equals(a[2]) ? stored : null,
                "put", null));
        f.returns("session", engine(f, RccMlsBody.frameText("hi"), aadFor("m1"), true, "+2"));
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final RccMlsBody.Parsed p = MlsInboundDecrypt.decryptInbound(MlsConfig.defaults(),
                f.port(), log, "+2", "m1", new byte[] {9}, "grp", null);
        assertEquals(MlsInboundRefusal.marker(MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING),
                p.contentType);
        assertTrue(log.said("E", MlsInboundRefusal.MARKER_REPLAY));
    }

    /** {@code decryptPort}, but with no group for the first {@code misses} lookups. */
    private static FakeShellPort racePort(final int misses) {
        final FakeShellPort f = decryptPort(RccMlsBody.frameText("hi"), "+2", null);
        final int[] n = {0};
        final long[] clock = {0L};
        return f.on("resolveInbound", a -> n[0]++ < misses ? null : "g:grp")
                .on("elapsedRealtime", a -> clock[0]++);
    }

    @Test
    public void aJoinThatLandsDuringTheWindowIsRetriedAndDelivered() {
        final FakeShellPort f = racePort(1);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        final RccMlsBody.Parsed p = MlsInboundDecrypt.awaitJoinAndRetryDecrypt(MlsConfig.defaults(),
                f.port(), log, "+2", "m1", new byte[] {9}, "grp", 1000L, null);
        assertArrayEquals(RccMlsBody.parse(RccMlsBody.frameText("hi")).body, p.body);
        assertTrue(log.said("I", "JOIN RACE"));
        assertTrue(log.said("I", "SUCCEEDED"));
    }

    @Test
    public void noJoinOrAGroupAlreadyHeldTakesTheOrdinaryPath() {
        final FakeShellPort.Log log = new FakeShellPort.Log();
        assertNull(MlsInboundDecrypt.awaitJoinAndRetryDecrypt(MlsConfig.defaults(),
                racePort(Integer.MAX_VALUE).port(), log, "+2", "m1", new byte[] {9}, "grp", 0L,
                null));
        assertTrue(log.said("I", "no join arrived"));
        final FakeShellPort held = racePort(0);
        assertNull(MlsInboundDecrypt.awaitJoinAndRetryDecrypt(MlsConfig.defaults(), held.port(),
                MlsLogSink.NONE, "+2", "m1", new byte[] {9}, "grp", 1000L, null));
        assertFalse(held.calls.contains("MlsSession.process"));
    }
}
