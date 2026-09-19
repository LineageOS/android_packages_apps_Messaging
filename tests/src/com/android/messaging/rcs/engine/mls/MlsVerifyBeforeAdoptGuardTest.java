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
 * {@code establishGroup} and its addMembers arm verify the epoch authenticator before the host
 * adopts a group; the one arm that must adopt first ({@code healOntoServerGroupOrUnadopt}) gives
 * the adoption back when the heal misses. Ordering is the property, so this is a source scan, and
 * each predicate has a falsifier that breaks the real body. See docs/mls/group-lifecycle.md.
 */
public final class MlsVerifyBeforeAdoptGuardTest {

    /** The check that separates "we joined the server's group" from "we built our own". */
    private static final String CHECK = "serverStateCheckFor(";

    /** Its store-resolving sibling, which cannot answer for a group the host has not adopted. */
    private static final String STORE_RESOLVING_CHECK = "serverStateCheck(";

    /** The statement that hands the group to the host. */
    private static final String ADOPT = "adoptGroup(rcsGroupId, first, art.groupId);";

    /** {@code adoptGroup} is package-private, which {@code SourceScan.bodyOf} cannot see. */
    private static final String ADOPT_DECL =
            "String adoptGroup(final String rcsGroupId, final String peerE164,";

    /** The pre-adoption snapshot, and the undo it feeds. */
    private static final String CAPTURE = "captureAdoption(";
    private static final String ROLLBACK = "rollBackAdoption(";

    /** Where the one unavoidable adoption and its rollback live. */
    private static final String DIFFERS_ARM = "healOntoServerGroupOrUnadopt(";

    /** The addMembers arm of {@code establishGroup}. */
    private static final String ADD_ARM = "addMembersToExistingGroup(";

    /** Its RPC; an OK is not evidence the server applied anything. */
    private static final String ADD_RPC = ".addGroupUsersMls(";

    /** The engine-side undo: exactly one, on the refusal. */
    private static final String ENGINE_ROLLBACK = "rollBackDiscardedCreate(";

    /** The add arm's own three negative answers, keyed on its own variable. */
    private static final String ADD_REFUSED =
            "addState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER";
    private static final String ADD_UNKNOWN =
            "addState == MlsWelcomeAdmission.ServerState.UNKNOWN";
    private static final String ADD_DIFFERS =
            "addState == MlsWelcomeAdmission.ServerState.DIFFERS";

    /** The three negative arms, keyed on the comparison, each a different remedy. */
    private static final String ARM_REFUSED =
            "createState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER";
    private static final String ARM_UNKNOWN =
            "createState == MlsWelcomeAdmission.ServerState.UNKNOWN";
    private static final String ARM_DIFFERS =
            "createState == MlsWelcomeAdmission.ServerState.DIFFERS";

    private static String establishBody() throws Exception {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "establishGroup",
                "final MlsUpgradeClaim preClaimed");
        assertTrue("the 4-argument establishGroup was not found in " + SourceScan.TRANSPORT
                + " — if it was renamed or its signature changed, this guard must follow it rather "
                + "than silently pass", body.length() > 2000);
        return body;
    }

    /**
     * @return {@code null} when the create arm verifies before it adopts and gives the one
     *     unavoidable adoption back, else the first violation found, in the wording a reader needs.
     */
    static String orderingFault(final String body) {
        final int check = body.indexOf(CHECK);
        if (check < 0) {
            return "establishGroup never calls " + CHECK + ". The epoch AUTHENTICATOR is the only "
                    + "thing that separates joining the server's group from building our own at the "
                    + "same era — equal era is not equal state.";
        }
        if (body.indexOf(CHECK, check + 1) >= 0) {
            return "establishGroup calls " + CHECK + " more than once. Two asks are two answers, "
                    + "and the arms below route on ONE variable.";
        }
        // Asked about the group the engine built; a store lookup cannot answer before adoption.
        final int semi = body.indexOf(';', check);
        if (semi < 0 || !body.substring(check, semi).contains("art.groupId")) {
            return "establishGroup calls " + CHECK + " without passing art.groupId. Resolving the "
                    + "group id from the host store is the dependency that forced the check BELOW "
                    + "the adoption in the first place.";
        }
        if (body.contains(STORE_RESOLVING_CHECK)) {
            return "establishGroup calls " + STORE_RESOLVING_CHECK + ", which resolves the group "
                    + "through getGroup() and therefore answers UNKNOWN for every create the host "
                    + "has not adopted yet. A check that cannot fail before the adoption is not a "
                    + "check.";
        }

        // Each arm exactly once, and all of them downstream of the check they route on.
        for (final String arm : new String[] {ARM_REFUSED, ARM_UNKNOWN, ARM_DIFFERS}) {
            final int at = body.indexOf(arm);
            if (at < 0) {
                return "establishGroup no longer tests `" + arm + "`. All three negative answers "
                        + "must stay distinguishable: 'we did not ask', 'we could not read' and 'the "
                        + "server holds a different group' are opposite pieces of evidence.";
            }
            if (body.indexOf(arm, at + 1) >= 0) {
                return "establishGroup tests `" + arm + "` more than once — the second test is the "
                        + "one that goes stale.";
            }
            if (at < check) {
                return "establishGroup tests `" + arm + "` BEFORE it calls " + CHECK + ", so it is "
                        + "routing on an answer it has not obtained.";
            }
        }

        // Every adoption is checked against its role, not by position alone: moving the success
        // adoption above the check leaves the DIFFERS adoption last.
        final List<Integer> adopts = SourceScan.indicesOf(body, ADOPT);
        if (adopts.isEmpty()) {
            return "establishGroup no longer contains `" + ADOPT
                    + "` — this guard's whole claim is "
                    + "about WHERE that statement sits and cannot be checked without it.";
        }
        // The addMembers arm is pinned by addArmFault; a re-inlined arm would escape it.
        final int addArm = body.indexOf(ADD_ARM);
        if (addArm < 0) {
            return "establishGroup no longer routes into " + ADD_ARM + ". That is where the "
                    + "addMembers arm's own verify-then-adopt lives; an addMembers "
                    + "re-inlined into establishGroup would adopt outside the body addArmFault() "
                    + "watches, which is how the arm went unguarded the first time.";
        }
        final int lastAdopt = adopts.get(adopts.size() - 1).intValue();
        final int differs = body.indexOf(ARM_DIFFERS);
        for (final String arm : new String[] {ARM_REFUSED, ARM_UNKNOWN, ARM_DIFFERS}) {
            if (body.indexOf(arm) > lastAdopt) {
                return "establishGroup reaches `" + ADOPT + "` BEFORE it tests `" + arm + "`. That "
                        + "is the broken ordering: the arm returns -1 so the CALLER believes the "
                        + "establish failed, while the host is left holding the group — which every "
                        + "later establish then short-circuits on and reports as a success.";
            }
        }
        for (final Integer at : adopts) {
            final int a = at.intValue();
            if (a < check) {
                return "establishGroup adopts at offset " + a + ", BEFORE the verification at "
                        + CHECK
                        + ". That is the broken ordering exactly: the host takes the group, "
                        + "and the check that could refuse it runs afterwards with all three of its "
                        + "arms returning -1 to a caller that is told the establish FAILED.";
            }
            if (a != lastAdopt) {
                // The DIFFERS adoption lives in healOntoServerGroupOrUnadopt, beside its undo.
                return "establishGroup adopts at offset " + a
                        + ", between the verification and the "
                        + "success adoption. The only arm that may adopt early is DIFFERS, and it "
                        + "does so inside " + DIFFERS_ARM + " where its rollback sits beside it.";
            }
        }
        // A bare `return -1` on DIFFERS would leave the caller correct and the group unrepaired.
        final int delegates = body.indexOf(DIFFERS_ARM);
        if (delegates < 0 || delegates < differs || delegates > lastAdopt) {
            return "the DIFFERS arm no longer routes into " + DIFFERS_ARM + ". That is where the "
                    + "adopt-then-roll-back half lives — selfHeal cannot run without the "
                    + "adoption, so the arm takes it deliberately and owes it back.";
        }
        return null;
    }

    /**
     * The DIFFERS arm's own body: adopt, heal, and give the adoption back when the heal misses.
     *
     * @return {@code null} when the arm holds the property, else the violation.
     */
    static String differsArmFault(final String body) {
        final int capture = body.indexOf(CAPTURE);
        final int adopt = body.indexOf("adoptGroup(");
        final int heal = body.indexOf("selfHeal(");
        final int rollback = body.indexOf(ROLLBACK);
        if (adopt < 0) {
            return DIFFERS_ARM + " no longer adopts. It exists BECAUSE this arm has to: selfHeal "
                    + "resolves its group through getGroup and refuses without one.";
        }
        if (capture < 0 || capture > adopt) {
            return DIFFERS_ARM + " adopts without calling " + CAPTURE + " first. The snapshot is "
                    + "what makes the undo exact — taken after the adoption it reads the adoption's "
                    + "own writes, and the rollback then removes rows it did not create.";
        }
        if (heal < 0 || heal < adopt) {
            return DIFFERS_ARM + " no longer calls selfHeal after adopting. The adoption is only "
                    + "justified as a PRECONDITION of that repair; without it, it is just an "
                    + "unverified group the host now owns.";
        }
        if (rollback < 0 || rollback < heal) {
            return DIFFERS_ARM + " never calls " + ROLLBACK + " after the heal. An adoption taken "
                    + "so that selfHeal can run is owed back when the heal does not converge — "
                    + "otherwise the host keeps a group the authenticator already PROVED is a fork, "
                    + "and stays forked.";
        }
        // The rollback is on the non-convergent path, below the success return.
        final int converged = body.indexOf("return healed;");
        if (converged < 0 || converged > rollback) {
            return DIFFERS_ARM + " does not return the converged era before it rolls back, so the "
                    + "undo is either unreachable or it discards a heal that succeeded.";
        }
        return null;
    }

    /**
     * A review trigger, not a proof: {@code adoptGroup} writes through exactly the two helpers
     * {@code rollBackAdoption} reverses, and the undo re-reads {@code getGroup}.
     *
     * @return {@code null} when the coupling holds, else what a reader has to go and check.
     */
    static String couplingFault(final String adoptBody, final String rollBackBody) {
        for (final String write : new String[] {"putGroup(", "ensureRecord("}) {
            if (!adoptBody.contains(write)) {
                return "adoptGroup no longer calls " + write + " — rollBackAdoption is written to "
                        + "reverse exactly putGroup() and ensureRecord(), so this guard's coupling "
                        + "claim no longer has a subject.";
            }
        }
        // A direct store write would bypass the undo.
        for (final String direct : new String[] {"mGroups.", "mRecords."}) {
            if (adoptBody.contains(direct)) {
                return "adoptGroup touches " + direct + " directly. rollBackAdoption reverses what "
                        + "putGroup() and ensureRecord() write; a write made beside them is one the "
                        + "undo does not know about, and the host would keep a fragment of an "
                        + "adoption it was told to give back.";
            }
        }
        for (final String undo
                : new String[] {"mGroups.remove(", "mRecords.removeAlias(", "mRecords.remove("}) {
            if (!rollBackBody.contains(undo)) {
                return "rollBackAdoption does not call " + undo
                        + ". adoptGroup writes an in-memory "
                        + "row, a conversation→group alias and an MlsConversationRecord; an undo "
                        + "that leaves any one of them lets getGroup() reload the group and the "
                        + "conversation re-adopts itself.";
            }
        }
        // The undo re-reads rather than reporting that its removals ran.
        if (!rollBackBody.contains("getGroup(")) {
            return "rollBackAdoption never re-reads getGroup(). Three void removals returning true "
                    + "reports that they RAN, not that they worked — and the whole cost of this "
                    + "was a -1 that meant something other than what the store held.";
        }
        return null;
    }

    /**
     * The addMembers arm: ship the commit, verify, and only then adopt. The arm runs only when the
     * engine holds the group and the host does not, a state every refusal path leaves behind,
     * including one where the authenticator has already shown a fork.
     *
     * @return {@code null} when the arm verifies before it adopts, else the violation.
     */
    static String addArmFault(final String body) {
        final int rpc = body.indexOf(ADD_RPC);
        if (rpc < 0) {
            return "the addMembers arm no longer calls " + ADD_RPC + " — this guard's subject is "
                    + "the ordering between that RPC and the host adoption, and there is nothing to "
                    + "order without it.";
        }
        final int check = body.indexOf(CHECK);
        if (check < 0) {
            return "the addMembers arm never calls " + CHECK + ". An RPC that returns OK is not "
                    + "evidence the server applied anything, and on this arm it is not even the "
                    + "question: the engine state this commit sits on was never adopted by the "
                    + "host, so it was never agreed with the server either. Only the epoch "
                    + "AUTHENTICATOR separates the server's group from one epoch further into our "
                    + "own.";
        }
        if (body.indexOf(CHECK, check + 1) >= 0) {
            return "the addMembers arm calls " + CHECK + " more than once. Two asks are two "
                    + "answers, and the arms below route on ONE variable.";
        }
        final int semi = body.indexOf(';', check);
        if (semi < 0 || !body.substring(check, semi).contains("art.groupId")) {
            return "the addMembers arm calls " + CHECK + " without passing art.groupId. The host "
                    + "holds no group on this path BY CONSTRUCTION, so a store-resolved id would "
                    + "make the check answer UNKNOWN every single time — a check that cannot fail.";
        }
        if (body.contains(STORE_RESOLVING_CHECK)) {
            return "the addMembers arm calls " + STORE_RESOLVING_CHECK + ", which resolves the "
                    + "group through getGroup() — and on this arm the host has never adopted one.";
        }
        if (check < rpc) {
            return "the addMembers arm asks " + CHECK + " BEFORE it ships the commit. The question "
                    + "is whether the server holds the state this commit produced; asked first, it "
                    + "is answered about a different epoch.";
        }
        for (final String arm : new String[] {ADD_REFUSED, ADD_UNKNOWN, ADD_DIFFERS}) {
            final int at = body.indexOf(arm);
            if (at < 0) {
                return "the addMembers arm no longer tests `" + arm + "`. All three negative "
                        + "answers must stay distinguishable: 'we did not ask', 'we could not read' "
                        + "and 'the server holds a different group' are opposite pieces of evidence.";
            }
            if (body.indexOf(arm, at + 1) >= 0) {
                return "the addMembers arm tests `" + arm + "` more than once — the second test is "
                        + "the one that goes stale.";
            }
            if (at < check) {
                return "the addMembers arm tests `" + arm + "` BEFORE it calls " + CHECK
                        + ", so it "
                        + "is routing on an answer it has not obtained.";
            }
        }
        final List<Integer> adopts = SourceScan.indicesOf(body, ADOPT);
        if (adopts.size() != 1) {
            return "the addMembers arm holds " + adopts.size() + " `" + ADOPT + "` statements. "
                    + "Exactly one, on the MATCHES path: a second one is an arm that takes the "
                    + "group without the answer that justifies it.";
        }
        final int adopt = adopts.get(0).intValue();
        if (adopt < check) {
            return "the addMembers arm adopts at offset " + adopt + ", BEFORE the verification at "
                    + CHECK + ". That is the defect exactly, one arm over: the "
                    + "host takes a group whose agreement with the server was never tested, every "
                    + "later establishGroup short-circuits on it and reports its era as a SUCCESS, "
                    + "and joinFromWelcome stops being reachable for that conversation.";
        }
        for (final String arm : new String[] {ADD_REFUSED, ADD_UNKNOWN, ADD_DIFFERS}) {
            if (body.indexOf(arm) > adopt) {
                return "the addMembers arm reaches `" + ADOPT + "` BEFORE it tests `" + arm + "`, "
                        + "so that arm returns -1 to a caller told the establish FAILED while the "
                        + "host keeps the group.";
            }
        }
        final int delegates = body.indexOf(DIFFERS_ARM);
        if (delegates < 0 || delegates < body.indexOf(ADD_DIFFERS) || delegates > adopt) {
            return "the addMembers arm's DIFFERS answer no longer routes into " + DIFFERS_ARM + ". "
                    + "A bare `return -1` there leaves the caller correct and the divergence "
                    + "unrepaired — and that helper is where the adopt-then-roll-back half lives.";
        }
        // Only the RPC refusal is evidence the server applied nothing, so only it rolls the engine
        // back; the DIFFERS self-heal converges from the engine's state.
        final List<Integer> undos = SourceScan.indicesOf(body, ENGINE_ROLLBACK);
        if (undos.size() != 1) {
            return "the addMembers arm calls " + ENGINE_ROLLBACK + " " + undos.size() + " times. "
                    + "Exactly one, on the refusal: an engine rollback on a verification arm acts "
                    + "on an inference rather than on evidence, and it would delete the state the "
                    + "DIFFERS self-heal has to converge from.";
        }
        if (undos.get(0).intValue() > check) {
            return "the addMembers arm rolls the engine back AFTER " + CHECK
                    + ". The only arm with "
                    + "evidence the server applied nothing is the RPC refusal, which is above it.";
        }
        return null;
    }

    private static String addArmBody() throws Exception {
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(),
                "addMembersToExistingGroup");
        assertTrue("addMembersToExistingGroup was not found in " + SourceScan.TRANSPORT
                + " — it is establishGroup's addMembers arm, and this guard must follow it rather "
                + "than silently pass", body.length() > 500);
        return body;
    }

    private static String differsArmBody() throws Exception {
        final String body = SourceScan.bodyOf(
                SourceScan.transportUnsplitCode(), "healOntoServerGroupOrUnadopt");
        assertTrue("healOntoServerGroupOrUnadopt was not found in " + SourceScan.TRANSPORT
                + " — it is the adopt-then-roll-back half and this guard must follow "
                + "it rather than silently pass", body.length() > 500);
        return body;
    }

    /** Production holds the ordering property. */
    @Test
    public void theHostAdoptsOnlyWhatTheServerConfirmed() throws Exception {
        final String fault = orderingFault(establishBody());
        if (fault != null) fail(fault);
    }

    /** Production holds the DIFFERS arm's adopt-heal-undo property. */
    @Test
    public void theOneUnavoidableAdoptionIsGivenBack() throws Exception {
        final String fault = differsArmFault(differsArmBody());
        if (fault != null) fail(fault);
    }

    /** Production holds the addMembers arm's verify-then-adopt property. */
    @Test
    public void theAddMembersArmAdoptsOnlyWhatTheServerConfirmed() throws Exception {
        final String fault = addArmFault(addArmBody());
        if (fault != null) fail(fault);
    }

    /** Falsifier for the addMembers arm: five mutations of the real body, each named. */
    @Test
    public void theAddArmGuardCanFail() throws Exception {
        final String body = addArmBody();
        assertNull("precondition: the real arm must pass before a mutation proves anything",
                addArmFault(body));

        // (1) The adoption above the verification.
        assertEquals("the adoption must be unique for the mutation to be anchored",
                1, SourceScan.count(body, ADOPT));
        final String checkStmt = "final MlsWelcomeAdmission.ServerState addState = " + CHECK;
        assertEquals("the check anchor must be unique", 1, SourceScan.count(body, checkStmt));
        final String preFix = body.replace(ADOPT, "").replace(checkStmt, ADOPT + " " + checkStmt);
        final String faultA = addArmFault(preFix);
        assertNotNull("moving the adoption above the check must fail this guard", faultA);
        assertTrue("and must say it adopts before the verification: " + faultA,
                faultA.contains("BEFORE the verification"));

        // (2) The check deleted: the arm trusts the RPC's OK.
        final String faultB = addArmFault(body.replace(CHECK, "trustTheRpc("));
        assertNotNull("deleting the verification must fail this guard", faultB);
        assertTrue("and must say it never asks: " + faultB, faultB.contains("never calls"));

        // (3) The store-resolving check, which cannot answer on this arm.
        final String faultC = addArmFault(body.replace(CHECK, STORE_RESOLVING_CHECK));
        assertNotNull("swapping in the store-resolving check must fail this guard", faultC);

        // (4) The DIFFERS answer stops routing into the adopt-then-undo helper.
        final String faultD = addArmFault(body.replace(DIFFERS_ARM, "doNothingAbout("));
        assertNotNull("deleting the DIFFERS delegation must fail this guard", faultD);
        assertTrue("and must name the helper: " + faultD, faultD.contains(DIFFERS_ARM));

        // (5) An engine rollback on a verification arm.
        final String differsReturn = "return " + DIFFERS_ARM;
        assertEquals("the DIFFERS return must be unique", 1,
                SourceScan.count(body, differsReturn));
        final String faultE = addArmFault(body.replace(differsReturn,
                ENGINE_ROLLBACK + "gidBytes, preCreate); " + differsReturn));
        assertNotNull("an engine rollback on a verification arm must fail this guard", faultE);
        assertTrue("and must name the rollback: " + faultE, faultE.contains(ENGINE_ROLLBACK));
    }

    /** Falsifier for the DIFFERS arm. */
    @Test
    public void theDiffersArmGuardCanFail() throws Exception {
        final String body = differsArmBody();
        assertNull("precondition: the real arm must pass", differsArmFault(body));

        // The undo deleted: the fork stays adopted when the heal misses.
        final String noUndo = body.replace(ROLLBACK, "neverRolledBack(");
        assertNotNull("deleting the rollback must fail this guard", differsArmFault(noUndo));

        // The snapshot taken after the adoption, so the undo removes nothing.
        final int cap = body.indexOf(CAPTURE);
        final int semi = body.indexOf(';', cap);
        final String snapshotStmt = body.substring(body.lastIndexOf('\n', cap) + 1, semi + 1);
        assertEquals("the snapshot statement must be unique for the mutation to be anchored",
                1, SourceScan.count(body, snapshotStmt));
        final String moved = body.replace(snapshotStmt, "")
                .replace("final int healed = selfHeal(", snapshotStmt
                        + " final int healed = selfHeal(");
        final String faultLate = differsArmFault(moved);
        assertNotNull("taking the snapshot after the adoption must fail this guard", faultLate);
        assertTrue("and must say the snapshot comes too late: " + faultLate,
                faultLate.contains(CAPTURE));

        // The heal removed.
        assertNotNull("deleting the heal must fail this guard",
                differsArmFault(body.replace("selfHeal(", "noHeal(")));
    }

    /** Production holds the coupling property. */
    @Test
    public void theUndoGivesBackWhatTheAdoptionTakes() throws Exception {
        final String src = SourceScan.transportUnsplitCode();
        final String adopt = SourceScan.bodyOfDeclaredAs(src, ADOPT_DECL);
        final String undo = SourceScan.bodyOf(src, "rollBackAdoption");
        assertTrue("adoptGroup was not found in " + SourceScan.TRANSPORT, adopt.length() > 200);
        assertTrue("rollBackAdoption was not found in " + SourceScan.TRANSPORT
                + " — the DIFFERS arm's undo is the fallback half", undo.length() > 200);
        final String fault = couplingFault(adopt, undo);
        if (fault != null) fail(fault);
    }

    /** Falsifier for the create arm: mutations of the real body, each named. */
    @Test
    public void theGuardCanFail() throws Exception {
        final String body = establishBody();
        assertNull("precondition: the real body must pass before a mutation proves anything",
                orderingFault(body));

        // (1) The success adoption moved above the check.
        final int last = body.lastIndexOf(ADOPT);
        assertTrue("the success adoption must be locatable for the mutation to mean anything",
                last > 0);
        final String pulled = body.substring(0, last) + body.substring(last + ADOPT.length());
        final String anchor = "final MlsWelcomeAdmission.ServerState createState = " + CHECK;
        assertEquals("the mutation anchor must be unique", 1, SourceScan.count(pulled, anchor));
        final String preFix = pulled.replace(anchor, ADOPT + " " + anchor);
        final String faultA = orderingFault(preFix);
        assertNotNull("moving the adoption back above the check must fail this guard", faultA);
        assertTrue("and must say the adoption precedes an arm that can refuse: " + faultA,
                faultA.contains("BEFORE it tests"));

        // (1b) An extra adoption above the check, with the success adoption still last.
        final String duplicated = body.replace(anchor, ADOPT + " " + anchor);
        final String faultAb = orderingFault(duplicated);
        assertNotNull("re-adding an adoption above the check must fail this guard", faultAb);
        assertTrue("and must say it precedes the verification: " + faultAb,
                faultAb.contains("BEFORE the verification"));

        // (2) The store-resolving check, which answers UNKNOWN for an unadopted group.
        final String resolving = body.replace(CHECK, STORE_RESOLVING_CHECK);
        final String faultB = orderingFault(resolving);
        assertNotNull("swapping in the store-resolving check must fail this guard", faultB);
        assertTrue("and must say the check was never called: " + faultB,
                faultB.contains("never calls"));

        // (3) The DIFFERS arm no longer routes into the adopt-then-undo helper.
        final String noDelegate = body.replace(DIFFERS_ARM, "doNothingAbout(");
        final String faultC = orderingFault(noDelegate);
        assertNotNull("deleting the DIFFERS delegation must fail this guard", faultC);
        assertTrue("and must name the helper: " + faultC, faultC.contains(DIFFERS_ARM));

        // (4) An adoption inlined between the check and the success adoption.
        final String inlined = body.replace(
                "if (createState == MlsWelcomeAdmission.ServerState.DIFFERS) {",
                "if (createState == MlsWelcomeAdmission.ServerState.DIFFERS) { " + ADOPT);
        final String faultD = orderingFault(inlined);
        assertNotNull("inlining an adoption into the create path must fail this guard", faultD);
        assertTrue("and must say where it sits: " + faultD,
                faultD.contains("between the verification and the success adoption"));
    }

    /** Falsifier for the coupling. */
    @Test
    public void theCouplingGuardCanFail() throws Exception {
        final String src = SourceScan.transportUnsplitCode();
        final String adopt = SourceScan.bodyOfDeclaredAs(src, ADOPT_DECL);
        final String undo = SourceScan.bodyOf(src, "rollBackAdoption");
        assertNull("precondition: the real bodies must pass", couplingFault(adopt, undo));

        // A fourth store write added beside the two the undo reverses.
        final String extraWrite = adopt.replace("ensureRecord(key);",
                "ensureRecord(key); mRecords.put(somethingElse);");
        assertNotNull("a direct store write in adoptGroup must fail the coupling guard",
                couplingFault(extraWrite, undo));

        // Each removal deleted in turn.
        for (final String removal
                : new String[] {"mGroups.remove(", "mRecords.removeAlias(", "mRecords.remove("}) {
            final String crippled = undo.replace(removal, "notRemoved(");
            final String fault = couplingFault(adopt, crippled);
            assertNotNull("deleting " + removal + " must fail the coupling guard", fault);
        }

        // The re-read dropped, so the undo reports success on three void calls.
        final String unverified = undo.replace("getGroup(", "neverAsked(");
        final String fault = couplingFault(adopt, unverified);
        assertNotNull("dropping the post-rollback re-read must fail the coupling guard", fault);
        assertTrue("and must say so: " + fault, fault.contains("getGroup()"));
    }
}
