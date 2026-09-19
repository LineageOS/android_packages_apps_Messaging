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

import com.android.messaging.rcs.engine.mls.MlsAppMessage.Moment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The out-of-order pending-message queue — §10.8. Rework item {@code 6.7}.
 *
 * <p><b>This is a local-readiness DEFERRAL buffer, not a general reordering buffer.</b> The
 * distinction decides everything else: a reordering buffer holds whatever arrives early and sorts it
 * out later, so it needs a capacity, an eviction policy and a way to give up. A deferral buffer holds
 * only what it can prove it will be able to process, so it needs none of those — and Google Messages has none
 * of those.
 *
 * <h2>What this REPLACED, and why almost none of it survived</h2>
 *
 * <p>The host had a Java buffer that contradicted nearly every property §10.8 states. Item {@code 6.7}
 * itemised ten conflicts; the ones that were live defects rather than differences:
 *
 * <ul>
 *   <li><b>It was in-memory, so it was lost on process death.</b> A message deferred at 11:59 was
 *       gone at midnight, and the sender had no way to know.</li>
 *   <li><b>Admission was epoch-only</b> ({@code msg_epoch < pre_epoch ⇒ drop, else buffer}), so an
 *       <b>era-crossing message was misclassified</b>: era is the MAJOR key, and a message from era
 *       N+1 at epoch 0 is from the FUTURE even though its epoch is lower than ours.</li>
 *   <li><b>It buffered only AFTER a failed decrypt.</b> Google Messages' G1 buffers BEFORE decrypt whenever
 *       the group is mid-transition — so ours attempted a destructive decrypt against a group it
 *       already knew was moving.</li>
 *   <li><b>It had a cap of 8 with a SILENT DROP</b> and a 3-round strand counter that escalated to a
 *       self-heal thread. §10.8's negative is exhaustive: no capacity, no eviction, no TTL,
 *       no far-future cutoff. <i>"A message claiming era 9 999 is queued exactly like one at era
 *       N+1."</i> The strand machinery has <b>no counterpart in Google Messages</b> and was REMOVED, not
 *       retuned.</li>
 *   <li><b>The drain was a SWEEP</b> — it replayed the whole per-conversation list on any commit.
 *       Google Messages takes exactly {@code pending[current_era][current_epoch]}. Messages parked at epoch
 *       N+2 wait until the group reaches exactly N+2; sweeping them re-attempts, re-fails and
 *       re-buffers them on every intermediate commit, which is what fed the strand counter that then
 *       declared the conversation unrecoverable.</li>
 * </ul>
 *
 * <h2>THE UNBOUNDEDNESS RESTS ON AN INHERITED ABSENCE — falsifier, and what it does NOT license</h2>
 *
 * <p>Two different things are being claimed above and they need separating.
 * <b>"A deferral buffer needs no capacity"</b> is a design argument, and a design argument about
 * somebody else's code is a plausibility, not a read. <b>"Google Messages has none of those"</b> is an
 * EMPIRICAL negative, and it is the one this class was built on: we removed a cap of 8 and shipped
 * an <b>unbounded, network-fed</b> structure on the strength of it. Do not let the first sentence
 * carry the second — if the scan missed a bound, the design argument will still read as convincing
 * and the queue will still grow without limit.
 *
 * <p><b>FALSIFIER:</b> an eviction, TTL or capacity constant found in Google Messages' own
 * pending-message code — or a Google Messages peer OBSERVED to stop retrying a
 * parked message it could still read. Either overturns "no capacity, no eviction, no TTL". Note how
 * the negative could have been missed: a bound that is inlined, expressed as a type invariant, or
 * enforced by the CALLER rather than the queue is invisible to a scan looking for a constant.
 *
 * <p><b>What does NOT overturn it: our own queue growing large.</b> That is the argument that put
 * the cap of 8 there the first time, and it would put it back.
 *
 * <p><b>And Google Messages having no bound is a fact about THAT client, not a safety argument for
 * US.</b>
 * Different memory headroom, process lifetime and drain cadence. A bound of our own would be a
 * defensible DIVERGENCE needing no falsifier at all; what needs one — and now has one — is the
 * claim that Google Messages has none.
 *
 * <h2>One deliberate architectural divergence, stated plainly</h2>
 *
 * <p>§10.8 says the queue lives in the engine's own state object beside the health status, and that
 * <i>"the host never inspects"</i> the persisted blobs. <b>Ours is in the Java engine module rather
 * than in Rust</b>, and that is a considered choice, not an unfinished migration:
 *
 * <p>G1 and G3 are predicates over the <b>health status</b>, and in our split the health status lives
 * in {@link MlsConversationRecord} on the Java side — Google Messages can put the queue in native code
 * because its engine owns both. Putting the queue in Rust would mean either duplicating the health
 * status across the FFI boundary on every inbound message, or moving the whole state machine into
 * Rust where it could not be host-tested. The property item {@code 6.7} actually demands — <i>out of
 * the host transport, and persisted</i> — is satisfied here, and this way the admission predicate,
 * the validation order and the drain semantics are all checkable without a device.
 *
 * <p>What is NOT negotiable and is preserved: the host transport does no reassembly, holds no buffer,
 * and cannot reach past this class's API to sweep.
 *
 * <p>Not thread-safe by itself: one queue belongs to one conversation and is guarded by that
 * conversation's lock, exactly as the record is.
 */
public final class MlsPendingQueue {

    /**
     * Why a message was admitted — the three gates of §10.8, kept apart because they mean different
     * things and a caller that collapses them cannot tell "the group is busy" from "this message is
     * from the future".
     */
    public enum Admission {
        /**
         * <b>G1</b> — the pre-decrypt busy-group lock, {@code health ∈ {1,2,4,7,12,14,16,17}}.
         *
         * <p>Checked BEFORE any decrypt is attempted. This is the native origin of
         * {@code PENDING_REASON_GROUP_LOCKED_FOR_SELF_HEAL}.
         */
        GROUP_LOCKED,
        /**
         * <b>G2</b> — strictly from the future: processing failed with {@code OutOfOrderCommit(29)}
         * or {@code is_from_future}, AND {@code moment(msg) > moment(group)}.
         *
         * <p><b>Strictly greater, with era as the major key.</b> Equal or behind falls through to the
         * failure/self-heal path — a message at or behind our moment is <b>never</b> queued, even
         * when {@code OutOfOrderCommit} was reported.
         */
        FROM_FUTURE,
        /**
         * <b>G3</b> — the resent-message FTD arm. Two things separate it from G2, and only one of
         * them is the comparison: it is {@code >=} rather than {@code >}, and <b>it compares against
         * a DIFFERENT MOMENT</b>.
         *
         * <p><b>G3's moment is {@code SelfHealState.recovered_at}, not the live group moment</b>,
         * which was settled by reading Google Messages' own engine.
         * That is why {@link #admit} takes {@code recoveredAt} as a parameter of its own: the two
         * arms now differ by their SOURCE, which one shared parameter cannot express and which a
         * later reader therefore cannot quietly re-collapse into "same value, different operator".
         *
         * <p><b>The reading, recorded so nobody re-derives it.</b> G2's getter reads the LIVE group
         * moment. G3's getter walks somewhere else entirely: into the group's {@code SelfHealState}
         * submessage, and out of it the era and epoch of {@code recovered_at}.
         *
         * <p><b>Those two scalars are {@code recovered_at} and not {@code last_healthy_moment}, and
         * the evidence is the WRITE side rather than a label.</b> {@code on_enter}, which fires on
         * <i>every</i> arrival at {@code Healthy(13)}, writes the OTHER pair.
         * {@code on_transition} Block A, gated on the SOURCE state being in the recovery set
         * {@code 0x10d6 = {1,2,4,6,7,12}} — SIX states, bit 10 is CLEAR, and
         * {@code {1,2,4,6,7,10,12}} would be {@code 0x14d6} — writes THIS pair.
         * That set is {@code MlsHealthMachine.isRecoveryLanding}, which carried a seven-state
         * mis-transcription for a while and is now pinned to the mask by its own test. The
         * sibling getter, the one SH_FULL Phase 7 uses for "last healthy", reads the OTHER pair;
         * <b>G3 does not call it.</b> Layout and has-bits cross-check
         * against §9.3f row for row.
         *
         * <p><b>What this fixes, and its direction.</b> Comparing against the live group moment was
         * TOO STRICT, in exactly the window the arm exists for: {@code recovered_at} is stamped when
         * a recovery LANDS and then stays put while the group commits forward, so it sits behind the
         * live moment precisely when a resend arrives. Against the live moment we would FTD resends
         * Google Messages queues — the sender resends again, and neither side's logs look wrong.
         *
         * <p><b>An unset {@code recovered_at} is {@code (0,0)}, not "unknown".</b> Google Messages'
         * accessors read the scalars RAW, with no has-bit
         * test, and a null submessage yields the all-zero default instance. So on a group that has
         * never completed a recovery the moment gate always passes. {@link #atOrAfterRecoveredAt}
         * models that; treating null as "cannot prove, so refuse" would reintroduce the same
         * too-strict bug at its most extreme.
         *
         * <p>The comparison itself is era-major unsigned, and its call site is
         * gated on failure reason {@code 6} — so this is G3 and not G2, which
         * is the attribution that was got wrong once before.
         *
         * <p>⚠ <b>Still NO LIVE CALLER, deliberately.</b> Both host sites pass
         * {@code resentMessageForMeFtd = false}: the receive-side selector needs a resent-message
         * component encoding nobody has captured yet. Giving the arm its first
         * caller is the resend receive path's job — and it no longer has to change the moment source
         * on the way in, because the arm now reads the right one.
         */
        RESENT_FTD,
        /**
         * Not admitted. <b>Everything else is an ERROR, not a queue admission</b> — a bad AAD, a
         * key-generation mismatch, a replay, a message-id mismatch or a non-member sender goes
         * straight to the error/FTD path.
         */
        NONE;

        public boolean admitted() { return this != NONE; }
    }

    /**
     * G1's mask, as a method rather than a duplicate of {@link MlsHealthPredicates#buffersInbound}.
     *
     * <p>Delegating matters: the mask is {@code 0x35096} and it is <b>not</b> the complement of
     * {@code is_downgraded} — the two disagree on five states in both directions. A second copy here
     * is a second thing to get wrong.
     */
    public static boolean groupLocked(final int healthStatus) {
        return MlsHealthPredicates.buffersInbound(healthStatus);
    }

    /**
     * The §10.8 admission predicate, in full and in order.
     *
     * <p>Pure — it decides, it does not store. That separation is what lets the ten-way conflict in
     * item {@code 6.7} be checked as ten assertions rather than inferred from behaviour.
     *
     * @param healthStatus            the group's persisted health status (a WIRE number)
     * @param msg                     the message's moment {@code (era, epoch)}, or null if unknown
     * @param group                   the group's current moment — G2's comparand, and ONLY G2's
     * @param recoveredAt             {@code SelfHealState.recovered_at} — the moment at which a
     *                                recovery last LANDED in Healthy, or null if one never has.
     *                                <b>G3's comparand</b>, and deliberately not {@code group};
     *                                see {@link Admission#RESENT_FTD} for why.
     * @param processingFailed        whether the engine failed to process it
     * @param outOfOrderCommit        the engine reported {@code OutOfOrderCommit(29)}
     * @param isFromFuture            the FTD path's own from-the-future flag
     * @param resentMessageForMeFtd   the failure reason was
     *                                {@code CLIENT_FAILURE_RESENT_MESSAGE_FOR_ME_FTD(6)}
     * @param sameApplicationMessageFailing G3's negative condition
     */
    public static Admission admit(final int healthStatus, final Moment msg, final Moment group,
            final Moment recoveredAt,
            final boolean processingFailed, final boolean outOfOrderCommit,
            final boolean isFromFuture, final boolean resentMessageForMeFtd,
            final boolean sameApplicationMessageFailing) {
        // G1 FIRST, and before any decrypt has been attempted by the caller. A healthy group never
        // takes this gate — inbound is processed inline.
        if (groupLocked(healthStatus)) {
            return Admission.GROUP_LOCKED;
        }
        // G2 — the failure arm. OutOfOrderCommit is THE error value that routes an inbound public
        // message into the queue-vs-error decision; it becomes a self-heal request only when the
        // moment check declines.
        if (processingFailed && (outOfOrderCommit || isFromFuture)
                && strictlyAfter(msg, group)) {
            return Admission.FROM_FUTURE;
        }
        // G3 — the resend arm. TWO differences from G2, not one: a different MOMENT SOURCE
        // (SelfHealState.recovered_at, §22.1-39) and a different operator (`>=`, not `>`). The
        // source is the one that was wrong; do not collapse it back onto `group`. Excluded across
        // the whole end-MLS family.
        if (resentMessageForMeFtd
                && atOrAfterRecoveredAt(msg, recoveredAt)
                && !sameApplicationMessageFailing
                && !MlsHealthPredicates.isDowngraded(healthStatus)) {
            return Admission.RESENT_FTD;
        }
        return Admission.NONE;
    }

    /**
     * Google Messages' engine-error ordinal for {@code ExpectedSelfHealToBeOngoing} — §10.8's silent drop.
     */
    public static final int ERROR_EXPECTED_SELF_HEAL_TO_BE_ONGOING = 52;

    /**
     * <b>The one failure class that emits NOTHING</b> — no FTD, no receipt, no queue entry (§10.8).
     *
     * <p>{@code ExpectedSelfHealToBeOngoing(52)} together with
     * {@code !same_application_message_failing}. §10.8 states it as a thing to <i>"budget for in your
     * diff"</i>, because a silent drop is invisible: nothing distinguishes it from a message that
     * never arrived.
     *
     * <p><b>Honest status: this predicate has no producer in our stack today.</b> Error 52 is a
     * a Google-engine error and our engine is mls-rs, which has no equivalent — so the rule is implemented and
     * tested but currently unreachable. That is deliberately not the same as "not implemented": the
     * decision lives in exactly one place, so the day our inbound path can raise the condition, the
     * silent drop happens by wiring rather than by re-deriving §10.8. Recording it as reachable would
     * be the overstatement this project has made before ("unreachable" for "not provoked"); recording
     * it as absent would lose the rule.
     *
     * <p>What it must NOT do is emit an FTD, which is what our path would do today if the condition
     * ever arose — the gap item {@code 6.7} conflict 10 names.
     */
    public static boolean silentDrop(final int errorOrdinal,
            final boolean sameApplicationMessageFailing) {
        return errorOrdinal == ERROR_EXPECTED_SELF_HEAL_TO_BE_ONGOING
                && !sameApplicationMessageFailing;
    }

    /**
     * {@code a > b} with <b>era as the MAJOR key</b>, both fields unsigned.
     *
     * <p>The era-major ordering is the correction that matters most in this file. Comparing epochs
     * alone misclassifies every era-crossing message: a new era restarts the epoch at 0, so era N+1
     * epoch 0 is strictly AFTER era N epoch 40 despite {@code 0 < 40}. Our old classification would
     * have called that message PAST and dropped it — losing the first message of every new era.
     *
     * <p>A null moment is never after anything: we cannot prove it is from the future, and the
     * default for "cannot prove" on this path is the error path, not the queue.
     */
    public static boolean strictlyAfter(final Moment a, final Moment b) {
        if (a == null || b == null) return false;
        return a.compareTo(b) > 0;
    }

    /**
     * <b>G3's moment gate</b> — {@code msg >= SelfHealState.recovered_at}, era-major and unsigned,
     * matching Google Messages, which compares both halves unsigned and makes era the major key.
     *
     * <p><b>Named for its comparand deliberately, and there is no general {@code atOrAfter} any
     * more.</b> G3's bug was never the operator — it was comparing against the live group moment.
     * A generically-named {@code >=} is what let the two arms sit one word apart looking
     * interchangeable, so a caller that wants {@code >=} against something else now has to say
     * which something.
     *
     * <p><b>A null {@code recoveredAt} PASSES.</b> Null means no recovery has ever landed; Google Messages
     * reads {@code +0x2c}/{@code +0x30} RAW out of a possibly-default {@code SelfHealState} with no
     * has-bit test, so it sees {@code (0,0)} and every message clears the gate. Refusing here — the
     * "we cannot prove it, so take the error path" reading that {@link #strictlyAfter} correctly
     * applies to the GROUP moment — would be §22.1-39's too-strict bug at its most extreme.
     *
     * <p>A null {@code msg} still refuses: Google Messages' message-side getter
     * returns an Ok-sentinel which is tested first and branches away from the
     * compare when it is absent.
     */
    public static boolean atOrAfterRecoveredAt(final Moment msg, final Moment recoveredAt) {
        if (msg == null) return false;
        return recoveredAt == null || msg.compareTo(recoveredAt) >= 0;
    }

    /**
     * Can we reach the moment a parked message waits for, by our own action?
     *
     * <p>A park is a promise to retry "when the group reaches that moment". This is the predicate
     * that says whether that promise can be kept.
     *
     * <p><b>An EPOCH gap we close ourselves</b> by replaying the commits we missed, so parking is the
     * whole remedy and reporting would only provoke a pointless resend. <b>An ERA gap we cannot</b>:
     * a new era is a new group, and joining one needs a Welcome that only the peer or the server can
     * send. {@code MlsProviderTransport} already treats every era gap as categorically not
     * self-serviceable for exactly this reason.
     *
     * <p>So an era-crossing park waits for something that may never happen, and if it also stays
     * silent the conversation is permanently one-way <em>with both sides believing they are fine</em>
     * — the sender sees its message encrypted and sent, never learns we could not read it, and so
     * never re-Welcomes us. Device-observed against a Google Messages peer on 2026-08-04.
     *
     * <p>The caller's remedy is to keep the park <em>and</em> emit the §6.2 report, not to drop the
     * park: if the peer does re-Welcome us we cross the era and the parked ciphertext drains at its
     * exact key, so dropping it would discard the very message we asked them to make readable.
     *
     * @return true if waiting alone can still work; false if the wait needs something only a peer can
     *         give, and the caller must therefore also speak up
     */
    public static boolean awaitedMomentIsReachable(final Moment msg, final Moment group) {
        // Unknown moments do not get an opinion — never turn a missing fact into a report.
        if (msg == null || group == null) return true;
        return msg.era <= group.era;
    }

    /**
     * Was this ciphertext sealed in an era the group has already LEFT?
     *
     * <p>The mirror of {@link #awaitedMomentIsReachable}, on the other side of the same boundary.
     * That one asks whether a message ahead of us can ever become readable; this one names the
     * messages behind us that never can.
     *
     * <p><b>It is a classification, not a new decision.</b> Nothing branches on it: a superseded-era
     * ciphertext already falls out of the park gate ({@link #strictlyAfter} is false) and into the
     * §6.2 FTD path, which is the correct remedy — the sender resends at the current era and the
     * message lands. What was missing is that the log said nothing about WHY, so the one outcome
     * that is working as designed read as an ordinary decrypt failure — and an era advance is the
     * ordinary recovery path here, so the failure appeared immediately after a recovery and was
     * attributed to it.
     *
     * <p><b>Why it can never become readable, which is the part worth stating once:</b> an era
     * advance builds a NEW MLS group under the SAME group id, so the epoch id restarts and the
     * prior era's secrets are cleared on every member — by {@code delete_group} on the advancer and
     * by {@code purge_prior_epochs_on_join} on everyone who reaches the new era by Welcome. Keeping
     * them would not help either: mls-rs addresses its epoch archive by {@code (group_id, epoch_id)}
     * alone, so era N's epoch 2 and era N+1's epoch 2 compete for one key. RCC.16 §6.1.1's three
     * days is a within-era promise, and both we and Google Messages behave that way.
     *
     * <p>Compared UNSIGNED, matching {@link Moment#compareTo}'s own ordering — the era is a u32.
     *
     * @return true if {@code msg} belongs to an era strictly older than the group's
     */
    public static boolean isFromASupersededEra(final Moment msg, final Moment group) {
        // Unknown moments do not get an opinion — same rule as the predicate above.
        if (msg == null || group == null) return false;
        return Integer.compareUnsigned(msg.era, group.era) < 0;
    }

    // ---- entries ------------------------------------------------------------------------------

    /**
     * Which inbound door a parked message came through, and therefore which one it must be replayed
     * to when its moment arrives.
     *
     * <p><b>This cannot be inferred from {@link Entry#variantTag}</b>, which is the reason it is
     * stored. An application message and a commit are BOTH {@code PrivateMessage} (wire_format 2),
     * so the variant tag — the only discriminator §10.8 itself defines — cannot tell them apart.
     *
     * <p>It has to be right because the two doors are not interchangeable. The application door
     * ({@code decryptInbound}) runs the §7.5.3.1 AAD message-id binding, the sender-identity
     * binding and the decrypt rendezvous, then hands the plaintext to the router to classify and
     * insert. The control door ({@code applyInboundControl}) runs none of those and has nowhere to
     * insert a message. Replaying an application message through the control door decrypts it
     * successfully and then discards it — with a log line that reads like success.
     * Both halves of the engine call are ratchet-advancing, so this must be decided BEFORE
     * the blob reaches the engine; there is no "process it and see".
     */
    public enum Plane {
        /** A control message: commit, proposal, Welcome-bearing frame. */
        CONTROL,
        /** An application ciphertext: a message, receipt or key delivery for the app. */
        APPLICATION;

        public static Plane fromOrdinal(final int i) {
            return i == APPLICATION.ordinal() ? APPLICATION : CONTROL;
        }
    }

    /** One parked message. Immutable. */
    public static final class Entry {
        /** The RCS message id — the dedup key WITHIN a bucket, never globally. */
        public final String messageId;
        /** The moment it claims. Its {@code (era, epoch)} is the bucket key. */
        public final Moment moment;
        /** The MLSMessage {@code wire_format}, i.e. §10.8's "variant tag". */
        public final int variantTag;
        /** The MLS group id the message names, or null if it named none. */
        public final byte[] groupId;
        /** The opaque wire blob, replayed verbatim on drain. */
        public final byte[] blob;
        /** Which door it arrived through, and must leave through. Never inferred — see {@link Plane}. */
        public final Plane plane;

        public Entry(final String messageId, final Moment moment, final int variantTag,
                final byte[] groupId, final byte[] blob) {
            this(messageId, moment, variantTag, groupId, blob, Plane.CONTROL);
        }

        public Entry(final String messageId, final Moment moment, final int variantTag,
                final byte[] groupId, final byte[] blob, final Plane plane) {
            this.messageId = messageId == null ? "" : messageId;
            this.moment = moment;
            this.variantTag = variantTag;
            this.groupId = groupId;
            this.blob = blob;
            this.plane = plane == null ? Plane.CONTROL : plane;
        }

        @Override public String toString() {
            return "pending{" + messageId + " at " + moment + " tag=" + variantTag
                    + " " + plane + " " + (blob == null ? 0 : blob.length) + "B}";
        }
    }

    /**
     * The four store-time validations and the dedup outcome — §10.8, each carrying
     * Google Messages' own engine-error ordinal.
     *
     * <p><b>Note the ORDER, which is not the numeric order and is the thing most likely to be
     * "tidied" into agreement with it:</b> group-id-MATCHES ({@code 28}) is checked BEFORE
     * epoch-present ({@code 27}). A message for the wrong group with no epoch reports {@code 28},
     * not {@code 27}.
     */
    public enum StoreResult {
        /** Inserted. */
        STORED(0),
        /**
         * A message with this id is already in this {@code (era, epoch)} bucket.
         *
         * <p>Google Messages logs and returns <b>Ok</b> without inserting — a duplicate is not an error, and
         * treating it as one would turn an ordinary retransmission into a failure the sender sees.
         */
        DUPLICATE(0),
        /** variant tag ≥ 3 — a Welcome, GroupInfo or KeyPackage cannot be a pending message. */
        INVALID_WIRE_FORMAT(25),
        MISSING_REQUIRED_GROUP_ID(26),
        MISSING_REQUIRED_EPOCH(27),
        INVALID_GROUP_ID(28);

        /** Google Messages' engine-error ordinal, or 0 for the two non-error outcomes. */
        public final int errorOrdinal;

        StoreResult(final int errorOrdinal) { this.errorOrdinal = errorOrdinal; }

        public boolean stored() { return this == STORED; }
        public boolean isError() { return errorOrdinal != 0; }
    }

    /** {@code "Pending message already exists for message id: {:?}"} — Google Messages' verbatim line. */
    public static String duplicateLine(final String messageId) {
        return "Pending message already exists for message id: " + messageId;
    }

    // era -> epoch -> entries, in insertion order within a bucket.
    private final TreeMap<Integer, TreeMap<Long, List<Entry>>> mByEra = new TreeMap<>();

    /** The highest variant tag a pending message may carry, exclusive. */
    private static final int MAX_VARIANT_TAG_EXCLUSIVE = 3;

    /**
     * Store one message, running §10.8's validations in Google Messages' order.
     *
     * @param expectedGroupId the group this queue belongs to; the message must name it
     */
    public StoreResult store(final Entry e, final byte[] expectedGroupId) {
        if (e == null) return StoreResult.INVALID_WIRE_FORMAT;
        // 1. variant tag < 3.
        //
        // Our variant tag is the MLSMessage wire_format: 1 PublicMessage, 2 PrivateMessage,
        // 3 Welcome, 4 GroupInfo, 5 KeyPackage. So "< 3" admits exactly the two shapes that are
        // messages TO a group, and refuses the three that are messages ABOUT one. That reading makes
        // the constant behavioural rather than arbitrary — a Welcome parked in a per-epoch bucket
        // could never be drained, because a Welcome is what gets you INTO the group whose epoch the
        // bucket is keyed by. (The mapping from Google Messages' own variant enum to MLS wire_format
        // is inferred; the constant 3 and its position first in the ladder are read off the engine.)
        if (e.variantTag <= 0 || e.variantTag >= MAX_VARIANT_TAG_EXCLUSIVE) {
            return StoreResult.INVALID_WIRE_FORMAT;
        }
        // 2. group id present.
        if (e.groupId == null || e.groupId.length == 0) {
            return StoreResult.MISSING_REQUIRED_GROUP_ID;
        }
        // 3. group id MATCHES — before the epoch check, per the proven order.
        if (expectedGroupId == null || !java.util.Arrays.equals(e.groupId, expectedGroupId)) {
            return StoreResult.INVALID_GROUP_ID;
        }
        // 4. epoch present. A null moment means we could not establish one.
        if (e.moment == null) {
            return StoreResult.MISSING_REQUIRED_EPOCH;
        }
        // 5. dedup by message id WITHIN the (era, epoch) bucket — not globally. The same id in a
        // different bucket is a different delivery attempt at a different moment, and collapsing
        // them would drop the one that can actually be applied.
        final TreeMap<Long, List<Entry>> byEpoch =
                mByEra.computeIfAbsent(e.moment.era, k -> new TreeMap<>());
        final List<Entry> bucket = byEpoch.computeIfAbsent(e.moment.epoch, k -> new ArrayList<>());
        for (final Entry existing : bucket) {
            if (existing.messageId.equals(e.messageId)) {
                return StoreResult.DUPLICATE;
            }
        }
        bucket.add(e);
        return StoreResult.STORED;
    }

    /**
     * <b>The EXACT-KEY take</b> — {@code pending[era][epoch]}, removed, in insertion order.
     *
     * <p>Not a sweep, and not "everything ≤ current". Messages parked at epoch N+2 wait until the
     * group reaches <b>exactly</b> N+2. Draining early is not merely wasteful: each early attempt
     * fails and re-buffers, which is precisely what fed the strand counter that then declared the
     * conversation permanently behind.
     *
     * @return the taken entries, in insertion order; empty if that bucket is absent
     */
    public List<Entry> take(final int era, final long epoch) {
        final TreeMap<Long, List<Entry>> byEpoch = mByEra.get(era);
        if (byEpoch == null) return Collections.emptyList();
        final List<Entry> taken = byEpoch.remove(epoch);
        if (byEpoch.isEmpty()) mByEra.remove(era);
        return taken == null ? Collections.emptyList() : taken;
    }

    /** {@link #take(int, long)} at a moment. */
    public List<Entry> take(final Moment at) {
        return at == null ? Collections.<Entry>emptyList() : take(at.era, at.epoch);
    }

    /**
     * Everything still parked, for diagnostics only.
     *
     * <p><b>Deliberately not a drain.</b> §10.8's take is the only removal path; exposing a
     * "drain everything" verb is how the sweep comes back.
     */
    public List<Entry> peekAll() {
        final List<Entry> all = new ArrayList<>();
        for (final Map<Long, List<Entry>> byEpoch : mByEra.values()) {
            for (final List<Entry> bucket : byEpoch.values()) all.addAll(bucket);
        }
        return all;
    }

    public int size() {
        int n = 0;
        for (final Map<Long, List<Entry>> byEpoch : mByEra.values()) {
            for (final List<Entry> bucket : byEpoch.values()) n += bucket.size();
        }
        return n;
    }

    public boolean isEmpty() { return size() == 0; }

    /**
     * The eras with parked messages.
     *
     * <p><b>An era advance does NOT purge a stale partition</b> (§10.8: only two functions in
     * Google Messages touch the era map, and neither prunes). An abandoned era's partition is retained until
     * the whole per-group blob is deleted. So this can legitimately report eras the group has long
     * passed, and a "tidy up old eras" helper would be a divergence, not a fix.
     */
    public java.util.Set<Integer> eras() {
        return Collections.unmodifiableSet(mByEra.keySet());
    }

    /** Drop everything — only for group deletion, which is the one purge Google Messages has. */
    public void clear() { mByEra.clear(); }

    // ---- codec --------------------------------------------------------------------------------
    //
    // Length-prefixed and version-tagged. The queue is persisted as ONE opaque blob per conversation
    // and rewritten wholesale, which is Google Messages' shape (`mls_pending_message_blobs`, "rewritten
    // wholesale", host never inspects). Wholesale rather than per-entry rows is deliberate: a
    // partial write of a reordering structure is worse than no write, because a bucket missing its
    // second half looks exactly like a bucket that was fully drained.

    /**
     * <b>2</b> adds {@link Entry#plane}, appended after the blob so a v1 record is a strict prefix
     * of a v2 one.
     *
     * <p>v1 blobs still decode; every entry in one comes back {@link Plane#CONTROL}. That is a
     * GUESS, and it is wrong for exactly the entries that motivated the field — a v1 blob holding a
     * parked application message replays to the control door and is discarded,
     * i.e. no worse than before this field existed, but no better. There is no sniff that would fix
     * it: the discriminator genuinely is not in the bytes. The only v1 blobs in existence are on our
     * own test devices, so the honest migration is to say so rather than to infer a plane from the
     * wire and be confidently wrong on someone's message.
     */
    private static final int CODEC_VERSION = 2;

    /** Serialize the whole queue. */
    public byte[] toBytes() {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        try {
            d.writeInt(CODEC_VERSION);
            d.writeInt(size());
            for (final Map.Entry<Integer, TreeMap<Long, List<Entry>>> era : mByEra.entrySet()) {
                for (final Map.Entry<Long, List<Entry>> ep : era.getValue().entrySet()) {
                    for (final Entry e : ep.getValue()) {
                        d.writeUTF(e.messageId);
                        d.writeInt(era.getKey());
                        d.writeLong(ep.getKey());
                        d.writeInt(e.variantTag);
                        writeBytes(d, e.groupId);
                        writeBytes(d, e.blob);
                        d.writeInt(e.plane.ordinal());
                    }
                }
            }
            d.flush();
        } catch (final java.io.IOException impossible) {
            // A ByteArrayOutputStream does not do I/O. Returning empty here would silently discard
            // the queue, so say so instead.
            throw new IllegalStateException("MlsPendingQueue.toBytes failed", impossible);
        }
        return out.toByteArray();
    }

    /**
     * Deserialize. Returns an EMPTY queue on any malformation rather than throwing.
     *
     * <p>Empty rather than throwing because a queue we cannot read must not stop the conversation
     * from working: losing parked messages costs a resend, and refusing to start costs everything.
     * The loss is logged by the caller — silently returning empty is only acceptable because it is
     * paired with that.
     */
    public static MlsPendingQueue fromBytes(final byte[] b) {
        final MlsPendingQueue q = new MlsPendingQueue();
        if (b == null || b.length == 0) return q;
        try {
            final java.io.DataInputStream d = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(b));
            final int version = d.readInt();
            if (version != CODEC_VERSION && version != 1) return q;
            final int n = d.readInt();
            if (n < 0) return q;
            for (int i = 0; i < n; i++) {
                final String id = d.readUTF();
                final int era = d.readInt();
                final long epoch = d.readLong();
                final int tag = d.readInt();
                final byte[] gid = readBytes(d);
                final byte[] blob = readBytes(d);
                final Plane plane = version >= 2 ? Plane.fromOrdinal(d.readInt()) : Plane.CONTROL;
                final Entry e = new Entry(id, new Moment(era, epoch), tag, gid, blob, plane);
                // Insert WITHOUT re-validating: these entries passed validation when they were
                // stored, and re-running the ladder here would silently drop a message because the
                // group id we would have to compare against is not available at decode time.
                q.mByEra.computeIfAbsent(era, k -> new TreeMap<>())
                        .computeIfAbsent(epoch, k -> new ArrayList<>())
                        .add(e);
            }
        } catch (final Throwable malformed) {
            return new MlsPendingQueue();
        }
        return q;
    }

    private static void writeBytes(final java.io.DataOutputStream d, final byte[] v)
            throws java.io.IOException {
        d.writeInt(v == null ? -1 : v.length);
        if (v != null && v.length > 0) d.write(v);
    }

    private static byte[] readBytes(final java.io.DataInputStream d) throws java.io.IOException {
        final int n = d.readInt();
        if (n < 0) return null;
        final byte[] v = new byte[n];
        d.readFully(v);
        return v;
    }

    @Override public String toString() {
        final StringBuilder sb = new StringBuilder("MlsPendingQueue{");
        boolean first = true;
        for (final Map.Entry<Integer, TreeMap<Long, List<Entry>>> era : mByEra.entrySet()) {
            for (final Map.Entry<Long, List<Entry>> ep : era.getValue().entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("e").append(era.getKey()).append('/').append(ep.getKey())
                  .append('=').append(ep.getValue().size());
            }
        }
        return sb.append('}').toString();
    }

    /** A stable, ordered view for tests: era-major then epoch, buckets in insertion order. */
    public Map<String, List<String>> debugLayout() {
        final Map<String, List<String>> m = new LinkedHashMap<>();
        for (final Map.Entry<Integer, TreeMap<Long, List<Entry>>> era : mByEra.entrySet()) {
            for (final Map.Entry<Long, List<Entry>> ep : era.getValue().entrySet()) {
                final List<String> ids = new ArrayList<>();
                for (final Entry e : ep.getValue()) ids.add(e.messageId);
                m.put(era.getKey() + "/" + ep.getKey(), ids);
            }
        }
        return m;
    }
}
