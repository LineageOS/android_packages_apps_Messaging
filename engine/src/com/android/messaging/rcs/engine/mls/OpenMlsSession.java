/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link MlsSession} over the native core ({@link OpenMlsNative}). Public because transport code in
 * other packages uses the extra methods it adds.
 */
public final class OpenMlsSession implements MlsSession {
    private final long handle;

    OpenMlsSession(long handle) { this.handle = handle; }

    @Override public byte[] generateKeyPackages(int count) {
        return OpenMlsNative.nativeGenerateKeyPackages(handle, count);
    }
    /**
     * One MLSMessage-wrapped KeyPackage carrying the RFC 9420 {@code last_resort} extension, for
     * the KDS last-resort slot.
     */
    public byte[] generateLastResortKeyPackage() {
        return OpenMlsNative.nativeGenerateLastResortKp(handle);
    }

    public byte[] keyPackageRef(final byte[] keyPackage) {
        if (keyPackage == null || keyPackage.length == 0) return null;
        return OpenMlsNative.nativeKeyPackageRef(handle, keyPackage);
    }

    public java.util.List<byte[]> welcomeKeyPackageRefs(final byte[] welcome) {
        if (welcome == null || welcome.length == 0) return java.util.Collections.emptyList();
        final byte[] packed = OpenMlsNative.nativeWelcomeKeyPackageRefs(handle, welcome);
        if (packed == null || packed.length == 0) return java.util.Collections.emptyList();
        return splitLenPrefixed(packed);
    }
    @Override public MlsGroupArtifacts createGroup(long era, byte[] peerKeyPackage) {
        return createGroupWithId(era, peerKeyPackage, null);
    }
    @Override public MlsGroupArtifacts createGroupWithId(long era, byte[] peerKeyPackage,
            byte[] groupIdOverride) {
        // A non-empty groupIdOverride reuses the server's group id (revival); otherwise a fresh
        // UUID.
        byte[] art = OpenMlsNative.nativeCreateGroup(handle, (int) era, peerKeyPackage,
                groupIdOverride == null ? new byte[0] : groupIdOverride);
        // The caller supplied the era, so the bundle stops at slot 5 and decodes era=-1 and
        // welcomeAction=null, distinguishing it from createGroupPlanned.
        return MlsArtifactBundle.decode(art);
    }
    @Override public byte[] join(byte[] welcome) { return OpenMlsNative.nativeJoin(handle,
            welcome); }

    /**
     * Welcome join with an out-of-band RFC 9420 ratchet tree, for a Welcome whose GroupInfo omits
     * the extension; null or empty uses the in-Welcome tree. Returns the group id, or null.
     */
    public byte[] joinWithTree(byte[] welcome, byte[] ratchetTree) {
        return OpenMlsNative.nativeJoinWithTree(handle, welcome, ratchetTree);
    }

    public byte[] joinTreelessWelcome(byte[] welcome, byte[] blob) {
        return OpenMlsNative.nativeJoinTreelessWelcome(handle, welcome, blob);
    }

    public java.util.Map<Integer, long[]> memberValidity(byte[] groupId) {
        final java.util.Map<Integer, long[]> out = new java.util.LinkedHashMap<>();
        final byte[] r = OpenMlsNative.nativeMemberValidity(handle, groupId);
        if (r == null) return out;
        for (int o = 0; o + 20 <= r.length; o += 20) {
            int idx = 0;
            for (int i = 0; i < 4; i++) idx = (idx << 8) | (r[o + i] & 0xFF);
            long nb = 0, na = 0;
            for (int i = 4; i < 12; i++) nb = (nb << 8) | (r[o + i] & 0xFFL);
            for (int i = 12; i < 20; i++) na = (na << 8) | (r[o + i] & 0xFFL);
            out.put(idx, new long[] { nb, na });
        }
        return out;
    }

    public long[] groupInfoSigner(final byte[] groupInfo) {
        if (groupInfo == null || groupInfo.length == 0) return null;
        final byte[] r = OpenMlsNative.nativeGroupInfoSigner(handle, groupInfo);
        if (r == null || r.length < 12) return null;
        long idx = 0, epoch = 0;
        for (int i = 0; i < 4; i++) idx = (idx << 8) | (r[i] & 0xFFL);
        for (int i = 4; i < 12; i++) epoch = (epoch << 8) | (r[i] & 0xFFL);
        return new long[] { idx, epoch };
    }

    public java.util.List<MlsTreeLeaf> treeMemberValidity(final byte[] ratchetTree) {
        final java.util.List<MlsTreeLeaf> out = new java.util.ArrayList<>();
        if (ratchetTree == null || ratchetTree.length == 0) return out;
        final byte[] r = OpenMlsNative.nativeTreeMemberValidity(handle, ratchetTree);
        if (r == null) return out;
        int o = 0;
        while (o + 24 <= r.length) {
            int idx = 0;
            for (int i = 0; i < 4; i++) idx = (idx << 8) | (r[o + i] & 0xFF);
            long nb = 0, na = 0;
            for (int i = 4; i < 12; i++) nb = (nb << 8) | (r[o + i] & 0xFFL);
            for (int i = 12; i < 20; i++) na = (na << 8) | (r[o + i] & 0xFFL);
            int mlen = 0;
            for (int i = 20; i < 24; i++) mlen = (mlen << 8) | (r[o + i] & 0xFF);
            // An overrunning length is a framing failure: return what was read.
            if (mlen < 0 || o + 24 + mlen > r.length) break;
            final String msisdn =
                    new String(r, o + 24, mlen, java.nio.charset.StandardCharsets.UTF_8);
            out.add(new MlsTreeLeaf(idx, nb, na, msisdn));
            o += 24 + mlen;
        }
        return out;
    }

    public MlsSelfLeafStatus selfLeafStatus(byte[] groupId) {
        return MlsSelfLeafStatus.parse(OpenMlsNative.nativeSelfLeafStatus(handle, groupId));
    }

    public java.util.Map<Integer, MlsParticipantKeyResync.Leaf> memberParticipantKeys(
            final byte[] groupId) {
        final java.util.Map<Integer, MlsParticipantKeyResync.Leaf> out =
                new java.util.LinkedHashMap<>();
        final byte[] r = OpenMlsNative.nativeMemberParticipantKeys(handle, groupId);
        if (r == null) return out;
        int o = 0;
        while (o + 4 <= r.length) {
            int idx = 0;
            for (int i = 0; i < 4; i++) idx = (idx << 8) | (r[o + i] & 0xFF);
            o += 4;
            final byte[] msisdn = readLenPrefixed(r, o);
            if (msisdn == null) break;
            o += 4 + msisdn.length;
            final byte[] key = readLenPrefixed(r, o);
            if (key == null) break;
            o += 4 + key.length;
            out.put(idx, new MlsParticipantKeyResync.Leaf(idx,
                    new String(msisdn, java.nio.charset.StandardCharsets.US_ASCII),
                    key.length == 0 ? "" : hexOf(key)));
        }
        return out;
    }

    /** {@code [u32 len][bytes]} at {@code off}, or null if it does not fit. */
    private static byte[] readLenPrefixed(final byte[] b, final int off) {
        if (off + 4 > b.length) return null;
        int n = 0;
        for (int i = 0; i < 4; i++) n = (n << 8) | (b[off + i] & 0xFF);
        if (n < 0 || off + 4 + n > b.length) return null;
        final byte[] out = new byte[n];
        System.arraycopy(b, off + 4, out, 0, n);
        return out;
    }

    private static String hexOf(final byte[] b) {
        final StringBuilder sb = new StringBuilder(b.length * 2);
        for (final byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                .append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /**
     * External-commit join from a peer-created group's GroupInfo and ratchet tree. The caller sends
     * {@link ExternalJoin#externalCommit}; null on failure.
     */
    public ExternalJoin externalJoin(byte[] groupInfo, byte[] ratchetTree) {
        final byte[] r = OpenMlsNative.nativeExternalJoin(handle, groupInfo, ratchetTree);
        if (r == null) return null;
        final List<byte[]> p = splitLenPrefixed(r);  // [group_id, external_commit]
        if (p.size() < 2 || p.get(0).length == 0 || p.get(1).length == 0) return null;
        return new ExternalJoin(p.get(0), p.get(1));
    }

    /** {group_id, external_commit MLS message} from {@link #externalJoin}. */
    public static final class ExternalJoin {
        public final byte[] groupId;
        public final byte[] externalCommit;
        ExternalJoin(byte[] groupId, byte[] externalCommit) {
            this.groupId = groupId; this.externalCommit = externalCommit;
        }
    }

    @Override public byte[] encrypt(byte[] groupId, byte[] plaintext) {
        return OpenMlsNative.nativeEncrypt(handle, groupId, plaintext, new byte[0]);
    }
    @Override public void setNextResentComponent(byte[] component) {
        OpenMlsNative.nativeSetNextResentComponent(component);
    }
    @Override public void setRequestMessageId(byte[] messageId) {
        OpenMlsNative.nativeSetRequestMessageId(messageId);
    }
    @Override public boolean lastMessageIdMismatch() {
        return OpenMlsNative.nativeLastMessageIdMismatch() != 0;
    }
    @Override public byte[] lastMessageIdMismatchDetail() {
        final byte[] d = OpenMlsNative.nativeLastMessageIdMismatchDetail();
        return d == null || d.length == 0 ? null : d;
    }
    @Override public byte[] encryptWithAad(byte[] groupId, byte[] plaintext, byte[] aad) {
        return OpenMlsNative.nativeEncrypt(handle, groupId, plaintext, aad == null ? new byte[0]
                : aad);
    }
    @Override public int nextAppGen(byte[] groupId) {
        final byte[] b = OpenMlsNative.nativeNextAppGen(handle, groupId);
        if (b == null || b.length != 4) return -1;
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }
    @Override public byte[] epochAuth(byte[] groupId) {
        return OpenMlsNative.nativeEpochAuth(handle, groupId);
    }
    @Override public byte[] eraEpoch(byte[] groupId) {
        return OpenMlsNative.nativeEraEpoch(handle, groupId);
    }
    @Override public byte[] process(byte[] groupId, byte[] wire) {
        return OpenMlsNative.nativeProcess(handle, groupId, wire);
    }
    @Override public MlsGroupArtifacts addMember(byte[] groupId, byte[] peerKeyPackage,
            byte[] aad) {
        return artifactsFrom(OpenMlsNative.nativeAddMember(handle, groupId, peerKeyPackage,
                aad == null ? new byte[0] : aad));
    }

    @Override public MlsGroupArtifacts addMembers(final byte[] groupId,
            final java.util.List<byte[]> peerKeyPackages, final byte[] aad) {
        if (peerKeyPackages == null || peerKeyPackages.isEmpty()) return null;
        int total = 0;
        for (final byte[] k : peerKeyPackages) total += 4 + (k == null ? 0 : k.length);
        final byte[] packed = new byte[total];
        int o = 0;
        for (final byte[] k : peerKeyPackages) {
            final int n = (k == null) ? 0 : k.length;
            packed[o++] = (byte) (n >>> 24); packed[o++] = (byte) (n >>> 16);
            packed[o++] = (byte) (n >>> 8);  packed[o++] = (byte) n;
            if (n > 0) { System.arraycopy(k, 0, packed, o, n); o += n; }
        }
        return artifactsFrom(OpenMlsNative.nativeAddMembers(handle, groupId, packed,
                aad == null ? new byte[0] : aad));
    }
    @Override public MlsGroupArtifacts removeMember(byte[] groupId, byte[] memberSigPub,
            byte[] aad) {
        return artifactsFrom(OpenMlsNative.nativeRemoveMember(handle, groupId, memberSigPub,
                aad == null ? new byte[0] : aad));
    }
    @Override public MlsGroupArtifacts removeMemberByMsisdn(byte[] groupId, String msisdn,
            byte[] aad) {
        if (msisdn == null || msisdn.isEmpty()) return null;
        return artifactsFrom(OpenMlsNative.nativeRemoveMemberByMsisdn(handle, groupId,
                msisdn.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                aad == null ? new byte[0] : aad));
    }
    @Override public MlsGroupArtifacts selfLeave(byte[] groupId, byte[] aad) {
        return artifactsFrom(OpenMlsNative.nativeSelfLeave(handle, groupId, aad == null ? new byte[0] : aad));
    }
    @Override public boolean kpIsLastResort(byte[] keyPackage) {
        if (keyPackage == null || keyPackage.length == 0) return false;
        final byte[] r = OpenMlsNative.nativeKpIsLastResort(handle, keyPackage);
        return r != null && r.length >= 1 && r[0] == 1;
    }
    @Override public OpStatus lastStatus() {
        return OpStatus.fromCode(OpenMlsNative.nativeLastStatus());
    }

    @Override public byte[] lastInboundAad() {
        final byte[] a = OpenMlsNative.nativeLastAad();
        return a == null ? new byte[0] : a;
    }

    @Override public String lastInboundSenderMsisdn() {
        final byte[] a = OpenMlsNative.nativeLastSenderMsisdn();
        return (a == null || a.length == 0) ? ""
                : new String(a, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override public long takeStateWriteBytes() {
        return OpenMlsNative.nativeTakeStateBytes();
    }

    /** Big-endian u64 at {@code off}. */
    private static long beLong(final byte[] b, final int off) {
        long v = 0;
        for (int i = off; i < off + 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }

    @Override public KeyPackageInfo inspectKeyPackage(byte[] keyPackage) {
        if (keyPackage == null || keyPackage.length == 0) return null;
        final byte[] r = OpenMlsNative.nativeKpInspect(handle, keyPackage);
        if (r == null || r.length < 9) return null;  // parse error or undatable KeyPackage
        long notAfter = 0;
        for (int i = 1; i < 9; i++) {
            notAfter = (notAfter << 8) | (r[i] & 0xFFL);
        }
        // not_after is an unsigned u64; a never-expiring lifetime (2^64-1, which RFC 9420 permits)
        // would wrap to -1 and read as expired. Clamp instead: safe for a floor check only.
        if (notAfter < 0) notAfter = Long.MAX_VALUE;
        // Layout: [0] last_resort | [1..9] LeafNode not_after | [9..11] the KeyPackage's cipher
        // suite | [11] n | [12..12+2n] advertised suites | then the certificate's
        // notBefore/notAfter (u64 each) | then the SAN MSISDN, last because it has no length. A
        // suite block that does not fit means an older layout (byte 9 would be an ASCII digit) and
        // leaves the suites unknown.
        int suiteFieldsEnd = 9;
        int kpSuite = 0;
        int[] suites = new int[0];
        if (r.length >= 12) {
            final int n = r[11] & 0xFF;
            if (12 + 2 * n <= r.length) {
                kpSuite = ((r[9] & 0xFF) << 8) | (r[10] & 0xFF);
                suites = new int[n];
                for (int i = 0; i < n; i++) {
                    suites[i] = ((r[12 + 2 * i] & 0xFF) << 8) | (r[13 + 2 * i] & 0xFF);
                }
                suiteFieldsEnd = 12 + 2 * n;
            }
        }
        // The certificate's own window (what the server checks), unlike the LeafNode lifetime
        // above. A Unix second has three leading zero bytes, which an ASCII MSISDN cannot, so an
        // older layout is recognised and leaves the window unknown (0).
        int certFieldsEnd = suiteFieldsEnd;
        long certNotBefore = 0L;
        long certNotAfter = 0L;
        if (r.length >= suiteFieldsEnd + 16
                && r[suiteFieldsEnd] == 0 && r[suiteFieldsEnd + 1] == 0
                && r[suiteFieldsEnd + 2] == 0) {
            certNotBefore = beLong(r, suiteFieldsEnd);
            certNotAfter = beLong(r, suiteFieldsEnd + 8);
            certFieldsEnd = suiteFieldsEnd + 16;
        }
        // The rest is the SAN tel: identity as bare E.164, or empty.
        final String msisdn = (r.length > certFieldsEnd)
                ? new String(r, certFieldsEnd, r.length - certFieldsEnd,
                        java.nio.charset.StandardCharsets.US_ASCII) : "";
        return new KeyPackageInfo(r[0] == 1, notAfter, msisdn, kpSuite, suites,
                certNotBefore, certNotAfter);
    }
    @Override public MlsGroupArtifacts commitEndMls(byte[] groupId, byte[] aad, boolean remove) {
        return artifactsFrom(OpenMlsNative.nativeCommitEndMls(handle, groupId,
                aad == null ? new byte[0] : aad, remove));
    }

    @Override public MlsGroupArtifacts commitEraAdvance(byte[] groupId, byte[] aad, int newEra) {
        return artifactsFrom(OpenMlsNative.nativeCommitEraAdvance(handle, groupId,
                aad == null ? new byte[0] : aad, newEra));
    }

    @Override public MlsGroupArtifacts commitGroupMetadata(byte[] groupId, byte[] aad,
            byte[] iconKey, byte[] iconCommitment, byte[] subjectKey, byte[] subjectCommitment) {
        return artifactsFrom(OpenMlsNative.nativeCommitGroupMetadata(handle, groupId,
                aad == null ? new byte[0] : aad,
                iconKey == null ? new byte[0] : iconKey,
                iconCommitment == null ? new byte[0] : iconCommitment,
                subjectKey == null ? new byte[0] : subjectKey,
                subjectCommitment == null ? new byte[0] : subjectCommitment));
    }
    @Override public MlsGroupArtifacts commitIconSubject(byte[] groupId, byte[] aad,
            byte[] iconCommitment, byte[] subjectCommitment) {
        return artifactsFrom(OpenMlsNative.nativeCommitIconSubject(handle, groupId,
                aad == null ? new byte[0] : aad,
                iconCommitment == null ? new byte[0] : iconCommitment,
                subjectCommitment == null ? new byte[0] : subjectCommitment));
    }

    @Override public MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> kps,
            byte[] groupIdOverride) {
        return createGroupMulti(era, kps, groupIdOverride, null);
    }

    @Override public MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> kps,
            byte[] groupIdOverride, byte[] carryGroupInfo) {
        return createGroupMulti(era, kps, groupIdOverride, carryGroupInfo,
                MlsAdvanceEraKind.NORMAL);
    }

    @Override public MlsGroupArtifacts createGroupMulti(long era, java.util.List<byte[]> kps,
            byte[] groupIdOverride, byte[] carryGroupInfo, MlsAdvanceEraKind kind) {
        if (kps == null || kps.isEmpty()) return null;
        int total = 0;
        for (final byte[] k : kps) total += 4 + (k == null ? 0 : k.length);
        final byte[] packed = new byte[total];
        int o = 0;
        for (final byte[] k : kps) {
            final int n = (k == null) ? 0 : k.length;
            packed[o++] = (byte) (n >>> 24); packed[o++] = (byte) (n >>> 16);
            packed[o++] = (byte) (n >>> 8);  packed[o++] = (byte) n;
            if (n > 0) { System.arraycopy(k, 0, packed, o, n); o += n; }
        }
        return artifactsFrom(OpenMlsNative.nativeCreateGroupMulti(handle, (int) era, packed,
                groupIdOverride == null ? new byte[0] : groupIdOverride,
                carryGroupInfo == null ? new byte[0] : carryGroupInfo,
                (kind == null ? MlsAdvanceEraKind.NORMAL : kind).mode));
    }

    @Override public MlsGroupArtifacts createGroupPlanned(final java.util.List<byte[]> kps,
            final byte[] groupIdOverride, final byte[] carryGroupInfo,
            final MlsAdvanceEraKind kind) {
        if (kps == null || kps.isEmpty()) return null;
        return artifactsFrom(OpenMlsNative.nativeCreateGroupPlanned(handle, joinLenPrefixed(kps),
                groupIdOverride == null ? new byte[0] : groupIdOverride,
                carryGroupInfo == null ? new byte[0] : carryGroupInfo,
                (kind == null ? MlsAdvanceEraKind.NORMAL : kind).mode));
    }

    @Override public byte[] groupInfoExtTypes(byte[] groupInfo) {
        if (groupInfo == null || groupInfo.length == 0) return new byte[0];
        final byte[] r = OpenMlsNative.nativeGroupInfoExtTypes(handle, groupInfo);
        return r == null ? new byte[0] : r;
    }

    @Override public byte[] groupInfoExt(byte[] groupInfo, int extType) {
        if (groupInfo == null || groupInfo.length == 0) return new byte[0];
        final byte[] r = OpenMlsNative.nativeGroupInfoExt(handle, groupInfo, extType);
        return r == null ? new byte[0] : r;
    }

    /**
     * One extension of any type from either extension list of a serialised GroupInfo; continuity
     * code points are returned with their inner varint stripped, others raw. Empty means absent, so
     * this must not refuse types.
     */
    @Override public byte[] groupInfoContinuity(final byte[] groupInfo, final int extType) {
        if (groupInfo == null) return new byte[0];
        final byte[] v = OpenMlsNative.nativeGroupInfoContinuity(handle, groupInfo, extType);
        return v == null ? new byte[0] : v;
    }

    @Override public byte[] groupInfoWithContinuity(final byte[] groupId, final byte[] commitment,
            final boolean withTree) {
        if (groupId == null) return new byte[0];
        final byte[] v = OpenMlsNative.nativeGroupInfoWithContinuity(
                handle, groupId, commitment == null ? new byte[0] : commitment, withTree);
        return v == null ? new byte[0] : v;
    }

    @Override public byte[] takeWelcomeContinuityToken(final byte[] groupId) {
        if (groupId == null || groupId.length == 0) return new byte[0];
        final byte[] v = OpenMlsNative.nativeTakeWelcomeContinuityToken(handle, groupId);
        return v == null ? new byte[0] : v;
    }

    @Override public byte[] groupExt(byte[] groupId, int extType) {
        final byte[] r = OpenMlsNative.nativeGroupExt(handle, groupId, extType);
        return r == null ? new byte[0] : r;
    }

    @Override public boolean endMlsPresent(byte[] groupId) {
        final byte[] r = OpenMlsNative.nativeEndMlsPresent(handle, groupId);
        return r != null && r.length > 0 && r[0] != 0;
    }

    @Override public byte[] rcsSign(byte[] groupId, byte[] derivedContent) {
        final byte[] r = OpenMlsNative.nativeRcsSign(handle, groupId,
                derivedContent == null ? new byte[0] : derivedContent);
        return (r == null || r.length == 0) ? null : r;
    }

    @Override public byte[] rcsVerify(byte[] groupId, byte[] publicMessage) {
        if (publicMessage == null || publicMessage.length == 0) return null;
        final byte[] r = OpenMlsNative.nativeRcsVerify(handle, groupId, publicMessage);
        return (r == null || r.length < 4) ? null : r;
    }

    @Override public boolean commitRequired(byte[] groupId) {
        final byte[] r = OpenMlsNative.nativeCommitRequired(handle, groupId);
        return r != null && r.length > 0 && r[0] != 0;
    }

    @Override public MlsGroupArtifacts selfUpdate(byte[] groupId, byte[] aad) {
        return artifactsFrom(OpenMlsNative.nativeSelfUpdate(handle, groupId, aad == null ? new byte[0] : aad));
    }
    @Override public MlsGroupArtifacts selfUpdateExtPub(byte[] groupId, byte[] aad) {
        return artifactsFrom(OpenMlsNative.nativeSelfUpdateExtPub(handle, groupId, aad == null
                ? new byte[0] : aad));
    }
    @Override public ExternalCommit externalCommitResync(byte[] serverGroupInfo, byte[] ratchetTree,
            long removeLeafIndex) {
        final byte[] r = OpenMlsNative.nativeExternalCommitResync(
                handle, serverGroupInfo, ratchetTree == null ? new byte[0] : ratchetTree,
                removeLeafIndex);
        if (r == null) return null;
        // [group_id, external_commit, groupInfo, epochAuth, ratchet_tree]
        final List<byte[]> p = splitLenPrefixed(r);
        if (p.size() < 5 || p.get(0).length == 0 || p.get(1).length == 0
                || p.get(2).length == 0 || p.get(3).length == 0 || p.get(4).length
                == 0) return null;
        return new ExternalCommit(p.get(0), p.get(1), p.get(2), p.get(3), p.get(4));
    }
    @Override public boolean deleteGroup(byte[] groupId) {
        final byte[] r = OpenMlsNative.nativeDeleteGroup(handle, groupId);
        return r != null && r.length >= 1 && r[0] == 1;
    }
    @Override public byte[] exportGroupSnapshot(byte[] groupId) {
        final byte[] r = OpenMlsNative.nativeExportGroupSnapshot(handle, groupId);
        return (r != null && r.length > 0) ? r : null;
    }
    @Override public boolean restoreGroupSnapshot(byte[] groupId, byte[] snapshot) {
        if (snapshot == null || snapshot.length == 0) return false;
        final byte[] r = OpenMlsNative.nativeRestoreGroupSnapshot(handle, groupId, snapshot);
        return r != null && r.length >= 1 && r[0] == 1;
    }
    @Override public ProcResult processEx(byte[] groupId, byte[] wire) {
        final byte[] r = OpenMlsNative.nativeProcessEx(handle, groupId, wire);
        if (r == null || r.length == 0) return new ProcResult(7, new byte[0]);  // failed
        final int status = r[0] & 0xff;
        // Status 2 (proposal) carries the proposal type in bytes 1..2 instead of a payload.
        if (status == 2) {
            final int type = (r.length >= 3) ? (((r[1] & 0xff) << 8) | (r[2] & 0xff)) : -1;
            return new ProcResult(status, new byte[0], type);
        }
        final byte[] payload = r.length > 1 ? java.util.Arrays.copyOfRange(r, 1, r.length)
                : new byte[0];
        return new ProcResult(status, payload);
    }
    @Override public List<MlsEngineResult> processResults(byte[] groupId, byte[] wire,
            String contextId) {
        final byte[] ctx = (contextId == null ? "" : contextId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] r = OpenMlsNative.nativeProcessResults(handle, groupId, wire, ctx);
        if (r == null || r.length == 0) {
            // A null return is the native side refusing: one malformed result, keeping an empty
            // list for the engine genuinely returning nothing.
            return java.util.Collections.singletonList(new MlsEngineResult(
                    contextId, /*status=*/ 7, /*proposalType=*/ -1, groupId, new byte[0]));
        }
        return MlsEngineResult.decodeList(r);
    }
    @Override public List<MlsEngineResult> encryptResults(byte[] groupId, byte[] plaintext,
            byte[] aad, String contextId, boolean wantKeyUpdate) {
        final byte[] ctx = (contextId == null ? "" : contextId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] r = OpenMlsNative.nativeEncryptResults(handle, groupId, plaintext,
                aad == null ? new byte[0] : aad, ctx, wantKeyUpdate);
        // Empty, not a synthesised failure: nothing was encrypted and no generation consumed, and a
        // fake element could pass a caller's "got a ciphertext" test.
        if (r == null || r.length == 0) return java.util.Collections.emptyList();
        return MlsEngineResult.decodeList(r);
    }
    @Override public boolean clearPendingProposals(byte[] groupId) {
        final byte[] r = OpenMlsNative.nativeClearPendingProposals(handle, groupId);
        return r != null && r.length >= 1 && r[0] == 1;
    }
    /** The slot layout is defined, and host-tested, in {@link MlsArtifactBundle}. */
    private static MlsGroupArtifacts artifactsFrom(byte[] bundle) {
        return MlsArtifactBundle.decode(bundle);
    }
    @Override public void close() { OpenMlsNative.nativeSessionClose(handle); }

    /** The first KeyPackage of a length-prefixed pool. */
    public static byte[] firstKeyPackage(byte[] pool) {
        List<byte[]> p = splitLenPrefixed(pool);
        return p.isEmpty() ? null : p.get(0);
    }
    static byte[] at(List<byte[]> l, int i) { return i < l.size() ? l.get(i) : new byte[0]; }
    /** Delegates to {@link MlsArtifactBundle#splitLenPrefixed}, which is host-tested. */
    public static List<byte[]> splitLenPrefixed(byte[] b) {
        List<byte[]> out = new ArrayList<>();
        int i = 0;
        while (b != null && i + 4 <= b.length) {
            int n = ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
            if (n < 0 || i + 4 + n > b.length) break;
            byte[] rec = new byte[n];
            System.arraycopy(b, i + 4, rec, 0, n);
            out.add(rec);
            i += 4 + n;
        }
        return out;
    }
    /** Delegates to {@link MlsArtifactBundle#joinLenPrefixed}, which is host-tested. */
    public static byte[] joinLenPrefixed(List<byte[]> parts) {
        int total = 0;
        for (byte[] p : parts) total += 4 + (p == null ? 0 : p.length);
        byte[] out = new byte[total];
        int i = 0;
        for (byte[] p : parts) {
            int n = p == null ? 0 : p.length;
            out[i] = (byte) (n >>> 24); out[i + 1] = (byte) (n >>> 16);
            out[i + 2] = (byte) (n >>> 8); out[i + 3] = (byte) n;
            if (n > 0) System.arraycopy(p, 0, out, i + 4, n);
            i += 4 + n;
        }
        return out;
    }
}
