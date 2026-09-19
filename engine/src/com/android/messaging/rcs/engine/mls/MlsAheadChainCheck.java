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

import java.util.Arrays;

/**
 * Is the server's anchor a point on OUR chain, when our epoch is ABOVE the server's?
 * The mirror of the BELOW-arm check.
 *
 * <h2>The gap this closes, and it is NOT a diagnostic</h2>
 *
 * <p>{@code MlsProviderTransport.healthAgainstServer} returned {@code notAsked(Health.AHEAD)} on the
 * arm where our epoch is above the server's, so "we are further along the SAME chain" and "we are on
 * a DIFFERENT chain that happens to have gone further" were reported identically. That was recorded
 * as a P3 with no consequence attached. It has one, and it was measured before this was written:
 *
 * <ul>
 *   <li>{@code runMaintenanceOnce} aborts the pass on a fork — but the guard is spelled
 *       {@code if (identity.health == Health.DIVERGED) return maintenanceFoundAFork(...)}, and
 *       {@code DIVERGED} is only ever produced on the {@code cmp == 0} arm, because that is the only
 *       arm that ran an identity test. <b>A fork sitting ABOVE the server's epoch therefore walks
 *       straight past the fork abort.</b></li>
 *   <li>{@code quarantineIfAheadOfServer}, which runs first, compares the ERA ONLY
 *       ({@code engineEra <= serverEra} ⇒ "not ahead"), so a same-era epoch-ahead fork is satisfied
 *       by construction and never quarantined.</li>
 *   <li>The pass then continues into the add arm, counts the SERVER's roster members that our
 *       membership lacks, and on a warranted verdict issues an {@code eraAdvance} and writes
 *       {@code recordMembership(key, g, serverRoster)} — an era-budget charge and a membership
 *       history, on a group we never showed was the server's. That is the
 *       "a fork acquires a plausible-looking history" case, reached through a door that check did not
 *       close.</li>
 * </ul>
 *
 * <p>The baseline write on the {@code ours == null} branch is NOT part of that: that check already
 * gated it on {@code identity.serverConfirmedOurs()}, a positive, and a {@code notAsked} arm cannot
 * satisfy it. The unguarded consequence is the add arm and the era advance.
 *
 * <h2>Why it is answerable now and was not before 2026-09-13</h2>
 *
 * <p>The BELOW arm was left alone for a reason that still holds: one epoch has one
 * authenticator, so comparing our CURRENT authenticator against the server's can only ever answer
 * DIFFERS below the server's epoch, and the server serves no earlier anchor. The ABOVE arm is the
 * opposite shape — the epoch in question is one WE have already been at — and since the
 * authenticator map gained an era key, our own authenticators are retained keyed by
 * {@code (era, epoch)} rather than by epoch alone. So the question becomes a lookup:
 *
 * <pre>  ours_at(server.era, server.epoch) == server.epoch_authenticator ?</pre>
 *
 * <h2>Three states that are NOT an answer, and are kept apart from one</h2>
 *
 * <p>This class exists as a separate pure-JDK decision precisely because collapsing those into
 * "not the same chain" would re-create the defect one level down. {@link Verdict#SERVER_ERA_UNKNOWN},
 * {@link Verdict#NO_RETAINED_ANCHOR} and {@link Verdict#LOOK_REFUSED} are facts about US;
 * {@link Verdict#SERVER_ANCHOR_ABSENT} is a fact about the server; only
 * {@link Verdict#DIFFERENT_CHAIN} is evidence of a fork.
 *
 * <p>{@link Verdict#SERVER_ERA_UNKNOWN} is the one a reader would not predict, and it is a real
 * reachable state rather than defensive padding. The era gate above this arm reads
 * {@code if (server[0] >= 0 && localEra != server[0])}, so when the server's era is UNREADABLE the
 * gate is SKIPPED and the epoch comparison runs with the eras never compared. Keying the retained
 * map on the LOCAL era in that state would assert the very thing the gate failed to establish.
 */
public final class MlsAheadChainCheck {

    /**
     * What the chain test concluded — or the specific reason it concluded nothing.
     *
     * <p>Only {@link #DIFFERENT_CHAIN} may demote the verdict. Everything else leaves
     * {@code Health.AHEAD} standing, which is what it was before this test existed; the difference
     * is that the log can now say WHICH of the five it was.
     */
    public enum Verdict {
        /** The test can be run; pay for the server look. Never returned by {@link #decide}. */
        TESTABLE,
        /** The server's era was unreadable, so the era gate above this arm was skipped. */
        SERVER_ERA_UNKNOWN,
        /** We do not hold our own authenticator for that {@code (era, epoch)}. */
        NO_RETAINED_ANCHOR,
        /** Our own fetch ledger refused the look. Nothing was asked, so nothing is concluded. */
        LOOK_REFUSED,
        /** We asked and could not read the server's anchor. A fact about the server, not a fork. */
        SERVER_ANCHOR_ABSENT,
        /** The server's anchor IS a point on our chain: we are genuinely further along it. */
        SAME_CHAIN,
        /** It is not. We are not ahead of anything — we are FORKED. */
        DIFFERENT_CHAIN,
    }

    private MlsAheadChainCheck() {}

    /**
     * Can the test be run at all — asked BEFORE paying for the server look.
     *
     * <p>Split from {@link #decide} so the three free refusals cost no fetch. A ledger ration spent
     * to discover we had nothing to compare against is the kind of spend the fetch budget exists for.
     *
     * @param serverEra      the server's era, or negative when it could not be read
     * @param retainedEra    {@code MlsConversationRecord.epochAuthEra} — the ONE era the retained
     *                       map belongs to, or {@code MlsConversationRecord.ERA_UNKNOWN}
     * @param oursAtServerEpoch our retained authenticator for the server's epoch, or null
     * @return {@link Verdict#TESTABLE}, or the reason it is not
     */
    public static Verdict blockedBefore(final long serverEra, final int retainedEra,
            final byte[] oursAtServerEpoch) {
        if (serverEra < 0L) {
            return Verdict.SERVER_ERA_UNKNOWN;
        }
        // ERA_UNKNOWN is the migration window: the map KEEPS its entries and only the first
        // write stamps an era, so a non-current entry there may belong to an older era. The
        // CURRENT-epoch lookup is correct throughout, which is what makes that lazy migration safe —
        // but this arm is by definition asking about an epoch that is NOT our current one.
        if (retainedEra == MlsConversationRecord.ERA_UNKNOWN) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        // The retained map holds exactly one era. Above the era gate this is the same era; below the
        // SERVER_ERA_UNKNOWN hole it may not be, and then we hold nothing that answers the question.
        if (retainedEra != serverEra) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        if (oursAtServerEpoch == null || oursAtServerEpoch.length == 0) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        return Verdict.TESTABLE;
    }

    /**
     * The verdict once the server look has been paid for.
     *
     * @param oursAtServerEpoch the same value {@link #blockedBefore} was given and approved
     * @param lookRefused       our own fetch ledger refused the look
     * @param serverAnchor      the server's current 32-byte epoch authenticator, or null/empty
     */
    public static Verdict decide(final byte[] oursAtServerEpoch, final boolean lookRefused,
            final byte[] serverAnchor) {
        if (lookRefused) {
            return Verdict.LOOK_REFUSED;
        }
        if (serverAnchor == null || serverAnchor.length == 0) {
            return Verdict.SERVER_ANCHOR_ABSENT;
        }
        // Defensive rather than reachable from the intended call order — blockedBefore has already
        // refused an absent retained anchor — and it fails to NO_RETAINED_ANCHOR rather than to
        // DIFFERENT_CHAIN, because "we have nothing to compare" must never be spelled as "we are
        // forked". That direction is the whole subject of this class.
        if (oursAtServerEpoch == null || oursAtServerEpoch.length == 0) {
            return Verdict.NO_RETAINED_ANCHOR;
        }
        return Arrays.equals(oursAtServerEpoch, serverAnchor)
                ? Verdict.SAME_CHAIN : Verdict.DIFFERENT_CHAIN;
    }

    /** True only for the one verdict that is evidence of a fork and may demote {@code AHEAD}. */
    public static boolean isFork(final Verdict v) {
        return v == Verdict.DIFFERENT_CHAIN;
    }
}
