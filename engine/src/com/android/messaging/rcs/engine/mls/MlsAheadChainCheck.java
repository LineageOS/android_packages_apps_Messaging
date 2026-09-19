/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Arrays;

/**
 * Server comparison for group health. Above the server's epoch, our retained authenticator for the
 * server's {@code (era, epoch)} separates "further along the same chain" from a fork; only
 * {@link Verdict#DIFFERENT_CHAIN} is evidence of one. See docs/mls/health-and-recovery.md.
 */
public final class MlsAheadChainCheck {

    /** What the chain test concluded, or why it concluded nothing. */
    public enum Verdict {
        /** Never returned by {@link #decide}. */
        TESTABLE,
        SERVER_ERA_UNKNOWN,
        NO_RETAINED_ANCHOR,
        LOOK_REFUSED,
        SERVER_ANCHOR_ABSENT,
        SAME_CHAIN,
        DIFFERENT_CHAIN,
    }

    private MlsAheadChainCheck() {}

    /**
     * Whether the test can run, asked before paying for the server look.
     *
     * @param serverEra   negative when it could not be read
     * @param retainedEra {@code MlsConversationRecord.epochAuthEra}, the one era the map belongs to
     */
    public static Verdict blockedBefore(final long serverEra, final int retainedEra,
            final byte[] oursAtServerEpoch) {
        if (serverEra < 0L) {
            return Verdict.SERVER_ERA_UNKNOWN;
        }
        // An unstamped map may hold entries from an older era.
        if (retainedEra == MlsConversationRecord.ERA_UNKNOWN) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        if (retainedEra != serverEra) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        if (oursAtServerEpoch == null || oursAtServerEpoch.length == 0) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        return Verdict.TESTABLE;
    }

    /** The verdict once the server look has been paid for. */
    public static Verdict decide(final byte[] oursAtServerEpoch, final boolean lookRefused,
            final byte[] serverAnchor) {
        if (lookRefused) {
            return Verdict.LOOK_REFUSED;
        }
        if (serverAnchor == null || serverAnchor.length == 0) {
            return Verdict.SERVER_ANCHOR_ABSENT;
        }
        // Defensive: "nothing to compare" must never read as a fork.
        if (oursAtServerEpoch == null || oursAtServerEpoch.length == 0) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        return Arrays.equals(oursAtServerEpoch, serverAnchor)
                ? Verdict.SAME_CHAIN : Verdict.DIFFERENT_CHAIN;
    }

    /** Only this verdict may demote {@code AHEAD}. */
    public static boolean isFork(final Verdict v) {
        return v == Verdict.DIFFERENT_CHAIN;
    }
}
