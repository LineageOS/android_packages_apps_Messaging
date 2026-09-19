/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;

/**
 * Every stored inbound callback is confirmed to the provider, and only after the store: the
 * provider acknowledges the message upstream on that confirmation, so a confirmation sent before
 * the row exists loses the message when the app dies in between, and one never sent leaves it to
 * be offered again forever. See "Delivery confirmation" in docs/rcs/provider-contract.md.
 *
 * <p>The receive actions and the router need a device, so this is a positional source scan: each
 * action's {@code executeAction} runs its {@code store} first and confirms after it returns, and
 * the router hands every content callback's confirmation to its action.
 */
public class InboundStoreConfirmGuardTest {

    private static final String ACTIONS = "src/com/android/messaging/datamodel/action/";
    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String TRANSPORT = "src/com/android/messaging/rcs/ProviderTransport.java";
    private static final String CONFIRM = "RcsInboundConfirmation.afterStore(actionParameters";

    /** The actions that store an inbound callback's effect. */
    private static final String[] RECEIVE_ACTIONS = {
        "ReceiveRcsMessageAction", "ReceiveRcsMediaAction", "ReceiveRcsBotMessageAction",
        "UpdateRcsReactionAction", "ReceiveRcsGroupEventAction", "UpdateRcsMessageStatusAction",
    };

    @Test
    public void everyReceiveActionConfirmsAfterItsStore() throws IOException {
        for (final String a : RECEIVE_ACTIONS) {
            final String code = SourceScan.codeOnly(SourceScan.read(ACTIONS + a + ".java"));
            final String f = fault(SourceScan.bodyOf(code, "executeAction"),
                    SourceScan.bodyOf(code, "store"));
            assertNull(a + ": " + f, f);
        }
    }

    /** A tapback stored as a reaction is confirmed by the reaction's store, not before it. */
    @Test
    public void aTapbackHandsItsConfirmationToTheReaction() throws IOException {
        final String store = SourceScan.bodyOf(SourceScan.codeOnly(
                SourceScan.read(ACTIONS + "ReceiveRcsMessageAction.java")), "store");
        final int move = store.indexOf("RcsInboundConfirmation.moveTo(actionParameters, "
                + "reaction.actionParameters);");
        final int start = store.indexOf("reaction.start();");
        assertTrue("no hits: the tapback arm no longer starts a reaction", start >= 0);
        assertTrue("the tapback's reaction must carry the message's confirmation, handed over "
                + "before it starts", move >= 0 && move < start);
    }

    /** Every content callback hands a confirmation to the action that stores it. */
    @Test
    public void theRouterHandsEveryContentCallbackItsConfirmation() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ROUTER));
        final String[][] handoffs = {
            {"receiveMessage", "new ReceiveRcsMessageAction(msg).confirming(ticket).start()"},
            {"onIncomingMessage", "forBinderCall(msg.subId, msg.messageId)"},
            {"onIncomingMedia", ".confirming(ticket)"},
            {"receiveReaction", ".confirming(ticket).start()"},
            {"onIncomingReaction", "forBinderCall(subId, confirmId)"},
            {"onIncomingBotMessage", "forBinderCall(msg.subId, msg.messageId)"},
            {"onImdnReceipt", "forBinderCall(subId, confirmId)"},
            {"onGroupImdnReceipt", "forBinderCall(subId, confirmId)"},
            {"onGroupEvent", "forBinderCall(subId, confirmId)"},
        };
        for (final String[] h : handoffs) {
            final String body = SourceScan.bodyOf(code, h[0]);
            assertTrue("no hits: RcsCallbackRouter." + h[0] + " not found", !body.isEmpty());
            assertTrue("RcsCallbackRouter." + h[0] + " does not hand its confirmation on ("
                    + h[1] + ")", body.replaceAll("\\s+", " ").contains(h[1]));
        }
        // The caller is read before the identity is cleared for the descriptor copy.
        final String media = SourceScan.bodyOf(code, "onIncomingMedia");
        assertTrue("onIncomingMedia must take its ticket before clearCallingIdentity()",
                media.indexOf("forBinderCall(") >= 0
                        && media.indexOf("forBinderCall(")
                                < media.indexOf("Binder.clearCallingIdentity()"));
    }

    /** The app tells the provider it confirms what it stores: its revision is at least that. */
    @Test
    public void theAppDeclaresTheConfirmingRevision() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        assertTrue("ProviderTransport.CONTRACT_VERSION must be the confirming revision or later",
                java.util.regex.Pattern.compile("CONTRACT_VERSION = "
                        + "IRcsProvider\\.CONTRACT_CONFIRMS_STORED( \\+ \\d+)?;")
                        .matcher(code).find());
    }

    /** The scan must be able to fail. */
    @Test
    public void theScanCatchesAMissingOrEarlyConfirmation() {
        final String store = "{ db.insert(); return m; }";
        final String[] bad = {
            "{ return store(); }",
            "{ " + CONFIRM + ", \"a\"); final Object s = store(); return s; }",
            "{ final Object s = store(); return s; }",
        };
        for (final String b : bad) {
            assertNotNull("must flag: " + b, fault(b, store));
        }
        assertNotNull("a confirmation inside the store must be flagged",
                fault("{ final Object s = store(); " + CONFIRM + ", \"a\"); return s; }",
                        "{ " + CONFIRM + ", \"a\"); db.insert(); }"));
        assertNull(fault("{ final Object s = store(); " + CONFIRM + ", \"a\"); return s; }",
                store));
    }

    /** Null when {@code execute} runs {@code store} first and confirms after it. */
    private static String fault(final String execute, final String store) {
        if (execute.isEmpty()) return "no executeAction";
        if (store.isEmpty()) return "no store method: the stored inbound is never confirmed";
        final int stored = execute.indexOf("store()");
        final int confirmed = execute.indexOf(CONFIRM);
        if (confirmed < 0) return "stores an inbound callback and never confirms it: " + execute;
        if (stored < 0 || confirmed < stored) {
            return "confirms before the store returns: " + execute;
        }
        if (store.contains("RcsInboundConfirmation.afterStore(")
                || store.contains("RcsInboundConfirmation.send(")) {
            return "store confirms from inside, before it has returned";
        }
        return null;
    }
}
