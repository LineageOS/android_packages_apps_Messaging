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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * <b>A receipt naming a RESEND must resolve to the chat row through the §10.3 chain.</b>
 *
 * <h2>The defect, OBSERVED rather than reasoned</h2>
 *
 * <p>Every MLS resend goes out under a FRESH {@code rcs_message_id} —
 * {@code MlsProviderTransport.sendResendNow} mints it via {@code MlsResendLedger.recordResend},
 * because invariant 62 forbids replaying a consumed generation and the server dedupes on message
 * id. That new id is recorded ONLY in {@code mls_resends}; the {@code messages} row keeps the
 * ORIGINAL id for ever. {@code UpdateRcsMessageStatusAction} resolved strictly by exact
 * {@code rcs_message_id}, so every STATUS / IMDN DELIVERED / DISPLAYED / GROUP IMDN for a resend
 * was dropped with a {@code w} line.
 *
 * <p><b>Caught end to end in one second of a real capture</b>, 2026-08-01:
 *
 * <pre>
 *   17:58:24.577  onMlsNegativeDelivery mid=MxRhDXs-… reason=4
 *   17:58:25.368  MlsResendLedger: resend 1 of MxRhDXs-… → new id 6e6f0ff8-…
 *   17:58:25.390  sendGroupMessage … id=6e6f0ff8-…
 *   17:58:25.903  incoming IMDN &lt;message-id&gt;6e6f0ff8-…&lt;/message-id&gt; &lt;delivered/&gt;
 *   17:58:25.918  MlsProviderTransport: 6e6f0ff8-… reached a terminal state — released 1 resend row
 *   17:58:25.953  UpdateRcsMessageStatusAction: no local row for rcsId=6e6f0ff8-…
 * </pre>
 *
 * <p>The resend was <b>delivered</b>, the peer said so, and the chat row never learned. The line
 * between the last two is the sharpest statement of it: {@code MlsProviderTransport} handled the
 * SAME receipt correctly, because it root-resolves. Two consumers of one receipt, one chain-aware
 * and one not — and the knowledge sat next to the defect the whole time
 * ({@code MlsResendLedger.rootOf}'s own javadoc says "call this before looking up a reported id
 * anywhere", and {@code RcsCallbackRouter} names the ledger as the only thing that resolves a
 * resend id three lines above a construction that does not use it).
 *
 * <h2>What is pinned, and what is deliberately NOT</h2>
 *
 * <p><b>Pinned:</b> that the chain step exists; that it is a FALLBACK behind the exact lookup
 * rather than a replacement of it; that the direction guard still runs AFTER the resolution; and
 * that the INBOUND lookups did <b>not</b> gain the same step. That last one is the assertion that
 * stops this fix over-applying: an inbound id is the SENDER's, never a resend of ours, and
 * root-resolving one would be a lookup on a chain we do not own.
 *
 * <p><b>Not pinned:</b> the behaviour itself. {@code UpdateRcsMessageStatusAction} needs a
 * {@code DatabaseWrapper}, a {@code DataModel} and a {@code Factory}; {@code MlsResendLedger}
 * self-serves the database. Neither has a host test and giving them one means standing up most of
 * the app. Two rules apply and are met: key on the INVOKED METHOD NAME rather
 * than a receiver, a variable or a log label, and <b>make zero hits FAIL</b> — every assertion here
 * counts, and {@link #theDetectorFiresOnTheLookupAsItStood} proves the counting can fail.
 *
 * <p><b>The state that would make each assertion fail is reachable and was the tree's state until
 * this landed:</b> a single unconditional {@code findLocalIdByRcsMessageId} with no chain step.
 *
 * <h2>The second instance of this family — no longer open, and now pinned below</h2>
 *
 * <p>{@code UpdateRcsReactionAction} had the same defect one organ over: it keyed
 * {@code upsertReaction} on the raw {@code targetRcsId}, so a tapback on a message we RESENT was
 * stored under the resend's id while the render side joins on the row's (root) id — written, and
 * invisible, with no log line, because the upsert genuinely succeeded. It was a change to what
 * goes INTO the side table rather than to a lookup, so it landed separately
 * rather than as a quiet passenger on this one.
 *
 * <p><b>It is now covered, by {@link #theReactionKeyIsRootedToo} below</b> — the paragraph that
 * used to stand here said it was not, and a cross-reference that outlives its subject is the
 * thing this file keeps being about. The two are guarded together because they are one defect
 * with two shapes: <b>a lookup can try the exact id and fall back on a miss; a write key has no
 * miss to detect and must be decided before the row exists.</b> That is why one is a fallback and
 * the other is unconditional, and why reading either arm as the pattern for the other is wrong.
 *
 * <p>Still NOT a claim to cover every consumer of a wire id — only the two enumerated here.
 */
public class MlsResendReceiptRoutingGuardTest {

    private static final String STATUS_ACTION =
            "src/com/android/messaging/datamodel/action/UpdateRcsMessageStatusAction.java";
    private static final String MEDIA_ACTION =
            "src/com/android/messaging/datamodel/action/ReceiveRcsMediaAction.java";
    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String REACTION =
            "src/com/android/messaging/datamodel/action/UpdateRcsReactionAction.java";

    private static final String EXACT = "findLocalIdByRcsMessageId(";
    private static final String CHAIN = "MlsResendLedger.rootOf(";
    private static final String DIRECTION = "isIncomingLocalId(";

    /** The live property. */
    @Test
    public void theReceiptLookupResolvesAResendThroughItsChain() throws IOException {
        final String fault = routingFault(receiptBody());
        if (fault != null) fail(fault);
    }

    /**
     * <b>THE FIX MUST NOT OVER-APPLY.</b> Two other callers of the same store method resolve an
     * INBOUND id — the sender's, for a message we received — and a resend chain we minted can say
     * nothing about it. Root-resolving there would be a lookup against someone else's id space,
     * and on a non-resend {@code rootOf} is a wasted query on every inbound media re-delivery and
     * every duplicate-dispatch check.
     *
     * <p>Asserted on the ENCLOSING METHOD BODY, not the file, so an unrelated future use of the
     * ledger elsewhere in {@code RcsCallbackRouter} does not read as this defect.
     */
    @Test
    public void theInboundLookupsDidNotGainTheChainStep() throws IOException {
        final String media = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(MEDIA_ACTION)), "executeAction");
        assertTrue("ReceiveRcsMediaAction.executeAction not found — this guard is scanning nothing",
                SourceScan.count(media, EXACT) >= 1);
        if (SourceScan.count(media, CHAIN) != 0) {
            fail("ReceiveRcsMediaAction root-resolves an INBOUND rcs id. That id is the SENDER's — "
                    + "our resend ledger has no chain for it, so this is a lookup against an id "
                    + "space we do not own, and on the common path it is a wasted query on every "
                    + "media re-delivery. The fix belongs on the RECEIPT path only.");
        }

        final String dispatch = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(ROUTER)), "decryptClassifyInsert");
        assertTrue("RcsCallbackRouter.decryptClassifyInsert not found — this guard is scanning "
                        + "nothing", SourceScan.count(dispatch, EXACT) >= 1);
        if (SourceScan.count(dispatch, CHAIN) != 0) {
            fail("the inbound duplicate-dispatch check root-resolves. It asks 'have I already "
                    + "inserted a row for this INBOUND message', and the id is the sender's — "
                    + "resolving it through our resend chain can only produce a false duplicate.");
        }
    }

    /**
     * <b>A reaction's side-table key must be the chain ROOT.</b>
     *
     * <p>Two of {@code UpdateRcsReactionAction}'s five callers hand it a RAW WIRE ID — the
     * provider's {@code onIncomingReaction} callback, and the MLS path that reads the CPIM
     * {@code Reference-ID}. A peer names whichever message it saw, so on a resent message that is
     * the resend's id, which no {@code messages} row carries.
     *
     * <p><b>Three things are asserted and each has a distinct failure it prevents:</b>
     *
     * <ul>
     *   <li><b>The rooting happens</b> — without it the row is written under an id the chip query
     *       cannot join, and nothing says so.</li>
     *   <li><b>It happens BEFORE the write and before the notify lookup</b>, asserted by index.
     *       Both read the same id and they must agree: rooting only the write would store the row
     *       correctly and then fail to refresh the conversation it belongs to.</li>
     *   <li><b>The raw reported id is not still used as a key afterwards</b>, which is the
     *       assertion a "does it call rootOf" check cannot make. Calling the resolver and then
     *       keying on the unresolved value is the shape that looks handled and is not.</li>
     * </ul>
     *
     * <p>The rooting is UNCONDITIONAL here while {@link #theReceiptLookupResolvesAResendThroughItsChain}
     * requires a FALLBACK, and that asymmetry is deliberate rather than an inconsistency: a lookup
     * can try the exact id and fall back on a miss; a write key has no miss to detect.
     */
    @Test
    public void theReactionKeyIsRootedToo() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(REACTION)), "executeAction");
        assertTrue("UpdateRcsReactionAction.executeAction not found in " + REACTION
                + " — a guard that cannot find its subject passes on nothing", body.length() > 200);

        final List<Integer> root = SourceScan.indicesOf(body, CHAIN);
        assertTrue("the reaction target is not root-resolved. A peer names the message id it SAW, "
                + "and on a resent message that is the resend's id — which no messages row "
                + "carries, while the chip query joins rcs_reactions.target_rcs_message_id against "
                + "messages.rcs_message_id. The row is written, the chip never appears, and the "
                + "upsert reports success because it was one.", !root.isEmpty());

        final List<Integer> writes = SourceScan.indicesOf(body, "upsertReaction(");
        final List<Integer> removes = SourceScan.indicesOf(body, "removeReaction(");
        final List<Integer> notify = SourceScan.indicesOf(body, "findLocalIdByRcsMessageId(");
        assertEquals("the add path must still write exactly one reaction row", 1, writes.size());
        assertEquals("the remove path must still delete by the same key — if the add roots and "
                + "the remove does not, a reaction becomes UNREMOVABLE, which is worse than one "
                + "that does not show", 1, removes.size());
        assertEquals("the conversation refresh must still resolve the target's local id",
                1, notify.size());
        for (final int at : new int[] {writes.get(0), removes.get(0), notify.get(0)}) {
            assertTrue("the root resolution must happen BEFORE every use of the target id — the "
                    + "write, the delete AND the notify lookup all read it and must agree",
                    root.get(0).intValue() < at);
        }

        // AND THE UNRESOLVED VALUE MUST NOT SURVIVE AS A KEY. Calling rootOf and then keying on
        // the reported id is the shape that reads as handled and is not; a presence check for
        // rootOf( cannot tell the two apart.
        //
        // THE NAMES ARE DERIVED FROM THE SOURCE, NOT HARDCODED — and the first version of this
        // block hardcoded them AND spelled the needles without the space after "db,", so they
        // could never match. Both mutations it was written to catch passed clean. It was found by
        // running them, which is the only thing that finds a check that cannot fail; the paragraph
        // above it claiming the check existed did not. Keyed on the INVOKED METHOD rootOf( and on
        // whatever that call assigns to, so a rename of either variable keeps working.
        final int rootAt = root.get(0).intValue();
        final String rootedName = assignedNameBefore(body, rootAt);
        final String reportedName = argumentOf(body, rootAt + CHAIN.length());
        assertTrue("could not read the rooted/reported variable names out of the rootOf( call, so "
                + "the assertions below would be vacuous. Read the source rather than relaxing "
                + "this: rooted=" + rootedName + " reported=" + reportedName,
                !rootedName.isEmpty() && !reportedName.isEmpty()
                        && !rootedName.equals(reportedName));

        for (final String sink : new String[] {"upsertReaction(", "removeReaction(",
                "findLocalIdByRcsMessageId("}) {
            final int at = body.indexOf(sink);
            assertTrue(sink + " not found in executeAction — this guard is scanning nothing",
                    at >= 0);
            final String args = argsOf(body, at + sink.length() - 1);
            assertTrue("the key handed to " + sink + " is not the ROOTED id (" + rootedName
                    + "). Its arguments read: " + args, args.contains(rootedName));
            assertFalse("the UNRESOLVED id (" + reportedName + ") is still being used as a key at "
                    + sink + ". Root it first and key on the RESULT — resolving and then "
                    + "discarding the answer is the defect wearing the fix's clothes, and it "
                    + "renders exactly as the original bug did. Its arguments read: " + args,
                    args.contains(reportedName));
        }
    }

    /** The identifier a `… <name> = ` assignment immediately before {@code at} binds. */
    private static String assignedNameBefore(final String body, final int at) {
        final int eq = body.lastIndexOf('=', at);
        if (eq < 0) return "";
        int end = eq;
        while (end > 0 && Character.isWhitespace(body.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && (Character.isJavaIdentifierPart(body.charAt(start - 1)))) start--;
        return body.substring(start, end);
    }

    /** The single identifier passed at an argument list opening at {@code openParen}. */
    private static String argumentOf(final String body, final int openParen) {
        final int close = body.indexOf(')', openParen);
        if (close < 0) return "";
        return body.substring(openParen, close).replace("(", "").trim();
    }

    /** The full argument text of a call whose `(` is at {@code openParen}, parens balanced. */
    private static String argsOf(final String body, final int openParen) {
        int depth = 0;
        for (int i = openParen; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return body.substring(openParen + 1, i);
            }
        }
        return "";
    }

    /**
     * <b>The detector must SEE the lookup as it stood.</b> "The chain step is present and ordered"
     * is equally true of a scanner that finds no body, matches the wrong method, or was defeated by
     * a reformat. So the old shape — the exact lookup with no chain step — is
     * reconstructed IN MEMORY by deleting the chain call, and {@link #routingFault} is required to
     * report it.
     */
    @Test
    public void theDetectorFiresOnTheLookupAsItStood() throws IOException {
        final String body = receiptBody();
        assertTrue("there is no chain call to remove, so the mutation below would test nothing",
                SourceScan.count(body, CHAIN) >= 1);
        // ANCHORED ON AN EXACT LITERAL and asserted unique before use, so reversing the edit
        // reproduces the original byte for byte. A positional splice would round-trip by
        // construction and prove nothing — the defect CLAUDE.md records against range replacement.
        final String marker = "__N38V8_CHAIN_REMOVED__";
        assertTrue("the chain token is not unique in this body, so the removal below would be "
                        + "positional in disguise", SourceScan.count(body, CHAIN) == 1);
        final String mutated = body.replace(CHAIN, marker + "(");
        assertTrue("the in-memory mutation did not reverse cleanly, so it is not the one described",
                mutated.replace(marker + "(", CHAIN).equals(body));
        if (routingFault(mutated) == null) {
            fail("the detector did NOT report a receipt lookup with no chain step, so "
                    + "theReceiptLookupResolvesAResendThroughItsChain cannot fail and its green is "
                    + "worthless.");
        }
    }

    /** @return {@code null} when the receipt lookup is intact, else what is wrong with it. */
    static String routingFault(final String body) {
        final List<Integer> exact = SourceScan.indicesOf(body, EXACT);
        if (exact.isEmpty()) {
            return "UpdateRcsMessageStatusAction no longer resolves the chat row by rcs id at all. "
                    + "If the lookup moved, re-derive this guard rather than deleting it — the "
                    + "property is that a RESEND's receipt reaches its row, not that "
                    + "any particular method is called.";
        }
        final List<Integer> chain = SourceScan.indicesOf(body, CHAIN);
        if (chain.isEmpty()) {
            return "the receipt lookup does not consult the §10.3 resend chain. A resend goes out "
                    + "under a FRESH rcs_message_id (invariant 62) and is recorded ONLY in "
                    + "mls_resends — the messages row keeps the ORIGINAL id — so a peer's receipt "
                    + "for a resend matches no row and the update is dropped. OBSERVED on "
                    + "2026-08-01: a resend was DELIVERED, the peer's IMDN arrived, and the bubble "
                    + "never learned. Call MlsResendLedger.rootOf on the miss.";
        }
        // ORDER IS THE PROPERTY, not presence. The exact lookup must come FIRST, so the chain step
        // runs only where we previously returned null and no receipt that resolved before can
        // resolve differently now. Reversed, every receipt would be routed through the ledger and
        // the change would stop being additive — with a non-unique rcs id index and a LIMIT 1
        // lookup, that is a different answer, not a tidier one.
        if (chain.get(0).intValue() < exact.get(0).intValue()) {
            return "the chain step now runs BEFORE the exact lookup. That makes the resolution a "
                    + "REPLACEMENT rather than a fallback: every receipt would be routed through "
                    + "the resend ledger, including the overwhelming majority that are not resends. "
                    + "index_messages_rcs_id is NOT unique and the lookup is LIMIT 1, so this is a "
                    + "behaviour change for rows that already resolved correctly.";
        }
        // ...and the resolution must be followed by the direction guard, or root-resolving hands a
        // receipt the power to rewrite a RECEIVED row as outgoing.
        final int direction = body.indexOf(DIRECTION);
        if (direction < 0) {
            return "the INCOMING-row direction guard is gone. rcs_message_id is not unique across "
                    + "devices in a group — each member stores its received copy under the "
                    + "sender's id — so without it these writes stamp a received row "
                    + "BUGLE_STATUS_OUTGOING_DELIVERED (measured 2026-08-16, 28 rows). "
                    + "Root-resolving makes MORE receipts reach a row, so this guard matters more "
                    + "now, not less.";
        }
        if (direction < chain.get(0).intValue()) {
            return "the direction guard now runs BEFORE the chain resolution, so it is checking the "
                    + "direction of a row that has not been resolved yet. It must sit after the "
                    + "resolution and test the row this receipt will actually update.";
        }
        return null;
    }

    private static String receiptBody() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(STATUS_ACTION)), "executeAction");
        assertTrue("UpdateRcsMessageStatusAction.executeAction not found in " + STATUS_ACTION
                + " — a guard that cannot find its subject passes on nothing", body.length() > 200);
        return body;
    }
}
