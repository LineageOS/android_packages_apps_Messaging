/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RCC.16 §10.1.1: plans which clients of a participant whose key rotated are removed by a resync
 * External Commit. An External Commit carries only one Remove proposal, so the rest go as
 * {@code ServerRemove} proposals (RCC.16 §7.11.9). Pure selection and planning; building and
 * sending the commit belongs to the transport.
 */
public final class MlsParticipantKeyResync {

    private MlsParticipantKeyResync() { }

    /** One client (leaf) of a participant, as far as this decision needs to see it. */
    public static final class Leaf {
        /** The leaf index in the ratchet tree — what {@code ServerRemove.to_remove} carries. */
        public final int index;
        /** The participant this leaf belongs to (MSISDN). */
        public final String participant;
        /** An identifier for the participant key that signed this leaf's certificate. */
        public final String signedByParticipantKey;

        public Leaf(final int index, final String participant,
                final String signedByParticipantKey) {
            this.index = index;
            this.participant = participant == null ? "" : participant;
            this.signedByParticipantKey =
                    signedByParticipantKey == null ? "" : signedByParticipantKey;
        }
    }

    /** The planned resync: one Remove, then ServerRemoves for the rest. */
    public static final class Plan {
        /** The single leaf carried as the External Commit's Remove proposal, or {@code -1}. */
        public final int removeLeaf;
        /** Every other stale leaf, each needing a {@code ServerRemove} proposal, in index order. */
        public final List<Integer> serverRemoveLeaves;

        Plan(final int removeLeaf, final List<Integer> serverRemoveLeaves) {
            this.removeLeaf = removeLeaf;
            this.serverRemoveLeaves = Collections.unmodifiableList(
                    serverRemoveLeaves == null ? new ArrayList<>() : serverRemoveLeaves);
        }

        /** True iff nothing needs removing. */
        public boolean isEmpty() { return removeLeaf < 0 && serverRemoveLeaves.isEmpty(); }

        /** Total leaves this plan removes. */
        public int size() { return (removeLeaf < 0 ? 0 : 1) + serverRemoveLeaves.size(); }
    }

    /**
     * Plan the removal of every client of {@code participant} still signed by a superseded key.
     *
     * @param roster            every leaf currently in the group
     * @param participant       the participant whose key rolled
     * @param currentKey        the participant's new key; leaves signed by it stay. A null or
     *                          empty value plans nothing.
     */
    public static Plan plan(final List<Leaf> roster, final String participant,
            final String currentKey) {
        // With no current key every client would compare stale and the whole participant would be
        // removed; an unestablished key means do nothing.
        if (currentKey == null || currentKey.isEmpty()) return new Plan(-1, null);
        final List<Integer> stale = new ArrayList<>();
        if (roster != null && participant != null && !participant.isEmpty()) {
            for (final Leaf l : roster) {
                if (l == null) continue;
                if (!participant.equals(l.participant)) continue;
                if (currentKey.equals(l.signedByParticipantKey)) continue;
                // No recorded signing key means we did not look, not that it is old.
                if (l.signedByParticipantKey.isEmpty()) continue;
                stale.add(l.index);
            }
        }
        Collections.sort(stale);
        if (stale.isEmpty()) return new Plan(-1, null);
        // One Remove per External Commit; the rest travel as ServerRemove (0xF004).
        return new Plan(stale.get(0), new ArrayList<>(stale.subList(1, stale.size())));
    }

    /**
     * Plan from a {@link MlsParticipantKeyLedger} reading; the overload production code should use,
     * since an {@code Update} cannot carry a key the ledger has not established. The empty-key case
     * is refused by {@link #plan} itself.
     *
     * @param roster every leaf currently in the group, the same list the ledger observed
     * @param update the fold of that reading into the participant's recorded history
     */
    public static Plan planFrom(final List<Leaf> roster,
            final MlsParticipantKeyLedger.Update update) {
        if (update == null) {
            return new Plan(-1, null);
        }
        return plan(roster, update.entry.participant, update.currentKey);
    }

    /**
     * The RCC.16 §7.11.9 {@code ServerRemove} proposal body (type 0xF004),
     * {@code struct { uint32 to_remove; }}: the leaf index as four bytes big-endian
     * (RFC 9420 §2.1), not a varint. mls-rs writes it without a length prefix because
     * {@code gsma_rcs_e2ee_feature} is enabled. See docs/mls/rust-core.md.
     *
     * @return the body, or {@code null} for a negative index
     */
    public static byte[] serverRemoveBody(final int leafIndex) {
        if (leafIndex < 0) return null;
        return new byte[] {
                (byte) ((leafIndex >>> 24) & 0xFF),
                (byte) ((leafIndex >>> 16) & 0xFF),
                (byte) ((leafIndex >>> 8) & 0xFF),
                (byte) (leafIndex & 0xFF)
        };
    }

    /** Parse a {@code ServerRemove} body, or {@code -1} if it is not four bytes. */
    public static int parseServerRemoveBody(final byte[] body) {
        if (body == null || body.length != 4) return -1;
        return ((body[0] & 0xFF) << 24) | ((body[1] & 0xFF) << 16)
                | ((body[2] & 0xFF) << 8) | (body[3] & 0xFF);
    }
}
