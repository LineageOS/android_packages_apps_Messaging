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

/**
 * The continuity token and its commitment (RCC.16 §7.11.12, §8.3.1.1): a group secret linking a
 * new era to its predecessor, and a hash that lets a peer check the link without the server
 * learning the token.
 *
 * <pre>
 *   struct { opaque continuity_token&lt;V&gt;; opaque epoch_authenticator&lt;V&gt;; } TokenCommitment
 *   token_commitment = RefHash("Continuity Token GroupInfo Commitment", TokenCommitment)
 * </pre>
 *
 * <p>This class computes and stores received tokens and never emits one: emitting {@code 0xF011}
 * would arm validation on every peer, whose failure is a downgrade. See docs/mls/metadata.md.
 */
public final class MlsContinuityToken {

    private MlsContinuityToken() { }

    /** RCC.16 §8.3.1.1: 256 bits. */
    public static final int TOKEN_BYTES = 32;

    /**
     * Our own bound, not the spec's: the record is rewritten whole on every update, so a
     * peer-chosen length would amplify every later write.
     */
    public static final int MAX_STORED_TOKEN_BYTES = 8 * TOKEN_BYTES;

    /** RCC.16 §7.11.12.2, without the {@code "MLS 1.0 "} prefix {@link #refHash} adds. */
    public static final String COMMITMENT_LABEL = "Continuity Token GroupInfo Commitment";

    private static final String MLS_LABEL_PREFIX = "MLS 1.0 ";

    public static byte[] mint() {
        final byte[] t = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(t);
        return t;
    }

    /** 32 bytes, or null if an input is missing or the digest is unavailable. */
    public static byte[] commitment(final byte[] token, final byte[] epochAuthenticator) {
        if (token == null || epochAuthenticator == null) return null;
        final byte[] inner = tokenCommitmentStruct(token, epochAuthenticator);
        if (inner == null) return null;
        return refHash(COMMITMENT_LABEL, inner);
    }

    /** Public because RCC.16 §7.11.12.2 describes the extension as this struct and as its hash. */
    public static byte[] tokenCommitmentStruct(final byte[] token,
            final byte[] epochAuthenticator) {
        if (token == null || epochAuthenticator == null) return null;
        try {
            final ByteArrayOutputStream out =
                    new ByteArrayOutputStream(token.length + epochAuthenticator.length + 8);
            out.write(MlsAppMessage.mlsVarint(token.length));
            out.write(token);
            out.write(MlsAppMessage.mlsVarint(epochAuthenticator.length));
            out.write(epochAuthenticator);
            return out.toByteArray();
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * RFC 9420 §5.2 {@code RefHash}, with the {@code "MLS 1.0 "} label prefix that
     * {@link RccCommitment}'s labels lack. Null if the inputs are unusable.
     */
    public static byte[] refHash(final String label, final byte[] value) {
        if (label == null || value == null) return null;
        try {
            final byte[] l = (MLS_LABEL_PREFIX + label).getBytes(StandardCharsets.US_ASCII);
            final ByteArrayOutputStream out =
                    new ByteArrayOutputStream(l.length + value.length + 8);
            out.write(MlsAppMessage.mlsVarint(l.length));
            out.write(l);
            out.write(MlsAppMessage.mlsVarint(value.length));
            out.write(value);
            return MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Constant-time. */
    public static boolean commitmentMatches(final byte[] a, final byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }
}
