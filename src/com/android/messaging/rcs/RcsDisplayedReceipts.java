/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Which unreported inbound messages get a displayed receipt when a conversation is read. Plain
 * Java, so the host tests run it.
 *
 * <p>Every receipt is one send through the provider, and the provider sends them in turn with the
 * user's own messages. Opening a conversation with hundreds of old unreported rows queued one
 * receipt per row and held a user send for minutes. So a pass reports only messages newer than the
 * newest one already reported, and of those only the newest {@link #MAX_PER_PASS}. The rest are
 * marked reported without a receipt, so no later pass sends them either: a peer learns that the
 * conversation was read from the newest receipts. See "Receipts" in docs/rcs/provider-contract.md.
 */
public final class RcsDisplayedReceipts {

    /** The most displayed receipts one pass sends for one conversation. */
    public static final int MAX_PER_PASS = 10;

    private RcsDisplayedReceipts() {}

    /** One inbound message with no displayed receipt yet. */
    public static final class Candidate {
        public final String localId;
        public final long receivedTs;

        public Candidate(final String localId, final long receivedTs) {
            this.localId = localId;
            this.receivedTs = receivedTs;
        }
    }

    /** A pass: the messages to report, oldest first, and those to mark without a receipt. */
    public static final class Plan {
        public final List<Candidate> send;
        public final List<Candidate> skip;

        Plan(final List<Candidate> send, final List<Candidate> skip) {
            this.send = Collections.unmodifiableList(send);
            this.skip = Collections.unmodifiableList(skip);
        }
    }

    /**
     * @param pending       the conversation's inbound messages with no displayed receipt, any order
     * @param reportedUntil the received time of the newest inbound message already marked
     *                      reported, or 0 when none is
     */
    public static Plan plan(final List<Candidate> pending, final long reportedUntil) {
        final List<Candidate> newestFirst = new ArrayList<>(pending);
        newestFirst.sort(NEWEST_FIRST);
        final List<Candidate> send = new ArrayList<>();
        final List<Candidate> skip = new ArrayList<>();
        for (final Candidate c : newestFirst) {
            if (c.receivedTs > reportedUntil && send.size() < MAX_PER_PASS) {
                send.add(c);
            } else {
                skip.add(c);
            }
        }
        Collections.reverse(send);
        return new Plan(send, skip);
    }

    /** By received time, then by local id as a number, newest first. */
    private static final Comparator<Candidate> NEWEST_FIRST = (a, b) -> {
        if (a.receivedTs != b.receivedTs) return Long.compare(b.receivedTs, a.receivedTs);
        return Long.compare(idOf(b), idOf(a));
    };

    private static long idOf(final Candidate c) {
        try {
            return Long.parseLong(c.localId);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }
}
