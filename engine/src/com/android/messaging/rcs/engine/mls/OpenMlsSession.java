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
import java.util.List;

/** {@link MlsSession} backed by the OpenMLS (mls-rs) native op surface via {@link OpenMlsNative}. */
// Public: messaging2's carrier/lab glue (MlsCarrierTransport, MlsEnrollDebugReceiver,
// MlsSelfTestReceiver) lives in a DIFFERENT package and consumes this directly, so package-private
// is no longer viable now that both apps compile the same engine.
public final class OpenMlsSession implements MlsSession {
    private final long handle;

    OpenMlsSession(long handle) { this.handle = handle; }

    @Override public byte[] generateKeyPackages(int count) {
        return OpenMlsNative.nativeGenerateKeyPackages(handle, count);
    }
    /** One MLSMessage-wrapped last-resort KeyPackage (carries the RFC-9420 last_resort ext) for the
     *  KDS UploadKeyPackages last-resort slot. Not on the neutral {@link MlsSession} interface — only the
     *  OpenMLS uploader needs it. */
    public byte[] generateLastResortKeyPackage() {
        return OpenMlsNative.nativeGenerateLastResortKp(handle);
    }

    /**
     * The RFC-9420 {@code KeyPackageRef} of one MLSMessage-wrapped KeyPackage.
     *
     * <p>{@code RefHash("MLS 1.0 KeyPackage Reference", KeyPackage)} — derived from the package
     * bytes, so it is stable across processes and a published set stays meaningful after a restart.
     * Not on the neutral {@link MlsSession} interface: only the KP bookkeeping needs it.
     *
     * @return the ref, or null/empty if the input was not a KeyPackage
     */
    public byte[] keyPackageRef(final byte[] keyPackage) {
        if (keyPackage == null || keyPackage.length == 0) return null;
        return OpenMlsNative.nativeKeyPackageRef(handle, keyPackage);
    }

    /**
     * The KeyPackageRefs a Welcome is SEALED TO — one per {@code EncryptedGroupSecrets} entry
     *.
     *
     * <p>A Welcome carries one entry per ADDED member, so in a multi-add only one of these is ours.
     * Callers must cross off the INTERSECTION with what they published, never the whole list.
     */
    public java.util.List<byte[]> welcomeKeyPackageRefs(final byte[] welcome) {
        if (welcome == null || welcome.length == 0) return java.util.Collections.emptyList();
        final byte[] packed = OpenMlsNative.nativeWelcomeKeyPackageRefs(handle, welcome);
        if (packed == null || packed.length == 0) return java.util.Collections.emptyList();
        return splitLenPrefixed(packed);
    }
    @Override public MlsGroupArtifacts createGroup(long era, byte[] peerKeyPackage) {
        return createGroupWithId(era, peerKeyPackage, null);
    }
    @Override public MlsGroupArtifacts createGroupWithId(long era, byte[] peerKeyPackage, byte[] groupIdOverride) {
        // groupIdOverride: reuse the server's existing rcs group_id for a REVIVE (era-advancement
        // CREATE); null/empty ⟹ mint a fresh UUID (normal create).
        byte[] art = OpenMlsNative.nativeCreateGroup(handle, (int) era, peerKeyPackage,
                groupIdOverride == null ? new byte[0] : groupIdOverride);
        // The era-TAKING entry point, so the bundle stops at slot 5 and decodes to era=-1 /
        // welcomeAction=null: the caller already holds the number it supplied, and echoing it back
        // would make this indistinguishable from createGroupPlanned's derived answer.
        return MlsArtifactBundle.decode(art);
    }
    @Override public byte[] join(byte[] welcome) { return OpenMlsNative.nativeJoin(handle, welcome); }

    /** Welcome join WITH an out-of-band ratchet_tree — the Apple add-member path, where the
     *  kind=5 Welcome's group_info omits the ratchet_tree ext (plain {@link #join} →
     *  RatchetTreeNotFound). {@code ratchetTree} is the exported RFC-9420 RatchetTree extracted from
     *  the same kind=5 blob; null/empty falls back to the in-Welcome tree. Returns group_id or null.
     *  Not on the neutral {@link MlsSession} interface — only the OpenMLS path needs it. */
    public byte[] joinWithTree(byte[] welcome, byte[] ratchetTree) {
        return OpenMlsNative.nativeJoinWithTree(handle, welcome, ratchetTree);
    }

    /**
     * Join from a Welcome that carries NO {@code ratchet_tree} extension, by splicing the tree out of
     * the LeafNodes shipped beside it. {@code welcome} = the unwrapped bare Welcome; {@code blob} =
     * the whole rawInner, which also carries the canonical member LeafNodes. Native locates and
     * splices the tree, then does process_welcome-with-tree. Returns group_id or null. Only the
     * OpenMLS path implements this.
     *
     * <p><b>RENAMED 2026-08-06 — it was {@code joinAppleKind5}.</b> The old name recorded where the
     * shape was first seen (an iPhone add-member, kind=5, 2026-07-23) and read as a vendor branch,
     * which it never was. The condition is protocol-general: RFC 9420 makes {@code ratchet_tree} an
     * OPTIONAL GroupInfo extension, so ANY conforming sender may omit it and deliver the tree out of
     * band — mls-rs models exactly that as {@code join_group(None, …)} vs
     * {@code join_group(Some(tree), …)}. There is no "Apple variant" of MLS in play; this is RCC.16
     * over RFC 9420 throughout.
     *
     * <p>Device-proven not-Apple on 2026-08-04: this is the path that joined us to a group created by
     * GOOGLE MESSAGES (a 9529B kind=47 Welcome). The rename was deferred once on the
     * grounds that the JNI C symbol would need regenerating in lockstep — true, and done: the Rust
     * export, the C bridge and the native declaration all moved together.
     */
    public byte[] joinTreelessWelcome(byte[] welcome, byte[] blob) {
        return OpenMlsNative.nativeJoinTreelessWelcome(handle, welcome, blob);
    }

    /**
     * Every member's certificate validity window, as {@code leafIndex -> {notBefore, notAfter}} in
     * epoch seconds. Empty map if the group cannot be read.
     *
     * <p>Fills {@link MlsConversationRecord}'s row 16, which has existed since the record was defined
     * and which nothing has ever written — on a live device it reads {@code memberValidity=0}. It is
     * the input §9.7's expiry refresh needs, and therefore the input
     * {@link MlsMaintenancePolicy#evaluate} had no caller able to supply.
     *
     * <p>A member whose credential is not X.509, or whose leaf will not parse, comes back as
     * {@code {0, 0}} rather than being dropped. "Could not read this member" must stay
     * distinguishable from "this member is fine" — collapsing them is how a stale roster hides.
     */
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

    /**
     * Every leaf's certificate window in the SERVER's ratchet tree.
     *
     * <p>{@link #memberValidity} loads the group and therefore reports the copy THIS DEVICE holds.
     * The rule is that success is measured on the group, and we have measured one
     * device at epoch 1 against a server at epoch 3 and epoch 24 — so the local answer can describe
     * a copy the server does not hold, in EITHER direction. This parses the bytes
     * {@code GetMlsGroupInfo} already returns and loads nothing.
     *
     * <p>The alternative available before this — claiming a peer's KeyPackage — spends one from
     * their pool and measures their PUBLISHED POOL rather than their IN-GROUP LEAF, which is the
     * conflation the floor checks are about.
     *
     * <p>An EMPTY list means the engine returned nothing: either the tree would not parse or it was
     * empty. That is NOT "the roster is empty" and callers must not render it as one — the native
     * side refuses an empty tree with an error rather than returning zero leaves.
     */
    /**
     * Which leaf SIGNED a GroupInfo — fork attribution.
     *
     * <p>On the anchor the SERVER stores this is the member whose Commit produced the epoch, which
     * is the question "whose side of the fork does the server descend from?". Resolve it through
     * {@link #treeMemberValidity}'s per-leaf MSISDN table and the committer is NAMED rather than
     * inferred — matching certificate windows instead identifies a CERTIFICATE, and lines re-minted
     * in one batch share triples, which is the aliasing that has already cost us an identification.
     *
     * <p>Needs no group loaded and no state for the signer's own device, which is what makes it
     * usable on an anchor nobody holds the state for.
     *
     * <p><b>The EPOCH comes back with it, and a caller that ignores it misreads the signer.</b> The
     * provider fetches this pack ANCHORED AT OUR OWN era and epoch authenticator, and its own source
     * notes the returned bundle's authenticator "equals the anchor we asked with" — so a reader cannot
     * assume the bundle describes the server's CURRENT epoch. Compare the returned epoch against
     * the one you hold: higher, and the signer is a committer ahead of us; EQUAL TO OURS, it is our
     * own anchor handed back and the signer is evidence about nobody.
     *
     * @return {@code {leafIndex, epoch}}, or {@code null} when the bytes are absent or unreadable.
     *     Never {@code {0, 0}} for "we could not tell" — 0 is a real leaf index and the creator's,
     *     so a sentinel colliding with it would name an innocent member.
     */
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
            // A length that overruns the buffer is a FRAMING failure, not a short roster: stop and
            // report what was read rather than inventing a leaf or throwing away the whole answer.
            if (mlen < 0 || o + 24 + mlen > r.length) break;
            final String msisdn = new String(r, o + 24, mlen, java.nio.charset.StandardCharsets.UTF_8);
            out.add(new MlsTreeLeaf(idx, nb, na, msisdn));
            o += 24 + mlen;
        }
        return out;
    }

    /**
     * OUR leaf's credential in this group next to the one this client holds.
     *
     * <p>Answers the one question {@link #memberValidity} cannot: that reports the WHOLE roster keyed
     * by leaf index and never says which index is ours, so "is the group's copy of MY credential the
     * one I now hold?" could only be approximated by matching MSISDNs across two calls. This reads
     * both certificates in one place, where they are both in hand.
     *
     * @return the status, or {@code null} when the group cannot be read or the engine predates this
     *         call — which is NOT "our credential is current", and a caller must not treat it as one
     */
    public MlsSelfLeafStatus selfLeafStatus(byte[] groupId) {
        return MlsSelfLeafStatus.parse(OpenMlsNative.nativeSelfLeafStatus(handle, groupId));
    }

    /**
     * Per-leaf {@code (index, MSISDN, participant-key SPKI)} for every member of {@code groupId} —
     * the input {@link MlsParticipantKeyResync#plan} has been waiting for.
     *
     * <p>The participant key is RCC.16 A.3.8's ParticipantInformation SPKI: a key minted per
     * participant and distinct from the leaf's own certified key, which is what makes it the
     * "which participant key signed this leaf" identity the resync needs.
     *
     * <p>A leaf we could not parse comes back with an EMPTY key rather than being dropped. That is
     * load-bearing: {@code plan()} treats an empty signing key as "we did not look" and refuses to
     * remove the leaf, so an unreadable certificate costs a missed cleanup and never an
     * unrecoverable removal. Shortening the roster here would move that decision somewhere it is
     * not documented.
     *
     * @return leaf index → {@code Leaf}, in roster order
     */
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

    /** GSMA external-commit join (RFC-9420 External Commit) from a peer-created group's GroupInfo —
     *  the inbound path an iPhone uses (it delivers a GroupInfo + plaintext ratchet_tree, not a
     *  Welcome). Returns {groupId, externalCommit} where externalCommit is the MLS message to SEND
     *  back so the group applies our join; null on failure. Not on the neutral {@link MlsSession}
     *  interface — only the OpenMLS path implements external-commit. */
    public ExternalJoin externalJoin(byte[] groupInfo, byte[] ratchetTree) {
        final byte[] r = OpenMlsNative.nativeExternalJoin(handle, groupInfo, ratchetTree);
        if (r == null) return null;
        final List<byte[]> p = splitLenPrefixed(r);   // [group_id, external_commit]
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
    @Override public byte[] encryptWithAad(byte[] groupId, byte[] plaintext, byte[] aad) {
        return OpenMlsNative.nativeEncrypt(handle, groupId, plaintext, aad == null ? new byte[0] : aad);
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
    @Override public MlsGroupArtifacts addMember(byte[] groupId, byte[] peerKeyPackage, byte[] aad) {
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
    @Override public MlsGroupArtifacts removeMember(byte[] groupId, byte[] memberSigPub, byte[] aad) {
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

    /** One big-endian u64 at {@code off}. Certificate seconds never approach the sign bit. */
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
        if (r == null || r.length < 9) return null;      // parse error / undatable KP
        long notAfter = 0;
        for (int i = 1; i < 9; i++) {
            notAfter = (notAfter << 8) | (r[i] & 0xFFL);
        }
        // not_after is an RFC 9420 uint64 and Java's long is SIGNED. A peer that publishes a
        // never-expiring Lifetime (not_after = 2^64-1, which RFC 9420 permits and Apple uses) wraps
        // to -1 here — and a lifetime floor then reads "never expires" as "expired in 1970" and
        // refuses the KeyPackage. Device-proven 2026-07-29: claiming the iPhone's KP logged
        // "expires in -20663d (not_after=-1)", which is the Unix epoch, not a date anyone set.
        // Anything that does not fit a signed long is a date far beyond any real expiry, so clamp
        // rather than wrap. Clamping is safe for a FLOOR check (it can only make a key look more
        // valid, and a key valid forever genuinely passes a "≥30 days remaining" test); it would NOT
        // be safe to clamp a value we were treating as an upper bound.
        if (notAfter < 0) notAfter = Long.MAX_VALUE;
        // LAYOUT (kp_inspect):
        //   [0] last_resort | [1..9] the LeafNode Lifetime's not_after | [9..11] the KP's own
        //   cipher_suite | [11] n | [12..12+2n] the n advertised suite code points |
        //   [12+2n..+16] the leaf CERTIFICATE's notBefore/notAfter | [12+2n+16..] the SAN MSISDN.
        // The MSISDN is last because it is the only field with no length of its own.
        //
        // A SHORT OR IMPLAUSIBLE RECORD FALLS BACK TO THE OLD LAYOUT rather than misreading one.
        // The .so and this class ship together, so a mismatch should be impossible — but if it ever
        // happens, byte 9 is the first character of the MSISDN (an ASCII digit, 0x30-0x39), which
        // would read as a count of 48+ suites and need >100 bytes that are not there. Requiring the
        // suite block to FIT is therefore a reliable discriminator, and the fallback degrades to
        // "suites unknown" — which is what a caller must treat an empty list as anyway.
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
        // THE LEAF CERTIFICATE'S OWN VALIDITY WINDOW — the clock the SERVER measures,
        // as opposed to `notAfter` above, which is the LeafNode Lifetime the engine minted.
        //
        // SAME DISCRIMINATOR DISCIPLINE AS THE SUITE BLOCK, and it is a real one rather than a
        // length check: a Unix second is well under 2^40, so the top THREE bytes of each of these
        // big-endian u64s are 0x00 — and 0x00 cannot appear in the ASCII E.164 that occupied this
        // offset before. So a build whose .so predates this field is recognised rather than
        // misread, and degrades to "certificate window unknown", which every reader must already
        // treat as no evidence. The unreadable case the engine reports (0/0) satisfies the same
        // test, which is correct: it decodes to 0 and means exactly that.
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
        // Everything past the certificate window is the leaf certificate's SAN tel: identity as
        // bare E.164 ASCII, empty when the credential is not X.509 or carries no tel: URI.
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
     * Read one extension out of a serialized GroupInfo — <b>any</b> type, from either of its two
     * extension lists.
     *
     * <p><b>The continuity-only guard that used to be here returned {@code new byte[0]} for every
     * other code point WITHOUT CALLING NATIVE, and callers log an empty result as "ABSENT".</b> So
     * {@code external_pub(0x0004)} and {@code external_senders(0x0005)} — which
     * {@code MlsProviderTransport}'s external-join viability probe reads through this method, saying
     * so in its own comment — reported ABSENT unconditionally, whatever the server actually sent.
     * That constant is the entire evidence base for the claim that "external
     * commit cannot rescue a behind member".
     *
     * <p>The comment two lines above that call site reads <i>"A GUARD THAT CANNOT SEE MUST SAY SO,
     * NOT VOTE NO"</i>. It was right, and the code under it voted no. Fourth probe found this way in
     * one day (see the WELCOME-EXT headstone in
     * {@code MlsProviderTransport}), so the rule is worth stating as code and not only as prose:
     * <b>a reader that refuses a type must not share a return value with "not present".</b>
     *
     * <p>The native side handles arbitrary types (continuity code points get their inner varint
     * stripped, everything else comes back raw) and logs which list answered.
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
        return artifactsFrom(OpenMlsNative.nativeSelfUpdateExtPub(handle, groupId, aad == null ? new byte[0] : aad));
    }
    @Override public ExternalCommit externalCommitResync(byte[] serverGroupInfo, byte[] ratchetTree,
            long removeLeafIndex) {
        final byte[] r = OpenMlsNative.nativeExternalCommitResync(
                handle, serverGroupInfo, ratchetTree == null ? new byte[0] : ratchetTree, removeLeafIndex);
        if (r == null) return null;
        // [group_id, external_commit, groupInfo, epochAuth, ratchet_tree]
        final List<byte[]> p = splitLenPrefixed(r);
        if (p.size() < 5 || p.get(0).length == 0 || p.get(1).length == 0
                || p.get(2).length == 0 || p.get(3).length == 0 || p.get(4).length == 0) return null;
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
        if (r == null || r.length == 0) return new ProcResult(7, new byte[0]);   // FAILED
        final int status = r[0] & 0xff;
        // status 2 (PROPOSAL) carries the proposal TYPE in bytes 1..3 rather than a payload — the
        // caller has to know whether it can honour the proposal before sweeping it.
        if (status == 2) {
            final int type = (r.length >= 3) ? (((r[1] & 0xff) << 8) | (r[2] & 0xff)) : -1;
            return new ProcResult(status, new byte[0], type);
        }
        final byte[] payload = r.length > 1 ? java.util.Arrays.copyOfRange(r, 1, r.length) : new byte[0];
        return new ProcResult(status, payload);
    }
    @Override public List<MlsEngineResult> processResults(byte[] groupId, byte[] wire,
            String contextId) {
        final byte[] ctx = (contextId == null ? "" : contextId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final byte[] r = OpenMlsNative.nativeProcessResults(handle, groupId, wire, ctx);
        if (r == null || r.length == 0) {
            // A null return is the .so refusing, not an empty-but-valid list. Surfaced as one
            // MALFORMED result rather than an empty list so the §10.5 "no results at all" throw
            // stays reserved for the engine genuinely returning nothing — the two are different
            // faults and collapsing them would hide whichever came second.
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
        // EMPTY, not a synthesised failure result. An encrypt that produced nothing has consumed no
        // generation and has no ciphertext to describe; inventing a MALFORMED element here would
        // make the caller's "did I get a ciphertext" test pass on a list with no ciphertext in it.
        if (r == null || r.length == 0) return java.util.Collections.emptyList();
        return MlsEngineResult.decodeList(r);
    }
    @Override public boolean clearPendingProposals(byte[] groupId) {
        final byte[] r = OpenMlsNative.nativeClearPendingProposals(handle, groupId);
        return r != null && r.length >= 1 && r[0] == 1;
    }
    /**
     * @see MlsArtifactBundle — the slot layout lives there, with no native dependency, so the
     *      decoding (including the era / welcomeAction / admitted slots) can be exercised by a
     *      host test instead of only on a device.
     */
    private static MlsGroupArtifacts artifactsFrom(byte[] bundle) {
        return MlsArtifactBundle.decode(bundle);
    }
    @Override public void close() { OpenMlsNative.nativeSessionClose(handle); }

    /** First serialized KeyPackage out of a len-prefixed pool (for a 1:1 add). */
    public static byte[] firstKeyPackage(byte[] pool) {
        List<byte[]> p = splitLenPrefixed(pool);
        return p.isEmpty() ? null : p.get(0);
    }
    static byte[] at(List<byte[]> l, int i) { return i < l.size() ? l.get(i) : new byte[0]; }
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
