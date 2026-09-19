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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Pins where {@link MlsParticipantKeyResync#plan}'s {@code currentKey} comes from.
 *
 * <p><b>Why a host test.</b> On a device a ledger that picks the WRONG key and one that picks the
 * right key produce the same log line, right up until somebody is removed from a group they are
 * still in. The outcome this is guarding against is not observable at the moment it is decided.
 *
 * <p>Every case pairs the ledger with {@code plan()} rather than asserting on the ledger alone,
 * because the property that matters is what gets REMOVED, and a currentKey is only as safe as the
 * plan it produces.
 */
public class MlsParticipantKeyLedgerTest {

    private static final String A = "15715550107";
    private static final String B = "15715550103";

    // Real shapes: 91-byte P-256 SPKIs rendered as hex, distinguished by their tails — the same
    // rendering a key adopted after a 24-char PREFIX made three different keys look identical.
    private static final String K1 = "30593013" + rep("a", 174) + "c1d77f0f47a86222";
    private static final String K2 = "30593013" + rep("b", 174) + "7e528f38cf7b2cfd";
    private static final String K3 = "30593013" + rep("c", 174) + "01454c2095525c90";

    private static String rep(final String s, final int n) {
        final StringBuilder sb = new StringBuilder();
        while (sb.length() < n) {
            sb.append(s);
        }
        return sb.substring(0, n);
    }

    private static MlsParticipantKeyResync.Leaf leaf(final int idx, final String who,
            final String key) {
        return new MlsParticipantKeyResync.Leaf(idx, who, key);
    }

    private static List<MlsParticipantKeyResync.Leaf> roster(
            final MlsParticipantKeyResync.Leaf... ls) {
        return new ArrayList<>(Arrays.asList(ls));
    }

    // ---- the refusal that is the whole point ---------------------------------------------------

    /**
     * A FIRST reading that already shows two keys yields NO current key.
     *
     * <p>This is the case that makes the class safe. With no history, either key could be the
     * survivor; choosing wrong removes every client of the real current one, which is
     * unrecoverable for them and indistinguishable from being kicked.
     */
    @Test public void a_first_sighting_that_is_already_split_refuses_to_name_a_current_key() {
        final MlsParticipantKeyLedger.Update u = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1), leaf(1, A, K2), leaf(2, B, K3)), 1000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.FIRST_SIGHTING, u.verdict);
        assertFalse(u.reason, u.hasCurrentKey());
        assertTrue(u.reason, u.reason.contains("ALREADY SPLIT"));
        // And the plan built from it removes NOBODY.
        assertTrue(MlsParticipantKeyResync.plan(
                roster(leaf(0, A, K1), leaf(1, A, K2)), A, u.currentKey).isEmpty());
        // Both keys are recorded, so the SECOND reading has something to compare against.
        assertEquals(2, u.entry.keysInSightingOrder.size());
    }

    /** A first sighting on ONE key is an answer: "nothing to do" is not a guess. */
    @Test public void a_first_sighting_on_one_key_names_it_and_plans_nothing() {
        final MlsParticipantKeyLedger.Update u = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1), leaf(1, B, K2)), 1000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.FIRST_SIGHTING, u.verdict);
        assertEquals(K1, u.currentKey);
        assertTrue(MlsParticipantKeyResync.plan(roster(leaf(0, A, K1)), A, u.currentKey).isEmpty());
    }

    // ---- the case §10.1.1 is for ---------------------------------------------------------------

    /**
     * Reading 1 single-key, reading 2 split: the key that was not there before is current, and the
     * leaves on the old key are exactly what {@code plan()} removes.
     */
    @Test public void a_key_appearing_after_a_known_one_is_current_and_the_old_leaves_are_planned() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1), leaf(1, A, K1), leaf(2, B, K3)), 1000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.FIRST_SIGHTING, r1.verdict);
        assertEquals(K1, r1.currentKey);

        final List<MlsParticipantKeyResync.Leaf> later =
                roster(leaf(0, A, K1), leaf(1, A, K1), leaf(2, B, K3), leaf(3, A, K2));
        final MlsParticipantKeyLedger.Update r2 =
                MlsParticipantKeyLedger.observe(r1.entry, A, later, 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.NEW_KEY, r2.verdict);
        assertEquals(K2, r2.currentKey);
        assertEquals(2000L, r2.entry.lastNewKeyMs);

        final MlsParticipantKeyResync.Plan p =
                MlsParticipantKeyResync.plan(later, A, r2.currentKey);
        assertEquals("the two K1 leaves, and nothing of B's", 2, p.size());
        assertEquals(0, p.removeLeaf);
        assertEquals(Arrays.asList(1), p.serverRemoveLeaves);
    }

    /** Two new keys at once is not "the new one" — refused, not resolved. */
    @Test public void two_keys_appearing_at_once_is_ambiguous_and_plans_nothing() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1)), 1000L);
        final List<MlsParticipantKeyResync.Leaf> later =
                roster(leaf(0, A, K1), leaf(1, A, K2), leaf(2, A, K3));
        final MlsParticipantKeyLedger.Update r2 =
                MlsParticipantKeyLedger.observe(r1.entry, A, later, 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.AMBIGUOUS, r2.verdict);
        assertFalse(r2.hasCurrentKey());
        assertTrue(MlsParticipantKeyResync.plan(later, A, r2.currentKey).isEmpty());
    }

    /** Nothing new, still split: the split predates our history, so we still cannot say. */
    @Test public void a_split_that_predates_the_history_stays_unresolved_across_readings() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1), leaf(1, A, K2)), 1000L);
        final MlsParticipantKeyLedger.Update r2 = MlsParticipantKeyLedger.observe(
                r1.entry, A, roster(leaf(0, A, K1), leaf(1, A, K2)), 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.UNCHANGED, r2.verdict);
        assertFalse(r2.reason, r2.hasCurrentKey());
        assertTrue(r2.reason, r2.reason.contains("remains split"));
    }

    @Test public void an_unchanged_single_key_reading_keeps_naming_it() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1)), 1000L);
        final MlsParticipantKeyLedger.Update r2 = MlsParticipantKeyLedger.observe(
                r1.entry, A, roster(leaf(0, A, K1), leaf(5, A, K1)), 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.UNCHANGED, r2.verdict);
        assertEquals(K1, r2.currentKey);
        assertEquals("no new key means the timestamp does not move", 1000L, r2.entry.lastNewKeyMs);
    }

    // ---- unreadable leaves ---------------------------------------------------------------------

    /**
     * A leaf with no readable key records NOTHING and names no current key.
     *
     * <p>{@code memberParticipantKeys} yields an empty key for a certificate it could not parse,
     * and {@code plan()}'s own guard reads that as "we did not look". The ledger must agree, or a
     * participant whose certificates we simply failed to parse would look like one with no keys.
     */
    @Test public void a_participant_with_only_unreadable_leaves_records_nothing() {
        final MlsParticipantKeyLedger.Update u = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, ""), leaf(1, A, "")), 1000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.UNREADABLE, u.verdict);
        assertFalse(u.hasCurrentKey());
        assertTrue(u.entry.isEmpty());
        assertTrue(u.reason, u.reason.contains("did not look"));
    }

    /**
     * An unreadable leaf beside a readable one does NOT block the readable key, and does not count
     * as a second key.
     *
     * <p>Treating it as a distinct key would manufacture a split out of a parse failure; ignoring
     * it silently would hide that a leaf is unaccounted for. It is excluded from the key set and
     * named in the reason.
     */
    @Test public void an_unreadable_leaf_is_excluded_from_the_key_set_but_reported() {
        final MlsParticipantKeyLedger.Update u = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1), leaf(1, A, "")), 1000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.FIRST_SIGHTING, u.verdict);
        assertEquals(K1, u.currentKey);
        assertEquals(1, u.entry.keysInSightingOrder.size());
        assertTrue(u.reason, u.reason.contains("1 leaf/leaves unreadable"));
    }

    /**
     * AND THE CASE THAT WOULD REMOVE SOMEBODY: an unreadable leaf must never be planned for
     * removal, even when a current key IS established.
     */
    @Test public void an_unreadable_leaf_is_never_removed_even_with_a_known_current_key() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1)), 1000L);
        final List<MlsParticipantKeyResync.Leaf> later =
                roster(leaf(0, A, K1), leaf(1, A, K2), leaf(2, A, ""));
        final MlsParticipantKeyLedger.Update r2 =
                MlsParticipantKeyLedger.observe(r1.entry, A, later, 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.NEW_KEY, r2.verdict);
        assertEquals(K2, r2.currentKey);
        final MlsParticipantKeyResync.Plan p =
                MlsParticipantKeyResync.plan(later, A, r2.currentKey);
        assertEquals("only leaf 0 — the unreadable leaf 2 is not stale, it is unread", 1, p.size());
        assertEquals(0, p.removeLeaf);
    }

    // ---- scoping -------------------------------------------------------------------------------

    /** A participant absent from the roster records nothing rather than forgetting its history. */
    @Test public void a_participant_absent_from_the_roster_keeps_its_history() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1)), 1000L);
        final MlsParticipantKeyLedger.Update r2 = MlsParticipantKeyLedger.observe(
                r1.entry, A, roster(leaf(0, B, K3)), 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.UNREADABLE, r2.verdict);
        assertEquals(1, r2.entry.keysInSightingOrder.size());
        assertTrue(r2.reason, r2.reason.contains("no leaf in this roster"));
    }

    /** A history for somebody else is not applied to this participant. */
    @Test public void a_mismatched_prior_entry_is_ignored_rather_than_merged() {
        final MlsParticipantKeyLedger.Entry other =
                new MlsParticipantKeyLedger.Entry(B, Arrays.asList(K1, K2), 500L);
        final MlsParticipantKeyLedger.Update u = MlsParticipantKeyLedger.observe(
                other, A, roster(leaf(0, A, K1), leaf(1, A, K2)), 1000L);
        assertEquals("B's history must not make A's split look resolved",
                MlsParticipantKeyLedger.Verdict.FIRST_SIGHTING, u.verdict);
        assertFalse(u.hasCurrentKey());
        assertEquals(A, u.entry.participant);
    }

    /** A key that left the group is NOT forgotten — it must not look new if it returns. */
    @Test public void a_key_that_leaves_the_group_stays_in_the_history() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1)), 1000L);
        final MlsParticipantKeyLedger.Update r2 = MlsParticipantKeyLedger.observe(
                r1.entry, A, roster(leaf(0, A, K1), leaf(1, A, K2)), 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.NEW_KEY, r2.verdict);
        // K1's leaf is removed; only K2 remains in the group.
        final MlsParticipantKeyLedger.Update r3 = MlsParticipantKeyLedger.observe(
                r2.entry, A, roster(leaf(1, A, K2)), 3000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.UNCHANGED, r3.verdict);
        assertEquals(K2, r3.currentKey);
        // K1 comes back (a restored device). It is NOT a new key, so it does not become current.
        final MlsParticipantKeyLedger.Update r4 = MlsParticipantKeyLedger.observe(
                r3.entry, A, roster(leaf(1, A, K2), leaf(2, A, K1)), 4000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.UNCHANGED, r4.verdict);
        assertFalse("a returning old key must not be promoted to current", r4.hasCurrentKey());
    }

    // ---- persistence ---------------------------------------------------------------------------

    @Test public void an_entry_round_trips_through_encode_decode() {
        final MlsParticipantKeyLedger.Entry e =
                new MlsParticipantKeyLedger.Entry(A, Arrays.asList(K1, K2, K3), 1788843256529L);
        final MlsParticipantKeyLedger.Entry back = MlsParticipantKeyLedger.decode(e.encode());
        assertNotNull(back);
        assertEquals(A, back.participant);
        assertEquals(Arrays.asList(K1, K2, K3), back.keysInSightingOrder);
        assertEquals(1788843256529L, back.lastNewKeyMs);
        assertEquals(e.encode(), back.encode());
    }

    @Test public void an_empty_history_round_trips() {
        final MlsParticipantKeyLedger.Entry e = MlsParticipantKeyLedger.Entry.empty(A);
        final MlsParticipantKeyLedger.Entry back = MlsParticipantKeyLedger.decode(e.encode());
        assertNotNull(back);
        assertTrue(back.isEmpty());
        assertEquals(A, back.participant);
    }

    /**
     * A corrupted line decodes to NOTHING, never to a partial history.
     *
     * <p>A history missing one key presents that key as NEW the next time it is seen, which makes
     * the ledger name a superseded key as current and plan the removal of the real one.
     */
    @Test public void a_corrupted_line_decodes_to_null_not_to_a_partial_history() {
        assertNull(MlsParticipantKeyLedger.decode(null));
        assertNull(MlsParticipantKeyLedger.decode(""));
        assertNull(MlsParticipantKeyLedger.decode("no-separators"));
        assertNull(MlsParticipantKeyLedger.decode(A + "|" + K1));           // no timestamp
        assertNull(MlsParticipantKeyLedger.decode(A + "|" + K1 + "|abc"));  // bad timestamp
        assertNull(MlsParticipantKeyLedger.decode(A + "|" + K1 + ",|7"));   // a dropped key
    }

    /** Duplicates in a supplied history collapse rather than making a key look present twice. */
    @Test public void duplicate_keys_in_a_constructed_entry_collapse() {
        final MlsParticipantKeyLedger.Entry e =
                new MlsParticipantKeyLedger.Entry(A, Arrays.asList(K1, K1, "", null, K2), 1L);
        assertEquals(Arrays.asList(K1, K2), e.keysInSightingOrder);
    }
    // ---- planFrom: the path that cannot be handed a guess --------------------------

    /**
     * A ledger Update that established no current key plans NOTHING through {@code planFrom},
     * whatever the roster looks like.
     *
     * <p>This is the property the wiring needs: the caller does not get to decide what to pass, so
     * it cannot pass a key it never established.
     *
     * <p><b>This is a COMPOSITION test, and it is worth saying which part it pins.</b> The empty
     * key is refused by {@code plan()}'s own backstop, not by anything in {@code planFrom} — a
     * duplicate check there was deleted after a mutation showed no test could tell whether it
     * existed. So what this asserts is that routing a ledger refusal through {@code planFrom}
     * still removes nobody, which is the property the caller depends on; it does not, and cannot,
     * pin a second guard. The null arm below IS pinned — without it {@code planFrom} throws.
     */
    @Test public void planFrom_an_unestablished_update_removes_nobody() {
        final List<MlsParticipantKeyResync.Leaf> split =
                roster(leaf(0, A, K1), leaf(1, A, K2), leaf(2, A, K1));
        final MlsParticipantKeyLedger.Update first =
                MlsParticipantKeyLedger.observe(null, A, split, 1000L);
        assertFalse("a first sighting already split establishes nothing", first.hasCurrentKey());
        assertTrue(MlsParticipantKeyResync.planFrom(split, first).isEmpty());
        assertTrue("a null update is the same answer",
                MlsParticipantKeyResync.planFrom(split, null).isEmpty());
    }

    /**
     * And when the ledger HAS established one, planFrom plans exactly what plan() would — so the
     * safe path is not a weaker path.
     */
    @Test public void planFrom_matches_plan_once_a_key_is_established() {
        final MlsParticipantKeyLedger.Update r1 = MlsParticipantKeyLedger.observe(
                null, A, roster(leaf(0, A, K1), leaf(1, A, K1)), 1000L);
        final List<MlsParticipantKeyResync.Leaf> later =
                roster(leaf(0, A, K1), leaf(1, A, K1), leaf(2, A, K2));
        final MlsParticipantKeyLedger.Update r2 =
                MlsParticipantKeyLedger.observe(r1.entry, A, later, 2000L);
        assertEquals(MlsParticipantKeyLedger.Verdict.NEW_KEY, r2.verdict);

        final MlsParticipantKeyResync.Plan viaLedger = MlsParticipantKeyResync.planFrom(later, r2);
        final MlsParticipantKeyResync.Plan viaString =
                MlsParticipantKeyResync.plan(later, A, K2);
        assertEquals(viaString.size(), viaLedger.size());
        assertEquals(viaString.removeLeaf, viaLedger.removeLeaf);
        assertEquals(viaString.serverRemoveLeaves, viaLedger.serverRemoveLeaves);
        assertEquals("the two K1 leaves", 2, viaLedger.size());
    }

}
