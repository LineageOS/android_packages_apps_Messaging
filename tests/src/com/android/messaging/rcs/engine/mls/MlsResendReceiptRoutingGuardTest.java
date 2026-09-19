/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A receipt naming a resend resolves to its chat row through the RCC.16 §10.3 chain. A resend goes
 * out under a fresh {@code rcs_message_id} recorded only in {@code mls_resends}; the
 * {@code messages} row keeps the original id. {@code UpdateRcsMessageStatusAction} tries the exact
 * id first and falls back to {@code MlsResendLedger.rootOf}; the inbound lookups do not, since an
 * inbound id is the sender's. {@code UpdateRcsReactionAction} roots its side-table key
 * unconditionally, because a write key has no miss to fall back on. Neither class has a host test,
 * so this is a source scan keyed on invoked names, and zero hits fail. See
 * docs/mls/health-and-recovery.md.
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
     * The inbound lookups do not root-resolve: an inbound id is the sender's, and our resend chain
     * says nothing about it. Asserted on the enclosing method, not the file.
     */
    @Test
    public void theInboundLookupsDidNotGainTheChainStep() throws IOException {
        final String media = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(MEDIA_ACTION)), "store");
        assertTrue("ReceiveRcsMediaAction.store not found — this guard is scanning nothing",
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
     * A reaction's side-table key is the chain root: a peer names whichever message it saw, which
     * on a resent message is the resend's id. The rooting happens before both the write and the
     * notify lookup, and the raw reported id is not used as a key afterwards.
     */
    @Test
    public void theReactionKeyIsRootedToo() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(REACTION)), "store");
        assertTrue("UpdateRcsReactionAction.store not found in " + REACTION
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

        // The unresolved value does not survive as a key. The names are derived from the source:
        // keyed on the invoked rootOf( and whatever that call assigns to.
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
            assertTrue(sink + " not found in store — this guard is scanning nothing",
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
     * The detector sees the lookup without the chain step: that shape is reconstructed in memory
     * and {@link #routingFault} must report it.
     */
    @Test
    public void theDetectorFiresOnTheLookupAsItStood() throws IOException {
        final String body = receiptBody();
        assertTrue("there is no chain call to remove, so the mutation below would test nothing",
                SourceScan.count(body, CHAIN) >= 1);
        // Anchored on an exact literal asserted unique, so reversing the edit reproduces the
        // original.
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

    /** Null when the receipt lookup is intact, else what is wrong with it. */
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
                    + "for a resend matches no row and the update is dropped: "
                    + "a resend is DELIVERED, the peer's IMDN arrives, and the bubble "
                    + "never learns. Call MlsResendLedger.rootOf on the miss.";
        }
        // Order is the property: the exact lookup comes first, so the chain step runs only where it
        // finds nothing and an id the exact lookup resolves is never re-routed.
        if (chain.get(0).intValue() < exact.get(0).intValue()) {
            return "the chain step now runs BEFORE the exact lookup. That makes the resolution a "
                    + "REPLACEMENT rather than a fallback: every receipt would be routed through "
                    + "the resend ledger, including the overwhelming majority that are not resends. "
                    + "index_messages_rcs_id is NOT unique and the lookup is LIMIT 1, so this is a "
                    + "behaviour change for rows that already resolved correctly.";
        }
        // The direction guard follows the resolution, or a receipt could rewrite a received row as
        // outgoing.
        final int direction = body.indexOf(DIRECTION);
        if (direction < 0) {
            return "the INCOMING-row direction guard is gone. rcs_message_id is not unique across "
                    + "devices in a group — each member stores its received copy under the "
                    + "sender's id — so without it these writes stamp a received row "
                    + "BUGLE_STATUS_OUTGOING_DELIVERED. "
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
                SourceScan.codeOnly(SourceScan.read(STATUS_ACTION)), "store");
        assertTrue("UpdateRcsMessageStatusAction.store not found in " + STATUS_ACTION
                + " — a guard that cannot find its subject passes on nothing", body.length() > 200);
        return body;
    }
}
