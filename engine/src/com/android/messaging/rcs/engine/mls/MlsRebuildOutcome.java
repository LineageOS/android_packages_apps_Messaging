/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Why an automatic rebuild did or did not repair a conversation, one value per distinct cause so a
 * log line or alert never claims a cause it did not observe. {@link #needsAPerson()} is true only
 * for deliberate stops that the stalled-conversation notification's Try again can lift. The two
 * flags are declared per constant, not derived, so a new constant must choose both. See
 * docs/mls/health-and-recovery.md.
 */
public enum MlsRebuildOutcome {

    /** It ran, and the server agrees we are in its state. The only success. */
    REPAIRED(/*repaired=*/ true, /*needsAPerson=*/ false),

    /**
     * It ran and did not converge ({@code ensureReady} failed, or the anchor still differs).
     * Re-driving is right; the self-heal ladder escalates if it persists.
     */
    NOT_CONVERGED(false, false),

    /**
     * A peer-protecting guard refused: the joining allowlist, the era budget, the peer-health
     * streak, or the state-change kill switch.
     */
    REFUSED_BY_GUARD(false, /*needsAPerson=*/ true),

    /** Our own rate bound ({@code MlsRebuildLimiter}, three per six hours) refused. */
    RATE_LIMITED(false, true),

    /** The 60-second episode guard treated this as the fault just tried; clears by itself. */
    SUPPRESSED_AS_ONE_EPISODE(false, false),

    /**
     * Not attempted: no session, no conversation key, an unreadable server roster, or a provider
     * that declined to drop its record. Nothing a person can reset.
     */
    COULD_NOT_ATTEMPT(false, false),

    /**
     * The rebuild ran but could not be verified: our own {@code MlsFetchLedger} refused the
     * confirming look. Not {@link #NOT_CONVERGED}, so no stall alert is raised for an outcome that
     * was never checked; not repaired, because the repair is unverified.
     */
    RAN_BUT_UNVERIFIED(false, false),

    /**
     * Not started: our own {@code MlsFetchLedger} refused the look that decides whether the rebuild
     * is charged to the era budget, and proceeding would treat it as an uncharged first create. No
     * guard ran; the exit is {@link MlsFetchLedger#WINDOW_MS} passing, which Try again cannot
     * shorten.
     */
    DEFERRED_BY_OUR_OWN_LEDGER(false, /*needsAPerson=*/ false),

    /**
     * Not started: the server holds this group and we have no GroupInfo to carry, so the rebuild
     * would create a second group at {@code ERA_INITIAL} beside it
     * ({@link MlsReestablishPolicy#forksAtEraInitial}). Nothing was destroyed.
     */
    WOULD_FORK_AT_ERA_INITIAL(false, /*needsAPerson=*/ false),

    /**
     * Not started: the create would go out under a {@code contextId} the server already holds,
     * which it does not apply at any era ({@link MlsReestablishPolicy#reCreateWouldNotTake}).
     * Refused before anything is dropped, claimed or charged; the exit is a current member
     * re-admitting us.
     */
    RECREATE_WOULD_NOT_TAKE(false, /*needsAPerson=*/ false);

    private final boolean mRepaired;
    private final boolean mNeedsAPerson;

    MlsRebuildOutcome(final boolean repaired, final boolean needsAPerson) {
        mRepaired = repaired;
        mNeedsAPerson = needsAPerson;
    }

    /** Whether the conversation is now in the server's state. */
    public boolean repaired() {
        return mRepaired;
    }

    /**
     * Whether we stopped on purpose and the remaining exits are time or a person, i.e. whether this
     * outcome must reach the stalled-conversation notification.
     */
    public boolean needsAPerson() {
        return mNeedsAPerson;
    }

    /** One line for a log or an alert, naming only what was observed. */
    public String line() {
        switch (this) {
            case REPAIRED:
                return "rebuilt and the server agrees we are in its state";
            case NOT_CONVERGED:
                return "the rebuild RAN and did not converge — nothing is refusing, so this is worth "
                        + "re-driving from a later trigger";
            case REFUSED_BY_GUARD:
                return "a peer-protecting guard REFUSED the rebuild (the joining allowlist, the era "
                        + "budget, the peer-health streak, or the state-change kill switch). This is "
                        + "a deliberate stop, not a failure: re-driving cannot lift it, and its "
                        + "exits are the budget refilling or a person choosing Try again";
            case RATE_LIMITED:
                return "the rebuild rate bound REFUSED this attempt (3 per 6h per conversation). "
                        + "The allowance refills, so the conversation is throttled rather than "
                        + "abandoned, but not within any drive";
            case SUPPRESSED_AS_ONE_EPISODE:
                return "a rebuild was attempted moments ago and nothing has changed since — this is "
                        + "one fault, not several, and the suppression clears by itself";
            case RAN_BUT_UNVERIFIED:
                return "the rebuild RAN and could not be VERIFIED — our own fetch ledger refused the "
                        + "confirming look, so nothing was asked of the server. This is NOT evidence "
                        + "that it failed, and it must not raise a stall alert: the exit is our own "
                        + "window passing, which no Try again shortens";
            case DEFERRED_BY_OUR_OWN_LEDGER:
                return "the rebuild was NOT started — our own fetch ledger refused the look that "
                        + "decides whether it must be charged to the era budget, and an unasked "
                        + "question would be classified as a first create and charged nothing. No "
                        + "peer-protecting guard refused this and nothing was asked of the server. "
                        + "The exit is our own window passing, which no Try again shortens";
            case WOULD_FORK_AT_ERA_INITIAL:
                return "the rebuild was NOT started — the server holds this group and we had no "
                        + "GroupInfo to carry, so re-establishing would have been born at the "
                        + "INITIAL era on top of the server's group: a second group beside it, not "
                        + "a repair of it. No peer-protecting guard refused this, and nothing was "
                        + "destroyed. The exit is a later trigger reading the server's pack, or "
                        + "another member re-admitting us";
            case RECREATE_WOULD_NOT_TAKE:
                return "the rebuild was NOT started — the server holds this group and the create "
                        + "would go out under the contextId it already holds, which is not applied "
                        + "at any era (8/8). No peer-protecting guard refused this, "
                        + "nothing was destroyed and nothing was charged. The exit is another "
                        + "member re-admitting us, which this refusal asks for";
            case COULD_NOT_ATTEMPT:
            default:
                return "the rebuild could not be attempted at all (no session, no conversation key, "
                        + "an unreadable server roster, or a provider that would not drop its "
                        + "record) — nothing a person could reset is what stopped it";
        }
    }
}
