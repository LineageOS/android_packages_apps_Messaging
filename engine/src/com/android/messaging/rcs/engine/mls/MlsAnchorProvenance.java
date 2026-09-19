/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Whether a fetched GroupInfo describes the server's state or hands back the anchor we asked from.
 * The fetch is anchored at our own epoch, so a GroupInfo at that epoch may be ours and its signer
 * us; only one ahead of us supports fork attribution.
 */
public enum MlsAnchorProvenance {

    UNREADABLE,

    /** Ahead of us: the server's own state, signed by a real committer. */
    AHEAD_SERVER_STATE,

    /** At our anchored epoch; may be our own anchor, whose signer is us. */
    SAME_EPOCH_MAY_BE_OUR_ANCHOR,

    /** Behind us, which nothing in the fetch explains. */
    BEHIND_UNEXPLAINED;

    /** @param readable false when the GroupInfo was absent or would not parse */
    public static MlsAnchorProvenance of(final boolean readable, final long giEpoch,
            final long ourEpoch) {
        if (!readable) return UNREADABLE;
        if (giEpoch > ourEpoch) return AHEAD_SERVER_STATE;
        if (giEpoch == ourEpoch) return SAME_EPOCH_MAY_BE_OUR_ANCHOR;
        return BEHIND_UNEXPLAINED;
    }

    public boolean signerIsAttributable() {
        return this == AHEAD_SERVER_STATE;
    }

    public String line() {
        switch (this) {
            case UNREADABLE:
                return "unreadable";
            case AHEAD_SERVER_STATE:
                return "AHEAD of us, so this bundle describes the server's own state and the signer "
                        + "below is a real committer";
            case SAME_EPOCH_MAY_BE_OUR_ANCHOR:
                return "*** THE SAME EPOCH WE ANCHORED AT *** — this may be our own anchor handed "
                        + "back rather than the server's current state, in which case the signer "
                        + "below is evidence about NOBODY. Do not attribute a fork from it";
            case BEHIND_UNEXPLAINED:
            default:
                return "BEHIND us, which nothing here explains — treat the signer as unread";
        }
    }
}
