/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sealed-message entry: a message id that was already sealed replays its stored ciphertext
 * instead of encrypting again.
 */
public class MlsSealedMessageTest {

    private static Map<String, String> headers() {
        final Map<String, String> h = new LinkedHashMap<>();
        h.put("Era-ID", "19");
        h.put("Epoch-Authenticator", "AAAAAAAAAAAAAAAAAAAAAA==");
        h.put("x-sealed-message-id", "11111111-2222-3333-4444-555555555555");
        return h;
    }

    @Test public void everythingSentRoundTrips() {
        // The headers are cached too: replaying under the current era instead of the sealed one
        // looks like a decrypt failure.
        final byte[] ct = {1, 2, 3, (byte) 0xFE, 0};
        final MlsSealedMessage m = new MlsSealedMessage("mid", ct, 19, 7, headers(), 1234L);
        final MlsSealedMessage back = MlsSealedMessage.decode("mid", m.encode());
        assertArrayEquals(ct, back.ciphertext());
        assertEquals(19, back.era);
        assertEquals(7, back.generation);
        assertEquals(1234L, back.sealedAtMs);
        assertEquals("19", back.headers().get("Era-ID"));
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", back.headers().get("Epoch-Authenticator"));
        assertEquals("11111111-2222-3333-4444-555555555555",
                back.headers().get("x-sealed-message-id"));
    }

    @Test public void ciphertextsOfEveryLengthModuloThreeRoundTrip() {
        for (int n = 0; n < 40; n++) {
            final byte[] ct = new byte[n];
            for (int i = 0; i < n; i++) ct[i] = (byte) (i * 11 + 3);
            final MlsSealedMessage back = MlsSealedMessage.decode("m",
                    new MlsSealedMessage("m", ct, 1, 1, headers(), 1L).encode());
            assertArrayEquals("length " + n, ct, back.ciphertext());
        }
    }

    @Test public void anEmptyCiphertextIsNotAReplayableHit() {
        // Only a non-empty ciphertext is replayable; an empty hit would send nothing and report
        // success.
        assertFalse(new MlsSealedMessage("m", new byte[0], 1, 1, headers(), 1L).isReplayable());
        assertFalse(new MlsSealedMessage("m", null, 1, 1, headers(), 1L).isReplayable());
        assertTrue(new MlsSealedMessage("m", new byte[] {9}, 1, 1, headers(), 1L).isReplayable());
    }

    @Test public void headerValuesWithSeparatorsSurvive() {
        // Header names and values may contain a space, ';' or '='.
        final Map<String, String> odd = new LinkedHashMap<>();
        odd.put("weird key", "value with space; and=equals");
        odd.put("another=key", "a;b;c");
        final MlsSealedMessage back = MlsSealedMessage.decode("m",
                new MlsSealedMessage("m", new byte[] {1}, 1, 1, odd, 1L).encode());
        assertEquals(2, back.headers().size());
        assertEquals("value with space; and=equals", back.headers().get("weird key"));
        assertEquals("a;b;c", back.headers().get("another=key"));
    }

    @Test public void noHeadersRoundTrips() {
        final MlsSealedMessage back = MlsSealedMessage.decode("m",
                new MlsSealedMessage("m", new byte[] {1}, 1, 1, null, 1L).encode());
        assertTrue(back.headers().isEmpty());
        assertTrue(back.isReplayable());
    }

    @Test public void aCorruptEntryReadsAsAbsent() {
        // Replaying half-decoded bytes would send something no peer can open.
        assertNull(MlsSealedMessage.decode("m", null));
        assertNull(MlsSealedMessage.decode("m", ""));
        assertNull(MlsSealedMessage.decode("m", "garbage"));
        assertNull(MlsSealedMessage.decode("m", "notanint 1 1 AAAA "));
        assertNull(MlsSealedMessage.decode("m", "1 1 1 !!!not-base64!!! "));
    }

    @Test public void theCiphertextIsDefensivelyCopied() {
        final byte[] ct = {1, 2, 3};
        final MlsSealedMessage m = new MlsSealedMessage("m", ct, 1, 1, headers(), 1L);
        ct[0] = 99;
        assertEquals(1, m.ciphertext()[0]);
        m.ciphertext()[0] = 42;
        assertEquals(1, m.ciphertext()[0]);
    }

    @Test public void theHeaderMapIsCopiedBothWays() {
        final Map<String, String> h = headers();
        final MlsSealedMessage m = new MlsSealedMessage("m", new byte[] {1}, 1, 1, h, 1L);
        h.put("injected", "x");
        assertFalse("mutating the source must not reach in", m.headers().containsKey("injected"));
        m.headers().put("also-injected", "x");
        assertFalse("mutating a handout must not reach in",
                m.headers().containsKey("also-injected"));
    }

    /**
     * Field 6, the conversation key, is base64 on disk: a group key is server-supplied and a 1:1
     * key is an E.164, and neither is guaranteed free of the separator.
     */
    @Test public void theConversationKeyRoundTrips() {
        for (final String conv : new String[] {"p:+15551234567", "g:b1189d9cfcdc446ab117f38a6adced",
                "has a space", ""}) {
            final MlsSealedMessage m = new MlsSealedMessage("mid", new byte[] {1, 2}, 3, 4,
                    headers(), 99L, conv);
            final MlsSealedMessage back = MlsSealedMessage.decode("mid", m.encode());
            assertNotNull(conv, back);
            assertEquals(conv, conv, back.conversationKey);
        }
    }

    /**
     * A five-field row still decodes, with an empty conversation: an unreadable row is discarded
     * and re-encrypted, so the format grows only by appending. The fixture counts fields from the
     * front.
     */
    @Test public void aFiveFieldRecordFromAnOlderBuildStillDecodes() {
        final MlsSealedMessage m =
                new MlsSealedMessage("mid", new byte[] {9}, 2, 5, headers(), 42L);
        final String legacy = firstNFields(m.encode(), 5);
        assertEquals("the fixture is not a 5-field record", 5, legacy.split(" ", -1).length);
        final MlsSealedMessage back = MlsSealedMessage.decode("mid", legacy);
        assertNotNull("a pre-field-6 row must still decode, or it becomes a re-encrypt", back);
        assertEquals("", back.conversationKey);
        assertEquals(2, back.era);
        assertEquals(42L, back.sealedAtMs);
        assertEquals(1, back.ciphertext().length);
        assertEquals("a row with no field 7 is UNSTAMPED, which the cache ADOPTS rather than "
                + "expires", MlsSendRetentionPolicy.UNSTAMPED, back.sealedAtElapsedMs);
    }

    /**
     * A six-field row (a wall stamp, no elapsed stamp) reads as
     * {@link MlsSendRetentionPolicy#UNSTAMPED}, so the sweep can tell an older build's row from one
     * that cannot be aged.
     */
    @Test public void aSixFieldRecordFromAnOlderBuildStillDecodes() {
        final MlsSealedMessage m = new MlsSealedMessage("mid", new byte[] {9}, 2, 5, headers(), 42L,
                "p:+15551234567");
        final String legacy = firstNFields(m.encode(), 6);
        assertEquals("the fixture is not a 6-field record", 6, legacy.split(" ", -1).length);
        final MlsSealedMessage back = MlsSealedMessage.decode("mid", legacy);
        assertNotNull("a pre-field-7 row must still decode, or it becomes a re-encrypt", back);
        assertEquals("p:+15551234567", back.conversationKey);
        assertEquals(42L, back.sealedAtMs);
        assertEquals(MlsSendRetentionPolicy.UNSTAMPED, back.sealedAtElapsedMs);
    }

    /** Field 7, the elapsed stamp the retention sweep reads. */
    @Test public void theElapsedStampRoundTrips() {
        final MlsSealedMessage m = new MlsSealedMessage("mid", new byte[] {1, 2}, 3, 4, headers(),
                1_800_000_000_000L, "g:abc", 40_000_000L);
        final MlsSealedMessage back = MlsSealedMessage.decode("mid", m.encode());
        assertNotNull(back);
        assertEquals(40_000_000L, back.sealedAtElapsedMs);
        assertEquals("the wall stamp is a separate field and must not be overwritten by it",
                1_800_000_000_000L, back.sealedAtMs);
        assertEquals("g:abc", back.conversationKey);
    }

    /**
     * {@code adoptedAt} re-stamps only the elapsed field: the wall stamp is the only human-readable
     * time in a dump, and the rest is replay material that must not change.
     */
    @Test public void adoptedAtReStampsOnlyTheElapsedField() {
        final byte[] ct = {7, 8, 9};
        final MlsSealedMessage before = new MlsSealedMessage("mid", ct, 3, 4, headers(),
                1_800_000_000_000L, "g:abc");
        assertEquals(MlsSendRetentionPolicy.UNSTAMPED, before.sealedAtElapsedMs);
        final MlsSealedMessage after = before.adoptedAt(90_000L);
        assertEquals(90_000L, after.sealedAtElapsedMs);
        assertEquals(1_800_000_000_000L, after.sealedAtMs);
        assertEquals("mid", after.messageId);
        assertArrayEquals(ct, after.ciphertext());
        assertEquals(3, after.era);
        assertEquals(4, after.generation);
        assertEquals("g:abc", after.conversationKey);
        assertEquals(headers(), after.headers());
        // The adopted row survives the encode/decode boundary.
        final MlsSealedMessage back = MlsSealedMessage.decode("mid", after.encode());
        assertNotNull(back);
        assertEquals(90_000L, back.sealedAtElapsedMs);
        assertArrayEquals(ct, back.ciphertext());
    }

    /** The first {@code n} space-separated fields of {@code encoded}, joined back up. */
    private static String firstNFields(final String encoded, final int n) {
        final String[] f = encoded.split(" ", -1);
        assertTrue("the current format has fewer than " + n + " fields: " + f.length, f.length
                >= n);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(f[i]);
        }
        return sb.toString();
    }

    @Test public void toStringDoesNotPrintTheCiphertext() {
        final String s = new MlsSealedMessage("mid", new byte[] {(byte) 0xAB, (byte) 0xCD},
                19, 7, headers(), 1L).toString();
        assertTrue(s, s.contains("2B"));
        assertFalse(s, s.toLowerCase().contains("abcd"));
    }
}
