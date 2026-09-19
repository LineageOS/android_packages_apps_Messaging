/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.io.Closeable;

/**
 * Per-identity engine handle: the RFC 9420 operations, backend-neutral. Byte blobs are wire-format
 * MLSMessages (KeyPackage, Welcome, Commit, application ciphertext) that the provider carries
 * unchanged. Defaults answer "not supported" for backends without an operation.
 * See docs/mls/overview.md.
 */
public interface MlsSession extends Closeable {
    /** Generate {@code count} RFC 9420 KeyPackages, as the serialized pool for upload. */
    byte[] generateKeyPackages(int count);

    /**
     * Generate the last-resort KeyPackage (carries the {@code last_resort} extension), or
     * {@code null} where the backend has no last-resort concept.
     */
    byte[] generateLastResortKeyPackage();

    /** Create a 1:1 group at {@code era} and add the peer's KeyPackage. */
    MlsGroupArtifacts createGroup(long era, byte[] peerKeyPackage);

    /**
     * Like {@link #createGroup} but uses {@code groupIdOverride} as the MLS group_id, so an era
     * advance keeps the server's RCS group id. The default ignores the override.
     */
    default MlsGroupArtifacts createGroupWithId(long era, byte[] peerKeyPackage,
            byte[] groupIdOverride) {
        return createGroup(era, peerKeyPackage);
    }

    /** Join from a serialized Welcome. Returns the group id. */
    byte[] join(byte[] welcome);

    /** Encrypt an application message to serialized MLSMessage ciphertext. */
    byte[] encrypt(byte[] groupId, byte[] plaintext);

    /**
     * Stage an RCC.16 §10.3 resent-message component for the next AuthenticatedData the engine
     * builds, in place of the absent {@code 0x00}. One-shot; {@code null} clears it. The AAD around
     * it stays the engine's to build.
     */
    default void setNextResentComponent(byte[] component) { }

    /**
     * RCC.16 §7.5.3.1: the message id the next process call is for, compared against the inbound
     * AAD. It stays armed until cleared; {@code null} clears it, which skips the check rather than
     * failing it, and an empty id refuses an AAD that names one. See {@link MlsEngineIdCheck}.
     */
    default void setRequestMessageId(byte[] messageId) { }

    /** Whether the last processed application message's AAD id did not match. */
    default boolean lastMessageIdMismatch() { return false; }

    /** {@code expected\0actual} behind {@link #lastMessageIdMismatch}, or null when none. */
    default byte[] lastMessageIdMismatchDetail() { return null; }

    /**
     * Encrypt with an explicit {@code authenticated_data} (RCC.16 §7.5.3). It feeds the content
     * AEAD, so it must match what the peer binds. The default ignores {@code aad}.
     */
    default byte[] encryptWithAad(byte[] groupId, byte[] plaintext, byte[] aad) {
        return encrypt(groupId, plaintext);
    }

    /**
     * The application generation the next encrypt will stamp for our own leaf (peek, no advance),
     * or -1. The RCC.16 body header's uint32 must carry the same value; peers reject a message
     * whose {@code sender_data.generation} disagrees with the framed counter.
     */
    default int nextAppGen(byte[] groupId) { return -1; }

    /**
     * The 32-byte epoch_authenticator of the group's current epoch, for the Epoch-Authenticator
     * MIME part; {@code null} where the backend does not expose it.
     */
    default byte[] epochAuth(byte[] groupId) { return null; }

    /**
     * {@code [era u32 BE][epoch u64 BE]} of the group's current state: the era (extension 0xF001,
     * default 1) for the {@code Era-ID} MIME part and the RFC 9420 epoch. {@code null} where the
     * backend does not expose it.
     */
    default byte[] eraEpoch(byte[] groupId) { return null; }

    /** Process an inbound serialized MLSMessage to application plaintext, or {@code null}. */
    byte[] process(byte[] groupId, byte[] wire);

    /**
     * The result of {@link #externalCommitResync}. The post-commit ratchet tree travels as a
     * separate field, as for a create: the server refuses it both embedded in the GroupInfo and
     * absent. {@code groupId} keys local storage.
     */
    final class ExternalCommit {
        public final byte[] groupId;
        public final byte[] commit;
        public final byte[] groupInfo;
        public final byte[] epochAuth;
        public final byte[] ratchetTree;
        public ExternalCommit(final byte[] groupId, final byte[] commit,
                final byte[] groupInfo, final byte[] epochAuth, final byte[] ratchetTree) {
            this.groupId = groupId; this.commit = commit;
            this.groupInfo = groupInfo; this.epochAuth = epochAuth; this.ratchetTree = ratchetTree;
        }
    }

    /**
     * A status-tagged {@link #processEx} result. {@code status}: 0 app, 1 commit (applied, epoch
     * advanced), 2 proposal, 3 other, 7 malformed (not an MLSMessage; drop), 8 apply-failed
     * (current or future epoch, not yet applicable; buffer), 9 past-epoch (superseded commit;
     * drop). {@code payload} is the plaintext for an app message, else empty.
     */
    final class ProcResult {
        public final int status;
        public final byte[] payload;
        /**
         * For a proposal, its RFC 9420 proposal type; {@code -1} when unknown. {@code self_remove}
         * is implemented natively, while RCC.16 custom types reach the engine as opaque
         * CustomProposals whose commit changes no state, so the type decides whether a proposal can
         * be honoured.
         */
        public final int proposalType;
        public ProcResult(final int status, final byte[] payload) {
            this(status, payload, -1);
        }
        public ProcResult(final int status, final byte[] payload, final int proposalType) {
            this.status = status; this.payload = payload; this.proposalType = proposalType;
        }
    }

    /** RCC.16 §7.11 proposal type code points. */
    int PROP_END_MLS = 0xF001;
    int PROP_RCS_SIGNATURE = 0xF002;
    int PROP_SELF_REMOVE = 0xF003;
    int PROP_SERVER_REMOVE = 0xF004;

    /**
     * Drop every by-reference proposal cached for commit on this group. A cached proposal blocks
     * application messages until committed, so one we cannot honour must be dropped; the whole
     * cache goes, since mls-rs has no per-proposal eviction. Callers log when they do this.
     */
    default boolean clearPendingProposals(byte[] groupId) { return false; }

    /**
     * Propose our own removal as a by-reference {@code SelfRemoveProposal}, which the peer or
     * server acts on. The proposal sits in the commit slot of the artifacts and does not advance
     * the epoch. Used to empty a 1:1 group so the server can collect it.
     */
    default MlsGroupArtifacts selfLeave(byte[] groupId, byte[] aad) { return null; }

    /**
     * RCC.16 §9.7.1.4 and RCC.16 §9.7.1.5 metadata commit: the icon and subject keys and their
     * commitments. The commitment is a RefHash over the key material, binding it to the content
     * encrypted with that key. Empty slices leave a field unchanged.
     */
    default MlsGroupArtifacts commitGroupMetadata(byte[] groupId, byte[] aad, byte[] iconKey,
            byte[] iconCommitment, byte[] subjectKey, byte[] subjectCommitment) {
        return null;
    }

    /**
     * Whether a peer KeyPackage carries the RFC 9420 {@code last_resort} extension (0x000A). A
     * member joined on a reusable leaf self-updates on every commit until it holds a one-time leaf,
     * so establish prefers a one-time package.
     */
    default boolean kpIsLastResort(byte[] keyPackage) { return false; }

    /**
     * What an engine op's result meant beyond bytes or null: a failure, a missing group and a
     * correct no-op want different remedies, and a bounded drive loop needs to tell them apart.
     */
    enum OpStatus {
        /** The op succeeded. */
        OK,
        /** The op ran and there was nothing to do. A drive loop stops rather than retrying. */
        NO_OP,
        /** The group is not in local storage: a join or recover situation, not a retry. */
        NOT_FOUND,
        /** A real failure. Retryable at the caller's discretion. */
        ERR;

        /**
         * Decode an engine status code. Throws on an unknown code: it means the engine and this
         * decoder disagree about the contract, and any default would be wrong.
         */
        static OpStatus fromCode(final int code) {
            switch (code) {
                case 0: return OK;
                case 1: return NO_OP;
                case 2: return NOT_FOUND;
                case 3: return ERR;
                default:
                    throw new IllegalStateException(
                            "unknown engine op status " + code + " — the native engine and this "
                            + "decoder disagree about the contract; do not guess a default");
            }
        }
    }

    /**
     * The status of this session's most recent op on the calling thread, read immediately after it.
     * Backends with no status channel report {@link OpStatus#OK}.
     */
    default OpStatus lastStatus() { return OpStatus.OK; }

    /**
     * The largest group-state record written on the calling thread since the last read, in bytes,
     * or {@code 0}. Reading clears it. Recorded as {@link MlsMetrics#ZINNIA_STATE_SIZE} through
     * {@link MlsMetrics#log2Bucket}.
     */
    default long takeStateWriteBytes() { return 0L; }

    /**
     * Every leaf's credential validity window, {@code leafIndex -> [notBefore, notAfter]} in epoch
     * seconds (both 0 for a leaf whose credential will not parse), or {@code null} when unreadable.
     */
    default java.util.Map<Integer, long[]> memberValidity(byte[] groupId) { return null; }

    /**
     * Every leaf's participant key, by leaf index, or {@code null}. See {@link #memberValidity}.
     */
    default java.util.Map<Integer, MlsParticipantKeyResync.Leaf> memberParticipantKeys(
            byte[] groupId) {
        return null;
    }

    /**
     * Our own leaf's status in a group, or {@code null} if un-evaluable. See
     * {@link #memberValidity}.
     */
    default MlsSelfLeafStatus selfLeafStatus(byte[] groupId) { return null; }

    /** The RFC 9420 KeyPackageRef of a serialised KeyPackage, or {@code null}. */
    default byte[] keyPackageRef(byte[] keyPackage) { return null; }

    /** The KeyPackageRefs a Welcome names; empty by default. See {@link #keyPackageRef}. */
    default java.util.List<byte[]> welcomeKeyPackageRefs(byte[] welcome) {
        return java.util.Collections.emptyList();
    }

    /**
     * Every leaf of a serialised ratchet tree with its certificate window; empty means "could not
     * parse".
     */
    default java.util.List<MlsTreeLeaf> treeMemberValidity(byte[] ratchetTree) {
        return java.util.Collections.emptyList();
    }

    /**
     * A GroupInfo's {@code {signerLeafIndex, epoch}}, or {@code null}. See {@link #memberValidity}.
     */
    default long[] groupInfoSigner(byte[] groupInfo) { return null; }

    /**
     * Join from a Welcome that carries no {@code ratchet_tree} extension, splicing the tree from
     * the LeafNodes beside it in {@code blob}. Returns the group id, or {@code null}; the caller
     * then falls back to {@link #join}.
     */
    default byte[] joinTreelessWelcome(byte[] welcome, byte[] blob) { return null; }

    /**
     * The {@code authenticated_data} of the last application message processed on this thread, so
     * the host can enforce RCC.16 §7.5.3.1 (RFC 9420 authenticates the AAD but gives it no
     * meaning). Empty when there was none, the message was not an application message, or it
     * failed.
     */
    default byte[] lastInboundAad() { return new byte[0]; }

    /**
     * The certified MSISDN (the signing leaf's X.509 SAN {@code tel:}) of the sender of the last
     * application message processed on this thread. The transport envelope's sender is chosen by
     * the sender, so attribution must use this. Empty means unknown, never a match. Read directly
     * after the decrypt on the same thread; a non-application message clears it.
     */
    default String lastInboundSenderMsisdn() { return ""; }

    /** What the consume-side gate needs to know about a claimed peer KeyPackage. */
    final class KeyPackageInfo {
        /** The package carries the RFC 9420 {@code last_resort} extension (0x000A): reusable. */
        public final boolean lastResort;
        /**
         * The LeafNode's RFC 9420 §7.2 {@code Lifetime.not_after}, epoch seconds. Not the
         * certificate's expiry: the engine anchors it at the certificate's notBefore plus 365 days,
         * so a remaining-life floor applied to it is meaningless. Use {@link #certNotAfterSecs}.
         */
        public final long notAfterSecs;
        /**
         * The leaf certificate's X.509 {@code notBefore}, or {@code 0} when unread (never
         * "expired").
         */
        public final long certNotBeforeSecs;
        /**
         * The leaf certificate's X.509 {@code notAfter}, or {@code 0} when unread. This is the
         * clock the 30-day remaining-lifetime floor applies to (RCC.16 A.4.3.1).
         */
        public final long certNotAfterSecs;
        /**
         * The MSISDN the leaf certificate asserts (SAN {@code tel:}, bare E.164 digits), or empty.
         * RCC.16 A.4.1 requires it to equal the number queried; the claim site compares.
         */
        public final String msisdn;
        /**
         * The KeyPackage's own {@code cipher_suite} (RFC 9420 §17.1), or {@code 0} when unreported.
         * mls-rs refuses a mismatch at add time; checking here names the failure earlier.
         */
        public final int cipherSuite;
        /**
         * The suites the peer's LeafNode advertises, in wire order; empty means unknown, not
         * unsupported. RFC 9420 §7.2 requires the group's suite in every member's list and mls-rs
         * does not check it.
         */
        public final int[] cipherSuites;
        public KeyPackageInfo(final boolean lastResort, final long notAfterSecs,
                final String msisdn) {
            this(lastResort, notAfterSecs, msisdn, 0, null);
        }
        public KeyPackageInfo(final boolean lastResort, final long notAfterSecs,
                final String msisdn, final int cipherSuite, final int[] cipherSuites) {
            this(lastResort, notAfterSecs, msisdn, cipherSuite, cipherSuites, 0L, 0L);
        }
        public KeyPackageInfo(final boolean lastResort, final long notAfterSecs,
                final String msisdn, final int cipherSuite, final int[] cipherSuites,
                final long certNotBeforeSecs, final long certNotAfterSecs) {
            this.lastResort = lastResort;
            this.notAfterSecs = notAfterSecs;
            this.msisdn = (msisdn == null) ? "" : msisdn;
            this.cipherSuite = cipherSuite;
            this.cipherSuites = (cipherSuites == null) ? new int[0] : cipherSuites;
            this.certNotBeforeSecs = certNotBeforeSecs;
            this.certNotAfterSecs = certNotAfterSecs;
        }

        /**
         * Whether a certificate window was read, so a caller can tell "inside the floor" from "not
         * read".
         */
        public boolean certWindowKnown() {
            return certNotAfterSecs > 0L;
        }

        /** Whole days left on the leaf certificate, or {@link Long#MIN_VALUE} when unreadable. */
        public long certRemainingDays(final long nowSecs) {
            if (!certWindowKnown()) return Long.MIN_VALUE;
            return MlsCredentialFloor.remainingDays(certNotAfterSecs, nowSecs);
        }

        /** Whole days left on the LeafNode {@code Lifetime} at {@code nowSecs}. */
        public long lifetimeRemainingDays(final long nowSecs) {
            return MlsCredentialFloor.remainingDays(notAfterSecs, nowSecs);
        }

        /** Both clocks, labelled, for a log line. Never print one alone. */
        public String clocksText(final long nowSecs) {
            return "leafLifetime=" + lifetimeRemainingDays(nowSecs) + "d(not_after="
                    + notAfterSecs + ") cert=" + (certWindowKnown()
                            ? certRemainingDays(nowSecs) + "d(notAfter=" + certNotAfterSecs + ")"
                            : "UNREADABLE");
        }

        /**
         * Whether the peer's leaf advertises {@code suite}; {@code false} for an empty
         * advertisement, so test {@link #suitesKnown()} first to tell "does not support" from "did
         * not say".
         */
        public boolean advertisesSuite(final int suite) {
            for (final int s : cipherSuites) {
                if (s == suite) {
                    return true;
                }
            }
            return false;
        }

        /** Whether the peer said anything at all about its suites. */
        public boolean suitesKnown() {
            return cipherSuites.length > 0;
        }

        /** The advertised set as {@code 0xNNNN} text, for a log line. */
        public String suitesText() {
            if (cipherSuites.length == 0) {
                return "(none advertised)";
            }
            final StringBuilder b = new StringBuilder();
            for (final int s : cipherSuites) {
                b.append(b.length() == 0 ? "" : ",").append(String.format("0x%04X", s));
            }
            return b.toString();
        }
    }


    /**
     * Inspect a claimed peer KeyPackage: its two expiry clocks, identity, suites and last-resort
     * flag, for the RCC.16 A.4.1.2 and A.4.2.2 30-day floor. {@code null} when it will not parse or
     * has no {@code key_package} leaf source, so an undatable package fails the floor.
     */
    default KeyPackageInfo inspectKeyPackage(byte[] keyPackage) { return null; }

    /**
     * Add a member to an existing group.
     *
     * @param aad the AuthenticatedData bound to the commit; the server refuses an empty one
     */
    default MlsGroupArtifacts addMember(byte[] groupId, byte[] peerKeyPackage, byte[] aad) {
        return null;
    }

    /**
     * Add every KeyPackage in one commit, symmetric with {@link #removeMemberByMsisdn}. N packages
     * are N devices: RFC 9420 forbids duplicate leaf data, and mls-rs refuses it.
     */
    default MlsGroupArtifacts addMembers(byte[] groupId, java.util.List<byte[]> peerKeyPackages,
            byte[] aad) {
        return null;
    }

    /**
     * Remove a member by signature key; an empty key means the sole other member (1:1 only).
     *
     * @param aad as in {@link #addMember}
     */
    default MlsGroupArtifacts removeMember(byte[] groupId, byte[] memberSigPub, byte[] aad) {
        return null;
    }

    /**
     * Remove every leaf certified to one MSISDN in one commit: the correct selector for a group
     * removal, and the only one right for a multi-device participant. {@link #removeMember} with an
     * empty key removes an arbitrary leaf in a group larger than two.
     */
    default MlsGroupArtifacts removeMemberByMsisdn(byte[] groupId, String msisdn, byte[] aad) {
        return null;
    }

    /**
     * Self-update: in-place epoch advance. {@code aad} is the
     * {@code FramedContent.authenticated_data} bound onto the commit, the same
     * {@code AuthenticatedData} struct as on application messages; the server refuses an empty one.
     */
    default MlsGroupArtifacts selfUpdate(byte[] groupId, byte[] aad) { return null; }

    /**
     * {@link #selfUpdate} whose published GroupInfo carries {@code external_pub}, with the
     * post-commit ratchet tree as a separate artifact. Only the committer can add
     * {@code external_pub}, so without it nobody can resync-join. Gated by
     * {@code MlsConfig#KEY_PUBLISH_EXTERNAL_PUB}.
     */
    default MlsGroupArtifacts selfUpdateExtPub(byte[] groupId, byte[] aad) { return null; }

    /**
     * Resync by external commit from the server's GroupInfo with a fresh leaf.
     * {@code removeLeafIndex} {@code >= 0} also removes that stale leaf. Returns the commit to send
     * as a control message, or {@code null}.
     */
    default ExternalCommit externalCommitResync(byte[] serverGroupInfo, byte[] ratchetTree,
            long removeLeafIndex) { return null; }

    /** Delete a group's persisted state. */
    default boolean deleteGroup(byte[] groupId) { return false; }

    /**
     * Capture the group's persisted state before an optimistic commit, so a commit the server
     * refuses can be rolled back with {@link #restoreGroupSnapshot}. Returns the opaque snapshot,
     * or {@code null}.
     */
    default byte[] exportGroupSnapshot(byte[] groupId) { return null; }

    /** Restore a snapshot from {@link #exportGroupSnapshot}. */
    default boolean restoreGroupSnapshot(byte[] groupId, byte[] snapshot) { return false; }

    /**
     * Whether a cached by-reference proposal must be committed before another application message
     * may be encrypted (mls-rs {@code commit_required}). The leaver cannot commit its own
     * {@code self_remove}; a remaining member sweeps it into their next commit.
     */
    default boolean commitRequired(byte[] groupId) { return false; }

    /**
     * Commit the RCC.16 {@code end_mls} GroupContext extension (0xF002, RCC.16 §7.11.2.2):
     * encrypted sending stops (RCC.16 §9.1.1). {@code remove=true} removes it, returning the
     * conversation to encrypted.
     */
    default MlsGroupArtifacts commitEndMls(byte[] groupId, byte[] aad, boolean remove) {
        return null;
    }

    /**
     * Era advance that preserves membership (RCC.16 §8.3): a GroupContextExtensions commit on the
     * existing group. Tree and members survive, so no Welcome is produced and the server's roster
     * matches by construction.
     */
    default MlsGroupArtifacts commitEraAdvance(byte[] groupId, byte[] aad, int newEra) {
        return null;
    }

    /** RCC.16 icon and subject commitment extension types (RCC.16 §7.11.4, RCC.16 §7.11.6). */
    int EXT_ICON_COMMITMENT = 0xF004;
    int EXT_SUBJECT_COMMITMENT = 0xF006;

    /**
     * Commit the icon and subject commitment extensions (RCC.16 Annex C.1 values from
     * {@link RccCommitment}); null or empty leaves one untouched. The key extensions (0xF003,
     * 0xF005) are Welcome-only and not settable: in the GroupContext they would reach the server.
     */
    default MlsGroupArtifacts commitIconSubject(byte[] groupId, byte[] aad,
            byte[] iconCommitment, byte[] subjectCommitment) {
        return null;
    }

    /**
     * Create a group whose initial commit adds every member, so one Welcome covers everyone. The
     * server validates the MLS roster against the full RCS roster.
     *
     * @param peerKeyPackages each member's claimed KeyPackage, in claim order
     * @param groupIdOverride the MLS group id; for an RCS group, the RCS group id
     */
    default MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride) {
        return createGroupMulti(era, peerKeyPackages, groupIdOverride, null);
    }

    /**
     * As above, carrying the RCC.16 metadata extensions (0xF003 to 0xF006) over from a GroupInfo.
     * An era advance needs this: the server refuses one that drops a commitment.
     *
     * @param carryGroupInfo the server's GroupInfo, not ours, which is stale by definition here
     */
    default MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo) {
        return createGroupMulti(era, peerKeyPackages, groupIdOverride, carryGroupInfo,
                MlsAdvanceEraKind.NORMAL);
    }

    /**
     * As above, with the era-advance mode, which decides what happens to {@code end_mls} (0xF002):
     * {@link MlsAdvanceEraKind#NORMAL} carries it, the revival mode drops it, and
     * {@link MlsAdvanceEraKind#PHOENIX_DOWNGRADE} installs it. The mode comes from the caller and
     * must never be derived from group state, so no recovery path removes {@code end_mls} by
     * accident. See docs/mls/downgrade.md.
     */
    default MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, MlsAdvanceEraKind kind) {
        return null;
    }

    /**
     * Build a group operation without naming an era: the entry point every create uses. The engine
     * derives the era from its state and the carried GroupInfo, and reports what it did in
     * {@link MlsGroupArtifacts#welcomeAction} and {@link MlsGroupArtifacts#era}. Route the RPC on
     * the action: {@link MlsWelcomeAction#NEW_MEMBERSHIP_EXISTING_GROUP} is an add at the same era;
     * the three joinable actions are a create; anything else, or null, is refused. See
     * docs/mls/group-lifecycle.md.
     *
     * @param carryGroupInfo the server's GroupInfo when we have one, else null
     */
    default MlsGroupArtifacts createGroupPlanned(java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, MlsAdvanceEraKind kind) {
        return null;
    }

    /** Read an RCC.16 group extension's data, or empty. */
    default byte[] groupExt(byte[] groupId, int extType) { return new byte[0]; }

    /**
     * Which GroupContext extension types a serialized GroupInfo carries, as big-endian
     * {@code [u16 type][u16 length]} records. Parsed rather than byte-scanned, which would
     * false-positive inside key material.
     */
    default byte[] groupInfoExtTypes(byte[] groupInfo) { return new byte[0]; }

    /**
     * RCC.16 §7.11.12: read the continuity token or commitment extension
     * ({@link MlsContinuityCodePoints}) out of a serialized GroupInfo, decoded. Not version-gated.
     *
     * @return the decoded value, or empty when absent
     */
    default byte[] groupInfoContinuity(byte[] groupInfo, int extType) { return new byte[0]; }

    /**
     * RCC.16 §7.11.12.1: take the continuity token a Welcome carried for {@code groupId}; the
     * engine forgets it. Empty is a normal answer (we created the group, joined by external commit,
     * or already took it). The caller persists what it collects.
     */
    default byte[] takeWelcomeContinuityToken(byte[] groupId) { return new byte[0]; }

    /**
     * RCC.16 §7.9.2: a GroupInfo carrying the continuity-token commitment. Under v3.0 this is the
     * ordinary GroupInfo, so callers may invoke it unconditionally.
     */
    default byte[] groupInfoWithContinuity(byte[] groupId, byte[] commitment, boolean withTree) {
        return new byte[0];
    }

    /**
     * One extension's decoded value out of a serialized GroupInfo, or empty when absent. Decoded
     * like {@link #groupExt}: varint-framed types are unframed; the bare ones (0xF001, 0xF002,
     * 0xF007) pass through.
     */
    default byte[] groupInfoExt(byte[] groupInfo, int extType) { return new byte[0]; }

    /**
     * RCC.16 §7.6.2: sign {@code derivedContent} (an RCC.16 §7.6.3 VerifiableDerivedContent) into
     * an {@code rcs_signature} PublicMessage for the {@code MLS-Derived-Content-Signature} header.
     * Never cached, never committed.
     */
    default byte[] rcsSign(byte[] groupId, byte[] derivedContent) { return null; }

    /**
     * RCC.16 §7.6.2: verify an inbound {@code rcs_signature} PublicMessage.
     *
     * @return {@code [u32 BE leaf_index][derivedContent]}, or null if the signature or proposal
     * type did not check out; the caller must check the leaf is the expected member
     */
    default byte[] rcsVerify(byte[] groupId, byte[] publicMessage) { return null; }

    /**
     * Whether the group carries {@code end_mls}, so encrypted sending is forbidden (RCC.16 §9.1.1).
     */
    default boolean endMlsPresent(byte[] groupId) { return false; }

    /** Status-tagged process for the receive path. */
    default ProcResult processEx(byte[] groupId, byte[] wire) { return null; }

    /**
     * The repeated-result form of {@link #processEx}. Each result carries the context it belongs
     * to, so the host demuxes with {@link MlsResultBundle}. An implementing engine never returns
     * empty (a refusal is one malformed result); the default returns empty.
     *
     * @param contextId the caller's context; echoed on every result the engine attributes to it
     */
    default java.util.List<MlsEngineResult> processResults(byte[] groupId, byte[] wire,
            String contextId) {
        return java.util.Collections.emptyList();
    }

    /**
     * The repeated-result form of {@link #encryptWithAad}: the ciphertext first, at the current
     * epoch, then any key-update commit the engine folded in. Callers dispatch the whole list
     * before branching, or the rotation is silently lost. The host decides rotation through
     * {@code wantKeyUpdate}.
     *
     * @return the results, ciphertext first; empty if nothing was produced (no generation consumed)
     */
    default java.util.List<MlsEngineResult> encryptResults(byte[] groupId, byte[] plaintext,
            byte[] aad, String contextId, boolean wantKeyUpdate) {
        return java.util.Collections.emptyList();
    }

    @Override
    void close();
}
