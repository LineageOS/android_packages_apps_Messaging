/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import android.util.Log;

/**
 * JNI bindings of the native MLS core, one to one with the {@code rcs_mls.h} C ABI in
 * {@code libmlsopenmlsbridge.so}. A handle is the native session pointer as a {@code long}. See
 * {@link OpenMlsEngine} and docs/mls/rust-core.md.
 */
public final class OpenMlsNative {
    private static final String TAG = MlsLog.TAG;
    private static volatile boolean sLoaded;
    private static volatile boolean sTriedLoad;

    private OpenMlsNative() {}

    /** True iff the library loaded. Lazy and idempotent. */
    public static synchronized boolean available() {
        if (!sTriedLoad) {
            sTriedLoad = true;
            try {
                System.loadLibrary("mlsopenmlsbridge");
                sLoaded = true;
                Log.i(TAG, "libmlsopenmlsbridge loaded");
                // The KeyPackage lifetime and peer-certificate settings are not set here:
                // OpenMlsEngine sets both on every session start.
                try {
                    // Relaxes a peer-certificate check, so a debug build only: adb can set a
                    // debug.* property on a user build, but not ro.debuggable.
                    final boolean popLenient =
                            android.os.SystemProperties.getInt("ro.debuggable", 0) == 1
                            && android.os.SystemProperties.getBoolean(
                                    "debug.rcs.mls_pop_lenient", false);
                    if (popLenient) {
                        nativeSetPopLenient(true);
                    }
                    Log.i(TAG, "MLS .4 PoP "
                            + (popLenient ? "LENIENT (debug.rcs.mls_pop_lenient=1)" : "ENFORCED"));
                } catch (Throwable t2) {
                    Log.w(TAG, "nativeSetPopLenient failed", t2);
                }
            } catch (Throwable t) {
                Log.w(TAG, "libmlsopenmlsbridge not available", t);
            }
        }
        return sLoaded;
    }

    /** {@link OpenMlsEngine.KeyPackageLifetime#FIXED_365_DAYS}; clear is WITHIN_CERTIFICATE. */
    static final int SETTING_KP_FIXED_365_DAYS = 0x01;
    /** {@link OpenMlsEngine.PeerCertificatePolicy#DEPLOYMENT_TOLERANT}; clear is RCC16_STRICT. */
    static final int SETTING_PEER_CERT_TOLERANT = 0x02;

    /**
     * Sets the engine's two settings from a flags byte ({@code SETTING_*}); process-global, read
     * only while {@link #nativeSessionStart} builds a session. Uncalled, both are the RCC.16 ones.
     */
    static native void nativeSetEngineSettings(int flags);

    /** {@code true}: a failed {@code .4} proof warns rather than rejecting the peer. */
    static native void nativeSetPopLenient(boolean on);

    /**
     * Sets the RCC.16 revision ({@code 30} or {@code 40}); an unknown value is refused. Returns the
     * value in effect. See {@link Rcc16Version}.
     */
    static native int nativeSetRcc16Version(int version);

    /**
     * RCC.16 §7.9.2 GroupInfo carrying the caller-computed continuity-token commitment (0xF011);
     * unchanged under v3.0.
     */
    static native byte[] nativeGroupInfoWithContinuity(long handle, byte[] groupId,
            byte[] commitment, boolean withTree);

    /**
     * The decoded 0xF010 (token) or 0xF011 (commitment) from a serialised GroupInfo, whatever
     * revision is configured.
     *
     * @return the decoded value, or {@code null} when the extension is absent
     */
    static native byte[] nativeGroupInfoContinuity(long handle, byte[] groupInfo, int type);

    /**
     * {@code chain}, {@code roots} and {@code revokedSerials} are {@code [u32-be len][bytes]}
     * records; the revocation list may be empty.
     */
    static native long nativeSessionStart(byte[] leaf, byte[] chain, byte[] priv,
            byte[] pub, byte[] roots, byte[] revokedSerials, String storageDir);
    static native byte[] nativeGenerateKeyPackages(long handle, int count);
    static native byte[] nativeGenerateLastResortKp(long handle);

    /**
     * RCC.16 §7.11.12.1: returns and forgets the continuity token (0xF010) captured from the
     * joining Welcome; empty is normal. The only read that can see it. See docs/mls/rust-core.md.
     */
    static native byte[] nativeTakeWelcomeContinuityToken(long handle, byte[] groupId);

    /** Per-leaf (index, MSISDN, participant-key SPKI), length-prefixed. */
    static native byte[] nativeMemberParticipantKeys(long handle, byte[] groupId);

    /** The RFC 9420 KeyPackageRef of one MLSMessage-wrapped KeyPackage. */
    static native byte[] nativeKeyPackageRef(long handle, byte[] keyPackage);

    /** Len-prefixed list of the KeyPackageRefs a Welcome is sealed to. */
    static native byte[] nativeWelcomeKeyPackageRefs(long handle, byte[] welcome);
    static native byte[] nativeCreateGroup(long handle, int era, byte[] peerKeyPackage,
            byte[] groupIdOverride);
    static native byte[] nativeJoin(long handle, byte[] welcome);
    /**
     * Welcome join with an out-of-band {@code ratchet_tree}, for Welcomes that omit the extension.
     * Returns the group id, or null.
     */
    static native byte[] nativeJoinWithTree(long handle, byte[] welcome, byte[] ratchetTree);
    /**
     * Join from a Welcome without a {@code ratchet_tree} extension; the native side builds the tree
     * from the member LeafNodes in {@code blob}, the whole inner payload. Returns the group id or
     * null.
     */
    static native byte[] nativeJoinTreelessWelcome(long handle, byte[] welcome, byte[] blob);

    /**
     * {@code [u32 leafIndex][u64 notBefore][u64 notAfter]} per member, 20 bytes each, big-endian;
     * empty on error.
     */
    static native byte[] nativeMemberValidity(long handle, byte[] groupId);

    /**
     * Per-leaf {@code [u32 index][u64 notBefore][u64 notAfter][u32 msisdnLen][msisdn]} from a
     * serialised ratchet tree (the server's copy); read-only.
     */
    static native byte[] nativeTreeMemberValidity(long handle, byte[] ratchetTree);

    /** {@code [u32 leafIndex][u64 epoch]} of a GroupInfo's signer, from its bytes alone. */
    static native byte[] nativeGroupInfoSigner(long handle, byte[] groupInfo);
    /**
     * Our own leaf's certificate window in the group beside the client's:
     * {@code [u32 leafIndex][u64 groupNb][u64 groupNa][u64 clientNb][u64 clientNa][u8 stale]}, 37
     * bytes, big-endian; null or short on error. See {@link MlsSelfLeafStatus}.
     */
    static native byte[] nativeSelfLeafStatus(long handle, byte[] groupId);
    /**
     * External-commit join from a peer's GroupInfo and optional ratchet tree. Returns
     * length-prefixed {group_id, external_commit}; the caller sends the commit.
     */
    static native byte[] nativeExternalJoin(long handle, byte[] groupInfo, byte[] ratchetTree);
    /**
     * {@code messageId} is the message id, not an AAD: the engine builds the AAD itself, with the
     * era from the group's 0xF001 extension.
     */
    static native byte[] nativeEncrypt(long handle, byte[] groupId, byte[] plaintext,
            byte[] messageId);

    /**
     * RCC.16 §7.5.3.1: the message id the next {@code nativeProcess}/{@code nativeProcessEx} is
     * for, checked against the inbound AAD. {@code null} clears it and skips the check; an empty
     * array arms an empty id.
     */
    static native int nativeSetRequestMessageId(byte[] messageId);

    /** Non-zero if the last processed application message's AAD id did not match. */
    static native int nativeLastMessageIdMismatch();

    /** {@code expected\0actual} behind a mismatch. Empty when there was none. */
    static native byte[] nativeLastMessageIdMismatchDetail();

    /**
     * One-shot: puts an RCC.16 §10.3 resent-message component in the next AAD the engine builds,
     * instead of the absent {@code 0x00}.
     */
    static native int nativeSetNextResentComponent(byte[] component);
    /** The 4-byte big-endian generation the next encrypt will use; does not advance. */
    static native byte[] nativeNextAppGen(long handle, byte[] groupId);
    /** The current epoch's 32-byte {@code epoch_authenticator}. */
    static native byte[] nativeEpochAuth(long handle, byte[] groupId);
    /**
     * {@code [era u32 BE][epoch u64 BE]}: the group's era (0xF001, default 1) and RFC 9420 epoch.
     */
    static native byte[] nativeEraEpoch(long handle, byte[] groupId);
    /**
     * Status ({@link MlsSession.OpStatus}) of the calling thread's most recent engine operation;
     * read it before any other engine call on the thread.
     */
    static native int nativeLastStatus();

    /** {@code authenticated_data} of the last application message processed on this thread. */
    static native byte[] nativeLastAad();
    /**
     * Certified MSISDN of the leaf that signed the last application message processed on this
     * thread. Empty means unknown, never a match.
     */
    static native byte[] nativeLastSenderMsisdn();

    /** Largest group-state record written on this thread since the last read, or 0; clears. */
    static native long nativeTakeStateBytes();

    static native byte[] nativeProcess(long handle, byte[] groupId, byte[] wire);
    // ---- Group mutation, resync, delete, status-tagged process.
    /** Adds a member; returns the 6-record bundle [welcome, commit, groupInfo, tag, gid, tree]. */
    static native byte[] nativeAddMember(long handle, byte[] groupId, byte[] peerKeyPackage,
            byte[] messageId);

    /** Adds several members (length-prefixed) in one commit, so none is half-added. */
    static native byte[] nativeAddMembers(long handle, byte[] groupId, byte[] keyPackages,
            byte[] messageId);
    /** Removes a member by signature key (empty: the sole other member); empty Welcome. */
    static native byte[] nativeRemoveMember(long handle, byte[] groupId, byte[] memberSigPub,
            byte[] messageId);
    /**
     * Removes every leaf certified to one MSISDN in one commit; the selector for group removals,
     * since {@link #nativeRemoveMember} with an empty key picks an arbitrary leaf in a larger
     * group.
     */
    static native byte[] nativeRemoveMemberByMsisdn(long handle, byte[] groupId, byte[] msisdn,
            byte[] messageId);
    /** Commits {@code end_mls} (RCC.16 §7.11.2.2), or removal if {@code remove}; empty Welcome. */
    static native byte[] nativeCommitEndMls(long handle, byte[] groupId, byte[] messageId,
            boolean remove);

    /** Membership-preserving era advance (RCC.16 §8.3): no KeyPackages, no Welcome. */
    static native byte[] nativeCommitEraAdvance(long handle, byte[] groupId, byte[] messageId,
            int newEra);

    /** Extension types in a serialised GroupInfo, as {@code [u16 type][u16 len]} records. */
    static native byte[] nativeGroupInfoExtTypes(long handle, byte[] groupInfo);
    static native byte[] nativeGroupInfoExt(long handle, byte[] groupInfo, int extType);

    /** Metadata commit carrying the icon and subject keys and their commitments. */
    static native byte[] nativeCommitGroupMetadata(long handle, byte[] groupId, byte[] messageId,
            byte[] iconKey, byte[] iconCommitment, byte[] subjectKey, byte[] subjectCommitment);

    static native byte[] nativeCommitIconSubject(long handle, byte[] groupId, byte[] messageId,
            byte[] iconCommitment, byte[] subjectCommitment);

    /**
     * Creates a group with all members in the initial commit ({@code keyPackages} length-prefixed).
     * {@code advanceMode}: 0 carries {@code end_mls} as is, 1 drops it (revival), 2 installs it.
     * See {@link MlsAdvanceEraKind}.
     */
    static native byte[] nativeCreateGroupMulti(long handle, int era, byte[] keyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, int advanceMode);

    /**
     * As above, but the engine chooses the era. Two slots follow the usual six: [6] the u32
     * big-endian era built at, [7] the u32 big-endian {@code welcomeAction}.
     */
    static native byte[] nativeCreateGroupPlanned(long handle, byte[] keyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, int advanceMode);

    static native byte[] nativeGroupExt(long handle, byte[] groupId, int extType);

    static native byte[] nativeEndMlsPresent(long handle, byte[] groupId);

    static native byte[] nativeRcsSign(long handle, byte[] groupId, byte[] derivedContent);

    static native byte[] nativeRcsVerify(long handle, byte[] groupId, byte[] publicMessage);

    static native byte[] nativeCommitRequired(long handle, byte[] groupId);

    static native byte[] nativeSelfUpdate(long handle, byte[] groupId, byte[] messageId);
    static native byte[] nativeKpIsLastResort(long handle, byte[] keyPackage);
    /** {@code [u8 last_resort][u64 not_after BE]} for a claimed peer KeyPackage. */
    static native byte[] nativeKpInspect(long handle, byte[] keyPackage);
    /** Drops all cached by-reference proposals; see {@code MlsSession.clearPendingProposals}. */
    static native byte[] nativeClearPendingProposals(long handle, byte[] groupId);
    static native byte[] nativeSelfLeave(long handle, byte[] groupId, byte[] messageId);
    /** Snapshots the group's persisted state before an optimistic self-update. */
    static native byte[] nativeExportGroupSnapshot(long handle, byte[] groupId);
    /** Restores a snapshot, reverting an unacknowledged self-update. */
    static native byte[] nativeRestoreGroupSnapshot(long handle, byte[] groupId, byte[] snapshot);
    /**
     * External-commit resync from a server GroupInfo and optional tree; a negative
     * {@code removeLeafIndex} removes nothing. Returns [group_id, external_commit, groupInfo,
     * epochAuth, ratchet_tree].
     */
    static native byte[] nativeExternalCommitResync(long handle, byte[] serverGroupInfo,
            byte[] ratchetTree, long removeLeafIndex);
    /**
     * As {@code nativeSelfUpdate}, but the GroupInfo carries {@code external_pub} (needed for any
     * resync external commit) and the post-commit ratchet tree is the 6th record.
     */
    static native byte[] nativeSelfUpdateExtPub(long handle, byte[] groupId, byte[] messageId);
    /** Deletes a group's persisted state; 1 byte, 1 = ok. */
    static native byte[] nativeDeleteGroup(long handle, byte[] groupId);
    /**
     * Status-tagged process: [status, payload...]; 0 app, 1 commit, 2 proposal, 3 other, 7 failed.
     */
    static native byte[] nativeProcessEx(long handle, byte[] groupId, byte[] wire);
    /**
     * Processes into a length-prefixed list of results, each stamped with its context; the format
     * is defined by {@link MlsEngineResult#decodeList}.
     */
    static native byte[] nativeProcessResults(long handle, byte[] groupId, byte[] wire,
            byte[] contextId);
    /**
     * Encrypts, returning the ciphertext and, when {@code wantKeyUpdate}, a self-update commit, in
     * that order. Decoded by {@link MlsEngineResult#decodeList}.
     */
    static native byte[] nativeEncryptResults(long handle, byte[] groupId, byte[] plaintext,
            byte[] messageId, byte[] contextId, boolean wantKeyUpdate);
    static native void nativeSessionClose(long handle);

    // ---- RCC.16 credential encoding. Stateless (no handle). The caller signs what these return,
    // so private keys never reach native code; null means the encoder refused. See Rcc16Der.

    /** {@code Name} with one {@code CN=<cn>} as UTF8String; {@code cn} is UTF-8. */
    static native byte[] nativeRcc16SubjectDer(byte[] cn);

    /** {@code GeneralNames} with one URI; {@code uri} is UTF-8. */
    static native byte[] nativeRcc16SanDer(byte[] uri);

    /** Validity in unix seconds; null if empty or inverted. */
    static native byte[] nativeRcc16ValidityDer(long notBefore, long notAfter);

    /**
     * {@code tbsParticipantInfo}, to sign with the participant key. Subject, leaf SPKI and SAN come
     * from the leaf; vendor id and validity from the extension.
     */
    static native byte[] nativeRcc16TbsDer(byte[] subject, long vendorId, byte[] validity,
            byte[] leafSpki, byte[] san);

    /** The {@code .4 ParticipantInformation} value around a signature over that TBS. */
    static native byte[] nativeRcc16Ext4Der(long vendorId, byte[] validity, byte[] popSig,
            byte[] participantSpki);

    // ---- Self-test PKI: throwaway chains for driving the engine without a network. Same split:
    // these return a TBS, the caller signs, nativeRcc16Certificate assembles.

    /** A CA certificate's TBS; for a root pass issuer == subject and aki == ski. */
    static native byte[] nativeRcc16TbsCa(byte[] issuer, byte[] subject, byte[] spki,
            byte[] serial, long notBefore, long notAfter, byte[] ski, byte[] aki, long vendorId);

    /** A client leaf's TBS; {@code san} and {@code ext4} are embedded verbatim. */
    static native byte[] nativeRcc16TbsLeaf(byte[] issuer, byte[] subject, byte[] spki,
            byte[] serial, long notBefore, long notAfter, byte[] ski, byte[] aki, byte[] san,
            byte[] ext4, long vendorId);

    /** A certificate from its TBS and signature. */
    static native byte[] nativeRcc16Certificate(byte[] tbs, byte[] signature);

    /** CA {@code Name} {@code O=}, {@code CN=}, both PrintableString. */
    static native byte[] nativeRcc16CaNameDer(byte[] org, byte[] cn);

    /** Flips one bit of a {@code .4} signature, for a negative test fixture. */
    static native byte[] nativeRcc16CorruptPop(byte[] ext4);
}
