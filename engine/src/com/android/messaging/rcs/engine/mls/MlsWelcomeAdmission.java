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
 * May an inbound Welcome REPLACE the group state we already hold?
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@code rejoinOnEraAdvance} kept a re-join only if the Welcome STRICTLY ADVANCED our era.
 * Google Messages' accept-set (§9.5) is <code>{NEW_GROUP, NEW_ERA_EXISTING_GROUP,
 * REFRESH_MEMBERSHIP_EXISTING_GROUP}</code>, and the third is a <b>same-era</b> re-Welcome: the
 * sender rebuilt the group's membership and is handing us the result. A forward-only rule refuses a
 * legal arm, and the cost is not cosmetic — a peer that refreshes at the same era cannot carry us
 * along, so we keep the old state and every later control drops as past-epoch. That is silent
 * message loss, and it is ordinary production traffic: Google Messages' maintenance refresh generates it.
 *
 * <h2>Why it was left refused, and what changed</h2>
 *
 * <p>This was a deliberate refusal rather than an oversight: at the point
 * of decision, a legal same-era refresh and a REPLAY of the current era's Welcome are <em>the same
 * observation</em> — the join succeeded and the era did not move. Loosening a downgrade guard on an
 * ambiguity is not a trade worth making.
 *
 * <p>What was missing was evidence that separates the two. Of the three candidates, the
 * second — "the epoch/epoch-authenticator differs from the one we hold" — is the right instinct and
 * <b>is not sufficient on its own</b>, which is worth stating because it is the fix a reader will
 * reach for first:
 *
 * <ul>
 *   <li>a refresh that REBUILDS the group restarts its epoch, so a perfectly legal refresh can land
 *       at an epoch <em>lower</em> than the one we hold;</li>
 *   <li>which makes it indistinguishable, by ordering alone, from the downgrade the guard exists to
 *       prevent.</li>
 * </ul>
 *
 * <p>No purely local comparison resolves that. <b>So ask the authority.</b> The question "is the
 * state we just landed in the CURRENT one?" is answerable by the server, and the answer separates
 * every case cleanly:
 *
 * <pre>
 *   era advanced                  → ACCEPT_ERA_ADVANCE   (fast path: no round trip needed)
 *   era same, we ARE current      → ACCEPT_REFRESH       (the legal arm this is about)
 *   era same, we are NOT current  → REJECT_REPLAY        (a replayed or stale Welcome)
 *   era same, cannot ask          → REJECT_UNVERIFIED    (unknown is not consent)
 * </pre>
 *
 * <p>This is <b>strictly stronger</b> than the rule it replaces, not a loosening — which is the bar
 * that was set. The old rule accepted <em>any</em> era advance, including one that lands us in a
 * group the server has never held; this refuses that too, and device evidence says it must: on
 * 2026-08-05 a create at the server's own era produced era 2 == era 2, epoch 1 == epoch 1, and a
 * DIFFERENT epoch authenticator — and the server refused the next send with
 * {@code incorrect-epoch-authenticator}. Equal numbers are not equal state.
 */
public final class MlsWelcomeAdmission {

    /** What the server says about the state we just joined into. Three-valued, deliberately. */
    public enum ServerState {
        /** The state we hold IS the server's current state. */
        MATCHES,
        /** The server holds something else — what we joined is not current. */
        DIFFERS,
        /**
         * We could not ask, or could not understand the answer.
         *
         * <p>Kept distinct from {@link #DIFFERS} because they mean opposite things about the PEER: a
         * mismatch is evidence about the Welcome, an unreachable server is evidence about our
         * network. Collapsing them would report a transient RPC failure as a replay attack, and
         * would put that wording in the log a human reads first.
         */
        UNKNOWN,
        /**
         * <b>We did not ask.</b> Our own {@link MlsFetchLedger} refused the {@code GetMlsGroupInfo}
         * this read costs.
         *
         * <p>Kept distinct from {@link #UNKNOWN} for the same reason {@code UNKNOWN} is kept distinct
         * from {@link #DIFFERS}: they have different remedies and they are evidence about different
         * things. {@code UNKNOWN} says the server was asked and the answer was unusable, which is
         * evidence about the network or about the conversation. This says nothing was asked at all,
         * which is evidence about US — the remedy is to wait for the window rather than to retry, and
         * a reader who cannot tell them apart will chase a network fault that is not there.
         *
         * <p>{@link #decide} treats it as {@link Verdict#REJECT_UNVERIFIED}, which is correct and
         * unchanged: unknown is not consent, and neither is unasked.
         */
        REFUSED_BY_LEDGER
    }

    /** The verdict, and the reason, which is the part worth logging. */
    public enum Verdict {
        /** The Welcome moved us to a later era. Keep the re-join. */
        ACCEPT_ERA_ADVANCE(MlsWelcomeAction.NEW_ERA_EXISTING_GROUP, true),
        /** Same era, and the server confirms this is the current state. Keep the re-join. */
        ACCEPT_REFRESH(MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP, true),
        /** Same era, and the server holds something else. Roll back. */
        REJECT_REPLAY(null, false),
        /** Same era, and we could not verify. Roll back — unknown is not consent. */
        REJECT_UNVERIFIED(null, false),
        /**
         * The join itself did not produce a group for us.
         *
         * <p>NOT a refusal, and it must never be logged as one: this is what an add-members commit
         * looks like from inside the group — the Welcome belongs to the member being added, and the
         * commit riding with it is still ours to apply.
         */
        NOT_ADDRESSED_TO_US(MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP, false),
        /**
         * We DID join from the Welcome, but the resulting era could not be read.
         *
         * <p>Split out of {@link #NOT_ADDRESSED_TO_US} after observing a
         * re-join refused with {@code offered=-1} and made the point that <em>"a guard that cannot
         * read the offer should say so rather than silently treating it as older"</em>. Rolling back
         * is still the right action — we will not keep a join whose era we cannot establish — but
         * "we could not read it" and "it was not for us" are different facts with different
         * follow-ups, and reporting the second when the first happened sends the next reader to look
         * at addressing rather than at parsing.
         */
        REJECT_ERA_UNREADABLE(null, false);

        private final MlsWelcomeAction mAction;
        private final boolean mKeep;

        Verdict(final MlsWelcomeAction action, final boolean keep) {
            mAction = action;
            mKeep = keep;
        }

        /** Whether the joined state should be KEPT (true) or rolled back (false). */
        public boolean keepsJoin() { return mKeep; }

        /** The {@link MlsWelcomeAction} this outcome corresponds to, or null when it names none. */
        public MlsWelcomeAction action() { return mAction; }

        /** Whether a server round trip was needed to reach this verdict. */
        public boolean consultedServer() {
            return this == ACCEPT_REFRESH || this == REJECT_REPLAY || this == REJECT_UNVERIFIED;
        }
    }

    private MlsWelcomeAdmission() {}

    /**
     * Decide, from facts the caller has already gathered.
     *
     * <p>Pure: the server consult is the CALLER's job, and its result arrives as {@code serverState}.
     * That split is what makes this testable at all — and it is also why the fast path is expressed
     * here rather than at the call site: {@link #needsServerConsult} lets the caller skip the round
     * trip for an era advance without duplicating the rule that decides when it is safe to skip.
     *
     * @param joinedGroup    did the Welcome actually admit us to a group?
     * @param oldEra         the era we held before, or negative if unknown
     * @param newEra         the era we hold after joining, or negative if the join produced none
     * @param serverState    what the server says about the joined state; consulted only when the era
     *                       did not advance, and ignored otherwise
     */
    public static Verdict decide(final boolean joinedGroup, final int oldEra, final int newEra,
            final ServerState serverState) {
        if (!joinedGroup) return Verdict.NOT_ADDRESSED_TO_US;
        // JOINED, but the era is unreadable. Distinct from not-addressed-to-us: the Welcome
        // WAS ours and we applied it, we simply cannot tell where it put us. Same rollback, different
        // diagnosis.
        if (newEra < 0) return Verdict.REJECT_ERA_UNREADABLE;
        if (newEra > oldEra) return Verdict.ACCEPT_ERA_ADVANCE;
        // Same era or backwards. Backwards is included here on purpose: a Welcome that moves us to an
        // EARLIER era is exactly a downgrade, and the only thing that could justify keeping it is the
        // server telling us that earlier era is what it currently holds — in which case it is not a
        // downgrade at all, it is us being behind. The authority answers both cases identically, so
        // they do not need separate arms.
        if (serverState == null) return Verdict.REJECT_UNVERIFIED;
        switch (serverState) {
            case MATCHES: return Verdict.ACCEPT_REFRESH;
            case DIFFERS: return Verdict.REJECT_REPLAY;
            case UNKNOWN:
            // NAMED, not left to the default. The verdict is the same — unasked is no more consent
            // than unknown is — but a reader auditing which states can keep a join must be able to
            // see that this one was considered rather than inherited.
            case REFUSED_BY_LEDGER:
            default: return Verdict.REJECT_UNVERIFIED;
        }
    }

    /**
     * Does this outcome require asking the server?
     *
     * <p>Only when the era did not advance. An era advance is self-evidently forward, so the common
     * case costs no round trip — which matters because this runs on the inbound control path, where
     * an added RPC per Welcome would serialise behind every group's traffic.
     */
    public static boolean needsServerConsult(final boolean joinedGroup, final int oldEra,
            final int newEra) {
        return joinedGroup && newEra >= 0 && newEra <= oldEra;
    }

    /** A line naming what happened and why, for the one log a human reads when a group goes quiet. */
    public static String line(final Verdict v, final int oldEra, final int newEra) {
        switch (v) {
            case ACCEPT_ERA_ADVANCE:
                return "era " + oldEra + " → " + newEra + ", accepted as "
                        + MlsWelcomeAction.NEW_ERA_EXISTING_GROUP;
            case ACCEPT_REFRESH:
                return "same era (" + newEra + ") and the SERVER confirms this is the current state "
                        + "— accepted as " + MlsWelcomeAction.REFRESH_MEMBERSHIP_EXISTING_GROUP
                        + ", the arm a forward-only rule used to refuse";
            case REJECT_ERA_UNREADABLE:
                return "we JOINED from the Welcome but its era could not be READ (ours=" + oldEra
                        + " offered=" + newEra + ") — rolling back, because an era we cannot "
                        + "establish is one we cannot order against ours. This is NOT 'the Welcome "
                        + "was not for us': it was, and it applied. Look at the era parse, not at "
                        + "addressing";
            case REJECT_REPLAY:
                return "same era (ours=" + oldEra + " offered=" + newEra + ") and the server holds a "
                        + "DIFFERENT state — this is a replayed or stale Welcome, not a refresh; "
                        + "rolled back";
            case REJECT_UNVERIFIED:
                return "same era (ours=" + oldEra + " offered=" + newEra + ") and the server could "
                        + "NOT be asked which state is current — rolled back, because unknown is not "
                        + "consent for replacing live group state. This is a transient failure, not "
                        + "an accusation about the sender; it will be retried on the next Welcome.";
            case NOT_ADDRESSED_TO_US:
            default:
                return MlsWelcomeAction.NEW_MEMBERSHIP_EXISTING_GROUP.addMembersLine();
        }
    }
}
