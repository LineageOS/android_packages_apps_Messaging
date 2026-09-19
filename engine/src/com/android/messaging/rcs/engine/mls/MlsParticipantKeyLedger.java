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
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where {@link MlsParticipantKeyResync#plan}'s {@code currentKey} comes from.
 *
 * <h2>The starvation this ends, and why it was not an oversight</h2>
 *
 * <p>{@code plan()} has been built, host-tested and UNCALLED since it landed, because nothing
 * supplied the one input it needs: which of a participant's keys is the CURRENT one.
 * {@code OpenMlsSession.memberParticipantKeys} answers the other half — <i>which participant key
 * signed each leaf</i>, device-verified, and cross-checked across two groups where the three shared
 * participants carry byte-identical keys at different leaf indices. What it cannot answer, <b>and
 * what no single roster reading can</b>, is which of two keys is the new one. A roster is a set; it
 * carries no order in time. Leaf index is not add order once anything has been removed.
 *
 * <p>That is why the next step was "take a second reading". This class is that step,
 * mechanised: fold successive readings together and the NEW key is the one that was not there
 * before.
 *
 * <h2>THE REFUSAL IS THE POINT</h2>
 *
 * <p>{@code plan()} plans REMOVALS. Removing a member on wrong metadata is unrecoverable for them
 * and, from their side, indistinguishable from being kicked. So the ledger answers {@code ""} —
 * "we have not established which key is current" — in every case where the answer would be a
 * guess, and {@code plan()} already treats an empty key as "we did not look". Specifically, a
 * FIRST sighting that already shows a participant on two keys yields NO current key: with no
 * history there is nothing that distinguishes the newcomer from the survivor, and picking one
 * would remove every client of the other.
 *
 * <p>A single-key participant is different and is answered: {@code currentKey} is that key, which
 * makes {@code plan()} return an empty plan. "Nothing to do" is a real answer, not a guess.
 *
 * <h2>Trust model — issuer substitution, not peer forgery</h2>
 *
 * <p>"The key that appeared most recently is current" is only as trustworthy as whoever attested
 * it. That is not a peer: a leaf reaches a roster only by chaining to a configured trust anchor,
 * and the {@code .4} ParticipantInformation is PoP-bound and minted by the participant's KDS. A
 * peer cannot mint a {@code .4} for somebody else. The residual exposure is a substituted ISSUER,
 * which is the trust-anchor question and not this one. This class does not change
 * that rating in either direction.
 *
 * <h2>What actually rolls a participant key — and why the seven-day clock is NOT it</h2>
 *
 * <p>Source-verified, because the experiment was waiting on it:
 *
 * <ul>
 *   <li><b>Ours does not roll on an identity refresh.</b> {@code MlsKeyStore.getOrCreate} returns a
 *       PERSISTED keypair and mints only when the store is empty; in the default {@code derive}
 *       mode that mint is {@code MlsIdentityKey.deriveP256(stableDeviceSecret, LABEL_PARTICIPANT)},
 *       deterministic from {@code ANDROID_ID} — "the SAME (secret, label) ALWAYS yields the SAME
 *       key — across process restarts, pm-clear, and re-cold". {@code MlsKeyStore.clear} has ONE
 *       caller, the {@code MLS_KDS_PROVISION} debug receiver's {@code freshKey} single-shot, and no
 *       refresh path touches it. So the 7-day {@code IDENTITY_REFRESH} re-mints the CERTIFICATE
 *       over the same participant key.</li>
 *   <li><b>Google Messages' does not either.</b> It persists a random {@code SignatureKeyPair} in its
 *       self-participant store keyed by provisioning sessionId, and that store survives config
 *       refreshes, so Google Messages re-mints indefinitely with the
 *       same key.</li>
 *   <li><b>And a rotation is not free.</b> The KDS pins the reg-id&harr;key binding on whatever key
 *       we present first and thereafter checks only that we present the SAME one; a changed key
 *       against a surviving reg-id is the {@code INVALID_ARGUMENT} we have measured. A real
 *       roll therefore comes with a NEW provisioning identity — a factory reset, or a full re-cold
 *       — which is exactly the situation that leaves a participant's OLD clients in our groups
 *       while their new one joins on a new key.</li>
 * </ul>
 *
 * <p>So §10.1.1's trigger is re-provisioning, not ageing, and an experiment that waits for a
 * refresh will record "no change" forever. That is a finding about the experiment, not evidence
 * about the extraction.
 *
 * <p>Pure Java, no Android, no storage: the caller owns persistence and {@link Entry#encode}
 * /{@link #decode} give it a one-line round trip. The DECISION is what is worth testing, and it is
 * unreachable from a device — on a phone, a ledger that picks the wrong key and one that picks the
 * right key produce the same log line right up until somebody is removed from a group.
 */
public final class MlsParticipantKeyLedger {

    private MlsParticipantKeyLedger() { }

    /** What one new reading said about one participant. */
    public enum Verdict {
        /** No prior record. {@link Update#currentKey} is set only if the participant is on ONE key. */
        FIRST_SIGHTING,
        /** The same key set as last time. */
        UNCHANGED,
        /** Exactly one key appeared that we had never seen for this participant — it is current. */
        NEW_KEY,
        /**
         * More than one new key appeared at once, so "the new one" is not a single thing.
         * Refused rather than resolved: two simultaneous newcomers means two of the three keys
         * present could each be the survivor.
         */
        AMBIGUOUS,
        /** No readable key on any of this participant's leaves. Nothing to record and nothing to do. */
        UNREADABLE
    }

    /**
     * One participant's recorded history.
     *
     * <p>{@link #keysInSightingOrder} is the whole state: keys in the order they were FIRST seen,
     * which is the only ordering that survives the fact that a roster carries none. Nothing is ever
     * removed from it — a key that leaves the group is still a key this participant once used, and
     * forgetting it would make it look new if it ever came back (a device restored from a backup,
     * a client rejoining).
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

        /** An empty history for {@code participant} — what {@link #observe} takes on a first pass. */
        public static Entry empty(final String participant) {
            return new Entry(participant, null, 0L);
        }

        /** True iff nothing has ever been recorded. */
        public boolean isEmpty() { return keysInSightingOrder.isEmpty(); }

        /**
         * {@code participant|key1,key2,…|lastNewKeyMs} — a flat line for whatever the caller
         * persists into.
         *
         * <p>The participant is written first and the keys are hex SPKI, so neither can contain the
         * separators. A malformed line decodes to an EMPTY entry rather than a partial one: a
         * half-read history would present a real key as a new one.
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

    /** {@link Entry#encode}'s inverse. Returns {@code null} for anything it cannot read in full. */
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
                    return null;              // a dropped key is a corrupted history, not an empty one
                }
                keys.add(k);
            }
        }
        return new Entry(participant, keys, at);
    }

    /** The outcome of folding one reading in. */
    public static final class Update {
        public final Verdict verdict;
        /** The history AFTER this reading. Persist this. */
        public final Entry entry;
        /**
         * What may be handed to {@link MlsParticipantKeyResync#plan} as {@code currentKey}, or
         * {@code ""} when the ledger declines to say. Never a guess.
         */
        public final String currentKey;
        /** Always populated — the sentence to log, so a refusal is never silent. */
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
     * @param roster every leaf currently in the group — this method selects {@code participant}'s,
     *     so the caller hands over the same list {@link MlsParticipantKeyResync#plan} takes and the
     *     two cannot disagree about who belongs to whom
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
                            ? who + " has no leaf in this roster — nothing recorded"
                            : who + " has " + mine + " leaf/leaves and NO readable participant "
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
            // FIRST SIGHTING. One key is an answer; two is not, and that distinction is the whole
            // safety property — with no history, either of the two could be the survivor, and
            // picking wrong removes every client of the real current key.
            if (now.size() == 1) {
                return new Update(Verdict.FIRST_SIGHTING, next, first(now),
                        "first sighting of " + who + ", on a single key — nothing to resync" + tail);
            }
            return new Update(Verdict.FIRST_SIGHTING, next, "",
                    "first sighting of " + who + " ALREADY SPLIT across " + now.size()
                            + " participant keys. No current key: with no earlier reading nothing "
                            + "distinguishes the new key from the superseded one, and choosing "
                            + "wrong removes every client of the current one. A second reading "
                            + "resolves it" + tail);
        }

        if (appeared.isEmpty()) {
            if (now.size() == 1) {
                return new Update(Verdict.UNCHANGED, next, first(now),
                        who + " is on one known key — nothing to resync" + tail);
            }
            // Still split, and nothing new: the split predates our history, so we still cannot say.
            return new Update(Verdict.UNCHANGED, next, "",
                    who + " remains split across " + now.size() + " known participant keys with "
                            + "no new one this reading — still no basis for calling one current"
                            + tail);
        }

        if (appeared.size() > 1) {
            return new Update(Verdict.AMBIGUOUS, next, "",
                    appeared.size() + " participant keys appeared for " + who + " at once, so "
                            + "\"the new one\" is not a single key. Refusing rather than picking"
                            + tail);
        }

        final String current = appeared.get(0);
        return new Update(Verdict.NEW_KEY, next, current,
                "a NEW participant key appeared for " + who + " alongside "
                        + (now.size() - 1) + " already-known one(s) — the newcomer is current, and "
                        + "this is RCC.16 §10.1.1's condition" + tail);
    }

    private static String first(final Set<String> s) {
        return s.iterator().next();
    }
}
