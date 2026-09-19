/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.messaging.rcs.engine.mls;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * RCC.16 <b>Annex C.2 / C.3</b> — encrypting and decrypting a file under a one-shot symmetric key.
 *
 * <p>This is the primitive the E2EE group icon and subject are built on (§9.7.1.1 / §9.7.1.2): the
 * icon is encrypted with it and uploaded to the File Transfer Server, the subject is encrypted with
 * it and Base64'd into the MSRP, and the key itself travels in the {@code icon_key} (0xF003) /
 * {@code subject_key} (0xF005) GroupContext extensions. It is deliberately standalone — no MLS state,
 * no group, no transport — because it is pure spec crypto and can therefore be pinned by host tests
 * rather than by a device round-trip.
 *
 * <p>The construction, verbatim from C.2:
 *
 * <pre>
 *   salt        = 0x3243f6a8…e6c8            (the 256-bit hex representation of pi)
 *   k_enc‖k_hmac = HKDF-SHA256(Key, salt, Info)   each exactly 256 bits
 *   paddedMessage = M ‖ pad ‖ messageLength(u32) ‖ paddingLength(u32)
 *   IV'         = IV(96-bit random) ‖ 0x00000000
 *   Ciphertext  = AES-CTR-Enc(k_enc, IV', paddedMessage)
 *   Tag         = HMAC-SHA256(k_hmac, IV ‖ Ciphertext)
 * </pre>
 *
 * <p><b>Two things worth knowing before trusting this against a real peer.</b>
 *
 * <ol>
 *   <li><b>{@code Info} is the original filename</b>, and it is an input to the KDF — not a label we
 *       may choose. §9.7.1.4 fixes it to {@code "group_icon"} and §9.7.1.5 to {@code "group_subject"}.
 *       Get it wrong and the peer derives different keys, with a tag mismatch as the only symptom.</li>
 *   <li><b>{@code Padme} is NOT defined in RCC.16.</b> The spec cites the PETS 2019 PURB paper by
 *       reference and never restates the algorithm, so the exact rounding is an interop assumption on
 *       our side rather than something the spec pins — see {@link #padme(long)}. It is load-bearing:
 *       C.3 <em>verifies</em> {@code paddingLength == Padme(messageLength) - messageLength} and
 *       rejects the file otherwise, so a peer computing a different Padme cannot decrypt ours at all.
 *       Recorded as an open interop risk rather than a settled fact.</li>
 * </ol>
 */
public final class RccFileCrypto {

    private RccFileCrypto() { }

    /** Annex C.2 salt — "the 256 bit hex representation of pi". */
    private static final byte[] SALT = {
        (byte) 0x32, (byte) 0x43, (byte) 0xf6, (byte) 0xa8, (byte) 0x88, (byte) 0x5a, (byte) 0x30,
        (byte) 0x8d, (byte) 0x31, (byte) 0x31, (byte) 0x98, (byte) 0xa2, (byte) 0xe0, (byte) 0x37,
        (byte) 0x07, (byte) 0x34, (byte) 0x4a, (byte) 0x40, (byte) 0x93, (byte) 0x82, (byte) 0x22,
        (byte) 0x99, (byte) 0xf3, (byte) 0x1d, (byte) 0x00, (byte) 0x82, (byte) 0xef, (byte) 0xa9,
        (byte) 0x8e, (byte) 0xc4, (byte) 0xe6, (byte) 0xc8,
    };

    /** §9.7.1.4 — the {@code Info} (filename) the group ICON key is derived under. */
    public static final String INFO_GROUP_ICON = "group_icon";
    /** §9.7.1.5 — the {@code Info} (filename) the group SUBJECT key is derived under. */
    public static final String INFO_GROUP_SUBJECT = "group_subject";

    /** C.2: "K is a 256 bit randomly chosen key used for one and only one file." */
    public static final int KEY_LEN = 32;
    /** C.2: "Let IV be a 96 bit random nonce". */
    public static final int IV_LEN = 12;
    /** HMAC-SHA256, "256-bit tag output". */
    public static final int TAG_LEN = 32;

    /** The output of {@link #encrypt}: C.2 emits exactly {@code (IV, Ciphertext, Tag)}. */
    public static final class Encrypted {
        public final byte[] iv;
        public final byte[] ciphertext;
        public final byte[] tag;
        /** C.3 needs the original length out of band — it verifies the decrypted one against it. */
        public final int fileLengthHint;
        Encrypted(byte[] iv, byte[] ciphertext, byte[] tag, int fileLengthHint) {
            this.iv = iv; this.ciphertext = ciphertext; this.tag = tag;
            this.fileLengthHint = fileLengthHint;
        }
    }

    /**
     * Mint a fresh 256-bit content key. C.2 requires one key per file — reusing one across two files
     * reuses the AES-CTR keystream, which is a plaintext-recovery break, not a hygiene preference.
     */
    public static byte[] newKey() {
        final byte[] k = new byte[KEY_LEN];
        new SecureRandom().nextBytes(k);
        return k;
    }

    /**
     * Annex C.2 {@code Encrypt(Key, M, Info)}.
     *
     * @param info the ORIGINAL FILENAME — an input to the KDF, so it must match what the peer uses
     *             ({@link #INFO_GROUP_ICON} / {@link #INFO_GROUP_SUBJECT})
     * @return the triple, or {@code null} if the inputs are unusable
     */
    public static Encrypted encrypt(final byte[] key, final byte[] message, final String info) {
        if (key == null || key.length != KEY_LEN || message == null || info == null) return null;
        final byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        return encryptWithIv(key, message, info, iv);
    }

    /** {@link #encrypt} with a caller-supplied IV — for tests that need a fixed vector. */
    static Encrypted encryptWithIv(final byte[] key, final byte[] message, final String info,
            final byte[] iv) {
        if (key == null || key.length != KEY_LEN || message == null || info == null
                || iv == null || iv.length != IV_LEN) {
            return null;
        }
        try {
            final byte[][] keys = deriveKeys(key, info);
            final int messageLength = message.length;
            final int paddingLength = (int) (padme(messageLength) - messageLength);

            // paddedMessage = M ‖ pad ‖ messageLength ‖ paddingLength  (both lengths u32 big-endian)
            final ByteArrayOutputStream padded =
                    new ByteArrayOutputStream(messageLength + paddingLength + 8);
            padded.write(message);
            padded.write(new byte[paddingLength]);
            padded.write(u32(messageLength));
            padded.write(u32(paddingLength));

            final byte[] ciphertext = aesCtr(Cipher.ENCRYPT_MODE, keys[0], iv, padded.toByteArray());
            return new Encrypted(iv, ciphertext, hmac(keys[1], concat(iv, ciphertext)),
                    messageLength);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Annex C.3 {@code Decrypt}. Every check the spec lists is performed, and any failure returns
     * {@code null} rather than a partial result — a file that fails validation is not a file.
     *
     * @param fileLengthHint the length carried alongside the ciphertext; C.3 requires the decrypted
     *                       {@code messageLength} to equal it. Pass {@code -1} to skip only that
     *                       check (when the hint genuinely was not transported).
     */
    public static byte[] decrypt(final byte[] key, final byte[] iv, final byte[] ciphertext,
            final byte[] tag, final String info, final int fileLengthHint) {
        if (key == null || key.length != KEY_LEN || iv == null || iv.length != IV_LEN
                || ciphertext == null || tag == null || info == null) {
            return null;
        }
        try {
            if (ciphertext.length < 8) return null;             // "ciphertext too small"
            final byte[][] keys = deriveKeys(key, info);

            // Tag FIRST — authenticate before decrypting, and compare in constant time.
            if (!constantTimeEquals(hmac(keys[1], concat(iv, ciphertext)), tag)) return null;

            final byte[] plaintext = aesCtr(Cipher.DECRYPT_MODE, keys[0], iv, ciphertext);
            final int n = plaintext.length;
            if (n < 8) return null;
            final long messageLength = readU32(plaintext, n - 8);
            final long paddingLength = readU32(plaintext, n - 4);

            if (messageLength >= (1L << 31)) return null;       // "message too long"
            if (fileLengthHint >= 0 && messageLength != fileLengthHint) return null;
            if (ciphertext.length != 8L + messageLength + paddingLength) return null;
            if (paddingLength != padme(messageLength) - messageLength) return null;
            if (messageLength + paddingLength + 8L != n) return null;

            // The pad must be all zeroes — checked in constant time so a partially-correct pad does
            // not leak how far the comparison got.
            int diff = 0;
            for (long i = messageLength; i < messageLength + paddingLength; i++) {
                diff |= plaintext[(int) i];
            }
            if (diff != 0) return null;

            return Arrays.copyOfRange(plaintext, 0, (int) messageLength);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * PADME (PETS 2019, "Reducing Metadata Leakage from Encrypted Files and Communication with
     * PURBs", §4) — round a length up so that only a bounded number of distinct sizes are
     * observable.
     *
     * <pre>
     *   E = floor(log2(L))        // exponent
     *   S = floor(log2(E)) + 1    // bits needed to represent E
     *   z = E - S                 // low bits to zero
     *   L' = (L + 2^z - 1) &amp; ~(2^z - 1)
     * </pre>
     *
     * <p><b>RCC.16 does not restate this.</b> It cites [PADME] by URL and nothing more, so the
     * rounding here comes from the paper, not from the spec — an interop assumption, and a
     * load-bearing one, because C.3 verifies the padding length against it and rejects a mismatch.
     * If a peer's files ever fail to decrypt with "ciphertext decoding", this is the first thing to
     * suspect.
     *
     * <p>Lengths 0 and 1 are returned unchanged: {@code log2} is undefined at 0, and the formula
     * degenerates for 1.
     */
    public static long padme(final long length) {
        if (length < 2) return length;
        final int e = 63 - Long.numberOfLeadingZeros(length);        // floor(log2(L))
        if (e < 1) return length;
        final int s = (63 - Long.numberOfLeadingZeros((long) e)) + 1; // floor(log2(E)) + 1
        final int z = e - s;
        if (z <= 0) return length;
        final long mask = (1L << z) - 1;
        return (length + mask) & ~mask;
    }

    // ---- primitives ----

    /** {@code k_enc‖k_hmac = HKDF-SHA256(Key, salt, Info)}, each exactly 256 bits. */
    private static byte[][] deriveKeys(final byte[] key, final String info) throws Exception {
        final byte[] okm = hkdf(key, SALT, info.getBytes(StandardCharsets.UTF_8), 64);
        return new byte[][] {
            Arrays.copyOfRange(okm, 0, 32),
            Arrays.copyOfRange(okm, 32, 64),
        };
    }

    /** RFC 5869 HKDF-SHA256: extract-then-expand. */
    private static byte[] hkdf(final byte[] ikm, final byte[] salt, final byte[] info, final int len)
            throws Exception {
        final byte[] prk = hmac(salt, ikm);
        final ByteArrayOutputStream out = new ByteArrayOutputStream(len + 32);
        byte[] t = new byte[0];
        for (int i = 1; out.size() < len; i++) {
            final ByteArrayOutputStream block = new ByteArrayOutputStream();
            block.write(t);
            block.write(info);
            block.write((byte) i);
            t = hmac(prk, block.toByteArray());
            out.write(t);
        }
        return Arrays.copyOfRange(out.toByteArray(), 0, len);
    }

    private static byte[] hmac(final byte[] key, final byte[] data) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /** AES-CTR with {@code IV' = IV ‖ 0x00000000} — the 96-bit nonce plus the 4-byte zero counter. */
    private static byte[] aesCtr(final int mode, final byte[] k, final byte[] iv, final byte[] in)
            throws Exception {
        final byte[] ivPrime = new byte[16];
        System.arraycopy(iv, 0, ivPrime, 0, IV_LEN);            // trailing 4 bytes stay zero (ZV)
        final Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        c.init(mode, new SecretKeySpec(k, "AES"), new IvParameterSpec(ivPrime));
        return c.doFinal(in);
    }

    private static byte[] u32(final long v) {
        return new byte[] {
            (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v,
        };
    }

    private static long readU32(final byte[] b, final int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean constantTimeEquals(final byte[] a, final byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        return MessageDigest.isEqual(a, b);
    }
}
