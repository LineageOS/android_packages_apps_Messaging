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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.engine.mls.MlsCommitApplication.Reconciliation;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The partition a commit outcome falls into, and the arithmetic that
 * decides whether the server took a commit whose response said nothing.</b>
 *
 * <h2>What would make each of these fail, since a guard nobody can fail is not a guard</h2>
 *
 * <ul>
 *   <li>{@link #aTransportFailureIsSilentAndMustNotBeTreatedAsARefusal} fails if
 *       {@code VERDICT_TRANSPORT_FAILED} or {@code VERDICT_NOT_REGISTERED} is classified
 *       {@link MlsCommitApplication#NOT_APPLIED}. <b>That state is not hypothetical: it is what the
 *       caller did until this change</b> — {@code commitAndSend} rolled back on every verdict that was
 *       not {@code VERDICT_OK}, which is the same claim expressed as behaviour.</li>
 *   <li>{@link #everyVerdictTheWireCanCarryIsClassified} fails if a {@code VERDICT_*} constant is
 *       added to {@link MlsTransportDisposition} and nothing here notices — the constants are read
 *       by REFLECTION, so the list cannot go stale by being hand-written.</li>
 *   <li>{@link #aCommitThatDidNotMoveOurEpochIsNotAnswerableByAnEpochRead} fails if
 *       {@link MlsCommitApplication#reconcile} reports {@link Reconciliation#SERVER_HAS_IT} for a
 *       {@code LEAVE}, where the comparison is true whether or not the server ever saw the commit.
 *       That is the "a check that cannot fail is not evidence" rule applied to this class's own
 *       output, and the naive {@code serverEpoch >= ourEpoch} implementation has it.</li>
 * </ul>
 */
public final class MlsCommitApplicationTest {

    // ---- the partition ---------------------------------------------------------------------------

    @Test
    public void anOkVerdictMeansTheServerAppliedIt() {
        assertEquals("A gRPC-OK Apply response cannot be hiding a refusal: the response message "
                        + "has exactly ONE wire field, a header of two longs. "
                        + "There is nowhere to put a verdict.",
                MlsCommitApplication.APPLIED,
                MlsCommitApplication.ofVerdict(MlsTransportDisposition.VERDICT_OK));
    }

    @Test
    public void aTransportFailureIsSilentAndMustNotBeTreatedAsARefusal() {
        assertEquals("VERDICT_TRANSPORT_FAILED's own javadoc is \"No server verdict at all — the "
                        + "request never completed. NOT a rejection.\" Classifying it NOT_APPLIED "
                        + "is what let commitAndSend discard a commit the server had taken.",
                MlsCommitApplication.SILENT,
                MlsCommitApplication.ofVerdict(MlsTransportDisposition.VERDICT_TRANSPORT_FAILED));
        assertEquals("VERDICT_NOT_REGISTERED comes from gRPC UNAUTHENTICATED. \"The server never "
                        + "evaluated this commit\" is an inference from a status NAME, never an "
                        + "observation — and isConnectivityLoss already calls this verdict "
                        + "'nothing was decided', which is the claim part 2 exists to stop acting "
                        + "on.",
                MlsCommitApplication.SILENT,
                MlsCommitApplication.ofVerdict(MlsTransportDisposition.VERDICT_NOT_REGISTERED));
    }

    @Test
    public void theFourVerdictsThatCarryAServerStatementAreNotApplied() {
        final int[] spoke = {
            MlsTransportDisposition.VERDICT_ERA_GAP,
            MlsTransportDisposition.VERDICT_EXTERNAL_COMMIT_REFUSED,
            MlsTransportDisposition.VERDICT_GROUP_ID_CHANGED,
            MlsTransportDisposition.VERDICT_REJECTED,
        };
        for (final int v : spoke) {
            assertEquals("verdict " + v + " carries a statement ABOUT THE COMMIT — an earlier "
                            + "change made VERDICT_REJECTED require positive evidence that the server "
                            + "spoke, and the other three name what it said. Rolling back on these "
                            + "is correct and this change must not alter it.",
                    MlsCommitApplication.NOT_APPLIED, MlsCommitApplication.ofVerdict(v));
        }
    }

    /**
     * An unrecognised verdict is SILENT, and the direction is the whole safety argument.
     *
     * <p>{@link MlsTransportDisposition#ofVerdict} answers {@code RETRYABLE} for the same input and
     * for the analogous reason. Both defaults are chosen so that a verdict nobody has seen cannot
     * destroy state on its own.
     */
    @Test
    public void anUnknownVerdictIsSilentRatherThanARefusal() {
        assertEquals(MlsCommitApplication.SILENT, MlsCommitApplication.ofVerdict(999));
        assertEquals(MlsCommitApplication.SILENT, MlsCommitApplication.ofVerdict(-7));
    }

    /**
     * Every {@code VERDICT_*} on the cross-process contract is classified deliberately.
     *
     * <p>Read by reflection, so a constant added tomorrow appears here without anyone remembering
     * to add it. A new verdict falls to {@link MlsCommitApplication#SILENT} by the default arm,
     * which is SAFE but is not necessarily RIGHT — so this test reports the classification of every
     * constant in its failure message, and the count assertion below is what stops the scan from
     * silently examining nothing.
     */
    @Test
    public void everyVerdictTheWireCanCarryIsClassified() throws Exception {
        final Map<String, MlsCommitApplication> seen = new LinkedHashMap<>();
        for (final Field f : MlsTransportDisposition.class.getDeclaredFields()) {
            if (!f.getName().startsWith("VERDICT_")) continue;
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            seen.put(f.getName(), MlsCommitApplication.ofVerdict(f.getInt(null)));
        }
        // ZERO HITS MUST FAIL: a reflection scan that matched nothing would certify
        // every claim below having examined no constant at all.
        assertTrue("no VERDICT_* constant was found on MlsTransportDisposition — the contract's "
                        + "constants have moved or been renamed, and this test just certified a "
                        + "partition it never evaluated", seen.size() >= 7);
        final List<String> unclassified = new ArrayList<>();
        for (final Map.Entry<String, MlsCommitApplication> e : seen.entrySet()) {
            // VERDICT_NOT_IN_GROUP is SILENT BY DECISION, not by fall-through.
            //
            // NOT_APPLIED's threshold is that the server refused ON THE MERITS -- that it
            // evaluated the commit and said no. "This line is not a member of the group"
            // (TachyonError 36) does not meet it, for the same reason TRANSIENT_ERROR does not:
            // it can arrive BEFORE the commit was evaluated or AFTER it was applied. A member
            // whose commit lands and who is then removed by a concurrent commit draws exactly
            // this verdict on a request that DID apply.
            //
            // Classifying it NOT_APPLIED would roll back a commit that may have landed, which
            // diverges us from the group -- the one failure this subsystem must not cause. So
            // the safe direction is also the correct one here, and it is recorded rather than
            // inherited.
            //
            // The falsifier: an applyMlsControl answering NOT_IN_GROUP after which the server's
            // epoch is found NOT to have advanced past ours, observed on a group we had not been
            // removed from. That would make the verdict a statement about the commit and move it
            // to NOT_APPLIED. Nobody has one.
            if (e.getValue() == MlsCommitApplication.SILENT
                    && !"VERDICT_TRANSPORT_FAILED".equals(e.getKey())
                    && !"VERDICT_NOT_REGISTERED".equals(e.getKey())
                    && !"VERDICT_NOT_IN_GROUP".equals(e.getKey())) {
                unclassified.add(e.getKey());
            }
        }
        if (!unclassified.isEmpty()) {
            fail("These verdicts reach MlsCommitApplication's DEFAULT arm and are therefore held to "
                    + "be SILENT: " + unclassified + ". That is the safe direction and it may well "
                    + "be right — but it was reached by falling through rather than by anyone "
                    + "deciding. Classify each one explicitly (and say why in its case label), or "
                    + "add it to this test's known-silent list with the same reasoning. Full "
                    + "partition as it stands: " + seen);
        }
        // And the three buckets must all be occupied: a partition that collapsed onto one value
        // would pass every check above and mean nothing.
        assertEquals("all three buckets must be reachable from the real constants — a partition "
                        + "with an empty bucket is a classifier that is not classifying. " + seen,
                EnumSet.allOf(MlsCommitApplication.class),
                EnumSet.copyOf(seen.values()));
    }

    @Test
    public void onlySilentAnswersIsSilent() {
        assertTrue(MlsCommitApplication.SILENT.isSilent());
        assertTrue(!MlsCommitApplication.APPLIED.isSilent());
        assertTrue(!MlsCommitApplication.NOT_APPLIED.isSilent());
    }

    // ---- the reconciliation arithmetic ------------------------------------------------------------

    @Test
    public void theServerAtOurPostCommitEpochIsAPositionNotYetAnIdentity() {
        assertEquals("equality is where the authenticator can settle whose chain it is, so this "
                        + "arm must stay DISTINCT from 'past' — folding them together is what let "
                        + "a position be reported as an identity",
                Reconciliation.SERVER_AT_OUR_EPOCH,
                MlsCommitApplication.reconcile(new long[] {1L, 3L}, 1L, /*pre=*/ 2L, /*post=*/ 3L));
    }

    /**
     * PAST our epoch is still "it took it", and the {@code >=} is load-bearing.
     *
     * <p>Between our commit landing and the read, another member may have committed on top of it.
     * Requiring equality would roll back an ACCEPTED commit because the group kept moving — which
     * is the defect this is about, re-introduced by a stricter test.
     */
    @Test
    public void theServerPastOurPostCommitEpochStillTookTheCommit() {
        assertEquals(Reconciliation.SERVER_PAST_OUR_EPOCH,
                MlsCommitApplication.reconcile(new long[] {1L, 9L}, 1L, 2L, 3L));
    }

    @Test
    public void theServerStillAtOurPreCommitEpochDidNotTakeIt() {
        assertEquals("this is the ONLY arm that may roll back a silent outcome, and it is the one "
                        + "where the server was asked and answered",
                Reconciliation.SERVER_LACKS_IT,
                MlsCommitApplication.reconcile(new long[] {1L, 2L}, 1L, 2L, 3L));
    }

    @Test
    public void aDifferentEraIsNotACloseCall() {
        // Eras are crossed by a Welcome, never by a commit, so a commit offered in era 1 cannot be
        // sitting on a server in era 2 — in EITHER direction.
        assertEquals(Reconciliation.SERVER_LACKS_IT,
                MlsCommitApplication.reconcile(new long[] {2L, 99L}, 1L, 2L, 3L));
        assertEquals(Reconciliation.SERVER_LACKS_IT,
                MlsCommitApplication.reconcile(new long[] {1L, 99L}, 2L, 2L, 3L));
    }

    @Test
    public void anAbsentReadIsUnreadableAndNeverEvidence() {
        assertEquals("getMlsServerEraEpoch returns null BOTH for a failed RPC and for 'the server "
                        + "holds no group', and the provider conflates them — so null can never be "
                        + "read as 'the server does not have our commit'",
                Reconciliation.UNREADABLE, MlsCommitApplication.reconcile(null, 1L, 2L, 3L));
        assertEquals(Reconciliation.UNREADABLE,
                MlsCommitApplication.reconcile(new long[] {1L}, 1L, 2L, 3L));
        assertEquals("era 0 is the provider's own 'no live era named' sentinel and is not a reading",
                Reconciliation.UNREADABLE,
                MlsCommitApplication.reconcile(new long[] {0L, 3L}, 1L, 2L, 3L));
    }

    @Test
    public void ourOwnUnreadableStateIsUnreadableToo() {
        assertEquals(Reconciliation.UNREADABLE,
                MlsCommitApplication.reconcile(new long[] {1L, 3L}, -1L, 2L, 3L));
        assertEquals(Reconciliation.UNREADABLE,
                MlsCommitApplication.reconcile(new long[] {1L, 3L}, 1L, -1L, 3L));
        assertEquals(Reconciliation.UNREADABLE,
                MlsCommitApplication.reconcile(new long[] {1L, 3L}, 1L, 2L, -1L));
    }

    /**
     * <b>The {@code LEAVE} case, and the reason this class has a fourth verdict.</b>
     *
     * <p>A self-leave is a by-reference PROPOSAL: it does not advance our epoch. So
     * {@code serverEpoch >= ourEpoch} is TRUE in both worlds — the server took it, or the server
     * never saw it — and a comparison whose answer is the same either way is not a measurement.
     * Reporting {@link Reconciliation#SERVER_HAS_IT} from it would be a green light that could not
     * have been red.
     *
     * <p>This test fails against the obvious implementation, which is the point of writing it.
     */
    @Test
    public void aCommitThatDidNotMoveOurEpochIsNotAnswerableByAnEpochRead() {
        assertEquals("a LEAVE leaves our epoch where it was, so no era/epoch read can locate it",
                Reconciliation.INDISTINGUISHABLE,
                MlsCommitApplication.reconcile(new long[] {1L, 2L}, 1L, /*pre=*/ 2L, /*post=*/ 2L));
        // And it is decided BEFORE the read is consulted, so the caller can skip spending the fetch.
        assertEquals("with no read at all the answer is the same, which is what lets the caller "
                        + "avoid paying for a question that has no answer",
                Reconciliation.INDISTINGUISHABLE,
                MlsCommitApplication.reconcile(null, 1L, 2L, 2L));
    }

    // ---- the disposition: what the caller actually does --------------------------------------------

    @Test
    public void aMeasuredAcceptanceIsReportedAsSuccess() {
        // AT our epoch, the authenticator MATCHES: the chain is demonstrably ours.
        assertEquals(MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, /*alreadyHoldingOne=*/ false,
                        MlsWelcomeAdmission.ServerState.MATCHES));
        // And holding an earlier one does not change it: the server was ASKED and answered.
        assertEquals(MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, /*alreadyHoldingOne=*/ true,
                        MlsWelcomeAdmission.ServerState.MATCHES));
    }

    /**
     * <b>A POSITION MATCH IS NOT AN IDENTITY MATCH, and this is the arm review found.</b>
     *
     * <p>Another member commits at our base epoch, the server takes THEIRS, and it lands at our
     * post-commit epoch NUMBER on a different chain. Reporting success there is not merely an
     * over-claim: {@code commitAndSend} returns {@code era >= 0}, so RCC.16 §9.5.3's release arm —
     * gated on {@code era < 0} — never runs and the certificate's one attempt stays spent. That is
     * the marker wedge through a new door.
     *
     * <p>This test fails against the implementation that answered SUCCESS on the numbers alone,
     * which is what shipped in the second cut.
     */
    @Test
    public void aMatchingEpochNumberWithADifferingAuthenticatorIsNotAnAcceptance() {
        assertEquals("DIFFERS at equality means our commit is NOT on the server's chain",
                MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, false,
                        MlsWelcomeAdmission.ServerState.DIFFERS));
        for (final MlsWelcomeAdmission.ServerState notAsked : new MlsWelcomeAdmission.ServerState[] {
                MlsWelcomeAdmission.ServerState.UNKNOWN,
                MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER}) {
            assertEquals("an unanswered identity question gets what every other unanswered question "
                            + "gets here: a hold, not a claim (" + notAsked + ")",
                    MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                    MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                            Reconciliation.SERVER_AT_OUR_EPOCH, false, notAsked));
        }
    }

    /**
     * DIFFERS must HOLD, never roll back — knowing we do not have it changes what we REPORT, not
     * which direction is survivable.
     *
     * <p>Rolling back would put us one epoch BELOW a server we are already forked from:
     * {@code LOWER_EPOCH_CHAIN_UNKNOWN}, which has no repair. Keeping leaves matching numbers with a
     * differing authenticator, which is {@code Health.DIVERGED} and which {@code reconcileAction}
     * rebuilds. The asymmetry that governs the whole rule governs this arm too.
     */
    @Test
    public void aKnownDivergenceStillHoldsRatherThanRollingBackIntoTheUnrepairableDirection() {
        assertTrue("ROLL_BACK on DIFFERS would trade a repairable state for an unrepairable one",
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, false,
                        MlsWelcomeAdmission.ServerState.DIFFERS)
                        != MlsCommitApplication.Disposition.ROLL_BACK);
    }

    /**
     * Above equality the authenticator cannot answer, so it is not asked and not consulted — and
     * the commit is therefore HELD, not reported as a success.
     *
     * <p>One epoch has one authenticator: comparing ours against the server's at a HIGHER epoch
     * returns DIFFERS by construction. A check that cannot come back "same" is not a check, and
     * acting on it would roll back a commit that WAS accepted merely because the group kept moving.
     * So the answer is ignored — and with nothing measured, nothing is claimed.
     */
    @Test
    public void pastOurEpochIgnoresTheIdentityAnswerEntirely() {
        for (final MlsWelcomeAdmission.ServerState any : MlsWelcomeAdmission.ServerState.values()) {
            assertEquals("SERVER_PAST_OUR_EPOCH must not depend on an answer that is DIFFERS by "
                            + "construction there (" + any + ")",
                    MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                    MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                            Reconciliation.SERVER_PAST_OUR_EPOCH, false, any));
        }
    }

    /**
     * <b>THE §9.5.3 REGRESSION THIS PARTITION INTRODUCED, pinned so it cannot come back.</b>
     *
     * <p>{@code commitAndSend} returning {@code era >= 0} makes RCC.16 §9.5.3's marker-release arm
     * unreachable, because that arm is gated on {@code era < 0}. Previously a silent outcome rolled
     * back, returned negative and RELEASED the certificate's one attempt — that release IS the
     * marker repair. Any arm that reports SUCCESS without having measured the identity
     * therefore leaves the attempt spent until the next mint, with the group's copy of our
     * credential ageing toward the floor and a good certificate in hand.
     *
     * <p>So the invariant is stated as a property over the whole decision space rather than as a
     * case list: <b>a SILENT outcome reaches KEEP_AND_REPORT_SUCCESS only via MATCHES.</b> This
     * enumerates every combination and fails if any other route to success is ever opened —
     * including one added by a future Reconciliation value, which a case list would not catch.
     */
    @Test
    public void aSilentOutcomeReportsSuccessOnlyWhenTheIdentityWasMeasured() {
        final List<String> wrong = new ArrayList<>();
        for (final Reconciliation rec : Reconciliation.values()) {
            for (final boolean holding : new boolean[] {false, true}) {
                for (final MlsWelcomeAdmission.ServerState id
                        : MlsWelcomeAdmission.ServerState.values()) {
                    final boolean success = MlsCommitApplication.disposition(
                            MlsCommitApplication.SILENT, rec, holding, id)
                            == MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS;
                    final boolean measured = rec == Reconciliation.SERVER_AT_OUR_EPOCH
                            && id == MlsWelcomeAdmission.ServerState.MATCHES;
                    if (success != measured) {
                        wrong.add(rec + "/holding=" + holding + "/" + id
                                + (success ? " reports SUCCESS unmeasured" : " fails to report a "
                                        + "measured acceptance"));
                    }
                }
            }
        }
        if (!wrong.isEmpty()) {
            fail("A SILENT outcome may report SUCCESS only when the epoch AUTHENTICATOR was compared "
                    + "and matched. These combinations disagree: " + wrong + ". Reporting success "
                    + "from a POSITION alone returns era >= 0, which skips §9.5.3's marker-release "
                    + "arm (gated on era < 0) and leaves the certificate's one attempt spent until "
                    + "the next mint — the marker repair, removed by a fix for its sibling.");
        }
    }

    @Test
    public void aServerThatDidNotTakeItRollsBack() {
        assertEquals(MlsCommitApplication.Disposition.ROLL_BACK,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_LACKS_IT, false,
                        MlsWelcomeAdmission.ServerState.UNKNOWN));
        assertEquals("a verdict that says the server refused must roll back whatever the read said",
                MlsCommitApplication.Disposition.ROLL_BACK,
                MlsCommitApplication.disposition(MlsCommitApplication.NOT_APPLIED,
                        Reconciliation.UNREADABLE, false, MlsWelcomeAdmission.ServerState.UNKNOWN));
    }

    /**
     * <b>The held commit is KEPT and the operation is reported FAILED — two answers, two
     * questions.</b>
     *
     * <p>Reporting success would spend RCC.16 §9.5.3's once-per-certificate marker on a request no
     * server may have seen, because that arm only releases the marker when {@code commitAndSend}
     * returns negative. That is the marker wedge, and it would have been re-created here by
     * answering the state question and the outcome question with one value.
     */
    @Test
    public void anUnresolvedCommitIsKeptButReportedAsFailed() {
        assertEquals(MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.UNREADABLE, false, MlsWelcomeAdmission.ServerState.UNKNOWN));
        assertEquals(MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.INDISTINGUISHABLE, false,
                        MlsWelcomeAdmission.ServerState.UNKNOWN));
    }

    /**
     * <b>The bound, and without it this repair has a runaway.</b>
     *
     * <p>A device that cannot reach the server holds EVERY commit it makes — one maintenance rekey
     * per pass, each applied on top of the last — drifting an epoch further from the group each
     * time. The first is held; every later one is rolled back onto it, which restores the state
     * holding the first, so the gap stays at one epoch however long the outage lasts.
     *
     * <p>This fails against the implementation without the {@code alreadyHoldingOne} argument, which
     * is what the first cut of this change had.
     */
    @Test
    public void onlyOneUnacknowledgedCommitMayBeHeldAtATime() {
        for (final Reconciliation cannotTell
                : new Reconciliation[] {Reconciliation.UNREADABLE, Reconciliation.INDISTINGUISHABLE}) {
            assertEquals("holding a SECOND unacknowledged commit on " + cannotTell + " lets the gap "
                            + "grow without bound for as long as the outage lasts — the AHEAD "
                            + "fixture reached the same conclusion and answers it the same way",
                    MlsCommitApplication.Disposition.ROLL_BACK,
                    MlsCommitApplication.disposition(MlsCommitApplication.SILENT, cannotTell,
                            /*alreadyHoldingOne=*/ true, MlsWelcomeAdmission.ServerState.UNKNOWN));
        }
    }

    /** Every (application, reconciliation, holding) triple has a decision, and none throws. */
    @Test
    public void theDispositionIsTotal() {
        int seen = 0;
        final EnumSet<MlsCommitApplication.Disposition> reached =
                EnumSet.noneOf(MlsCommitApplication.Disposition.class);
        for (final MlsCommitApplication a : MlsCommitApplication.values()) {
            for (final Reconciliation rec : Reconciliation.values()) {
                for (final boolean holding : new boolean[] {false, true}) {
                    for (final MlsWelcomeAdmission.ServerState id
                            : MlsWelcomeAdmission.ServerState.values()) {
                        final MlsCommitApplication.Disposition d =
                                MlsCommitApplication.disposition(a, rec, holding, id);
                        assertNotNull(a + "/" + rec + "/holding=" + holding + "/" + id
                                + " has no disposition", d);
                        reached.add(d);
                        seen++;
                    }
                }
            }
        }
        assertEquals("the enumeration must cover every combination; a smaller number means values "
                        + "were added and this loop is no longer total",
                MlsCommitApplication.values().length * Reconciliation.values().length * 2
                        * MlsWelcomeAdmission.ServerState.values().length, seen);
        assertEquals("every disposition must be reachable from some triple — one that is not is a "
                        + "branch production can never take. " + reached,
                EnumSet.allOf(MlsCommitApplication.Disposition.class), reached);
    }

    /** Neither hold verdict may ever be confused with the one that authorises a rollback. */
    @Test
    public void onlyOneReconciliationValueAuthorisesARollback() {
        final List<Reconciliation> holds = new ArrayList<>();
        for (final Reconciliation v : Reconciliation.values()) {
            if (v != Reconciliation.SERVER_LACKS_IT) holds.add(v);
        }
        assertEquals("FOUR of the five outcomes must HOLD the commit — SERVER_LACKS_IT is the only "
                        + "one that may discard it. Was three of four until SERVER_HAS_IT was split "
                        + "into SERVER_AT_OUR_EPOCH and SERVER_PAST_OUR_EPOCH, which is why this is "
                        + "a ratchet and not a comment: if a value is added that ALSO authorises a "
                        + "rollback, the asymmetry has to be re-argued for it. AHEAD is rebuilt "
                        + "automatically; BEHIND-an-epoch-we-signed is not repaired at all.",
                4, holds.size());
        assertNotNull(Reconciliation.valueOf("SERVER_LACKS_IT"));
    }
}
