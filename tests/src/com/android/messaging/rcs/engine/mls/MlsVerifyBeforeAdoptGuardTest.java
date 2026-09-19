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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

/**
 * <b>{@code establishGroup} must VERIFY before the host ADOPTS, and give the adoption back on the
 * one arm that cannot be reordered</b>.
 *
 * <h2>The state this pins, measured rather than reasoned</h2>
 *
 * <p>Measured on a device, RCS group {@code b1189d9cfcdc446ab117f38a6adcedd2}. A sibling guard
 * answers how that device BUILT a forked era-1 copy of a group the server already held. This guard
 * is the other half — why the fork became PERMANENT rather than a discarded attempt.
 *
 * <p>{@code adoptGroup} used to run ABOVE the epoch-authenticator check, which is the only check
 * that can separate "we joined the server's group" from "we built our own at the same era". All
 * three of that check's negative arms return {@code -1}, so the CALLER believed the establish had
 * FAILED while the host was left holding the group. Two doors then close behind it:
 *
 * <ol>
 *   <li>{@code establishGroup} early-returns on {@code getGroup(key) != null} (INVARIANT ED-1) and
 *       RETURNS that group's era — so every LATER establish reports era 1 as a <em>success</em> for
 *       a group the server has no trace of;</li>
 *   <li>{@code applyInboundControl} reaches {@code joinFromWelcome} only when {@code resolveInbound}
 *       returns null, and {@code resolveInbound} returns the key as soon as {@code getGroup(key)}
 *       answers — so the one repair available to a member holding no state, being re-admitted by
 *       somebody current, cannot land either.</li>
 * </ol>
 *
 * <p>Device state consistent with exactly that: {@code health=Unknown}, {@code lastHealthy=null},
 * and an engine group file whose mtime had not moved since 2026-09-08 17:49:43.
 *
 * <h2>The two shapes, and why the arms differ</h2>
 *
 * <p><b>VERIFY-THEN-ADOPT</b> is the stronger fix and it is available for {@code REFUSED_BY_LEDGER}
 * and {@code UNKNOWN}: nothing is taken, so nothing has to be given back. It became available by
 * asking the check through {@code serverStateCheckFor}, which takes the MLS group id instead of
 * resolving it from the host store — {@code serverStateCheck} resolves through {@code getGroup} and
 * would therefore answer {@code UNKNOWN} for every unadopted create, which is the dependency that
 * forced the original ordering.
 *
 * <p><b>ADOPT-THEN-ROLL-BACK</b> is the fallback and {@code DIFFERS} needs it: the remedy there is
 * {@code selfHeal}, which resolves its own group through {@code getGroup} and refuses when the host
 * holds none. On that arm the adoption is a PRECONDITION of the repair rather than a claim about it,
 * so it is taken deliberately and owed back when the heal does not converge.
 *
 * <h2>BOTH ARMS — and this guard used to EXEMPT the second</h2>
 *
 * <p>The first fix covered the CREATE arm and said so; its addMembers sibling
 * ({@code action.routesToAddMembers()}) adopted straight after its RPC with no authenticator check
 * at all. This guard then encoded that as a carve-out — <i>"it adopts before this check and always
 * did"</i>, implemented as an {@code if (a > addArmEnd) … continue;} — which made the guard silent
 * about the one place the same defect was still live. The arm moved into
 * {@code addMembersToExistingGroup} and {@link #addArmFault} pins it; {@link #orderingFault} now
 * allows NO adoption before the check and additionally pins that {@code establishGroup} still routes
 * into that helper, so re-inlining the arm cannot take it back outside a guard's view.
 *
 * <p>The add arm's reachability is not an analogy: {@code establishGroup} early-returns on
 * {@code getGroup(key) != null} and the engine plans an addMembers only when it HOLDS the group, so
 * that arm runs exactly when the ENGINE holds the group and the HOST does not — the residue every
 * refusal path in this method deliberately leaves behind, including
 * {@code healOntoServerGroupOrUnadopt}'s, where the authenticator has already shown the engine's
 * group is a fork.
 *
 * <h2>Why a source guard and not a unit test</h2>
 *
 * <p>ORDERING is the property, and {@code MlsProviderTransport} needs a {@link android.content
 * .Context} and a bound provider — it has no host test at all. Same call as
 * {@link MlsRebuildForkRefusalGuardTest}, which pins the sibling half of this defect.
 *
 * <h2>The negative control</h2>
 *
 * <p>{@link #theGuardCanFail} takes the REAL body and breaks it four ways — restore the original
 * ordering, swap the store-resolving check back in, delete the {@code DIFFERS} rollback, and take
 * the pre-adoption snapshot after the adoption — and requires this guard to name each. Without that
 * it would be a check whose output is the same whether or not the property holds.
 */
public final class MlsVerifyBeforeAdoptGuardTest {

    /** The check that separates "we joined the server's group" from "we built our own". */
    private static final String CHECK = "serverStateCheckFor(";

    /** Its store-resolving sibling, which CANNOT answer for a group the host has not adopted. */
    private static final String STORE_RESOLVING_CHECK = "serverStateCheck(";

    /** The statement that hands the group to the host. */
    private static final String ADOPT = "adoptGroup(rcsGroupId, first, art.groupId);";

    /**
     * {@code adoptGroup}'s own declaration, spelled out because it is PACKAGE-PRIVATE.
     *
     * <p>{@code SourceScan.bodyOf} keys on a visibility modifier, so it answers {@code ""} for
     * this method — which reads as "the body holds nothing" rather than "I could not see it". The
     * first cut of this guard consumed that empty string and its coupling assertions all passed
     * vacuously; {@link SourceScan#bodyOfDeclaredAs} exists because of it.
     */
    private static final String ADOPT_DECL =
            "String adoptGroup(final String rcsGroupId, final String peerE164,";

    /** The pre-adoption snapshot, and the undo it feeds. */
    private static final String CAPTURE = "captureAdoption(";
    private static final String ROLLBACK = "rollBackAdoption(";

    /** Where the one unavoidable adoption and its rollback live. */
    private static final String DIFFERS_ARM = "healOntoServerGroupOrUnadopt(";

    /**
     * The addMembers arm, which {@code establishGroup} used to hold inline and adopt from without
     * asking anything.
     */
    private static final String ADD_ARM = "addMembersToExistingGroup(";

    /** Its RPC, whose OK used to be the whole of its verification. */
    private static final String ADD_RPC = ".addGroupUsersMls(";

    /** The ENGINE-side undo. Exactly one, on the refusal — see {@link #addArmFault}. */
    private static final String ENGINE_ROLLBACK = "rollBackDiscardedCreate(";

    /** The add arm's own three negative answers, keyed on its own variable. */
    private static final String ADD_REFUSED =
            "addState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER";
    private static final String ADD_UNKNOWN =
            "addState == MlsWelcomeAdmission.ServerState.UNKNOWN";
    private static final String ADD_DIFFERS =
            "addState == MlsWelcomeAdmission.ServerState.DIFFERS";

    /**
     * The three arms, keyed on the comparison rather than on a log string.
     *
     * <p>Named individually because they are three different decisions with three different
     * remedies, and a guard that only counted returns could not tell which one had drifted.
     */
    private static final String ARM_REFUSED =
            "createState == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER";
    private static final String ARM_UNKNOWN =
            "createState == MlsWelcomeAdmission.ServerState.UNKNOWN";
    private static final String ARM_DIFFERS =
            "createState == MlsWelcomeAdmission.ServerState.DIFFERS";

    private static String establishBody() throws Exception {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "establishGroup",
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
        // The check must be asked about the group the ENGINE just built, not about one looked up in
        // the host store — a store lookup is exactly what cannot answer before the adoption.
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

        // THE CORE CLAIM. Every adoption is classified by ROLE and checked against the role's rule —
        // deliberately not by counting, and not by "the last one is the success one". The first cut
        // of this guard did exactly that and the mutation control caught it: a mutation that moves
        // the success adoption back above the check leaves the DIFFERS adoption as the last one, so
        // every positional assertion still passed while the defect was fully restored.
        final List<Integer> adopts = SourceScan.indicesOf(body, ADOPT);
        if (adopts.isEmpty()) {
            return "establishGroup no longer contains `" + ADOPT + "` — this guard's whole claim is "
                    + "about WHERE that statement sits and cannot be checked without it.";
        }
        // THE addMembers ARM IS NO LONGER IN THIS BODY, and this guard used to EXEMPT it.
        //
        // It is a different action (the engine kept the group, the era does not move, the bundle
        // holds an addMembers commit) and it had its own RPC and its own return — all true, and none
        // of it a reason to adopt before the authenticator was consulted, which is what it did. The
        // exemption here was an `if (a > addArmEnd) … continue;` carve-out: a branch that made this
        // guard silent about exactly the arm the same defect was living in.
        //
        // The arm moved to addMembersToExistingGroup, which runs the SAME check against the SAME
        // authenticator; {@link #addArmFault} pins that body. What is pinned HERE is only that
        // establishGroup still ROUTES to it — an arm re-inlined into this method would be outside
        // the body addArmFault watches — and, below, that NO adoption in this body precedes the
        // verification. There is no exempt adoption left.
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
                // NO EXEMPTION. Every adoption in this body is on the create path now.
                return "establishGroup adopts at offset " + a + ", BEFORE the verification at "
                        + CHECK + ". That is the broken ordering exactly: the host takes the group, "
                        + "and the check that could refuse it runs afterwards with all three of its "
                        + "arms returning -1 to a caller that is told the establish FAILED.";
            }
            if (a != lastAdopt) {
                // The DIFFERS arm's adoption lives in healOntoServerGroupOrUnadopt, where its undo
                // is beside it. One inlined here would be an adoption on the create path with no
                // rollback in the same body — the shape this is about.
                return "establishGroup adopts at offset " + a + ", between the verification and the "
                        + "success adoption. The only arm that may adopt early is DIFFERS, and it "
                        + "does so inside " + DIFFERS_ARM + " where its rollback sits beside it.";
            }
        }
        // The DIFFERS arm must still ROUTE somewhere that adopts-and-undoes rather than silently
        // doing neither: a bare `return -1` here leaves the caller correct and the group unrepaired.
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
                    + "which is 0286's state exactly.";
        }
        // The rollback must be on the NON-convergent path. Above the success return it would undo a
        // heal that worked.
        final int converged = body.indexOf("return healed;");
        if (converged < 0 || converged > rollback) {
            return DIFFERS_ARM + " does not return the converged era before it rolls back, so the "
                    + "undo is either unreachable or it discards a heal that succeeded.";
        }
        return null;
    }

    /**
     * The undo must give back what the adoption takes.
     *
     * <p>Deliberately a REVIEW TRIGGER rather than a proof: it pins that {@code adoptGroup} persists
     * through exactly the two helpers whose writes {@code rollBackAdoption} reverses, so a third
     * write cannot be added silently. It cannot show the reversal is complete — only that the
     * coupling has not drifted unnoticed.
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
        // A DIRECT store write inside adoptGroup would bypass both of those and therefore bypass the
        // undo. The two stores are named structurally, not by the helper that happens to wrap them.
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
                return "rollBackAdoption does not call " + undo + ". adoptGroup writes an in-memory "
                        + "row, a conversation→group alias and an MlsConversationRecord; an undo "
                        + "that leaves any one of them lets getGroup() reload the group and the "
                        + "conversation re-adopts itself.";
            }
        }
        // The undo must ANSWER the question the next establish will ask, not merely run its three
        // removals. A rollback that reports success without re-reading is the same defect one layer
        // down: a check whose output is identical whether or not it worked.
        if (!rollBackBody.contains("getGroup(")) {
            return "rollBackAdoption never re-reads getGroup(). Three void removals returning true "
                    + "reports that they RAN, not that they worked — and the whole cost of this "
                    + "was a -1 that meant something other than what the store held.";
        }
        return null;
    }

    /**
     * The addMembers arm's own body: ship the commit, VERIFY, and only then adopt.
     *
     * <h2>Why this arm is not covered by {@link #orderingFault}, and used to be EXEMPTED by it</h2>
     *
     * <p>The first fix covered {@code establishGroup}'s CREATE arm and deliberately stopped there. This
     * guard then wrote the addMembers arm's early adoption into itself as a carve-out — an
     * {@code if (a > addArmEnd) … continue;} that made the guard silent about exactly the arm the
     * same defect was still living in. Removing the carve-out and pinning the arm's own body is the
     * whole of the follow-up.
     *
     * <h2>The reachability that makes it a defect rather than an untidiness</h2>
     *
     * <p>{@code establishGroup} early-returns on {@code getGroup(key) != null}, and the engine only
     * plans an addMembers when it HOLDS the group ({@code plan_group} arm 3). So this arm runs
     * exactly when the ENGINE holds the group and the HOST does not — and every route into that
     * state is one where the host declined to own what the engine built:
     * {@code rollBackDiscardedCreate(gid, null)} after a refused create (which KEEPS the engine's
     * group), the {@code REFUSED_BY_LEDGER} and {@code UNKNOWN} arms, and
     * {@code healOntoServerGroupOrUnadopt} when the heal misses — that last one leaving the engine
     * holding a group the epoch authenticator has already shown is a fork.
     *
     * <p>So the check is warranted whatever the answer to <i>"can {@code addGroupUsersMls} return OK
     * while the server applies nothing?"</i> turns out to be. That question is open and needs a
     * capture; this guard does not rest on it.
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
                return "the addMembers arm tests `" + arm + "` BEFORE it calls " + CHECK + ", so it "
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
        // THE ENGINE IS TOUCHED ONCE, AND ONLY BY THE REFUSAL. A refusal is positive evidence the
        // server did not apply the commit, so the engine is put back. DIFFERS after an OK is NOT
        // that evidence — the fork may predate the add, and the DIFFERS remedy converges FROM the
        // engine's state. The invariant: the engine may hold more than the host; the host must
        // never hold what was not verified.
        final List<Integer> undos = SourceScan.indicesOf(body, ENGINE_ROLLBACK);
        if (undos.size() != 1) {
            return "the addMembers arm calls " + ENGINE_ROLLBACK + " " + undos.size() + " times. "
                    + "Exactly one, on the refusal: an engine rollback on a verification arm acts "
                    + "on an inference rather than on evidence, and it would delete the state the "
                    + "DIFFERS self-heal has to converge from.";
        }
        if (undos.get(0).intValue() > check) {
            return "the addMembers arm rolls the engine back AFTER " + CHECK + ". The only arm with "
                    + "evidence the server applied nothing is the RPC refusal, which is above it.";
        }
        return null;
    }

    private static String addArmBody() throws Exception {
        final String body = SourceScan.bodyOf(SourceScan.transport(),
                "addMembersToExistingGroup");
        assertTrue("addMembersToExistingGroup was not found in " + SourceScan.TRANSPORT
                + " — it is establishGroup's addMembers arm, and this guard must follow it rather "
                + "than silently pass", body.length() > 500);
        return body;
    }

    private static String differsArmBody() throws Exception {
        final String body = SourceScan.bodyOf(
                SourceScan.transport(), "healOntoServerGroupOrUnadopt");
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

    /**
     * THE CONTROL for the addMembers arm. Break the real body five ways and require the guard to
     * name each.
     *
     * <p>The first mutation reconstructs the defect exactly — the adoption back on the
     * line after the RPC's {@code ok()}, which is where it sat from the day the arm was written
     * until the fix.
     */
    @Test
    public void theAddArmGuardCanFail() throws Exception {
        final String body = addArmBody();
        assertNull("precondition: the real arm must pass before a mutation proves anything",
                addArmFault(body));

        // (1) THE ORIGINAL DEFECT, rebuilt: the adoption pulled back above the verification.
        assertEquals("the adoption must be unique for the mutation to be anchored",
                1, SourceScan.count(body, ADOPT));
        final String checkStmt = "final MlsWelcomeAdmission.ServerState addState = " + CHECK;
        assertEquals("the check anchor must be unique", 1, SourceScan.count(body, checkStmt));
        final String preFix = body.replace(ADOPT, "").replace(checkStmt, ADOPT + " " + checkStmt);
        final String faultA = addArmFault(preFix);
        assertNotNull("moving the adoption above the check must fail this guard", faultA);
        assertTrue("and must say it adopts before the verification: " + faultA,
                faultA.contains("BEFORE the verification"));

        // (2) The check deleted outright — the arm back to trusting ar.ok().
        final String faultB = addArmFault(body.replace(CHECK, "trustTheRpc("));
        assertNotNull("deleting the verification must fail this guard", faultB);
        assertTrue("and must say it never asks: " + faultB, faultB.contains("never calls"));

        // (3) The store-resolving sibling swapped in — on this arm it cannot answer at all, because
        // the host holds no group here by construction.
        final String faultC = addArmFault(body.replace(CHECK, STORE_RESOLVING_CHECK));
        assertNotNull("swapping in the store-resolving check must fail this guard", faultC);

        // (4) The DIFFERS answer stops routing into the adopt-then-undo helper.
        final String faultD = addArmFault(body.replace(DIFFERS_ARM, "doNothingAbout("));
        assertNotNull("deleting the DIFFERS delegation must fail this guard", faultD);
        assertTrue("and must name the helper: " + faultD, faultD.contains(DIFFERS_ARM));

        // (5) An ENGINE rollback added to a verification arm. It reads as tidying up and it is the
        // one write that must not happen there: DIFFERS after an OK is not evidence the server
        // applied nothing, and the state being deleted is what the self-heal converges from.
        final String differsReturn = "return " + DIFFERS_ARM;
        assertEquals("the DIFFERS return must be unique", 1,
                SourceScan.count(body, differsReturn));
        final String faultE = addArmFault(body.replace(differsReturn,
                ENGINE_ROLLBACK + "gidBytes, preCreate); " + differsReturn));
        assertNotNull("an engine rollback on a verification arm must fail this guard", faultE);
        assertTrue("and must name the rollback: " + faultE, faultE.contains(ENGINE_ROLLBACK));
    }

    /** THE CONTROL for the DIFFERS arm. */
    @Test
    public void theDiffersArmGuardCanFail() throws Exception {
        final String body = differsArmBody();
        assertNull("precondition: the real arm must pass", differsArmFault(body));

        // The undo deleted — the fork stays adopted when the heal misses.
        final String noUndo = body.replace(ROLLBACK, "neverRolledBack(");
        assertNotNull("deleting the rollback must fail this guard", differsArmFault(noUndo));

        // The snapshot taken after the adoption — it then reads the adoption's own writes and the
        // undo removes nothing. Every membership test still passes; the outcome is the defect.
        final int cap = body.indexOf(CAPTURE);
        final int semi = body.indexOf(';', cap);
        final String snapshotStmt = body.substring(body.lastIndexOf('\n', cap) + 1, semi + 1);
        assertEquals("the snapshot statement must be unique for the mutation to be anchored",
                1, SourceScan.count(body, snapshotStmt));
        final String moved = body.replace(snapshotStmt, "")
                .replace("final int healed = selfHeal(", snapshotStmt + " final int healed = selfHeal(");
        final String faultLate = differsArmFault(moved);
        assertNotNull("taking the snapshot after the adoption must fail this guard", faultLate);
        assertTrue("and must say the snapshot comes too late: " + faultLate,
                faultLate.contains(CAPTURE));

        // The heal removed — the adoption then has no justification at all.
        assertNotNull("deleting the heal must fail this guard",
                differsArmFault(body.replace("selfHeal(", "noHeal(")));
    }

    /** Production holds the coupling property. */
    @Test
    public void theUndoGivesBackWhatTheAdoptionTakes() throws Exception {
        final String src = SourceScan.transport();
        final String adopt = SourceScan.bodyOfDeclaredAs(src, ADOPT_DECL);
        final String undo = SourceScan.bodyOf(src, "rollBackAdoption");
        assertTrue("adoptGroup was not found in " + SourceScan.TRANSPORT, adopt.length() > 200);
        assertTrue("rollBackAdoption was not found in " + SourceScan.TRANSPORT
                + " — the DIFFERS arm's undo is the fallback half", undo.length() > 200);
        final String fault = couplingFault(adopt, undo);
        if (fault != null) fail(fault);
    }

    /**
     * THE CONTROL. Break the real body four ways and require the guard to name each.
     *
     * <p>Every mutation is applied to production text rather than to a hand-written sample, so what
     * is proven is that this guard would catch the regression on the code it actually watches. The
     * first mutation reconstructs the ORIGINAL defect — the state 0286 is in.
     */
    @Test
    public void theGuardCanFail() throws Exception {
        final String body = establishBody();
        assertNull("precondition: the real body must pass before a mutation proves anything",
                orderingFault(body));

        // (1) THE ORIGINAL DEFECT, rebuilt: the success adoption moved back above the check, so all
        // three negative arms run with the group already adopted.
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

        // (1b) The same defect arriving by ADDITION rather than by movement — the old line restored
        // without the new one being removed, so the host adopts early AND late. The success
        // adoption is still last, so the rule above cannot see it; this is the rule that can.
        final String duplicated = body.replace(anchor, ADOPT + " " + anchor);
        final String faultAb = orderingFault(duplicated);
        assertNotNull("re-adding an adoption above the check must fail this guard", faultAb);
        assertTrue("and must say it precedes the verification: " + faultAb,
                faultAb.contains("BEFORE the verification"));

        // (2) The store-resolving check swapped back in — the version that can only answer UNKNOWN
        // for a group the host has not adopted, i.e. a check that cannot fail.
        final String resolving = body.replace(CHECK, STORE_RESOLVING_CHECK);
        final String faultB = orderingFault(resolving);
        assertNotNull("swapping in the store-resolving check must fail this guard", faultB);
        assertTrue("and must say the check was never called: " + faultB,
                faultB.contains("never calls"));

        // (3) The DIFFERS arm stops routing into the adopt-then-undo helper and just returns -1.
        // The caller is then told the truth and the conversation is left unrepaired — and, worse,
        // the arm that was the reason the early adoption existed silently stops being one.
        final String noDelegate = body.replace(DIFFERS_ARM, "doNothingAbout(");
        final String faultC = orderingFault(noDelegate);
        assertNotNull("deleting the DIFFERS delegation must fail this guard", faultC);
        assertTrue("and must name the helper: " + faultC, faultC.contains(DIFFERS_ARM));

        // (4) An adoption inlined back into the create path between the check and the success —
        // an adoption with no rollback in the same body, which is the defect one arm over.
        final String inlined = body.replace("if (createState == MlsWelcomeAdmission.ServerState.DIFFERS) {",
                "if (createState == MlsWelcomeAdmission.ServerState.DIFFERS) { " + ADOPT);
        final String faultD = orderingFault(inlined);
        assertNotNull("inlining an adoption into the create path must fail this guard", faultD);
        assertTrue("and must say where it sits: " + faultD,
                faultD.contains("between the verification and the success adoption"));
    }

    /** THE CONTROL for the coupling half. */
    @Test
    public void theCouplingGuardCanFail() throws Exception {
        final String src = SourceScan.transport();
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
