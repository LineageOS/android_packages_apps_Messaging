/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A commit may not be discarded on an outcome that is silent about whether the server took it: a
 * commit the server applied but never acknowledged would leave us below an epoch we signed. The
 * decision is host-tested in {@code MlsCommitApplicationTest}; this scan pins that
 * {@code commitAndSend} consults it before any rollback. See docs/mls/group-lifecycle.md.
 */
public final class MlsUnacknowledgedCommitGuardTest {

    private static final String SEND = "commitAndSend";
    /** The gate it must consult before any rollback. */
    private static final String GATE = "keepUnacknowledgedCommit(";
    /** The RPC that mutates the server's group. */
    private static final String RPC = "applyMlsControl(";
    /** The act that discards the commit. */
    private static final String ROLLBACK = "restoreGroupSnapshot(";

    /**
     * Every rollback after the RPC sits after {@link #GATE}. An ordering, not presence: a gate
     * after the restore, or a second rollback arm, would pass a presence check.
     */
    @Test
    public void theRollbackIsGatedOnTheServerHavingDecided() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), SEND);
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

    /** The gate decides through {@link MlsCommitApplication} and a charged server read. */
    @Test
    public void theGateConsultsTheOutcomeTaxonomyAndTheServer() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "keepUnacknowledgedCommit");
        assertTrue("keepUnacknowledgedCommit() is gone — the gate has been removed "
                + "or renamed, and the ordering check above is now asserting a call to nothing.",
                !body.isEmpty());
        final String[] needed = {
            "MlsCommitApplication.ofVerdict(",
            "isSilent()",
            "MlsCommitApplication.reconcile(",
            "MlsCommitApplication.disposition(",
            // An era/epoch read measures a position, which another member's commit at our base
            // epoch also reaches; the identity check tells whose commit the server took.
            "serverStateCheck(",
            "MlsFetchLedger.Caller.COMMIT_OUTCOME_CHECK",
            // The bound: at most one held commit per conversation.
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

    /** One rollback site on this path keeps the ordering check meaningful. */
    @Test
    public void theGateDecidesButNeverDiscards() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "keepUnacknowledgedCommit");
        assertTrue("keepUnacknowledgedCommit() is gone; see the test above.", !body.isEmpty());
        assertEquals("keepUnacknowledgedCommit() calls " + ROLLBACK + ". It must not: it answers "
                        + "WHETHER to discard and leaves the discarding to the one site in "
                        + SEND + "(), so that site stays the only one and stays orderable.",
                0, SourceScan.count(body, ROLLBACK));
    }

    /** Otherwise the one-commit allowance stays spent for the life of the process. */
    @Test
    public void anAcceptedCommitReleasesTheHoldAllowance() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), SEND);
        assertTrue(SEND + "() is gone; see the first test.", !body.isEmpty());
        final int gate = body.indexOf(GATE);
        assertTrue(SEND + "() no longer calls " + GATE + "; see the first test.", gate >= 0);
        final List<Integer> releases =
                SourceScan.indicesOf(body, "unacknowledgedCommitEpoch = -1L");
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
     * A ledger refusal and an empty read produce the same {@code Reconciliation}, so each logging
     * arm carries one shared evidence string built above the branches.
     */
    @Test
    public void everyArmRecordsWhetherTheServerWasActuallyAsked() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "keepUnacknowledgedCommit");
        assertTrue("keepUnacknowledgedCommit() is gone; see the tests above.", !body.isEmpty());
        assertEquals("the evidence string must be built EXACTLY ONCE, above the branches. Two "
                        + "definitions is how the arms drift apart again.",
                1, SourceScan.count(body, "final String evidence"));
        final int built = body.indexOf("final String evidence");
        final List<Integer> uses = SourceScan.indicesOf(body, "+ evidence");
        assertTrue(
                "nothing uses the evidence string — it is built and dropped, which is worse than "
                + "not building it: the log looks instrumented and is not.", !uses.isEmpty());
        assertEquals("every disposition arm logs, and every one of them must carry the evidence. "
                        + "Three arms, three uses: ROLL_BACK, KEEP_AND_REPORT_SUCCESS is the one "
                        + "case that needs none (it is reached only via a confirmed match, so there "
                        + "is nothing ambiguous to record), and KEEP_BUT_REPORT_UNRESOLVED.",
                2, uses.size());
        for (final Integer at : uses) {
            assertTrue("an arm uses the evidence string BEFORE it is built, at offset " + at,
                    at.intValue() > built);
        }
        // Keyed on the `asked` flag: string contents are blanked by the scan.
        assertTrue(
                "the evidence string is no longer derived from `asked`, so it cannot distinguish "
                + "a ledger refusal from a read that came back empty — the two produce the same "
                + "Reconciliation and mean opposite things, and a run carrying neither token cannot "
                + "be scored.", body.contains("asked ?"));
    }

    /** An unknown verdict is {@code SILENT}, not {@code NOT_APPLIED}. */
    @Test
    public void anUnrecognisedVerdictStillHoldsTheCommit() {
        assertEquals("the fail-safe direction is the automatic one: a verdict nobody has seen must "
                        + "not be able to destroy a commit by falling through",
                MlsCommitApplication.SILENT, MlsCommitApplication.ofVerdict(Integer.MIN_VALUE));
    }
}
