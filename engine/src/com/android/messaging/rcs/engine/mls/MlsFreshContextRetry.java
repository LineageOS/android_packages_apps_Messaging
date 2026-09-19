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

import java.security.SecureRandom;
import java.util.Base64;

/**
 * ONE RE-DIAL WITH A FRESH {@code contextId}, BEFORE THE DESTRUCTIVE REBUILD.
 *
 * <h2>The rule this acts on, which is settled and not re-derived here</h2>
 *
 * <p>A {@code CreateMlsConversation} that reuses a {@code contextId} the server already holds is
 * NOT APPLIED, and nothing says so: the RPC completes with gRPC OK and the server's era, re-read
 * afterwards, does not move. 8/8 controlled trials — a 1:1 with four alternating arms 2026-09-08,
 * and a GROUP with four arms across two purpose-built groups 2026-09-10, with ordinal position, era
 * value and group identity each controlled out. Minted is GRANTED; stored is NOT APPLIED.
 *
 * <p>An era advance IS a create, and by default it sends the STORED id, so by default every era
 * advance asks the one thing that does not take.
 *
 * <h2>Why a re-dial and not a refusal, which was the other option</h2>
 *
 * <p>The cheaper interim would have been to give the other era-advance callers what
 * {@code floorRebuild} has — {@code MlsFloorRebuild#leverRefusal}, which declines BEFORE
 * attempting. That must not ship, and the reason is arithmetic rather than judgement:
 * {@code leverRefusal} proceeds only when {@code debug.rcs.mls_advance_create_fallback} is
 * <b>false</b> AND {@code debug.rcs.mls_advance_fresh_ctxid} is <b>not off</b>, and the fleet runs
 * the defaults (true / "off"). Wiring the automatic callers to it would disable every automatic era
 * advance on every device. {@code floorRebuild} can decline for free because it is
 * operator-triggered — declining means "set the props and re-run". The automatic callers have no
 * operator, so declining converts "expensive but it recovers" into "stuck forever".
 *
 * <h2>What this does instead, and why it is nearly free</h2>
 *
 * <p>At the moment the non-advance is measured, the artifacts the create was built from are still
 * in hand and the engine has not yet been rolled back. So the whole remedy is: <b>send the same
 * bytes again with one field changed</b> — a freshly minted, Google Messages-shaped
 * {@code contextId}. That is the treatment arm of the A/B above, applied to a patient already
 * diagnosed.
 *
 * <ul>
 *   <li><b>No KeyPackage is claimed</b> — {@code createGroupPlanned} already ran and its artifacts
 *       are reused verbatim.</li>
 *   <li><b>No second G2 charge</b> — the era budget was charged at the {@code eraAdvance} funnel and
 *       this is the same attempt, not a new peer-facing re-creation ({@link MlsRecreationEpisode}
 *       governs the charge the REBUILD pays, and that rebuild is what this tries to avoid).</li>
 *   <li><b>Nothing is declined</b> — on any outcome but a granted era we fall through to exactly
 *       today's path, {@code rebuildConversation} included.</li>
 * </ul>
 *
 * <p>The prize is the rebuild: forget the provider record, forget the engine group, re-establish,
 * re-Welcome every member, and charge G2 a second time. Why that ever
 * worked is plain enough — the rebuild only ever worked because dropping the provider record made
 * the NEXT create mint one by accident. This asks for the mint on purpose and keeps the
 * conversation.
 *
 * <h2>What this is NOT</h2>
 *
 * <p>It is not the full flip ({@code freshContextIdForRecreate} minting by default on every
 * re-create). That changes behaviour on every era advance on every device and is the operator's
 * call; era storms are the thing being guarded against. This fires
 * only after an advance has already been measured as not applied — i.e. only on conversations that
 * today are headed for the destructive rebuild — so a grantable advance never sees it.
 *
 * <p>It does not fix the DIVERGED rebuild, which spends an era-budget
 * charge per attempt and stranding the conversation; this reduces how often the rebuild is reached
 * and touches nothing inside it.
 *
 * <h2>Why the decision lives here and not in an {@code if}</h2>
 *
 * <p>Pure Java, no Android, no engine dependency — so the host tests exercise the same code
 * production calls, which is the only honest coverage available: the state it judges (a server that
 * answers OK and then does not move) cannot be produced on a device on demand. Same charter as
 * {@link MlsFloorRebuild} and {@link MlsRecreationEpisode}.
 */
public final class MlsFreshContextRetry {

    private MlsFreshContextRetry() {}

    /** The live value of {@code debug.rcs.mls_advance_fresh_ctxid} that means "do not mint". */
    public static final String MODE_OFF = "off";

    /** The kill switch for the re-dial. Default TRUE — see {@link #decide}. */
    public static final String KEY_RETRY = "debug.rcs.mls_advance_fresh_ctxid_retry";

    /** Google Messages' {@code contextId} is 18 random bytes, base64url, unpadded — 24 characters. */
    public static final int CONTEXT_ID_CHARS = 24;
    private static final int CONTEXT_ID_BYTES = 18;

    /** What to do about a create that the server accepted and did not apply. */
    public enum Decision {
        /** Re-dial once with a freshly minted contextId, then re-read the era. */
        RETRY,
        /** The kill switch is set. */
        SKIP_DISABLED,
        /**
         * The lever could already have minted for this create, so the stored-contextId cause does
         * not apply and re-minting would be asking a question we just asked.
         */
        SKIP_LEVER_MAY_HAVE_MINTED,
        /** Not the shape this remedy is for — see {@link #decide}'s first paragraph. */
        SKIP_NOT_A_SILENT_NON_ADVANCE,
    }

    /**
     * Should the refused advance get one re-dial with a fresh {@code contextId}?
     *
     * <p><b>The shape gate comes first and is deliberately strict.</b> This remedy addresses ONE
     * outcome: the create was ACCEPTED (gRPC OK), the server's era was actually READ BACK, and it
     * did not move. A create the server REFUSED has an error on the channel refusals use and is a
     * different finding; an era we could not read is not a measurement at all, and treating it as
     * one is how an unverified advance gets built on.
     *
     * <p><b>The lever gate mirrors {@code freshContextIdForRecreate}'s own conditions</b>
     * (provider side, {@code MlsCreateConversationClient}): the lever fires only for mode
     * {@code 1to1} on a 1:1 or mode {@code all} on either shape — so mode {@code 1to1} on a GROUP
     * advance means the stored/engine id went out after all, and the re-dial is worth taking. Where
     * the lever COULD have fired we skip, because the remaining precondition (a prior provider
     * record) is not observable from this side: we would be re-asking a question the create may
     * already have asked. The create log's {@code contextId MINTED FRESH} line is what settles it
     * for a reader, and {@code eraAdvanceLeverDiagnostic} already points at it.
     *
     * @param createAccepted  the {@code CreateMlsConversation} returned gRPC OK
     * @param eraWasReadBack  the post-create era read reached the server (the ledger did not refuse)
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
     * Could {@code freshContextIdForRecreate} have minted for this create?
     *
     * <p>The provider's gate, restated: {@code off}/empty never mints; {@code all} mints for both
     * shapes; {@code 1to1} mints only when there is no RCS group id. Anything else is an
     * unrecognised value and mints nothing — matching the provider, which falls through its two
     * {@code equalsIgnoreCase} arms to {@code return null}.
     */
    public static boolean leverCouldHaveMinted(final boolean isGroup, final String mode) {
        if (mode == null || mode.isEmpty() || MODE_OFF.equalsIgnoreCase(mode)) return false;
        if ("all".equalsIgnoreCase(mode)) return true;
        return !isGroup && "1to1".equalsIgnoreCase(mode);
    }

    /** The line that says what is about to be tried, and on whose evidence. */
    public static String retryingLine(final String key, final int newEra, final String mode) {
        return "FRESH-contextId RE-DIAL for " + key + " — the advance to era " + newEra + " was "
                + "accepted (gRPC OK) and the server's era did not move, which is exactly the shape "
                + "measured 8/8 as a contextId the server already holds. The artifacts "
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
                return key + " — NOT re-dialling the advance to era " + newEra + " with a fresh "
                        + "contextId: " + KEY_RETRY + " is 0. Falling through to whatever "
                        + "debug.rcs.mls_advance_create_fallback says, which by default is the "
                        + "destructive rebuild.";
            case SKIP_LEVER_MAY_HAVE_MINTED:
                return key + " — NOT re-dialling the advance to era " + newEra + " with a fresh "
                        + "contextId: debug.rcs.mls_advance_fresh_ctxid=" + mode + " already "
                        + "permits minting for this shape, so the create may ALREADY have sent a "
                        + "fresh contextId and the known cause does not explain this refusal. Check "
                        + "the create log for 'contextId MINTED FRESH'; if it is ABSENT the lever "
                        + "did not fire (it also needs a prior provider record under this MLS group "
                        + "id) and this skip was too cautious.";
            case SKIP_NOT_A_SILENT_NON_ADVANCE:
            default:
                return key + " — NOT re-dialling the advance to era " + newEra + ": this is not the "
                        + "accepted-and-not-applied shape. A create the server REFUSED carries an "
                        + "error on the channel refusals use, and an era we could not READ is not a "
                        + "measurement.";
        }
    }

    /** The line for a re-dial that was granted — the case that saves the rebuild. */
    public static String grantedLine(final String key, final int newEra, final String minted) {
        return "FRESH-contextId RE-DIAL GRANTED for " + key + " — the server now reports era "
                + newEra + " under the minted contextId " + minted + ". The destructive rebuild is NOT "
                + "run: the provider record and the engine group both survive, members re-join by "
                + "Welcome on the same group id (the cost an era advance already imposes), and the "
                + "era budget is charged once rather than twice. This is the remedy applied at "
                + "the point of measurement instead of by way of forgetting everything.";
    }

    /** The line for a re-dial that did not move the era either. */
    public static String notGrantedLine(final String key, final int newEra, final String minted,
            final long serverEra) {
        return "FRESH-contextId RE-DIAL did NOT take for " + key + " — a freshly minted contextId ("
                + minted + ") was accepted and the server still reports era " + serverEra
                + ", not " + newEra + ". That is a REAL result and it is worth more than the RPC "
                + "cost: the rule predicts a grant here, so this conversation's refusal has a "
                + "cause the contextId does not explain. Falling through to the path that would "
                + "have run anyway.";
    }

    /**
     * A Google Messages-shaped {@code contextId}: 18 random bytes, base64url, unpadded — 24 characters.
     *
     * <p>The SHAPE is the load-bearing part and it came from the wire, not from a spec: Google Messages
     * sends e.g. {@code "MxwWNgNorfRjuNR6HpufapAQ"}, where our app's own conversation key
     * ({@code "p:+15715550106"}) is not even in the base64url alphabet. The provider's resolver
     * accepts a supplied id only when it passes {@link #isReferenceShapedContextId}, so minting the
     * wrong shape here would be silently ignored and the re-dial would repeat the stored id — a
     * probe whose output is identical whether or not it fired.
     *
     * @param rng caller-supplied so a test can pin the shape without pinning the bytes
     */
    public static String mintContextId(final SecureRandom rng) {
        final byte[] raw = new byte[CONTEXT_ID_BYTES];
        rng.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Is this the shape the provider's resolver will honour — 24 base64url characters, no padding?
     *
     * <p>Kept in step with {@code MlsCreateConversationClient.isReferenceShapedContextId} by
     * construction rather than by comment: the re-dial supplies a contextId ACROSS the AIDL
     * boundary and the far side re-tests it, so a divergence between the two would show up as the
     * far side quietly falling back to the stored id.
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
