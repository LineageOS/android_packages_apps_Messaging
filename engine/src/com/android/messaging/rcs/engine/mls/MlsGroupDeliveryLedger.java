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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which members have confirmed delivery of a GROUP message we sent.
 *
 * <p>A group message has N recipients, and one member confirming says nothing about the others. So
 * {@code MlsSendRetentionPolicy.releaseOnPositiveReceipt} refuses to release a group message's
 * replay material on any single receipt, and the bytes sit for 24h instead — a deliberately
 * conservative stand-in, chosen because the right rule (release when EVERY member has confirmed)
 * needed the receipt's sender.
 *
 * <p><b>It has the sender.</b> {@code onGroupImdnReceipt(subId, messageId, fromUri, imdnType)}
 * carries it and has since the per-member group receipt shipped; the proposed v58
 * bump was never needed and the release site's javadoc claiming otherwise was stale. This class is
 * the missing consumer, not new plumbing.
 *
 * <h2>Coverage is judged against the roster passed in, every time</h2>
 *
 * <p>Deliberately no stored roster. Membership changes while a message is in flight — that is the
 * whole subject here — and a ledger holding its own copy would answer "covered" against a
 * group that no longer exists. The caller owns the roster and passes today's, so a member added
 * after the send cannot make an already-covered message look uncovered, and a member removed after
 * the send stops being waited on. Both are the behaviour you want and neither needs a policy here.
 *
 * <h2>Bounded, and it says what it dropped</h2>
 *
 * <p>Unlike §10.8's pending queue — which is unbounded on purpose, because its exact-key drain makes
 * depth cheap — this maps message id to a member set with no drain of its own, and a message whose
 * last member never confirms is never removed by coverage. Its natural end is the retention sweep,
 * so it needs a cap for the same reason the send store has one. Oldest-first eviction, and the
 * eviction is reported: a silently dropped entry becomes a message that can never reach coverage and
 * therefore always waits the full 24h, which is exactly the behaviour this class exists to remove
 * and would be invisible without a log.
 *
 * <p>Not thread-safe by itself; the caller holds the conversation lock.
 */
public final class MlsGroupDeliveryLedger {

    /**
     * Messages tracked at once. Sized to match the send-material store's own cap — tracking
     * coverage for a message whose bytes are already gone buys nothing, so a larger number here
     * would only hold memory for entries with nothing to release.
     */
    public static final int MAX_MESSAGES = 256;

    /** message id → the members that have confirmed it. Insertion-ordered for oldest-first eviction. */
    private final LinkedHashMap<String, Set<String>> mConfirmed =
            new LinkedHashMap<String, Set<String>>();

    /** Message ids evicted by the cap, for the caller to report. Cleared when read. */
    private final List<String> mEvicted = new ArrayList<String>();

    /**
     * Record that {@code member} confirmed {@code messageId}.
     *
     * <p>Idempotent: a duplicate or replayed IMDN from the same member cannot advance coverage
     * twice, which is the same reason KeyPackages are crossed off by ref rather than
     * counting them.
     *
     * @return true if this was new information
     */
    public boolean record(final String messageId, final String member) {
        if (isBlank(messageId) || isBlank(member)) return false;
        Set<String> set = mConfirmed.get(messageId);
        if (set == null) {
            evictIfFull();
            set = new LinkedHashSet<String>();
            mConfirmed.put(messageId, set);
        }
        return set.add(normalise(member));
    }

    /**
     * Has every member of {@code roster} confirmed {@code messageId}?
     *
     * <p><b>An empty or null roster is NOT coverage.</b> "Nobody left to hear from" and "we could
     * not read the roster" are indistinguishable from in here, and treating the second as the first
     * would release the replay material of a message nobody has confirmed — the precise failure
     * we have already recorded, where the bytes went and the next member's failure report reached a
     * correct remedy with nothing to resend. When in doubt, keep the bytes: the cost is memory for
     * up to 24h, against a message that can never be resent.
     *
     * @param roster the members expected to confirm, EXCLUDING ourselves — the caller strips self,
     *               because we never send ourselves an IMDN and a roster containing us could never
     *               be covered
     */
    public boolean isCovered(final String messageId, final Collection<String> roster) {
        if (isBlank(messageId) || roster == null || roster.isEmpty()) return false;
        final Set<String> set = mConfirmed.get(messageId);
        if (set == null) return false;
        for (final String m : roster) {
            if (isBlank(m)) continue;
            if (!set.contains(normalise(m))) return false;
        }
        return true;
    }

    /** How many distinct members have confirmed {@code messageId}. */
    public int confirmedCount(final String messageId) {
        final Set<String> set = isBlank(messageId) ? null : mConfirmed.get(messageId);
        return set == null ? 0 : set.size();
    }

    /** Stop tracking {@code messageId} — released, failed, or aged out. */
    public void forget(final String messageId) {
        if (!isBlank(messageId)) mConfirmed.remove(messageId);
    }

    /** Messages currently tracked. */
    public int size() { return mConfirmed.size(); }

    /**
     * Message ids dropped by the cap since the last call, and clears the list.
     *
     * <p>Read it and log it. An entry evicted here can never reach coverage afterwards, so its
     * message silently reverts to waiting the full retention window.
     */
    public List<String> takeEvicted() {
        final List<String> out = new ArrayList<String>(mEvicted);
        mEvicted.clear();
        return out;
    }

    private void evictIfFull() {
        while (mConfirmed.size() >= MAX_MESSAGES) {
            final Map.Entry<String, Set<String>> oldest = mConfirmed.entrySet().iterator().next();
            mEvicted.add(oldest.getKey());
            mConfirmed.remove(oldest.getKey());
        }
    }

    /**
     * Compare members by DIGITS ONLY.
     *
     * <p>The roster and the IMDN sender do not always agree on presentation — one side may carry
     * {@code +15551234567}, the other {@code 15551234567} or a {@code sip:} / {@code tel:} URI. A
     * literal string compare there would leave a message permanently one member short of coverage
     * and, worse, would do it silently: the release simply never fires and the 24h path takes over,
     * which looks exactly like this class not being wired up at all.
     */
    private static String normalise(final String e164) {
        final StringBuilder sb = new StringBuilder(e164.length());
        for (int i = 0; i < e164.length(); i++) {
            final char c = e164.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBlank(final String s) {
        return s == null || s.isEmpty();
    }
}
