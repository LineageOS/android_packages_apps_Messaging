/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RCC.16 Annex C.2 and C.3 file encryption. The C.2 construction is recomputed from the spec text
 * and matched byte for byte: a wrong salt, swapped keys, a little-endian length or a mis-built IV'
 * all round-trip through our own code and still break interop.
 */
public final class RccFileCryptoTest {

    private static final byte[] KEY = new byte[32];
    private static final byte[] IV = new byte[12];
    static {
        for (int i = 0; i < KEY.length; i++) KEY[i] = (byte) (i + 1);
        for (int i = 0; i < IV.length; i++) IV[i] = (byte) (0xA0 + i);
    }

    /** The Annex C.2 salt, restated from the spec. */
    private static byte[] salt() {
        final byte[] s = new byte[32];
        final String hex = "3243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c8";
        for (int i = 0; i < 32; i++) {
            s[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return s;
    }

    private static byte[] hmac(byte[] k, byte[] d) throws Exception {
        final Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(k, "HmacSHA256"));
        return m.doFinal(d);
    }

    /** RFC 5869 HKDF, written out so the test does not use the implementation's. */
    private static byte[] hkdf64(byte[] ikm, byte[] salt, byte[] info) throws Exception {
        final byte[] prk = hmac(salt, ikm);
        final byte[] t1 = hmac(prk, concat(info, new byte[] {1}));
        final byte[] t2 = hmac(prk, concat(concat(t1, info), new byte[] {2}));
        return concat(t1, t2);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        final byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    /**
     * The whole C.2 pipeline, recomputed from the spec text:
     * {@code k_enc‖k_hmac = HKDF(K, salt, Info)}, {@code paddedMessage = M‖pad‖len‖padLen},
     * {@code IV' = IV‖0000}, {@code CT = AES-CTR(k_enc, IV', padded)},
     * {@code Tag = HMAC(k_hmac, IV‖CT)}.
     */
    @Test
    public void matchesTheSpecConstructionByteForByte() throws Exception {
        final byte[] msg = "a group subject".getBytes(StandardCharsets.UTF_8);
        final String info = RccFileCrypto.INFO_GROUP_SUBJECT;

        final byte[] okm = hkdf64(KEY, salt(), info.getBytes(StandardCharsets.UTF_8));
        final byte[] kEnc = Arrays.copyOfRange(okm, 0, 32);
        final byte[] kHmac = Arrays.copyOfRange(okm, 32, 64);

        final int padLen = (int) (RccFileCrypto.padme(msg.length) - msg.length);
        final byte[] padded = new byte[msg.length + padLen + 8];
        System.arraycopy(msg, 0, padded, 0, msg.length);
        final int at = msg.length + padLen;
        padded[at] = (byte) (msg.length >>> 24);     padded[at + 1] = (byte) (msg.length >>> 16);
        padded[at + 2] = (byte) (msg.length >>> 8);  padded[at + 3] = (byte) msg.length;
        padded[at + 4] = (byte) (padLen >>> 24);     padded[at + 5] = (byte) (padLen >>> 16);
        padded[at + 6] = (byte) (padLen >>> 8);      padded[at + 7] = (byte) padLen;

        final byte[] ivPrime = new byte[16];
        System.arraycopy(IV, 0, ivPrime, 0, 12);
        final Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kEnc, "AES"), new IvParameterSpec(ivPrime));
        final byte[] expectedCt = c.doFinal(padded);
        final byte[] expectedTag = hmac(kHmac, concat(IV, expectedCt));

        final RccFileCrypto.Encrypted e = RccFileCrypto.encryptWithIv(KEY, msg, info, IV);
        assertNotNull(e);
        assertArrayEquals("ciphertext must match the spec construction", expectedCt, e.ciphertext);
        assertArrayEquals("tag must match the spec construction", expectedTag, e.tag);
        assertEquals(msg.length, e.fileLengthHint);
    }

    /** Encryption uses the first half of the HKDF output, the tag the second. */
    @Test
    public void encryptionKeyIsTheFirstHalfOfTheKdfOutput() throws Exception {
        final byte[] msg = "x".getBytes(StandardCharsets.UTF_8);
        final byte[] okm = hkdf64(KEY, salt(),
                RccFileCrypto.INFO_GROUP_ICON.getBytes(StandardCharsets.UTF_8));
        final byte[] kHmac = Arrays.copyOfRange(okm, 32, 64);
        final RccFileCrypto.Encrypted e =
                RccFileCrypto.encryptWithIv(KEY, msg, RccFileCrypto.INFO_GROUP_ICON, IV);
        assertArrayEquals(hmac(kHmac, concat(IV, e.ciphertext)), e.tag);
    }

    /** Info is the filename and feeds the KDF, so icon and subject keys differ (RCC.16 §9.7.1). */
    @Test
    public void infoSeparatesIconFromSubject() {
        final byte[] msg = "same bytes".getBytes(StandardCharsets.UTF_8);
        final RccFileCrypto.Encrypted icon =
                RccFileCrypto.encryptWithIv(KEY, msg, RccFileCrypto.INFO_GROUP_ICON, IV);
        final RccFileCrypto.Encrypted subj =
                RccFileCrypto.encryptWithIv(KEY, msg, RccFileCrypto.INFO_GROUP_SUBJECT, IV);
        assertTrue(!Arrays.equals(icon.ciphertext, subj.ciphertext));
        // Decrypting under the wrong filename fails rather than returning garbage.
        assertNull(RccFileCrypto.decrypt(KEY, icon.iv, icon.ciphertext, icon.tag,
                RccFileCrypto.INFO_GROUP_SUBJECT, msg.length));
    }

    @Test
    public void roundTripsAcrossSizesIncludingEmptyAndPaddingBoundaries() {
        for (final int n : new int[] {0, 1, 2, 15, 16, 17, 63, 64, 255, 256, 1000, 4096}) {
            final byte[] msg = new byte[n];
            for (int i = 0; i < n; i++) msg[i] = (byte) (i * 31 + 7);
            final RccFileCrypto.Encrypted e =
                    RccFileCrypto.encrypt(KEY, msg, RccFileCrypto.INFO_GROUP_ICON);
            assertNotNull("encrypt(" + n + ")", e);
            assertArrayEquals("round-trip " + n, msg, RccFileCrypto.decrypt(
                    KEY, e.iv, e.ciphertext, e.tag, RccFileCrypto.INFO_GROUP_ICON, n));
        }
    }

    /** Annex C.3 authenticates before decrypting, so tampering is rejected, never partial. */
    @Test
    public void rejectsTamperedCiphertextTagIvAndKey() {
        final byte[] msg = "authenticate me".getBytes(StandardCharsets.UTF_8);
        final RccFileCrypto.Encrypted e =
                RccFileCrypto.encrypt(KEY, msg, RccFileCrypto.INFO_GROUP_ICON);

        final byte[] ct = e.ciphertext.clone();
        ct[0] ^= 0x01;
        assertNull("flipped ciphertext bit",
                RccFileCrypto.decrypt(KEY, e.iv, ct, e.tag, RccFileCrypto.INFO_GROUP_ICON,
                        msg.length));

        final byte[] tag = e.tag.clone();
        tag[31] ^= 0x01;
        assertNull("flipped tag bit",
                RccFileCrypto.decrypt(KEY, e.iv, e.ciphertext, tag, RccFileCrypto.INFO_GROUP_ICON,
                        msg.length));

        final byte[] iv = e.iv.clone();
        iv[0] ^= 0x01;
        assertNull("flipped IV bit — the IV is inside the tag",
                RccFileCrypto.decrypt(KEY, iv, e.ciphertext, e.tag, RccFileCrypto.INFO_GROUP_ICON,
                        msg.length));

        final byte[] wrongKey = KEY.clone();
        wrongKey[0] ^= 0x01;
        assertNull("wrong key", RccFileCrypto.decrypt(wrongKey, e.iv, e.ciphertext, e.tag,
                RccFileCrypto.INFO_GROUP_ICON, msg.length));
    }

    /** Annex C.3: the decrypted messageLength must equal the out-of-band hint. */
    @Test
    public void rejectsAFileLengthHintMismatch() {
        final byte[] msg = "hint me".getBytes(StandardCharsets.UTF_8);
        final RccFileCrypto.Encrypted e =
                RccFileCrypto.encrypt(KEY, msg, RccFileCrypto.INFO_GROUP_ICON);
        assertNull(RccFileCrypto.decrypt(KEY, e.iv, e.ciphertext, e.tag,
                RccFileCrypto.INFO_GROUP_ICON, msg.length + 1));
        // -1 skips only that check.
        assertArrayEquals(msg, RccFileCrypto.decrypt(KEY, e.iv, e.ciphertext, e.tag,
                RccFileCrypto.INFO_GROUP_ICON, -1));
    }

    @Test
    public void rejectsAShortCiphertext() {
        assertNull(RccFileCrypto.decrypt(KEY, IV, new byte[7], new byte[32],
                RccFileCrypto.INFO_GROUP_ICON, 0));
    }

    /**
     * Padmé (PETS 2019 §4): {@code E=floor(log2(L))}, {@code S=floor(log2(E))+1}, zero the low
     * {@code E-S} bits rounding up. RCC.16 cites the paper without restating it, and Annex C.3
     * checks the padding length, so a divergence makes files undecryptable.
     */
    @Test
    public void padmeMatchesTheIndependentlyRestatedAlgorithm() {
        for (long l = 0; l < 5000; l++) {
            assertEquals("padme(" + l + ")", expectedPadme(l), RccFileCrypto.padme(l));
        }
        // Never shorter than the input, and idempotent on a padded size.
        for (final long l : new long[] {3, 100, 1234, 65536, 1_000_000}) {
            final long p = RccFileCrypto.padme(l);
            assertTrue("padme(" + l + ") >= " + l, p >= l);
            assertEquals("idempotent", p, RccFileCrypto.padme(p));
        }
    }

    private static long expectedPadme(final long l) {
        if (l < 2) return l;
        final int e = 63 - Long.numberOfLeadingZeros(l);
        if (e < 1) return l;
        final int s = (63 - Long.numberOfLeadingZeros((long) e)) + 1;
        final int z = e - s;
        if (z <= 0) return l;
        final long mask = (1L << z) - 1;
        return (l + mask) & ~mask;
    }

    @Test
    public void nullsAndBadLengthsDoNotThrow() {
        assertNull(RccFileCrypto.encrypt(null, new byte[1], "f"));
        assertNull(RccFileCrypto.encrypt(new byte[31], new byte[1], "f"));   // key must be 256-bit
        assertNull(RccFileCrypto.encrypt(KEY, null, "f"));
        assertNull(RccFileCrypto.encrypt(KEY, new byte[1], null));
        assertNull(RccFileCrypto.decrypt(KEY, new byte[11], new byte[16], new byte[32], "f", 1));
        assertEquals(32, RccFileCrypto.newKey().length);
    }
}
