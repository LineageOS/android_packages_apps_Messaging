/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * {@code runMaintenanceOnce} reconciles the group's identity, not only its era. On a same-era fork
 * the era matches by construction, so an era-only pass would write the server's roster onto a group
 * the server has never seen and leave it looking reconciled. A matching era and epoch with a
 * differing epoch authenticator is a different group at the same position; the pass asks
 * {@code healthAgainstServer}, the discrimination {@code detectHealth} uses, rather than a copy.
 * {@link #theGuardCanFail} is the negative control. See docs/testing.md.
 */
public final class MlsMaintenanceIdentityGuardTest {

    /** The era reconciliation. */
    private static final String ERA_RECONCILE = "quarantineIfAheadOfServer(";

    /** Its outcome, consumed: the arm that drops the group must stop the rest of the pass. */
    private static final String QUARANTINE_CONSUMED = "reconciled.quarantined";

    /** The identity discrimination, shared with {@code detectHealth}. */
    private static final String IDENTITY = "healthAgainstServer(";

    /** Its own ration; borrowing {@code MAINTENANCE}'s would halve the pass's allowance. */
    private static final String IDENTITY_CALLER = "MlsFetchLedger.Caller.MAINTENANCE_IDENTITY";

    /** The verdict the pass must route on. */
    private static final String DIVERGED_TEST = "identity.health == Health.DIVERGED";

    /** Where a fork goes instead of through the rest of the pass. */
    private static final String FORK_ROUTE = "maintenanceFoundAFork(";

    /** The one positive: we read the server's epoch authenticator and it is ours. */
    private static final String BASELINE_GATE = "identity.serverConfirmedOurs()";

    /** The write that records the server's roster as our membership. */
    private static final String BASELINE_WRITE = "recordMembership(key, g, serverRoster);";

    /** The era advance: a refresh rebuilds this group, so a fork must never reach it. */
    private static final String ERA_ADVANCE = "eraAdvance(rcsGroupId, peerE164, serverGroupInfo)";

    private static String maintenanceBody() throws Exception {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "runMaintenanceOnce");
        assertTrue("runMaintenanceOnce was not found in " + SourceScan.TRANSPORT + " — if it was "
                + "renamed or its signature changed, this guard must follow it rather than silently "
                + "pass", body.length() > 2000);
        return body;
    }

    private static String comparisonBody() throws Exception {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "healthAgainstServer");
        assertTrue("healthAgainstServer was not found in " + SourceScan.TRANSPORT + " — it is "
                + "detectHealth's comparison, extracted so the maintenance pass can reach the one "
                + "copy instead of growing a second", body.length() > 800);
        return body;
    }

    /**
     * The condition that governs the statement at {@code at}: the nearest {@code if (} whose brace
     * opens before it. Proximity between landmarks is not a gate.
     */
    private static String conditionGoverning(final String body, final int at) {
        final int ifAt = body.lastIndexOf("if (", at);
        if (ifAt < 0) return "";
        final int open = body.indexOf('{', ifAt);
        if (open < 0 || open > at) return "";
        return body.substring(ifAt, open);
    }

    /**
     * Null when the pass asks the identity question before acting on the server's view, else the
     * first violation.
     */
    static String identityFault(final String body) {
        final int era = body.indexOf(ERA_RECONCILE);
        if (era < 0) {
            return "runMaintenanceOnce no longer calls " + ERA_RECONCILE
                    + ". The era half is where "
                    + "the server's [era, epoch] is read, and the identity half is asked over the "
                    + "pair that read returns — without it the identity check would need a server "
                    + "read of its own.";
        }
        if (!body.contains(QUARANTINE_CONSUMED)) {
            return "runMaintenanceOnce does not consume " + ERA_RECONCILE + "'s outcome (`"
                    + QUARANTINE_CONSUMED + "` is absent). That call can DROP the local group, and "
                    + "every step below it — including " + BASELINE_WRITE
                    + " — then runs against a "
                    + "group the store no longer holds. It returned void and the pass carried on; "
                    + "that is half of the defect.";
        }

        final int identity = body.indexOf(IDENTITY);
        if (identity < 0) {
            return "runMaintenanceOnce never calls " + IDENTITY + ". The era matches BY "
                    + "CONSTRUCTION on a same-era fork, so an era-only reconciliation cannot tell "
                    + "'we are behind' from 'we are on a different chain at the same number' — and "
                    + "those need opposite responses.";
        }
        if (body.indexOf(IDENTITY, identity + 1) >= 0) {
            return "runMaintenanceOnce calls " + IDENTITY + " more than once. Two asks are two "
                    + "answers, and the arms below route on ONE variable.";
        }
        if (identity < era) {
            return "runMaintenanceOnce calls " + IDENTITY + " BEFORE " + ERA_RECONCILE + ", so it "
                    + "cannot be using the [era, epoch] that call reads — which means either a "
                    + "second charged server read, or a comparison made against nothing.";
        }
        final int identityEnd = body.indexOf(';', identity);
        if (identityEnd < 0 || !body.substring(identity, identityEnd).contains(IDENTITY_CALLER)) {
            return "runMaintenanceOnce calls " + IDENTITY + " without naming "
                    + IDENTITY_CALLER + ". Charging the authenticator look to MAINTENANCE would "
                    + "take a pass from spending half its ration to spending all of it — one "
                    + "maintenance pass per window instead of two, silently, with the symptom "
                    + "appearing as conversations that stop being maintained.";
        }

        final int diverged = body.indexOf(DIVERGED_TEST);
        if (diverged < 0 || diverged < identity) {
            return "runMaintenanceOnce does not test `" + DIVERGED_TEST + "` after asking "
                    + IDENTITY + ". Obtaining the verdict and not routing on it is the defect with "
                    + "an extra server look attached.";
        }
        final int fork = body.indexOf(FORK_ROUTE);
        if (fork < 0 || fork < diverged) {
            return "the DIVERGED arm no longer routes into " + FORK_ROUTE + ". A fork that "
                    + "continues through the pass computes its delta against the wrong group and can "
                    + "era-advance, which rebuilds THE FORK around the server's roster — making it "
                    + "look more like the real group without ever being it.";
        }

        // The fork leaves before anything acts on the server's view of a group that is not ours.
        final List<Integer> writes = SourceScan.indicesOf(body, BASELINE_WRITE);
        if (writes.isEmpty()) {
            return "runMaintenanceOnce no longer contains `" + BASELINE_WRITE + "` — this guard's "
                    + "whole claim is about WHEN that statement may run and cannot be checked "
                    + "without it.";
        }
        final int firstWrite = writes.get(0).intValue();
        if (fork > firstWrite) {
            return "runMaintenanceOnce reaches `" + BASELINE_WRITE + "` BEFORE it routes a fork "
                    + "into " + FORK_ROUTE
                    + ". Writing the server's roster onto a group the server "
                    + "has never seen is recording a membership we were never part of, and it is "
                    + "what gives a fork a plausible-looking history.";
        }
        final int advance = body.indexOf(ERA_ADVANCE);
        if (advance >= 0 && fork > advance) {
            return "runMaintenanceOnce reaches `" + ERA_ADVANCE + "` BEFORE it routes a fork into "
                    + FORK_ROUTE
                    + ". An era advance re-creates the group it is issued on; issued on "
                    + "a fork it re-creates the fork.";
        }

        // The baseline write runs on a confirmation, not on the absence of a disproof: IN_SYNC is
        // also what an unread authenticator, a refused look and a non-decisive arm produce.
        final String governing = conditionGoverning(body, firstWrite);
        if (!governing.contains(BASELINE_GATE)) {
            return "`" + BASELINE_WRITE + "` is not governed by `" + BASELINE_GATE + "` — the "
                    + "condition it sits under is `" + governing.trim()
                    + "`. That statement records "
                    + "the SERVER's roster as OUR membership, which is only true of a group that IS "
                    + "the server's group. Running it on anything weaker than a READ AND MATCHING "
                    + "epoch authenticator is the old behaviour exactly.";
        }
        if (governing.contains("!")) {
            return "`" + BASELINE_WRITE + "` is governed by a NEGATED form of `" + BASELINE_GATE
                    + "` (`" + governing.trim() + "`). Spelled as `if (!confirmed) { … } else { "
                    + "write }` the gate inverts the moment the write moves one branch over and "
                    + "still reads as gated; the write belongs under the un-negated predicate.";
        }
        if (firstWrite < identity) {
            return "`" + BASELINE_WRITE + "` runs BEFORE " + IDENTITY + ", so whatever gates it is "
                    + "reading an answer the pass has not obtained.";
        }
        return null;
    }

    /**
     * The comparison asks the authenticator question only where it can come back "same"; null when
     * it does.
     */
    static String decisiveArmFault(final String body) {
        final int equal = body.indexOf("if (cmp == 0) {");
        if (equal < 0) {
            return "healthAgainstServer no longer has an `if (cmp == 0)` arm. Equality of the epoch "
                    + "NUMBERS is the only position at which comparing authenticators settles "
                    + "anything, and this guard's claim is about what sits inside it.";
        }
        final int check = body.indexOf("serverStateCheckFor(");
        if (check < 0) {
            return "healthAgainstServer no longer calls serverStateCheckFor(. The epoch "
                    + "authenticator is the only thing that separates the server's group from "
                    + "another group at the same position.";
        }
        if (body.indexOf("serverStateCheckFor(", check + 1) >= 0) {
            return "healthAgainstServer calls serverStateCheckFor( more than once — the second ask "
                    + "is on an arm where it could not have come back 'same'.";
        }
        if (check < equal) {
            return "healthAgainstServer asks serverStateCheckFor( OUTSIDE the `cmp == 0` arm. One "
                    + "epoch has one authenticator, so anywhere else the comparison answers DIFFERS "
                    + "by construction — a check that cannot come back 'same' is not a check, and "
                    + "acting on it would route every merely-behind group into divergence handling. "
                    + "Health.LOWER_EPOCH_CHAIN_UNKNOWN states the same rule for its own arm.";
        }
        final int lower = body.indexOf("if (cmp < 0) {");
        if (lower >= 0 && check > lower) {
            return "healthAgainstServer asks serverStateCheckFor( at or below the `cmp < 0` arm, "
                    + "which is the arm whose javadoc says the comparison cannot settle it.";
        }
        // It passes the group it compared; the store-resolving sibling can answer about a different
        // group, or UNKNOWN for one the host has not adopted.
        final int semi = body.indexOf(';', check);
        if (semi < 0 || !body.substring(check, semi).contains("g.groupId")) {
            return "healthAgainstServer calls serverStateCheckFor( without passing g.groupId — the "
                    + "group whose era and epoch it just compared. Re-resolving through the host "
                    + "store can answer about a different group, and had no way of agreeing.";
        }
        return null;
    }

    /** Production holds the identity-before-action property. */
    @Test
    public void theMaintenancePassAsksWhoseGroupItIsMaintaining() throws Exception {
        final String fault = identityFault(maintenanceBody());
        if (fault != null) fail(fault);
    }

    /** Production asks the authenticator question only where it is a check. */
    @Test
    public void theIdentityLookIsSpentOnlyWhereItCanComeBackSame() throws Exception {
        final String fault = decisiveArmFault(comparisonBody());
        if (fault != null) fail(fault);
    }

    /**
     * Breaks the production body six ways and requires the guard to name each. Mutation (1)
     * rebuilds the code without the identity reconciliation.
     */
    @Test
    public void theGuardCanFail() throws Exception {
        final String body = maintenanceBody();
        assertNull("precondition: the real body must pass before a mutation proves anything",
                identityFault(body));

        // (1) The baseline write unconditional again; only the gate is removed.
        final String ungated = body.replace("if (" + BASELINE_GATE + ") {", "if (true) {");
        assertEquals("the gate must be spelled exactly once for this mutation to be anchored",
                1, SourceScan.count(body, "if (" + BASELINE_GATE + ") {"));
        final String fault1 = identityFault(ungated);
        assertNotNull("an UNCONDITIONAL baseline write — the old code — must fail this guard",
                fault1);
        assertTrue("and must name the gate: " + fault1, fault1.contains(BASELINE_GATE));

        // (1b) The gate kept but negated: the roster is recorded exactly when the server did not
        // confirm.
        final String negated = body.replace("if (" + BASELINE_GATE + ") {",
                "if (!" + BASELINE_GATE + ") {");
        final String fault1b = identityFault(negated);
        assertNotNull("a NEGATED gate must fail this guard", fault1b);
        assertTrue("and must say the gate is negated: " + fault1b, fault1b.contains("NEGATED"));

        // (2) The identity question deleted.
        final String noAsk = body.replace(IDENTITY, "neverAsked(");
        final String fault2 = identityFault(noAsk);
        assertNotNull("deleting the identity check must fail this guard", fault2);
        assertTrue("and must say it is never called: " + fault2, fault2.contains("never calls"));

        // (3) The ration borrowed from the pass's own: a second pass in one window silently stops.
        final String borrowed = body.replace(IDENTITY_CALLER, "MlsFetchLedger.Caller.MAINTENANCE");
        final String fault3 = identityFault(borrowed);
        assertNotNull("borrowing MAINTENANCE's ration must fail this guard", fault3);
        assertTrue("and must name the caller constant: " + fault3,
                fault3.contains(IDENTITY_CALLER));

        // (4) The DIVERGED route removed, so a fork walks on through the pass.
        final String noRoute = body.replace(FORK_ROUTE, "carryOnRegardless(");
        final String fault4 = identityFault(noRoute);
        assertNotNull("deleting the fork route must fail this guard", fault4);
        assertTrue("and must name the route: " + fault4, fault4.contains(FORK_ROUTE));

        // (5) The quarantine outcome ignored: the pass writes a roster onto a dropped group.
        final String ignored = body.replace(QUARANTINE_CONSUMED, "false");
        final String fault5 = identityFault(ignored);
        assertNotNull("ignoring the quarantine outcome must fail this guard", fault5);
        assertTrue("and must say the outcome is not consumed: " + fault5,
                fault5.contains(QUARANTINE_CONSUMED));
    }

    /** The negative control for the decisive-arm half. */
    @Test
    public void theDecisiveArmGuardCanFail() throws Exception {
        final String body = comparisonBody();
        assertNull("precondition: the real comparison must pass", decisiveArmFault(body));

        // The look hoisted above the equality arm, where it answers DIFFERS by construction and
        // every group that is merely behind would be rebuilt as a fork.
        final int check = body.indexOf("serverStateCheckFor(");
        final int stmtStart =
                body.lastIndexOf("final MlsWelcomeAdmission.ServerState anchor", check);
        assertTrue(
                "the authenticator statement must be locatable for the mutation to mean anything",
                stmtStart > 0);
        final int semi = body.indexOf(';', check);
        final String stmt = body.substring(stmtStart, semi + 1);
        assertEquals("the statement must be unique for the mutation to be anchored",
                1, SourceScan.count(body, stmt));
        final String hoisted = body.replace(stmt, "")
                .replace("if (cmp == 0) {", stmt + " if (cmp == 0) {");
        final String faultA = decisiveArmFault(hoisted);
        assertNotNull("hoisting the authenticator look above the equality arm must fail this guard",
                faultA);
        assertTrue("and must say it is outside the arm: " + faultA, faultA.contains("OUTSIDE"));

        // The store-resolving sibling swapped in.
        final String resolving = body.replace("serverStateCheckFor(caller, rcsGroupId, peerE164, "
                + "g.groupId)", "serverStateCheckFor(caller, rcsGroupId, peerE164, someOtherId)");
        final String faultB = decisiveArmFault(resolving);
        assertNotNull("dropping g.groupId from the authenticator check must fail this guard",
                faultB);
        assertTrue("and must name it: " + faultB, faultB.contains("g.groupId"));

        // The check deleted outright.
        assertNotNull("deleting the authenticator check must fail this guard",
                decisiveArmFault(body.replace("serverStateCheckFor(", "neverAsked(")));
    }
}
