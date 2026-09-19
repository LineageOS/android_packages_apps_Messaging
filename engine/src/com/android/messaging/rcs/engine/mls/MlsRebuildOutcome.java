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
 * Why an automatic rebuild did not repair a conversation.
 *
 * <h2>The bug this exists to fix is a shared return value</h2>
 *
 * <p>{@code rebuildConversation} answered a {@code boolean}. Nine different situations collapsed into
 * {@code false}: no session, no roster, a peer-protecting guard refusing, our own rate bound
 * refusing, the 60-second episode guard de-duplicating a burst, the provider declining to drop its
 * record, and a rebuild that genuinely ran and did not converge. {@code reconcileAction}'s AHEAD,
 * DIVERGED, ERA_GAP and REJOIN arms then returned {@code FAIL_RETRY} for all of them with the reason
 * <em>"the rebuild did not converge or was rate-limited"</em> — a disjunction the code had not
 * measured and could not have measured, printed as though it had.
 *
 * <p>That is the house rule this project earned the hard way twice in one day: <b>a log line must not
 * assert something it does not measure, and a reader that refuses a case must not share a return
 * value with the case where nothing was there.</b>
 *
 * <h2>And it is what left G4's "surface it" half missing</h2>
 *
 * <p>The peer-protecting guards fail closed on purpose — that is the point of them, and nothing here
 * loosens one. But a stop with no route to a person is not a circuit breaker, it is a dead
 * conversation with a tidy log. Those arms re-drive forever on a guard refusal and tell nobody,
 * while the guard's only other exit (the peer successfully processing something of ours) is
 * unreachable for precisely the conversation that cannot reach the peer.
 *
 * <p>{@link #needsAPerson()} is the predicate that closes it: <b>we stopped on purpose, and the
 * remaining exits are time or a person</b>. It is deliberately NARROWER than "did not repair":
 *
 * <ul>
 *   <li><b>{@link #REFUSED_BY_GUARD}</b> and <b>{@link #RATE_LIMITED}</b> answer yes. Both are
 *       deliberate stops that outlast any drive, and the stalled-conversation notification's
 *       <b>Try again</b> resets every one of the four bounds behind them — so the alert offers a
 *       button that actually does something.</li>
 *   <li><b>{@link #SUPPRESSED_AS_ONE_EPISODE}</b> answers no. It clears in sixty seconds by
 *       construction; interrupting a person for it is how an alert gets trained into noise.</li>
 *   <li><b>{@link #COULD_NOT_ATTEMPT}</b> answers no, and this is the one worth arguing. It covers
 *       no session, an unreadable server roster, and a provider that would not drop its record —
 *       real refusals, but none of them is a bound a person can lift. Try again resets four budgets
 *       and would change nothing about any of the three, so raising the alert here would hand
 *       someone a button that does nothing, which is the specific failure this project keeps
 *       naming.</li>
 *   <li><b>{@link #DEFERRED_BY_OUR_OWN_LEDGER}</b> answers no, and it is the newest of them
 *       (2026-09-09). It answered YES for as long as it shared
 *       {@link #REFUSED_BY_GUARD}'s value, and the button it offered could not reach the bound that
 *       stopped us — Try again resets six bounds and the fetch ledger is not one of them.</li>
 *   <li><b>{@link #NOT_CONVERGED}</b> answers no. The rebuild RAN; escalating on the first
 *       non-convergence would pre-empt the self-heal ladder, whose exhausted arm already raises the
 *       alert with an attempt count behind it.</li>
 * </ul>
 *
 * <p>The two flags are declared independently rather than derived, for the reason
 * {@link MlsReestablishPolicy.Verdict} states: a constant added later with the pair out of step is
 * then a test failure rather than a silent stop nobody is told about.
 */
public enum MlsRebuildOutcome {

    /** It ran, and the server agrees we are in its state. The only success. */
    REPAIRED(/*repaired=*/ true, /*needsAPerson=*/ false),

    /**
     * It ran and did not converge — {@code ensureReady} failed, or the anchor still differs.
     *
     * <p>Not a stop: nothing is refusing, so re-driving from a later trigger is exactly right and
     * the self-heal ladder is what escalates if it keeps happening.
     */
    NOT_CONVERGED(false, false),

    /**
     * A peer-protecting guard said no — {@code MlsPeerGuard}'s joining allowlist, the era budget
     * (G2), the peer-health streak (G4), or the state-change kill switch (G6).
     *
     * <p>This is the case the guardrails asked to be surfaced, and that was found not to be.
     */
    REFUSED_BY_GUARD(false, /*needsAPerson=*/ true),

    /**
     * Our own rate bound said no — {@code MlsRebuildLimiter}, three per six hours per conversation.
     *
     * <p>Surfaced for the same reason as a guard refusal even though it is our cost rather than the
     * peer's: it outlasts any drive, its allowance refills on a clock the drive will not wait out,
     * and Try again clears it outright.
     */
    RATE_LIMITED(false, true),

    /**
     * The 60-second episode guard treated this as the same fault we just tried.
     *
     * <p>Deliberately silent. It clears by itself, and it exists precisely so that a burst is one
     * fault rather than several — telling a person about it would report a working de-duplication as
     * a stall.
     */
    SUPPRESSED_AS_ONE_EPISODE(false, false),

    /**
     * We never got as far as trying: no session, no conversation key, an unreadable server roster,
     * or a provider that declined to drop its record.
     *
     * <p>Not surfaced, because no bound a person can lift is what is stopping us. See the class
     * javadoc.
     */
    COULD_NOT_ATTEMPT(false, false),

    /**
     * The rebuild RAN — both halves dropped, the conversation re-established — and we could not
     * VERIFY it, because our own {@code MlsFetchLedger} refused the confirming look.
     *
     * <p><b>Not {@link #NOT_CONVERGED}, and the difference reaches a person.</b> That value means the
     * rebuild ran and was measured not to have worked; this one means it was not measured. They were
     * the same value until somebody pointed out where the boolean lands:
     * {@code offerTheStallChoiceOffThread} raises the stalled-conversation notification on
     * {@code !repaired()}, so a look we refused ourselves would put a stall alert on a conversation
     * that may be perfectly repaired — the same conflation of "we could not read" with "it
     * did not work", one layer up.
     *
     * <p><b>Not {@link #COULD_NOT_ATTEMPT} either</b>, whose javadoc says "we never got as far as
     * trying". We got all the way; only the confirmation is missing, and a reader deciding whether
     * local state still exists needs those apart.
     *
     * <p>{@code needsAPerson=false}: the exit is our own window passing, which no Try again shortens.
     * {@code repaired=false}: we will not claim a repair we did not verify — this method's own rule
     * is that a tool reporting success is not evidence.
     */
    RAN_BUT_UNVERIFIED(false, false),

    /**
     * We never started, because our OWN {@code MlsFetchLedger} refused the look that decides whether
     * the rebuild must be charged to the era budget.
     *
     * <p><b>This was {@link #REFUSED_BY_GUARD} for a while, and that is the defect this class
     * was written to prevent, committed inside this class.</b> {@code rebuildConversation} asks the
     * server whether it still holds the conversation before it does anything; the answer is what
     * {@code MlsReestablishPolicy} turns into "charge the era budget or do not".
     * When the fetch ledger refuses that look the rebuild is correctly refused — proceeding would
     * classify an unasked question as a FIRST CREATE and re-open the uncharged-advance door. But it
     * returned the value that means <i>a peer-protecting guard said no</i>, and three things
     * followed from that one substitution:
     *
     * <ul>
     *   <li>{@link #line()} asserted that "the joining allowlist, the era budget, the peer-health
     *       streak, or the state-change kill switch" had refused. <b>None of them ran.</b> That is
     *       a refusal speaking for a bound it never checked — the same defect one layer
     *       up, and it is the disjunction-nobody-measured that this class's own opening paragraph
     *       is written against.</li>
     *   <li>{@code needsAPerson()} was true, so {@code surfaceIfNobodyElseWill} raised the
     *       stalled-conversation notification carrying that text.</li>
     *   <li>The alert's <b>Try again</b> resets six bounds — the self-heal budget, the rebuild rate
     *       bound and its episode suppressor, the era budget, the peer-health streak, the
     *       re-establish cooldown and the §11.2.2 external-commit allowance — <b>and not the fetch
     *       ledger</b>, which has no reset at all. So the alert offered a button that could not
     *       change the thing that stopped us: the exact failure this class's javadoc gives as the
     *       reason {@link #COULD_NOT_ATTEMPT} is not surfaced.</li>
     * </ul>
     *
     * <p>The log line at the call site already said <i>"Deferred, not abandoned"</i>. The value
     * disagreed with the log, and the value is what {@code surfaceIfNobodyElseWill} reads.
     *
     * <p>{@code needsAPerson=false} for {@link #RAN_BUT_UNVERIFIED}'s reason exactly: the exit is our
     * own {@link MlsFetchLedger#WINDOW_MS} window passing, which no Try again shortens.
     * {@code repaired=false} because nothing was attempted. <b>Not {@link #COULD_NOT_ATTEMPT}</b>,
     * whose {@link #line()} enumerates "no session, no conversation key, an unreadable server
     * roster, or a provider that would not drop its record" — folding a fifth cause into a
     * four-way disjunction is the same defect one level down, and a reader deciding whether to
     * re-drive needs "a bound of ours that expires in seconds" apart from "nothing here will ever
     * work".
     */
    DEFERRED_BY_OUR_OWN_LEDGER(false, /*needsAPerson=*/ false),

    /**
     * We never started, because the rebuild we were about to run would have BUILT A FORK rather than
     * repaired anything.
     *
     * <p>The condition is {@link MlsReestablishPolicy#forksAtEraInitial}: a GROUP, the server
     * measurably holds it, and we have no GroupInfo to carry into the re-establish. With nothing
     * local (the forget is the rebuild's first act) and nothing carried, the engine's {@code
     * plan_group} takes its ARM 1 and the new group is born at {@code ERA_INITIAL} — on top of a
     * group the server already has. At server era 1 the transport's post-create check compares era
     * NUMBERS, they match, and the fork is adopted; above era 1 the create is rolled back and both
     * halves of our state were destroyed for nothing. There is no era at which the carry-less arm
     * repairs a group.
     *
     * <p><b>Not {@link #COULD_NOT_ATTEMPT}</b>, whose {@link #line()} enumerates four causes and
     * whose javadoc says "no bound a person can lift" — true here too, but folding a fifth cause
     * into a four-way disjunction is the defect this enum exists to prevent, and a reader needs
     * "we stopped because running would have made it worse" apart from "we could not get going".
     * <b>Not {@link #REFUSED_BY_GUARD}</b>: no peer-protecting guard ran, and that substitution is
     * the regression {@link #DEFERRED_BY_OUR_OWN_LEDGER} was split out to undo.
     *
     * <p>{@code needsAPerson=false}. Three of the four ways to have no pack are transient
     * ({@code MlsServerPackOutcome}: our own ledger refusing, the server answering with nothing, the
     * look throwing) and a later trigger re-asks; the fourth — no local state to ask with — needs
     * another member to re-admit us, which no Try again produces. Try again resets six bounds and
     * none of them is "the pack could not be fetched", so surfacing this would hand a person a
     * button that cannot change what stopped us.
     */
    WOULD_FORK_AT_ERA_INITIAL(false, /*needsAPerson=*/ false),

    /**
     * We never started, because the create this rebuild drives would go out under a
     * {@code contextId} the server ALREADY HOLDS, and such a create is not applied.
     *
     * <p>The condition is {@link MlsReestablishPolicy#reCreateWouldNotTake}, whose javadoc carries
     * the argument and the two device runs. In short: a GROUP re-create's wire {@code contextId} is
     * the RCS group id, which does not change with the era, so the server sees an id it holds and
     * the era does not move (measured 8/8). A GroupInfo carry does not change that — it decides
     * the era the ENGINE derives, not whether the SERVER applies the create — which is why
     * {@link #WOULD_FORK_AT_ERA_INITIAL} does not cover this case: that predicate returns FALSE the
     * moment a carry is in hand, and the with-carry arm was never measured.
     *
     * <p><b>Not {@link #NOT_CONVERGED}</b>, and that is the whole behaviour change. Until this
     * existed, this conversation reached NOT_CONVERGED — after dropping both halves of its local
     * state, claiming a peer KeyPackage, charging the era budget and spending a rebuild allowance,
     * every one of which is unrecoverable and none of which bought anything. NOT_CONVERGED says the
     * rebuild RAN; this says it was refused before it could cost anything.
     *
     * <p><b>Not {@link #WOULD_FORK_AT_ERA_INITIAL}</b>: that one describes a build that HAPPENS and
     * lands beside the server's group, permanently at {@code ERA_INITIAL}. This one describes a
     * build the server does not take at all, at any era, so there is no fork to adopt — the local
     * halves would simply be gone.
     *
     * <p>{@code needsAPerson=false}, for {@link #WOULD_FORK_AT_ERA_INITIAL}'s reason exactly: Try
     * again resets six bounds and not one of them is "the server holds this contextId". The exits
     * are a member who IS current re-admitting us — which the refusal ASKS FOR on the §7.7.2.2
     * channel, so this is a chosen state with a way out rather than the end of a ladder — or an
     * operator setting {@code debug.rcs.mls_ctxid_group_engine_id=0}. Offering a button that
     * changes neither is the failure this enum's javadoc names.
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
     * Whether we stopped on purpose and the remaining exits are time or a person — i.e. whether this
     * outcome must reach the stalled-conversation notification rather than being re-driven silently.
     */
    public boolean needsAPerson() {
        return mNeedsAPerson;
    }

    /**
     * One line for a log or an alert, saying what was actually measured.
     *
     * <p>Replaces "the rebuild did not converge or was rate-limited", which named two possibilities
     * and distinguished neither.
     */
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
