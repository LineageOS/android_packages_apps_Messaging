/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link MlsCommitApplication}: the partition a commit outcome falls into, and the arithmetic that
 * decides whether the server took a commit whose response said nothing. A transport failure is
 * silent, never a refusal, and a {@code LEAVE} (which does not move our epoch) cannot be answered
 * by an epoch read. See docs/mls/health-and-recovery.md.
 */
public final class MlsCommitApplicationTest {

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
     * An unrecognised verdict is silent, like {@link MlsTransportDisposition#ofVerdict}'s retryable
     * default, so an unseen verdict cannot destroy state on its own.
     */
    @Test
    public void anUnknownVerdictIsSilentRatherThanARefusal() {
        assertEquals(MlsCommitApplication.SILENT, MlsCommitApplication.ofVerdict(999));
        assertEquals(MlsCommitApplication.SILENT, MlsCommitApplication.ofVerdict(-7));
    }

    /**
     * Every {@code VERDICT_*} on the cross-process contract is classified deliberately. Read by
     * reflection so a new constant appears here; the failure message lists every classification.
     */
    @Test
    public void everyVerdictTheWireCanCarryIsClassified() throws Exception {
        final Map<String, MlsCommitApplication> seen = new LinkedHashMap<>();
        for (final Field f : MlsTransportDisposition.class.getDeclaredFields()) {
            if (!f.getName().startsWith("VERDICT_")) continue;
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) continue;
            seen.put(f.getName(), MlsCommitApplication.ofVerdict(f.getInt(null)));
        }
        // Zero hits fail: a scan that matched nothing would certify every claim below.
        assertTrue("no VERDICT_* constant was found on MlsTransportDisposition — the contract's "
                        + "constants have moved or been renamed, and this test just certified a "
                        + "partition it never evaluated", seen.size() >= 7);
        final List<String> unclassified = new ArrayList<>();
        for (final Map.Entry<String, MlsCommitApplication> e : seen.entrySet()) {
            // VERDICT_NOT_IN_GROUP is silent by decision. NOT_APPLIED means the server evaluated
            // the commit and refused it; "not a member" can arrive before evaluation or after the
            // commit applied (a concurrent removal), and rolling back a commit that landed diverges
            // us from the group.
            if (e.getValue() == MlsCommitApplication.SILENT
                    && !"VERDICT_TRANSPORT_FAILED".equals(e.getKey())
                    && !"VERDICT_NOT_REGISTERED".equals(e.getKey())
                    && !"VERDICT_NOT_IN_GROUP".equals(e.getKey())) {
                unclassified.add(e.getKey());
            }
        }
        if (!unclassified.isEmpty()) {
            fail(
                    "These verdicts reach MlsCommitApplication's DEFAULT arm and are therefore held to "
                    + "be SILENT: " + unclassified + ". That is the safe direction and it may well "
                    + "be right — but it was reached by falling through rather than by anyone "
                    + "deciding. Classify each one explicitly (and say why in its case label), or "
                    + "add it to this test's known-silent list with the same reasoning. Full "
                    + "partition as it stands: " + seen);
        }
        // All three buckets are occupied; a partition collapsed onto one value would pass the rest.
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

    @Test
    public void theServerAtOurPostCommitEpochIsAPositionNotYetAnIdentity() {
        assertEquals("equality is where the authenticator can settle whose chain it is, so this "
                        + "arm must stay DISTINCT from 'past' — folding them together is what let "
                        + "a position be reported as an identity",
                Reconciliation.SERVER_AT_OUR_EPOCH,
                MlsCommitApplication.reconcile(new long[] {1L, 3L}, 1L, /*pre=*/ 2L, /*post=*/ 3L));
    }

    /**
     * Past our epoch still means the server took it: another member may have committed on top, and
     * requiring equality would roll back an accepted commit.
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
        // Eras are crossed by a Welcome, never by a commit, so a commit offered in one era cannot
        // be on a server in another, in either direction.
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
        assertEquals(
                "era 0 is the provider's own 'no live era named' sentinel and is not a reading",
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
     * A self-leave is a by-reference proposal that does not advance our epoch, so
     * {@code serverEpoch >= ourEpoch} is true whether or not the server saw it; the class reports a
     * separate verdict instead of {@link Reconciliation#SERVER_HAS_IT}.
     */
    @Test
    public void aCommitThatDidNotMoveOurEpochIsNotAnswerableByAnEpochRead() {
        assertEquals("a LEAVE leaves our epoch where it was, so no era/epoch read can locate it",
                Reconciliation.INDISTINGUISHABLE,
                MlsCommitApplication.reconcile(new long[] {1L, 2L}, 1L, /*pre=*/ 2L, /*post=*/ 2L));
        // Decided before the read is consulted, so the caller can skip the fetch.
        assertEquals("with no read at all the answer is the same, which is what lets the caller "
                        + "avoid paying for a question that has no answer",
                Reconciliation.INDISTINGUISHABLE,
                MlsCommitApplication.reconcile(null, 1L, 2L, 2L));
    }

    @Test
    public void aMeasuredAcceptanceIsReportedAsSuccess() {
        // At our epoch, the authenticator matches: the chain is ours.
        assertEquals(MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, /*alreadyHoldingOne=*/ false,
                        MlsWelcomeAdmission.ServerState.MATCHES));
        // Holding an earlier one does not change it: the server was asked and answered.
        assertEquals(MlsCommitApplication.Disposition.KEEP_AND_REPORT_SUCCESS,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, /*alreadyHoldingOne=*/ true,
                        MlsWelcomeAdmission.ServerState.MATCHES));
    }

    /**
     * A position match is not an identity match: another member's commit can land at our
     * post-commit epoch number on a different chain. Reporting success there would return
     * {@code era >= 0} and skip RCC.16 §9.5.3's release arm, leaving the certificate's one attempt
     * spent.
     */
    @Test
    public void aMatchingEpochNumberWithADifferingAuthenticatorIsNotAnAcceptance() {
        assertEquals("DIFFERS at equality means our commit is NOT on the server's chain",
                MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                        Reconciliation.SERVER_AT_OUR_EPOCH, false,
                        MlsWelcomeAdmission.ServerState.DIFFERS));
        for (final MlsWelcomeAdmission.ServerState notAsked :
                new MlsWelcomeAdmission.ServerState[] {
                MlsWelcomeAdmission.ServerState.UNKNOWN,
                MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER}) {
            assertEquals(
                            "an unanswered identity question gets what every other unanswered question "
                            + "gets here: a hold, not a claim (" + notAsked + ")",
                    MlsCommitApplication.Disposition.KEEP_BUT_REPORT_UNRESOLVED,
                    MlsCommitApplication.disposition(MlsCommitApplication.SILENT,
                            Reconciliation.SERVER_AT_OUR_EPOCH, false, notAsked));
        }
    }

    /**
     * DIFFERS holds, never rolls back: rolling back would leave us one epoch below a server we are
     * forked from ({@code LOWER_EPOCH_CHAIN_UNKNOWN}, no repair), while keeping gives a diverged
     * health state, which {@code reconcileAction} rebuilds.
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
     * Above equality the authenticator cannot answer (one epoch has one authenticator), so it is
     * not consulted and the commit is held rather than reported as a success.
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
     * A silent outcome reaches KEEP_AND_REPORT_SUCCESS only via MATCHES. Stated over the whole
     * decision space so a new route to success fails, since reporting success unmeasured makes
     * RCC.16 §9.5.3's marker-release arm (gated on {@code era < 0}) unreachable.
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
                                + (success ? " reports SUCCESS unconfirmed" : " fails to report a "
                                        + "confirmed acceptance"));
                    }
                }
            }
        }
        if (!wrong.isEmpty()) {
            fail(
                    "A SILENT outcome may report SUCCESS only when the epoch AUTHENTICATOR was compared "
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
     * The held commit is kept and the operation reported failed: the state question and the outcome
     * question get separate answers, so RCC.16 §9.5.3's marker is not spent on a request no server
     * may have seen.
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
     * With one commit already held, later ones roll back onto it, so an unreachable server leaves
     * the gap at one epoch however long the outage lasts.
     */
    @Test
    public void onlyOneUnacknowledgedCommitMayBeHeldAtATime() {
        for (final Reconciliation cannotTell
                : new Reconciliation[] {Reconciliation.UNREADABLE,
                Reconciliation.INDISTINGUISHABLE}) {
            assertEquals("holding a SECOND unacknowledged commit on " + cannotTell
                            + " lets the gap "
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

    /** Neither hold verdict is confused with the one that authorises a rollback. */
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
