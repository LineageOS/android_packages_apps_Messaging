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
 * Whose state does a fetched GroupInfo describe — the server's, or the anchor we asked from?
 *
 * <h2>Why this decision exists at all</h2>
 *
 * <p>A GroupInfo's {@code signer} names the member that signed it, and on the server's stored anchor
 * that is the member whose Commit produced the epoch — which is the only direct evidence available
 * about which side of a fork the server descends from. But {@code fetchServerPack} issues its
 * {@code GetMlsGroupInfo} <b>anchored at our own era and epoch authenticator</b>, and the provider's
 * own source notes that the returned bundle's authenticator equals the RESPONSE's own anchor
 * field. A reader who assumes the bundle is always the server's current state can therefore
 * read a signer that is <b>us by construction</b> — we signed our own epoch — and attribute a fork
 * from it. Comparing the GroupInfo's own epoch against the one we hold is what separates the two.
 *
 * <h2>Extracted so the branch that matters can be exercised</h2>
 *
 * <p>{@link #SAME_EPOCH_MAY_BE_OUR_ANCHOR} <b>has never fired on a device</b> — every real reading so
 * far has been {@link #AHEAD_SERVER_STATE}. A branch taken only in tests is exactly where a wrong
 * answer survives, so the decision lives here as pure arithmetic with no {@code Context}, and every
 * outcome is pinned. The transport renders; it does not decide.
 */
public enum MlsAnchorProvenance {

    /** The GroupInfo could not be read. Says nothing about the server and nothing about us. */
    UNREADABLE,

    /**
     * The bundle's epoch is AHEAD of ours, so it describes state we do not hold — the server's own.
     * Its signer is a real committer and fork attribution from it is sound.
     */
    AHEAD_SERVER_STATE,

    /**
     * The bundle sits at <b>the epoch we anchored the request at</b>. It may be our own anchor
     * handed back rather than the server's current state, in which case its signer is us by
     * construction and is <b>evidence about nobody</b>.
     */
    SAME_EPOCH_MAY_BE_OUR_ANCHOR,

    /** The bundle's epoch is BEHIND ours, which nothing in the fetch explains. */
    BEHIND_UNEXPLAINED;

    /**
     * Classify a fetched GroupInfo against the epoch this device holds.
     *
     * @param readable  false when the GroupInfo was absent or would not parse
     * @param giEpoch   the GroupInfo's own {@code GroupContext.epoch}
     * @param ourEpoch  the epoch this device holds for the same group
     */
    public static MlsAnchorProvenance of(final boolean readable, final long giEpoch,
            final long ourEpoch) {
        if (!readable) return UNREADABLE;
        if (giEpoch > ourEpoch) return AHEAD_SERVER_STATE;
        if (giEpoch == ourEpoch) return SAME_EPOCH_MAY_BE_OUR_ANCHOR;
        return BEHIND_UNEXPLAINED;
    }

    /** True only when the bundle's signer may be attributed to a real committer. */
    public boolean signerIsAttributable() {
        return this == AHEAD_SERVER_STATE;
    }

    /** The operator-facing sentence. Kept with the verdict so the two cannot drift apart. */
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
