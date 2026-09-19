/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leaving a group is gated by the kill switch and nothing else. The other {@code MlsPeerGuard}
 * tiers would keep a person in a group they asked to leave: the peer allowlist refuses a group with
 * a peer outside it, and the peer-health streak refuses exactly when a peer looks wedged. The
 * transport and the guard need a {@code Context}, so this is a source guard. See
 * docs/rcs/groups.md.
 */
public final class MlsSelfDepartureGuardTest {

    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    @Test
    public void leavingIsGatedByTheKillSwitchAndNothingElse() throws IOException {
        // The unsplit view: leaveGroup moved to MlsMembership, its guard call spelled back.
        final String transport = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        final String body = bodyOf(transport, "leaveGroup");
        assertTrue("MlsProviderTransport.leaveGroup is gone — the menu row has nothing to call",
                body.length() > 0);
        assertTrue("leaveGroup does not ask MlsPeerGuard at all. The kill switch must stop our own "
                + "departure too: a self-leave transmits a SelfRemove proposal, and G6 exists so "
                + "that one sysprop stops every outbound MLS state change.",
                body.contains("MlsPeerGuard.allowSelfDeparture("));
        assertFalse(
                "leaveGroup reaches for allowStateChange because it is nearest. Its peer-health "
                + "tier refuses a departure exactly when the peer looks wedged — which is when a "
                + "person most wants out — and for a group it refuses on ONE member's streak.",
                body.contains("allowStateChange("));
        assertFalse("leaveGroup reaches for the peer allowlist. It would refuse our own escape "
                + "from a group containing a peer outside the allowlist, which is backwards.",
                body.contains("allowJoiningPeer(") || body.contains("allowDebugStateChange("));
    }

    /**
     * The entry point routes to {@code Tier.SELF_DEPARTURE} and no other. What that tier consults
     * is tested behaviourally in
     * {@link MlsStateChangeGateTest#leavingIsGatedByTheKillSwitchAndNothingElse}.
     */
    @Test
    public void theSelfDepartureGateReadsOnlyTheFreeze() throws IOException {
        final String guard = codeOnly(read("src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java"));
        final String body = bodyOf(guard, "allowSelfDeparture");
        assertTrue("MlsPeerGuard.allowSelfDeparture is gone", body.length() > 0);
        assertTrue("allowSelfDeparture no longer names Tier.SELF_DEPARTURE. Whatever tier it takes "
                + "now, it is not the one whose row is G6 alone — and the two nearest alternatives "
                + "both refuse a person's escape from a group with a peer that has stopped "
                + "answering.", body.contains("Tier.SELF_DEPARTURE"));
        assertFalse("allowSelfDeparture names a second tier, so which guards apply to a departure "
                + "depends on something this file cannot see", body.contains("Tier.ORGANIC")
                || body.contains("Tier.ALLOWLISTED") || body.contains("Tier.ERA_ADVANCE"));
        assertFalse("it consults the peer allowlist", body.contains("allowedPeers("));
        assertFalse("it consults the peer-health record", body.contains("healthKey("));
    }

    /**
     * The MLS arm writes nothing locally: {@code leave()} already wrote the terminal mark, the
     * encryption bit and the "You left" line, and a second write would collapse into the first only
     * by coincidence.
     */
    @Test
    public void theLeaveArmDoesNotWriteASecondOptimisticLine() throws IOException {
        final String action = codeOnly(
                read("src/com/android/messaging/datamodel/action/ManageRcsGroupAction.java"));
        final String body = bodyOf(action, "executeAction");
        assertTrue("executeAction is gone", body.length() > 0);
        final int arm = body.indexOf("op == OP_LEAVE");
        assertTrue("the OP_LEAVE arm is gone — Leave group is unrouted again", arm > 0);
        assertTrue("the shared optimistic write is gone and this check means nothing",
                body.indexOf("applyLocalMirror(db, conversationId, groupId, subId, op", arm) > 0);
        // The arm's own brace-matched block, not the span up to the shared write below it.
        final String leaveArm = blockAfter(body, arm);
        assertTrue("the OP_LEAVE arm's block could not be brace-matched", leaveArm.length() > 0);
        assertFalse("the OP_LEAVE arm calls applyLocalMirror. On the MLS path leave() has already "
                + "written the whole mirror; on the plaintext path buildSelfStatusText would render "
                + "\"You removed You\" and removeGroupParticipants skips the self participant.",
                leaveArm.contains("applyLocalMirror("));
        assertTrue(
                "the OP_LEAVE arm does not route through the transport, so it is choosing an RPC "
                + "for itself — the decision belongs in the layer that owns the state",
                leaveArm.contains(".leaveGroup(groupId)"));
        assertTrue("the plaintext arm no longer tells the thread anything, so a leave on a "
                + "non-encrypted group is silent — the server fans its push to the members who "
                + "REMAIN, so nothing else will",
                leaveArm.contains("GroupDepartureApplier.apply("));
    }

    /**
     * No arm of {@code executeAction} writes the local mirror before the RPC it mirrors. A write
     * before a refused change would leave the local roster ahead of the server's, hiding the
     * failure. Checked per arm, since the arms return independently.
     */
    @Test
    public void noArmMirrorsLocallyBeforeTheRpcItMirrors() throws IOException {
        final String action = codeOnly(
                read("src/com/android/messaging/datamodel/action/ManageRcsGroupAction.java"));
        final String body = bodyOf(action, "executeAction");
        assertTrue("executeAction is gone", body.length() > 0);

        // The rename to applyLocalMirror is asserted: a call spelled applyOptimistic is the defect.
        assertFalse("executeAction calls applyOptimistic. No caller is optimistic any more — the "
                + "MLS arm mirrors on APPLIED, the plaintext arm on a true RPC result, the leave "
                + "arm not at all — so the name is a description of a design this file abandoned.",
                action.contains("applyOptimistic("));

        int mirrors = 0;
        for (int i = body.indexOf("applyLocalMirror("); i >= 0;
                i = body.indexOf("applyLocalMirror(", i + 1)) {
            mirrors++;
        }
        // The count is the zero guard for the ordering assertions below; each site also has its
        // own. A new arm gets its own ordering assertion and bumps this count.
        assertEquals("executeAction no longer calls applyLocalMirror at all, or calls it from a "
                + "place that has not been given the ordering. FOUR sites is the architecture: the "
                + "MLS add/remove arm on APPLIED, the MLS rename arm on APPLIED, the "
                + "MLS icon arm on APPLIED, and the plaintext arm on a true RPC "
                + "result. The leave arm deliberately has none. If you are here because you added "
                + "an arm, give it its own ordering assertion below and bump this number — do not "
                + "widen this into a range.",
                4, mirrors);

        // The MLS arm mirrors only on APPLIED. The routing enum is in MlsTransportTypes.
        final int mlsAt = body.indexOf("routed != MlsTransportTypes.GroupMembershipRouting"
                + ".PLAINTEXT");
        assertTrue("the MLS add/remove routing arm is gone — the re-route has been undone",
                mlsAt > 0);
        final String mlsArm = blockAfter(body, mlsAt);
        assertTrue("the MLS arm's block could not be brace-matched", mlsArm.length() > 0);
        final int applied = mlsArm.indexOf("if (applied)");
        assertTrue("the MLS arm no longer gates on `applied`, so it mirrors whatever the transport "
                + "answered — including REFUSED", applied >= 0);
        assertTrue("the MLS arm mirrors OUTSIDE its `if (applied)` block, so a REFUSED membership "
                + "change is written to messaging.db as though the server had taken it",
                mlsArm.indexOf("applyLocalMirror(", applied) > applied);

        // The MLS rename arm mirrors only on APPLIED: the renamer cannot decrypt its own RCC.16
        // §9.7.1.5 subject, so this write is the only thing that shows the new name, and an ungated
        // one would show a name that was never sent.
        final int renameAt = body.indexOf("op == OP_RENAME");
        assertTrue("the OP_RENAME arm is gone — the rename is unrouted again", renameAt > 0);
        final String renameArm = blockAfter(body, renameAt);
        assertTrue("the OP_RENAME arm's block could not be brace-matched", renameArm.length() > 0);
        final int renameApplied = renameArm.indexOf("if (applied)");
        assertTrue("the OP_RENAME arm no longer gates on `applied`, so it mirrors whatever the "
                + "transport answered — including a REFUSED subject change", renameApplied >= 0);
        assertTrue("the OP_RENAME arm mirrors OUTSIDE its `if (applied)` block, so a rename the "
                + "server never took is written to messaging.db as the conversation's name",
                renameArm.indexOf("applyLocalMirror(", renameApplied) > renameApplied);

        // The MLS icon arm, for the same reason: the sender cannot decrypt its own RCC.16 §9.7.1.4
        // icon key, and a stale avatar cannot be told from a current one.
        final int iconAt = body.indexOf("op == OP_CHANGE_ICON");
        assertTrue("the OP_CHANGE_ICON arm is gone — the icon change is unrouted again",
                iconAt > 0);
        final String iconArm = blockAfter(body, iconAt);
        assertTrue("the OP_CHANGE_ICON arm's block could not be brace-matched",
                iconArm.length() > 0);
        final int iconApplied = iconArm.indexOf("if (applied)");
        assertTrue("the OP_CHANGE_ICON arm no longer gates on `applied`, so it mirrors whatever "
                + "the transport answered — including a REFUSED icon change", iconApplied >= 0);
        assertTrue("the OP_CHANGE_ICON arm mirrors OUTSIDE its `if (applied)` block, so an icon "
                + "the server never took is written to the conversation as its photo",
                iconArm.indexOf("applyLocalMirror(", iconApplied) > iconApplied);

        // The plaintext arm: RPC first, then the mirror, gated on the result.
        final int rpc = body.indexOf("transport.renameGroup(");
        assertTrue("the plaintext RPC switch is gone and this ordering check means nothing",
                rpc > 0);
        final int lastMirror = body.lastIndexOf("applyLocalMirror(");
        assertTrue("the plaintext arm mirrors BEFORE it fires the RPC (mirror at " + lastMirror
                + ", RPC at " + rpc + "). That is the defect: a refused rename/add/remove is "
                + "written to messaging.db anyway and never rolled back, so the local roster is "
                + "permanently ahead of the server's and every tap widens it.", lastMirror > rpc);
        final String between = body.substring(rpc, lastMirror);
        assertTrue("the plaintext arm's mirror is not gated on the RPC's result — there is no "
                + "`if (ok)` between the RPC and the write, so a failed RPC still mirrors",
                between.contains("if (ok)"));
    }

    /**
     * The brace-matched block opened by the arm at {@code at}, or "" if the arm is braceless. A
     * {@code ;} before the brace ends the search, or a braceless arm would borrow its neighbour's
     * block.
     */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        final int stop = src.indexOf(';', at);
        if (stop >= 0 && stop < open) return "";        // braceless arm: that brace is not its own
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    private static String bodyOf(final String src, final String name) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
            }
            return "";
        }
        return "";
    }

    /** Comments and string-literal contents blanked; offsets preserved. */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        int i = 0;
        while (i < out.length) {
            final char c = out[i];
            final char next = (i + 1 < out.length) ? out[i + 1] : '\0';
            if (c == '/' && next == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && next == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < out.length
                        && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
                if (i < out.length) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < out.length && out[i] != c) {
                    if (out[i] == '\\') {
                        out[i++] = ' ';
                        if (i < out.length) out[i++] = ' ';
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
