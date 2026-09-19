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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * RCC.16 <b>§8.3.1.2</b> / <b>§8.3.1.3</b> — what to DO about a continuity token, as a pure decision.
 *
 * <p>Separate from {@link MlsContinuityToken} (which only computes) because these rules decide
 * whether a conversation gets <b>downgraded out of encryption</b>, and that is the single most
 * consequential thing this subsystem can do to a user. A rule with that outcome belongs somewhere a
 * host test can enumerate its inputs.
 *
 * <h2>§8.3.1.2 — validation on new-era creation</h2>
 *
 * The spec gives three comparisons and then a compound condition, and the compound is the part
 * worth reading twice:
 *
 * <blockquote>
 * If <b>token mismatch AND the fetched GroupInfo has members not in the local group</b>, OR
 * <b>the RCS chat has participants not in the fetched MLS group</b>, then mint a new token; the
 * client MAY move to unencrypted with {@code END_MLS_REASON_CONTINUITY_TOKEN_MISMATCH}.
 * </blockquote>
 *
 * <p>So a token mismatch <b>on its own is not enough</b> to act. It has to coincide with the fetched
 * group containing members we do not know about. That conjunction is the spec being careful: a bare
 * mismatch is what an ordinary re-mint or a lost token looks like, whereas a mismatch plus unknown
 * members is what a group being SUBSTITUTED looks like. Implementing the mismatch alone would
 * downgrade conversations on the benign case, which is exactly the failure the standing "do not mint
 * one" position was protecting against.
 *
 * <h2>§8.3.1.3 — no local token</h2>
 *
 * If the RCS participants are a subset of the fetched MLS participants, <b>request</b> the token via
 * §10.5.2. Otherwise mint a new one via §8.3.1.1. Note this is the opposite default from §8.3.1.2:
 * absence is recoverable, substitution is not.
 */
public final class MlsContinuityPolicy {

    private MlsContinuityPolicy() { }

    /** What the caller should do after evaluating §8.3.1.2 / §8.3.1.3. */
    public enum Action {
        /** Everything agrees. Carry the existing token into the new era. */
        CARRY_TOKEN,
        /** §8.3.1.3: we hold no token and the membership is consistent — ask for it (§10.5.2). */
        REQUEST_TOKEN,
        /** §8.3.1.1: mint a fresh token and carry on encrypted. */
        MINT_TOKEN,
        /**
         * §8.3.1.2's compound condition held: mint a new token, and the client MAY downgrade with
         * {@code END_MLS_REASON_CONTINUITY_TOKEN_MISMATCH}. MAY, not shall — see
         * {@link #downgradeIsPermitted}.
         */
        MINT_TOKEN_AND_MAY_DOWNGRADE
    }

    /**
     * §8.3.1.2 / §8.3.1.3, evaluated together because they share every input.
     *
     * @param localToken       the token we hold for this conversation, or {@code null}/empty if none
     * @param fetchedToken     the token in the fetched GroupInfo, or {@code null}/empty if absent
     * @param rcsParticipants  the participants the RCS conversation names
     * @param fetchedMembers   the members the fetched MLS GroupInfo carries
     * @param localMembers     the members of the MLS group we hold
     */
    public static Action evaluate(final byte[] localToken, final byte[] fetchedToken,
            final Collection<String> rcsParticipants, final Collection<String> fetchedMembers,
            final Collection<String> localMembers) {

        final Set<String> rcs = norm(rcsParticipants);
        final Set<String> fetched = norm(fetchedMembers);
        final Set<String> local = norm(localMembers);

        // §8.3.1.2 comparison (1) / §8.3.1.3's condition, and it is checked FIRST because it is the
        // one arm of the compound condition that fires on its own: "the RCS chat has participants
        // not in the fetched MLS group" is an OR term, not an AND term.
        final boolean rcsHasStrangers = !fetched.containsAll(rcs);

        if (isEmpty(localToken)) {
            // §8.3.1.3. Absence is recoverable if — and only if — the membership is consistent.
            return rcsHasStrangers ? Action.MINT_TOKEN : Action.REQUEST_TOKEN;
        }

        // §8.3.1.2 comparison (2): local token vs the token in the fetched GroupInfo. A fetched
        // GroupInfo with NO token cannot match one we hold, and must not be read as agreement.
        final boolean tokenMismatch =
                isEmpty(fetchedToken) || !MlsContinuityToken.commitmentMatches(localToken, fetchedToken);

        // §8.3.1.2 comparison (3): does the fetched group contain members we do not hold?
        final boolean fetchedHasStrangers = !local.containsAll(fetched);

        // THE COMPOUND CONDITION, verbatim: (mismatch AND fetched-has-strangers) OR rcs-has-strangers.
        // The parenthesisation is the whole rule — a bare mismatch does NOT act.
        if ((tokenMismatch && fetchedHasStrangers) || rcsHasStrangers) {
            return Action.MINT_TOKEN_AND_MAY_DOWNGRADE;
        }
        // A mismatch without unknown members is a benign re-mint or a lost token on the far side.
        // Take the fetched group's token forward rather than fighting over it.
        return tokenMismatch ? Action.MINT_TOKEN : Action.CARRY_TOKEN;
    }

    /**
     * Whether the §8.3.1.2 downgrade may be taken.
     *
     * <p>The spec says MAY. We say <b>no, unless the transport has been observed doing continuity at
     * all</b> — because a downgrade on this signal is indistinguishable, from the user's side, from
     * the encryption simply failing. If no peer ever mints a token then every fetched GroupInfo
     * mismatches, and a client that acted on MAY would downgrade every group it has.
     *
     * <p>{@code serverDoesContinuity} is a MEASUREMENT, not a configuration: it is true once a server
     * GroupInfo has been seen carrying 0xF011, which v4.0 requires in <em>every</em> GroupInfo. Until
     * then this returns false and the caller mints and carries on encrypted.
     */
    public static boolean downgradeIsPermitted(final Action action, final boolean serverDoesContinuity) {
        return action == Action.MINT_TOKEN_AND_MAY_DOWNGRADE && serverDoesContinuity;
    }

    /** The §7.11.2.2 downgrade reason that goes with each outcome, or {@code -1} for none. */
    public static int downgradeReason(final byte[] localToken) {
        // 6 = CONTINUITY_TOKEN_NOT_RECEIVED, 5 = CONTINUITY_TOKEN_MISMATCH. The distinction is
        // whether we ever had one: "not received" names the §8.3.1.3 path, "mismatch" the §8.3.1.2.
        return isEmpty(localToken) ? 6 : 5;
    }

    private static boolean isEmpty(final byte[] b) { return b == null || b.length == 0; }

    private static Set<String> norm(final Collection<String> in) {
        final Set<String> out = new LinkedHashSet<>();
        if (in == null) return out;
        for (final String s : in) {
            if (s == null) continue;
            final String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
