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

import java.io.Closeable;

/**
 * Per-identity engine handle. The RFC 9420 crypto ops, backend-neutral. Byte blobs are wire-format
 * MLSMessages (KeyPackage / Welcome / Commit / application ciphertext), so they ride the shared
 * Tachyon/KDS transport unchanged.
 */
public interface MlsSession extends Closeable {
    /** Generate {@code count} RFC 9420 KeyPackages (serialized pool for UploadKeyPackages). */
    byte[] generateKeyPackages(int count);

    /**
     * Generate the last-resort KeyPackage (carries the {@code last_resort} extension; KDS
     * last-resort slot).
     *
     * <p>The whole native stack already had it
     * ({@code rcs_mls_generate_last_resort_kp} → {@code OpenMlsNative.nativeGenerateLastResortKp});
     * only messaging2's forked copy of this interface declared it, so unifying the two forks required
     * lifting it here. Implementations that have no last-resort concept return {@code null}.
     */
    byte[] generateLastResortKeyPackage();

    /** Create a 1:1 group carrying Era, add the peer KeyPackage. Returns the four artifacts. */
    MlsGroupArtifacts createGroup(long era, byte[] peerKeyPackage);

    /** Like {@link #createGroup} but reuses {@code groupIdOverride} as the MLS group_id (for a REVIVE —
     *  an era-advancement CREATE keeps the server's existing rcs group_id, only the era advances). A
     *  null/empty override falls back to minting a fresh UUID (default → plain {@link #createGroup}). */
    default MlsGroupArtifacts createGroupWithId(long era, byte[] peerKeyPackage, byte[] groupIdOverride) {
        return createGroup(era, peerKeyPackage);
    }

    /** Join from a serialized Welcome. Returns the group id. */
    byte[] join(byte[] welcome);

    /** Encrypt an application message → serialized MLSMessage ciphertext. */
    byte[] encrypt(byte[] groupId, byte[] plaintext);

    /** Encrypt an application message with an explicit MLS {@code authenticated_data} (the RFC-9420
     *  PrivateMessage AAD). Google Messages RCS binds an {@code AuthenticatedData{version, message_id, …}} here
     *  (byte layout {@code 00 01 <len><message_id> 00 00 00 03 00}); it is fed into the content AEAD, so
     *  it MUST match what the peer binds or the AEAD auth-fails (surfacing as KEY_GENERATION_MISMATCH).
     *  Default falls back to empty AAD (plain {@link #encrypt}). */
    /**
     * Stage a §10.3 RESENT-MESSAGE COMPONENT for the next AAD the engine builds, instead of the
     * absent {@code 0x00}. One-shot; {@code null} clears it.
     *
     * <p>Supplying a COMPONENT is not the removed host-builds-the-AAD seam returning:
     * §10.3 makes the component the host's to choose, while the AAD around it stays
     * the engine's to build. Used by the resent-component probe.
     */
    default void setNextResentComponent(byte[] component) { }

    /**
     * §7.5.3.1 — tell the engine which message id the next process call is for, so it can compare
     * against the inbound AAD. {@code null} clears it, which SKIPS the check rather than failing it.
     */
    default void setRequestMessageId(byte[] messageId) { }

    /** Whether the last processed application message's AAD id did not match. */
    default boolean lastMessageIdMismatch() { return false; }

    default byte[] encryptWithAad(byte[] groupId, byte[] plaintext, byte[] aad) {
        return encrypt(groupId, plaintext);
    }

    /** The application generation the next encrypt will stamp for our own leaf (peek, no advance),
     *  or -1 if unavailable. The RCC.16 body header's uint32 must carry this SAME value: Google Messages
     *  increments the framing counter and {@code sender_data.generation} in lockstep (captured
     *  2026-07-25 — 0,1,2 across three messages in one epoch), and it rejects a message
     *  whose stamped generation disagrees with the framed counter. */
    default int nextAppGen(byte[] groupId) { return -1; }

    /** 32-byte epoch_authenticator of the group's CURRENT epoch (RCC.16 §7.11 Epoch-Authenticator),
     *  for the SendMessage {@code fwvc.f12} Epoch-Authenticator MIME part. May return {@code null}
     *  on a backend that doesn't expose it. */
    default byte[] epochAuth(byte[] groupId) { return null; }

    /** 12-byte {@code [era u32 BE][epoch u64 BE]} of the group's CURRENT state — era (RCC.16 Era ext
     *  0xF001, default 1) for the SendMessage {@code Era-ID} MIME part, epoch (RFC-9420 counter) for
     *  the incorrect-epoch-authenticator diagnosis. {@code null} on a backend that doesn't expose it. */
    default byte[] eraEpoch(byte[] groupId) { return null; }

    /** Process an inbound serialized MLSMessage → application plaintext (or {@code null}). */
    byte[] process(byte[] groupId, byte[] wire);

    // ---- S2 group-mutation + resync + status-tagged process (default-null; OpenMLS implements) ----

    /** {group_id, external_commit, groupInfo, epochAuth, ratchetTree} from {@link #externalCommitResync}
     *  — commit + groupInfo + epochAuth + ratchetTree feed the external-commit request (the
     *  post-commit tree rides as a SEPARATE field, as it does for create; the server rejects it
     *  embedded in the
     *  GroupInfo AND rejects it absent); group_id keys local storage. */
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

    /** Status-tagged {@link #processEx} result: {@code status} 0=APP, 1=COMMIT (applied, epoch advanced),
     *  2=PROPOSAL, 3=OTHER, 7=MALFORMED (from_bytes failed — not a valid MLSMessage, DROP), 8=APPLY_FAILED
     *  (valid FUTURE/current-epoch message we can't apply yet, BUFFER for retry), 9=PAST_EPOCH (a commit
     *  whose epoch is BEHIND ours — already superseded, DROP so it doesn't re-spam the flush cap);
     *  {@code payload} = the decrypted plaintext for APP, else empty. */
    final class ProcResult {
        public final int status;
        public final byte[] payload;
        /**
         * For {@code status == 2}, the RFC 9420 proposal type of the cached proposal; {@code -1} when
         * unknown or not applicable.
         *
         * <p>The type is what tells an honourable proposal from an unhonourable one. A
         * {@code self_remove} (0xF003) is implemented natively by mls-rs, so sweeping it into a commit
         * really does remove the proposer. An RCC.16 custom type we merely ADVERTISE — {@code end_mls}
         * (0xF001), {@code server_remove} (0xF004) — reaches the engine as an opaque CustomProposal:
         * committing it consumes the proposal and tells the sender it was honoured while no group
         * state changes at all. Without the type those two cases are indistinguishable.
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
     * Drop every by-reference proposal cached for commit on this group.
     *
     * <p>A cached proposal blocks application messages until committed — mls-rs's own
     * {@code commit_required()} contract. So an inbound proposal we cannot honour is not merely
     * ignorable: leaving it cached wedges the conversation permanently, and committing it would
     * consume it while changing nothing. Dropping it is the remaining option, and the caller must say
     * so out loud when it does.
     *
     * <p>Clears the WHOLE cache — mls-rs offers no per-proposal eviction — so reach for it only when
     * the cache holds something unhonourable.
     */
    default boolean clearPendingProposals(byte[] groupId) { return false; }

    /** SELF_LEAVE (fgxc=15): propose our own removal — a by-reference {@code SelfRemoveProposal}
     *  MlsMessage the peer/server acts on. Returns [welcome(empty), PROPOSAL(commit slot), GroupInfo,
     *  epoch_auth, group_id, tree(empty)]; a proposal does not advance the epoch (base==post auth).
     *  Ship as a SELF_LEAVE control message to empty a 1:1 group so the server GCs it. */
    default MlsGroupArtifacts selfLeave(byte[] groupId, byte[] aad) { return null; }

    /**
     * RCC.16 §9.7.1.4/§9.7.1.5 metadata commit: the icon/subject KEYS and their commitments
     *.
     *
     * <p>The commitment is a RefHash over the KEY MATERIAL and the content is encrypted with that
     * key, so the extension is what BINDS the two. A commit carrying only the commitment — or a
     * commitment computed over the ciphertext — leaves the ciphertext unbound and the paired
     * ChangeGroupProfile is refused. Empty slices leave a field unchanged.
     */
    default MlsGroupArtifacts commitGroupMetadata(byte[] groupId, byte[] aad, byte[] iconKey,
            byte[] iconCommitment, byte[] subjectKey, byte[] subjectCommitment) {
        return null;
    }

    /** True iff a claimed peer KeyPackage carries the RFC-9420 {@code last_resort} extension (0x000A).
     *  A member joined on a last-resort (reusable) leaf eagerly self-updates on every commit until it
     *  holds a one-time leaf — perpetual era/epoch churn. The establish path should
     *  prefer a one-time KP and avoid adding a peer via a last-resort one. */
    default boolean kpIsLastResort(byte[] keyPackage) { return false; }

    /**
     * What an engine op RESULT MEANT, beyond "some bytes or null".
     *
     * <p>Every failure used to collapse to a null {@code byte[]}, so a caller could not tell a real
     * failure from a group that is not in storage from an op that correctly had nothing to do. Those
     * three want different remedies — retry, join/recover, and stop — and a bounded drive loop cannot
     * terminate correctly while they are indistinguishable.
     */
    enum OpStatus {
        /** The op succeeded. */
        OK,
        /** The op ran and there was nothing to do. A drive loop must STOP on this, not retry. */
        NO_OP,
        /** The group is not in local storage — a join/recover situation, not a retry. */
        NOT_FOUND,
        /** A real failure. Retryable at the caller's discretion. */
        ERR;

        /**
         * Decode an engine status code.
         *
         * <p><b>Throws on an unknown code</b> rather than defaulting. A code we do not recognise
         * means the engine and this decoder disagree about the contract, and every default is wrong
         * in a different way: treating it as OK acts on a result that may not exist, and treating it
         * as ERR can turn a successful op into an endless retry.
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
     * The status of this session's most recent op on the CALLING thread.
     *
     * <p>Meaningful only immediately after an op, on the thread that made it. Implementations with
     * no status channel report {@link OpStatus#OK}, which preserves the old "null means failure"
     * reading for callers that do not consult it.
     */
    default OpStatus lastStatus() { return OpStatus.OK; }

    /**
     * Largest group-state record this session wrote on the CALLING thread since the last read, in
     * bytes; {@code 0} if none.
     *
     * <p><b>Reading clears it</b>, so a caller that records after every op cannot double-count a
     * write from the previous one. Feed it through {@link MlsMetrics#log2Bucket} and record against
     * {@link MlsMetrics#ZINNIA_STATE_SIZE} — Google Messages' cheapest state-bloat regression detector
     * (rework 13.3). Implementations with no storage instrumentation report {@code 0}.
     */
    default long takeStateWriteBytes() { return 0L; }

    /**
     * The {@code authenticated_data} of the last application message processed on this thread.
     *
     * <p>RFC 9420 AUTHENTICATES the AAD but assigns it no meaning, so enforcing RCC.16 §7.5.3.1 —
     * the AAD's message_id must equal the transport's — is the host's job, and it cannot do that
     * without seeing the bytes. Google Messages does enforce it: two comparison sites against the request
     * context's message_id, failing with MessageIdMismatch.
     *
     * <p>Empty when the last message carried no AAD, was not an application message, or failed.
     */
    default byte[] lastInboundAad() { return new byte[0]; }

    /**
     * The CERTIFIED MSISDN of the leaf that SIGNED the last application message processed on this
     * thread — the authenticated answer to "who sent this".
     *
     * <p><b>The transport envelope's answer is attacker-chosen.</b> MLS authenticates the sender
     * WITHIN the group: a decrypt proves some current member produced the message, and nothing more.
     * It does not tie that member to the identity a UI puts a name against. A host that reads the
     * sender off the envelope therefore lets any member of a group send a message attributed to any
     * other member, simply by addressing the envelope in their name.
     *
     * <p>This is the only place the authenticated answer exists: {@code sender_index} comes out of
     * the decrypt, the roster maps it to a leaf, and that leaf's X.509 SAN carries the MSISDN the
     * KDS certified — the same field the A.4.1 claim-time check reads, so both sides of the identity
     * question have one definition.
     *
     * <p><b>Empty means UNKNOWN, never a match.</b> A non-X.509 credential or a SAN with no
     * {@code tel:} entry lands there, and treating it as agreement would restore the hole.
     *
     * <p>Same thread-and-immediacy rule as {@link #lastInboundAad()}: read it directly after the
     * decrypt, on the same thread. A non-application message CLEARS it, so a stale identity cannot
     * be read for a message that did not carry one.
     */
    default String lastInboundSenderMsisdn() { return ""; }

    /** What the consume-side gate needs to know about a claimed peer KeyPackage. */
    final class KeyPackageInfo {
        /** The KP carries the RFC-9420 {@code last_resort} extension (0x000A) — reusable, not one-time. */
        public final boolean lastResort;
        /**
         * The LeafNode's own RFC 9420 §7.2 {@code Lifetime.not_after}, seconds since the epoch.
         *
         * <p><b>This is NOT the certificate's expiry, and it is not the clock the server measures</b>
         *. On the Tachyon profile the engine anchors this Lifetime at the
         * CERTIFICATE's {@code notBefore} and runs it a fixed 365 days, so it reads ~363d on a
         * certificate with 73 days left — a factor of five, device-measured on one device
         * claiming another's package. A remaining-lifetime floor applied to this
         * value cannot fire before the certificate is ~335 days old, which a ~75-day certificate
         * never reaches. Use {@link #certNotAfterSecs} for anything the RCS SPN will judge.
         */
        public final long notAfterSecs;
        /**
         * The leaf CERTIFICATE's own X.509 {@code Validity.notBefore}, or {@code 0} when the engine
         * could not read it (a non-X.509 credential, or a leaf that will not parse).
         *
         * <p>{@code 0} means not measured. It is never "expired" and never "fine".
         */
        public final long certNotBeforeSecs;
        /**
         * The leaf CERTIFICATE's own X.509 {@code Validity.notAfter}, or {@code 0} when the engine
         * could not read it.
         *
         * <p><b>This is the clock RCC.16's remaining-lifetime floor is actually about.</b> The RCS
         * SPN validates every credential in the post-Commit roster at {@code now + 30d} (A.4.3.1
         * §1(a), Invariant 17) and its refusal quotes {@code Validity { not_before … not_after … }}
         * straight off the certificate — device-measured across three samples.
         */
        public final long certNotAfterSecs;
        /**
         * The MSISDN the leaf CERTIFICATE asserts — its SAN {@code tel:} identity, as bare E.164
         * digits. Empty when the credential is not X.509 or carries no {@code tel:} URI.
         *
         * <p>RCC.16 A.4.1 requires this to EQUAL the number that was queried. Only the caller knows
         * that number — it is whichever MSISDN it asked the KDS for — so the engine reports the
         * certified identity and the comparison happens at the claim site.
         */
        public final String msisdn;
        /**
         * The KeyPackage's OWN {@code cipher_suite} field (RFC 9420 §17.1 code point), or {@code 0}
         * when the engine could not report it.
         *
         * <p>This is the one the MLS library ENFORCES: a package whose suite differs from the
         * group's is refused at add time with {@code CipherSuiteMismatch}. Checking it here only
         * moves an inevitable failure earlier and gives it a name.
         */
        public final int cipherSuite;
        /**
         * The suites the peer's LeafNode ADVERTISES ({@code capabilities.cipher_suites}), in wire
         * order. Empty means UNKNOWN — either the peer advertised none or the advertisement would
         * not parse — and must NOT be read as "the peer does not support our suite".
         *
         * <p>RFC 9420 §7.2 requires a group's suite to appear in every member's list, and mls-rs
         * 0.55.2 does not check that one (it validates required_capabilities, extensions, proposals
         * and credentials, never the suite list). So unlike {@link #cipherSuite}, nothing downstream
         * will catch a violation here — which is exactly why it is surfaced.
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
         * Did the engine read a certificate window at all?
         *
         * <p>The two-arm shape matters: a caller that refuses on the certificate clock must
         * distinguish "the certificate is inside the floor" from "we did not measure a
         * certificate", and only the first is evidence against a peer.
         */
        public boolean certWindowKnown() {
            return certNotAfterSecs > 0L;
        }

        /**
         * Whole days left on the leaf CERTIFICATE at {@code nowSecs}; {@link Long#MIN_VALUE} when
         * the window was not readable, so it can never be mistaken for a small positive number.
         */
        public long certRemainingDays(final long nowSecs) {
            if (!certWindowKnown()) return Long.MIN_VALUE;
            return MlsCredentialFloor.remainingDays(certNotAfterSecs, nowSecs);
        }

        /** Whole days left on the LeafNode {@code Lifetime} at {@code nowSecs}. */
        public long lifetimeRemainingDays(final long nowSecs) {
            return MlsCredentialFloor.remainingDays(notAfterSecs, nowSecs);
        }

        /**
         * BOTH clocks, labelled, for a log line — the thing this pair exists to make
         * impossible to misread. Never print one of these numbers on its own.
         */
        public String clocksText(final long nowSecs) {
            return "leafLifetime=" + lifetimeRemainingDays(nowSecs) + "d(not_after="
                    + notAfterSecs + ") cert=" + (certWindowKnown()
                            ? certRemainingDays(nowSecs) + "d(notAfter=" + certNotAfterSecs + ")"
                            : "UNREADABLE");
        }

        /**
         * Whether the peer's leaf advertises {@code suite}. Returns {@code false} for an empty
         * advertisement, so a caller must test {@link #suitesKnown()} first if it means to
         * distinguish "does not support it" from "did not say".
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

        /** The advertised set as ` 0xNNNN` text, for a log line. */
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
     * Inspect a claimed peer KeyPackage: its two expiry clocks, its identity, its suites and
     * whether it is a last-resort leaf.
     *
     * <p>RCC.16 <b>A.4.1.2 / A.4.2.2</b> require a ≥30-day remaining lifetime before a KeyPackage is
     * consumed, and that floor can only be applied by whoever holds the parser. Returns {@code null}
     * when the KP will not parse or carries no {@code key_package} leaf source — a package we cannot
     * date must fail the floor rather than pass it by default.
     *
     * <p><b>Which expiry the floor is about is a real question and this returns both.</b>
     * {@link KeyPackageInfo#notAfterSecs} is the LeafNode's own §7.2 Lifetime, a
     * number the engine itself mints and which on Tachyon is {@code cert.notBefore + 365d};
     * {@link KeyPackageInfo#certNotAfterSecs} is the leaf certificate's Validity, which is what the
     * RCS SPN measures. They differ by a factor of five on a live Tachyon package.
     */
    default KeyPackageInfo inspectKeyPackage(byte[] keyPackage) { return null; }

    /** Add a member to an existing group → the four artifacts (welcome/commit/groupInfo/tag/tree). */
    /** {@code aad} = the AuthenticatedData bound to the commit. Tachyon rejects a membership commit
     *  with an empty AAD ("Could not parse AAD: []") exactly as it does a rekey — this parameter was
     *  missing entirely. */
    default MlsGroupArtifacts addMember(byte[] groupId, byte[] peerKeyPackage, byte[] aad) {
        return null;
    }

    /**
     * Add EVERY key package in ONE commit — rework {@code 9.1}, §14.1/§15.2.
     *
     * <p>The mirror of {@code removeMemberByMsisdn}, which already removes all of a participant's
     * leaves in one commit. The two must stay symmetric: a roster assembled by one rule and taken
     * apart by another is a roster nobody can reason about.
     *
     * <p><b>N packages means N DEVICES, not N packages from one device.</b> Every package a single
     * client mints carries that client's signature key, and RFC 9420 forbids duplicate leaf data in
     * one group — mls-rs refuses it with {@code DuplicateLeafData}, which is what makes this safe to
     * expose: there is no silent path where an over-eager claim produces duplicate leaves.
     */
    default MlsGroupArtifacts addMembers(byte[] groupId, java.util.List<byte[]> peerKeyPackages,
            byte[] aad) {
        return null;
    }

    /** Remove a member by signature pubkey (empty = the sole non-self member, 1:1) → artifacts, no welcome. */
    /** {@code aad} as in {@link #addMember}. */
    default MlsGroupArtifacts removeMember(byte[] groupId, byte[] memberSigPub, byte[] aad) {
        return null;
    }

    /**
     * Remove EVERY leaf certified to one MSISDN, in ONE commit — <b>the correct selector for a
     * group removal</b>.
     *
     * <p>Prefer this over {@link #removeMember} whenever the participant is known by number. That
     * one selects by signature key and treats an empty key as "the sole other member", which on a
     * group with more than two members removes an <em>arbitrary</em> leaf. Device-observed
     * 2026-08-05: the commit removed someone other than the named member, so the MLS membership no
     * longer matched the RCS roster the same request was changing, and Tachyon answered
     * {@code mismatched-rcs-group-state}.
     *
     * <p>It is also the only selector that is right for a <b>multi-device</b> participant: all of
     * their leaves go in one commit, where removing them one at a time creates an intermediate
     * epoch in which that participant is half-removed. That is the exact mirror of the add path,
     * which already claims a key package per device — and the two must stay symmetric.
     *
     * <p>This existed in the Rust engine for some time with no C export and no binding, referenced
     * from this file's javadoc as though it were reachable. It is wired now.
     */
    default MlsGroupArtifacts removeMemberByMsisdn(byte[] groupId, String msisdn, byte[] aad) {
        return null;
    }

    /** Self-update / rekey (in-place EPOCH advancement) → artifacts, no welcome. {@code aad} is the
     *  RFC-9420 {@code FramedContent.authenticated_data} bound onto the Commit — Google Messages sets the SAME
     *  {@code AuthenticatedData{version, message_id, era, 00}} struct here as on app messages;
     *  an empty AAD draws the server's {@code "Could not parse AAD: []"}. */
    default MlsGroupArtifacts selfUpdate(byte[] groupId, byte[] aad) { return null; }

    /**
     * {@link #selfUpdate} whose published GroupInfo CARRIES {@code external_pub}, with the
     * post-commit ratchet_tree returned as a separate artifact.
     *
     * <p>{@code external_pub} is COMMITTER-produced: the committer builds the GroupInfo and the
     * delivery service only stores and serves it. So a group whose commits all publish a plain
     * GroupInfo can never be resync-joined by anyone — the extension the joiner needs was never put
     * there. Gated on the host; see {@code MlsConfig#KEY_PUBLISH_EXTERNAL_PUB}.
     */
    default MlsGroupArtifacts selfUpdateExtPub(byte[] groupId, byte[] aad) { return null; }

    /** Resync: external-commit from the SERVER's GroupInfo with a fresh leaf (derives new epoch
     *  secrets). {@code removeLeafIndex < 0} = plain fresh join; {@code >= 0} = also remove that stale
     *  leaf. Returns {@code {group_id, external_commit}} (send the commit as a control message), or
     *  {@code null}. */
    default ExternalCommit externalCommitResync(byte[] serverGroupInfo, byte[] ratchetTree,
            long removeLeafIndex) { return null; }

    /** Delete a group's persisted state (AHEAD-discard / PHOENIX reset). */
    default boolean deleteGroup(byte[] groupId) { return false; }

    /** Defer-until-ACK snapshot: capture the group's persisted state BEFORE an optimistic
     *  {@link #selfUpdate} so a rejected control commit can be rolled back via
     *  {@link #restoreGroupSnapshot} — otherwise a self-update that applied locally but the server
     *  never accepted leaves us epoch-AHEAD of the server. Returns the opaque snapshot, or
     *  {@code null}. */
    default byte[] exportGroupSnapshot(byte[] groupId) { return null; }

    /** Restore a {@link #exportGroupSnapshot} snapshot (revert an un-ACKed self-update). */
    default boolean restoreGroupSnapshot(byte[] groupId, byte[] snapshot) { return false; }

    /**
     * True iff a cached BY-REFERENCE proposal must be committed before another application message may
     * be encrypted (mls-rs {@code commit_required}).
     *
     * <p>RCC.16 {@code self_remove} is by-reference and the LEAVER cannot commit it, so a remaining
     * member must sweep it into their next commit.
     */
    default boolean commitRequired(byte[] groupId) { return false; }

    /**
     * Commit the RCC.16 {@code end_mls} GroupContext extension (0xF002) — move the conversation to
     * UNENCRYPTED (§7.11.2.2). After this the client must not send encrypted messages (§9.1.1).
     * {@code remove=true} inverts it, which the server explicitly accepts and is how a conversation
     * returns to encrypted.
     */
    default MlsGroupArtifacts commitEndMls(byte[] groupId, byte[] aad, boolean remove) {
        return null;
    }

    /**
     * ERA ADVANCE that PRESERVES MEMBERSHIP (RCC.16 §8.3) — Google Messages' {@code
     * generate_revive_mls_commit}, as opposed to the create-and-re-add path.
     *
     * <p>Advances era (0xF001) on the EXISTING group via a GroupContextExtensions commit. The ratchet
     * tree and every member survive, so <b>the returned artifacts carry NO Welcome</b>: nobody is
     * being admitted, and existing members follow by applying the commit.
     *
     * <p>This is the difference Tachyon rejects us for getting wrong. Our create-based advance
     * REBUILDS the roster from freshly claimed KeyPackages, which no longer matches the RCS group
     * state the server holds — {@code mlsError 5}, MismatchedRcsGroupState. A preserved roster
     * matches by construction.
     */
    default MlsGroupArtifacts commitEraAdvance(byte[] groupId, byte[] aad, int newEra) {
        return null;
    }

    /** RCC.16 icon/subject COMMITMENT extension types (§7.11.4 / §7.11.6). */
    int EXT_ICON_COMMITMENT = 0xF004;
    int EXT_SUBJECT_COMMITMENT = 0xF006;

    /**
     * Commit the RCC.16 icon/subject COMMITMENT extensions (Annex C.1 values from
     * {@link RccCommitment}). Pass null/empty to leave one untouched.
     *
     * <p>The KEY extensions (0xF003/0xF005) are deliberately not settable: they carry the symmetric
     * keys, are Welcome-only, and §9.7.1.4 wants a sanitised GroupInfo for the server that mls-rs
     * cannot currently build. Putting a key in the GroupContext would ship it to the server.
     */
    default MlsGroupArtifacts commitIconSubject(byte[] groupId, byte[] aad,
            byte[] iconCommitment, byte[] subjectCommitment) {
        return null;
    }

    /**
     * Create a group whose INITIAL commit adds EVERY member.
     *
     * <p>Tachyon validates the MLS GroupContext against the FULL RCS roster, so an N-member RCS
     * group whose MLS group holds only two members is refused {@code mlsError 5}. Google Messages builds the
     * whole membership in one commit (Google's {@code create_group_with_members}), not
     * create-then-Add. One commit means one Welcome covering everyone.
     *
     * @param peerKeyPackages each member's claimed KeyPackage, in claim order
     * @param groupIdOverride the MLS group id — for an RCS group this MUST be the RCS group id
     */
    default MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride) {
        return createGroupMulti(era, peerKeyPackages, groupIdOverride, null);
    }

    /**
     * As above, CARRYING OVER the RCC.16 metadata (0xF003-0xF006) from an existing GroupInfo.
     *
     * <p>Required for an ERA ADVANCE. A fresh GroupContext has none of the metadata the old one
     * accumulated, and Tachyon refuses an advance that drops it: {@code "Subject commitment changed
     * from Some([..]) to None"} — the check that stops a commitment being erased by advancing.
     *
     * @param carryGroupInfo the SERVER's GroupInfo. Not ours: a member needing an era advance is
     *                       behind by definition, so its own copy of these extensions is stale.
     */
    default MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo) {
        return createGroupMulti(era, peerKeyPackages, groupIdOverride, carryGroupInfo,
                MlsAdvanceEraKind.NORMAL);
    }

    /**
     * As above, with the §9.7g <b>era-advance MODE</b> — rework {@code 11.1c}/{@code 11.2c},
     * invariant 103.
     *
     * <p>The mode decides what happens to {@code end_mls} (0xF002) in the new era, and it is the
     * mechanism that makes INVARIANT ED-1 structural rather than guarded:
     *
     * <ul>
     *   <li>{@link MlsAdvanceEraKind#NORMAL} — carry it forward exactly as it is. Every ordinary
     *       caller wants this: dropping it would silently erase a peer's downgrade.</li>
     *   <li>{@link MlsAdvanceEraKind#REVIVAL} — <b>drop</b> it. One of exactly two deliberate removal
     *       sites in the whole engine.</li>
     *   <li>{@link MlsAdvanceEraKind#PHOENIX_DOWNGRADE} — <b>install</b> it. The new era is born
     *       downgraded, which a preserve-only carry list cannot express.</li>
     * </ul>
     *
     * <p><b>Do not add a fourth mode, and do not derive the mode from group state.</b> The intent
     * comes from the caller precisely so that no recovery path can arrive at a removal by accident.
     *
     * <p><b>FALSIFIER</b> — the two halves fail differently, so they get
     * different ones:
     *
     * <ul>
     *   <li><i>"No fourth mode"</i> is EMPIRICAL and overturnable: a Google Messages era advance observed
     *       doing something to {@code end_mls} that none of carry / drop / install expresses. The
     *       three are what the mode byte has been seen to drive; they are not proven exhaustive, and
     *       a fourth Google Messages behaviour is a reason to add one rather than to force it into an
     *       existing mode.</li>
     *   <li><i>"Do not derive the mode from group state"</i> is a DESIGN rule and has no falsifier —
     *       no observation about Google Messages could make it safe for a recovery path to infer a removal
     *       it was never asked for. Stated separately so the empirical half is not read as protected
     *       by the design half, which is exactly how a checkable claim stops being checked.</li>
     * </ul>
     */
    default MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, MlsAdvanceEraKind kind) {
        return null;
    }

    /**
     * Build a group operation WITHOUT naming an era — the entry point every create should use
     * (design §9.5).
     *
     * <h2>The inversion</h2>
     *
     * <p>Every overload above takes an era, which means the HOST decided it. That is backwards, and
     * the signature is where the fix has to live: Google Messages' engine is handed a context, a config and
     * a member list and no era at all, derives one from its own state, and reports
     * what it did as a {@code welcomeAction} on the create response. The rule, in one line:
     *
     * <p><b>ERA IS AN INPUT TO READS AND AN OUTPUT OF WRITES.</b> Naming a moment is legitimate when
     * ASKING about state ({@code GetMlsGroupInfo} takes one) and never when CHANGING it.
     *
     * <p>Why the signature and not a convention: the era lives only as GroupContext extension
     * {@code 0xF001} inside the GroupInfo, so the layer that writes that field is the only one that
     * can honestly say which era a group was built at. Leaving the host to compute the number and
     * merely relocating the enum would reproduce the old behaviour with more code — an entry point
     * that <em>cannot</em> be told an era is what actually moves the decision.
     *
     * <h2>What the caller must do with the answer</h2>
     *
     * <p>Route the RPC on {@link MlsGroupArtifacts#welcomeAction}, and never on which method it is
     * standing in:
     *
     * <ul>
     *   <li>{@link MlsWelcomeAction#NEW_MEMBERSHIP_EXISTING_GROUP} — the bundle is an
     *       {@code addMembers} commit at the SAME era. Ship it on the add RPC.</li>
     *   <li>{@link MlsWelcomeAction#NEW_GROUP}, {@link MlsWelcomeAction#NEW_ERA_EXISTING_GROUP},
     *       {@link MlsWelcomeAction#REFRESH_MEMBERSHIP_EXISTING_GROUP} — a create. Ship it on
     *       {@code CreateMlsConversation}.</li>
     *   <li>Anything else, including {@link MlsWelcomeAction#UNKNOWN} and a {@code null}: refuse.
     *       An action this build cannot name is one it does not understand.</li>
     * </ul>
     *
     * <p>The era to report, persist and verify against the server is
     * {@link MlsGroupArtifacts#era} — the engine's, not one the caller recomputed.
     *
     * @param carryGroupInfo the SERVER's GroupInfo when we have one, else null. Supplying it is how
     *        the engine learns the server's era; it is state, not a number, and reading {@code
     *        0xF001} out of it here is the difference between the engine being handed a fact and
     *        being told an answer.
     */
    default MlsGroupArtifacts createGroupPlanned(java.util.List<byte[]> peerKeyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, MlsAdvanceEraKind kind) {
        return null;
    }

    /** Read an RCC.16 group extension's data, or empty. */
    default byte[] groupExt(byte[] groupId, int extType) { return new byte[0]; }

    /**
     * Probe: which GroupContext extension types a serialized GroupInfo carries, as big-endian
     * {@code [u16 type][u16 length]} records. Answers "does the peer/server actually send X" from
     * the bytes rather than from a byte-scan, which would false-positive inside key material.
     */
    default byte[] groupInfoExtTypes(byte[] groupInfo) { return new byte[0]; }

    /**
     * RCC.16 §7.11.12 — read {@link MlsContinuityCodePoints#TOKEN} or
     * {@link MlsContinuityCodePoints#COMMITMENT} out of a serialized GroupInfo, DECODED.
     *
     * <p>Deliberately NOT version-gated: a peer that sends us continuity is understood whatever
     * revision we announce. This is the instrument for the measurement that gates the whole feature
     * — v4.0 requires the commitment in every GroupInfo, so its absence everywhere is decisive.
     *
     * @return the decoded value, or empty when absent
     */
    default byte[] groupInfoContinuity(byte[] groupInfo, int extType) { return new byte[0]; }

    /**
     * RCC.16 §7.11.12.1 — collect the continuity token a Welcome carried for {@code groupId}, and
     * forget it in the engine.
     *
     * <p>The token rides the ENCRYPTED GroupInfo of a Welcome, "for new joiners", and that is the
     * only channel a peer has ever been measured to use: a Welcome built for us by a Google
     * Messages peer carried {@code 0xF010=33B}, i.e. {@code 0x20 ‖ 32}, the right
     * shape for §8.3.1.1's 256 CSPRNG bits. Before this existed the engine logged that length and
     * dropped the value.
     *
     * <p>Returns the DECODED value, or empty. <b>Empty is a normal answer</b> — a group we created,
     * an external-commit join (no Welcome), a peer that does not do continuity, or a second call —
     * so a caller must not treat it as a failure. The caller is responsible for persisting what it
     * collects; nothing keeps a second copy.
     *
     * <p>Read-side only. It changes no bytes on the wire and is independent of whether we ever EMIT
     * continuity, which is a separate and unsettled decision.
     */
    default byte[] takeWelcomeContinuityToken(byte[] groupId) { return new byte[0]; }

    /**
     * RCC.16 §7.9.2 — a GroupInfo carrying the continuity-token commitment.
     *
     * <p>Under v3.0 this returns the ordinary GroupInfo unchanged, so a caller may invoke it
     * unconditionally and get the right bytes for whichever revision the transport announced.
     */
    default byte[] groupInfoWithContinuity(byte[] groupId, byte[] commitment, boolean withTree) {
        return new byte[0];
    }

    /**
     * Probe: one extension's DECODED VALUE out of a serialized GroupInfo — the companion to
     * {@link #groupInfoExtTypes}, which reports only that an extension is present and how long it is.
     *
     * <p>Added because the server accepts an era-advance create and then reports the
     * OLD era, and seeing {@code 0xF001} listed as "4B" on the server's GroupInfo does not say which
     * era it holds. Empty when the extension is absent.
     *
     * <p>Decoded like {@link #groupExt}, so the two are directly comparable — the varint-framed types
     * are unframed, the bare ones ({@code 0xF001}, {@code 0xF002}, {@code 0xF007}) pass through.
     */
    default byte[] groupInfoExt(byte[] groupInfo, int extType) { return new byte[0]; }

    /**
     * RCC.16 §7.6.2 — sign {@code derivedContent} (a §7.6.3 VerifiableDerivedContent) into an
     * {@code rcs_signature} PublicMessage. The caller Base64s it into the CPIM
     * {@code MLS-Derived-Content-Signature} header. Never cached, never committed.
     */
    default byte[] rcsSign(byte[] groupId, byte[] derivedContent) { return null; }

    /**
     * RCC.16 §7.6.2 — verify an inbound {@code rcs_signature} PublicMessage.
     *
     * @return {@code [u32 BE leaf_index][derivedContent]}, or null if the signature or proposal type
     *         did not check out. The leaf matters: a valid signature from the WRONG member is not a
     *         valid receipt for that member's message. Never returns unverified data.
     */
    default byte[] rcsVerify(byte[] groupId, byte[] publicMessage) { return null; }

    /** True iff the group carries the {@code end_mls} tag — encrypted sending is forbidden (§9.1.1). */
    default boolean endMlsPresent(byte[] groupId) { return false; }

    /** Status-tagged process (distinguishes APP/COMMIT/PROPOSAL/OTHER/FAILED for the receive path). */
    default ProcResult processEx(byte[] groupId, byte[] wire) { return null; }

    /**
     * The REPEATED-RESULT form of {@link #processEx} (rework 6.6, §10.5).
     *
     * <p>Each result is stamped with the context it belongs to, so the host can demux with
     * {@link MlsResultBundle} — post-processing results for OTHER contexts for their effects while
     * returning only its own (invariant 57).
     *
     * <p>Returns exactly one result today, because that is what the engine produces. The vocabulary
     * is the point: when the encrypt path folds in a self-key-update commit (rework 7.3) it becomes
     * a second element, and nothing above this changes.
     *
     * @param contextId the caller's context; echoed on every result the engine attributes to it
     * @return never {@code null} and never empty — a refusing engine yields one MALFORMED result,
     *         which is a different fault from "the engine returned nothing" and must stay
     *         distinguishable from it
     */
    default java.util.List<MlsEngineResult> processResults(byte[] groupId, byte[] wire,
            String contextId) {
        return java.util.Collections.emptyList();
    }

    /**
     * The REPEATED-RESULT form of {@link #encryptWithAad} (rework 7.3, §11.1a).
     *
     * <p>§11.1a requires the WHOLE result list be dispatched before the caller branches on status,
     * because the second element can be a self-key-update commit the engine folded in. Taking the
     * first and dropping the rest means the send succeeds, the rotation silently does not happen,
     * and nothing says so.
     *
     * <p>The ciphertext is always first and is produced at the CURRENT epoch; the commit, when
     * present, comes after. That order is load-bearing — reversed, the message would be encrypted at
     * an epoch its recipients have not been told about yet.
     *
     * <p>The host decides whether to rotate and passes {@code wantKeyUpdate}: Google Messages' engine keeps
     * an {@code encryption_key_usage_level} of its own, ours does not, and adding a second counter
     * would put one decision in two places. What the engine guarantees is the part that matters —
     * both artifacts come from one call, so neither can be lost by a caller that branches early.
     *
     * @return the results, ciphertext first; EMPTY if nothing was produced (no generation consumed)
     */
    default java.util.List<MlsEngineResult> encryptResults(byte[] groupId, byte[] plaintext,
            byte[] aad, String contextId, boolean wantKeyUpdate) {
        return java.util.Collections.emptyList();
    }

    @Override
    void close();
}
