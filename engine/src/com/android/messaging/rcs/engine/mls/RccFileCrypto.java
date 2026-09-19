/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * RCC.16 Annex C.2 / C.3 file encryption under a one-time key, used for the encrypted group icon
 * and subject: {@code k_enc ‖ k_hmac = HKDF-SHA256(Key, salt = pi, Info)}, Padmé-padded
 * {@code M ‖ pad ‖ messageLength ‖ paddingLength}, AES-CTR with {@code IV ‖ 0x00000000}, and
 * {@code Tag = HMAC-SHA256(k_hmac, IV ‖ Ciphertext)}. See docs/mls/metadata.md.
 */
public final class RccFileCrypto {

    private RccFileCrypto() { }

    /** Annex C.2 salt: the 256-bit hex representation of pi. */
    private static final byte[] SALT = {
        (byte) 0x32, (byte) 0x43, (byte) 0xf6, (byte) 0xa8, (byte) 0x88, (byte) 0x5a, (byte) 0x30,
        (byte) 0x8d, (byte) 0x31, (byte) 0x31, (byte) 0x98, (byte) 0xa2, (byte) 0xe0, (byte) 0x37,
        (byte) 0x07, (byte) 0x34, (byte) 0x4a, (byte) 0x40, (byte) 0x93, (byte) 0x82, (byte) 0x22,
        (byte) 0x99, (byte) 0xf3, (byte) 0x1d, (byte) 0x00, (byte) 0x82, (byte) 0xef, (byte) 0xa9,
        (byte) 0x8e, (byte) 0xc4, (byte) 0xe6, (byte) 0xc8,
    };

    /** RCC.16 §9.7.1.4 {@code Info} for the group icon; a KDF input, not a label. */
    public static final String INFO_GROUP_ICON = "group_icon";
    /** RCC.16 §9.7.1.5 {@code Info} for the group subject. */
    public static final String INFO_GROUP_SUBJECT = "group_subject";

    /** One random 256-bit key per file. */
    public static final int KEY_LEN = 32;
    /** 96-bit random nonce. */
    public static final int IV_LEN = 12;
    /** HMAC-SHA256 output. */
    public static final int TAG_LEN = 32;

    /** The C.2 output {@code (IV, Ciphertext, Tag)}. */
    public static final class Encrypted {
        public final byte[] iv;
        public final byte[] ciphertext;
        public final byte[] tag;
        /** The plaintext length, sent out of band; C.3 checks the decrypted length against it. */
        public final int fileLengthHint;
        Encrypted(byte[] iv, byte[] ciphertext, byte[] tag, int fileLengthHint) {
            this.iv = iv; this.ciphertext = ciphertext; this.tag = tag;
            this.fileLengthHint = fileLengthHint;
        }
    }

    /**
     * A fresh content key. Never reuse one across files: that reuses the AES-CTR keystream.
     */
    public static byte[] newKey() {
        final byte[] k = new byte[KEY_LEN];
        new SecureRandom().nextBytes(k);
        return k;
    }

    /**
     * Annex C.2 {@code Encrypt(Key, M, Info)}.
     *
     * @param info the original file name, a KDF input ({@link #INFO_GROUP_ICON} /
     *     {@link #INFO_GROUP_SUBJECT})
     * @return the triple, or {@code null} if the inputs are unusable
     */
    public static Encrypted encrypt(final byte[] key, final byte[] message, final String info) {
        if (key == null || key.length != KEY_LEN || message == null || info == null) return null;
        final byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        return encryptWithIv(key, message, info, iv);
    }

    /** {@link #encrypt} with a fixed IV, for test vectors. */
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

            // Both lengths are u32 big-endian.
            final ByteArrayOutputStream padded =
                    new ByteArrayOutputStream(messageLength + paddingLength + 8);
            padded.write(message);
            padded.write(new byte[paddingLength]);
            padded.write(u32(messageLength));
            padded.write(u32(paddingLength));

            final byte[] ciphertext =
                    aesCtr(Cipher.ENCRYPT_MODE, keys[0], iv, padded.toByteArray());
            return new Encrypted(iv, ciphertext, hmac(keys[1], concat(iv, ciphertext)),
                    messageLength);
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Annex C.3 {@code Decrypt}, with every check the spec lists; any failure returns {@code null}.
     *
     * @param fileLengthHint the transported plaintext length, or {@code -1} to skip only that check
     */
    public static byte[] decrypt(final byte[] key, final byte[] iv, final byte[] ciphertext,
            final byte[] tag, final String info, final int fileLengthHint) {
        if (key == null || key.length != KEY_LEN || iv == null || iv.length != IV_LEN
                || ciphertext == null || tag == null || info == null) {
            return null;
        }
        try {
            if (ciphertext.length < 8) return null;
            final byte[][] keys = deriveKeys(key, info);

            // Authenticate before decrypting, in constant time.
            if (!constantTimeEquals(hmac(keys[1], concat(iv, ciphertext)), tag)) return null;

            final byte[] plaintext = aesCtr(Cipher.DECRYPT_MODE, keys[0], iv, ciphertext);
            final int n = plaintext.length;
            if (n < 8) return null;
            final long messageLength = readU32(plaintext, n - 8);
            final long paddingLength = readU32(plaintext, n - 4);

            if (messageLength >= (1L << 31)) return null;
            if (fileLengthHint >= 0 && messageLength != fileLengthHint) return null;
            if (ciphertext.length != 8L + messageLength + paddingLength) return null;
            if (paddingLength != padme(messageLength) - messageLength) return null;
            if (messageLength + paddingLength + 8L != n) return null;

            // The pad must be zero; checked in constant time.
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
     * Padmé (PETS 2019, "Reducing Metadata Leakage from Encrypted Files and Communication with
     * PURBs", §4): with {@code E = floor(log2 L)} and {@code S = floor(log2 E) + 1}, round
     * {@code L} up to a multiple of {@code 2^(E - S)}. RCC.16 cites the paper without restating
     * it, so this rounding is an interoperability assumption; C.3 rejects a file whose padding
     * differs. Lengths 0 and 1 are returned unchanged.
     */
    public static long padme(final long length) {
        if (length < 2) return length;
        final int e = 63 - Long.numberOfLeadingZeros(length);  // floor(log2(L))
        if (e < 1) return length;
        final int s = (63 - Long.numberOfLeadingZeros((long) e)) + 1;  // floor(log2(E)) + 1
        final int z = e - s;
        if (z <= 0) return length;
        final long mask = (1L << z) - 1;
        return (length + mask) & ~mask;
    }

    // ---- primitives ----

    /** {@code k_enc ‖ k_hmac}, 32 bytes each. */
    private static byte[][] deriveKeys(final byte[] key, final String info) throws Exception {
        final byte[] okm = hkdf(key, SALT, info.getBytes(StandardCharsets.UTF_8), 64);
        return new byte[][] {
            Arrays.copyOfRange(okm, 0, 32),
            Arrays.copyOfRange(okm, 32, 64),
        };
    }

    /** RFC 5869 HKDF-SHA256. */
    private static byte[] hkdf(final byte[] ikm, final byte[] salt, final byte[] info,
            final int len)
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

    /** AES-CTR with the 96-bit nonce followed by a zero 32-bit counter. */
    private static byte[] aesCtr(final int mode, final byte[] k, final byte[] iv, final byte[] in)
            throws Exception {
        final byte[] ivPrime = new byte[16];
        System.arraycopy(iv, 0, ivPrime, 0, IV_LEN);
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
