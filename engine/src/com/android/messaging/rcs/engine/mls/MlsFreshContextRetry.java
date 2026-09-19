/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * One re-dial of an era-advance create with a freshly minted {@code contextId}, before the
 * destructive rebuild. A create that reuses a context id the server already holds returns OK and
 * does not move the era; the artifacts are still in hand at that point, so the same bytes are sent
 * again with only the context id changed. No KeyPackage is claimed and no second era-budget charge
 * is taken; any outcome but a granted era falls through to the existing path. Pure Java, so host
 * tests exercise what production runs. See docs/mls/group-lifecycle.md.
 */
public final class MlsFreshContextRetry {

    private MlsFreshContextRetry() {}

    /** The live value of {@code debug.rcs.mls_advance_fresh_ctxid} that means "do not mint". */
    public static final String MODE_OFF = "off";

    /** The kill switch for the re-dial; default true. */
    public static final String KEY_RETRY = "debug.rcs.mls_advance_fresh_ctxid_retry";

    /** A peer-shaped {@code contextId}: 18 random bytes, base64url, unpadded, 24 characters. */
    public static final int CONTEXT_ID_CHARS = 24;
    private static final int CONTEXT_ID_BYTES = 18;

    /** What to do about a create that the server accepted and did not apply. */
    public enum Decision {
        /** Re-dial once with a freshly minted contextId, then re-read the era. */
        RETRY,
        /** The kill switch is set. */
        SKIP_DISABLED,
        /**
         * The lever could already have minted for this create, so re-minting would repeat the
         * question.
         */
        SKIP_LEVER_MAY_HAVE_MINTED,
        /** Not an accepted create whose era was read back and did not move. */
        SKIP_NOT_A_SILENT_NON_ADVANCE,
    }

    /**
     * Should the refused advance get one re-dial? Only for a create that was accepted, whose era
     * was read back and did not move; a refused create or an unread era is a different finding.
     * Skipped where the provider's fresh-id lever could already have minted for this shape.
     *
     * @param createAccepted  the create returned gRPC OK
     * @param eraWasReadBack  the post-create era read reached the server (the ledger did not
     *     refuse)
     * @param eraMoved        the server's era is now the one we asked for
     * @param isGroup         this advance addresses an RCS group rather than a 1:1
     * @param freshCtxIdMode  the live {@code debug.rcs.mls_advance_fresh_ctxid}
     * @param retryEnabled    the live {@link #KEY_RETRY}, default {@code true}
     */
    public static Decision decide(final boolean createAccepted, final boolean eraWasReadBack,
            final boolean eraMoved, final boolean isGroup, final String freshCtxIdMode,
            final boolean retryEnabled) {
        if (!createAccepted || !eraWasReadBack || eraMoved) {
            return Decision.SKIP_NOT_A_SILENT_NON_ADVANCE;
        }
        if (!retryEnabled) return Decision.SKIP_DISABLED;
        if (leverCouldHaveMinted(isGroup, freshCtxIdMode)) {
            return Decision.SKIP_LEVER_MAY_HAVE_MINTED;
        }
        return Decision.RETRY;
    }

    /**
     * Could the provider's fresh-id lever have minted for this create? {@code off} or empty never;
     * {@code all} for both shapes; {@code 1to1} only for a 1:1; anything else never.
     */
    public static boolean leverCouldHaveMinted(final boolean isGroup, final String mode) {
        if (mode == null || mode.isEmpty() || MODE_OFF.equalsIgnoreCase(mode)) return false;
        if ("all".equalsIgnoreCase(mode)) return true;
        return !isGroup && "1to1".equalsIgnoreCase(mode);
    }

    /** The line that says what is about to be tried, and why. */
    public static String retryingLine(final String key, final int newEra, final String mode) {
        return "FRESH-contextId RE-DIAL for " + MlsConversationKey.forLog(key)
                + " — the advance to era " + newEra + " was "
                + "accepted (gRPC OK) and the server's era did not move, which is exactly the shape "
                + "of a create under a contextId the server already holds. The artifacts "
                + "are still in hand and the engine has NOT been rolled back, so this re-sends the "
                + "SAME bytes with ONE field changed — a freshly minted contextId. No KeyPackage is "
                + "claimed and no era-budget charge is taken; if the era moves we keep the "
                + "conversation and skip the rebuild entirely (debug.rcs.mls_advance_fresh_ctxid="
                + mode + " did not mint for this create, which is why the stored id went out).";
    }

    /** The line for every outcome that is not {@link Decision#RETRY}. */
    public static String skipLine(final Decision d, final String key, final int newEra,
            final String mode) {
        switch (d) {
            case SKIP_DISABLED:
                return MlsConversationKey.forLog(key)
                        + " — NOT re-dialling the advance to era " + newEra + " with a fresh "
                        + "contextId: " + KEY_RETRY + " is 0. Falling through to whatever "
                        + "debug.rcs.mls_advance_create_fallback says, which by default is the "
                        + "destructive rebuild.";
            case SKIP_LEVER_MAY_HAVE_MINTED:
                return MlsConversationKey.forLog(key)
                        + " — NOT re-dialling the advance to era " + newEra + " with a fresh "
                        + "contextId: debug.rcs.mls_advance_fresh_ctxid=" + mode + " already "
                        + "permits minting for this shape, so the create may ALREADY have sent a "
                        + "fresh contextId and the known cause does not explain this refusal. Check "
                        + "the create log for 'contextId MINTED FRESH'; if it is ABSENT the lever "
                        + "did not fire (it also needs a prior provider record under this MLS group "
                        + "id) and this skip was too cautious.";
            case SKIP_NOT_A_SILENT_NON_ADVANCE:
            default:
                return MlsConversationKey.forLog(key)
                        + " — NOT re-dialling the advance to era " + newEra
                        + ": this is not the "
                        + "accepted-and-not-applied shape. A create the server REFUSED carries an "
                        + "error on the channel refusals use, and an era we could not READ is not a "
                        + "measurement.";
        }
    }

    /** The line for a granted re-dial. */
    public static String grantedLine(final String key, final int newEra, final String minted) {
        return "FRESH-contextId RE-DIAL GRANTED for " + MlsConversationKey.forLog(key)
                + " — the server now reports era "
                + newEra + " under the minted contextId " + minted
                + ". The destructive rebuild is NOT "
                + "run: the provider record and the engine group both survive, members re-join by "
                + "Welcome on the same group id (the cost an era advance already imposes), and the "
                + "era budget is charged once rather than twice. This is the remedy applied at "
                + "the point of measurement instead of by way of forgetting everything.";
    }

    /** The line for a re-dial that did not move the era either. */
    public static String notGrantedLine(final String key, final int newEra, final String minted,
            final long serverEra) {
        return "FRESH-contextId RE-DIAL did NOT take for " + MlsConversationKey.forLog(key)
                + " — a freshly minted contextId ("
                + minted + ") was accepted and the server still reports era " + serverEra
                + ", not " + newEra + ". That is a REAL result and it is worth more than the RPC "
                + "cost: the rule predicts a grant here, so this conversation's refusal has a "
                + "cause the contextId does not explain. Falling through to the path that would "
                + "have run anyway.";
    }

    /**
     * Mint a {@code contextId} in the shape peers send. The provider honours a supplied id only in
     * this shape ({@link #isReferenceShapedContextId}); a wrong shape would be silently replaced by
     * the stored id.
     *
     * @param rng caller-supplied so a test can pin the shape without pinning the bytes
     */
    public static String mintContextId(final SecureRandom rng) {
        final byte[] raw = new byte[CONTEXT_ID_BYTES];
        rng.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Is this the shape the provider will honour: 24 base64url characters, no padding? The provider
     * re-tests it on its side of the AIDL boundary, so the two tests must agree.
     */
    public static boolean isReferenceShapedContextId(final String s) {
        if (s == null || s.length() != CONTEXT_ID_CHARS) return false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }
}
