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

import static com.android.messaging.rcs.SourceScan.bodyOf;
import static com.android.messaging.rcs.SourceScan.codeOnly;
import static com.android.messaging.rcs.SourceScan.count;
import static com.android.messaging.rcs.SourceScan.read;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * <b>A UI rename of an MLS group must go out as the RCC.16 §9.7.1.5 ENCRYPTED SUBJECT, not as the
 * bare {@code ChangeGroupProfile} display name</b>.
 *
 * <h2>The defect class this pins, which is the reason it is worth a guard at all</h2>
 *
 * <p>{@code MlsProviderTransport.changeGroupSubject} implemented this whole flow, device-verified
 * end to end, until 2026-09-13 — <b>and its only caller in the tree was
 * {@code RcsDebugSendReceiver --es changesubj}</b>. We could RECEIVE an encrypted title and not SEND
 * one. That is the same shape as {@code sendToGroup} (debug-only while the UI sent
 * plaintext) and two others: a built, correct, sometimes device-verified MLS verb
 * reachable only from a debug broadcast, while the UI is wired to a plaintext sibling. A sweep of
 * every public verb on the transport found four such instances. <b>A correct mechanism nothing calls
 * is the shape the defect already had</b>, so what needs pinning is not the flow but the WIRING.
 *
 * <h2>Why a source scan</h2>
 *
 * <p>The standing caution on {@link SourceScan} applies, for the usual reason: both
 * {@code ManageRcsGroupAction} and {@code MlsProviderTransport} need a {@code Context}, a bound
 * provider and a {@code messaging.db}, so neither has a host test and the property is otherwise
 * unreachable. Every assertion below keys on an INVOKED NAME and counts its hits, so a pattern that
 * has gone stale fails instead of passing on nothing.
 *
 * <h2>The falsifier</h2>
 *
 * <p>Run this against the revision before the routing landed, for either file, and it goes red in both
 * directions: {@code renameGroupRouting} took ONE argument and never mentioned
 * {@code changeGroupSubject}, and the {@code OP_RENAME} arm returned {@code Boolean.FALSE} for
 * every non-{@code PLAINTEXT} verdict without ever calling {@code applyLocalMirror}. Checked before
 * landing — see {@code theseAssertionsWouldHaveFailedBeforeTheFix} for the ones whose failing state
 * is named rather than merely asserted.
 */
public class MlsRenameRoutingGuardTest {

    private static final String ACTION =
            "src/com/android/messaging/datamodel/action/ManageRcsGroupAction.java";
    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    /**
     * The UI arm asks the transport and passes the NAME — the decision is not made at the call site.
     *
     * <p>The two-argument call is the load-bearing half. Before the fix the arm asked
     * {@code renameGroupRouting(groupId)} with no name, because the only answer it could act on was
     * "refuse or send it in the clear"; a one-argument call today would mean the routing had been
     * reverted to that.
     */
    @Test
    public void theRenameArmRoutesThroughTheTransportAndHandsItTheName() throws IOException {
        final String arm = renameArm();
        assertEquals("the OP_RENAME arm no longer asks the transport how to route the rename — the "
                + "decision that was moved into the layer that owns the state is back at the call site",
                1, count(arm, ".renameGroupRouting("));
        // THE ARGUMENTS READ AS ARGUMENTS, not as a substring of the call. `contains("…(groupId, "
        // + "name)")` would also be satisfied by a call that passed them to something else on the
        // same line, and would break on a reformat that wrapped the argument list — brittle and
        // weak at once, which is the combination worth avoiding.
        final java.util.List<String> args = SourceScan.topLevelArguments(
                SourceScan.argumentListAt(arm, arm.indexOf(".renameGroupRouting(")));
        assertEquals("renameGroupRouting is called without the new name, so the arm can only refuse "
                + "or leak — the one-argument form is the old shape",
                java.util.Arrays.asList("groupId", "name"), args);
    }

    /**
     * <b>The mirror on {@code APPLIED} is not optional here, and that is the trap.</b>
     *
     * <p>{@code MlsSubjectApplier} — what turns a decrypted §9.7.1.5 subject into the conversation's
     * name — can never fire for the RENAMER: the key rides in an MLS private message and MLS gives
     * you no way to decrypt your own. On every other routed op the local write races an inbound echo
     * that also arrives; on this one it is the ONLY thing that ever shows the new title to the person
     * who typed it. Dropping it leaves them looking at the old name after a rename that succeeded,
     * an appearance that cost a wrong diagnosis once already.
     */
    @Test
    public void theRenameArmWritesTheLocalMirrorWhenTheSubjectChangeWasApplied() throws IOException {
        final String arm = renameArm();
        assertEquals("the OP_RENAME arm does not write the local mirror. The sender cannot decrypt "
                + "its own subject, so MlsSubjectApplier will never fire for it and NOTHING else "
                + "will ever show the renamer the new name.",
                1, count(arm, "applyLocalMirror(db, conversationId, groupId, subId, op"));
        // THE MIRROR MUST BE INSIDE THE `if (applied)` BLOCK, not merely in the same arm as the
        // word APPLIED. This asserted `arm.contains("GroupMembershipRouting.APPLIED")` until
        // 2026-09-14 — the MEMBERSHIP form, proving co-presence while the test's own NAME claims a
        // RELATIONSHIP. Move the write outside the branch and both tokens are still there, so the
        // old assertion passed on the exact regression it names: measured, not reasoned about.
        //
        // MlsSelfDepartureGuardTest has checked its own arms this way all along
        // (`mlsArm.indexOf("applyLocalMirror(", applied) > applied`); this guard was the weaker
        // sibling in the same package, which is the tell worth recording — a neighbour already
        // solving it correctly is the cheapest review anyone gets, and I did not take it.
        final int applied = arm.indexOf("if (applied)");
        assertTrue("the OP_RENAME arm no longer gates on `applied`, so it mirrors whatever the "
                + "transport answered — including a REFUSED subject change, which would display a "
                + "name that was never sent", applied >= 0);
        final String gated = blockAfter(arm, applied);
        assertTrue("the `if (applied)` block could not be brace-matched", gated.length() > 0);
        assertEquals("the OP_RENAME arm's mirror is OUTSIDE its `if (applied)` block, so a rename "
                + "the server never took is written to messaging.db as the conversation's name — "
                + "the contract every arm has kept since the routing landed",
                1, count(gated, "applyLocalMirror(db, conversationId, groupId, subId, op"));
        assertEquals("the arm stopped redrawing the thread, so the mirrored name will not appear "
                + "until something else happens to refresh the cursor",
                1, count(arm, "MessagingContentProvider.notifyMessagesChanged(conversationId)"));
    }

    /**
     * The transport sends the ENCRYPTED SUBJECT, and does not reach for the bare display name.
     */
    @Test
    public void renameGroupRoutingSendsTheEncryptedSubject() throws IOException {
        final String body = renameGroupRouting();
        assertEquals("renameGroupRouting no longer calls changeGroupSubject, so the §9.7.1.5 flow "
                + "is UI-unreachable again and only the debug broadcast can send an encrypted title",
                1, count(body, "changeGroupSubject("));
        assertFalse("renameGroupRouting calls the bare renameGroup itself. The PLAINTEXT verdict is "
                + "the caller's instruction to do that; making the call here would send the display "
                + "name at fxax.1 from the one method whose job is to prevent exactly that.",
                body.contains("renameGroup(mSubId") || body.contains("pt(\"renameGroup\")"));
        // THE SECOND ARGUMENT, READ AS AN ARGUMENT — not as the text "/*peerE164=*/ null", which is
        // what the first cut asserted and which CANNOT MATCH: codeOnly() blanks comments, so the
        // marker is whitespace by the time it gets here. A needle that can never match is a check
        // that cannot fail, and this one announced itself by going red on correct code.
        final java.util.List<String> args = SourceScan.topLevelArguments(
                SourceScan.argumentListAt(body, body.indexOf("changeGroupSubject(")));
        assertEquals("changeGroupSubject's arity changed under this guard", 4, args.size());
        assertEquals("the peerE164 argument is no longer null. For a group canonicalKey() ignores it "
                + "entirely, so passing a member's number would look like it addressed something — "
                + "groupPlane's own rule.",
                "null", args.get(1));
    }

    /**
     * Routing did not weaken the original refusal: the two planes that are not {@code MLS} and not
     * {@code PLAINTEXT} still send nothing.
     *
     * <p>{@code UNKNOWN} and {@code MLS_LOCKED_OUT} refuse for different reasons — one cannot
     * classify the conversation, the other holds no group to build a commit in — and neither may
     * take the bare RPC.
     *
     * <p><b>Asserted as "nothing PAST the guard returns PLAINTEXT", not as a COUNT of PLAINTEXT
     * returns, and the difference is not stylistic.</b> The first cut counted them and demanded
     * exactly two. It went red within the hour — correctly, on correct code — when a concurrent
     * change added a {@code GroupPlane.MLS_DOWNGRADED} arm that legitimately returns
     * {@code PLAINTEXT}: a downgraded conversation genuinely wants the bare rename. A count over a
     * set that is DESIGNED to grow encodes today's arm list as the invariant. The real invariant is
     * positional — once we are past {@code plane != GroupPlane.MLS}, the conversation IS encrypted,
     * and no path from there may send the name in the clear.
     */
    @Test
    public void nothingPastTheMlsGuardCanReturnToPlaintext() throws IOException {
        final String body = renameGroupRouting();
        final int guard = body.indexOf("plane != GroupPlane.MLS)");
        assertTrue("renameGroupRouting no longer funnels every non-MLS plane through one guard, so "
                + "a conversation we cannot classify may now reach the encrypted-subject arm — or "
                + "worse, the bare rename", guard > 0);
        final String refusal = blockAfter(body, guard);
        assertTrue("the non-MLS guard's block could not be brace-matched", refusal.length() > 0);
        assertTrue("the non-MLS guard stopped REFUSING — UNKNOWN and MLS_LOCKED_OUT must send "
                + "nothing at all", refusal.contains("GroupMembershipRouting.REFUSED"));
        final String past = body.substring(body.indexOf(refusal, guard) + refusal.length());
        assertEquals("a path PAST the non-MLS guard returns PLAINTEXT. Everything past that point "
                + "is an MLS conversation by construction, so a PLAINTEXT verdict there tells the "
                + "caller to put an encrypted group's name on the server in the clear.",
                0, count(past, "GroupMembershipRouting.PLAINTEXT"));
        assertEquals("the encrypted-subject send is no longer past the guard", 1,
                count(past, "changeGroupSubject("));
    }

    /**
     * <b>Naming the state that would make the two headline assertions FAIL, and confirming it is
     * reachable.</b> A check that cannot fail is not evidence, and these are source scans, whose
     * commonest failure is a needle that silently stopped matching anything.
     *
     * <p>So: assert the needles are absent from the code they must NOT match. If
     * {@code changeGroupSubject(} appeared in {@code changeGroupMembership}, or
     * {@code renameGroupRouting(} in the {@code OP_LEAVE} arm, the counts above would be matching
     * something other than what their messages claim.
     */
    @Test
    public void theseAssertionsWouldHaveFailedBeforeTheFix() throws IOException {
        final String transport = codeOnly(read(TRANSPORT));
        assertEquals("changeGroupSubject is called from changeGroupMembership, so the count in "
                + "renameGroupRoutingSendsTheEncryptedSubject is not isolating the rename",
                0, count(bodyOf(transport, "changeGroupMembership"), "changeGroupSubject("));
        final String action = codeOnly(read(ACTION));
        final String execute = bodyOf(action, "executeAction");
        assertTrue("executeAction is gone from ManageRcsGroupAction", execute.length() > 0);
        assertEquals("renameGroupRouting is called more than once in executeAction, so the arm "
                + "scan is not isolating the rename arm",
                1, count(execute, ".renameGroupRouting("));
    }

    /**
     * The {@code OP_RENAME} arm's own brace-matched block.
     *
     * <p><b>The arity check is not defensive clutter — without it this helper is a SCOPE LOSS
     * waiting to happen.</b> It reads the FIRST {@code op == OP_RENAME} in {@code executeAction};
     * if a second rename arm is ever added beside it (a downgraded-rename arm, say), every
     * assertion built on this would go on passing while covering only half its subject, and would
     * stay GREEN doing it. That is worse than the count rot this class already documents: a count
     * narrows LOUDLY on the next addition, a scope loss narrows SILENTLY.
     *
     * <p>Named after {@code theGateAsksTheEngineTheSameQuestionsTheSeal
     * PathAsks} did exactly this — it kept asserting parity over the GROUP leg after
     * {@code sealCapability} grew a 1:1 one, and nothing went red (fixed in {@code 5dce73e4}). The
     * general form, which is the durable rule: <b>any guard whose subject is a SET — arms, planes,
     * legs, call sites — must derive its expectation from that set, or it narrows as the set
     * grows.</b> One line converts the silent narrowing into a loud failure.
     */
    private static String renameArm() throws IOException {
        final String execute = bodyOf(codeOnly(read(ACTION)), "executeAction");
        assertTrue("executeAction is gone from ManageRcsGroupAction", execute.length() > 0);
        assertEquals("executeAction has more than one OP_RENAME arm (or none). This helper reads "
                + "the FIRST, so a second arm would leave every assertion in this class silently "
                + "covering half its subject while staying green. Give the new arm its own "
                + "assertions and make this read both — do not just widen this number.",
                1, count(execute, "op == OP_RENAME"));
        final int at = execute.indexOf("op == OP_RENAME");
        assertTrue("the OP_RENAME arm is gone — the rename is unrouted again", at > 0);
        final String arm = blockAfter(execute, at);
        assertTrue("the OP_RENAME arm's block could not be brace-matched", arm.length() > 0);
        return arm;
    }

    private static String renameGroupRouting() throws IOException {
        final String body = bodyOf(codeOnly(read(TRANSPORT)), "renameGroupRouting");
        assertTrue("MlsProviderTransport.renameGroupRouting is gone", body.length() > 0);
        return body;
    }

    /**
     * The brace-matched block that OPENS after {@code at}, or empty when the arm is braceless.
     *
     * <p>The {@code ;}-before-{@code &#123;} bail matters: without it a braceless arm
     * silently borrows the NEXT block, and the guard then asserts over code that is not the arm.
     * Copied rather than shared, as the four neighbouring guards each do.
     */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        final int stop = src.indexOf(';', at);
        if (stop >= 0 && stop < open) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }
}
