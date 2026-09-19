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

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * <b>A commit may not be discarded on an outcome that is silent about whether the server took
 * it</b>, asserted over {@code MlsProviderTransport}'s source.
 *
 * <h2>Why a source scan and not a unit test</h2>
 *
 * <p>The decision itself IS unit-tested: {@link MlsCommitApplication} is pure arithmetic and
 * {@code MlsCommitApplicationTest} exercises every arm. What cannot be unit-tested is that
 * {@code commitAndSend} CONSULTS it — the transport needs a {@link android.content.Context} and a
 * bound provider, so it has no host test at all, and this is precisely a case of correct
 * information being available and not acted on. {@code SourceScan}'s javadoc states the standing
 * cost of this technique: a source scan encodes a SPELLING, not a property. It is paid here because
 * the property is otherwise unreachable, and both of that class's rules are met — every needle below
 * is an INVOKED METHOD NAME or a contract constant, never a log label or a variable, and every scan
 * fails on zero hits.
 *
 * <h2>The state each check would fail in, named, because a guard nobody can fail is not a guard</h2>
 *
 * <p>{@link #theRollbackIsGatedOnTheServerHavingDecided} fails against the code as it stood before
 * this landed — verified by running this class with its working directory pointed at a checkout of the
 * pre-change file, where {@code commitAndSend}'s send arm read
 *
 * <pre>
 *     if (r == null || r.verdict != RcsMlsControlResult.VERDICT_OK) {
 *         final boolean rolledBack = snapshot != null
 *                 &amp;&amp; mSelf.restoreGroupSnapshot(g.groupId, snapshot);
 * </pre>
 *
 * <p>with nothing between the RPC and the restore. That is the whole defect: a commit the server
 * APPLIED and never ACKNOWLEDGED was discarded, leaving us below an epoch we had signed — measured
 * on {@code g:b1189d9c} (deviceA, 2026-09-11), where the server's anchored GroupInfo sat at
 * epoch 3 with OUR leaf as its {@code signer} while we held epoch 1.
 *
 * <h2>What this class NO LONGER covers, and where that half now lives</h2>
 *
 * <p>This class used to carry one further check that read the out-of-tree RCS provider's source
 * across a repository boundary — the provider's {@code mlsVerdictFor}, which is where a server
 * reply becomes the verdict {@code commitAndSend} acts on. It asserted two things: that the two
 * structured reasons which say NOTHING about the commit's fate (0, "unknown", and 9, "transient
 * error") route together to the transport-failed verdict and so reach {@link
 * MlsCommitApplication#SILENT}; and that no numeric case label at or above 12 appears in that
 * switch, 12 being the point above which the four known orderings of that reason vocabulary stop
 * agreeing, so a label there is an extrapolation wearing a table's authority.
 *
 * <p>It is gone because the provider is not part of this repository and a guard that cannot find
 * its subject certifies nothing. <b>Both invariants are PROVIDER invariants and belong to the
 * provider's own test suite</b>, which is the suite a change to that method actually runs; that
 * suite already guards the neighbouring properties of the same method (the default arm's evidence
 * requirement, the retryable arm, and the case label 11). Whoever owns that method owns these two
 * as well — do not re-add a reader here, because a guard in a repository that cannot see the code
 * is the failure this deletion fixes, not the one it causes.
 */
public final class MlsUnacknowledgedCommitGuardTest {

    /** The method whose decision this is about. */
    private static final String SEND = "commitAndSend";
    /** The gate it must consult before any rollback. */
    private static final String GATE = "keepUnacknowledgedCommit(";
    /** The RPC that mutates the server's group. */
    private static final String RPC = "applyMlsControl(";
    /** The act that discards the commit. */
    private static final String ROLLBACK = "restoreGroupSnapshot(";

    /**
     * <b>The check this exists for.</b> In {@code commitAndSend}, every rollback that can
     * follow the mutating RPC must be gated on {@link #GATE} having already declined to keep the
     * commit.
     *
     * <p>Asserted as an ORDERING over the brace-matched body rather than as presence: a call to the
     * gate that sat AFTER the restore would satisfy a presence check and change nothing, and a
     * SECOND rollback arm added later — the likeliest way this regresses — would slip past one too.
     */
    @Test
    public void theRollbackIsGatedOnTheServerHavingDecided() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), SEND);
        assertTrue("MlsProviderTransport." + SEND + "() is gone or was renamed, so this guard is "
                + "reading an empty string and would pass every assertion below without examining "
                + "anything.", !body.isEmpty());

        final int rpc = body.indexOf(RPC);
        assertTrue(SEND + "() no longer calls " + RPC + " — the mutating RPC has moved, and the "
                + "ordering this guard asserts is about nothing. Re-point it at the method that "
                + "now sends the commit.", rpc >= 0);

        final int gate = body.indexOf(GATE);
        if (gate < 0) {
            fail(SEND + "() does not call " + GATE + ". That is the state this was written "
                    + "against: the send arm rolled the group back on ANY outcome that was not "
                    + "VERDICT_OK, including the three that say nothing about whether the server "
                    + "applied the commit (a null result, VERDICT_TRANSPORT_FAILED, "
                    + "VERDICT_NOT_REGISTERED). A commit the server took and never acknowledged is "
                    + "then discarded, and we sit below an epoch we signed with no path back — "
                    + "reconcileAction's LOWER_EPOCH_CHAIN_UNKNOWN arm performs no repair.");
        }
        assertEquals("the gate must be consulted exactly once in " + SEND + "(); two call sites "
                        + "means two policies and this guard can only order one of them",
                1, SourceScan.count(body, GATE));
        assertTrue("the gate is consulted BEFORE the commit is sent, which cannot be right — it "
                + "classifies the RESULT of the send", gate > rpc);

        final List<Integer> rollbacks = SourceScan.indicesOf(body, ROLLBACK);
        assertTrue(SEND + "() no longer calls " + ROLLBACK + " anywhere. Either the rollback moved "
                + "(re-point this guard at it) or it was deleted, which would be a much larger "
                + "change than this: defer-until-ACK is what stops a REFUSED commit leaving us "
                + "epoch-ahead for ever.", !rollbacks.isEmpty());
        for (final Integer at : rollbacks) {
            if (at.intValue() > rpc && at.intValue() < gate) {
                fail(SEND + "() discards the commit at offset " + at + ", which is AFTER the "
                        + "mutating RPC and BEFORE " + GATE + ". Every rollback on the send arm "
                        + "must be downstream of the gate, or an outcome that is silent about "
                        + "whether the server applied the commit can throw it away again — which "
                        + "is the defect exactly.");
            }
        }
    }

    /**
     * The gate must decide from the OUTCOME TAXONOMY and the SERVER, not from a re-derived rule.
     *
     * <p>Keyed on the engine class and its two entry points, so the decision cannot drift back into
     * the transport where it would be unreachable from a host test again. {@code isSilent} is named
     * separately from {@code ofVerdict}: a body that classified and then ignored the classification
     * would satisfy the first needle alone.
     */
    @Test
    public void theGateConsultsTheOutcomeTaxonomyAndTheServer() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "keepUnacknowledgedCommit");
        assertTrue("keepUnacknowledgedCommit() is gone — the gate has been removed "
                + "or renamed, and the ordering check above is now asserting a call to nothing.",
                !body.isEmpty());
        final String[] needed = {
            "MlsCommitApplication.ofVerdict(",
            "isSilent()",
            "MlsCommitApplication.reconcile(",
            "MlsCommitApplication.disposition(",
            // THE IDENTITY LEG. Without it the gate claims "the server took OUR commit" from an
            // era/epoch read, which measures a POSITION — and the two part company exactly when
            // another member commits at our base epoch (found in review).
            "serverStateCheck(",
            "MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK",
            // The BOUND. Without a read of the per-conversation hold marker the gate cannot pass
            // `alreadyHoldingOne`, and a device that cannot reach the server holds every commit it
            // makes — one epoch further from the group per maintenance pass.
            "unacknowledgedCommitEpoch",
        };
        for (final String n : needed) {
            assertTrue("keepUnacknowledgedCommit() no longer reaches " + n + ". The partition and "
                    + "the era/epoch arithmetic live in MlsCommitApplication precisely so they can "
                    + "be host-tested; re-deriving either here puts the whole decision back "
                    + "out of reach of a test. The ledger caller is required for the same reason "
                    + "every other GetMlsGroupInfo in this file is charged — an uncharged read is "
                    + "an unbounded one.", body.contains(n));
        }
    }

    /**
     * <b>The gate may not itself roll back.</b>
     *
     * <p>It returns {@code false} to let the caller's existing arm run, which keeps ONE rollback
     * site on this path and therefore one thing for the ordering check above to be about. A gate
     * that both decided and discarded would make {@link #theRollbackIsGatedOnTheServerHavingDecided}
     * vacuous — the restore it was watching for would have moved inside the call it checks the
     * ordering against.
     */
    @Test
    public void theGateDecidesButNeverDiscards() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "keepUnacknowledgedCommit");
        assertTrue("keepUnacknowledgedCommit() is gone; see the test above.", !body.isEmpty());
        assertEquals("keepUnacknowledgedCommit() calls " + ROLLBACK + ". It must not: it answers "
                        + "WHETHER to discard and leaves the discarding to the one site in "
                        + SEND + "(), so that site stays the only one and stays orderable.",
                0, SourceScan.count(body, ROLLBACK));
    }

    /**
     * <b>An ACCEPTED commit must release the hold allowance.</b>
     *
     * <p>The allowance is one unacknowledged commit per conversation. A server that accepts a later
     * commit has answered about this conversation, so whatever was held is settled — and if the
     * accepted path does not say so, the allowance stays spent for the life of the process after a
     * single blip and no future outage may hold anything. The failure is silent and looks exactly
     * like the bug this fixes: a commit discarded on a silent outcome.
     *
     * <p>Keyed on the field assignment rather than on a log line, per {@code SourceScan}'s rule.
     */
    @Test
    public void anAcceptedCommitReleasesTheHoldAllowance() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), SEND);
        assertTrue(SEND + "() is gone; see the first test.", !body.isEmpty());
        final int gate = body.indexOf(GATE);
        assertTrue(SEND + "() no longer calls " + GATE + "; see the first test.", gate >= 0);
        final List<Integer> releases = SourceScan.indicesOf(body, "unacknowledgedCommitEpoch = -1L");
        assertTrue("nothing in " + SEND + "() releases the hold allowance. The gate takes it on a "
                + "held commit and only an ACCEPTED one can settle that conversation from here; "
                + "without the release the allowance is spent for the life of the process after one "
                + "transport blip, and every later outage rolls back instead of holding.",
                !releases.isEmpty());
        boolean afterTheGate = false;
        for (final Integer at : releases) {
            if (at.intValue() > gate) afterTheGate = true;
        }
        assertTrue("every release of the hold allowance in " + SEND + "() sits BEFORE the gate, so "
                + "none of them is on the accepted path — the accepted tail is downstream of the "
                + "gate by construction.", afterTheGate);
    }

    /**
     * <b>EVERY arm must record whether the server was actually ASKED.</b>
     *
     * <p>Caught on hardware by arm D of a device run, and it was this guard's own disease
     * in its own instrument. The HOLD arm printed whether the read was made; the ROLL_BACK
     * arm did not. So {@code "UNREADABLE, and we are ALREADY holding one"} read as THE BOUND FIRED
     * when it could equally have been WE NEVER ASKED — the fetch ledger refusing the first look
     * inside the window produces the same {@code Reconciliation} as a read that came back empty.
     * Different evidence, identical record.
     *
     * <p>Asserted as ONE shared string rather than as a needle per arm, because a per-arm suffix is
     * exactly the thing a future arm forgets — which is how the omission happened. The evidence is
     * assembled above the branches and every branch is handed it, so this guard checks the property
     * (it is built once and used everywhere) rather than counting occurrences of a spelling.
     */
    @Test
    public void everyArmRecordsWhetherTheServerWasActuallyAsked() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "keepUnacknowledgedCommit");
        assertTrue("keepUnacknowledgedCommit() is gone; see the tests above.", !body.isEmpty());
        assertEquals("the evidence string must be built EXACTLY ONCE, above the branches. Two "
                        + "definitions is how the arms drift apart again.",
                1, SourceScan.count(body, "final String evidence"));
        final int built = body.indexOf("final String evidence");
        final List<Integer> uses = SourceScan.indicesOf(body, "+ evidence");
        assertTrue("nothing uses the evidence string — it is built and dropped, which is worse than "
                + "not building it: the log looks instrumented and is not.", !uses.isEmpty());
        assertEquals("every disposition arm logs, and every one of them must carry the evidence. "
                        + "Three arms, three uses: ROLL_BACK, KEEP_AND_REPORT_SUCCESS is the one "
                        + "case that needs none (it is reached only via a measured match, so there "
                        + "is nothing ambiguous to record), and KEEP_BUT_REPORT_UNRESOLVED.",
                2, uses.size());
        for (final Integer at : uses) {
            assertTrue("an arm uses the evidence string BEFORE it is built, at offset " + at,
                    at.intValue() > built);
        }
        // AND THE TOKEN MUST STILL BE DERIVED FROM WHETHER WE ASKED — keyed on the VARIABLE, not on
        // the sentence it produces.
        //
        // My first cut of this assertion looked for the literal "the read was made" and failed
        // against correct code, because SourceScan.transport() blanks string-literal CONTENTS on
        // purpose: its javadoc says key on an invoked name, "never on a receiver, a variable name or
        // a log label", and a log label is exactly what I had reached for. The scan caught the guard
        // committing the error the scan exists to prevent. What survives blanking — and what is
        // actually the property — is that the evidence is a function of `asked`: reword the sentence
        // freely, but if this stops reading the flag, a ledger refusal and an empty read become
        // indistinguishable again (arm D).
        assertTrue("the evidence string is no longer derived from `asked`, so it cannot distinguish "
                + "a ledger refusal from a read that came back empty — the two produce the same "
                + "Reconciliation and mean opposite things, and a run carrying neither token cannot "
                + "be scored.", body.contains("asked ?"));
    }

    /**
     * The taxonomy's own default may not be flipped back to a refusal.
     *
     * <p>A property of the engine class rather than of the transport, asserted here beside the
     * behaviour it protects: if an unknown verdict ever became {@link MlsCommitApplication#NOT_APPLIED},
     * every check above would still pass and the defect would be back for every verdict a future
     * provider adds.
     */
    @Test
    public void anUnrecognisedVerdictStillHoldsTheCommit() {
        assertEquals("the fail-safe direction is the automatic one: a verdict nobody has seen must "
                        + "not be able to destroy a commit by falling through",
                MlsCommitApplication.SILENT, MlsCommitApplication.ofVerdict(Integer.MIN_VALUE));
    }
}
