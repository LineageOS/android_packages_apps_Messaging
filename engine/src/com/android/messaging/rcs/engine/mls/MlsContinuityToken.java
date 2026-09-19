/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
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

    /**
     * The single write path for both arrival routes (a Welcome, and RCC.16 §10.5.4
     * {@code GroupMetadataKeys}); a received token replaces a held one. Oversize is logged as
     * OVERSIZE, not DROPPED, which a peer must not be able to provoke.
     *
     * @return true if we now hold this token
     */
    public static boolean noteContinuityToken(final MlsShellPort shell, final MlsLogSink log,
            final String key, final byte[] token, final String source) {
        if (token == null || token.length == 0) return false;
        if (token.length > MlsContinuityToken.MAX_STORED_TOKEN_BYTES) {
            log.w("MlsContinuityToken: continuity OVERSIZE — " + source + " offered a "
                    + token.length + "B token for " + MlsConversationKey.forLog(key) + ", over the "
                    + MlsContinuityToken.MAX_STORED_TOKEN_BYTES + "B bound (§8.3.1.1 says 32). "
                    + "Refused: the record "
                    + "is rewritten WHOLE on every update, so a peer-chosen length here is a write "
                    + "amplifier on every later write. Deliberately not logged as DROPPED — that "
                    + "word is a NEVER marker and this one IS peer-provokable.");
            return false;
        }
        final String self = shell.selfE164();
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (self.isEmpty() || g == null || g.groupId == null || g.groupId.length == 0) {
            log.w("MlsContinuityToken: continuity DROPPED — a " + token.length
                    + "B token from " + source + " for key=" + MlsConversationKey.forLog(key)
                    + " could not be keyed ("
                    + (self.isEmpty() ? "no self identity" : "no MLS group under that key")
                    + "). Logged loudly on purpose: a token dropped in silence is unrecoverable.");
            return false;
        }
        shell.lock(key);
        try {
            final StoreRead<MlsConversationRecord> r = shell.records().get(self, g.groupId);
            if (r.isErr()) {
                // Never overwrite an unreadable record; it may hold an in-flight operation.
                log.w("MlsContinuityToken: continuity DROPPED — the record for "
                        + MlsConversationKey.forLog(key)
                        + " is unreadable (" + ((StoreRead.Err<MlsConversationRecord>) r).reason
                        + "); refusing to overwrite it to store a token");
                return false;
            }
            final MlsConversationRecord rec = r.isOk()
                    ? ((StoreRead.Ok<MlsConversationRecord>) r).value
                    : MlsConversationRecord.initial(self, g.groupId, g.rcsGroupId, g.peerE164);
            final byte[] held = rec.continuityToken;
            final String verdict;
            if (held.length == 0) {
                verdict = "FIRST";
            } else if (java.util.Arrays.equals(held, token)) {
                log.i("MlsContinuityToken: continuity SAME — " + source + " re-delivered "
                        + "the " + token.length + "B token we already hold for "
                        + MlsConversationKey.forLog(key)
                        + "; no write");
                return true;
            } else {
                verdict = "REPLACED (was " + held.length + "B)";
            }
            final String err = shell.records().put(rec.toBuilder().continuityToken(token).build());
            if (err != null) {
                log.w("MlsContinuityToken: continuity DROPPED — could not persist the "
                        + token.length + "B token for " + MlsConversationKey.forLog(key) + ": "
                        + err);
                return false;
            }
            // Length only, never the value: it is a group secret.
            log.i("MlsContinuityToken: continuity " + verdict + " — stored a "
                    + token.length + "B token for " + MlsConversationKey.forLog(key) + " from "
                    + source
                    + " into the record's continuityToken"
                    + (token.length == MlsContinuityToken.TOKEN_BYTES ? ""
                            : " [UNEXPECTED LENGTH: §8.3.1.1 says 256 bits; stored anyway]")
                    + ". Nothing consumes it yet — §8.3.1.2/.3 is MlsContinuityPolicy, which is "
                    + "deliberately not wired in. Storage is the part that must not wait: "
                    + "a commitment published over a token we cannot reproduce across a restart is a "
                    + "guaranteed mismatch.");
            return true;
        } catch (final Throwable t) {
            log.w("MlsContinuityToken: continuity DROPPED — storing the token for "
                    + MlsConversationKey.forLog(key)
                    + " threw", t);
            return false;
        } finally { shell.unlock(key); }
    }

    /**
     * Persists the token from the Welcome that admitted us (RCC.16 §7.11.12.1), which only the
     * engine can read. None is ordinary: groups we created, external commits, peers without it.
     */
    public static void collectWelcomeContinuityToken(final MlsShellPort shell, final MlsLogSink log,
            final String key, final byte[] mlsGroupId) {
        if (shell.session() == null || mlsGroupId == null || mlsGroupId.length == 0) return;
        final byte[] token;
        try {
            token = shell.session().takeWelcomeContinuityToken(mlsGroupId);
        } catch (final Throwable t) {
            // The join has succeeded; a failed bookkeeping read must not undo it.
            log.w("MlsContinuityToken: reading the Welcome's continuity token threw for "
                    + MlsConversationKey.forLog(key)
                    + " — the join stands and we simply hold no token", t);
            return;
        }
        if (token == null || token.length == 0) return;
        MlsContinuityToken.noteContinuityToken(shell, log, key, token, "§7.11.12.1 Welcome");
    }
}
