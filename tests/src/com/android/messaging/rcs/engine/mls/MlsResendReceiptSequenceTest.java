/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * A resent message's receipts reach its chat row. The row keeps the original id; the resend goes
 * out under a fresh one recorded only in the resend ledger, and the receipt lookup resolves a miss
 * through {@link MlsResendLedgerAccess#rootOf}. The router releases send material on a delivery
 * receipt before that lookup runs, and a displayed receipt follows later, so the release must leave
 * the id-to-root mapping in place. {@link Ledger} models the {@code mls_resends} queries.
 */
public final class MlsResendReceiptSequenceTest {

    private static final String ROOT = "m1";
    private static final String PEER = "+2";
    private static final String KEY = "p:" + PEER;

    /** The {@code mls_resends} rows and the queries MlsResendLedger runs over them. */
    static final class Ledger implements MlsResendLedgerAccess {
        static final class Row {
            String id;
            String root;
            String recipient;
            String key;
            int count;
            long ts;
        }

        final List<Row> rows = new ArrayList<>();
        long now = 1_000_000_000L;

        @Override public String rootOf(final String id) {
            for (final Row r : rows) if (r.id.equals(id)) return r.root;
            return id;
        }

        @Override public String conversationKeyOf(final String id) {
            for (final Row r : rows) if (r.id.equals(id)) return r.key;
            return null;
        }

        @Override public List<MlsResendRecord> siblings(final String root) {
            final List<MlsResendRecord> out = new ArrayList<>();
            for (final Row r : rows) {
                if (r.root.equals(root)) {
                    out.add(new MlsResendRecord(r.id, r.root, r.root, r.recipient, "", r.count,
                            r.ts));
                }
            }
            return out;
        }

        @Override public String recordResend(final String root, final String replaces,
                final String recipient, final String clientId, final String key, final long ts) {
            final Row r = new Row();
            r.id = "resend-" + (rows.size() + 1);
            r.root = root;
            r.recipient = recipient;
            r.key = key;
            r.count = MlsResendRecord.nextFtdResendCount(siblings(root));
            r.ts = ts;
            rows.add(r);
            return r.id;
        }

        @Override public int resendsToPeerSince(final String key, final String recipient,
                final long since) {
            int n = 0;
            for (final Row r : rows) {
                if (r.recipient.equals(recipient) && r.key.equals(key)
                        && r.ts >= Math.max(0L, since)) n++;
            }
            return n;
        }

        @Override public int repeatResendsToPeerSince(final String key, final String recipient,
                final long since) {
            final java.util.Set<String> roots = new java.util.HashSet<>();
            int n = 0;
            for (final Row r : rows) {
                if (r.recipient.equals(recipient) && r.key.equals(key)
                        && r.ts >= Math.max(0L, since)) {
                    n++;
                    roots.add(r.root);
                }
            }
            return n - roots.size();
        }

        @Override public int forgetConversation(final String key) {
            final int before = rows.size();
            rows.removeIf(r -> r.key.equals(key));
            return before - rows.size();
        }

        @Override public int retireChain(final String root) {
            int n = 0;
            for (final Row r : rows) {
                if (r.root.equals(root) && r.ts >= 0L) {
                    r.ts = -now;
                    n++;
                }
            }
            return n;
        }

        @Override public int forgetRetiredChains(final long maxAgeMs) {
            final long retiredBefore = now - Math.max(0L, maxAgeMs);
            final int before = rows.size();
            rows.removeIf(r -> r.ts < 0L && r.ts >= -retiredBefore);
            return before - rows.size();
        }
    }

    private static FakeShellPort port(final Ledger ledger) {
        final FakeShellPort f = new FakeShellPort().returns("resendLedger", ledger)
                .returns("findGroupIdByRcsMessageId", null).returns("elapsedRealtime", 0L);
        f.returns("sealedCache",
                f.stub(MlsSealedCacheAccess.class, "release", null, "sweepExpired", 0));
        f.returns("pendingBodies", f.stub(MlsPendingBodyAccess.class, "release", null,
                "sweepExpired", java.util.Collections.emptyList()));
        return f;
    }

    /** What the receipt lookup does when no row carries the reported id. */
    private static String rowIdFor(final Ledger ledger, final String reportedId) {
        return ledger.rootOf(reportedId);
    }

    @Test
    public void theDeliveredAndDisplayedReceiptsOfAResendReachTheOriginalRow() {
        final Ledger ledger = new Ledger();
        final String resend = ledger.recordResend(ROOT, ROOT, PEER, null, KEY, ledger.now);
        assertNotNull(resend);

        // IMDN DELIVERED for the resend: the router releases first, then the status action looks.
        final FakeShellPort f = port(ledger);
        MlsSendRetentionPolicy.releaseSealedOnPositiveReceipt(f.port(), MlsLogSink.NONE, resend);
        assertTrue("the delivery released the chain's material",
                f.calls.contains("MlsSealedCacheAccess.release"));
        assertEquals("the DELIVERED receipt of the resend no longer maps to the original row: the "
                + "release ran first and took the resend chain with it", ROOT,
                rowIdFor(ledger, resend));

        // IMDN DISPLAYED for the resend, later: nothing is released, the lookup runs again.
        ledger.now += 60_000L;
        assertEquals("the DISPLAYED receipt of the resend does not map to the original row", ROOT,
                rowIdFor(ledger, resend));

        // A delivered chain is not escalation evidence against the peer.
        final long window = MlsResendBudget.windowStart(ledger.now);
        assertEquals(0, ledger.resendsToPeerSince(KEY, PEER, window));
        assertEquals(0, ledger.repeatResendsToPeerSince(KEY, PEER, window));
        assertEquals(0, ledger.resendsToPeerSince(KEY, PEER, 0L));

        // The retention sweep keeps the mapping inside the window and deletes it after.
        ledger.now += MlsSendRetentionPolicy.RETIRED_CHAIN_MAX_AGE_MS - 120_000L;
        MlsSendRetentionPolicy.sweepExpiredSendMaterial(port(ledger).port(), MlsLogSink.NONE);
        assertEquals(ROOT, rowIdFor(ledger, resend));
        ledger.now += 120_000L;
        MlsSendRetentionPolicy.sweepExpiredSendMaterial(port(ledger).port(), MlsLogSink.NONE);
        assertTrue("a retired chain past its window is deleted", ledger.rows.isEmpty());
    }

    /** A failure keeps the chain live and counted, as before: only a delivery retires it. */
    @Test
    public void aPermanentFailureNeitherRetiresNorForgetsTheChain() {
        final Ledger ledger = new Ledger();
        final String resend = ledger.recordResend(ROOT, ROOT, PEER, null, KEY, ledger.now);
        MlsSendRetentionPolicy.releaseSealedOnPermanentFailure(port(ledger).port(),
                MlsLogSink.NONE, resend);
        assertEquals(ROOT, rowIdFor(ledger, resend));
        assertEquals(1, ledger.resendsToPeerSince(KEY, PEER,
                MlsResendBudget.windowStart(ledger.now)));
        ledger.now += MlsSendRetentionPolicy.RETIRED_CHAIN_MAX_AGE_MS * 2;
        MlsSendRetentionPolicy.sweepExpiredSendMaterial(port(ledger).port(), MlsLogSink.NONE);
        assertEquals("the sweep deletes only retired chains", 1, ledger.rows.size());
    }
}
