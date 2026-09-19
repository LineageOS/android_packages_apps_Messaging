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
 * <b>Leaving a group is gated by the KILL SWITCH and by nothing else.</b>
 *
 * <h2>Why this is a source guard, and why it is worth one at all</h2>
 *
 * <p>The wrong gate here is not a crash and not a wrong answer: it is a person who taps <b>Leave
 * group</b> and stays in the group, on a conversation with a peer they want out of. Both existing
 * {@code MlsPeerGuard} tiers produce exactly that, and both of them are the nearest thing to hand:
 *
 * <ul>
 *   <li>{@code allowJoiningPeer}'s lab allowlist refuses a departure from a group containing a
 *       non-lab peer — backwards, since leaving is how you stop talking to one;</li>
 *   <li>{@code allowStateChange}'s peer-health streak refuses it precisely when the peer looks
 *       wedged, which is when a person most wants out — and for a GROUP it would refuse on ONE
 *       member's streak, because a group has no single peer to charge.</li>
 * </ul>
 *
 * <p>Neither failure is visible in a passing run: the guard logs a refusal and the row appears to do
 * nothing. {@code MlsProviderTransport} and {@code MlsPeerGuard} both need a {@code Context}, so the
 * host suite cannot reach them any other way. Same reasoning as {@code MlsGuardPersistenceTest}.
 */
public final class MlsSelfDepartureGuardTest {

    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    @Test
    public void leavingIsGatedByTheKillSwitchAndNothingElse() throws IOException {
        final String transport = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java"));
        final String body = bodyOf(transport, "leaveGroup");
        assertTrue("MlsProviderTransport.leaveGroup is gone — the menu row has nothing to call",
                body.length() > 0);
        assertTrue("leaveGroup does not ask MlsPeerGuard at all. The kill switch must stop our own "
                + "departure too: a self-leave transmits a SelfRemove proposal, and G6 exists so "
                + "that one sysprop stops every outbound MLS state change during an incident.",
                body.contains("MlsPeerGuard.allowSelfDeparture("));
        assertFalse("leaveGroup reaches for allowStateChange because it is nearest. Its peer-health "
                + "tier refuses a departure exactly when the peer looks wedged — which is when a "
                + "person most wants out — and for a group it refuses on ONE member's streak.",
                body.contains("allowStateChange("));
        assertFalse("leaveGroup reaches for the lab allowlist. It would refuse our own escape from "
                + "a group containing a non-lab peer, which is backwards.",
                body.contains("allowJoiningPeer(") || body.contains("allowDebugStateChange("));
    }

    /**
     * The gate itself must consult the freeze and nothing else, or the point of it is lost.
     *
     * <h3>Re-pointed by Stage 2, and STRENGTHENED rather than moved</h3>
     *
     * <p>{@code allowSelfDeparture} is now one line naming {@code Tier.SELF_DEPARTURE}, so the three
     * assertions this test used to make about its body have become assertions about a delegate. The
     * question they were asking — "does leaving consult anything but the kill switch?" — is now
     * answerable BEHAVIOURALLY, because the composition is a value in the engine:
     * {@link MlsStateChangeGateTest#leavingIsGatedByTheKillSwitchAndNothingElse} hands the tier a
     * wedged, non-lab peer with a spent budget and asserts it still gets out. That is a stronger
     * statement than "the method body does not mention {@code labPeers(}", and it is the reason D1
     * chose option (a).
     *
     * <p>What stays here is the WIRING, which no host test can reach: that the entry point routes to
     * that tier and to no other.
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
        assertFalse("it consults the lab allowlist", body.contains("labPeers("));
        assertFalse("it consults the peer-health record", body.contains("healthKey("));
    }

    /**
     * The MLS arm must write NOTHING locally: {@code leave()} already wrote the terminal mark, the
     * encryption bit and the "You left" line. A second optimistic write shares the de-dup signature
     * and collapses into the first — but only while both use the identical requester/affected, which
     * is a coincidence to depend on rather than a design.
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
        // THE ARM'S OWN BLOCK, brace-matched — not the span from here to the shared write below it.
        // The sixth axis. The span ENDED at the first applyLocalMirror, so the assertFalse
        // below could only ever see a call spelled differently from the one that terminates the
        // span; a call spelled the SAME way truncated the span instead, and the test went red
        // naming a missing GroupDepartureApplier.apply that was still there. Measured. Not a silent
        // pass, but a guard that misdiagnoses is one a reader stops believing.
        final String leaveArm = blockAfter(body, arm);
        assertTrue("the OP_LEAVE arm's block could not be brace-matched", leaveArm.length() > 0);
        assertFalse("the OP_LEAVE arm calls applyLocalMirror. On the MLS path leave() has already "
                + "written the whole mirror; on the plaintext path buildSelfStatusText would render "
                + "\"You removed You\" and removeGroupParticipants skips the self participant.",
                leaveArm.contains("applyLocalMirror("));
        assertTrue("the OP_LEAVE arm does not route through the transport, so it is choosing an RPC "
                + "for itself — the decision belongs in the layer that owns the state",
                leaveArm.contains(".leaveGroup(groupId)"));
        assertTrue("the plaintext arm no longer tells the thread anything, so a leave on a "
                + "non-encrypted group is silent — the server fans its push to the members who "
                + "REMAIN, so nothing else will",
                leaveArm.contains("GroupDepartureApplier.apply("));
    }

    /**
     * <b>No arm of {@code executeAction} writes the local mirror before the RPC it mirrors.</b>
     * Both the MLS arms and the plaintext arm.
     *
     * <p>The helper ran unconditionally BEFORE the RPC and was never rolled back, so a refused
     * membership change left {@code messaging.db}'s roster permanently ahead of the server's — and
     * ahead in the direction that HIDES the failure, because the member is gone from the list the
     * user is looking at. Device-measured on deviceB: {@code FAILED_PRECONDITION
     * tachyonerror=-1} and the participant dropped locally anyway.
     *
     * <p>Three arms, one ordering. The check is per-arm and not a single "mirror comes last",
     * because the arms return independently and a correct ordering in one says nothing about the
     * others — which is exactly how the plaintext arm kept the old ordering for two days after the
     * MLS arm lost it.
     */
    @Test
    public void noArmMirrorsLocallyBeforeTheRpcItMirrors() throws IOException {
        final String action = codeOnly(
                read("src/com/android/messaging/datamodel/action/ManageRcsGroupAction.java"));
        final String body = bodyOf(action, "executeAction");
        assertTrue("executeAction is gone", body.length() > 0);

        // THE RENAME IS PART OF THE FIX, so it is asserted rather than assumed: a call spelled
        // applyOptimistic is either the old helper restored or a new one that means what the old
        // name said, and both are the defect.
        assertFalse("executeAction calls applyOptimistic. No caller is optimistic any more — the "
                + "MLS arm mirrors on APPLIED, the plaintext arm on a true RPC result, the leave "
                + "arm not at all — so the name is a description of a design this file abandoned.",
                action.contains("applyOptimistic("));

        int mirrors = 0;
        for (int i = body.indexOf("applyLocalMirror("); i >= 0;
                i = body.indexOf("applyLocalMirror(", i + 1)) {
            mirrors++;
        }
        // ZERO MUST FAIL. A scan that cannot find the write it is ordering has certified an
        // ordering it never looked at.
        //
        // FOUR NOW, and the bump keeps being the interesting part rather than a
        // chore. This read "2 is the architecture", then three when the RENAME arm was routed,
        // and now four for the ICON arm — every one of those bumps landed on CORRECT code
        // that had been given exactly the ordering this test exists to require. A COUNT over a set
        // designed to grow encodes today's arm list as the invariant, so a correctly-built new arm
        // reads as a regression.
        //
        // It is kept as a count ONLY because it is the ZERO guard — the thing that stops the
        // ordering assertions below from certifying an empty string — and every site has its own
        // ordering assertion underneath. THE INSTRUCTION FOR THE NEXT ARM IS UNCHANGED AND WAS
        // FOLLOWED HERE: give it one and bump this; do not delete the assertions and widen this
        // into a range.
        assertEquals("executeAction no longer calls applyLocalMirror at all, or calls it from a "
                + "place that has not been given the ordering. FOUR sites is the architecture: the "
                + "MLS add/remove arm on APPLIED, the MLS rename arm on APPLIED, the "
                + "MLS icon arm on APPLIED, and the plaintext arm on a true RPC "
                + "result. The leave arm deliberately has none. If you are here because you added "
                + "an arm, give it its own ordering assertion below and bump this number — do not "
                + "widen this into a range.",
                4, mirrors);

        // --- the MLS arm: mirrors only on APPLIED ------------------------------------------------
        final int mlsAt = body.indexOf("routed != MlsProviderTransport.GroupMembershipRouting"
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

        // --- the MLS RENAME arm: mirrors only on APPLIED ----------------------------
        //
        // The third site the count above now expects, given its own ordering assertion rather than
        // just being counted. Its mirror is MORE load-bearing than the add/remove arm's, not less:
        // the sender cannot decrypt its own §9.7.1.5 subject, so MlsSubjectApplier never fires for
        // the renamer and this write is the only thing that ever shows them the new name. That
        // makes "mirror only on APPLIED" the assertion to hold — an ungated write here would show a
        // name that was never sent.
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

        // --- the MLS ICON arm: mirrors only on APPLIED ------------------------------
        //
        // The fourth site, and its mirror is load-bearing for exactly the reason the rename's is:
        // the sender cannot decrypt its own §9.7.1.4 icon key, so GroupIconApplier never fires for
        // the person who SET the photo and this write is the only thing that ever shows it to them.
        // An ungated write here would show a photo that was never sent — and unlike a name, the
        // user has no way to tell a stale avatar from a current one.
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

        // --- the plaintext arm: RPC first, then mirror, gated on the result ----------------------
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

    // ---- helpers (same shape as MlsGuardPersistenceTest's) -----------------------------------

    /**
     * The brace-matched block opened by the arm at {@code at}, or "" if that arm has no block.
     *
     * <p><b>It must be THAT arm's block.</b> Taking "the next {@code &#123;} after the offset" is the
     * same defect as a character window one step removed: a BRACELESS arm
     * ({@code if (refused) log(…);}) has no block of its own, so the search runs on and hands back a
     * NEIGHBOUR's — and the assertion then reports on code the arm does not contain. Measured before
     * this guard existed: make the refusal arm braceless and non-returning, leave a
     * {@code REFUSED_BY_GUARD} in the arm below it, and the check returned PASS on a rebuild that
     * charges the budget, is refused, and rebuilds anyway.
     *
     * <p>So a {@code ;} before the {@code &#123;} ends the search: a statement has intervened, the arm
     * is braceless, and there is no block to read. That is the third time something written AFTER
     * naming the defect class contained it — the same shape turned up in another per-arm lookup on
     * the same day.
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

    /** Comments and string-literal contents blanked; offsets preserved. See MlsGuardPersistenceTest. */
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
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
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
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
