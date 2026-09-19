/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;
import com.android.messaging.rcs.e2ee.MlsInboundRedelivery;
import com.android.messaging.rcs.e2ee.MlsInboundRedelivery.Verdict;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An MLS message this device already stored and that the provider delivers again (our
 * confirmation did not reach it) is dropped before the decrypt: its generation is spent, and the
 * failed decrypt used to end in a failed-to-decrypt report whose resend was stored as a second
 * row. The decision is tested here; the router has no host test, so where it asks is a positional
 * source scan: before the decrypt, inside the try whose finally confirms, and a branch that only
 * returns.
 */
public class MlsInboundRedeliveryTest {

    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String ASK = "MlsInboundRedelivery.beforeDecrypt(";
    private static final String DECRYPT = ".decryptInbound(";

    /** A fake store that records what it was asked. */
    private static final class Rows implements MlsInboundRedelivery.Store {
        final Set<String> ids = new HashSet<>();
        final List<String> asked = new ArrayList<>();
        boolean broken;

        @Override
        public boolean holds(final String messageId) throws Exception {
            asked.add(messageId);
            if (broken) throw new IllegalStateException("database closed");
            return ids.contains(messageId);
        }
    }

    @Test
    public void aStoredIdIsARedelivery() {
        final Rows rows = new Rows();
        rows.ids.add("0e5bf0f7");
        final Verdict v = MlsInboundRedelivery.beforeDecrypt("0e5bf0f7", id -> false, rows);
        assertEquals(Verdict.STORED, v);
        assertTrue(v.isRedelivery());
    }

    @Test
    public void aClaimedIdIsARedeliveryWithoutAskingTheStore() {
        final Rows rows = new Rows();
        final Verdict v = MlsInboundRedelivery.beforeDecrypt("m1", "m1"::equals, rows);
        assertEquals(Verdict.CLAIMED, v);
        assertTrue(v.isRedelivery());
        assertTrue("the store was read for a claimed id", rows.asked.isEmpty());
    }

    @Test
    public void aNewIdIsDecrypted() {
        final Rows rows = new Rows();
        rows.ids.add("other");
        final Verdict v = MlsInboundRedelivery.beforeDecrypt("m2", id -> false, rows);
        assertEquals(Verdict.NEW, v);
        assertFalse(v.isRedelivery());
        assertEquals(Arrays.asList("m2"), rows.asked);
    }

    /** A lost message is worse than a duplicate: an unreadable store lets the decrypt run. */
    @Test
    public void anUnreadableStoreFailsOpen() {
        final Rows rows = new Rows();
        rows.broken = true;
        final Verdict v = MlsInboundRedelivery.beforeDecrypt("m3", id -> false, rows);
        assertEquals(Verdict.UNREAD, v);
        assertFalse(v.isRedelivery());
    }

    @Test
    public void anIdThatIsMissingIsNeverARedelivery() {
        final Rows rows = new Rows();
        assertEquals(Verdict.NEW, MlsInboundRedelivery.beforeDecrypt(null, id -> true, rows));
        assertEquals(Verdict.NEW, MlsInboundRedelivery.beforeDecrypt("", id -> true, rows));
        assertTrue(rows.asked.isEmpty());
    }

    /** The router asks before the decrypt, returns without a report, and still confirms. */
    @Test
    public void theRouterDropsAStoredRedeliveryBeforeTheDecrypt() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(ROUTER)),
                "decryptClassifyInsert");
        assertTrue("no hits: decryptClassifyInsert not found or decrypts nothing",
                body.contains(DECRYPT));
        final String f = fault(body);
        assertNull("RcsCallbackRouter.decryptClassifyInsert: " + f, f);
    }

    /**
     * A decrypted message is confirmed by the action that stores it, after the row exists, and the
     * finally does not confirm it a second time, earlier. A claimed copy that is not stored yet
     * is left to the claim holder's store. See InboundStoreConfirmGuardTest for the actions.
     */
    @Test
    public void aStoredMlsMessageIsConfirmedByItsStore() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ROUTER));
        final String entry = SourceScan.bodyOf(code, "onMlsCiphertext")
                .replaceAll("\\s+", " ");
        assertTrue("onMlsCiphertext must take the ticket on the binder thread",
                entry.contains("RcsInboundConfirmation.forBinderCall(subId, messageId)"));
        final String body = SourceScan.bodyOf(code, "decryptClassifyInsert")
                .replaceAll("\\s+", " ");
        final int insert = body.indexOf("receiveMessage(new RcsIncomingMessage(");
        assertTrue("no hits: decryptClassifyInsert no longer stores through receiveMessage",
                insert >= 0);
        final int handed = body.indexOf("confirmedByStore = storeTicket != null;", insert);
        assertTrue("the insert must hand its confirmation to the store", handed > insert
                && body.substring(insert, handed).contains("storeTicket)"));
        final int fin = body.lastIndexOf("finally {");
        assertTrue("the finally must not confirm what a store confirms",
                fin > handed && body.indexOf("!confirmedByStore", fin) > fin);
        final int ask = body.indexOf(ASK);
        final int claimed = body.indexOf("Verdict.CLAIMED && !storedLocally(mid)", ask);
        assertTrue("a claimed, unstored redelivery must be left to the claim holder's store",
                claimed > ask && claimed < body.indexOf(DECRYPT));
    }

    /** The scan must be able to fail. */
    @Test
    public void theScanCatchesAMissingLateOrReportingCheck() {
        final String decrypt = "Parsed p = t" + DECRYPT + "from, mid); if (p == null) { "
                + "t.onDecryptFailure(gid, from, mid); return; } ";
        final String dedup = "if (!claimInboundInsert(mid)) { return; } "
                + "if (RcsMessageStore.findLocalIdByRcsMessageId(db, mid) != null) { return; } ";
        final String ask = "final Verdict seen = " + ASK + "mid, a, b); ";
        final String quiet = "if (seen.isRedelivery()) { log(); return; } ";
        final String fin = " finally { confirmApplied(s, mid); } ";
        final String[] bad = {
            // e1621658: the only lookup comes after the decrypt
            "{ try { " + decrypt + dedup + "insert(); }" + fin + "}",
            "{ try { " + decrypt + ask + quiet + dedup + "insert(); }" + fin + "}",
            "{ try { " + ask + "if (seen.isRedelivery()) { t.onDecryptFailure(gid, from, mid); "
                    + "return; } " + decrypt + dedup + "insert(); }" + fin + "}",
            "{ try { " + ask + "if (seen.isRedelivery()) { log(); } " + decrypt + dedup
                    + "insert(); }" + fin + "}",
            "{ " + ask + quiet + "try { " + decrypt + dedup + "insert(); }" + fin + "}",
            "{ try { " + ask + quiet + decrypt + "insert(); }" + fin + "}",
        };
        for (final String b : bad) assertNotNull("must flag: " + b, fault(b));
        final String ok = "{ try { " + ask + quiet + decrypt + dedup + "insert(); } "
                + "catch (final Throwable t) { log(); }" + fin + "}";
        assertNull(fault(ok));
    }

    private static String fault(final String body) {
        final int ask = body.indexOf(ASK);
        final int decrypt = body.indexOf(DECRYPT);
        if (ask < 0) return "no " + ASK + "...) before the decrypt";
        if (decrypt >= 0 && ask > decrypt) return "the redelivery check comes after the decrypt";
        final int ifAt = body.indexOf("isRedelivery()", ask);
        final int open = ifAt < 0 ? -1 : body.indexOf('{', ifAt);
        if (open < 0 || (decrypt >= 0 && open > decrypt)) {
            return "no branch on isRedelivery() before the decrypt";
        }
        final String branch = body.substring(open, closeBrace(body, open) + 1);
        if (!branch.contains("return")) return "the redelivery branch does not return: " + branch;
        for (final String no : new String[] {"onDecryptFailure(", "sendImdn(",
                "onIncomingMessage(", "confirmApplied("}) {
            if (branch.contains(no)) return "the redelivery branch calls " + no + "): " + branch;
        }
        final int tryAt = body.lastIndexOf("try {", ask);
        if (tryAt < 0) return "the check is not inside a try, so its return is not confirmed";
        int k = closeBrace(body, body.indexOf('{', tryAt)) + 1;
        while (true) {
            final String rest = body.substring(k).trim();
            if (!rest.startsWith("catch")) break;
            k = closeBrace(body, body.indexOf('{', k)) + 1;
        }
        final String rest = body.substring(k).trim();
        if (!rest.startsWith("finally")) return "the check's try has no finally";
        final int fo = body.indexOf('{', k);
        if (!body.substring(fo, closeBrace(body, fo) + 1).contains("confirmApplied(")) {
            return "the check's finally does not confirm the message";
        }
        // The check after the decrypt stays: it closes the race between two dispatches.
        final String after = decrypt < 0 ? "" : body.substring(decrypt);
        if (!after.contains("claimInboundInsert(")
                || !after.contains("findLocalIdByRcsMessageId(")) {
            return "the claim and the store lookup after the decrypt are gone";
        }
        return null;
    }

    private static int closeBrace(final String code, final int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return code.length() - 1;
    }
}
