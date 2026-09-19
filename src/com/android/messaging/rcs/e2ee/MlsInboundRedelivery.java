/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * Whether an inbound MLS ciphertext is a redelivery of a message this device already holds, asked
 * before the decrypt. The provider delivers a message again when our confirmation did not reach
 * it: the app was killed while frozen, or the provider restarted. A stored message's generation is
 * spent, so its ciphertext no longer decrypts, and a failed decrypt enters RCC.16 §10 recovery and
 * ends in a failed-to-decrypt report; the sender then resends under a new id, which is stored as a
 * second row. A redelivery is confirmed as a stored message is, and nothing else is sent.
 *
 * <p>The claim and the store are injected so the decision is host-testable. The check after the
 * decrypt stays: it closes the race between two dispatches that both pass this one.
 */
public final class MlsInboundRedelivery {

    private MlsInboundRedelivery() {}

    /** Message ids this process has claimed for insertion; a claim is never released. */
    public interface Claims {
        boolean holds(String messageId);
    }

    /** The message store: whether a row carries this {@code rcs_message_id}. */
    public interface Store {
        boolean holds(String messageId) throws Exception;
    }

    /** What the lookup found. Only {@link #CLAIMED} and {@link #STORED} skip the decrypt. */
    public enum Verdict {
        /** Not seen: decrypt it. */
        NEW,
        /** Claimed for insertion by an earlier dispatch in this process. */
        CLAIMED,
        /** A row already carries the id. */
        STORED,
        /** The store could not be read: decrypt it, since a lost message is worse. */
        UNREAD;

        public boolean isRedelivery() {
            return this == CLAIMED || this == STORED;
        }
    }

    /** The verdict for {@code messageId}; an id that is null or empty is always {@link #NEW}. */
    public static Verdict beforeDecrypt(final String messageId, final Claims claims,
            final Store store) {
        if (messageId == null || messageId.isEmpty()) return Verdict.NEW;
        if (claims.holds(messageId)) return Verdict.CLAIMED;
        try {
            return store.holds(messageId) ? Verdict.STORED : Verdict.NEW;
        } catch (final Exception e) {
            return Verdict.UNREAD;
        }
    }
}
