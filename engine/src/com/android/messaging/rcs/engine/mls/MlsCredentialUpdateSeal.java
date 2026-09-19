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

/**
 * <b>When a refused RCC.16 §9.5.3 certificate update may be offered again.</b>
 *
 * <h2>The rule, in one sentence</h2>
 *
 * <p>A refusal of the CERTIFICATE seals the update until a new certificate exists; a refusal of our
 * POSITION seals it only until we move.
 *
 * <h2>Why it needed a rule rather than a condition at the call site</h2>
 *
 * <p>{@code maybeUpdateGroupCredential} spends one attempt per certificate, so that a Commit the
 * server has already judged is not re-offered unchanged on every maintenance pass. That is right for
 * a verdict about the bytes and wrong for every other verdict, and until this class the predicate
 * separating them was {@link MlsTransportDisposition#isConnectivityLoss(int)} — which covers only
 * the two cases where nothing reached the server at all.
 *
 * <p>{@code VERDICT_ERA_GAP} and {@code VERDICT_GROUP_ID_CHANGED} fell on the wrong side of it, and
 * they are the common ones. {@link MlsTransportDisposition#ofVerdict(int)} already classifies both
 * as {@link MlsTransportDisposition#OK} rather than as failures, in its own words because "they mean
 * the call reached the server and the server disagreed about WHERE WE ARE, which is recovery's
 * business"; the provider's classifier says the same thing from the other side of the Binder, that
 * {@code VERDICT_REJECTED} is the ONLY verdict asserting the server evaluated this commit and said
 * no. Both statements were already written down. Neither was being acted on here.
 *
 * <h2>What it cost, device-measured</h2>
 *
 * <p>On one device, two groups held a copy of our credential 32 days older
 * than the one the device held. Both records sat at {@code moment=(era=1 epoch=1)} against a server
 * at epoch 3 and epoch 24, so the §9.5.3 Commit came back {@code grpcStatus=3 "Commit was from era
 * Some(Era: 1) epoch Some(1), expected era Era: 1 epoch 3" mlsError=&#123;1=2&#125;} — the position
 * refused before the certificate was looked at — and the attempt was spent on it.
 *
 * <p>Where that matters is NOT the wedged group. Its repair is an era advance, which re-creates
 * around freshly claimed leaves and makes this arm moot. It is the ORDINARY COMMIT RACE on a healthy
 * group: a peer's Commit lands first, ours is refused for the epoch, we process theirs, our position
 * becomes correct — and at that moment the update is both possible and still needed, while the arm
 * stays sealed until the next mint. A group that ages on its original mint's clock however often the
 * device re-mints is exactly the wedge this class exists to prevent.
 *
 * <h2>Not a clock</h2>
 *
 * <p>§8.7's "build no timer". Both halves are pure functions of state that the group itself carries:
 * a moment and a verdict. Nothing here expires, so nothing here can expire early on a device whose
 * wall clock moves.
 */
public final class MlsCredentialUpdateSeal {

    private MlsCredentialUpdateSeal() {}

    /**
     * Does a seal taken at {@code sealedAt} still suppress a re-offer, now that we are at
     * {@code positionNow}?
     *
     * <p>Says nothing about WHETHER anything is sealed — that is the certificate marker's job, and
     * this must be read together with it. {@code null} is both "nothing was attempted" and "what was
     * attempted was refused on its merits", and only the marker separates those.
     *
     * @param sealedAt the moment the refusal was measured at, or {@code null} for a seal that no
     *     move lifts
     * @param positionNow where the conversation is now, or {@code null} when the engine could not
     *     say
     */
    public static boolean stillStands(final MlsAppMessage.Moment sealedAt,
            final MlsAppMessage.Moment positionNow) {
        // A seal about the BYTES does not lift on a move, whatever the move was.
        if (sealedAt == null) return true;
        // AND AN UNREADABLE POSITION KEEPS THE SEAL, which is the conservative direction and worth
        // saying why: the alternative is re-offering a Commit on the strength of a GUESS that we
        // have moved. One suppressed repair costs the group a maintenance cycle; one Commit sent per
        // pass because the position cannot be read costs an epoch and a self-heal spend per pass.
        // The un-evaluable case is also already loud one level up, where selfLeafStatus is read.
        if (positionNow == null) return true;
        return sealedAt.equals(positionNow);
    }

    /**
     * <b>Was the server asked at all?</b>
     *
     * <p>The question that has to be answered BEFORE {@link #isAboutTheBytes(int)}, because a verdict
     * field holds two quite different things: what the server said, and the fact that nothing has
     * been recorded there. Every value the transport can report is non-negative —
     * {@code VERDICT_OK}(0) through {@code VERDICT_REJECTED}(6), and the same seven constants on the
     * AIDL side — and a null result is recorded as {@code VERDICT_TRANSPORT_FAILED}(5), not as a
     * negative. So a negative verdict is not a server answer; it is the ABSENCE of one, and
     * {@code commitAndSend} writes exactly that sentinel ({@code -1}) at the top of every attempt.
     *
     * <h2>Why it must release rather than seal, and why the old answer was worse than nothing</h2>
     *
     * <p>Without this, a {@code -1} fell to {@code ofVerdict}'s {@code RETRYABLE} default, which is
     * not {@code PERMANENT}, so {@link #isAboutTheBytes(int)} answered false and the attempt was kept
     * under a POSITION-scoped seal. That reads as "the server refused where we stood" about a request
     * the server never received — and on a quiet group a position does not move, so the repair sat
     * sealed until the next mint or the next process restart, which is the wedge
     * reached by a third door.
     *
     * <p>The route that makes it reachable is not exotic. {@code maybeUpdateGroupCredential} takes
     * the marker and calls {@code rekey}; {@code commitAndSend} returns {@code -1} without ever
     * calling {@code applyMlsControl} when the engine produces no commit artefact
     * ({@code art == null}) — and an engine that returns null there gets a STRICTER answer than one
     * that throws, since the arm's {@code catch} already releases outright for exactly this reason
     * ("a throw carries no verdict about the bytes"). Two routes to one fact, one of them covered.
     * The release is the same act for the same reason.
     *
     * <p><b>What it costs if it is wrong:</b> one engine call on the next maintenance pass. No
     * Commit is sent, no epoch is spent, no self-heal budget is touched — the artefact was never
     * built. Against that, a seal too wide abandons a repair the device is fully able to make.
     */
    public static boolean serverWasNeverAsked(final int verdict) {
        return verdict < 0;
    }

    /**
     * Did the server EVALUATE the commit we sent, as opposed to refusing where we stood or never
     * seeing it?
     *
     * <p>The one question that decides whether the seal is about the certificate. Expressed over
     * {@link MlsTransportDisposition#ofVerdict(int)} rather than by listing verdicts, so a verdict
     * added later is classified once, in the place that already owns that classification.
     *
     * <p>An UNRECOGNISED verdict answers {@code false} here, via {@code ofVerdict}'s own
     * {@link MlsTransportDisposition#RETRYABLE} default. That is the fail-safe direction: a seal
     * that is too narrow costs one extra Commit after a move, a seal that is too wide silently
     * abandons the repair.
     */
    public static boolean isAboutTheBytes(final int verdict) {
        return MlsTransportDisposition.ofVerdict(verdict) == MlsTransportDisposition.PERMANENT;
    }

    /**
     * When the server refused on a CREDENTIAL'S VALIDITY, was the credential it named OURS?
     *
     * <p>The second half of "did the server judge THIS certificate", and the only one
     * {@link #isAboutTheBytes(int)} cannot answer: a verdict says the server spoke, not what it
     * spoke about. A {@code "Time-related validation error"} is a statement about ONE member's
     * credential, and A.4.3.2 §3 exempts only the committer's own leaf from the expiry check — so
     * the same refusal means opposite things depending on whose number is in it.
     *
     * <h2>Why this arrives as a verdict about our bytes, and why that is wrong</h2>
     *
     * <p>Wire {@code mlsError} code 4 is {@code EXPIRED_CREDENTIAL} (read off Google Messages and
     * mapped BY NAME — four orderings of that vocabulary exist and the
     * proto and trailer enums diverge from 12 up, but 4 is below the divergence). Google Messages'
     * own interceptor puts it in the RECOVERABLE arm. Our provider maps 1/2 and 11/12 and
     * lets 4 fall through to {@code VERDICT_REJECTED}, which is {@code PERMANENT} — the widest seal
     * there is, liftable only by a new mint.
     *
     * <p>We have measured that exact refusal on a §9.5.3 Self-Update and it named the PEER:
     * <i>client "326B6A76-…" with MSISDN "+12025550101" … not valid at time</i>, while the
     * certificate we were installing had 73 days left. Nothing about our bytes was judged. The
     * roster pre-check in {@code maybeUpdateGroupCredential} already declines to spend the marker
     * for precisely this reason — "the roster can clear without a new mint" — and it covers only
     * what we can see LOCALLY; this refusal is what arrives when we could not.
     *
     * <h2>The three answers</h2>
     *
     * <ul>
     *   <li><b>Not a credential-validity refusal at all</b> ({@code parse} returns null, the
     *       ordinary case) — {@code true}. Nothing here contradicts the verdict, so it stands.</li>
     *   <li><b>It named US</b> — {@code true}. Re-offering the same certificate gets the same
     *       answer; only a new mint changes it, which is the wide seal exactly.</li>
     *   <li><b>It named a PEER, or named nobody we could read</b> — {@code false}. Not evidence
     *       about our certificate. {@code namesUs} answers false for an unparsed MSISDN by design
     *       ("the safe direction"), and the same direction is safe here: a seal that is too narrow
     *       costs one extra Commit, one that is too wide abandons the repair.</li>
     * </ul>
     */
    public static boolean judgedOurOwnCredential(final String detail, final String ourE164) {
        final MlsTimeValidationRefusal ref = MlsTimeValidationRefusal.parse(detail);
        return ref == null || ref.namesUs(ourE164);
    }
}
