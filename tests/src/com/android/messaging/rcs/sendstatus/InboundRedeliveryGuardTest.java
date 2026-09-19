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
 * An inbound message whose {@code rcs_message_id} is already stored is a redelivery: the provider
 * did not get its ack in (the app died during the call) and the message arrived again. The receive
 * actions look the id up and return before anything is inserted. They have no host test, so this
 * is a source scan, and positional: the property is that the early return comes before the insert.
 */
public class InboundRedeliveryGuardTest {

    private static final String ACTIONS = "src/com/android/messaging/datamodel/action/";
    private static final String LOOKUP = "findLocalIdByRcsMessageId(";
    private static final String INSERT = "insertNewMessageInTransaction(";

    @Test
    public void aRedeliveredMessageIsNotInsertedAgain() throws IOException {
        final String body = executeAction("ReceiveRcsMessageAction.java");
        final String branch = redeliveryBranch(body);
        final String f = fault(body, branch);
        assertNull("ReceiveRcsMessageAction: " + f, f);
        assertTrue("a redelivery still repeats the delivered receipt the sender may be waiting "
                + "for: " + branch, branch.contains("sendDeliveredReceipt("));
    }

    @Test
    public void aRedeliveredBotMessageIsNotInsertedAgain() throws IOException {
        final String body = executeAction("ReceiveRcsBotMessageAction.java");
        final String f = fault(body, redeliveryBranch(body));
        assertNull("ReceiveRcsBotMessageAction: " + f, f);
    }

    /** The scan must be able to fail. */
    @Test
    public void theScanCatchesAMissingOrLateCheck() {
        final String insert = "db.beginTransaction(); BugleDatabaseOperations." + INSERT
                + "db, m); db.endTransaction(); ";
        final String check = "if (RcsMessageStore." + LOOKUP + "db, id) != null) { return null; } ";
        final String[] bad = {
            "{ " + insert + "}",
            "{ " + insert + check + "}",
            "{ if (RcsMessageStore." + LOOKUP + "db, id) != null) { log(); } " + insert + "}",
        };
        for (final String b : bad) {
            assertNotNull("must flag: " + b, fault(b, redeliveryBranch(b)));
        }
        final String ok = "{ " + check + insert + "}";
        assertNull(fault(ok, redeliveryBranch(ok)));
    }

    private static String executeAction(final String file) throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(ACTIONS + file)), "executeAction");
        assertTrue("no hits: " + file + " executeAction not found or inserts nothing",
                body.contains(INSERT));
        return body;
    }

    /** The block of the if whose condition holds the first lookup, or null. */
    private static String redeliveryBranch(final String body) {
        final int at = body.indexOf(LOOKUP);
        if (at < 0) return null;
        final int ifAt = body.lastIndexOf("if (", at);
        final int open = body.indexOf('{', at);
        if (ifAt < 0 || open < 0 || body.substring(ifAt, open).contains(";")) return null;
        int depth = 0;
        for (int i = open; i < body.length(); i++) {
            final char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return body.substring(ifAt, i + 1);
        }
        return null;
    }

    private static String fault(final String body, final String branch) {
        if (branch == null) return "no if on a findLocalIdByRcsMessageId lookup before the insert";
        if (!branch.contains("return")) return "the lookup's branch does not return: " + branch;
        if (body.indexOf(branch) > body.indexOf(INSERT)) return "the lookup comes after the insert";
        return null;
    }
}
