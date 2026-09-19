/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Map;
import java.util.List;
/**
 * The data types MlsProviderTransport's decision code passes around, grouped here because several
 * names ({@code Group}, {@code Look}, {@code Op}) only make sense in the transport's vocabulary.
 * The transport imports them by their nested names. See docs/mls/transport-and-port.md.
 */
public final class MlsTransportTypes {
    private MlsTransportTypes() {}

    public static final long ERA_INITIAL = 1L;

        /**
         * Per-conversation MLS group state. {@code rcsGroupId} is the RCS group this MLS group
         * backs, or null for a 1:1; it distinguishes two groups with the same peer.
         */
        public static final class Group {
            public byte[] groupId;
            public String peerE164;
            public String rcsGroupId;
            public long era = ERA_INITIAL;
            public byte[] epochAuth;
            /**
             * App messages sent since the last epoch change, ours or a peer's: the
             * application-ratchet generation, which a new epoch resets to 0 ({@code max_skip}).
             */
            public int sendsThisEpoch;
            /**
             * App messages sent since our own leaf key last rotated: the rekey-threshold input,
             * standing in for the leaf's {@code encryption_key_usage_level}. Reset only where our
             * leaf rotates: a rekey, a remove or a GroupContext-extensions commit (mls-rs includes
             * an UpdatePath), not an add-only commit and never a peer's commit.
             */
            public int sendsSinceLeafRotation;
        }

        /**
         * One charged look at the server. A refusal is not an absence: {@link #orNull()} is
         * meaningful only when {@link #refused()} is false, and every refusable arm must say which
         * happened.
         */
        public static final class Look<T> {
            private final T mValue;
            private final boolean mRefused;
            private final String mWhy;

            private Look(final T value, final boolean refused, final String why) {
                mValue = value;
                mRefused = refused;
                mWhy = why;
            }

            public static <T> Look<T> asked(final T value) {
                return new Look<>(value, false, null);
            }

            public static <T> Look<T> refusedByLedger(final String why) {
                return new Look<>(null, true, why);
            }

            /** True when the ledger refused and nothing was asked of the server. */
            public boolean refused() {
                return mRefused;
            }

            /**
             * The ledger's words for the refusal, for the caller's log line; null when not refused.
             */
            public String why() {
                return mWhy;
            }

            /** The server's answer; always null when {@link #refused()}. */
            public T orNull() {
                return mValue;
            }
        }

        /**
         * Where the group was when we yielded an era advance to a peer, and how many looks since. A
         * moment, not a timestamp; see {@link MlsRecoveryPolicy#eraYieldSatisfied}.
         */
        public static final class EraYield {
            public final MlsAppMessage.Moment at;
            public final int observations;
            /**
             * {@link ConvState#heardSeq} when this yield began, so
             * {@link MlsAdvancerElection#classify} can tell a member heard from during the yield
             * from one gone quiet.
             */
            public final long heardSeqAtStart;
            /**
             * The members elected from when this yield began. A stable electorate keeps members
             * from electing different advancers mid-episode, and holding it makes a re-look free:
             * no fetch, no self-heal charge.
             */
            public final java.util.List<String> electorate;
            public EraYield(final MlsAppMessage.Moment at, final int observations,
                    final long heardSeqAtStart, final java.util.List<String> electorate) {
                this.at = at; this.observations = observations; this.heardSeqAtStart =
                        heardSeqAtStart;
                this.electorate = electorate;
            }
        }

        /**
         * An RCC.16 §10.3 resend held at a closed send-gate. Carries the framed body, since a
         * positive receipt arriving before the flush releases the body from {@code mPendingBodies}.
         */
        public static final class DeferredResend {
            public final String rcsGroupId;
            public final String peerE164;
            public final String originalMessageId;
            public final String reportedMessageId;
            public final byte[] framedBody;

            public DeferredResend(final String rcsGroupId, final String peerE164,
                    final String originalMessageId, final String reportedMessageId,
                    final byte[] framedBody) {
                this.rcsGroupId = rcsGroupId;
                this.peerE164 = peerE164;
                this.originalMessageId = originalMessageId;
                this.reportedMessageId = reportedMessageId;
                this.framedBody = framedBody;
            }
        }

        /**
         * Every in-memory per-conversation field under one key, so teardown is one removal and
         * there is one monitor per conversation. That monitor is a leaf lock: nothing inside
         * {@code synchronized (state)} takes another lock. Everything here is work in flight and
         * resets on process restart; anything that must survive a restart belongs in
         * {@link MlsConversationRecord}.
         */
        public static final class ConvState {
            /**
             * RCC.16 §7.7.2.2 reports queued for flush, as {@code messageId -> sender E.164}. The
             * sender is kept per message because a group's state holds every member's failures and
             * each report must go to the member who sent that message.
             */
            public final java.util.LinkedHashMap<String, String> ftdPending =
                    new java.util.LinkedHashMap<>();
            /** Resends parked until this conversation's send-gate opens. */
            public final java.util.ArrayDeque<DeferredResend> gatedResends =
                    new java.util.ArrayDeque<>();
            /** The re-entrancy marker ({@link MlsSchedulingType}); null means normal scheduling. */
            public MlsSchedulingType scheduling;
            /**
             * When the send-gate closed, as {@code SystemClock.elapsedRealtime()} floored at 1; 0
             * means no gate. Monotonic, so a wall clock stepping backwards cannot stop the gate
             * deadline from firing.
             */
            public long gateOpenedAt;
            /** The bounded era yield, or null if we are not yielding. */
            public EraYield eraYield;
            /** An encrypted group subject whose RCC.16 §7.8.1 key has not arrived yet. */
            public byte[] pendingSubject;
            /**
             * A downloaded encrypted group icon whose RCC.16 §7.8.1 key has not arrived yet,
             * bounded by bytes across all conversations
             * ({@link MlsMetadataKeysPolicy#MAX_PENDING_ICON_BYTES}).
             */
            public byte[] pendingIcon;
            /**
             * The {@code notAfter} of the certificate we last tried to carry into this group with
             * an RCC.16 §9.5.3 Self-Update, or 0, so the same certificate is not re-offered after a
             * refusal while a new mint is. In memory: losing it costs one extra attempt, where a
             * wrong persisted value would suppress the repair.
             */
            public long credentialUpdateAttemptedFor;
            /**
             * The group moment at which {@link #credentialUpdateAttemptedFor} was refused, or
             * {@code null} when the refusal was about the bytes. A refusal of our position
             * ({@code VERDICT_ERA_GAP}, {@code VERDICT_GROUP_ID_CHANGED}) seals the attempt only
             * while we stay at that moment, so losing a commit race does not disable the update.
             * Read together with {@link #credentialUpdateAttemptedFor}: null is also the value when
             * nothing was attempted. See docs/mls/credentials.md.
             */
            public MlsAppMessage.Moment credentialUpdateRefusedAt;
            /**
             * The {@code RcsMlsControlResult.VERDICT_*} for the last control commit we sent, or
             * {@code -1} if none; tells "refused" from "never asked". Cleared at the top of every
             * commit.
             */
            public int lastControlVerdict = -1;
            /**
             * The {@code detail} string that came with {@link #lastControlVerdict}, or
             * {@code null}; decides whether a refusal was about our own credential (see
             * {@link MlsCredentialUpdateSeal#judgedOurOwnCredential}). Written and cleared with the
             * verdict.
             */
            public String lastControlDetail = null;
            /**
             * The epoch of a commit held because its outcome did not say whether the server took
             * it, or {@code -1}. Bounds drift during an outage: the first unacknowledged commit is
             * held and later ones roll back onto it. Cleared by an accepted commit or a rollback.
             * In memory; a restart permits a second hold, and the reconcile each hold enqueues
             * still bounds it.
             */
            public long unacknowledgedCommitEpoch = -1L;
            /**
             * The {@code notAfter} of our certificate when a floor rebuild was last attempted, or
             * {@code 0}. A rebuild can only be tested by claiming a KeyPackage from every member,
             * so it re-arms only on our own re-mint, a proxy for fleet certificates turning over.
             * The debug lever ignores it.
             */
            public long floorRebuildAttemptedFor;
            /**
             * Consecutive RCC.16 §10 recoveries that did not converge; reset by one that does. In
             * memory: it drives toward a repair, while the durable bound is
             * {@link MlsRebuildLimiter}.
             */
            public int consecutiveUnconverged;
            /** The group moment at the last park. Null until something parks. */
            public MlsAppMessage.Moment lastParkGroupMoment;
            /** Consecutive parks observed at {@link #lastParkGroupMoment} without it moving. */
            public int consecutiveParksAtSameMoment;
            /**
             * Whether the stall alert is down as far as this process knows, so healthy drive passes
             * do not cancel it on every pass. Starts false so an alert raised before a restart is
             * still removed.
             */
            public boolean stallAlertDown;
            /**
             * Counter bumped on everything attributable to a member: the advancer-liveness ledger.
             * A sequence, not a clock, so a sleeping device's yield does not expire.
             */
            public long heardSeq;
            /**
             * Member E.164 to the {@link #heardSeq} we last heard from them. An absent key means
             * never heard; an empty map means we know nothing (see
             * {@link MlsAdvancerElection.Presence#UNKNOWN}). In memory: a restart makes us more
             * patient, never less. Capped at 256 entries since keys come off the wire; an evicted
             * member reads as never heard.
             */
            public final java.util.LinkedHashMap<String, Long> heardAtSeq =
                    new java.util.LinkedHashMap<String, Long>(16, 0.75f, false) {
                        @Override protected boolean removeEldestEntry(
                                final java.util.Map.Entry<String, Long> eldest) {
                            return size() > 256;
                        }
                    };
            /**
             * Message id to the group moment an application decrypt of it failed at, taken once
             * by {@link MlsInboundHold#parkFutureCiphertext}: a commit can land between the failure
             * and the park decision, so the decision compares against the moment the decrypt saw.
             * Capped at 64 entries since keys come off the wire; an evicted id falls back to the
             * group's current moment.
             */
            public final java.util.LinkedHashMap<String, MlsAppMessage.Moment> decryptFailedAt =
                    new java.util.LinkedHashMap<String, MlsAppMessage.Moment>(16, 0.75f, false) {
                        @Override protected boolean removeEldestEntry(
                                final java.util.Map.Entry<String, MlsAppMessage.Moment> eldest) {
                            return size() > 64;
                        }
                    };
        }

        /**
         * How a conversation's local MLS state compares to the server's. {@code DIVERGED}: era and
         * epoch match but the epoch authenticator does not, i.e. two different groups at the same
         * position. See docs/mls/health-and-recovery.md.
         */
        public enum Health {
            IN_SYNC, AHEAD, ERA_GAP, REJOIN, NOT_FOUND, DIVERGED, UNKNOWN,
            /**
             * Era and epoch match the server, but the ledger refused the authenticator look, so the
             * verdict rests on numbers alone. Never used to tell a person their retry worked; does
             * not demote to a mismatch either.
             */
            IN_SYNC_UNVERIFIED,
            /**
             * Our epoch is lower than the server's, and whether we are on the server's chain cannot
             * be determined: the server serves only its current anchor and no commit backfill, and
             * comparing authenticators across different epochs always differs. Treated as behind by
             * every consumer; classifying it as a fork would rebuild groups that were a commit away
             * from fine.
             */
            LOWER_EPOCH_CHAIN_UNKNOWN,
            /**
             * Our own {@link MlsFetchLedger} refused the look, so nothing was asked. Kept apart
             * from {@link #UNKNOWN} (asked, answer unusable): same patience, different diagnosis.
             */
            LOOK_REFUSED
        }

        /**
         * A {@link Health} verdict and the identity answer behind it. {@link Health#IN_SYNC} can
         * mean the authenticator matched, could not be read, or was not asked; only the first is a
         * positive statement that this is the server's group, which is what writing the server's
         * roster onto the local group requires.
         */
        public static final class ServerComparison {
            public final Health health;

            /** The four-valued identity answer, or null when this arm did not ask. */
            private final MlsWelcomeAdmission.ServerState mIdentity;

            /**
             * The ahead arm's chain verdict, or null on every other arm. Separate from the identity
             * answer so {@link #serverConfirmedOurs} keeps meaning "confirmed at our position".
             */
            private final MlsAheadChainCheck.Verdict mChain;

            private ServerComparison(final Health health,
                    final MlsWelcomeAdmission.ServerState identity,
                    final MlsAheadChainCheck.Verdict chain) {
                this.health = health;
                this.mIdentity = identity;
                this.mChain = chain;
            }

            /** An arm that asked the authenticator question and got one of its four answers. */
            public static ServerComparison asked(final Health health,
                    final MlsWelcomeAdmission.ServerState identity) {
                return new ServerComparison(health, identity, null);
            }

            /**
             * The ahead arm with its chain verdict. The health is AHEAD for every verdict except
             * {@code DIFFERENT_CHAIN}, which demotes to DIVERGED.
             */
            public static ServerComparison chainTested(final Health health,
                    final MlsAheadChainCheck.Verdict chain) {
                return new ServerComparison(health, null, chain);
            }

            /**
             * An arm where the question was not asked: not decisive there, or nothing to ask. Not
             * {@code UNKNOWN}, which means asked and unreadable.
             */
            public static ServerComparison notAsked(final Health health) {
                return new ServerComparison(health, null, null);
            }

            /**
             * Whether we read the server's epoch authenticator and found it to be ours: the one
             * positive.
             */
            public boolean serverConfirmedOurs() {
                return mIdentity == MlsWelcomeAdmission.ServerState.MATCHES;
            }

            /** Whether the authenticator question was put to the server at all on this arm. */
            public boolean identityAsked() {
                return mIdentity != null;
            }

            /**
             * Whether our own ledger stopped the question: a temporary, bounded reason (one
             * {@code MlsFetchLedger.WINDOW_MS}), so a caller can defer rather than act blind.
             */
            public boolean identityRefusedByLedger() {
                return mIdentity == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER;
            }

            /** For a log line: the answer, or why there is none. */
            public String identityLine() {
                // The chain verdict first: on the ahead arm it is the only answer.
                if (mChain != null) return "CHAIN " + mChain.name();
                return mIdentity == null ? "NOT ASKED (not decisive at this position)"
                        : mIdentity.name();
            }
        }

        /**
         * What {@code quarantineIfAheadOfServer} learned: the server's era and epoch (the identity
         * test is decisive only where both are equal, so the caller needs the epoch too) and
         * whether it dropped the local group, after which nothing may use it.
         */
        public static final class EraReconcile {
            /** The server's {@code [era, epoch]}, or null when we did not learn it. */
            public final long[] serverEraEpoch;
            /**
             * True when this call dropped the local group. Nothing downstream may keep using it.
             */
            public final boolean quarantined;

            private EraReconcile(final long[] serverEraEpoch, final boolean quarantined) {
                this.serverEraEpoch = serverEraEpoch;
                this.quarantined = quarantined;
            }

            /** We did not learn the server's position: no group, a refused look, or a throw. */
            public static EraReconcile unknown() {
                return new EraReconcile(null, false);
            }

            /** We read the server's position and left the group alone. */
            public static EraReconcile server(final long[] pair) {
                return new EraReconcile(pair, false);
            }

            /** We read the server's position and dropped the group. */
            public static EraReconcile dropped(final long[] pair) {
                return new EraReconcile(pair, true);
            }
        }

        /**
         * One charged claim against a peer's pool. Refused is not empty: {@link #orNull()} is
         * meaningful only when neither {@link #refused()} nor {@link #notAttempted()}, and neither
         * of those is evidence that the peer has no key packages.
         */
        public static final class Claim<T> {
            private final T mValue;
            private final boolean mRefused;
            private final boolean mNotAttempted;
            private final String mWhy;
            private final MlsClaimLedger.Attribution mAttribution;

            private Claim(final T value, final boolean refused, final boolean notAttempted,
                    final String why, final MlsClaimLedger.Attribution attribution) {
                mValue = value;
                mRefused = refused;
                mNotAttempted = notAttempted;
                mWhy = why;
                mAttribution = attribution == null ? MlsClaimLedger.Attribution.UNKNOWN
                        : attribution;
            }

            public static <T> Claim<T> asked(final T value) {
                return asked(value, MlsClaimLedger.Attribution.UNKNOWN);
            }

            /** A claim that was made, with the provider's attribution of an empty answer. */
            public static <T> Claim<T> asked(final T value,
                    final MlsClaimLedger.Attribution attribution) {
                return new Claim<>(value, false, false, null, attribution);
            }

            public static <T> Claim<T> refusedByLedger(final String why) {
                return new Claim<>(null, true, false, why, MlsClaimLedger.Attribution.UNKNOWN);
            }

            /**
             * A claim the provider never sent ({@code OUTCOME_NOT_ATTEMPTED}). {@link #refused()}
             * stays false: that means our ledger declined, a different fact.
             */
            public static <T> Claim<T> notAttempted(final MlsClaimLedger.Attribution attribution,
                    final String why) {
                return new Claim<>(null, false, true, why, attribution);
            }

            /**
             * Who an empty answer is evidence about; {@link MlsClaimLedger.Attribution#UNKNOWN} on
             * a refusal and for callers that did not request an outcome.
             */
            public MlsClaimLedger.Attribution attribution() {
                return mAttribution;
            }

            /** True when the ledger refused and nothing was claimed from the peer. */
            public boolean refused() {
                return mRefused;
            }

            /**
             * True when the provider never sent the claim (not bound, or too old to say). Nothing
             * left the peer's pool and the ledger charge was refunded.
             */
            public boolean notAttempted() {
                return mNotAttempted;
            }

            /** The explanation for the log; null only when the claim was actually sent. */
            public String why() {
                return mWhy;
            }

            /** The KDS's answer; always null when {@link #refused()} or {@link #notAttempted()}. */
            public T orNull() {
                return mValue;
            }
        }

        /**
         * What the claim supplier learned, filled in as a side effect. An out-parameter so the
         * claim accounting structure pinned by {@code MlsKeyPackageClaimLedgerGuardTest} does not
         * change. Fields start at the answer that asserts nothing; the supplier writes only what it
         * knows.
         */
        public static final class ClaimOutcomeSink {
            /** Who an empty answer is evidence about. */
            public MlsClaimLedger.Attribution attribution = MlsClaimLedger.Attribution.UNKNOWN;
            /** The provider's human diagnostic. For the log only; never parsed. */
            public String detail;
            /**
             * The provider never sent the claim ({@code OUTCOME_NOT_ATTEMPTED}): the one outcome
             * asserting no dial was spent.
             */
            public boolean notAttempted;
        }

        /**
         * A piggybacked self-key-update whose network call is deferred until the conversation lock
         * is released, since a provider round trip must not run under it. The cached era and epoch
         * follow the engine immediately (the engine applied the update locally); only the send is
         * deferred. If the server refuses, the snapshot and cached values are restored together.
         */
        public static final class PendingKeyUpdate {
            public final byte[] commit;
            public final byte[] rollback;        // pre-commit engine snapshot; null if none
            public final byte[] groupId;
            public final String conversationKey;
            public final String peerE164;
            public final String rcsGroupId;
            public final byte[] baseEpochAuth;   // the epoch authenticator the commit is based on
            public final long prevEra;           // cached values to restore if the server refuses
            public final byte[] prevEpochAuth;
            public final int prevSendsThisEpoch;
            public final int prevSendsSinceLeafRotation;

            public PendingKeyUpdate(final byte[] commit, final byte[] rollback,
                    final byte[] groupId,
                    final String conversationKey, final String peerE164, final String rcsGroupId,
                    final byte[] baseEpochAuth, final long prevEra, final byte[] prevEpochAuth,
                    final int prevSendsThisEpoch, final int prevSendsSinceLeafRotation) {
                this.commit = commit;
                this.rollback = rollback;
                this.groupId = groupId;
                this.conversationKey = conversationKey;
                this.peerE164 = peerE164;
                this.rcsGroupId = rcsGroupId;
                this.baseEpochAuth = baseEpochAuth;
                this.prevEra = prevEra;
                this.prevEpochAuth = prevEpochAuth;
                this.prevSendsThisEpoch = prevSendsThisEpoch;
                this.prevSendsSinceLeafRotation = prevSendsSinceLeafRotation;
            }
        }

        /** Which group-changing operation {@code commitAndSend} should build. */
        public enum Op { REKEY, ADD, REMOVE, LEAVE }

        /**
         * What the host held for a conversation before an adoption: whether each durable row
         * existed, so a rollback removes only rows the adoption created and never restores a stale
         * value.
         */
        public static final class MlsAdoptionUndo {
            /** Whether an {@link MlsConversationRecord} already existed for this MLS group id. */
            public final boolean hadRecord;
            /** Whether a conversation-to-group alias already existed for this canonical key. */
            public final boolean hadAlias;

            public MlsAdoptionUndo(final boolean hadRecord, final boolean hadAlias) {
                this.hadRecord = hadRecord;
                this.hadAlias = hadAlias;
            }
        }

        /**
         * What {@code claimRosterForAdvance} decided: the packages, or the failure code to return
         * (such as {@code ERA_ADVANCE_ROSTER_NOT_READY}, which a bare {@code -1} could not carry).
         */
        public static final class RosterClaim {
            /**
             * The claimed packages in member order, or {@code null} when the advance must not
             * proceed.
             */
            public final java.util.List<byte[]> kps;
            /** What to return when {@link #kps} is null. */
            public final int failure;

            private RosterClaim(final java.util.List<byte[]> kps, final int failure) {
                this.kps = kps;
                this.failure = failure;
            }

            public static RosterClaim of(final java.util.List<byte[]> kps) {
                return new RosterClaim(kps, 0);
            }

            public static RosterClaim refused(final int failure) {
                return new RosterClaim(null, failure);
            }
        }

        /** The outcome of validating a peer's signed IMDN. */
        public static final class ImdnCheck {
            /** The signature verified against a group member's leaf. */
            public final boolean signatureValid;
            /** The signed statement matches the IMDN we actually received. */
            public final boolean contentMatches;
            public final int leafIndex;
            public ImdnCheck(boolean v, boolean m, int l) {
                signatureValid = v; contentMatches = m; leafIndex = l;
            }
            /** Only both together mean "this peer really said this about this message". */
            public boolean ok() { return signatureValid && contentMatches; }
            @Override public String toString() {
                return "ImdnCheck{sig=" + signatureValid + " content=" + contentMatches
                        + " leaf=" + leafIndex + "}";
            }
        }

        /** What {@code probeAnchor} did. */
        public enum AnchorProbe {
            /** The probe reached the server; the anchor comparison is in the log. */
            PROBED,
            /** No session, no key or no local group: nothing to compare. */
            NO_LOCAL_STATE,
            /** Our ledger refused the look; nothing was asked. */
            REFUSED_BY_LEDGER
        }

        /**
         * A server pack, or which of the no-pack situations applied ({@link MlsServerPackOutcome}),
         * so the absences a rebuild can hit are never read as each other.
         */
        public static final class ServerPack {
            private final byte[] mBytes;
            private final MlsServerPackOutcome mOutcome;

            private ServerPack(final byte[] bytes, final MlsServerPackOutcome outcome) {
                mBytes = bytes;
                mOutcome = outcome;
            }

            public static ServerPack of(final byte[] bytes) {
                return new ServerPack(bytes, MlsServerPackOutcome.FETCHED);
            }

            public static ServerPack none(final MlsServerPackOutcome why) {
                return new ServerPack(null, why);
            }

            /** The pack; null for every outcome except {@link MlsServerPackOutcome#FETCHED}. */
            public byte[] bytes() {
                return mBytes;
            }

            /** Which situation this is. Never null. */
            public MlsServerPackOutcome outcome() {
                return mOutcome;
            }
        }

        /** One server primitive, handed to the fetch ledger by its wrappers. */
        public interface ServerLook<T> {
            T look();
        }

        /** One claim, handed to the claim ledger by its wrappers. */
        public interface PeerClaim<T> {
            T claim();
        }

        /**
         * What {@code changeGroupMembership} did. {@link #PLAINTEXT} is neither success nor
         * failure: the conversation has no MLS state and the caller should make the bare provider
         * RPC.
         */
        public enum GroupMembershipRouting {
            /** No MLS state for this conversation: use the bare provider RPC. */
            PLAINTEXT,
            /** The commit and the RCS roster change went out together and were accepted. */
            APPLIED,
            /** The change did not happen: a guard, no KeyPackage, or a refusal. */
            REFUSED,
        }

        /**
         * Which plane a group conversation is on, as {@code groupPlane} answers. Holding an MLS
         * group and showing a padlock on a group we hold nothing for are different states with
         * different remedies.
         */
        public enum GroupPlane {
            /** No MLS state and no encryption bit: the bare RCS RPC is right. */
            PLAINTEXT,
            /** We hold an MLS group for it now, so a commit can be built. */
            MLS,
            /**
             * We hold a group but it carries {@code end_mls} (RCC.16 §9.1.1): plaintext now, though
             * the engine state survives because {@code downgradeLocally} clears the encryption bit
             * without dropping the group.
             */
            MLS_DOWNGRADED,
            /**
             * The encryption bit is set but we hold no group for the conversation: a rejoin never
             * completed.
             */
            MLS_LOCKED_OUT,
            /** An MLS identity exists but the engine would not open a session: cannot tell. */
            UNKNOWN,
        }

        /** The MLS header sidecar for one outbound receipt, all from one group state. */
        public static final class ImdnStamps {
            public final int subId;
            public final long eraId;
            public final String epochAuthB64;
            /**
             * Base64 {@code rcs_signature} over the RCC.16 §7.6 signed content; null if we could
             * not sign.
             */
            public final String signatureB64;
            /**
             * The id this receipt carries on the wire, minted app-side because the signature covers
             * it and both must name the same value.
             */
            public final String receiptMessageId;
            public ImdnStamps(final int subId, final long eraId, final String epochAuthB64,
                    final String signatureB64, final String receiptMessageId) {
                this.subId = subId; this.eraId = eraId;
                this.epochAuthB64 = epochAuthB64; this.signatureB64 = signatureB64;
                this.receiptMessageId = receiptMessageId;
            }
        }
}
