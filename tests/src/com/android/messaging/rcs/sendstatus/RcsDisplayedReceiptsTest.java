/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.RcsDisplayedReceipts;
import com.android.messaging.rcs.RcsDisplayedReceipts.Candidate;
import com.android.messaging.rcs.RcsDisplayedReceipts.Plan;
import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reading a conversation sends a bounded number of displayed receipts: one open used to queue 633
 * and hold a user send for three minutes. See "Receipts" in docs/rcs/provider-contract.md.
 */
public class RcsDisplayedReceiptsTest {

    private static final String ACTION =
            "src/com/android/messaging/datamodel/action/MarkAsReadAction.java";

    private static List<Candidate> rows(final int n, final long firstTs) {
        final List<Candidate> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Candidate(Integer.toString(i + 1), firstTs + i));
        Collections.shuffle(out, new java.util.Random(7));
        return out;
    }

    private static List<String> ids(final List<Candidate> cs) {
        final List<String> out = new ArrayList<>();
        for (final Candidate c : cs) out.add(c.localId);
        return out;
    }

    /** The device case: 633 never-reported rows send the newest ten, oldest of them first. */
    @Test
    public void aBacklogSendsOnlyTheNewestFew() {
        final Plan p = RcsDisplayedReceipts.plan(rows(633, 1000), 0);
        assertEquals(RcsDisplayedReceipts.MAX_PER_PASS, p.send.size());
        assertEquals(633 - RcsDisplayedReceipts.MAX_PER_PASS, p.skip.size());
        final List<String> expected = new ArrayList<>();
        for (int i = 633 - RcsDisplayedReceipts.MAX_PER_PASS + 1; i <= 633; i++) {
            expected.add(Integer.toString(i));
        }
        assertEquals(expected, ids(p.send));
        assertTrue(RcsDisplayedReceipts.MAX_PER_PASS <= 10);
    }

    /** Rows no newer than the newest reported one are marked, not sent. */
    @Test
    public void rowsOlderThanTheLastReportedOneAreNotSent() {
        final Plan p = RcsDisplayedReceipts.plan(rows(5, 100), 102);
        assertEquals(java.util.Arrays.asList("4", "5"), ids(p.send));
        assertEquals(3, p.skip.size());
    }

    @Test
    public void anOrdinaryReadSendsEveryNewMessage() {
        final Plan p = RcsDisplayedReceipts.plan(rows(3, 500), 499);
        assertEquals(java.util.Arrays.asList("1", "2", "3"), ids(p.send));
        assertEquals(0, p.skip.size());
        assertEquals(0, RcsDisplayedReceipts.plan(new ArrayList<>(), 0).send.size());
    }

    @Test
    public void equalTimesAreOrderedById() {
        final List<Candidate> same = new ArrayList<>();
        for (int i = 20; i >= 1; i--) same.add(new Candidate(Integer.toString(i), 7));
        final Plan p = RcsDisplayedReceipts.plan(same, 0);
        assertEquals("11", p.send.get(0).localId);
        assertEquals("20", p.send.get(p.send.size() - 1).localId);
    }

    /**
     * MarkAsReadAction sends a receipt only for a planned row and marks the rest; it used to send
     * one for every row {@code findUndisplayedInboundRcs} returned.
     */
    @Test
    public void markAsReadSendsOnlyThePlannedReceipts() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(ACTION)),
                "sendDisplayedReceiptsForRcs").replaceAll("\\s+", " ");
        assertTrue("no hits: MarkAsReadAction.sendDisplayedReceiptsForRcs not found",
                !body.isEmpty());
        final int plan = body.indexOf("RcsDisplayedReceipts.plan(candidates, "
                + "RcsMessageStore.newestDisplayedInboundTs(db, conversationId))");
        final int skip = body.indexOf("for (final RcsDisplayedReceipts.Candidate c : plan.skip) "
                + "{ RcsMessageStore.markDisplayedSent(db, c.localId, now);");
        final int send = body.indexOf("for (final RcsDisplayedReceipts.Candidate c : plan.send) {");
        final int imdn = body.indexOf("transport.sendImdn(");
        assertTrue("the receipts must come from RcsDisplayedReceipts.plan", plan >= 0);
        assertTrue("the rows the plan skips must be marked", skip > plan);
        assertTrue("a receipt must be sent only from the plan's send list",
                send > plan && imdn > send);
        assertEquals("exactly one receipt send site", 1,
                SourceScan.count(body, "transport.sendImdn("));
        final int receiptsOff = body.indexOf("if (!ReadReceiptSettings.resolve(conversationId)) "
                + "{ return; }");
        assertTrue("receipts off must return before anything is marked",
                receiptsOff >= 0 && receiptsOff < skip);
    }
}
