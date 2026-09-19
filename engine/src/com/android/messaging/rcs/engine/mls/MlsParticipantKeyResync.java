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
import java.util.Collections;
import java.util.List;

/**
 * RCC.16 <b>§10.1.1</b> — the participant-key rotation branch of self-heal.
 *
 * <h2>This is a v3.0 obligation, not a v4.0 feature</h2>
 *
 * §10.1 and §10.1.1 both exist in v3.0; only §10.1.2 (Enhanced) is new. v3.0 already said:
 *
 * <blockquote>
 * the client must use resync External Commit to replace <b>all</b> the Clients of the Participant
 * with KeyPackages signed with the new Participant Key
 * </blockquote>
 *
 * <p>and we did not implement it. {@code MlsConversationRecord.lastParticipantKeyUpdate} records
 * <em>that</em> a key rolled; nothing acted on it. v4.0 rewrites HOW, not WHETHER:
 *
 * <blockquote>
 * the client shall do a resync External Commit that <b>removes</b> all of the Clients of the
 * Participant that have certificates signed with the old Participant Key. Since the External Commit
 * can only have one Remove proposal, the client shall use <b>{@code ServerRemove} Proposals for all
 * removes after the first Client</b>.
 * </blockquote>
 *
 * <h2>The v3.0/v4.0 difference is not cosmetic</h2>
 *
 * v3.0 <em>replaces</em> the stale clients (remove and re-add on the new key); v4.0 <em>removes</em>
 * them and lets them rejoin. The v4.0 form also states a structural constraint MLS imposes that v3.0
 * left implicit: <b>an External Commit may carry only ONE Remove proposal</b>. So removing N stale
 * clients needs one Remove plus N−1 {@code ServerRemove} proposals — and until v4.0 published
 * {@code struct ServerRemove { uint32 to_remove }} we had no body for those, which is why this could
 * not have been built correctly before now even though the obligation is v3.0's.
 *
 * <p>Pure leaf selection and proposal planning. It decides WHICH leaves and in WHAT shape; building
 * and sending the commit belongs to the transport.
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

        public Leaf(final int index, final String participant, final String signedByParticipantKey) {
            this.index = index;
            this.participant = participant == null ? "" : participant;
            this.signedByParticipantKey =
                    signedByParticipantKey == null ? "" : signedByParticipantKey;
        }
    }

    /** The planned resync: one Remove, then ServerRemoves for the rest. */
    public static final class Plan {
        /** The single leaf carried as the External Commit's one Remove proposal, or {@code -1}. */
        public final int removeLeaf;
        /** Every other stale leaf, each needing a {@code ServerRemove} proposal. In index order. */
        public final List<Integer> serverRemoveLeaves;

        Plan(final int removeLeaf, final List<Integer> serverRemoveLeaves) {
            this.removeLeaf = removeLeaf;
            this.serverRemoveLeaves = Collections.unmodifiableList(
                    serverRemoveLeaves == null ? new ArrayList<>() : serverRemoveLeaves);
        }

        /** True iff nothing needs removing — the participant's clients are all on the current key. */
        public boolean isEmpty() { return removeLeaf < 0 && serverRemoveLeaves.isEmpty(); }

        /** Total leaves this plan removes. */
        public int size() { return (removeLeaf < 0 ? 0 : 1) + serverRemoveLeaves.size(); }
    }

    /**
     * Plan the removal of every client of {@code participant} still signed by a superseded key.
     *
     * @param roster            every leaf currently in the group
     * @param participant       the participant whose key rolled
     * @param currentKey        the identifier of the participant's NEW key; leaves signed by it
     *                          stay. An ABSENT value plans nothing — see below.
     */
    public static Plan plan(final List<Leaf> roster, final String participant,
            final String currentKey) {
        // AN UNKNOWN CURRENT KEY PLANS NOTHING. This is the mirror of the per-leaf guard further
        // down and it is the MORE dangerous of the two, because it fails in the opposite
        // direction: with a null or empty currentKey the equality test below matches no leaf at
        // all, so every client of the participant is classified stale and the plan removes the
        // whole participant from the group. An absent currentKey means "we have not established
        // which key is current" — exactly what MlsParticipantKeyLedger returns for a participant
        // whose split predates our history — and the answer to that is to do nothing.
        //
        // It has never fired because nothing calls plan() yet, and it was found by
        // pairing the ledger's refusal with the plan it produces rather than by reading either
        // in isolation: the ledger correctly refused to name a key, and handing that refusal
        // straight to plan() removed everybody.
        if (currentKey == null || currentKey.isEmpty()) return new Plan(-1, null);
        final List<Integer> stale = new ArrayList<>();
        if (roster != null && participant != null && !participant.isEmpty()) {
            for (final Leaf l : roster) {
                if (l == null) continue;
                if (!participant.equals(l.participant)) continue;
                // Leaves already on the current key are exactly what we are resyncing TO.
                if (currentKey.equals(l.signedByParticipantKey)) continue;
                // A leaf with no recorded signing key is NOT assumed stale. Removing a member on
                // missing metadata is unrecoverable for them and indistinguishable, from their side,
                // from being kicked; an absent value means we did not look, not that it is old.
                if (l.signedByParticipantKey.isEmpty()) continue;
                stale.add(l.index);
            }
        }
        Collections.sort(stale);
        if (stale.isEmpty()) return new Plan(-1, null);
        // MLS allows an External Commit exactly ONE Remove proposal; everything after the first
        // travels as a ServerRemove (0xF004), whose body v4.0 finally defines.
        return new Plan(stale.get(0), new ArrayList<>(stale.subList(1, stale.size())));
    }

    /**
     * Plan from a {@link MlsParticipantKeyLedger} reading — <b>the overload the wiring should
     * use</b>.
     *
     * <p>{@link #plan} takes a bare {@code currentKey} string, and the one thing a caller must not
     * do is hand it a key it has not established. That failure removed EVERY client of the
     * participant until {@code 572d92d9} added the backstop, and a backstop is not a design: the
     * caller still has to decide what to pass, and the wrong answer is a catastrophic group wipe.
     *
     * <p>This overload removes the decision. A {@link MlsParticipantKeyLedger.Update} either
     * carries an established current key or explicitly does not, and the ledger refuses to invent
     * one — a first sighting already split across two keys yields nothing, because with no history
     * neither key is distinguishable from the other. So there is no way to reach a removal through
     * here without the evidence that justifies it, and the plan is empty until there is.
     *
     * <p>{@link #plan} stays public: it is what the host tests drive, and narrowing it would move
     * the test surface rather than the risk. The guarantee is that the PRODUCTION path goes
     * through a type that cannot express "I guessed".
     *
     * <p><b>There is deliberately no empty-key check here.</b> An {@code Update} that established
     * nothing carries {@code currentKey == ""}, and {@link #plan}'s own backstop already refuses
     * that — so a second check would be unreachable, and a test written against it would pass
     * whether or not it existed. That was measured, not reasoned: the first version of this method
     * had the check, and deleting it failed no test. One guard, in the place every caller reaches.
     * The {@code null} check below is NOT redundant — without it this throws.
     *
     * @param roster every leaf currently in the group — the same list the ledger observed
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
     * <b>0xF004 IS CORRECT.</b> That much held through three different explanations of WHY, two of
     * which were wrong. What follows is read from our own vendored
     * {@code external/mls-rs/mls-rs} rather than relayed, because this note has already been
     * rewritten twice on second-hand accounts.
     *
     * <h2>What the library actually does with 0xF004</h2>
     *
     * {@code proposal.rs:344} {@code decode_from_bytes} lives inside
     * {@code impl CustomDecoder for CustomProposal}. For {@code RCS_SERVER_REMOVE} it decodes a
     * {@code RemoveProposal}, <b>re-encodes it</b>, and returns those bytes — the source comment
     * says <i>"to be used in the data field of CustomProposal"</i>. So the round trip is a
     * VALIDATION of the payload, and the proposal <b>stays {@code Proposal::Custom}</b>. It never
     * becomes a {@code Proposal::Remove}.
     *
     * <p>Which kills the tempting story that 0xF004 "decodes into a Remove" and that Google Messages'
     * {@code Proposal::Remove} + {@code ProposalSource::Local} branch is therefore a downstream view
     * of the same thing. It cannot be: that branch can never fire for a 0xF004, because a 0xF004 is
     * never a {@code Remove}. Google Messages matches the type explicitly on
     * {@code CustomProposal.proposal_type} instead.
     *
     * <h2>The feature flips the ENCODING — and it is now ON, so this is no longer a trap</h2>
     *
     * <p><b>CORRECTED 2026-09-13. This block said {@code gsma_rcs_e2ee_feature} is OFF, and it has
     * been ON since that work landed.</b> The warning that followed was
     * therefore INVERTED — it told a reader the body would go out length-prefixed when on this
     * build it goes out bare, which is the spec-correct form. Re-derived from our own tree rather
     * than from the previous text: {@code rcs_mls_ffi/Cargo.toml:13} lists
     * {@code gsma_rcs_e2ee_feature} among the mls-rs features, and
     * {@code vendor/mls-rs-0.55.2/src/group/proposal.rs:324-341} gates the impl on it.
     *
     * <pre>
     *   feature ON  (OURS)  RCS_SIGNATURE | RCS_SERVER_REMOVE =&gt; writer.extend(data)
     *                         // source comment: "the length should not be included in the encoding"
     *   feature OFF         impl CustomDecoder for CustomProposal {}  -- empty, so the trait
     *                       default applies: byte_vec::mls_encode(data, writer)  // LENGTH-PREFIXED
     * </pre>
     *
     * <p>So on the current build a 0xF004 body emitted from here goes out <b>bare</b>, as RCC.16
     * wants. The double-length-prefix hazard the old text described is what we would have had on
     * the OTHER setting; it is not what we have. Keep the mechanism written down, because the flag
     * is a build-time choice that somebody could reverse — but do not "fix" a prefix that is not
     * being added.
     *
     * <p><b>Nothing is emitted today</b> regardless (v4.0 shapes are gated behind the announced
     * {@link Rcc16Version}, default v3.0).
     *
     * <p><b>Why this correction is worth its length:</b> the stale clause was quoted verbatim into
     * a successor's founding premise ("this must be settled first" — it was
     * settled a month earlier), which is the failure mode where a wrong clause carried as CONTEXT
     * rides along unexamined. The check that would have caught it is one grep of {@code Cargo.toml}.
     *
     * <p>Note also {@code tree_kem/mod.rs:247}: {@code can_support_proposal} requires
     * {@code count_supporting_proposal(t) == occupied_leaf_count()} — EVERY occupied leaf must
     * declare the capability. Turning the feature on for ourselves cannot make a peer's 0xF004
     * supported.
     *
     * <p>RCC.16 v4.0 §7.11.9 — the {@code ServerRemove} proposal body.
     *
     * <pre>struct { uint32 to_remove; } ServerRemove;</pre>
     *
     * <p>{@code to_remove} is "an index of the leaf node to be removed". <b>This body was recorded
     * as genuinely unknown</b> — we had closed it as exhausted across all three sources — and v4.0
     * published it. An MLS {@code uint32} is four bytes big-endian (RFC 9420 §2.1 fixed-width
     * integers are network byte order); this is not a protobuf varint and not the MLS varint used
     * for vector lengths, both of which would encode a small index in one byte and pass every
     * hand-written test.
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
