/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.android.messaging.rcs.engine.mls.MlsRendezvous.Stage;
import com.android.messaging.rcs.engine.mls.MlsRendezvous.Stored;

/**
 * The rendezvous keys and the stored result. The inbound decrypt advances the ratchet, so a
 * re-delivery or a process death before the chat row is written would otherwise consume a key the
 * sender never re-uses.
 */
public class MlsRendezvousTest {

    @Test public void everyFieldParticipatesInTheReadKey() {
        // A hit means this message, from this peer, at this stage; anything looser replays the
        // wrong answer.
        final String base = MlsRendezvous.readKey("+1self", "+1peer", "mid", Stage.DECRYPT);
        assertNotEquals(base, MlsRendezvous.readKey("+1other", "+1peer", "mid", Stage.DECRYPT));
        assertNotEquals(base, MlsRendezvous.readKey("+1self", "+1other", "mid", Stage.DECRYPT));
        assertNotEquals(base, MlsRendezvous.readKey("+1self", "+1peer", "other", Stage.DECRYPT));
        assertNotEquals("the same id at a different stage is a different question",
                base, MlsRendezvous.readKey("+1self", "+1peer", "mid", Stage.CONTROL));
    }

    @Test public void theKeyIsStableAndCaseInsensitive() {
        assertEquals(MlsRendezvous.readKey("+1SELF", " +1peer ", "MID", Stage.DECRYPT),
                MlsRendezvous.readKey("+1self", "+1peer", "mid", Stage.DECRYPT));
    }

    @Test public void fieldsCannotCollideByConcatenation() {
        // The separator cannot appear in an E.164 or a message id, so fields cannot collide by
        // concatenation.
        assertNotEquals(MlsRendezvous.readKey("a", "b c", "m", Stage.DECRYPT),
                MlsRendezvous.readKey("a b", "c", "m", Stage.DECRYPT));
    }

    @Test public void nullsDoNotCrashTheKey() {
        assertEquals(MlsRendezvous.readKey(null, null, null, null),
                MlsRendezvous.readKey("", "", "", Stage.UNKNOWN));
    }

    @Test public void deleteMatchesAcrossSendersAndStages() {
        // Once the message has a chat row every stage and sender attribution of it is settled; a
        // delete narrower than this leaks a row per message.
        for (final Stage st : Stage.values()) {
            for (final String peer : new String[] {"+1peer", "+1other", ""}) {
                final String k = MlsRendezvous.readKey("+1self", peer, "mid", st);
                assertTrue("stage=" + st + " peer=" + peer,
                        MlsRendezvous.keyMatchesMessage(k, "+1self", "mid"));
            }
        }
    }

    @Test public void deleteDoesNotReachOtherMessagesOrIdentities() {
        final String k = MlsRendezvous.readKey("+1self", "+1peer", "mid", Stage.DECRYPT);
        assertFalse(MlsRendezvous.keyMatchesMessage(k, "+1self", "other-mid"));
        assertFalse(MlsRendezvous.keyMatchesMessage(k, "+1other", "mid"));
    }

    @Test public void deleteIsNullAndGarbageSafe() {
        assertFalse(MlsRendezvous.keyMatchesMessage(null, "+1self", "mid"));
        assertFalse(MlsRendezvous.keyMatchesMessage("nonsense", "+1self", "mid"));
        assertFalse(MlsRendezvous.keyMatchesMessage("a b", "+1self", "mid"));
    }

    @Test public void aStoredResultRoundTrips() {
        final byte[] payload = {0, 1, 2, (byte) 0xFF, 65, 66};
        final Stored s = new Stored(MlsProcStatus.APP, payload, 1_700_000_000_000L);
        final Stored back = Stored.decode(s.encode());
        assertEquals(MlsProcStatus.APP, back.status);
        assertEquals(1_700_000_000_000L, back.storedAtMs);
        assertArrayEquals(payload, back.payload());
    }

    @Test public void anEmptyPayloadRoundTrips() {
        final Stored back = Stored.decode(new Stored(MlsProcStatus.COMMIT, null, 5L).encode());
        assertEquals(MlsProcStatus.COMMIT, back.status);
        assertEquals(0, back.payload().length);
    }

    @Test public void payloadsOfEveryLengthModuloThreeRoundTrip() {
        // The payload is a decrypted message; a truncated replay would be worse than none.
        for (int n = 0; n < 40; n++) {
            final byte[] p = new byte[n];
            for (int i = 0; i < n; i++) p[i] = (byte) (i * 7 + 1);
            final Stored back = Stored.decode(new Stored(0, p, 1L).encode());
            assertArrayEquals("length " + n, p, back.payload());
        }
    }

    @Test public void aCorruptRowReadsAsAbsentNotAsAPartialHit() {
        // Replaying a half-decoded result is worse than decrypting again.
        assertNull(Stored.decode(null));
        assertNull(Stored.decode(""));
        assertNull(Stored.decode("garbage"));
        assertNull(Stored.decode("notanint 5 AAAA"));
        assertNull(Stored.decode("0 notalong AAAA"));
        assertNull(Stored.decode("0 5 !!!not-base64!!!"));
    }

    @Test public void thePayloadIsDefensivelyCopied() {
        final byte[] p = {1, 2, 3};
        final Stored s = new Stored(0, p, 1L);
        p[0] = 99;
        assertEquals(1, s.payload()[0]);
        s.payload()[0] = 42;
        assertEquals(1, s.payload()[0]);
    }

    @Test public void stageWireNumbersRoundTrip() {
        for (final Stage s : Stage.values()) assertEquals(s, Stage.fromWire(s.wire));
        assertEquals(Stage.UNKNOWN, Stage.fromWire(77));
    }

    @Test public void toStringNamesTheStatusNotThePayload() {
        final String s = new Stored(MlsProcStatus.APP, new byte[] {77, 77}, 1L).toString();
        assertTrue(s, s.contains("APP"));
        assertTrue(s, s.contains("2B"));
    }
}
