/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What to do about a continuity token (RCC.16 §8.3.1.2, §8.3.1.3), which decides whether a
 * conversation may be downgraded out of encryption. A bare token mismatch is benign (a re-mint or a
 * lost token). See docs/mls/downgrade.md.
 */
public final class MlsContinuityPolicy {

    private MlsContinuityPolicy() { }

    public enum Action {
        /** Everything agrees; carry the existing token into the new era. */
        CARRY_TOKEN,
        /** No token held and the membership is consistent: request it (RCC.16 §10.5.2). */
        REQUEST_TOKEN,
        /** Mint a fresh token (RCC.16 §8.3.1.1) and carry on encrypted. */
        MINT_TOKEN,
        /** Mint, and the client may downgrade (see {@link #downgradeIsPermitted}). */
        MINT_TOKEN_AND_MAY_DOWNGRADE
    }

    /** Both clauses together, since they share every input; tokens may be null or empty. */
    public static Action evaluate(final byte[] localToken, final byte[] fetchedToken,
            final Collection<String> rcsParticipants, final Collection<String> fetchedMembers,
            final Collection<String> localMembers) {

        final Set<String> rcs = norm(rcsParticipants);
        final Set<String> fetched = norm(fetchedMembers);
        final Set<String> local = norm(localMembers);

        // The OR term that fires on its own, and also RCC.16 §8.3.1.3's condition.
        final boolean rcsHasStrangers = !fetched.containsAll(rcs);

        if (isEmpty(localToken)) {
            // Absence is recoverable only if the membership is consistent.
            return rcsHasStrangers ? Action.MINT_TOKEN : Action.REQUEST_TOKEN;
        }

        // A fetched GroupInfo with no token is not agreement.
        final boolean tokenMismatch =
                isEmpty(fetchedToken)
                || !MlsContinuityToken.commitmentMatches(localToken, fetchedToken);

        final boolean fetchedHasStrangers = !local.containsAll(fetched);

        if ((tokenMismatch && fetchedHasStrangers) || rcsHasStrangers) {
            return Action.MINT_TOKEN_AND_MAY_DOWNGRADE;
        }
        return tokenMismatch ? Action.MINT_TOKEN : Action.CARRY_TOKEN;
    }

    /**
     * The spec's "may" is taken only once a server GroupInfo has carried {@code 0xF011}: if no peer
     * mints tokens, every fetched GroupInfo mismatches.
     */
    public static boolean downgradeIsPermitted(final Action action,
            final boolean serverDoesContinuity) {
        return action == Action.MINT_TOKEN_AND_MAY_DOWNGRADE && serverDoesContinuity;
    }

    /**
     * The RCC.16 §7.11.2.2 reason: 6 (token not received) with no local token, else 5 (token
     * mismatch).
     */
    public static int downgradeReason(final byte[] localToken) {
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
