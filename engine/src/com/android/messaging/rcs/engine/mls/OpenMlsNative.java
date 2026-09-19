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

import android.util.Log;

/**
 * JNI face of the OpenMLS (mls-rs) engine — 1:1 with the {@code rcs_mls.h} C ABI in
 * {@code libmlsopenmlsbridge.so} (which whole-static-links the out-of-tree {@code librcs_mls_ffi}
 * staticlib). Handle = the native {@code ProdSession*} as a {@code long}. See {@link OpenMlsEngine}.
 */
public final class OpenMlsNative {
    private static final String TAG = MlsLog.TAG;
    private static volatile boolean sLoaded;
    private static volatile boolean sTriedLoad;

    private OpenMlsNative() {}

    /** True iff libmlsopenmlsbridge.so loaded. Lazy, idempotent. */
    public static synchronized boolean available() {
        if (!sTriedLoad) {
            sTriedLoad = true;
            try {
                System.loadLibrary("mlsopenmlsbridge");
                sLoaded = true;
                Log.i(TAG, "libmlsopenmlsbridge loaded");
                // The provider IS the Google Tachyon path — select the Tachyon behavior profile in
                // the shared Rust engine as soon as the lib loads, before ANY session/KeyPackage op
                // (the KP lifetime is baked at session_start). Lenient .4 PoP + 365d KP so Google
                // Messages certs validate and Google's KDS accepts the KP. (This class is the
                // PROVIDER's OpenMlsNative; the app has its own, so the lab profile is unaffected.)
                try {
                    nativeSetTachyonProfile(true);
                    // The .4 PoP is ENFORCED on both profiles —
                    // it is no longer part of the Tachyon profile. debug.rcs.mls_pop_lenient=1
                    // reverts to tolerate-and-warn without a rebuild.
                    final boolean popLenient =
                            android.os.SystemProperties.getBoolean("debug.rcs.mls_pop_lenient", false);
                    if (popLenient) {
                        nativeSetPopLenient(true);
                    }
                    Log.i(TAG, "MLS engine profile = Tachyon (365d KP); .4 PoP "
                            + (popLenient ? "LENIENT (debug.rcs.mls_pop_lenient=1)" : "ENFORCED"));
                } catch (Throwable t2) {
                    Log.w(TAG, "nativeSetTachyonProfile failed", t2);
                }
            } catch (Throwable t) {
                Log.w(TAG, "libmlsopenmlsbridge not available", t);
            }
        }
        return sLoaded;
    }

    // chain/roots are a concatenation of [u32-be len][DER] records.
    /** Select the Google-Tachyon behavior profile (lenient .4 PoP + 365d KP lifetime) in the
     *  shared Rust engine. Provider calls it ONCE before nativeSessionStart. */
    static native void nativeSetTachyonProfile(boolean on);

    /**
     * Escape hatch for the {@code .4} ParticipantInformation PoP enforcement.
     * Default (never called) = <b>ENFORCE</b>: a failed PoP rejects the
     * peer's certificate. Pass {@code true} to restore tolerate-and-warn.
     *
     * <p>Driven by {@code debug.rcs.mls_pop_lenient}. It exists because enforcement rejects a
     * <b>peer</b>, and the evidence behind the flip — Google's KDS across three lines and two
     * Google Messages versions, plus a captured Apple leaf — covers only vendors we could measure.
     * A vendor we have never seen is exactly the case that evidence cannot reach, and by the time
     * it matters someone is looking at a conversation that will not establish.
     */
    static native void nativeSetPopLenient(boolean on);

    /**
     * Announce the RCC.16 SPECIFICATION VERSION this transport speaks — {@code 30} for v3.0 (the
     * default) or {@code 40} for v4.0. Returns the version now <b>in effect</b>.
     *
     * <p>An unrecognised value is REFUSED by the engine and the current version kept, so a caller
     * that gets back something other than what it passed has been told its value was rejected. A
     * spec revision must never be selected by a truncated or typo'd number.
     *
     * <p>This is a SEPARATE axis from {@link #nativeSetTachyonProfile}: the profile says whose KDS
     * and certificate rules apply, the version says which spec revision's wire shapes to emit. Today
     * Tachyon is (Tachyon profile, v3.0) and the lab is (lab profile, v3.0 → v4.0 on request), but
     * a lab on v4.0 has to be expressible, so they cannot be collapsed into one switch.
     *
     * <p><b>Never infer this from a probe or an advertisement.</b> Nothing Tachyon sends states its
     * RCC.16 version; a detection heuristic here is the same silent-failure class as guessing a code
     * point. See {@link OpenMlsEngine.Rcc16Version}.
     */
    static native int nativeSetRcc16Version(int version);

    /**
     * RCC.16 §7.9.2 — build a GroupInfo carrying the continuity-token COMMITMENT (0xF011).
     *
     * <p>Under v3.0 this returns the ordinary GroupInfo unchanged, so callers invoke it
     * unconditionally and get the right bytes for whichever revision the transport announced.
     *
     * <p>{@code commitment} is computed host-side ({@link MlsContinuityToken#commitment}) because it
     * needs the TOKEN, which is per-conversation state that must outlive any single MLS group — the
     * whole point of continuity is surviving an Era advance, and an Era advance destroys the group.
     * Passing the finished commitment down also keeps the secret out of the engine's storage.
     *
     * <p>The token itself (0xF010) is Welcome-only and never appears here: this GroupInfo is the one
     * handed to the SERVER. Welcome-only is not the same as unreachable — it reaches this surface at
     * the one place it can, {@link #nativeTakeWelcomeContinuityToken}.
     */
    static native byte[] nativeGroupInfoWithContinuity(long handle, byte[] groupId,
            byte[] commitment, boolean withTree);

    /**
     * Read 0xF010 (token) or 0xF011 (commitment) out of a serialized GroupInfo, DECODED.
     *
     * <p>Not version-gated — a peer that sends us continuity is understood whatever we announce.
     * This is also the instrument for the one measurement that gates the whole feature: v4.0 requires
     * the COMMITMENT in every GroupInfo, so if the deployed server does continuity at all we see
     * 0xF011 universally, and its absence everywhere is decisive evidence it does not.
     *
     * @return the decoded value, or {@code null} when the extension is absent
     */
    static native byte[] nativeGroupInfoContinuity(long handle, byte[] groupInfo, int type);

    /** {@code revokedSerials} is the same [u32-be len][bytes] framing as chain/roots — the
     *  host-pushed RevokedCertificates list (CreateClientRequest field 7). Empty is normal. */
    static native long nativeSessionStart(byte[] leaf, byte[] chain, byte[] priv,
            byte[] pub, byte[] roots, byte[] revokedSerials, String storageDir);
    static native byte[] nativeGenerateKeyPackages(long handle, int count);
    static native byte[] nativeGenerateLastResortKp(long handle);

    /**
     * RCC.16 §7.11.12.1 — collect the continuity token (0xF010) that came in the ENCRYPTED GroupInfo
     * of the Welcome this group was joined from, and forget it natively.
     *
     * <p>Returns the DECODED 32-byte value, or empty/null when there is none to collect. <b>Empty is
     * a normal answer</b>, not a failure: it is what a group we created ourselves returns, what an
     * external-commit join returns (§7.11.12.1 is Welcome-only and an external commit has no
     * Welcome), what a peer that does not do continuity returns, and what a second call returns.
     *
     * <p><b>This is the only read that can ever see one.</b> A Welcome's GroupInfo is encrypted
     * under a joiner secret, so its extensions are in the clear for exactly the length of the native
     * join — which is where the value is captured. {@link #nativeGroupExt} reads the GroupContext
     * and {@link #nativeGroupInfoExt} needs a serialized GroupInfo; both are structurally blind to
     * 0xF010 wherever they are pointed, and a probe built out of them produced a confident false
     * negative that got quoted as an answer twice (see the WELCOME-EXT headstone in
     * {@code MlsProviderTransport}).
     *
     * <p>The caller must persist what it collects — the native side keeps no copy.
     */
    static native byte[] nativeTakeWelcomeContinuityToken(long handle, byte[] groupId);

    /** Per-leaf (index, MSISDN, participant-key SPKI), length-prefixed. */
    static native byte[] nativeMemberParticipantKeys(long handle, byte[] groupId);

    /** The RFC-9420 KeyPackageRef of one MLSMessage-wrapped KeyPackage. */
    static native byte[] nativeKeyPackageRef(long handle, byte[] keyPackage);

    /** Len-prefixed list of the KeyPackageRefs a Welcome is sealed to. */
    static native byte[] nativeWelcomeKeyPackageRefs(long handle, byte[] welcome);
    static native byte[] nativeCreateGroup(long handle, int era, byte[] peerKeyPackage, byte[] groupIdOverride);
    static native byte[] nativeJoin(long handle, byte[] welcome);
    /** Welcome join WITH an out-of-band ratchet_tree (Apple add-member Welcome carries no
     *  ratchet_tree ext → plain join fails RatchetTreeNotFound). Returns the group_id, or null. */
    static native byte[] nativeJoinWithTree(long handle, byte[] welcome, byte[] ratchetTree);
    /** Join from a Welcome carrying NO ratchet_tree extension — RFC 9420 makes it OPTIONAL, so any
     *  conforming sender may omit it and deliver the tree out of band. {@code welcome} is the
     *  unwrapped bare Welcome; {@code blob} is the whole rawInner, which also carries the member
     *  LeafNodes forming the tree. Native locates + splices the tree, then process_welcome-with-tree.
     *  Returns group_id or null. (Was nativeJoinAppleKind5 — first seen on an iPhone, then
     *  device-proven against GOOGLE MESSAGES; it was never vendor-specific.) */
    static native byte[] nativeJoinTreelessWelcome(long handle, byte[] welcome, byte[] blob);

    /** Packed member validity table: {@code [u32 leafIndex][u64 notBefore][u64 notAfter]} per
     *  member, 20 bytes each, big-endian. Empty on error. See {@code ProdSession::member_validity}. */
    static native byte[] nativeMemberValidity(long handle, byte[] groupId);

    /**
     * Leaf certificate windows in a SERIALIZED RATCHET TREE — the SERVER's copy.
     *
     * <p>{@link #nativeMemberValidity} loads the group, so it reports the copy THIS DEVICE holds.
     * This one parses bytes and loads nothing, which is the only way to measure the copy the server
     * validates against. READ-ONLY: {@link #nativeJoinWithTree} takes the same bytes and MUTATES.
     *
     * <p>Wire, per leaf: {@code [u32 index][u64 notBefore][u64 notAfter][u32 msisdnLen][msisdn]}.
     */
    static native byte[] nativeTreeMemberValidity(long handle, byte[] ratchetTree);

    /**
     * Which leaf SIGNED a GroupInfo — {@code [u32 leafIndex]}, from the
     * GroupInfo bytes alone. On the anchor the SERVER stores, that is the member whose Commit
     * produced the epoch; on one this device just generated it is simply us.
     */
    static native byte[] nativeGroupInfoSigner(long handle, byte[] groupInfo);
    /** OUR OWN leaf's certificate window in this group next to the one the client holds:
     *  {@code [u32 leafIndex][u64 groupNb][u64 groupNa][u64 clientNb][u64 clientNa][u8 stale]},
     *  37 bytes, big-endian. Null/short on error. See {@code ProdSession::self_leaf_status} and
     *  {@link MlsSelfLeafStatus}. */
    static native byte[] nativeSelfLeafStatus(long handle, byte[] groupId);
    /** GSMA external-commit join from a peer's GroupInfo (+ optional out-of-band ratchet_tree).
     *  Returns len-prefixed {group_id, external_commit_message}; caller sends the commit. */
    static native byte[] nativeExternalJoin(long handle, byte[] groupInfo, byte[] ratchetTree);
    /**
     * Encrypt an application message.
     *
     * <p><b>The last parameter is a MESSAGE ID, not an AuthenticatedData blob.</b>
     * RCC.16 puts AAD construction in the ENGINE — {@code EncryptMessageRequest} has
     * no authenticated-data field and the host supplies only a message id via the request context.
     * The engine builds {@code [00 01][varint len][message_id][uint32 era][00]} itself, reading the
     * era from the group's own {@code 0xF001} extension rather than trusting a caller that may have
     * lost track of it.
     *
     * <p>The JNI signature is unchanged from when this carried a full AAD — only the MEANING of the
     * bytes changed — so a stale caller compiles fine and produces a wrong AAD. Everything in-tree
     * was migrated together; check this parameter first if a peer starts reporting
     * {@code KEY_GENERATION_MISMATCH}.
     */
    static native byte[] nativeEncrypt(long handle, byte[] groupId, byte[] plaintext,
            byte[] messageId);

    /**
     * §7.5.3.1 — tell the engine which message id the next {@code nativeProcess}/
     * {@code nativeProcessEx} is for, so it can compare against the inbound AAD and make
     * {@code MessageIdMismatch(9)} reachable from the engine, where RCC.16 puts it.
     *
     * <p>Passing {@code null} CLEARS it, which SKIPS the check rather than failing it — an
     * unmigrated caller must not start dropping every message.
     */
    static native int nativeSetRequestMessageId(byte[] messageId);

    /** Non-zero if the last processed application message's AAD id did not match. */
    static native int nativeLastMessageIdMismatch();

    /** {@code expected\0actual} behind a mismatch, for logging. Empty when there was none. */
    static native byte[] nativeLastMessageIdMismatchDetail();

    /**
     * Put a §10.3 resent-message component in the NEXT AAD the engine builds, instead of the absent
     * {@code 0x00}. One-shot. Supplying a COMPONENT is not the removed seam returning: §10.3 makes
     * the component the host's to choose while the AAD around it stays the engine's to build. Used
     * by the resent-component probe.
     */
    static native int nativeSetNextResentComponent(byte[] component);
    /** 4-byte big-endian application generation the next encrypt will stamp (peek, no advance). */
    static native byte[] nativeNextAppGen(long handle, byte[] groupId);
    /** 32-byte epoch_authenticator of the group's CURRENT epoch (RCC.16 §7.11). */
    static native byte[] nativeEpochAuth(long handle, byte[] groupId);
    /** 12-byte {@code [era u32 BE][epoch u64 BE]} — the group's CURRENT era (RCC.16 Era ext 0xF001,
     *  default 1) + RFC-9420 epoch counter. era stamps the SendMessage Era-ID; epoch is a diagnostic. */
    static native byte[] nativeEraEpoch(long handle, byte[] groupId);
    /**
     * Status of the most recent engine op ON THE CALLING THREAD: see {@link MlsSession.OpStatus}.
     *
     * <p>Read it immediately after the op, on the same thread. A JNI call runs on the calling Java
     * thread, so the engine's thread-local is this caller's — but any intervening engine call on the
     * same thread overwrites it.
     */
    static native int nativeLastStatus();

    /** {@code authenticated_data} of the last application message processed on this thread. */
    static native byte[] nativeLastAad();
    /**
     * The CERTIFIED MSISDN of the leaf that signed the last application message processed on this
     * thread. Empty means UNKNOWN, never a match.
     */
    static native byte[] nativeLastSenderMsisdn();

    /**
     * Largest group-state record written on this thread since the last read, in bytes; 0 if none.
     *
     * <p><b>Reading clears it</b>, so a caller that records after every op cannot double-count a
     * write from the previous one. Bucketed by {@code MlsMetrics.log2Bucket} and recorded against
     * {@code Bugle.Mls.ZinniaStateSize}.
     */
    static native long nativeTakeStateBytes();

    static native byte[] nativeProcess(long handle, byte[] groupId, byte[] wire);
    // ---- S2 group-mutation + resync + delete + status-tagged process ----
    /** Add a member: 6-record bundle [welcome,commit,groupInfo,tag,gid,tree]. */
    static native byte[] nativeAddMember(long handle, byte[] groupId, byte[] peerKeyPackage,
            byte[] messageId);

    /**
     * Add N members in ONE commit; {@code keyPackages} is length-prefixed (rework 9.1).
     *
     * <p>N devices means N key packages and N leaves, and they must land in a single commit: each
     * commit advances an epoch every member must apply, so adding them one at a time creates
     * intermediate epochs in which a participant is half-added.
     */
    static native byte[] nativeAddMembers(long handle, byte[] groupId, byte[] keyPackages,
            byte[] messageId);
    /** Remove a member by signature pubkey (empty = sole non-self member): 6 records, empty welcome. */
    static native byte[] nativeRemoveMember(long handle, byte[] groupId, byte[] memberSigPub,
            byte[] messageId);
    /**
     * Remove EVERY leaf certified to one MSISDN, in ONE commit: 6 records, empty welcome.
     *
     * <p>THE CORRECT SELECTOR for a group removal. {@link #nativeRemoveMember}'s empty-key contract
     * is "remove the sole other member", which on a group with more than two members removes an
     * arbitrary leaf — device-observed producing {@code mismatched-rcs-group-state} because the MLS
     * membership then disagreed with the RCS roster we had just changed. It is also the only
     * selector that is correct for a multi-device participant: one commit removes all their leaves,
     * where one-at-a-time creates an intermediate epoch in which they are half-removed.
     */
    static native byte[] nativeRemoveMemberByMsisdn(long handle, byte[] groupId, byte[] msisdn,
            byte[] messageId);
    /** Self-update/rekey: 6 records, empty welcome. */
    static native byte[] nativeCommitEndMls(long handle, byte[] groupId, byte[] messageId, boolean remove);

    /** Membership-preserving era advance (RCC.16 §8.3) — no KeyPackage list, no Welcome. */
    static native byte[] nativeCommitEraAdvance(long handle, byte[] groupId, byte[] messageId, int newEra);

    /** Probe: extension types present in a serialized GroupInfo, as [u16 type][u16 len] records. */
    static native byte[] nativeGroupInfoExtTypes(long handle, byte[] groupInfo);
    static native byte[] nativeGroupInfoExt(long handle, byte[] groupInfo, int extType);

    /** RCC.16 metadata commit carrying the KEYS + their commitments. */
    static native byte[] nativeCommitGroupMetadata(long handle, byte[] groupId, byte[] messageId,
            byte[] iconKey, byte[] iconCommitment, byte[] subjectKey, byte[] subjectCommitment);

    static native byte[] nativeCommitIconSubject(long handle, byte[] groupId, byte[] messageId,
            byte[] iconCommitment, byte[] subjectCommitment);

    /**
     * Create a group adding ALL members in the initial commit; {@code kps} is len-prefixed.
     *
     * <p>{@code advanceMode} is §9.7g's era-advance mode byte — 0 carry {@code end_mls} as-is,
     * 1 drop it (revival), 2 install it (phoenix). See {@link MlsAdvanceEraKind}.
     */
    static native byte[] nativeCreateGroupMulti(long handle, int era, byte[] keyPackages,
            byte[] groupIdOverride, byte[] carryGroupInfo, int advanceMode);

    /**
     * As above but with NO {@code era} parameter — the engine derives it and reports back
     *.
     *
     * <p>The missing parameter is the feature. The bundle carries two extra len-prefixed slots after
     * the usual six: {@code [6]} the u32 big-endian era the engine actually built at, {@code [7]} the
     * u32 big-endian {@code welcomeAction} naming what the operation was.
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
    /** Drop all cached by-reference proposals — see {@code MlsSession.clearPendingProposals}. */
    static native byte[] nativeClearPendingProposals(long handle, byte[] groupId);
    static native byte[] nativeSelfLeave(long handle, byte[] groupId, byte[] messageId);
    /** Defer-until-ACK: snapshot the group's persisted state before an optimistic self-update. */
    static native byte[] nativeExportGroupSnapshot(long handle, byte[] groupId);
    /** Defer-until-ACK: restore a snapshot (revert an un-ACKed self-update so the epoch doesn't drift). */
    static native byte[] nativeRestoreGroupSnapshot(long handle, byte[] groupId, byte[] snapshot);
    /** External-commit resync from a server GroupInfo (+ optional tree; removeLeafIndex &lt;0 = none):
     *  returns [group_id, external_commit]. */
    static native byte[] nativeExternalCommitResync(long handle, byte[] serverGroupInfo, byte[] ratchetTree, long removeLeafIndex);
    /** As {@code nativeSelfUpdate}, but the published GroupInfo CARRIES {@code external_pub} and the
     *  post-commit ratchet_tree is returned as the 6th record instead of an empty one. Needed because
     *  external_pub is COMMITTER-produced: without it, no resync external commit can ever be built
     *  against a group we commit in. */
    static native byte[] nativeSelfUpdateExtPub(long handle, byte[] groupId, byte[] messageId);
    /** Delete a group's persisted state (AHEAD-discard / PHOENIX reset): 1 byte, 1=ok. */
    static native byte[] nativeDeleteGroup(long handle, byte[] groupId);
    /** Status-tagged process: [status, payload...] — 0=APP,1=COMMIT,2=PROPOSAL,3=OTHER,7=FAILED. */
    static native byte[] nativeProcessEx(long handle, byte[] groupId, byte[] wire);
    /**
     * The REPEATED-RESULT form (rework 6.6, §10.5): a length-prefixed list whose first record is a
     * format version and whose remaining records are results, each stamped with the context it
     * belongs to. Decoded by {@link MlsEngineResult#decodeList}, which is where the format is
     * normatively described.
     */
    static native byte[] nativeProcessResults(long handle, byte[] groupId, byte[] wire,
            byte[] contextId);
    /**
     * The REPEATED-RESULT encrypt (rework 7.3): the ciphertext and, when {@code wantKeyUpdate}, a
     * piggybacked self-key-update commit, returned by ONE call in that order. Decoded by
     * {@link MlsEngineResult#decodeList}.
     */
    static native byte[] nativeEncryptResults(long handle, byte[] groupId, byte[] plaintext,
            byte[] messageId, byte[] contextId, boolean wantKeyUpdate);
    static native void nativeSessionClose(long handle);
}
