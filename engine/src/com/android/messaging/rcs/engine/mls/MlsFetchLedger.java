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
 * The LEDGER for one scarce server resource — a {@code GetMlsGroupInfo} RPC — kept per
 * conversation and charged at the primitive.
 *
 * <h2>What this is, and what {@link MlsFetchBudget} already was</h2>
 *
 * <p>{@link MlsFetchBudget} rations LOOKS INSIDE ONE DRIVE. It is correct and it stays: a reconcile
 * pass that keeps re-reading the same two numbers must stop, and stopping it is what that class
 * measured and fixed. What it cannot do is bound the resource, because a drive is one of the ways
 * the resource is spent and not the only one.
 *
 * <p>This is the other bound. It counts the RPCs themselves, on a conversation, across every caller
 * that spends one — including the ones that never enter a drive at all.
 *
 * <h2>The defect it closes, stated as measured</h2>
 *
 * <p>The earlier fix was <i>"BOUND THE LOOP ON FETCHES, NOT ONLY ON PASSES"</i>, and it gated
 * {@code MlsFetchBudget.mayLook} at ONE call site. The resource has more doors than that, and the
 * enumeration below is the finding this class exists for — <b>re-derived from the RPC rather than
 * from a call name.</b>
 *
 * <p>Every one of these provider AIDL methods bottoms out in one
 * {@code MlsControlClient.getGroupInfo} dial, i.e. exactly one {@code GetMlsGroupInfo}:
 *
 * <table>
 *   <caption>The primitives, and how many call sites each has in the transport</caption>
 *   <tr><th>primitive</th><th>call sites</th><th>what it asks</th></tr>
 *   <tr><td>{@code fetchMissedCommits}</td><td>3</td>
 *       <td>anchored at our era + epoch authenticator</td></tr>
 *   <tr><td>{@code fetchServerEpochAuthenticator}</td><td>3</td>
 *       <td>unanchored; reads the server epoch authenticator</td></tr>
 *   <tr><td>{@code getMlsGroupInfoForGroup}</td><td>1</td>
 *       <td>unanchored; the full GroupInfo an external commit needs</td></tr>
 *   <tr><td>{@code getMlsServerEraEpoch}</td><td>10</td>
 *       <td>unanchored; reads the era/epoch pair</td></tr>
 *   <tr><td>{@code getMlsGroupInfo}</td><td>1</td>
 *       <td>the 1:1 GroupInfo by peer; a debug arm, and it reached the provider directly</td></tr>
 * </table>
 *
 * <p><b>An inventory keyed on the call NAME finds only one of the five.</b> Enumerating the doors
 * of {@code fetchMissedCommits} alone is exactly right for that call and still misses the
 * resource, because the resource is the RPC and not the call name — and enumerating from one of
 * its five spellings is itself
 * the hand inventory the invariant exists to replace. The sharp consequence: {@code detectHealth}
 * spends through {@code getMlsServerEraEpoch} and {@code fetchServerEpochAuthenticator}, so
 * {@code driveReconcileInner} — the ONE door charged before this class — never reaches
 * {@code fetchMissedCommits} on its own path at all.
 *
 * <p><b>And the enumeration had to be re-derived a second time.</b> The first pass keyed on the
 * literal {@code pt("<aidlName>")} and found 17 sites; the true count is <b>22</b>.
 * {@code MlsProviderTransport.pt(String)} takes a <i>lock-assertion label</i>, not a method name, so
 * that literal is a naming CONVENTION — four sites reach the transport through a local
 * {@code final ProviderTransport pt = pt("…")} and a fifth is
 * {@code pt("quarantineIfAheadOfServer").getMlsServerEraEpoch(…)}, where the label names the CALLER.
 * An independent catalogue found the same thing and both derivations then agreed. The
 * guard test is keyed on the INVOCATION now, for the reason the miss demonstrates: a check keyed on
 * a convention fails silently the first time someone does not follow it.
 *
 * <h2>Why a per-caller ration and not one number</h2>
 *
 * <p>A single global ration was rejected for a specific reason: {@code probeAnchor} is the DIVERGED
 * health test, and starving it makes health UNREADABLE — which has already been shown to escalate
 * the ladder to a heavier remedy than the fault needed. A diagnostic read and a recovery look must
 * therefore stop sharing a budget. So each caller carries its own allowance, and the readers whose
 * job is to make health legible are held OUTSIDE the shared ceiling entirely
 * ({@link Caller#chargesTheSharedCeiling}).
 *
 * <h2>It reads no clock and holds no state</h2>
 *
 * <p>All methods are total, take explicit inputs, and read nothing. The counters and the window live
 * in {@link MlsFetchLedgerRecord}, which the host stores; the clock is supplied. Same division as
 * every other budget here — the arithmetic is host-testable and the effects are not.
 */
public final class MlsFetchLedger {

    private MlsFetchLedger() {}

    /**
     * The provider primitives that each spend exactly one {@code GetMlsGroupInfo}.
     *
     * <p>Enumerated here rather than in the source-scan test so the two cannot drift: the test reads
     * these names out of this enum. A sixth spelling added provider-side and called from the
     * transport must appear here, and the scan fails until it does.
     */
    public enum Primitive {
        /** Anchored at our era + epoch authenticator; the enhanced-self-heal input. */
        FETCH_MISSED_COMMITS("fetchMissedCommits"),
        /** Unanchored; returns the server's own epoch authenticator. */
        FETCH_SERVER_EPOCH_AUTHENTICATOR("fetchServerEpochAuthenticator"),
        /** Unanchored; the full GroupInfo, the only one carrying {@code external_pub}. */
        GET_MLS_GROUP_INFO_FOR_GROUP("getMlsGroupInfoForGroup"),
        /** Unanchored; the era/epoch pair. */
        GET_MLS_SERVER_ERA_EPOCH("getMlsServerEraEpoch"),
        /**
         * The 1:1 GroupInfo by peer. Its only caller is a debug arm ({@code --ez ctrl}), which
         * reached the provider DIRECTLY until this ledger existed — so it was a door to the resource
         * outside the class that owns the resource, and no enumeration confined to
         * {@code MlsProviderTransport} could have seen it.
         */
        GET_MLS_GROUP_INFO("getMlsGroupInfo");

        /** The AIDL method name, as it appears inside {@code pt("…")} at the call site. */
        public final String aidlName;

        Primitive(final String aidlName) {
            this.aidlName = aidlName;
        }
    }

    /**
     * A caller whose ration is exempt from counting: it may always look, and the log says so.
     *
     * <p>Only debug arms hold this. A debug arm that silently spent a recovery look would be lying
     * about what the operator's next recovery has left; one refused for budget would be useless,
     * because the operator asked precisely because something is already wrong.
     */
    public static final int EXEMPT = -1;

    /**
     * <b>The doors.</b> One constant per method that decides to spend, with its own allowance inside
     * one {@link #WINDOW_MS} on one conversation.
     *
     * <p>The funnels — {@code fetchServerPack}, {@code serverStateCheck}, {@code stateMatchesServer},
     * {@code detectHealth}, {@code serverPackForRebuild} — deliberately have NO constant of their
     * own. A funnel does not decide to spend; whoever called it did, and attributing the cost to the
     * funnel would put five different decisions under one ration and hide exactly the competition
     * this class exists to separate. They take a {@code Caller} and pass it down.
     */
    public enum Caller {
        /**
         * {@code reconcileAction} inside {@code driveReconcileInner} — the reconcile drive.
         *
         * <p>Four, because a pass costs one {@code getMlsServerEraEpoch} plus a second
         * {@code fetchServerEpochAuthenticator} on the arm where era AND epoch match (the DIVERGED
         * test), and {@link MlsFetchBudget#RECOVERY_LOOKS} allows two passes. This ration and that
         * one bound different things and are deliberately NOT tied: an earlier fix refused that
         * coupling in as many words, because tying them is how raising one silently raises the
         * other.
         */
        RECONCILE_DRIVE(4, true),
        /**
         * {@code probeAnchor} — the read-only divergence probe.
         *
         * <p><b>Outside the shared ceiling.</b> This is the reader that makes health legible, and
         * starving it is the specific failure a single global ration would cause.
         */
        HEALTH_PROBE(2, false),
        /** {@code selfHealInner} — the recovery ladder's entry rung. */
        SELF_HEAL(3, true),
        /**
         * {@code reporterIsAMember} and {@code onPeerReportedFailure} — inbound §7.7.2.2 report
         * verification, once per report.
         *
         * <p><b>This caller FAILS OPEN and still charges</b>; see
         * {@link #failsOpenOnRefusal}. It is the sharpest of the doors because its rate is driven by
         * a PEER rather than by our own loop, and the peer that would drive it is by construction one
         * that is already failing.
         */
        PEER_REPORT_VERIFY(2, true),
        /** {@code runMaintenanceOnce} — the scheduled membership refresh. */
        MAINTENANCE(2, true),
        /**
         * {@code runMaintenanceOnce}'s IDENTITY question — "is the group we are maintaining the
         * server's group at all?".
         *
         * <p><b>Why it is not {@link #MAINTENANCE}.</b> That ration is 2 and the pass already spends
         * exactly one of it on the server pack, so folding the authenticator look in would take a
         * pass from costing half a ration to costing the whole of it — one maintenance pass per
         * window instead of two, silently, with the symptom showing up as conversations that stop
         * being maintained rather than as anything named. It gets its own constant for
         * that reason and this is it.
         *
         * <p><b>ONE, because the pass asks the question at most once</b> — and only on the arm where
         * it is decisive, era and epoch both equal. One epoch has one authenticator, so anywhere
         * else the comparison answers {@code DIFFERS} by construction and is not a check at all.
         * A second look inside one window would mean a second maintenance pass on the same
         * conversation inside 200 seconds, and the right answer there is to leave the identity
         * question unanswered rather than to spend again: a refusal reports IN_SYNC-on-numbers and
         * SAYS it rested on numbers, which is what the {@code detectHealth} arm already does.
         *
         * <p><b>OUTSIDE THE SHARED CEILING, by D3's own rule.</b> This is a reader whose job is to
         * make health legible, the same class as {@link #HEALTH_PROBE} and {@link #QUARANTINE_CHECK}
         * — and it audits a pass that has just spent the conversation's allowance on its own pack
         * fetch, so charging it to the ceiling would refuse it exactly on the conversations that
         * were most eventful. The cost of it going unread is a SAME-ERA FORK that satisfies the era
         * reconciliation by construction and reads as reconciled.
         *
         * <p><b>The cost, stated:</b> one extra {@code GetMlsGroupInfo} per maintenance pass per
         * conversation, on the era-and-epoch-equal arm only, outside the shared ceiling. The server
         * already returns this exact value in the maintenance pack's own response — the epoch
         * authenticator field
         * of the {@code fetchMissedCommits} reply, which the provider decodes, LOGS ("server anchor
         * auth=… ours=…") and then discards — so the look is not inherent, it is the price of the
         * provider contract not carrying it up.
         */
        MAINTENANCE_IDENTITY(1, false),
        /**
         * {@code eraAdvanceLocked} — the era advance and its create fallback.
         *
         * <p><b>This ration is the INNERMOST of three bounds on the rebuild rung, and it is almost
         * never the one that binds.</b> Ordered outermost first: {@code MlsPeerGuard}'s era budget
         * (2/hour, 5/day), which refuses long before the ledger is anywhere near biting; then the
         * self-heal budget; then this. Each can independently stop a repair, so a reader tracing a
         * recovery that halted should check them in that order.
         *
         * <p><b>RETRACTED, and left here because the wrong version was in this file and is worth
         * warning the next reader off.</b> This paragraph previously said "a refusal at any one
         * STARVES THE NEXT of the state it needs to be exercised at all". That is false for the
         * self-heal → ledger pair, which is the one it was written about. {@code selfHealInner}
         * charges the self-heal budget at {@code :2673} and does not consult this ledger until
         * {@code :2716}, and {@code chargeSelfHealBudget} calls {@code escalateExhaustedSelfHeal}
         * from inside itself on exhaustion — <b>so a ledger refusal cannot prevent either the charge
         * or the escalation.</b> The observation behind the claim was real (a budget stuck at 4
         * across repeated heals) and the cause was a different, upstream guard: the pending-op
         * in-flight check, which returns before the charge.
         *
         * <p>The consequence matters more than the correction: the deep recovery path IS reachable
         * from {@code adb}. Driving {@code --ez selfheal} with this ledger exhausted still charges
         * the budget each time and still escalates at 5/5; the refusal only costs that one heal its
         * fetch. Anything claiming a recovery arm cannot be provoked should be re-derived from the
         * call order rather than inherited from here.
         */
        ERA_ADVANCE(4, true),
        /** {@code eraQuotaBound} — "is this repair bounded by an era quota?" */
        ERA_QUOTA_CHECK(1, true),
        /** {@code resyncViaExternalCommit} — the resync rung. */
        EXTERNAL_COMMIT_RESYNC(3, true),
        /** {@code offerTheStallChoiceOffThread} — the stall-notification path. */
        STALL_CHOICE(1, true),
        /** {@code refreshStallNotification} — re-reading health after a person pressed Try again. */
        STALL_REFRESH(2, false),
        /** {@code rebuildConversation} and {@code serverPackForRebuild} — the rebuild rung. */
        REBUILD(4, true),
        /** {@code reestablishOutbound} — the outbound re-establish. */
        REESTABLISH(2, true),
        /** {@code requestReWelcome} — asking a current member to Welcome us again. */
        RE_WELCOME_REQUEST(2, true),
        /**
         * RETIRED (2026-09-14) — {@code reWelcomeDivergedPeers} is DELETED and
         * nothing charges this arm any more.
         *
         * <p><b>The constant stays because this enum is ordinal-persisted.</b>
         * {@link MlsFetchLedgerRecord} writes {@code who[n] = caller.ordinal()} and reads it back
         * by comparing ordinals, so removing a constant shifts every later one and silently
         * reattributes ledger rows already on disk to the wrong caller. Reclaiming the slot is a
         * migration, not a deletion.
         *
         * <p>What it charged for was a sweep that advanced an era on EVERY conversation after
         * checking only that WE were current — it never tested any peer, because peer state is not
         * queryable. The targeted replacement is {@link #DIVERGED_PEER_ESCALATION}, driven by the
         * peer's own RCC.16 §7.7.2.2 report.
         */
        RE_WELCOME_SWEEP(1, true),
        /** {@code rejoinOnEraAdvance} — the inbound era-advance re-join. */
        REJOIN_ON_ERA_ADVANCE(2, true),
        /** {@code establishGroup} — the create/re-create precondition read. */
        GROUP_ESTABLISH(2, true),
        /** {@code escalateForDivergedPeer} — the escalation ladder for a peer that cannot self-heal. */
        DIVERGED_PEER_ESCALATION(2, true),
        /** {@code onDecryptFailure} — "did we converge?" after a failed decrypt. */
        DECRYPT_FAILURE_CHECK(2, true),
        /**
         * {@code changeGroupSubject} — the pre-commit currency check. Since 2026-09-13 also
         * {@code changeGroupIcon}, through the shared {@code healBeforeMetadataChange}: the two are
         * one operation over two fields of the same request, and a person changes an icon and a subject in
         * the same sitting, so ONE ration covering the pair is the accurate model. Giving the icon
         * its own allowance would double the pre-check traffic a single UI visit can generate.
         */
        SUBJECT_CHANGE(1, true),
        /** {@code endMls} — the teardown's post-state read. */
        END_MLS(2, true),
        /**
         * {@code ensureReady} — the 1:1 create, its era-bump arm and its reclaim arm.
         *
         * <p>Three sites, all reached through a LOCAL {@code ProviderTransport} variable rather than
         * a {@code pt("…")} literal, which is how a scan keyed on that literal missed them (found by
         * an independent catalogue, agreed on re-measurement).
         *
         * <p><b>FOUR, not three, and the fourth is device-derived.</b> It was three — one per site —
         * until a real rebuild was measured on a BEHIND fixture and this caller finished at
         * <b>3 of 3</b>: at its cap,
         * zero headroom, inside a single rebuild. That is the binding constraint in that window —
         * the shared ceiling stood at 5 of 8 on the charge that capped this caller — so raising
         * {@link #SHARED_CEILING} would buy nothing there and only this ration can. (6 of 8 was the
         * NEXT charge, {@code REBUILD}'s; the trace is re-derivable from the run log, so the two
         * numbers must agree with it.)
         *
         * <p>The fourth look is not speculative: {@code ensureReady} has a bump arm AND an
         * a reclaim arm, and its own comment says "the bump can be followed by the
         * reclaim in a single flow". A conversation that takes both, after the create's own read, is
         * a fourth. At three it would be refused — and because the rebuild passes
         * {@code stateAlreadyDestroyed}, that refusal FAILS OPEN and proceeds unmeasured, so the
         * symptom is not a stopped repair but a ledger that stops informing exactly where the
         * measurement showed it working hardest.
         */
        ENSURE_READY(4, true),
        /**
         * {@code quarantineIfAheadOfServer} — the "did that operation leave us ahead of the server?"
         * check, reached from five methods.
         *
         * <p>It has a ration of its own rather than taking its callers', and the reason is NOT the
         * one first written here. That said it "runs AFTER an era advance or a membership change has
         * already spent theirs" — <b>false for a live caller</b>: {@code mlsMembershipChange} reaches
         * it having spent no {@code GetMlsGroupInfo} at all, and a second caller in the list,
         * {@code eraAdvancePreserving}, cannot run at all (its first act always returns
         * {@code BUILD_FAILED}, because {@code commit_era_advance} errors unconditionally). A reason
         * resting on a dead path and a counter-example is the defect {@code instruments-fix} named:
         * a wrong reason with a right verdict never fails a test.
         *
         * <p>The reason that holds for every live caller — {@code mlsMembershipChange},
         * {@code runMaintenanceOnce}, and {@code eraAdvanceLocked} twice — is simpler and does not
         * depend on what ran before: this is an AUDIT read, and its whole value is that it runs when
         * the audited operation was eventful. Charging it to the caller, or to a ceiling other
         * callers have already drawn down, makes it stop running exactly then. The cost of it going
         * unread is a conversation left silently AHEAD of the server, which is the state every
         * inbound message afterwards parks against.
         */
        QUARANTINE_CHECK(2, false),
        /**
         * {@code keepUnacknowledgedCommit} — "did the server take the commit whose response we never
         * got?"
         *
         * <p><b>OUTSIDE THE SHARED CEILING, for the reason {@link #QUARANTINE_CHECK} gives and one
         * more that is specific to it.</b> This read happens on a path that has ALREADY failed once,
         * so the conversation it audits is by construction the one most likely to have drawn down
         * its ceiling on the operation that just failed. Charging it there would refuse exactly the
         * commits whose fate is least knowable.
         *
         * <p><b>A refusal here is not a failure.</b> Unlike most callers, this one has a correct
         * answer with no read at all: HOLD the commit and reconcile later. So the cost of exhausting
         * the ration is a held commit rather than a wrong one — which is the whole asymmetry
         * {@link MlsCommitApplication} documents.
         *
         * <p><b>FOUR, BECAUSE THIS CALLER SPENDS TWO LOOKS PER INVOCATION — and the number is a
         * function of that count, not a preference.</b> It was two, set when the check was a single
         * {@code getMlsServerEraEpoch}. The identity fix added a second charge, the
         * {@code fetchServerEpochAuthenticator} that settles WHOSE chain the server's epoch belongs
         * to, so one full resolution now costs two. Device-measured —
         * {@code "charged getMlsServerEraEpoch … 1 of 2"} then {@code "charged
         * fetchServerEpochAuthenticator … 2 of 2"}, 174 ms apart, one silent commit consuming the
         * entire allowance.
         *
         * <p>So this is NOT a budget increase measured in invocations: it holds the permitted number
         * of invocations CONSTANT across a change in what one invocation costs. Leaving it at two
         * would have halved this door silently, and the symptom would have been a second silent
         * commit inside the window scored {@code UNREADABLE} — "we could not establish" standing in
         * for "we did not ask", which is exactly the collapse {@code LOOK_REFUSED} was split out
         * of {@code UNKNOWN} to end.
         *
         * <p><b>The cost, stated:</b> up to four {@code GetMlsGroupInfo} per conversation per
         * {@link #WINDOW_MS}, outside the shared ceiling. Our own throttle has been measured
         * biting at around ten fetches in that window, so four is inside the measured envelope and
         * is not free. {@code MlsFetchLedgerRationGuardTest} ties this constant to the charge-site
         * COUNT, so a third look cannot be added without the number being re-argued.
         */
        COMMIT_OUTCOME_CHECK(4, false),
        /**
         * {@code dumpGroupExtensions} — the {@code --ez groupexts} debug arm.
         *
         * <p>EXEMPT, and {@link #describeExemption} is what it logs. An operator runs this because
         * something is already wrong; refusing it for budget would withhold the answer at exactly the
         * moment it is worth having, and spending a recovery look without saying so would leave the
         * next recovery short with nothing in the log to explain it.
         */
        DEBUG_DUMP(EXEMPT, false),
        /**
         * {@code RcsDebugSendReceiver}'s direct health reads — {@code --ez health}.
         *
         * <p>EXEMPT for the same reason as {@link #DEBUG_DUMP}, and listed separately so the log
         * says which debug arm spent the look. It is NOT the same as {@link #RECONCILE_DRIVE}: the
         * operator's readout and the drive's own health read are two different decisions that happen
         * to call the same method, and the whole point of naming the caller at the call site is that
         * they are charged apart.
         */
        DEBUG_HEALTH(EXEMPT, false);

        /** Looks this caller may spend on ONE conversation within one {@link #WINDOW_MS}. */
        public final int ration;

        /**
         * Whether this caller's spend also counts against {@link #SHARED_CEILING}.
         *
         * <p>False for the readers whose job is to make health legible. They keep their own small
         * allowance and are never refused because recovery spent the conversation's ceiling — which
         * is the whole reason D3 chose per-caller rations over one number.
         */
        public final boolean chargesTheSharedCeiling;

        Caller(final int ration, final boolean chargesTheSharedCeiling) {
            this.ration = ration;
            this.chargesTheSharedCeiling = chargesTheSharedCeiling;
        }

        /** Whether this caller may always look. */
        public boolean isExempt() {
            return ration == EXEMPT;
        }

        /**
         * Whether a refusal must leave this caller proceeding as though it had looked and seen
         * nothing objectionable.
         *
         * <p><b>True for exactly one caller, and the asymmetry is deliberate.</b>
         * {@code reporterIsAMember}'s own reasoning is that <i>"the cost of acting on a real report
         * we could not verify is a redundant repair, while the cost of dropping one is a peer that
         * stays diverged forever."</i> That asymmetry survives the ledger: a report we could not
         * verify because of OUR OWN budget is still a report, and treating our budget as evidence
         * against the peer would convert a rate limit into a silent correctness failure.
         *
         * <p>It still CHARGES, because the point of charging is to make the spend visible and
         * bounded — and because a caller driven by a peer is the one whose rate most needs a number
         * attached to it. What it does not do is change its answer.
         */
        public boolean failsOpenOnRefusal() {
            return this == PEER_REPORT_VERIFY;
        }
    }

    /**
     * The window the counters roll on: the ONE measured cooldown after a self-inflicted
     * {@code RESOURCE_EXHAUSTED}, borrowed from {@link MlsFetchBudget#MEASURED_THROTTLE_COOLDOWN_MS}
     * rather than restated, so re-measuring it moves both.
     *
     * <p><b>One datum, not a published limit</b> — the same honesty that constant carries. It is the
     * only window we have evidence for, so it is the window the ledger counts on; if the real one is
     * longer we under-protect, and if it is shorter we are merely conservative.
     */
    public static final long WINDOW_MS = MlsFetchBudget.MEASURED_THROTTLE_COOLDOWN_MS;

    /**
     * Looks that all ceiling-charging callers TOGETHER may spend on one conversation in one window.
     *
     * <p><b>Eight, and the derivation is the point — including the half that was measured wrong the
     * first time.</b> Ten fetches on one conversation drew {@code grpcStatus=8 RESOURCE_EXHAUSTED}
     * on a device, and the identical single fetch succeeded 200 s later. So the ceiling has
     * to sit strictly below ten, and strictly above the sequence we deliberately exercise.
     *
     * <p>It was SEVEN, reasoned as {@link Caller#RECONCILE_DRIVE}(4) + {@link Caller#SELF_HEAL}(3) —
     * the drive-then-self-heal the device check runs back to back. <b>Running it proved that
     * arithmetic incomplete.</b> On both test devices the real sequence also carried one
     * {@link Caller#MAINTENANCE} charge on the same conversation from session bring-up, and the run
     * finished at exactly <i>7 of 7</i>: at the bound rather than under it, so the next ordinary look
     * on a HEALTHY conversation would have been refused. A ceiling that binds on the healthy case is
     * not the bound this class is for.
     *
     * <p>Eight leaves that sequence one look of headroom while still refusing the three heaviest
     * callers all maxing out together (4 + 3 + 2 = 9), which is what a ceiling is for. With
     * {@link Caller#HEALTH_PROBE} and {@link Caller#STALL_REFRESH} held outside it the provable
     * maximum is twelve; in practice those two are single-shot diagnostics.
     *
     * <p>It is NOT derived from ten. A bound set at the value that failed is a bound that fails.
     */
    public static final int SHARED_CEILING = 8;

    /** Whether a look may be spent, and if not, which bound refused it. */
    public enum Verdict {
        /** Spend it, and charge it. */
        SPEND,
        /** Spend it and charge nothing — a debug arm. {@link #describeExemption} says so. */
        SPEND_EXEMPT,
        /** This caller has spent its own allowance on this conversation in this window. */
        DENIED_CALLER_RATION,
        /** The conversation's shared allowance is gone, spent by callers other than this one too. */
        DENIED_SHARED_CEILING;

        /** Whether the look happens. */
        public boolean permitted() {
            return this == SPEND || this == SPEND_EXEMPT;
        }

        /** Whether the ledger should record a charge for it. */
        public boolean charges() {
            return this == SPEND;
        }
    }

    /**
     * May {@code caller} spend one {@code GetMlsGroupInfo} on this conversation?
     *
     * @param caller             the door; never null
     * @param spentByThisCaller  this caller's charges in the current window; negative reads as none
     * @param spentAgainstCeiling charges by all ceiling-charging callers in the current window,
     *                           this caller's included; negative reads as none
     * @param sharedCeiling      {@link #SHARED_CEILING}, or an operator override; anything below 1
     *                           is clamped to 1, because a ceiling of zero would refuse a
     *                           conversation that was never allowed to ask anything — a
     *                           self-inflicted outage rather than a tight bound, the same trap
     *                           {@link MlsFetchBudget#mayLook} clamps for
     */
    public static Verdict mayFetch(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling, final int sharedCeiling) {
        if (caller == null) {
            // A charge with no declared caller is the thing this ledger exists to make impossible,
            // so it is refused rather than defaulted. Defaulting would give a new call site a free
            // ration by omission, which is the shape of the defect being closed.
            return Verdict.DENIED_CALLER_RATION;
        }
        if (caller.isExempt()) return Verdict.SPEND_EXEMPT;
        final int mine = Math.max(0, spentByThisCaller);
        if (mine >= Math.max(1, caller.ration)) return Verdict.DENIED_CALLER_RATION;
        if (caller.chargesTheSharedCeiling) {
            final int shared = Math.max(0, spentAgainstCeiling);
            if (shared >= Math.max(1, sharedCeiling)) return Verdict.DENIED_SHARED_CEILING;
        }
        return Verdict.SPEND;
    }

    /** {@link #mayFetch} against {@link #SHARED_CEILING}. */
    public static Verdict mayFetch(final Caller caller, final int spentByThisCaller,
            final int spentAgainstCeiling) {
        return mayFetch(caller, spentByThisCaller, spentAgainstCeiling, SHARED_CEILING);
    }

    // -- what each outcome says, and it must say what IT measured ----------------------------------

    /**
     * The refusal line. It has to say the quota is OURS, and WHICH of our two bounds refused.
     *
     * <p>The person reading it is looking at an operation that stopped, and there are two available
     * wrong conclusions: "Tachyon limits our recovery" (it does not; we do) and "this caller is
     * looping" (it may not be — the shared ceiling is spent by other callers too). Naming the bound
     * separates them, which a single "budget exceeded" line cannot.
     */
    public static String describeRefusal(final Caller caller, final Verdict verdict,
            final int spentByThisCaller, final int spentAgainstCeiling, final int sharedCeiling,
            final String conversation, final Primitive wanted) {
        final String who = "MLS fetch ledger REFUSED " + name(wanted) + " for "
                + name(caller) + " on " + safe(conversation) + ": ";
        final String common = " This is OUR bound, not the server's — one GetMlsGroupInfo is the "
                + "scarce resource in recovery, and ten of them on one conversation drew "
                + "grpcStatus=8 RESOURCE_EXHAUSTED with the identical single fetch succeeding "
                + (WINDOW_MS / 1000L) + "s later. NOTHING WAS ASKED, so this operation "
                + "learned nothing — it must not be read as the server having nothing to say. The "
                + "work is still owed and is re-driven from a fresh trigger.";
        if (verdict == Verdict.DENIED_SHARED_CEILING) {
            return who + "the conversation's shared allowance of " + Math.max(1, sharedCeiling)
                    + " look(s) per " + (WINDOW_MS / 1000L) + "s is gone ("
                    + Math.max(0, spentAgainstCeiling) + " spent, of which "
                    + Math.max(0, spentByThisCaller) + " by this caller). OTHER CALLERS SPENT MOST "
                    + "OF IT — this one is not necessarily looping." + common;
        }
        if (caller == null) {
            // A charge with no declared caller is refused as DENIED_CALLER_RATION, so this line runs
            // for it. It has no ration to name, and a log line that throws inside a refusal turns a
            // bounded refusal into a crash.
            return who + "no caller was declared, so no ration could be consulted and the look is "
                    + "refused rather than defaulted — defaulting would hand a new call site a free "
                    + "allowance by omission." + common;
        }
        // THE RATION ARM MUST CHECK THE CEILING BEFORE SPEAKING FOR IT.
        //
        // mayFetch tests the ration FIRST, so DENIED_CALLER_RATION is returned even when the shared
        // ceiling is also gone — and this line used to say "the conversation's shared allowance
        // still has room" unconditionally, with the numbers contradicting the sentence in the same
        // breath. Device-observed twice, verbatim:
        // "... while the conversation's shared allowance still has room (8 of 8)", where 8 was the
        // ceiling exactly, i.e. NO room.
        //
        // It is not cosmetic: it is the defect class this ledger exists for, reintroduced inside the
        // refusal's own explanation. The sentence is there to tell an operator WHICH bound bit, and
        // the follow-on "THIS CALLER is the one repeating itself" is the operational instruction.
        // Both are wrong when the conversation is simultaneously at the ceiling: this caller may be
        // a minor contributor, and the remedy is to wait out the window rather than to hunt for a
        // loop here. That misreading is expensive, which is why Health.LOOK_REFUSED
        // was split out of Health.UNKNOWN.
        final int mine = Math.max(0, spentByThisCaller);
        final int shared = Math.max(0, spentAgainstCeiling);
        final int ceiling = Math.max(1, sharedCeiling);
        final String ration = who + "it has spent its own " + Math.max(1, caller.ration)
                + "-look allowance (" + mine + " spent per " + (WINDOW_MS / 1000L) + "s)";
        if (!caller.chargesTheSharedCeiling) {
            // Its ration is the ONLY bound it draws on, so the ceiling neither refused this look nor
            // would have allowed it. Reporting the ceiling's state here at all would invite the
            // reader to weigh a number that had no part in the decision.
            return ration + ", which is the only bound this caller draws on — it does not charge "
                    + "the conversation's shared ceiling, so that ceiling had no part in this "
                    + "refusal. THIS CALLER is the one repeating itself." + common;
        }
        if (shared >= ceiling) {
            return ration + " AND the conversation's shared allowance is ALSO gone (" + shared
                    + " of " + ceiling + "). BOTH bounds are exhausted. The ration is named because "
                    + "it is checked first, not because it is the only thing refusing — so this "
                    + "caller is NOT necessarily the one repeating itself, and the remedy is to let "
                    + "the " + (WINDOW_MS / 1000L) + "s window pass rather than to look for a loop "
                    + "here." + common;
        }
        return ration + " while the conversation's shared allowance still has room (" + shared
                + " of " + ceiling + "). THIS CALLER is the one repeating itself." + common;
    }

    /**
     * The line a debug arm logs instead of a charge.
     *
     * <p>Says the arm is exempt AND what that costs, because "exempt" alone reads as "free" and it is
     * not: the RPC is still made and the server still counts it. The operator is the one person who
     * can decide whether that matters, and they can only decide it if the line tells them.
     */
    public static String describeExemption(final Caller caller, final Primitive wanted,
            final String conversation, final int spentAgainstCeiling, final int sharedCeiling) {
        return "MLS fetch ledger EXEMPT: " + name(caller) + " is a debug arm, so its "
                + name(wanted) + " on " + safe(conversation) + " is NOT charged and cannot be "
                + "refused. It is not free — the server still counts it, and the conversation has "
                + Math.max(0, spentAgainstCeiling) + " of " + Math.max(1, sharedCeiling)
                + " shared look(s) spent in the last " + (WINDOW_MS / 1000L) + "s. A debug arm that "
                + "silently spent a recovery look would be lying; one refused for budget would be "
                + "useless.";
    }

    /** The accounting line for a charge that happened. */
    public static String describeCharge(final Caller caller, final Primitive wanted,
            final String conversation, final int spentByThisCallerAfter,
            final int spentAgainstCeilingAfter, final int sharedCeiling) {
        return "MLS fetch ledger charged " + name(wanted) + " to " + name(caller) + " on "
                + safe(conversation) + " — " + Math.max(0, spentByThisCallerAfter) + " of "
                + Math.max(1, caller.ration) + " for this caller, "
                + Math.max(0, spentAgainstCeilingAfter) + " of " + Math.max(1, sharedCeiling)
                + " shared, per " + (WINDOW_MS / 1000L) + "s.";
    }

    /**
     * What a FAIL-OPEN caller logs when it was refused and proceeded anyway.
     *
     * <p>Separate from {@link #describeRefusal} because it is a different claim: the refusal line
     * says nothing was asked, and this one says nothing was asked AND we went ahead. Merging them
     * would let a reader believe a verification happened.
     */
    public static String describeFailOpen(final Caller caller, final String conversation,
            final String whatItProceededWith) {
        return "MLS fetch ledger: " + name(caller) + " on " + safe(conversation) + " was refused a "
                + "look and is PROCEEDING ANYWAY (" + safe(whatItProceededWith) + "). This is "
                + "deliberate and it is the only caller that does it: the cost of acting on a real "
                + "report we could not verify is a redundant repair, while the cost of dropping one "
                + "is a peer that stays diverged forever. NOTHING WAS VERIFIED HERE — our own budget "
                + "refused the check, which is not evidence about the peer.";
    }

    /**
     * The line for a look that WAS spent and came back unreadable, deferring to
     * {@link MlsFetchBudget#describeUnreadableLook} for the throttle classification and adding what
     * only this layer knows: what the whole CONVERSATION had already spent, across every caller.
     *
     * <p>{@link MlsFetchBudget}'s version counts one operation's own looks, which is all it sees. A
     * throttle is provoked by the conversation, not by one caller, so the number that classifies it
     * is this one.
     */
    public static String describeUnreadableLook(final Caller caller, final Primitive wanted,
            final String conversation, final int spentAgainstCeilingBefore) {
        return "MLS fetch ledger: " + name(wanted) + " for " + name(caller) + " on "
                + safe(conversation) + " came back unreadable after the CONVERSATION had spent "
                + Math.max(0, spentAgainstCeilingBefore) + " shared look(s) in the last "
                + (WINDOW_MS / 1000L) + "s across all callers. "
                + MlsFetchBudget.describeUnreadableLook(spentAgainstCeilingBefore, name(caller));
    }

    private static String name(final Caller c) {
        return c == null ? "<no caller declared>" : c.name();
    }

    private static String name(final Primitive p) {
        return p == null ? "<no primitive named>" : p.aidlName;
    }

    private static String safe(final String s) {
        return (s == null || s.isEmpty()) ? "<no conversation key>" : s;
    }
}
