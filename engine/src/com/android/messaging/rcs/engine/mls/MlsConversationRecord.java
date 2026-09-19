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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * ONE persisted record per {@code (identity, group)} — §4.8's normative field list.
 *
 * <p>Rework item 2.1. This replaces a per-conversation record that was in-memory only, wrongly
 * keyed, and three fields wide out of eighteen. Everything the state machine needs lives here, and
 * the three shape rules are not stylistic:
 *
 * <ol>
 *   <li><b>Written whole.</b> One blob, upserted. There is no partial update anywhere, and that is
 *       what makes the "eighteen maps out of sync" class of bug <em>structurally impossible</em>
 *       rather than merely unlikely. {@link #encode()} and {@link #decode} are the only writers.</li>
 *   <li><b>Never indexed, never queried.</b> No {@code WHERE health_status = …}-shaped accessor,
 *       ever. If you want to know which groups are unhealthy, add a debug dump — an index is a
 *       second source of truth, and the second source is always the one that goes stale.</li>
 *   <li><b>Reserve unused field numbers.</b> Holes below are deliberate; do not reuse them.</li>
 * </ol>
 *
 * <p><b>Key is {@code (identity, group)}, not the conversation id.</b> The old key was
 * {@code canonicalKey()} — {@code "g:"+rcsGroupId} or {@code "p:"+peerE164} — which cannot express
 * the same RCS group under two identities, and those are two different MLS groups with two
 * different storage directories.
 *
 * <p>Immutable, with {@code with*} copies. A record that is written whole must not be mutable in
 * place: an in-place setter is a partial update wearing a different hat, and the first one to
 * appear is the one that gets forgotten before the write.
 *
 * <p>One §4.8 field is deliberately absent, and the absence is recorded rather than silent:
 * {@code pending_incoming}/{@code pending_outgoing} (row 18) belong with the out-of-order machinery
 * in Stage H, which is where the queues are actually consumed. Its field number is reserved.
 *
 * <p><b>Row 13 ({@code continuity_token}) used to be the second of those</b>, omitted on the
 * grounds that §21.2 calls it diagnostic-only. It is here now — see {@link #continuityToken} — and
 * the reason it had to come back is this: a Google Messages peer hands us a token in the
 * encrypted GroupInfo of every Welcome it builds, and with nowhere to put it we logged its length
 * and dropped a 256-bit group secret. The key {@code (identity, groupId)} is what makes this the
 * right home rather than a convenience: an era advance REUSES the MLS group id
 * ({@code create_group_carry}'s {@code gid_override} arm), so a record survives the one event the
 * token exists to survive.
 *
 * <p>That last part is a CHECKED claim and not a reading of a comment — two Rust tests assert the
 * id is preserved across an advance, {@code the_legal_era_advance_is_a_create_that_carries_the_metadata}
 * ("an advance keeps the conversation's group id") and
 * {@code an_era_advance_reuses_the_epoch_id_space_so_prior_era_secrets_cannot_stay_at_the_same_key}.
 * If either ever stops holding, row 13 silently starts over at every era boundary — which is exactly
 * the discontinuity a continuity token exists to detect.
 */
public final class MlsConversationRecord {

    /**
     * Bump only for a change the decoder cannot absorb by skipping unknown fields.
     *
     * <p>2 added the length frame — see the codec section. 1 is the unframed original: still READ,
     * never written. The first {@link #encode()} of a loaded record upgrades it in place, and since
     * the record is always written WHOLE (shape rule 1) that is every write.
     */
    private static final int VERSION = 2;

    /**
     * The last unframed version. Read-only.
     *
     * <p>A version-1 blob cannot be retro-framed — its bytes are already on disk — so it keeps the
     * weakness the frame exists to remove, and keeps it only until that record is next written.
     * Refusing to read them instead would reset every conversation on the fleet to Unknown and send
     * it back through the whole recovery ladder, which is enormously worse than the bug.
     */
    private static final int VERSION_UNFRAMED = 1;

    /**
     * {@link #epochAuthEra} for a record written before the era key existed, or one that has never
     * stored an authenticator.
     *
     * <p><b>{@code -1} is NOT a value no era can take, and the first draft of this javadoc claimed
     * it was.</b> {@code MlsAppMessage.Moment.era} is an UNSIGNED 32-bit era in a signed
     * {@code int}, so every era from {@code 0x80000000} up reads as negative and is perfectly
     * legal — and {@code nextEra} refuses only {@code era >= 0xFFFFFFFF}, so
     * {@code nextEra(0xFFFFFFFE)} RETURNS {@code 0xFFFFFFFF}, which is this sentinel's bit pattern.
     * The claim was caught by {@code MlsConversationRecordTest
     * .theUnknownSentinelIsTheOneValueThatCannotBeAnEra} before it could be relied on.
     *
     * <p>What is actually true, and it is enough: the collision is at ONE era, that era needs
     * {@code 2^32} advances to reach, and it is <b>already</b> ambiguous everywhere else —
     * {@code MlsAppMessage.eraFrom} spends the same {@code -1} on an unreadable {@code eraEpoch}
     * blob, so no code in this package can distinguish era {@code 0xFFFFFFFF} from "unreadable"
     * either. This field inherits that ambiguity rather than creating one.
     *
     * <p>The consequence a reader must respect: {@link #ERA_UNKNOWN} means <b>"not usable as an
     * era"</b>, NOT "this record is old". Do not infer the record's vintage
     * from it.
     *
     * <p>And the code below must NOT treat "negative" as "unknown" — that would quietly reclassify
     * two billion legal eras, and it is the bug this paragraph replaced.
     */
    public static final int ERA_UNKNOWN = -1;

    // Field numbers. RESERVED, never reused: 18 (pending queues).
    private static final int F_HEALTH_STATUS = 1;
    private static final int F_SELF_HEAL_KIND = 2;
    private static final int F_PENDING_OP = 3;
    private static final int F_MOMENT = 8;
    private static final int F_LAST_HEALTHY_MOMENT = 9;
    private static final int F_RECOVERED_AT = 25;
    private static final int F_SELF_HEAL_BUDGET = 10;
    private static final int F_STORED_STATUS_REQUEST = 11;
    private static final int F_EXPECTED_COMMIT_KIND = 12;
    private static final int F_MEMBERSHIP_HISTORY = 14;
    private static final int F_EPOCH_AUTHENTICATORS = 15;
    private static final int F_MEMBER_VALIDITY = 16;
    private static final int F_LAST_PARTICIPANT_KEY_UPDATE = 17;
    // Identity/routing, outside §4.8's list but needed to reconstitute the row standalone.
    private static final int F_IDENTITY = 20;
    private static final int F_GROUP_ID = 21;
    private static final int F_RCS_GROUP_ID = 22;
    private static final int F_PEER_E164 = 23;
    private static final int F_SENDS_THIS_EPOCH = 24;
    private static final int F_FTD_RESEND_COUNTS = 26;
    private static final int F_SENDS_SINCE_LEAF_ROTATION = 27;
    private static final int F_SELF_LEFT_AT = 28;
    /** Which ERA {@link #epochAuthenticators} belongs to. */
    private static final int F_EPOCH_AUTH_ERA = 29;
    /** §4.8 row 13, and it keeps the number that was reserved for it all along. */
    private static final int F_CONTINUITY_TOKEN = 13;

    /** §4.8 row 12: what commit we are waiting to see come back. */
    public enum ExpectedCommitKind {
        NONE(0), SELF_KEY_UPDATE(1), REQUEST_METADATA_KEYS(2);

        public final int wire;

        ExpectedCommitKind(final int wire) { this.wire = wire; }

        public static ExpectedCommitKind fromWire(final int w) {
            for (final ExpectedCommitKind k : values()) if (k.wire == w) return k;
            return NONE;
        }
    }

    /**
     * §4.8 row 10: the self-heal limiter's state.
     *
     * <p>Distinct from {@link MlsPendingOperation#attemptCount} and the distinction is load-bearing:
     * <b>this is cleared by the §5.10 hooks; that is not.</b> Conflating them means either the
     * self-heal limiter never resets (a group that heals twice hits its limit on the second
     * attempt) or the retry limiter is reset by a state transition, which a crash loop then rides.
     */
    public static final class SelfHealBudget {
        public final int retryCount;
        public final long firstAttemptAtMs;
        /** The "one more marker" of §4.8 row 10: a heal has run since the last clear. */
        public final boolean healedSinceClear;

        public SelfHealBudget(final int retryCount, final long firstAttemptAtMs,
                final boolean healedSinceClear) {
            this.retryCount = Math.max(0, retryCount);
            this.firstAttemptAtMs = firstAttemptAtMs;
            this.healedSinceClear = healedSinceClear;
        }

        public static final SelfHealBudget EMPTY = new SelfHealBudget(0, 0L, false);

        public SelfHealBudget counted(final long nowMs) {
            return new SelfHealBudget(retryCount + 1,
                    firstAttemptAtMs == 0L ? nowMs : firstAttemptAtMs, true);
        }

        /**
         * Has the limiter's window elapsed since the first attempt in it?
         *
         * <p>A window that has run out means <b>start a new one</b>, not "this conversation may
         * never heal again" — see {@link #rolled}.
         */
        public boolean windowElapsed(final long nowMs, final long windowMs) {
            return windowMs > 0L && firstAttemptAtMs > 0L && (nowMs - firstAttemptAtMs) >= windowMs;
        }

        /** A fresh window: the count reset, ready to be {@link #counted} at {@code nowMs}. */
        public SelfHealBudget rolled() {
            return new SelfHealBudget(0, 0L, healedSinceClear);
        }

        /**
         * Is the budget spent <b>within the current window</b>?
         *
         * <p><b>The window elapsing is deliberately NOT exhaustion.</b> It used
         * to be:
         *
         * <pre>return windowMs &gt; 0 &amp;&amp; firstAttemptAtMs &gt; 0 &amp;&amp; (nowMs - firstAttemptAtMs) &gt;= windowMs;</pre>
         *
         * which reads a rate-limit period as a permanent deadline. Once
         * {@code now - firstAttemptAtMs} passed the window it stayed past it <b>forever</b>, so a
         * conversation that had ever attempted a self-heal became permanently un-healable a day
         * later. The only reset is {@code noteForwardProgress()}, and forward progress requires a
         * successful heal — which the budget was refusing. A closed loop.
         *
         * <p>Device-proven against a Google Messages peer: every send was refused by the
         * server with {@code incorrect-epoch-authenticator}, the negative-delivery IMDN routed the
         * reason correctly, the remedy asked for a self-heal — and the limiter answered
         * {@code budget EXHAUSTED (time limit 86400000ms reached, reason 22, attempts=3)} with the
         * retry limit at 5. Three attempts of five, refused on time, on a conversation whose only
         * route back was the heal it was refusing.
         *
         * <p>Now the window ROLLS: exhaustion is the retry count within a window, and an elapsed
         * window starts a fresh one. The anti-infinite-loop guarantee is unchanged and is now
         * actually a rate — at most {@code limit} attempts per {@code windowMs} — instead of a
         * one-shot allowance that turns terminal.
         *
         * <p><b>On fidelity:</b> Google Messages has two exhaustion arms, reason 6
         * (RETRY_LIMIT_REACHED) and
         * reason 22 (TIME_LIMIT_REACHED), and this keeps the first. What reason 22 means on Google
         * Messages'
         * side is not something we can read from here — but a literal permanent deadline would make
         * Google Messages' own conversations un-healable after a day, so the deadline reading is far
         * more likely to be ours than theirs. Left open rather than assumed.
         */
        public boolean exhausted(final int limit, final long nowMs, final long windowMs) {
            if (windowElapsed(nowMs, windowMs)) return false;   // a new window, not a dead end
            return limit > 0 && retryCount >= limit;
        }

        @Override public String toString() {
            return "budget{n=" + retryCount + " since=" + firstAttemptAtMs
                    + (healedSinceClear ? " healed" : "") + "}";
        }
    }

    /**
     * §4.8 row 11: a requested health status that has not been acted on.
     *
     * <p>Superseded by staleness, NOT by severity — Google Messages has no severity lattice, and inventing
     * one would change which request wins.
     */
    public static final class StatusRequest {
        public final int requestedStatus;
        public final int cause;

        public StatusRequest(final int requestedStatus, final int cause) {
            this.requestedStatus = requestedStatus;
            this.cause = cause;
        }

        @Override public String toString() {
            return "req{" + MlsHealthStates.name(requestedStatus) + " cause=" + cause + "}";
        }
    }

    /** §4.8 row 16: a member's certificate validity window. */
    public static final class MemberValidity {
        public final long notBeforeSecs;
        public final long notAfterSecs;

        public MemberValidity(final long notBeforeSecs, final long notAfterSecs) {
            this.notBeforeSecs = notBeforeSecs;
            this.notAfterSecs = notAfterSecs;
        }

        public boolean expiresWithin(final long nowSecs, final long horizonSecs) {
            return notAfterSecs > 0L && (notAfterSecs - nowSecs) < horizonSecs;
        }
    }

    // ---- identity / routing --------------------------------------------------------------------

    /** Self MSISDN — the {@code identity_id} half of the key. */
    public final String identity;
    /** The MLS group id — the other half of the key. */
    public final byte[] groupId;
    public final String rcsGroupId;
    public final String peerE164;

    // ---- §4.8 --------------------------------------------------------------------------------

    /** Row 1: one of {@link MlsHealthStates}' 16 values. */
    public final int healthStatus;
    /** Row 2: NOT derivable from row 1 — see {@link MlsSelfHealKind}. */
    public final MlsSelfHealKind selfHealKind;
    /** Row 3: nullable, and a FIELD rather than a table. Null means nothing is in flight. */
    public final MlsPendingOperation pendingOperation;
    /** Row 8: the group's current {@code (era, epoch)}. */
    public final Moment moment;
    /** Row 9: distinguishes "we are behind" from "the fetched info is stale". */
    public final Moment lastHealthyMoment;
    /**
     * The moment a RECOVERY landed in Healthy — §5.10 Block A.
     *
     * <p>Deliberately NOT the same as {@link #lastHealthyMoment}, which is stamped on EVERY entry to
     * Healthy. This one is stamped only when Healthy was reached FROM a recovery state, so
     * "when did this conversation last need repairing" stays answerable after it has been healthy
     * for a while. Collapsing the two loses that distinction entirely.
     */
    public final Moment recoveredAt;
    /** Row 10. */
    public final SelfHealBudget selfHealBudget;
    /** Row 11: nullable. */
    public final StatusRequest storedStatusRequest;
    /** Row 12. */
    public final ExpectedCommitKind expectedCommitKind;
    /** Row 14: era → epoch → members. Two-level because a past-epoch message needs the members AS
     *  OF THAT EPOCH to validate its sender. */
    public final Map<Integer, Map<Long, String[]>> membershipHistory;
    /**
     * Row 15: epoch → epoch_authenticator, <b>for the ONE era named by {@link #epochAuthEra}</b>.
     *
     * <h2>Why one era, and why the key used to be wrong</h2>
     *
     * <p>The writer keyed on the EPOCH ALONE while the record spanned eras, and <b>a new era
     * restarts the epoch at 0</b> ({@code MlsAppMessage}, {@code MlsPendingQueue},
     * {@code MlsProviderTransport} all say so independently). {@link #toBuilder} carried the map
     * forward and nothing ever cleared it, so era N+1's epochs 0..k SILENTLY OVERWROTE era N's. The
     * 32 entries were not 32 epochs of history; they were up to 32 distinct epoch NUMBERS mixed
     * across eras, older era clobbered.
     *
     * <p>It never returned a wrong answer, which is exactly why it went unnoticed: the only reader
     * asks for the CURRENT epoch, and the current era's write is always the most recent. It was a
     * constraint on what the map COULD be used for — and the tell was four lines away in the same
     * builder, where {@link Builder#putMembership} is era-qualified and
     * {@link Builder#putEpochAuthenticator} was not.
     *
     * <p><b>The fix is to bound the map to one era rather than to widen it to several.</b> An era
     * advance is a NEW GROUP reached by a Welcome, not a point on the old commit chain — so a prior
     * era's authenticators cannot answer any question about this one. They are not stale history;
     * they are about a different group, and keeping them could only mislead a reader into treating
     * them as a point on our chain. {@link Builder#putEpochAuthenticator} therefore CLEARS the map
     * when the era it is handed differs from {@link #epochAuthEra}. That is also why this is not
     * {@link #membershipHistory}'s two-level shape: membership needs several eras because a
     * past-epoch message must be validated against the members as of that epoch, and the messages
     * outlive the era. An authenticator identifies a point on a chain, and the chain does not
     * survive its era.
     *
     * <p>Depth stays at {@link #EPOCH_AUTH_RETENTION} and now MEANS something: 32 epochs of the
     * current era's chain. Whether 32 is the right number is answerable now that the map can hold
     * what its name says; it was not answerable before, and deciding it first would have baked the
     * ambiguity in either way.
     */
    public final Map<Long, byte[]> epochAuthenticators;

    /**
     * The era {@link #epochAuthenticators} belongs to, or {@link #ERA_UNKNOWN}.
     *
     * <p>{@link #ERA_UNKNOWN} means a record written before the era key existed: its map may hold
     * entries from several eras with the older ones overwritten, so it must NOT be read as history.
     * The current-epoch lookup is still correct on such a record — that entry is the most recent
     * write — which is why the migration does nothing on DECODE and lets the first write clear it.
     * Dropping the entries at decode instead would leave {@code g.epochAuth} null until the next
     * write, and a 1:1 seal with no {@code Epoch-Authenticator} header is one a Google Messages peer
     * refuses to parse.
     */
    public final int epochAuthEra;
    /** Row 16: leaf index → validity window. */
    public final Map<Integer, MemberValidity> memberValidity;
    /** Row 17: MSISDN → last participant-key update, ms. */
    public final Map<String, Long> lastParticipantKeyUpdate;

    /**
     * §7.6 / §10.3: how many times we have reported a <b>failed-to-decrypt</b> for a given message
     * id, keyed by message id. DURABLE, and that is the whole point.
     *
     * <p>This lived in an in-memory {@code HashMap}, so a process restart reset §10.3's cap and we
     * re-reported the same permanently-undecryptable message to the peer indefinitely — which is
     * precisely the chain the cap exists to stop. The spec calls the count durable by construction
     * (a column, with per-client counts derived by counting sibling rows); we have no message-row
     * table in the transport, so it rides the record instead.
     *
     * <p><b>BOUNDED</b> at {@link #MAX_FTD_RESEND_ENTRIES}, because §4.8 shape-rule 1 writes the
     * record WHOLE — an unbounded map would grow the blob on every undecryptable message forever.
     * Insertion-ordered, and the oldest entry is evicted first: a message id we have not touched in
     * hundreds of failures is not the one whose cap still matters.
     *
     * <p>Not to be confused with the count of distinct FTDs a PEER has reported to us, which stays
     * in memory deliberately — persisting that one would make two unrelated failures weeks apart
     * escalate to a whole-group era advance.
     */
    public final Map<String, Integer> ftdResendCounts;

    /** How many message ids' FTD counts we keep. See {@link #ftdResendCounts} on why it is bounded. */
    public static final int MAX_FTD_RESEND_ENTRIES = 32;

    /**
     * Ours, not §4.8's: app sends since the last EPOCH advance.
     *
     * <p>This is the APPLICATION RATCHET generation — it resets whenever the epoch changes, ours or a
     * peer's, because a new epoch really does reset the SecretTree to generation 0. That is the
     * {@code max_skip} concern it was created for.
     *
     * <p>It is deliberately NOT the usage-limit input any more — see {@link #sendsSinceLeafRotation}.
     */
    public final int sendsThisEpoch;

    /**
     * Ours: app sends since OUR OWN LEAF KEY last rotated — the {@code REKEY_AFTER_SENDS} input, and
     * our stand-in for RCC.16's {@code encryption_key_usage_level}.
     *
     * <p><b>Why this is a second counter and not the one above.</b> {@code encryption_key_usage_level}
     * is a property of the KEY, not of the epoch, and the two diverge constantly. Our leaf key rotates
     * only when WE commit with an UpdatePath — which mls-rs includes for a rekey (no proposals), a
     * remove, or a GroupContext-extensions commit, and OMITS for an Add-only commit. A peer's commit
     * never rotates our leaf at all; it rotates theirs.
     *
     * <p>So a single per-epoch counter is reset by events that leave our key exactly where it was. In
     * a group seeing an add — or any peer commit — at least once per {@code REKEY_AFTER_SENDS} of our
     * sends, the usage-limit rekey would never fire and the leaf key would have no bound on its
     * lifetime from that mechanism at all. Busy groups are precisely where those events are frequent,
     * so the bound weakened exactly where traffic was highest.
     *
     * <p>Absent from records written before this field existed. Those are seeded from
     * {@code sendsThisEpoch} on load rather than from zero: zero would silently grant every existing
     * conversation a fresh full budget, which is the one direction that DELAYS a rotation.
     */
    public final int sendsSinceLeafRotation;

    /**
     * Ours, not §4.8's: when WE proposed our own removal from this group, in wall-clock ms, or
     * {@code 0} if we have not (RCC.16 §9.4).
     *
     * <p><b>A timestamp rather than a flag</b> for the same reason {@code recoveredAt} is a moment
     * rather than a boolean: the interesting question in a bugreport is not only whether the
     * conversation was left but WHEN, relative to everything else in the record.
     *
     * <p><b>It is not a health status, and deliberately so.</b> {@link MlsHealthStates} is a
     * faithful mirror of the engine's own enum, whose eighteen states and §5.3 edge table were
     * proven against Google Messages; a self-leave is not one of them, because Google Messages has no self-leave
     * flow at all. Borrowing {@code DoneEndMls} would assert an {@code end_mls} commit that never
     * happened, over an edge named {@code EndMlsAppliedByRemoteClient}, and every reader of that
     * status — revive, the downgrade funnel, ED-1 — would then reason about a downgrade nobody
     * performed. So this is a separate field, and the guards that consult it say so by name.
     *
     * <p>Absent from records written before this field existed, which decode to {@code 0} — the
     * correct answer for every conversation that predates the ability to leave one.
     */
    public final long selfLeftAtMs;

    /** Whether WE have proposed our own removal from this group — see {@link #selfLeftAtMs}. */
    public boolean selfLeft() { return selfLeftAtMs > 0L; }

    /**
     * §4.8 row 13 — RCC.16 §7.11.12.1's {@code continuity_token}: 32 bytes of CSPRNG (§8.3.1.1),
     * or empty when we hold none. <b>The DECODED value</b>, never the {@code opaque<V>} framing it
     * arrives in.
     *
     * <h2>Two arrival routes, one store — which is the whole point of putting it here</h2>
     *
     * <ol>
     *   <li><b>§7.11.12.1, the Welcome.</b> The token rides the ENCRYPTED GroupInfo of a Welcome,
     *       "for new joiners", and this is the only route a peer has ever been measured to use:
     *       a Welcome built for us by a Google Messages peer carried
     *       {@code 0xF010=33B}. 33 is {@code 0x20 ‖ 32}, so it was a well-formed token and not an
     *       incidental byte string in the right code point.</li>
     *   <li><b>§10.5.4, a {@code GroupMetadataKeys} message</b> — the RECOVERY route, answering a
     *       §10.5.2 request.</li>
     * </ol>
     *
     * <p>Until this field landed NEITHER route had a consumer: the Welcome-borne token reached a
     * log-only instrument that by its own docs "never looks at a value", and the §10.5.4 token was
     * written to a preference key that nothing in the tree read back. Two halves of one gap, which
     * is why the repair is one store rather than two readers.
     *
     * <h2>Why it survives what it has to survive</h2>
     *
     * <p>The token is defined as continuous across Epochs AND Eras, and an Era advance destroys the
     * MLS group — so any home tied to a group INSTANCE would lose it at exactly the moment it
     * matters. This record is keyed {@code (identity, groupId)}, and an era advance reuses the MLS
     * group id rather than minting one, so the record — and this field with it — carries across.
     *
     * <h2>Nothing consumes it yet, and that is deliberate</h2>
     *
     * <p>{@link MlsContinuityPolicy} is complete, host-tested and NOT wired into production; wiring
     * it in is a separate decision with a §11.2 downgrade at the end of it. Storing anyway is the
     * standing rule for server-issued material we cannot re-request on demand: gate the CONSUMPTION,
     * never strip the storage as dead code.
     *
     * <p><b>It is a SECRET.</b> Never log the value — {@link #dump()} prints its length only — and
     * never put it in a GroupInfo bound for the server (the engine's {@code WELCOME_ONLY_EXTS}
     * strips {@code 0xF010} from anything leaving the device).
     */
    public final byte[] continuityToken;

    /** True iff we hold a continuity token for this conversation — §8.3.1.2's "HAVE token" arm. */
    public boolean hasContinuityToken() { return continuityToken.length > 0; }

    private MlsConversationRecord(final Builder b) {
        identity = b.identity == null ? "" : b.identity;
        groupId = b.groupId == null ? new byte[0] : b.groupId;
        rcsGroupId = b.rcsGroupId == null ? "" : b.rcsGroupId;
        peerE164 = b.peerE164 == null ? "" : b.peerE164;
        healthStatus = b.healthStatus;
        selfHealKind = b.selfHealKind == null ? MlsSelfHealKind.NONE : b.selfHealKind;
        pendingOperation = b.pendingOperation;
        moment = b.moment;
        lastHealthyMoment = b.lastHealthyMoment;
        recoveredAt = b.recoveredAt;
        selfHealBudget = b.selfHealBudget == null ? SelfHealBudget.EMPTY : b.selfHealBudget;
        storedStatusRequest = b.storedStatusRequest;
        expectedCommitKind = b.expectedCommitKind == null
                ? ExpectedCommitKind.NONE : b.expectedCommitKind;
        membershipHistory = Collections.unmodifiableMap(new TreeMap<>(b.membershipHistory));
        epochAuthenticators = Collections.unmodifiableMap(new TreeMap<>(b.epochAuthenticators));
        // VERBATIM, not normalised: era is unsigned, so a negative int here is an era above 2^31,
        // not an absent one. See ERA_UNKNOWN.
        epochAuthEra = b.epochAuthEra;
        memberValidity = Collections.unmodifiableMap(new TreeMap<>(b.memberValidity));
        lastParticipantKeyUpdate =
                Collections.unmodifiableMap(new LinkedHashMap<>(b.lastParticipantKeyUpdate));
        ftdResendCounts = Collections.unmodifiableMap(new LinkedHashMap<>(b.ftdResendCounts));
        sendsThisEpoch = b.sendsThisEpoch;
        sendsSinceLeafRotation = b.sendsSinceLeafRotation;
        selfLeftAtMs = Math.max(0L, b.selfLeftAtMs);
        continuityToken = b.continuityToken == null ? new byte[0] : b.continuityToken;
    }

    /** The storage key. Identity FIRST, because the engine is per-identity. */
    public static String key(final String identity, final byte[] groupId) {
        return (identity == null ? "" : identity) + "/" + hex(groupId);
    }

    public String key() { return key(identity, groupId); }

    // ---- the SECOND key this record is addressed by: the conversation → group alias -------------
    //
    // The alias row lives in its own preferences file (MlsRecordStore.ALIAS_PREFS) and is what makes
    // a record reachable from a conversation at all: loadFromRecord asks groupIdFor(identity,
    // conversationId) before it can build the record key above. Its SHAPE is here, beside key(),
    // because the two are one addressing scheme and because only here is it host-testable.

    /**
     * The alias row's key. An E.164 cannot contain NUL, which is what makes the prefix unambiguous.
     *
     * <p><b>{@code conversationId} is whatever {@code putGroup} was called with</b>, and in practice
     * that is the transport's CANONICAL key ({@code "g:"+rcsGroupId} / {@code "p:"+peerE164}) — all
     * 22 rows measured on one device are canonical keys, none is an app conversation id. That is
     * the fact the teardown below exists to stop depending on.
     */
    public static String aliasKey(final String identity, final String conversationId) {
        return (identity == null ? "" : identity) + "\u0000" + conversationId;
    }

    /**
     * Every alias row to drop when one conversation is forgotten.
     *
     * <h2>Chosen by VALUE, because the KEY SPACE is exactly what a teardown cannot be trusted to
     * know</h2>
     *
     * <p>{@code putAlias} has one caller, {@code putGroup(conversationId, g)}, and every call site
     * passes the transport's canonical key. The teardown is handed a
     * {@code MlsPerConversationState.Scope} whose {@code conversationId} comes from
     * {@code conversationIdFor(key)} — the app's own conversation row id — so removing the alias
     * by that field could never match a row, exactly as {@code mConvAlias.remove(key)} could never
     * match one. The in-memory half of that defect was found and fixed; the
     * DURABLE half, one layer down, was not.
     *
     * <p>Device-measured: four alias rows naming groups with
     * no record and no engine state, three of them test fixtures which a "DEEP: both
     * halves dropped" forget had reported as gone.
     *
     * <p>So the row is selected by the one identifier the teardown provably holds in the same space
     * as the writer: the GROUP ID, which {@code putAlias} stored as the row's VALUE. Every alias for
     * this identity naming the group whose record is being deleted is dead by construction — a
     * conversation can be reachable under more than one key and all of them name the one group.
     *
     * <p>The conversation keys are still honoured, for the case the value cannot address: a scope
     * with no group id (the engine never had one) can only remove by key, and there we do not know
     * which space the writer used, so BOTH candidates are offered rather than one guessed.
     *
     * <p>Returns only keys that are actually PRESENT, so the caller's count is rows dropped rather
     * than removals attempted — {@code clearConversationState} prints it as evidence, and "we called
     * a teardown" and "a teardown had something to drop" are different claims.
     *
     * @param aliasRows        every row of the alias store, key → stored value (a
     *                         {@code SharedPreferences.getAll()}, so the value type is open)
     * @param identity         our own MSISDN; rows for other identities are never touched
     * @param encodedGroupId   the value {@code putAlias} would have written for this group, or null
     * @param conversationKeys candidate keys, any of which may be null
     */
    public static List<String> aliasKeysToForget(final Map<String, ?> aliasRows,
            final String identity, final String encodedGroupId, final String... conversationKeys) {
        final List<String> out = new ArrayList<>();
        if (aliasRows == null || aliasRows.isEmpty()) return out;
        final Set<String> seen = new LinkedHashSet<>();
        final String prefix = aliasKey(identity, "");
        if (encodedGroupId != null && !encodedGroupId.isEmpty()) {
            // Map<String, ?> because this is handed a SharedPreferences.getAll() straight from the
            // store: a non-String value is a row somebody else wrote and is not ours to delete.
            for (final Map.Entry<String, ?> e : aliasRows.entrySet()) {
                if (e.getKey() == null || !e.getKey().startsWith(prefix)) continue;
                if (encodedGroupId.equals(e.getValue()) && seen.add(e.getKey())) {
                    out.add(e.getKey());
                }
            }
        }
        if (conversationKeys != null) {
            for (final String c : conversationKeys) {
                if (c == null || c.isEmpty()) continue;
                final String k = aliasKey(identity, c);
                if (aliasRows.containsKey(k) && seen.add(k)) out.add(k);
            }
        }
        return out;
    }

    /** A brand-new record for a group that exists but has never had a status written. */
    public static MlsConversationRecord initial(final String identity, final byte[] groupId,
            final String rcsGroupId, final String peerE164) {
        return builder()
                .identity(identity).groupId(groupId).rcsGroupId(rcsGroupId).peerE164(peerE164)
                .healthStatus(MlsHealthStates.UNKNOWN)
                .build();
    }

    // ---- copies ---------------------------------------------------------------------------------

    public Builder toBuilder() {
        final Builder b = new Builder();
        b.identity = identity;
        b.groupId = groupId;
        b.rcsGroupId = rcsGroupId;
        b.peerE164 = peerE164;
        b.healthStatus = healthStatus;
        b.selfHealKind = selfHealKind;
        b.pendingOperation = pendingOperation;
        b.moment = moment;
        b.lastHealthyMoment = lastHealthyMoment;
        b.recoveredAt = recoveredAt;
        b.selfHealBudget = selfHealBudget;
        b.storedStatusRequest = storedStatusRequest;
        b.expectedCommitKind = expectedCommitKind;
        b.membershipHistory.putAll(membershipHistory);
        b.epochAuthenticators.putAll(epochAuthenticators);
        b.epochAuthEra = epochAuthEra;
        b.memberValidity.putAll(memberValidity);
        b.lastParticipantKeyUpdate.putAll(lastParticipantKeyUpdate);
        b.ftdResendCounts.putAll(ftdResendCounts);
        b.sendsThisEpoch = sendsThisEpoch;
        b.sendsSinceLeafRotation = sendsSinceLeafRotation;
        b.selfLeftAtMs = selfLeftAtMs;
        b.continuityToken = continuityToken;
        return b;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String identity;
        private byte[] groupId;
        private String rcsGroupId;
        private String peerE164;
        private int healthStatus = MlsHealthStates.UNKNOWN;
        private MlsSelfHealKind selfHealKind = MlsSelfHealKind.NONE;
        private MlsPendingOperation pendingOperation;
        private Moment moment;
        private Moment lastHealthyMoment;
        private Moment recoveredAt;
        private SelfHealBudget selfHealBudget = SelfHealBudget.EMPTY;
        private StatusRequest storedStatusRequest;
        private ExpectedCommitKind expectedCommitKind = ExpectedCommitKind.NONE;
        private final Map<Integer, Map<Long, String[]>> membershipHistory = new TreeMap<>();
        private final Map<Long, byte[]> epochAuthenticators = new TreeMap<>();
        private int epochAuthEra = ERA_UNKNOWN;
        private final Map<Integer, MemberValidity> memberValidity = new TreeMap<>();
        private final Map<String, Long> lastParticipantKeyUpdate = new LinkedHashMap<>();
        private final Map<String, Integer> ftdResendCounts = new LinkedHashMap<>();
        private int sendsThisEpoch;
        private int sendsSinceLeafRotation;
        private long selfLeftAtMs;
        private byte[] continuityToken;

        public Builder identity(final String v) { identity = v; return this; }
        public Builder groupId(final byte[] v) { groupId = v; return this; }
        public Builder rcsGroupId(final String v) { rcsGroupId = v; return this; }
        public Builder peerE164(final String v) { peerE164 = v; return this; }
        public Builder healthStatus(final int v) { healthStatus = v; return this; }
        public Builder selfHealKind(final MlsSelfHealKind v) { selfHealKind = v; return this; }
        public Builder pendingOperation(final MlsPendingOperation v) {
            pendingOperation = v; return this;
        }
        public Builder moment(final Moment v) { moment = v; return this; }
        public Builder lastHealthyMoment(final Moment v) { lastHealthyMoment = v; return this; }
        public Builder recoveredAt(final Moment v) { recoveredAt = v; return this; }
        public Builder selfHealBudget(final SelfHealBudget v) { selfHealBudget = v; return this; }
        public Builder storedStatusRequest(final StatusRequest v) {
            storedStatusRequest = v; return this;
        }
        public Builder expectedCommitKind(final ExpectedCommitKind v) {
            expectedCommitKind = v; return this;
        }
        /**
         * Record {@code auth} for {@code (era, epoch)}.
         *
         * <p><b>The {@code era} parameter is the whole fix.</b> This took an epoch alone while the
         * record spanned eras and a new era restarts the epoch at 0, so era N+1's epochs silently
         * overwrote era N's. Taking the era makes a caller that does not know it unable to compile,
         * which is the only way a keying bug like this stops recurring — the sibling
         * {@link #putMembership} has been era-qualified all along.
         *
         * <p>A DIFFERENT era CLEARS the map first. See {@link MlsConversationRecord#epochAuthenticators}
         * for why that is the right bound rather than {@code putMembership}'s several-era shape: an
         * era advance is a new group reached by a Welcome, so a prior era's authenticators are not
         * old points on this chain, they are points on another one.
         */
        public Builder putEpochAuthenticator(final int era, final long epoch, final byte[] auth) {
            if (era != epochAuthEra) {
                // Includes the ERA_UNKNOWN case, which is a record written before this field
                // existed: its entries may be mixed across eras, and the first write is where that
                // ambiguity is resolved rather than carried.
                //
                // THE ERA IS STORED VERBATIM. An earlier cut wrote `era < 0 ? ERA_UNKNOWN : era`,
                // which is wrong twice over: era is an unsigned u32 in a signed int, so it
                // reclassified every era above 2^31 as "unknown"; and it made this setter
                // NON-IDEMPOTENT for such an era — the stored value never equalled the one handed
                // in, so every subsequent write cleared the whole map again. Comparing and storing
                // the value as given is both correct and idempotent for every era.
                epochAuthenticators.clear();
                epochAuthEra = era;
            }
            epochAuthenticators.put(epoch, auth == null ? new byte[0] : auth);
            trimOldest(epochAuthenticators, EPOCH_AUTH_RETENTION);
            return this;
        }

        /**
         * Put an entry back exactly as it was stored — the DECODER's door, and it must not be the
         * era-aware one.
         *
         * <p>Two reasons, either of which alone is decisive. {@link #putEpochAuthenticator} CLEARS
         * the map when the era differs, so a decoder calling it would wipe the entries it had just
         * restored on every row after the first. And {@code F_EPOCH_AUTH_ERA} is written LAST — the
         * append convention this codec keeps — so while the entries are being read the era is not
         * known yet, and there is nothing correct to pass.
         *
         * <p>It is also the right SHAPE: a decoder mirrors what was written and does not re-derive
         * it. Normalising on the way in is how a store starts disagreeing with itself.
         */
        Builder restoreEpochAuthenticator(final long epoch, final byte[] auth) {
            epochAuthenticators.put(epoch, auth == null ? new byte[0] : auth);
            // A bound against a corrupt or hostile record, not a policy decision: a well-formed one
            // is already within it because the writer trims.
            trimOldest(epochAuthenticators, EPOCH_AUTH_RETENTION);
            return this;
        }
        public Builder putMembership(final int era, final long epoch, final String[] members) {
            Map<Long, String[]> byEpoch = membershipHistory.get(era);
            if (byEpoch == null) {
                byEpoch = new TreeMap<>();
                membershipHistory.put(era, byEpoch);
            }
            byEpoch.put(epoch, members == null ? new String[0] : members);
            trimOldest(byEpoch, MEMBERSHIP_EPOCH_RETENTION);
            while (membershipHistory.size() > MEMBERSHIP_ERA_RETENTION) {
                membershipHistory.remove(((TreeMap<Integer, ?>) membershipHistory).firstKey());
            }
            return this;
        }
        public Builder putMemberValidity(final int leafIndex, final MemberValidity v) {
            memberValidity.put(leafIndex, v); return this;
        }
        public Builder putParticipantKeyUpdate(final String msisdn, final long atMs) {
            lastParticipantKeyUpdate.put(msisdn == null ? "" : msisdn, atMs); return this;
        }

        /**
         * Record the FTD resend count for {@code messageId}, evicting the oldest entry if the map is
         * full.
         *
         * <p>Re-inserting an existing key does NOT refresh its position — the eviction order is
         * first-seen, not last-touched. That is deliberate: a message whose cap is being approached
         * is exactly the one that keeps getting touched, so LRU would keep it forever while evicting
         * ids that had only just started failing.
         */
        public Builder putFtdResendCount(final String messageId, final int count) {
            final String k = messageId == null ? "" : messageId;
            if (!ftdResendCounts.containsKey(k)
                    && ftdResendCounts.size() >= MAX_FTD_RESEND_ENTRIES) {
                final java.util.Iterator<String> it = ftdResendCounts.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            ftdResendCounts.put(k, Math.max(0, count));
            return this;
        }
        public Builder sendsThisEpoch(final int v) { sendsThisEpoch = v; return this; }
        public Builder sendsSinceLeafRotation(final int v) { sendsSinceLeafRotation = v; return this; }
        public Builder selfLeftAtMs(final long v) { selfLeftAtMs = v; return this; }

        /**
         * §4.8 row 13 — see {@link MlsConversationRecord#continuityToken}. Pass the DECODED value;
         * {@code null} and empty both mean "we hold none".
         */
        public Builder continuityToken(final byte[] v) {
            continuityToken = (v == null || v.length == 0) ? null : v.clone();
            return this;
        }

        public MlsConversationRecord build() { return new MlsConversationRecord(this); }
    }

    /**
     * Retention bounds, because §4.8 row 14 names a count-based GC and an unbounded map in a record
     * that is rewritten whole is a write amplification bug waiting to happen.
     */
    private static final int EPOCH_AUTH_RETENTION = 32;
    private static final int MEMBERSHIP_EPOCH_RETENTION = 32;
    private static final int MEMBERSHIP_ERA_RETENTION = 8;

    private static <K, V> void trimOldest(final Map<K, V> m, final int keep) {
        while (m.size() > keep && m instanceof TreeMap) {
            m.remove(((TreeMap<K, V>) m).firstKey());
        }
    }

    // ---- whole-blob codec ------------------------------------------------------------------------
    //
    // Hand-rolled TLV rather than protobuf: the engine module deliberately has no proto runtime, and
    // the decoder SKIPS unknown field numbers so a record written by a newer build still loads. That
    // forward-compatibility is not decoration — a downgrade that discards a group's health state
    // would send it back through the recovery ladder from Unknown.
    //
    // FRAMED: [version][varint payload length][payload], where the payload is the bare
    // (field, wireType, value) stream. The length covers the payload exactly and decode() refuses
    // any blob whose remaining bytes disagree with it.
    //
    // WHY A LENGTH PREFIX AND NOT AN END-OF-RECORD MARKER. The format carried neither,
    // so decode() read until the buffer ran out and could only notice a short read by catching the
    // cursor's exception — which fires only for a cut landing MID-field. A cut landing exactly on a
    // FIELD BOUNDARY leaves a well-formed SHORTER record: nothing threw, and decode returned a
    // record silently missing every field after the cut, which is precisely the "Unknown with
    // nothing pending" lie the catch block below exists to prevent. Nothing in the bytes
    // distinguished it from a record legitimately written that short.
    //
    // Either a trailing sentinel or a leading length closes that. The length wins on three counts:
    //   * it is checked BEFORE parsing, so a bad frame can never half-populate a Builder;
    //   * it catches a buffer that is too LONG as well as too short. A sentinel only proves the
    //     last byte written arrived, so a blob with trailing bytes — a concatenation, a padded
    //     rewrite, the tail of a longer record partially overwritten by a shorter one — would still
    //     parse "cleanly", with the junk skipped as unknown fields;
    //   * it costs no field number, leaving the reserved-numbers rule above untouched.
    //
    // It is NOT a checksum: the frame proves the byte COUNT, not the bytes. Corruption in the
    // middle that preserves the length stays out of scope, deliberately — a checksum answers
    // bit-rot, and what we have actually seen is truncation.

    public byte[] encode() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        putStr(out, F_IDENTITY, identity);
        putBytes(out, F_GROUP_ID, groupId);
        putStr(out, F_RCS_GROUP_ID, rcsGroupId);
        putStr(out, F_PEER_E164, peerE164);
        putVarint(out, F_HEALTH_STATUS, healthStatus);
        putVarint(out, F_SELF_HEAL_KIND, selfHealKind.wire);
        putVarint(out, F_SENDS_THIS_EPOCH, sendsThisEpoch);
        if (pendingOperation != null) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(64);
            putVarint(p, 1, pendingOperation.kind.wire);
            putVarint(p, 2, pendingOperation.attemptCount);
            putVarint(p, 3, pendingOperation.startedAtMs);
            if (pendingOperation.momentBinding != null) {
                putBytes(p, 4, momentBytes(pendingOperation.momentBinding));
            }
            putStr(p, 5, pendingOperation.context);
            putVarint(p, 6, pendingOperation.origin.wire);
            putBytes(out, F_PENDING_OP, p.toByteArray());
        }
        if (moment != null) putBytes(out, F_MOMENT, momentBytes(moment));
        if (lastHealthyMoment != null) {
            putBytes(out, F_LAST_HEALTHY_MOMENT, momentBytes(lastHealthyMoment));
        }
        if (recoveredAt != null) putBytes(out, F_RECOVERED_AT, momentBytes(recoveredAt));
        if (selfHealBudget.retryCount != 0 || selfHealBudget.firstAttemptAtMs != 0L
                || selfHealBudget.healedSinceClear) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(24);
            putVarint(p, 1, selfHealBudget.retryCount);
            putVarint(p, 2, selfHealBudget.firstAttemptAtMs);
            putVarint(p, 3, selfHealBudget.healedSinceClear ? 1 : 0);
            putBytes(out, F_SELF_HEAL_BUDGET, p.toByteArray());
        }
        if (storedStatusRequest != null) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(16);
            putVarint(p, 1, storedStatusRequest.requestedStatus);
            putVarint(p, 2, storedStatusRequest.cause);
            putBytes(out, F_STORED_STATUS_REQUEST, p.toByteArray());
        }
        if (expectedCommitKind != ExpectedCommitKind.NONE) {
            putVarint(out, F_EXPECTED_COMMIT_KIND, expectedCommitKind.wire);
        }
        for (final Map.Entry<Integer, Map<Long, String[]>> era : membershipHistory.entrySet()) {
            for (final Map.Entry<Long, String[]> ep : era.getValue().entrySet()) {
                final ByteArrayOutputStream p = new ByteArrayOutputStream(64);
                putVarint(p, 1, era.getKey());
                putVarint(p, 2, ep.getKey());
                for (final String m : ep.getValue()) putStr(p, 3, m);
                putBytes(out, F_MEMBERSHIP_HISTORY, p.toByteArray());
            }
        }
        for (final Map.Entry<Long, byte[]> e : epochAuthenticators.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(48);
            putVarint(p, 1, e.getKey());
            putBytes(p, 2, e.getValue());
            putBytes(out, F_EPOCH_AUTHENTICATORS, p.toByteArray());
        }
        for (final Map.Entry<Integer, MemberValidity> e : memberValidity.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(24);
            putVarint(p, 1, e.getKey());
            putVarint(p, 2, e.getValue().notBeforeSecs);
            putVarint(p, 3, e.getValue().notAfterSecs);
            putBytes(out, F_MEMBER_VALIDITY, p.toByteArray());
        }
        for (final Map.Entry<String, Long> e : lastParticipantKeyUpdate.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(32);
            putStr(p, 1, e.getKey());
            putVarint(p, 2, e.getValue());
            putBytes(out, F_LAST_PARTICIPANT_KEY_UPDATE, p.toByteArray());
        }
        for (final Map.Entry<String, Integer> e : ftdResendCounts.entrySet()) {
            final ByteArrayOutputStream p = new ByteArrayOutputStream(48);
            putStr(p, 1, e.getKey());
            putVarint(p, 2, e.getValue());
            putBytes(out, F_FTD_RESEND_COUNTS, p.toByteArray());
        }
        // APPENDED LAST, ON PURPOSE — and the next new field goes after this one. Field order is
        // free in this format, but writing new fields at the end leaves every existing record's byte
        // layout identical, so adding one cannot shift what any other offset means. That mattered
        // most while a boundary-aligned cut still decoded (see the codec note above); the frame has
        // closed that hole, and the convention stays, because "the old layout is untouched" is the
        // cheapest guarantee a whole-blob format can give a reader.
        putVarint(out, F_SENDS_SINCE_LEAF_ROTATION, sendsSinceLeafRotation);
        putVarint(out, F_SELF_LEFT_AT, selfLeftAtMs);
        // Written only when we hold one, so a record for a conversation that has never seen a token
        // is byte-identical to what the previous build wrote. The field NUMBER is the low one that
        // §4.8 reserved for row 13; the POSITION is the end, which is the convention above.
        if (continuityToken.length > 0) putBytes(out, F_CONTINUITY_TOKEN, continuityToken);
        // Written only when we know it, so a record that has never stored an authenticator is
        // byte-identical to what the previous build wrote. Appended last, per above.
        if (epochAuthEra != ERA_UNKNOWN) putVarint(out, F_EPOCH_AUTH_ERA, epochAuthEra);
        return frame(out.toByteArray());
    }

    /** {@code [VERSION][varint payload length][payload]} — see the codec note above. */
    private static byte[] frame(final byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 8);
        out.write(VERSION);
        writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /**
     * {@code null} if the blob is not a record this build can read. Never a partial record.
     *
     * <p>At {@link #VERSION} that is enforced by the frame rather than inferred from how far the
     * parse happened to get: the declared payload length must match the bytes present exactly, so a
     * truncation is refused wherever it lands — including on a field boundary, where what is left
     * over is itself a perfectly well-formed shorter record.
     */
    public static MlsConversationRecord decode(final byte[] raw) {
        if (raw == null || raw.length < 1) return null;
        final int version = raw[0] & 0xFF;
        if (version != VERSION && version != VERSION_UNFRAMED) return null;
        final Builder b = new Builder();
        try {
            int start = 1;
            if (version == VERSION) {
                final Cursor header = new Cursor(raw, 1);
                final long declaredLength = header.varint();
                start = header.position();
                // The whole of the fix. Everything below this line is unchanged parsing.
                if (declaredLength != (long) (raw.length - start)) return null;
            }
            final Cursor c = new Cursor(raw, start);
            while (c.hasMore()) {
                final int field = (int) c.varint();
                final int wireType = (int) c.varint();
                if (wireType == 0) {
                    final long v = c.varint();
                    switch (field) {
                        case F_HEALTH_STATUS: b.healthStatus = (int) v; break;
                        case F_SELF_HEAL_KIND: b.selfHealKind = MlsSelfHealKind.fromWire((int) v); break;
                        case F_EXPECTED_COMMIT_KIND:
                            b.expectedCommitKind = ExpectedCommitKind.fromWire((int) v); break;
                        case F_SENDS_THIS_EPOCH: b.sendsThisEpoch = (int) v; break;
                        case F_SENDS_SINCE_LEAF_ROTATION:
                            b.sendsSinceLeafRotation = (int) v; break;
                        case F_SELF_LEFT_AT: b.selfLeftAtMs = v; break;
                        case F_EPOCH_AUTH_ERA: b.epochAuthEra = (int) v; break;
                        default: break;                        // unknown scalar: skip
                    }
                } else {
                    final byte[] v = c.bytes();
                    switch (field) {
                        case F_IDENTITY: b.identity = str(v); break;
                        case F_GROUP_ID: b.groupId = v; break;
                        case F_RCS_GROUP_ID: b.rcsGroupId = str(v); break;
                        case F_PEER_E164: b.peerE164 = str(v); break;
                        case F_MOMENT: b.moment = Moment.from(v); break;
                        case F_LAST_HEALTHY_MOMENT: b.lastHealthyMoment = Moment.from(v); break;
                        case F_RECOVERED_AT: b.recoveredAt = Moment.from(v); break;
                        case F_PENDING_OP: b.pendingOperation = decodePending(v); break;
                        case F_SELF_HEAL_BUDGET: b.selfHealBudget = decodeBudget(v); break;
                        case F_STORED_STATUS_REQUEST: b.storedStatusRequest = decodeRequest(v); break;
                        case F_MEMBERSHIP_HISTORY: decodeMembership(b, v); break;
                        case F_EPOCH_AUTHENTICATORS: decodeEpochAuth(b, v); break;
                        case F_MEMBER_VALIDITY: decodeValidity(b, v); break;
                        case F_LAST_PARTICIPANT_KEY_UPDATE: decodeKeyUpdate(b, v); break;
                        case F_FTD_RESEND_COUNTS: decodeFtdResend(b, v); break;
                        case F_CONTINUITY_TOKEN: b.continuityToken = v; break;
                        default: break;                        // unknown length-delimited: skip
                    }
                }
            }
        } catch (final RuntimeException truncated) {
            // A truncated record is an ERROR, never a silently partial one: a half-read record reads
            // as "this group is at Unknown with no pending operation", which is a lie that sends a
            // healthy conversation back through the whole recovery ladder.
            //
            // This arm catches a cut landing MID-field, and a frame header too short to read. A cut
            // landing on a field BOUNDARY throws nothing at all — the length check above is what
            // catches that one, and for an unframed version-1 record nothing does.
            return null;
        }
        // MIGRATION SEED for records written before sendsSinceLeafRotation existed.
        //
        // Seeded from sendsThisEpoch rather than left at zero, and the direction is the whole point:
        // zero would hand every already-persisted conversation a fresh full REKEY_AFTER_SENDS budget
        // at upgrade — i.e. DELAY its next leaf rotation — which is the one direction this fix exists
        // to remove. Seeding from the old counter can only rotate at the same time or sooner.
        //
        // Correct for a genuinely absent field, and harmless when the field is present-but-zero: a
        // record that really has rotated recently also has a small sendsThisEpoch, because a rotation
        // advances the epoch.
        if (b.sendsSinceLeafRotation == 0 && b.sendsThisEpoch > 0) {
            b.sendsSinceLeafRotation = b.sendsThisEpoch;
        }
        return b.build();
    }

    private static MlsPendingOperation decodePending(final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int kind = 0, attempts = 0, origin = 0;
        long started = 0L;
        Moment at = null;
        String ctx = "";
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) {
                final long x = c.varint();
                if (f == 1) kind = (int) x;
                else if (f == 2) attempts = (int) x;
                else if (f == 3) started = x;
                else if (f == 6) origin = (int) x;
            } else {
                final byte[] x = c.bytes();
                if (f == 4) at = Moment.from(x);
                else if (f == 5) ctx = str(x);
            }
        }
        // Field 6 absent -> Origin.UNKNOWN, which does NOT escalate. That is the safe default for a
        // record written before the field existed: an old op on a freshly-upgraded device is far
        // more likely to be ordinary than wedged, and escalating every one of them on the first boot
        // after an update is the churn the categories exist to prevent.
        return new MlsPendingOperation(MlsPendingOperation.Kind.fromWire(kind),
                MlsPendingOperation.Origin.fromWire(origin), attempts, started, at, ctx);
    }

    private static SelfHealBudget decodeBudget(final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int n = 0;
        long since = 0L;
        boolean healed = false;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt != 0) { c.bytes(); continue; }
            final long x = c.varint();
            if (f == 1) n = (int) x;
            else if (f == 2) since = x;
            else if (f == 3) healed = x != 0L;
        }
        return new SelfHealBudget(n, since, healed);
    }

    private static StatusRequest decodeRequest(final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int status = 0, cause = 0;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt != 0) { c.bytes(); continue; }
            final long x = c.varint();
            if (f == 1) status = (int) x;
            else if (f == 2) cause = (int) x;
        }
        return new StatusRequest(status, cause);
    }

    private static void decodeMembership(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int era = 0;
        long epoch = 0L;
        final java.util.List<String> members = new java.util.ArrayList<>();
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) {
                final long x = c.varint();
                if (f == 1) era = (int) x;
                else if (f == 2) epoch = x;
            } else {
                final byte[] x = c.bytes();
                if (f == 3) members.add(str(x));
            }
        }
        b.putMembership(era, epoch, members.toArray(new String[0]));
    }

    private static void decodeEpochAuth(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        long epoch = 0L;
        byte[] auth = new byte[0];
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) { final long x = c.varint(); if (f == 1) epoch = x; }
            else { final byte[] x = c.bytes(); if (f == 2) auth = x; }
        }
        b.restoreEpochAuthenticator(epoch, auth);
    }

    private static void decodeValidity(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        int leaf = 0;
        long nb = 0L, na = 0L;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt != 0) { c.bytes(); continue; }
            final long x = c.varint();
            if (f == 1) leaf = (int) x;
            else if (f == 2) nb = x;
            else if (f == 3) na = x;
        }
        b.putMemberValidity(leaf, new MemberValidity(nb, na));
    }

    private static void decodeKeyUpdate(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        String who = "";
        long at = 0L;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) { final long x = c.varint(); if (f == 2) at = x; }
            else { final byte[] x = c.bytes(); if (f == 1) who = str(x); }
        }
        b.putParticipantKeyUpdate(who, at);
    }

    private static void decodeFtdResend(final Builder b, final byte[] v) {
        final Cursor c = new Cursor(v, 0);
        String mid = "";
        int n = 0;
        while (c.hasMore()) {
            final int f = (int) c.varint();
            final int wt = (int) c.varint();
            if (wt == 0) { final long x = c.varint(); if (f == 2) n = (int) x; }
            else { final byte[] x = c.bytes(); if (f == 1) mid = str(x); }
        }
        b.putFtdResendCount(mid, n);
    }

    // ---- TLV primitives --------------------------------------------------------------------------

    private static void putVarint(final ByteArrayOutputStream o, final int field, final long v) {
        writeVarint(o, field);
        writeVarint(o, 0);
        writeVarint(o, v);
    }

    private static void putBytes(final ByteArrayOutputStream o, final int field, final byte[] v) {
        writeVarint(o, field);
        writeVarint(o, 2);
        writeVarint(o, v.length);
        o.write(v, 0, v.length);
    }

    private static void putStr(final ByteArrayOutputStream o, final int field, final String v) {
        if (v == null || v.isEmpty()) return;
        putBytes(o, field, v.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVarint(final ByteArrayOutputStream o, long v) {
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
    }

    private static String str(final byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    private static byte[] momentBytes(final Moment m) {
        final byte[] b = new byte[12];
        b[0] = (byte) (m.era >>> 24); b[1] = (byte) (m.era >>> 16);
        b[2] = (byte) (m.era >>> 8);  b[3] = (byte) m.era;
        for (int i = 0; i < 8; i++) b[4 + i] = (byte) (m.epoch >>> (56 - 8 * i));
        return b;
    }

    private static String hex(final byte[] b) {
        if (b == null) return "";
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                                 .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** Throws on truncation, which {@link #decode} turns into a null record rather than a partial one. */
    private static final class Cursor {
        private final byte[] b;
        private int p;

        Cursor(final byte[] b, final int p) { this.b = b; this.p = p; }

        boolean hasMore() { return p < b.length; }

        /** Where the next read starts — used to size the frame's payload against the buffer. */
        int position() { return p; }

        long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                if (p >= b.length) throw new IllegalStateException("truncated varint");
                final int x = b[p++] & 0xFF;
                v |= ((long) (x & 0x7F)) << shift;
                if ((x & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("varint too long");
            }
        }

        byte[] bytes() {
            final int n = (int) varint();
            if (n < 0 || p + n > b.length) throw new IllegalStateException("truncated bytes");
            final byte[] out = new byte[n];
            System.arraycopy(b, p, out, 0, n);
            p += n;
            return out;
        }
    }

    /**
     * The debug dump §4.8 shape-rule 2 asks for INSTEAD of an index.
     *
     * <p>Built at the same time as the record deliberately: the first time someone asks "which
     * groups are unhealthy?" without this, the answer grows an index, and the index becomes the
     * second source of truth that goes stale.
     */
    public String dump() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("MlsConversationRecord{").append(key())
          .append(" rcsGid=").append(rcsGroupId)
          .append(" peer=").append(peerE164)
          .append("\n  health=").append(MlsHealthStates.name(healthStatus))
          // SAY THE CONSEQUENCE, NOT JUST THE STATE. A reader of this dump has to know
          // §10.8's G1 mask {1,2,4,7,12,14,16,17} by heart to tell "this conversation is
          // mid-transition" from "this conversation is receiving nothing", and the two read
          // identically. EraAdvancementRequested(7) is the state that made that matter: an operator
          // sees parked messages and a health string with no stated connection between them.
          .append(MlsHealthPredicates.buffersInbound(healthStatus) ? " INBOUND-PARKED(G1)" : "")
          .append(" selfHealKind=").append(selfHealKind)
          .append(" moment=").append(moment)
          .append(" lastHealthy=").append(lastHealthyMoment)
          .append(" recoveredAt=").append(recoveredAt)
          .append("\n  pending=").append(pendingOperation)
          .append(" ").append(selfHealBudget)
          .append("\n  statusRequest=").append(storedStatusRequest)
          .append(" expectedCommit=").append(expectedCommitKind)
          .append(" sendsThisEpoch=").append(sendsThisEpoch)
          .append(" sendsSinceLeafRotation=").append(sendsSinceLeafRotation)
          .append(selfLeft() ? " WE-LEFT@" + selfLeftAtMs : "")
          .append("\n  epochAuths=").append(epochAuthenticators.size())
          .append(epochAuthEra == ERA_UNKNOWN ? "@era?" : "@era" + epochAuthEra)
          .append(" membershipEras=").append(membershipHistory.size())
          .append(" memberValidity=").append(memberValidity.size())
          .append(" keyUpdates=").append(lastParticipantKeyUpdate.size())
          .append(" ftdResends=").append(ftdResendCounts.size())
          // LENGTH ONLY. The token is a 256-bit group secret and this dump goes to logcat; printing
          // the value would hand it to anything that can read a bugreport.
          .append(" continuityToken=").append(continuityToken.length).append("B")
          .append("}");
        return sb.toString();
    }

    @Override public String toString() {
        return "MlsConversationRecord{" + key() + " " + MlsHealthStates.name(healthStatus)
                + (selfLeft() ? " WE-LEFT" : "") + " " + moment + "}";
    }
}
