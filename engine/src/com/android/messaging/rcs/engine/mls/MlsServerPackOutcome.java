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
 * Why a rebuild has no server pack to rebuild around.
 *
 * <h2>The bug this exists to fix is a shared return value, and it BIT before it was reviewed</h2>
 *
 * <p>{@code MlsProviderTransport.serverPackForRebuild} answered {@code byte[]}, and FOUR distinct
 * situations shared its {@code null}: this is a 1:1 and no pack is ever fetched; we hold no local
 * group, so there is nothing to ask the server WITH; our own {@link MlsFetchLedger} refused the
 * look; and the look was taken and came back with nothing (or threw). That is the same shape as
 * {@link MlsRebuildOutcome}'s nine-situations-one-{@code false} and as
 * {@link MlsRebuildOutcome#DEFERRED_BY_OUR_OWN_LEDGER}'s refusal wearing a guard's value, one level
 * down, inside the method that feeds the rebuild.
 *
 * <h2>The caller's BEHAVIOUR was not the defect. The DIAGNOSTIC was, and it is measurable</h2>
 *
 * <p>The method's own comment argued that {@code null} meaning <i>"the roster falls back to the
 * recorded membership and the re-establish carries nothing, which is a degraded rebuild rather than
 * a refused one"</i> stays true for a refused look. <b>That argument holds and nothing here
 * disputes it</b> — no arm below changes what the rebuild does. What it did not account for is what
 * a READER can tell, and the cost of that came due on 2026-09-09: a device fixture was derived twice
 * from "{@code serverPackForRebuild} charges a third {@code REBUILD} look per attempt", the GROUP
 * variant of the {@code REBUILD} refusal fixture was called era-budget-independent and cheap on the
 * strength of it, and it is <b>false on the fixture actually used</b>. {@code --ez forgetgroup}
 * produces {@link #NO_LOCAL_STATE_TO_ASK_WITH}, which returns BEFORE the ledger is consulted at all.
 * Had a lab group existed, that plan would have run and measured nothing.
 *
 * <p>So {@link #spentALook()} is not decoration. It is the fact whose misreading produced a wrong
 * device plan, stated where a reader and a test can both reach it instead of being re-derived from a
 * method body each time somebody needs it.
 *
 * <h2>{@link #spentALook()} is three-valued on purpose</h2>
 *
 * <p>{@code null} is UNKNOWN, and unknown is not false — the same rule
 * {@code MlsProviderTransport.selfInServerRoster} states for its own {@code Boolean}. Only
 * {@link #LOOK_FAILED} answers {@code null}, because a throw can come from either side of the
 * ledger boundary: {@code spendOneLook} reads the record, asks {@link MlsFetchLedger#mayFetch}, and
 * only then makes the dial, so an exception says nothing about how far it got. Answering
 * {@code false} there would be this class's own defect, committed inside this class.
 *
 * <p><b>"Spent a look" means the ledger record was INCREMENTED</b>, which is
 * {@code MlsFetchLedgerRecord.charged}. A refusal consults the ledger and spends nothing — that is
 * why {@link #REFUSED_BY_LEDGER} answers {@code false} rather than {@code true}, and why the wrong
 * fixture above would still have been wrong if the arm had been the refusing one.
 *
 * <p>For this caller — and only for this caller — "spent a look" and "asked the server" are the same
 * question: {@code MlsFetchLedger.Caller.REBUILD} is not in the exempt set, so the one path that
 * dials without charging ({@code Verdict.SPEND_EXEMPT}) is unreachable from here. Stated rather than
 * assumed, because an exempt caller added later would break the equivalence silently.
 *
 * <h2>What this is NOT</h2>
 *
 * <p>It is not a verdict and nothing branches on it. The fall-back it describes is correct and
 * deliberate: a rebuild with no server pack degrades to the recorded membership
 * rather than refusing, and every arm below still does exactly that. This type only lets the caller
 * SAY which of the situations it is in, so a rebuild that lands at the wrong era can be attributed
 * to the thing that caused it.
 */
public enum MlsServerPackOutcome {

    /**
     * The look was taken, charged, and the server answered with a pack.
     *
     * <p>Still reachable at the no-carry warning: a pack can arrive whose slot 0 holds no GroupInfo,
     * and the difference between that and every arm below is the whole point — it is the server's
     * answer rather than a question we did not ask.
     */
    FETCHED(Boolean.TRUE),

    /**
     * A 1:1. No pack is ever fetched for one and none is missing.
     *
     * <p>The 1:1 rebuild re-establishes through {@code ensureReady}'s reclaim arm, which
     * reads the server's era after the server names its own group id in a refusal — it does not need
     * a pack and never asks for one. <b>The ledger is not consulted and nothing is charged.</b>
     */
    NOT_A_GROUP(Boolean.FALSE),

    /**
     * A group we hold no state for. There is nothing to ask the server WITH.
     *
     * <p>The pack look is anchored: it sends our era and our epoch authenticator, so a conversation
     * whose local group is gone cannot form the question. <b>The ledger is not consulted and nothing
     * is charged</b> — the method returns above the charge.
     *
     * <p><b>This is the REJOIN shape, and it is exactly what {@code --ez forgetgroup} produces.</b>
     * That is the sentence the wrong device plan needed and did not have: the pack site is not
     * blocked by our lacking a lab group, it is blocked by our lacking a group IN A STATE WHERE THAT
     * SITE CHARGES AT ALL, which needs {@code --es mlshold BEHIND}/{@code ERA_GAP} rather than the
     * cheap fixture. Recorded the same way on {@link MlsFetchLedgerCoverage}'s {@code REBUILD} row.
     */
    NO_LOCAL_STATE_TO_ASK_WITH(Boolean.FALSE),

    /**
     * The caller resolved a roster deliberately WITHOUT asking the server.
     *
     * <p>Not an absence to explain: {@code resetPeerHealth} runs from a notification tap on a
     * conversation that is by definition unhealthy and takes the local answers on purpose. It is a
     * constant rather than a reuse of {@link #NO_LOCAL_STATE_TO_ASK_WITH} because that one is a
     * conversation whose state is gone, and a reader deciding whether the state is intact needs
     * "we chose not to ask" apart from "there was nothing to ask with".
     */
    NOT_ASKED_BY_DESIGN(Boolean.FALSE),

    /**
     * Our own {@link MlsFetchLedger} refused the look. Nothing was asked of the server.
     *
     * <p>The ledger WAS consulted and it declined, so nothing was charged — a refusal is not a
     * spend, and {@link #spentALook()} answers {@code false} for that reason rather than because no
     * ledger was involved. The two are apart in the log and now in the return value.
     *
     * <p>This is the arm the type exists for: it shared {@code null} with
     * {@link #NO_LOCAL_STATE_TO_ASK_WITH}, whose entire content is that the ledger never saw the
     * attempt.
     */
    REFUSED_BY_LEDGER(Boolean.FALSE),

    /**
     * The look was taken and CHARGED, and the server answered with nothing.
     *
     * <p>The one arm where the server is the subject of the sentence. {@code spendOneLook} has
     * already logged this against the count every caller had spent on the conversation, which is
     * what classifies an unreadable look as a throttle rather than an emptiness.
     */
    SERVER_HAD_NOTHING(Boolean.TRUE),

    /**
     * The look threw.
     *
     * <p>{@link #spentALook()} is {@code null} here and that is the honest answer, not a hedge: the
     * throw can come from either side of {@link MlsFetchLedger#mayFetch}. See the class javadoc.
     */
    LOOK_FAILED(null);

    private final Boolean mSpentALook;

    MlsServerPackOutcome(final Boolean spentALook) {
        mSpentALook = spentALook;
    }

    /**
     * Whether this arm INCREMENTED the conversation's {@link MlsFetchLedger} record.
     *
     * <p>Declared per constant rather than derived, for the reason {@link MlsRebuildOutcome} states
     * about its own pair: a constant added later with the answer out of step must be a test failure
     * rather than a number somebody quotes into a fixture.
     *
     * @return {@code TRUE} charged, {@code FALSE} not charged, {@code null} UNKNOWN — and unknown is
     *     not false. Unbox it and a {@link #LOOK_FAILED} throws.
     */
    public Boolean spentALook() {
        return mSpentALook;
    }

    /** One clause for a log, saying what was actually measured. */
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
     * The warning a GROUP rebuild prints when it has no server GroupInfo to carry.
     *
     * <p>The sentence is unchanged from the one the transport carried inline; what is new is that it
     * now NAMES which of the situations produced the absence. A rebuild that lands at the wrong era
     * is attributed by this line, and every one of them used to read the same.
     *
     * @param eraInitial the era a carry-less create is born at, {@code MlsProviderTransport}'s
     *     {@code ERA_INITIAL} — passed rather than duplicated here because it is a WIRE constant and
     *     correctly transport-local (it is on the DoD-1 wire/ABI exempt list, not the policy one)
     */
    public String noCarryLine(final long eraInitial) {
        return "with NO server GroupInfo to carry — " + line() + ". The re-establish will be born "
                + "at era " + eraInitial + ", which the server may already hold. This is the shape "
                + "that gets accepted and discarded; it is not refused here because a genuinely new "
                + "group is the same shape and is correct.";
    }
}
