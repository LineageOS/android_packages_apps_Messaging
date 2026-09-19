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
 * <b>What an outcome says about whether the SERVER applied our commit.</b>
 *
 * <p>{@link MlsTransportDisposition} answers a different question about the same integer: <i>may a
 * drive loop try again?</i> That one is about OUR budget. This one is about THE SERVER'S STATE, and
 * the two are not the same partition — {@code VERDICT_ERA_GAP} is {@link
 * MlsTransportDisposition#OK} there (the server spoke, so it is recovery's business, not the retry
 * queue's) and {@link #NOT_APPLIED} here (the server spoke to say no).
 *
 * <h2>Why the question needs asking at all</h2>
 *
 * <p>{@code applyMlsControl} MUTATES shared server state. Every other classifier in this tree was
 * written for a READ, where "we did not hear back" and "nothing happened" are the same sentence.
 * For a commit they are not: the request can arrive, be applied, advance the group, and only the
 * RESPONSE be lost. The caller then holds a commit the server kept and has no way to see it.
 *
 * <p>The residue was measured on a device: the
 * server's anchored GroupInfo sat at era 1 epoch 3, its {@code signer} named OUR leaf, and we held
 * era 1 epoch 1. We were two epochs below an epoch we ourselves signed.
 *
 * <h2>SILENT IS THE DEFAULT CASE, NOT AN EDGE CASE — and that is a fact about the wire</h2>
 *
 * <p>Both response protos —
 * {@code ApplyMlsControlMessageResponse} and {@code CreateMlsConversationResponse}
 * — have <b>exactly one wire field each</b>: field 1, a header of two
 * longs. There is physically nowhere in either response to put an application-level verdict. A
 * refusal travels as the gRPC status code plus the binary trailer
 * {@code …instantmessaging.v1.tachyonerror-bin}, both reachable only from a {@code Throwable}.
 *
 * <p>Two consequences, and they point in opposite directions:
 *
 * <ul>
 *   <li><b>{@link #APPLIED} is now supported rather than assumed.</b> It used to be open —
 *       <i>"IF Tachyon can ever express a commit refusal inside a gRPC-OK body rather than as a
 *       status, it would pass as accepted"</i>. It cannot: there is no field. A gRPC-OK Apply
 *       response is an acceptance.</li>
 *   <li><b>The body can never tell us the commit was NOT applied either.</b> Everything we know
 *       about a failure comes from a status and a trailer, so any outcome that carries neither is
 *       silent by construction — a timeout, a cancellation, a dropped stream, a dead bind, a null
 *       result. That is ordinary network weather, not a rare fault.</li>
 * </ul>
 *
 * <h2>The asymmetry that decides what to DO with a silent outcome</h2>
 *
 * <p>The two errors are not equal and the difference is measured, not argued:
 *
 * <ul>
 *   <li><b>Keeping a commit the server refused leaves us epoch-AHEAD</b>, which
 *       {@code MlsProviderTransport.detectHealth} reports as {@code Health.AHEAD} and
 *       {@code reconcileAction}'s AHEAD arm REBUILDS. Costly — the rebuild drops both halves and
 *       re-establishes — but automatic and terminating.</li>
 *   <li><b>Discarding a commit the server applied leaves us epoch-BEHIND</b>, which is
 *       {@code Health.LOWER_EPOCH_CHAIN_UNKNOWN}, and that arm performs NO WORK: its own comment
 *       says catch-up needs the missed commits, <i>"which we do not buffer yet, so
 *       there is nothing to replay from"</i>. It logs and returns. There is no repair on that
 *       side.</li>
 * </ul>
 *
 * <p>So when we cannot tell, the safe direction is to KEEP. That is the whole of part 2.
 */
public enum MlsCommitApplication {

    /**
     * The server took the commit. Adopt the post-commit state.
     *
     * <p>Reached only from {@code VERDICT_OK}, which the provider sets from
     * {@code MlsControlClient.Result.ok()} — {@code status == Status2.OK}, i.e. gRPC OK with a
     * response. Per the field enumeration above, no refusal can be hiding in that body.
     *
     * <p><b>The one-sentence invariant this class now holds, and it is worth stating as a rule
     * rather than leaving to be re-derived from the switch:</b> a SILENT outcome is reported as a
     * SUCCESS only when the epoch AUTHENTICATOR was compared and matched. Position alone never
     * suffices — not at our epoch, and not past it.
     */
    APPLIED,

    /**
     * The server evaluated the commit and did not take it. Rolling back is correct and is what we
     * have always done.
     *
     * <p>Every member of this set requires POSITIVE EVIDENCE THAT THE SERVER SPOKE — a gRPC status
     * or a structured {@code mlsError} code from its own trailer. That invariant is part 1 of this
     * rule, enforced in {@code RcsProviderService.mlsVerdictFor}: absent both, the verdict is
     * {@code VERDICT_TRANSPORT_FAILED} rather than {@code VERDICT_REJECTED}. This enum inherits the
     * guarantee rather than re-deriving it, so the two halves cannot drift apart without the
     * provider's own guard failing first.
     *
     * <h3>THE SERVER SPEAKING IS NOT THE SERVER SAYING THE COMMIT WAS NOT APPLIED</h3>
     *
     * <p>Part 1's threshold is necessary and <b>not sufficient</b>, and the vocabulary was audited
     * against the server's own reason table rather than assumed. Two of those reasons state
     * nothing about the commit's fate, and both were landing here:
     *
     * <ul>
     *   <li><b>{@code UNKNOWN}</b> — the server cannot name the reason. The absence of a claim, not
     *       a claim.</li>
     *   <li><b>{@code TRANSIENT_ERROR}</b> — something went wrong transiently, which can happen
     *       before OR AFTER the commit was applied. That is exactly this case.</li>
     * </ul>
     *
     * <p>Both now map to {@code VERDICT_TRANSPORT_FAILED} in the provider, so they reach
     * {@link #SILENT} here. Every other reason — {@code INCORRECT_ERA}/{@code _EPOCH}/
     * {@code _EPOCH_AUTHENTICATOR}, {@code EXPIRED_CREDENTIAL},
     * {@code MISMATCHED_RCS_GROUP_STATE}, {@code UNPARSABLE_COMMIT},
     * {@code MISMATCHED_CONFIRMATION_TAG}, {@code PENDING_PROPOSAL},
     * {@code ENCRYPTION_NOT_AVAILABLE}, {@code INVALID_COMMIT}, {@code INVALID_INPUT},
     * {@code MLS_GROUP_HAS_END_MLS}, {@code MLS_GROUP_NOT_FOUND} and both {@code QUOTA_REACHED}
     * reasons — is a refusal on the merits.
     *
     * <p><b>This is NOT Google Messages' RECOVERABLE/UNRECOVERABLE split.</b> That one is a
     * RETRY POLICY: it puts {@code TRANSIENT_ERROR} in RECOVERABLE and {@code UNKNOWN} in
     * UNRECOVERABLE, and neither placement is a statement about whether the commit landed. Google
     * Messages'
     * two-way split must not be allowed to collapse this three-way one — the whole point is that
     * {@link #SILENT} exists and needs its own treatment. (Nor is its classifier an independent
     * witness of anything: it and the IMDN-plane classifier ship in the same binary, so their
     * agreement is design, not corroboration.)
     *
     * <h3>THE RESIDUAL EXPOSURE, named rather than widened away</h3>
     *
     * <p>A non-retryable gRPC status with NO structured code and no matching prose still lands here.
     * A status proves a server answered; it does not prove the MLS service evaluated the commit —
     * which is why {@code UNAUTHENTICATED} was carved out separately. This set is NOT widened to
     * cover it, for a measured reason: {@code PERMISSION_DENIED} is how the server refuses a
     * credential's validity window (we have measured one naming a peer's MSISDN) and it
     * arrives with prose and no {@code mlsError}. Treating every bare status as silent would send
     * that refusal to backoff instead of to its remedy.
     *
     * <p><b>The falsifier, so this paragraph is a claim and not a hedge:</b> an
     * {@code applyMlsControl} that draws a non-retryable status with no {@code mlsError}, after
     * which the server's epoch is found to have ADVANCED to what our commit would have produced.
     * One such observation moves that case to {@link #SILENT}. Nobody has one.
     */
    NOT_APPLIED,

    /**
     * <b>The outcome does not say.</b> The commit may or may not be on the server's chain, and
     * nothing in the response can settle it.
     *
     * <p>The caller must NOT roll back on this. It must either establish the answer (one
     * {@code GetMlsGroupInfo} era/epoch read — see {@link #reconcile}) or hold the committed state
     * and let reconcile settle it later.
     */
    SILENT;

    /** Whether the caller must not discard the commit on this outcome. */
    public boolean isSilent() {
        return this == SILENT;
    }

    /**
     * Partition a provider verdict.
     *
     * <p>THE DEFAULT IS {@link #SILENT}, and the direction is chosen rather than inherited. An
     * unrecognised verdict is one a future provider invented; classifying it as a refusal would
     * discard a commit on the strength of an integer we have never seen, and the asymmetry in this
     * class's header says which way to be wrong. Compare {@link MlsTransportDisposition#ofVerdict},
     * whose unknown case is {@code RETRYABLE} for the analogous reason.
     *
     * <h3>{@code VERDICT_NOT_REGISTERED} is here, and this is the one judgement call</h3>
     *
     * <p>Its only construction site is gRPC {@code UNAUTHENTICATED} (16), and the provider's own
     * log line there says <i>"the server never evaluated this commit"</i>. That sentence is an
     * inference from a status NAME, not an observation — nobody has watched an Apply that drew 16
     * and then checked the server's epoch. Two things keep it in {@link #SILENT}: the standing rule
     * against reading a status code as though its name were a measurement, and the fact that our
     * own {@link MlsTransportDisposition#isConnectivityLoss} ALREADY calls this verdict "nothing
     * was decided" — which is precisely the claim part 2 exists to stop acting on.
     *
     * <p>The cost of being wrong here is one server read on a path that has just failed, and at
     * worst a held commit the next reconcile resolves. The cost of being wrong the other way is the
     * rule.
     */
    public static MlsCommitApplication ofVerdict(final int verdict) {
        switch (verdict) {
            case MlsTransportDisposition.VERDICT_OK:
                return APPLIED;
            // The server spoke. Each of these four carries a statement ABOUT THE COMMIT.
            case MlsTransportDisposition.VERDICT_ERA_GAP:
            case MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED:
            case MlsTransportDisposition.VERDICT_GROUP_ID_CHANGED:
            case MlsTransportDisposition.VERDICT_REJECTED:
                return NOT_APPLIED;
            case MlsTransportDisposition.VERDICT_NOT_REGISTERED:
            case MlsTransportDisposition.VERDICT_TRANSPORT_FAILED:
            default:
                return SILENT;
        }
    }

    /** What a server era/epoch read says about a commit whose own outcome was {@link #SILENT}. */
    public enum Reconciliation {
        /**
         * <b>The server is AT the epoch our commit produced — which is a POSITION, not an
         * IDENTITY.</b> Undecided until the epoch authenticator is compared.
         *
         * <p>This value used to be folded into a single {@code SERVER_HAS_IT} that the caller read
         * as "it took it". The two are different sentences, and they part company in precisely the
         * case this class's own javadoc named as a cost: another member commits at our base epoch,
         * the server takes THEIRS, and it lands at our post-commit epoch NUMBER on a different
         * chain. One epoch has one authenticator, so at EQUALITY the authenticator settles it —
         * which is why the check is worth its look here and nowhere else.
         */
        SERVER_AT_OUR_EPOCH,
        /**
         * The server is PAST the epoch our commit produced, so a member committed on top of
         * something. Keep and report success.
         *
         * <p><b>No available read can settle this one, and the authenticator check is worse than
         * useless here.</b> One epoch has one authenticator, so comparing ours at our epoch against
         * the server's at a HIGHER epoch returns DIFFERS by construction — a check that cannot come
         * back "same" is not a check, and acting on it would roll back a commit that WAS accepted
         * merely because the group kept moving. That is this class's subject from the other side.
         * {@code LOWER_EPOCH_CHAIN_UNKNOWN}'s javadoc makes the same argument in the same words for
         * its own arm.
         */
        SERVER_PAST_OUR_EPOCH,
        /** The server is behind the epoch our commit produced, or in another era: it did not. Roll back. */
        SERVER_LACKS_IT,
        /** The read did not come back. Hold the commit; a later reconcile is the only way through. */
        UNREADABLE,
        /**
         * <b>The commit did not move our epoch, so no epoch comparison can answer the question.</b>
         *
         * <p>{@code Op.LEAVE} is the live instance: a self-leave is a by-reference PROPOSAL and
         * leaves our epoch exactly where it was, so {@code serverEpoch >= ourEpoch} is true whether
         * or not the server ever saw it. A test whose result is identical in both worlds is not a
         * test, and reporting {@link #SERVER_HAS_IT} from it would be a green light that could not
         * have been red. Held, like {@link #UNREADABLE}, and named apart so the log says which.
         */
        INDISTINGUISHABLE
    }

    /**
     * Did the server take a commit whose response was silent?
     *
     * <p>Pure arithmetic over four numbers, which is the whole reason it lives here rather than in
     * the transport: this is the decision it all turns on and it is otherwise unreachable from a
     * host test.
     *
     * <h3>Why {@code >=} and not {@code ==}</h3>
     *
     * <p>Between our commit landing and this read, another member may have committed on top of it.
     * The server would then be PAST our epoch, and our commit is still on its chain. Requiring
     * equality would roll back a commit that was accepted merely because the group kept moving —
     * the exact failure this class is about, re-introduced by a stricter test.
     *
     * <h3>What {@code >=} costs, stated</h3>
     *
     * <p>A concurrent commit from another member, at our base epoch, that the server took INSTEAD
     * of ours, also lands the server at our post-commit epoch NUMBER on a different chain. We keep,
     * and we are then {@code Health.DIVERGED} (matching numbers, different epoch authenticator) —
     * which {@code reconcileAction} answers with a rebuild. Still the better error: the alternative
     * is being BEHIND, which nothing repairs. Settling it properly would need the server's epoch
     * AUTHENTICATOR, a second fetch on a path that has just failed once.
     *
     * @param serverEraEpoch {@code {era, epoch}} as the provider read it, or {@code null} when the
     *                       read failed or the server holds no group — the provider conflates those
     *                       two, so neither is treated as evidence
     * @param ourEra         our era after the commit
     * @param preEpoch       our epoch BEFORE the commit
     * @param postEpoch      our epoch AFTER the commit
     */
    public static Reconciliation reconcile(final long[] serverEraEpoch, final long ourEra,
            final long preEpoch, final long postEpoch) {
        if (ourEra < 0L || preEpoch < 0L || postEpoch < 0L) return Reconciliation.UNREADABLE;
        // ORDERED BEFORE THE READ IS CONSULTED, on purpose. A commit that did not advance our epoch
        // is unanswerable by ANY era/epoch read, so saying so is more useful than reporting that a
        // read we should not have spent came back fine.
        if (postEpoch <= preEpoch) return Reconciliation.INDISTINGUISHABLE;
        if (serverEraEpoch == null || serverEraEpoch.length < 2) return Reconciliation.UNREADABLE;
        // era <= 0 is the provider's own "no live era named" sentinel (getMlsServerEraEpoch returns
        // null for it, deliberately diverging from Google Messages). Belt and braces: a caller that ever
        // passes the raw pair through must not read era 0 as a real reading either.
        if (serverEraEpoch[0] <= 0L) return Reconciliation.UNREADABLE;
        // A DIFFERENT ERA IS NOT A CLOSE CALL. Eras are crossed by a Welcome, never by a commit, so
        // a commit offered in era N cannot be sitting on a server that is in era M. It did not land.
        if (serverEraEpoch[0] != ourEra) return Reconciliation.SERVER_LACKS_IT;
        if (serverEraEpoch[1] < postEpoch) return Reconciliation.SERVER_LACKS_IT;
        return serverEraEpoch[1] == postEpoch
                ? Reconciliation.SERVER_AT_OUR_EPOCH : Reconciliation.SERVER_PAST_OUR_EPOCH;
    }

    /** What the caller must actually DO, once the outcome and the server have both been read. */
    public enum Disposition {
        /** The server took it. Adopt the post-commit state and report the operation succeeded. */
        KEEP_AND_REPORT_SUCCESS,
        /**
         * Keep the post-commit state, but report the operation as FAILED to the caller.
         *
         * <p><b>These are two different questions and answering them with one value is how this
         * sibling defects were made.</b> The STATE question is "do we still hold what we
         * sent", and the answer is yes — discarding it is the whole thing part 2 forbids. The
         * REPORT question is "did the operation succeed", and the honest answer is that we do not
         * know, so every caller's conservative arm must run. One of those arms is load-bearing:
         * the RCC.16 §9.5.3 credential update releases its once-per-certificate marker when
         * {@code commitAndSend} returns negative AND the verdict is a connectivity loss, which is
         * the repair for a wedged certificate. Reporting success here would leave that marker
         * spent on a request no server may have seen — the exact wedge it exists to prevent.
         */
        KEEP_BUT_REPORT_UNRESOLVED,
        /** Restore the pre-commit snapshot, exactly as before. */
        ROLL_BACK
    }

    /**
     * The whole decision, in one pure function of three facts.
     *
     * @param application       what the verdict says about the server having applied it
     * @param reconciliation    what a server era/epoch read says, from {@link #reconcile}
     * @param alreadyHoldingOne whether an EARLIER commit on this conversation is already being held
     *                          unacknowledged
     * @param identity          the epoch-authenticator comparison, consulted ONLY for
     *                          {@link Reconciliation#SERVER_AT_OUR_EPOCH} — the one arm where it is
     *                          decisive. Pass {@link MlsWelcomeAdmission.ServerState#UNKNOWN} when
     *                          it was not asked; every other arm ignores it.
     */
    public static Disposition disposition(final MlsCommitApplication application,
            final Reconciliation reconciliation, final boolean alreadyHoldingOne,
            final MlsWelcomeAdmission.ServerState identity) {
        if (application == APPLIED) return Disposition.KEEP_AND_REPORT_SUCCESS;
        if (application != SILENT) return Disposition.ROLL_BACK;
        switch (reconciliation) {
            case SERVER_PAST_OUR_EPOCH:
                // REPORTED UNRESOLVED, NOT SUCCESS — and this is the §9.5.3 REGRESSION the
                // defer-until-ACK change introduced, closed at its source rather than at the arm
                // it broke.
                //
                // A member committed on top of SOMETHING. Our commit may or may not be in that
                // chain, and above equality no read can say which: one epoch has one authenticator,
                // so comparing ours against the server's at a higher epoch answers DIFFERS either
                // way. Reporting SUCCESS from that was the same defect this whole class exists to
                // fix, one level up — a claim resting on a position rather than a measurement.
                //
                // THE COST WAS NOT ONLY THE OVER-CLAIM. `commitAndSend` returning era >= 0 makes
                // RCC.16 §9.5.3's marker-release arm unreachable, because that arm is gated on
                // `era < 0`. Before that change a silent outcome rolled back, returned negative and
                // RELEASED
                // the certificate's one attempt — that release IS the wedged-certificate repair.
                // Keeping the commit and reporting success left the attempt spent until the next
                // mint, so the change had silently removed a recovery path built elsewhere.
                //
                // THE ASYMMETRY IS THE SAME ONE AS EVERYWHERE ELSE HERE, so it decides this too.
                // Reporting failure when the commit DID land costs one wasted re-offer — a rekey
                // that succeeds. Reporting success when it did NOT costs the group's copy of our
                // credential ageing to the floor with a good certificate in hand, unreoffered until
                // a mint. Cheap error, expensive error; take the cheap one.
                //
                // WHAT SURVIVES: the STATE is still kept. Only the REPORT changes, which is this
                // class's founding distinction — "do we still hold what we sent" and "did the
                // operation succeed" are different questions.
                return alreadyHoldingOne
                        ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
            case SERVER_AT_OUR_EPOCH:
                // THE ONLY PLACE THIS CLASS CLAIMS IDENTITY, AND THE ONLY PLACE IT MEASURES IT.
                // MATCHES is the sole route to reporting success at equality; everything else HOLDS
                // rather than rolling back, and that is not a hedge:
                //
                //   DIFFERS  — we know our commit is NOT on the server's chain. Rolling back would
                //              put us one epoch BELOW a server we are already forked from, which is
                //              LOWER_EPOCH_CHAIN_UNKNOWN and has no repair. KEEPING leaves matching
                //              numbers with a differing authenticator, which detectHealth reports as
                //              DIVERGED and reconcileAction REBUILDS. Knowing we do not have it
                //              changes what we REPORT, not which direction is survivable.
                //   UNKNOWN / REFUSED_BY_LEDGER — we did not learn. Hold, like every other
                //              unanswered question here.
                if (identity == MlsWelcomeAdmission.ServerState.MATCHES) {
                    return Disposition.KEEP_AND_REPORT_SUCCESS;
                }
                return alreadyHoldingOne
                        ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
            case SERVER_LACKS_IT:
                return Disposition.ROLL_BACK;
            default:
                break;
        }
        // THE BOUND, and the repair has a runaway without it. A device that cannot reach the server
        // holds EVERY commit it makes — a maintenance rekey per pass, each applied on top of the
        // last — and drifts an epoch further from the group each time. The AHEAD fixture reached the
        // same conclusion from the other side and answers it in the same words: at capacity it
        // "refuses this rekey and rolls it back … the gap stops growing here".
        //
        // So the FIRST unacknowledged commit is held and every later one is rolled back ONTO it.
        // The rollback restores the snapshot taken before the SECOND commit, which is the state
        // holding the first — so the gap stays at one epoch however long the outage lasts, and the
        // held commit is still there to be reconciled when the server can be reached again.
        return alreadyHoldingOne
                ? Disposition.ROLL_BACK : Disposition.KEEP_BUT_REPORT_UNRESOLVED;
    }
}
