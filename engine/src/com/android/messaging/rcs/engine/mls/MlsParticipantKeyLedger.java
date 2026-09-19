/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.android.messaging.rcs.log.LogMask;

/**
 * Supplies {@link MlsParticipantKeyResync#plan}'s {@code currentKey} by folding successive roster
 * readings: a roster carries no order in time, so the new key is the one absent from the previous
 * reading. Answers {@code ""} wherever the answer would be a guess, since a wrong key removes a
 * member's clients. Pure; the caller owns persistence.
 */
public final class MlsParticipantKeyLedger {

    private MlsParticipantKeyLedger() { }

    /** What one new reading said about one participant. */
    public enum Verdict {
        /** No prior record; {@link Update#currentKey} is set only for a participant on one key. */
        FIRST_SIGHTING,
        /** The same key set as last time. */
        UNCHANGED,
        /** Exactly one never-seen key appeared; it is current. */
        NEW_KEY,
        /** More than one new key appeared at once; refused. */
        AMBIGUOUS,
        /** No readable key on any of this participant's leaves. */
        UNREADABLE
    }

    /**
     * One participant's recorded history: distinct keys in first-seen order. Nothing is removed, so
     * a key that returns does not look new.
     */
    public static final class Entry {
        /** The participant (MSISDN), as the roster spells it. */
        public final String participant;
        /** Distinct participant keys, in first-seen order. Never contains an empty string. */
        public final List<String> keysInSightingOrder;
        /** Wall-clock ms at which the most recent key was first seen; {@code 0} if never. */
        public final long lastNewKeyMs;

        public Entry(final String participant, final List<String> keysInSightingOrder,
                final long lastNewKeyMs) {
            this.participant = participant == null ? "" : participant;
            final List<String> keys = new ArrayList<>();
            if (keysInSightingOrder != null) {
                for (final String k : keysInSightingOrder) {
                    if (k != null && !k.isEmpty() && !keys.contains(k)) {
                        keys.add(k);
                    }
                }
            }
            this.keysInSightingOrder = Collections.unmodifiableList(keys);
            this.lastNewKeyMs = lastNewKeyMs;
        }

        /**
         * An empty history for {@code participant} — what {@link #observe} takes on a first pass.
         */
        public static Entry empty(final String participant) {
            return new Entry(participant, null, 0L);
        }

        /** True iff nothing has ever been recorded. */
        public boolean isEmpty() { return keysInSightingOrder.isEmpty(); }

        /**
         * {@code participant|key1,key2,…|lastNewKeyMs}. Keys are hex SPKI, so neither part contains
         * a separator.
         */
        public String encode() {
            final StringBuilder sb = new StringBuilder(participant).append('|');
            for (int i = 0; i < keysInSightingOrder.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(keysInSightingOrder.get(i));
            }
            return sb.append('|').append(lastNewKeyMs).toString();
        }

        @Override public String toString() {
            return "Entry{" + participant + " keys=" + keysInSightingOrder.size()
                    + " lastNewKeyMs=" + lastNewKeyMs + "}";
        }
    }

    /** {@link Entry#encode}'s inverse; {@code null} for anything it cannot read in full. */
    public static Entry decode(final String line) {
        if (line == null) {
            return null;
        }
        final int a = line.indexOf('|');
        final int b = line.lastIndexOf('|');
        if (a < 0 || b <= a) {
            return null;
        }
        final String participant = line.substring(0, a);
        final String keysPart = line.substring(a + 1, b);
        final long at;
        try {
            at = Long.parseLong(line.substring(b + 1).trim());
        } catch (final NumberFormatException e) {
            return null;
        }
        final List<String> keys = new ArrayList<>();
        if (!keysPart.isEmpty()) {
            for (final String k : keysPart.split(",", -1)) {
                if (k.isEmpty()) {
                    // a dropped key is a corrupted history, not an empty one
                    return null;
                }
                keys.add(k);
            }
        }
        return new Entry(participant, keys, at);
    }

    /** The outcome of folding one reading in. */
    public static final class Update {
        public final Verdict verdict;
        /** The history after this reading; persist this. */
        public final Entry entry;
        /** The {@code currentKey} for {@link MlsParticipantKeyResync#plan}, or {@code ""}. */
        public final String currentKey;
        /** Always populated: the sentence to log, so a refusal is never silent. */
        public final String reason;

        Update(final Verdict verdict, final Entry entry, final String currentKey,
                final String reason) {
            this.verdict = verdict;
            this.entry = entry;
            this.currentKey = currentKey == null ? "" : currentKey;
            this.reason = reason;
        }

        /** True iff a resync plan could be built from this reading. */
        public boolean hasCurrentKey() { return !currentKey.isEmpty(); }

        @Override public String toString() {
            return "Update{" + verdict + " currentKey="
                    + (currentKey.isEmpty() ? "none" : "yes") + " " + reason + "}";
        }
    }

    /**
     * Fold one roster reading for one participant into {@code prior}.
     *
     * @param prior  the recorded history, or {@code null}/{@link Entry#empty} for a first pass
     * @param participant the participant this reading is about
     * @param roster every leaf in the group, the same list {@link MlsParticipantKeyResync#plan}
     *     takes
     * @param atMs   wall clock, recorded only when a new key is first seen
     */
    public static Update observe(final Entry prior, final String participant,
            final Collection<MlsParticipantKeyResync.Leaf> roster, final long atMs) {
        final String who = participant == null ? "" : participant;
        final Entry base = prior == null || !who.equals(prior.participant)
                ? Entry.empty(who) : prior;

        final Set<String> now = new LinkedHashSet<>();
        int unreadable = 0;
        int mine = 0;
        if (roster != null && !who.isEmpty()) {
            for (final MlsParticipantKeyResync.Leaf l : roster) {
                if (l == null || !who.equals(l.participant)) {
                    continue;
                }
                mine++;
                if (l.signedByParticipantKey.isEmpty()) {
                    unreadable++;
                } else {
                    now.add(l.signedByParticipantKey);
                }
            }
        }

        if (now.isEmpty()) {
            return new Update(Verdict.UNREADABLE, base, "",
                    mine == 0
                            ? LogMask.number(who) + " has no leaf in this roster — nothing recorded"
                            : LogMask.number(who) + " has " + mine
                            + " leaf/leaves and NO readable participant "
                                    + "key on any of them; recording nothing, because an absent "
                                    + "value means we did not look, not that a key is old");
        }

        final List<String> merged = new ArrayList<>(base.keysInSightingOrder);
        final List<String> appeared = new ArrayList<>();
        for (final String k : now) {
            if (!merged.contains(k)) {
                merged.add(k);
                appeared.add(k);
            }
        }
        final long lastNewKeyMs = appeared.isEmpty() ? base.lastNewKeyMs : atMs;
        final Entry next = new Entry(who, merged, lastNewKeyMs);
        final String tail = unreadable > 0
                ? " (" + unreadable + " leaf/leaves unreadable and therefore not counted)" : "";

        if (base.isEmpty()) {
            // With no history, a split participant's keys are indistinguishable.
            if (now.size() == 1) {
                return new Update(Verdict.FIRST_SIGHTING, next, first(now),
                        "first sighting of " + LogMask.number(who)
                                + ", on a single key — nothing to resync"
                        + tail);
            }
            return new Update(Verdict.FIRST_SIGHTING, next, "",
                    "first sighting of " + LogMask.number(who) + " ALREADY SPLIT across "
                            + now.size()
                            + " participant keys. No current key: with no earlier reading nothing "
                            + "distinguishes the new key from the superseded one, and choosing "
                            + "wrong removes every client of the current one. A second reading "
                            + "resolves it" + tail);
        }

        if (appeared.isEmpty()) {
            if (now.size() == 1) {
                return new Update(Verdict.UNCHANGED, next, first(now),
                        LogMask.number(who) + " is on one known key — nothing to resync" + tail);
            }
            return new Update(Verdict.UNCHANGED, next, "",
                    LogMask.number(who) + " remains split across " + now.size()
                            + " known participant keys with "
                            + "no new one this reading — still no basis for calling one current"
                            + tail);
        }

        if (appeared.size() > 1) {
            return new Update(Verdict.AMBIGUOUS, next, "",
                    appeared.size() + " participant keys appeared for " + LogMask.number(who)
                            + " at once, so "
                            + "\"the new one\" is not a single key. Refusing rather than picking"
                            + tail);
        }

        final String current = appeared.get(0);
        return new Update(Verdict.NEW_KEY, next, current,
                "a NEW participant key appeared for " + LogMask.number(who) + " alongside "
                        + (now.size() - 1) + " already-known one(s) — the newcomer is current, and "
                        + "this is RCC.16 §10.1.1's condition" + tail);
    }

    private static String first(final Set<String> s) {
        return s.iterator().next();
    }
}
