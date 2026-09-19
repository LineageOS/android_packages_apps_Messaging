/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Why a rebuild has, or lacks, a server pack to rebuild around. Nothing branches on it: every arm
 * degrades to the recorded membership. It only lets the log say which situation applied and
 * whether a {@link MlsFetchLedger} look was charged. See docs/mls/budgets.md.
 */
public enum MlsServerPackOutcome {

    /**
     * The look was charged and the server answered with a pack (whose slot 0 may still be empty).
     */
    FETCHED(Boolean.TRUE),

    /** A 1:1, which re-establishes through {@code ensureReady} and never fetches a pack. */
    NOT_A_GROUP(Boolean.FALSE),

    /**
     * A group we hold no state for. The pack look is anchored on our era and epoch authenticator,
     * so it cannot be formed; returns before the ledger is consulted.
     */
    NO_LOCAL_STATE_TO_ASK_WITH(Boolean.FALSE),

    /**
     * The caller resolved a roster from local answers on purpose ({@code resetPeerHealth}); kept
     * apart from {@link #NO_LOCAL_STATE_TO_ASK_WITH} because local state is intact here.
     */
    NOT_ASKED_BY_DESIGN(Boolean.FALSE),

    /**
     * Our own {@link MlsFetchLedger} refused the look: consulted, nothing charged, nothing asked.
     */
    REFUSED_BY_LEDGER(Boolean.FALSE),

    /** The look was charged and the server answered with nothing. */
    SERVER_HAD_NOTHING(Boolean.TRUE),

    /** The look threw, on either side of {@link MlsFetchLedger#mayFetch}. */
    LOOK_FAILED(null);

    private final Boolean mSpentALook;

    MlsServerPackOutcome(final Boolean spentALook) {
        mSpentALook = spentALook;
    }

    /**
     * Whether this arm incremented the conversation's {@link MlsFetchLedger} record. Declared per
     * constant so a new arm must state its answer. For this caller a charge and a server read
     * coincide, since the rebuild caller is not ledger-exempt.
     *
     * @return {@code TRUE} charged, {@code FALSE} not charged, {@code null} unknown
     *     ({@link #LOOK_FAILED}); do not unbox
     */
    public Boolean spentALook() {
        return mSpentALook;
    }

    /** One clause for a log saying what happened. */
    public String line() {
        switch (this) {
            case FETCHED:
                return "the server ANSWERED and its pack is what we are rebuilding around";
            case NOT_A_GROUP:
                return "this is a 1:1, which never fetches a pack — it re-establishes through "
                        + "ensureReady's reclaim arm. The fetch ledger was not consulted and nothing "
                        + "was charged";
            case NO_LOCAL_STATE_TO_ASK_WITH:
                return "we hold NO local group for this conversation and the pack look is anchored "
                        + "on our era and epoch authenticator, so there was nothing to ask WITH. The "
                        + "fetch ledger was not consulted and nothing was charged; this is the "
                        + "REJOIN shape";
            case NOT_ASKED_BY_DESIGN:
                return "the server was deliberately not asked — this caller resolves the roster from "
                        + "local answers on purpose";
            case REFUSED_BY_LEDGER:
                return "our OWN fetch ledger refused the look, so nothing was asked of the server. "
                        + "This is not a server that would not answer, and a refusal is not a spend "
                        + "— the ledger was consulted and charged nothing";
            case SERVER_HAD_NOTHING:
                return "the look was taken and CHARGED, and the server answered with nothing";
            case LOOK_FAILED:
            default:
                return "the pack look THREW. Whether the fetch ledger was consulted, and whether a "
                        + "look was charged, is NOT KNOWN — the throw can come from either side of "
                        + "that boundary";
        }
    }

    /**
     * The warning a group rebuild prints when it has no server GroupInfo to carry, naming which arm
     * produced the absence.
     *
     * @param eraInitial the era a carry-less create is born at (the transport's
     * {@code ERA_INITIAL})
     */
    public String noCarryLine(final long eraInitial) {
        return "with NO server GroupInfo to carry — " + line() + ". The re-establish will be born "
                + "at era " + eraInitial + ", which the server may already hold. This is the shape "
                + "that gets accepted and discarded; it is not refused here because a genuinely new "
                + "group is the same shape and is correct.";
    }
}
