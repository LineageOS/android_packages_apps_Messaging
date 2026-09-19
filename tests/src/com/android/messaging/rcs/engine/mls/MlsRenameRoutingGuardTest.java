/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A UI rename of an MLS group goes out as the RCC.16 §9.7.1.5 encrypted subject, not as the bare
 * {@code ChangeGroupProfile} display name. The flow is tested elsewhere; this pins the wiring,
 * since a correct MLS verb reachable only from a debug broadcast while the UI calls a plaintext
 * sibling is the defect shape. {@code ManageRcsGroupAction} and the transport have no host test, so
 * the scan keys on invoked names and counts its hits. See docs/rcs/groups.md.
 */
public class MlsRenameRoutingGuardTest {

    private static final String ACTION =
            "src/com/android/messaging/datamodel/action/ManageRcsGroupAction.java";
    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    /**
     * The UI arm asks the transport with the group and the name; the decision is not made at the
     * call site.
     */
    @Test
    public void theRenameArmRoutesThroughTheTransportAndHandsItTheName() throws IOException {
        final String arm = renameArm();
        assertEquals("the OP_RENAME arm no longer asks the transport how to route the rename — the "
                + "decision that was moved into the layer that owns the state is back at the call site",
                1, count(arm, ".renameGroupRouting("));
        // Read as arguments, not a substring: a substring match accepts the same text passed
        // elsewhere on the line and breaks on a rewrap.
        final java.util.List<String> args = SourceScan.topLevelArguments(
                SourceScan.argumentListAt(arm, arm.indexOf(".renameGroupRouting(")));
        assertEquals(
                "renameGroupRouting is called without the new name, so the arm can only refuse "
                + "or leak — the one-argument form is the old shape",
                java.util.Arrays.asList("groupId", "name"), args);
    }

    /**
     * The local mirror on an applied rename is required: it writes the renamer's self-attributed
     * status line. The name itself the engine applies on acceptance
     * ({@code MlsGroupMetadataSplitTest}), since the renamer cannot decrypt their own subject.
     */
    @Test
    public void theRenameArmWritesTheLocalMirrorWhenTheSubjectChangeWasApplied()
            throws IOException {
        final String arm = renameArm();
        assertEquals("the OP_RENAME arm does not write the local mirror, so the renamer gets no "
                + "'You renamed' status line: no inbound writes one for their own subject, which "
                + "they cannot decrypt.",
                1, count(arm, "applyLocalMirror(db, conversationId, groupId, subId, op"));
        // The mirror is inside the `if (applied)` block, asserted by position; co-presence of the
        // two tokens would survive moving the write out of the branch.
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

    /** The transport sends the encrypted subject and does not reach for the bare display name. */
    @Test
    public void renameGroupRoutingSendsTheEncryptedSubject() throws IOException {
        final String body = renameGroupRouting();
        assertEquals("renameGroupRouting no longer calls changeGroupSubject, so the §9.7.1.5 flow "
                + "is UI-unreachable again and only the debug broadcast can send an encrypted title",
                1, count(body, "changeGroupSubject("));
        assertFalse(
                "renameGroupRouting calls the bare renameGroup itself. The PLAINTEXT verdict is "
                + "the caller's instruction to do that; making the call here would send the display "
                + "name at fxax.1 from the one method whose job is to prevent exactly that.",
                body.contains("renameGroup(mSubId") || body.contains("pt(\"renameGroup\")"));
        // The second argument read as an argument: codeOnly() blanks the argument-name comment.
        final java.util.List<String> args = SourceScan.topLevelArguments(
                SourceScan.argumentListAt(body, body.indexOf("changeGroupSubject(")));
        assertEquals("changeGroupSubject's arity changed under this guard", 4, args.size());
        assertEquals(
                "the peerE164 argument is no longer null. For a group canonicalKey() ignores it "
                + "entirely, so passing a member's number would look like it addressed something — "
                + "groupPlane's own rule.",
                "null", args.get(1));
    }

    /**
     * The non-MLS, non-PLAINTEXT planes ({@code UNKNOWN}, {@code MLS_LOCKED_OUT}) still send
     * nothing. Asserted as "nothing past {@code plane != GroupPlane.MLS} returns PLAINTEXT" rather
     * than as a count, because the set of PLAINTEXT arms legitimately grows
     * ({@code MLS_DOWNGRADED}).
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
     * The needles are absent from code they must not match, so the counts above match what their
     * messages claim.
     */
    @Test
    public void theseAssertionsWouldHaveFailedBeforeTheFix() throws IOException {
        // The unsplit view: on the shell this absence check would pass on a delegate whatever the
        // moved body did.
        final String transport = SourceScan.transportUnsplitCode();
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
     * The {@code OP_RENAME} arm's brace-matched block. The arity check makes a second rename arm
     * fail loudly instead of leaving every assertion here covering half its subject.
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
        final String body = bodyOf(SourceScan.transportUnsplitCode(), "renameGroupRouting");
        assertTrue("MlsProviderTransport.renameGroupRouting is gone", body.length() > 0);
        return body;
    }

    /**
     * The brace-matched block that opens after {@code at}, or empty when the arm is braceless; the
     * {@code ;}-before-brace bail stops a braceless arm borrowing the next block.
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
