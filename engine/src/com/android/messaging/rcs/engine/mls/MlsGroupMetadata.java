/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.log.LogMask;
/**
 * The encrypted group subject and icon (RCC.16 §9.7.1.4, §9.7.1.5): changing them, storing their
 * keys, and holding ciphertext that arrived before its key. See docs/mls/metadata.md.
 */
public final class MlsGroupMetadata {
    private MlsGroupMetadata() {}

    /** Conversations holding a subject; the count {@code mayHoldAnotherSubject} bounds. */
    public static int conversationsHoldingASubject(final MlsShellPort shell) {
        int n = 0;
        for (final ConvState s : shell.convStates().values()) {
            synchronized (s) { if (s.pendingSubject != null) n++; }
        }
        return n;
    }

    /** Held icon ciphertext across all conversations, in bytes. */
    public static long bytesHoldingAnIcon(final MlsShellPort shell) {
        long n = 0;
        for (final ConvState s : shell.convStates().values()) {
            synchronized (s) { if (s.pendingIcon != null) n += s.pendingIcon.length; }
        }
        return n;
    }

    /** The group's current commitment for a slot, or null. */
    public static byte[] currentCommitment(final MlsShellPort shell, final MlsLogSink log,
            final String convKey, final int slot) {
        // Reachable from the inbound path before a session exists, so it fails soft.
        try {
            if (!shell.ensureSession() || shell.session() == null) return null;
            final Group g = shell.getGroup(convKey);
            if (g == null || g.groupId == null) return null;
            final byte[] c = shell.session().groupExt(g.groupId, slot == RccFileInfo.SLOT_ICON
                    ? MlsSession.EXT_ICON_COMMITMENT : MlsSession.EXT_SUBJECT_COMMITMENT);
            return (c == null || c.length == 0) ? null : c;
        } catch (final Throwable t) {
            log.w("MlsGroupMetadata: could not read the current commitment", t);
            return null;
        }
    }

    /**
     * Opens an encrypted group subject, or holds it until its key arrives; the two are separate
     * concurrent deliveries and the ciphertext often wins.
     *
     * @return the plaintext if it opened now, else null (held or unopenable)
     */
    public static byte[] onEncryptedSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String fromE164, final byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length == 0) return null;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, fromE164);
        if (key == null) return null;
        final byte[] plain = shell.openStoredIconSubject(rcsGroupId, fromE164, false, ciphertext);
        if (plain != null) return plain;
        final ConvState cs = shell.conv(key);
        synchronized (cs) { cs.pendingSubject = ciphertext; }
        log.i("MlsGroupMetadata: encrypted subject for " + MlsConversationKey.forLog(key)
                + " held — its key has "
                + "not arrived yet; will open when the FileInfo lands");
        return null;
    }

    /**
     * Opens a downloaded encrypted group icon, or holds it until its key arrives. The hold is
     * bounded in bytes; over the bound the newcomer is dropped, since older holds are likelier to
     * open soon.
     *
     * @return the plaintext if it opened now, else null (held or unopenable)
     */
    public static byte[] onEncryptedIcon(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String fromE164, final byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length == 0) return null;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, fromE164);
        if (key == null) return null;
        final byte[] plain = shell.openStoredIconSubject(rcsGroupId, fromE164, true, ciphertext);
        if (plain != null) return plain;
        final ConvState cs = shell.conv(key);
        final byte[] prior;
        synchronized (cs) { prior = cs.pendingIcon; cs.pendingIcon = null; }
        // Counted with our own slot cleared, so replacing this conversation's hold is not
        // competition.
        if (!MlsMetadataKeysPolicy.mayHoldAnotherIcon(MlsGroupMetadata.bytesHoldingAnIcon(shell),
                ciphertext.length)) {
            log.w("MlsGroupMetadata: NOT holding the " + ciphertext.length
                    + "B encrypted icon for " + MlsConversationKey.forLog(key)
                    + " — that would exceed the "
                    + MlsMetadataKeysPolicy.MAX_PENDING_ICON_BYTES + "B pending-icon budget. The"
                    + " icon is dropped, not the key; a later change re-delivers one."
                    + (prior == null ? "" : " A prior hold for this conversation was released."));
            return null;
        }
        synchronized (cs) { cs.pendingIcon = ciphertext; }
        log.i("MlsGroupMetadata: encrypted icon for " + MlsConversationKey.forLog(key) + " held ("
                + ciphertext.length + "B) — its key has not arrived yet; will open when the "
                + "FileInfo lands or the commit applies");
        return null;
    }

    /**
     * Compares epoch authenticators with the server before a subject or icon change and self-heals
     * first if they differ. Both changes share the {@link MlsFetchLedger.Caller#SUBJECT_CHANGE}
     * ration.
     *
     * @return false iff the caller must refuse the change
     */
    public static boolean healBeforeMetadataChange(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164, final String what) {
        final MlsWelcomeAdmission.ServerState pre =
                MlsWelcomeAdmission.serverStateCheck(shell, MlsFetchLedger.Caller.SUBJECT_CHANGE,
                        rcsGroupId, peerE164);
        if (pre == MlsWelcomeAdmission.ServerState.REFUSED_BY_LEDGER) {
            // Proceed unverified: the check is an optimisation and a stale request is refused
            // cheaply.
            log.i("MlsGroupMetadata: the pre-" + what + "-change currency check was "
                    + "refused by the fetch ledger, so the pre-emptive self-heal is SKIPPED and the "
                    + "request goes out unverified. If we are behind, the server refuses it and the "
                    + "ordinary recovery path handles that — this arm asked nothing and concluded "
                    + "nothing.");
        } else if (pre != MlsWelcomeAdmission.ServerState.MATCHES) {
            log.w("MlsGroupMetadata: our state does not match the server before this "
                    + what + " change (" + pre + ") — self-healing first rather than emitting a "
                    + "doomed request");
            final int healed = shell.selfHeal(rcsGroupId, peerE164);
            if (healed < 0) {
                // The UI shows its generic rename-failed string for this case too.
                log.e("MlsGroupMetadata: refusing the " + what + " change — we are "
                        + "behind and could not self-heal; a peer must re-Welcome us");
                return false;
            }
        }
        return true;
    }

    /**
     * Consumes a received {@code GroupMetadataKeys} body (RCC.16 §10.5.4, §7.13.4), which is routed
     * by content type and is not a FileInfo.
     *
     * @return true if we understood and stored something
     */
    public static boolean onGroupMetadataKeys(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String fromE164, final byte[] proto) {
        final RccGroupMetadataKeys keys = RccGroupMetadataKeys.parse(proto);
        if (keys == null || keys.isEmpty()) {
            log.w("MlsGroupMetadata: unparseable/empty GroupMetadataKeys from "
                    + LogMask.number(fromE164) + " — dropped");
            return false;
        }
        boolean stored = false;
        // The RCC.16 §8.3.1.3 continuity token normally arrives in a Welcome; this is the recovery
        // route. It is stored even though nothing consumes it yet, since it cannot be re-requested.
        if (keys.continuityToken.length > 0) {
            // Same store as the Welcome route. The result means "held now", not "a write happened".
            stored |= MlsContinuityToken.noteContinuityToken(shell, log,
                    MlsConversationKey.canonicalKey(rcsGroupId, fromE164),
                    keys.continuityToken, "§10.5.4 GroupMetadataKeys from "
                            + LogMask.number(fromE164));
        }
        // Field 2 is a RCC.16 §7.8.1 FileInfo; reuse its consumer.
        if (keys.subjectIconKeys.length > 0) {
            stored |= shell.onFileInfo(rcsGroupId, fromE164, keys.subjectIconKeys);
        }
        return stored;
    }

    public static String fileInfoPrefKey(final String convKey, final int slot) {
        return "fileinfo_" + slot + "_" + convKey;
    }

    /**
     * Pref key for a FileInfo, scoped by the commitment it satisfies, so a stale or replayed key
     * cannot displace the current one and the reader looks up the current commitment.
     */
    public static String fileInfoPrefKey(final String convKey, final int slot,
            final byte[] commitment) {
        if (commitment == null || commitment.length == 0) {
            return fileInfoPrefKey(convKey, slot);
        }
        final StringBuilder sb = new StringBuilder("fileinfo_").append(slot).append('_')
                .append(convKey).append('_');
        for (int i = 0; i < Math.min(8, commitment.length); i++) {
            sb.append(String.format("%02x", commitment[i]));
        }
        return sb.toString();
    }

    /**
     * Verifies the key against {@code expectedCommitment}, the one it was looked up by, or the
     * group's current commitment when null. Re-reading it could race a commit.
     */
    public static boolean verifyIconSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] keyMaterial, final boolean icon, final byte[] expectedCommitment) {
        if (!shell.ensureSession()) return false;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return false;
        final byte[] committed = (expectedCommitment != null && expectedCommitment.length > 0)
                ? expectedCommitment
                : shell.session().groupExt(g.groupId,
                        icon ? MlsSession.EXT_ICON_COMMITMENT : MlsSession.EXT_SUBJECT_COMMITMENT);
        if (committed == null || committed.length == 0) {
            log.i("MlsGroupMetadata: no "
                    + (icon ? "icon" : "subject") + " commitment on "
                    + MlsConversationKey.forLog(key)
                    + " — nothing to verify");
            return false;
        }
        // The sender commits to the key material, not the ciphertext.
        final boolean ok = RccCommitment.verify(
                icon ? RccCommitment.LABEL_ICON : RccCommitment.LABEL_SUBJECT,
                keyMaterial, committed);
        final byte[] computed = RccCommitment.commit(icon ? RccCommitment.LABEL_ICON
                : RccCommitment.LABEL_SUBJECT, keyMaterial);
        log.i("MlsGroupMetadata: " + (icon ? "icon" : "subject")
                + " commitment verify → " + (ok ? "MATCH" : "MISMATCH — do not display")
                + " committed=" + MlsHex.hexPrefix(committed) + " computed="
                + MlsHex.hexPrefix(computed)
                + " keyLen=" + (keyMaterial == null ? -1 : keyMaterial.length));
        return ok;
    }

    /** Verifies against the commitment the key was filed under, then decrypts. */
    public static byte[] openIconSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] fileInfoProto, final byte[] encryptedContent,
            final byte[] expectedCommitment) {
        if (!shell.ensureSession()) return null;
        final RccFileInfo.Parsed parsed = RccFileInfo.parse(fileInfoProto);
        if (parsed == null) {
            log.w("MlsGroupMetadata: unparseable FileInfo — cannot open the content");
            return null;
        }
        final boolean icon = parsed.slot == RccFileInfo.SLOT_ICON;
        if (!MlsGroupMetadata.verifyIconSubject(shell, log, rcsGroupId, peerE164,
                parsed.metadata.keyMaterial, icon, expectedCommitment)) {
            log.w("MlsGroupMetadata: group " + (icon ? "icon" : "subject")
                    + " does NOT match the committed commitment — refusing to decrypt or display");
            return null;
        }
        final byte[] plain = RccFileInfo.open(parsed, encryptedContent);
        log.i("MlsGroupMetadata: group " + (icon ? "icon" : "subject")
                + " opened → " + (plain == null ? "DECRYPT FAILED" : plain.length + "B"));
        return plain;
    }

    /**
     * Bounds the retained content keys for one (conversation, slot), oldest first by an insertion
     * order list stored beside them. Never throws: a failed prune costs storage only.
     */
    public static void pruneRetainedContentKeys(final MlsShellPort shell, final MlsLogSink log,
            final String convKey, final int slot, final byte[] commitment) {
        try {
            final MlsPrefs p = shell.prefs();
            final String orderKey = MlsGroupMetadata.fileInfoPrefKey(convKey, slot) + "_order";
            final String cur = p.getString(orderKey, "");
            final java.util.List<String> order = new java.util.ArrayList<>();
            for (final String s : cur.split("\n")) if (!s.isEmpty()) order.add(s);
            final String newest = MlsGroupMetadata.fileInfoPrefKey(convKey, slot, commitment);
            order.remove(newest);            // re-delivery moves it to the newest position
            order.add(newest);
            final MlsPrefs.Editor e = p.edit();
            while (MlsMetadataKeysPolicy.overRetentionCap(order.size())) {
                final String evict = order.remove(0);
                e.remove(evict);
                log.i("MlsGroupMetadata: evicted a retained " + MlsMetadataKeysPolicy.slotName(slot)
                        + " key for " + MlsConversationKey.forLog(convKey) + " — cap is "
                        + MlsMetadataKeysPolicy.retainedContentKeysPerSlot()
                        + " per slot (retaining every "
                        + "content key a device ever saw partially undoes MLS forward secrecy, and "
                        + "nothing renders historical subjects or icons).");
            }
            e.putString(orderKey, String.join("\n", order)).apply();
        } catch (final Throwable t) {
            log.w("MlsGroupMetadata: pruning retained content keys threw — the key "
                    + "itself is stored; only the bound failed", t);
        }
    }

    /**
     * Sends our group metadata keys to the group as an encrypted application message
     * (RCC.16 §10.5.3). {@link MlsMetadataKeysPolicy} decides when.
     *
     * @param keysFileInfo a serialised RCC.16 §7.8.1 {@code FileInfo} carrying the key
     * @param continuityToken the RCC.16 §8.3.1.3 token to include, or {@code null}
     * @return true if something was sent
     */
    public static boolean sendGroupMetadataKeys(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final byte[] keysFileInfo, final byte[] continuityToken) {
        if (!shell.ensureSession()) return false;
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return false;
        final RccGroupMetadataKeys body = new RccGroupMetadataKeys(continuityToken, keysFileInfo);
        if (body.isEmpty()) {
            log.i("sendGroupMetadataKeys: nothing to send for " + rcsGroupId
                    + " (no key material and no continuity token) — not sending an empty body");
            return false;
        }
        final byte[] encoded = body.encode();
        if (encoded == null) {
            log.w("sendGroupMetadataKeys: GroupMetadataKeys encode failed");
            return false;
        }
        // As an attachment, so a client that does not know the type drops it instead of rendering
        // it.
        final byte[] framed = RccMlsBody.frame(encoded, RccGroupMetadataKeys.CONTENT_TYPE,
                /*inline=*/ false);
        log.i("sendGroupMetadataKeys: " + rcsGroupId + " keys="
                + (keysFileInfo == null ? 0 : keysFileInfo.length) + "B token="
                + (continuityToken == null ? 0 : continuityToken.length) + "B (§10.5.3)");
        return shell.sendFramedToGroup(rcsGroupId, framed, "mls-keys", /*rcsMessageId=*/ null);
    }

    public static byte[] changeIconOrSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] plaintext, final String contentType, final boolean icon) {
        if (!shell.ensureSession() || plaintext == null || plaintext.length == 0) return null;
        final String what = icon ? "icon" : "subject";
        if (rcsGroupId == null) {
            log.w("MlsGroupMetadata: an encrypted " + what + " is a GROUP property");
            return null;
        }
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsGroupMetadata: no MLS group for " + rcsGroupId);
            return null;
        }

        // One key yields the ciphertext, the commitment and the delivery; generated independently
        // they cannot validate. The info string is the KDF input, the FileInfo file name and the
        // receiver's lookup name.
        final String info = icon ? RccFileCrypto.INFO_GROUP_ICON : RccFileCrypto.INFO_GROUP_SUBJECT;
        final byte[] contentKey = RccFileCrypto.newKey();
        final RccFileCrypto.Encrypted enc =
                RccFileCrypto.encrypt(contentKey, plaintext, info);
        if (enc == null) {
            log.w("MlsGroupMetadata: could not encrypt the group " + what);
            return null;
        }
        final byte[] commitment = icon
                ? RccCommitment.iconCommitment(contentKey)
                : RccCommitment.subjectCommitment(contentKey);
        log.i("MlsGroupMetadata: committing " + what + " keyLen="
                + (contentKey == null ? -1 : contentKey.length)
                + " commitment=" + MlsHex.hexPrefix(commitment));
        // The blob is the RCC.16 Annex C.2 ciphertext as is; the tag travels in FileInfo.hmac_tag.
        final byte[] blob = enc.ciphertext;

        final byte[] snapshot = shell.session().exportGroupSnapshot(g.groupId);
        final byte[] baseEpochAuth = shell.session().epochAuth(g.groupId);
        final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        // One id for the control message and the AAD: the server rejects a mismatch.
        final String controlMsgId = java.util.UUID.randomUUID().toString();
        // the engine builds the AAD from this id + the group's own era
        final byte[] aad = controlMsgId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final MlsGroupArtifacts art = icon
                ? shell.session().commitGroupMetadata(g.groupId, aad, contentKey, commitment,
                        /*subjectKey=*/ null, /*subjectCommitment=*/ null)
                : shell.session().commitGroupMetadata(g.groupId, aad,
                        /*iconKey=*/ null, /*iconCommitment=*/ null, contentKey, commitment);
        if (art == null || art.commit == null) {
            if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.e("MlsGroupMetadata: " + what
                    + " metadata commit could not be built");
            return null;
        }
        // The key delivery rides in the same request, encrypted under the post-commit epoch the
        // engine has already applied, since receivers apply the commit before decrypting it.
        // contentType is the content's own type and survives only in this FileInfo.
        final byte[] fileInfo = RccFileInfo.encode(
                icon ? RccFileInfo.SLOT_ICON : RccFileInfo.SLOT_SUBJECT,
                new RccFileInfo.Metadata(info, contentType, contentKey,
                        enc.iv, enc.tag, RccFileInfo.ALGORITHM_AES256_CTR_HMAC_SHA256_256TAG,
                        enc.fileLengthHint));
        final byte[] keyDelivery = (fileInfo == null) ? null : shell.session().encryptWithAad(
                g.groupId,
                RccMlsBody.frame(fileInfo, RccFileInfo.CONTENT_TYPE, /*inline=*/ false), aad);
        if (keyDelivery == null) {
            if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.e("MlsGroupMetadata: could not build the " + what + " KEY delivery — "
                    + "not sending a commitment to a key the request would never deliver");
            return null;
        }

        // One request carries content, commit and key. A subject goes inline; an icon goes by
        // reference, uploaded by the provider, whose URL the app never sees.
        final MlsProviderRpc.ControlResult r = icon
                ? shell.rpc("changeGroupIconMls").changeGroupIconMls(rcsGroupId,
                        RccFileInfo.CONTENT_TYPE_ENCRYPTED, blob,
                        art.groupInfo, art.commit, art.tag, art.ratchetTree, baseEpochAuth,
                        keyDelivery, controlMsgId)
                : shell.rpc("changeGroupSubjectMls").changeGroupSubjectMls(rcsGroupId,
                        RccFileInfo.CONTENT_TYPE_ENCRYPTED, blob,
                        art.groupInfo, art.commit, art.tag, art.ratchetTree, baseEpochAuth,
                        keyDelivery, controlMsgId);
        if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
            final boolean rolledBack = snapshot != null
                    && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.w("MlsGroupMetadata: encrypted " + what + " REFUSED → " + r
                    + (rolledBack ? " (rolled back)" : " (ROLLBACK FAILED)"));
            return null;
        }
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (era >= 0) {
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            // An extension commit carries an UpdatePath, so our leaf rotates.
            g.sendsSinceLeafRotation = 0;
            shell.putGroup(key, g);
        }
        // No second message: the key went out in the request.
        log.i("MlsGroupMetadata: encrypted " + what + " ACCEPTED → era=" + era
                + " (key delivered in-band, " + keyDelivery.length + "B)");
        // Shown to the sender here, once accepted: it cannot decrypt its own control, and the
        // copy the group fans back is not re-processed, so no inbound ever applies it.
        final boolean shown = icon ? shell.applyGroupIcon(rcsGroupId, plaintext)
                : shell.applyGroupSubject(rcsGroupId,
                        new String(plaintext, java.nio.charset.StandardCharsets.UTF_8));
        if (!shown) {
            log.w("MlsGroupMetadata: the accepted " + what + " could not be applied to our own "
                    + "conversation for " + rcsGroupId + "; members see it and we do not");
        }
        return blob;
    }

    /**
     * Commits icon and/or subject commitments over key material with no content and no key
     * delivery. Superseded by {@link #changeGroupIcon} / {@link #changeGroupSubject}; kept as a
     * debug negative control, which the server is expected to refuse with
     * mismatched-rcs-group-state because it validates a commitment against content in the same
     * request.
     *
     * @return the era it committed at, or -1
     */
    public static int publishIconSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] iconKey, final byte[] subjectKey) {
        if (!shell.ensureSession()) return -1;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) return -1;
        final byte[] iconC = (iconKey == null || iconKey.length == 0)
                ? null : RccCommitment.iconCommitment(iconKey);
        final byte[] subjC = (subjectKey == null || subjectKey.length == 0)
                ? null : RccCommitment.subjectCommitment(subjectKey);
        if (iconC == null && subjC == null) {
            log.w("MlsGroupMetadata: publishIconSubject with nothing to commit");
            return -1;
        }
        log.w("MlsGroupMetadata: publishIconSubject is SUPERSEDED and is "
                + "a NEGATIVE CONTROL, not a way to publish an icon or subject. It deliberately "
                + "carries no content and no key delivery, so the server refuses it with mlsError 5 — "
                + "that refusal IS the result. Its commitment is over the key material, so "
                + "an acceptance would now be merely useless rather than harmful. To actually "
                + "change an icon or subject use changeGroupIcon / changeGroupSubject.");
        final byte[] snapshot = shell.session().exportGroupSnapshot(g.groupId);
        final byte[] baseEpochAuth = shell.session().epochAuth(g.groupId);
        final String ctrlId = "mls-iconsubj-" + (rcsGroupId == null ? peerE164 : rcsGroupId)
                + "-" + System.currentTimeMillis();
        final int eraNow = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        // the engine builds the AAD from this id + the group's own era
        final byte[] aad = ctrlId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        final MlsGroupArtifacts art =
                shell.session().commitIconSubject(g.groupId, aad, iconC, subjC);
        if (art == null || art.commit == null) {
            if (snapshot != null) shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.e("MlsGroupMetadata: icon/subject commit could not be built — rolled back");
            return -1;
        }
        log.i("MlsGroupMetadata: icon/subject commitments"
                + (iconC == null ? "" : " icon=" + iconC.length + "B")
                + (subjC == null ? "" : " subject=" + subjC.length + "B")
                + " commit=" + art.commit.length + "B era=" + eraNow);
        final MlsProviderRpc.ControlResult r = shell.rpc("applyMlsControl").applyMlsControl(
                peerE164, ctrlId, art.groupInfo, art.commit, art.tag, art.ratchetTree,
                baseEpochAuth, rcsGroupId);
        if (r == null || r.verdict != MlsProviderRpc.ControlResult.VERDICT_OK) {
            final boolean rolledBack = snapshot != null
                    && shell.session().restoreGroupSnapshot(g.groupId, snapshot);
            log.w("MlsGroupMetadata: icon/subject REFUSED → " + r
                    + (rolledBack ? " (rolled back)" : " (ROLLBACK FAILED)"));
            MlsSelfHeal.onControlRefused(shell, log, rcsGroupId, peerE164, r, "icon-subject");
            return -1;
        }
        final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(g.groupId));
        if (era >= 0) {
            g.era = era;
            g.epochAuth = shell.session().epochAuth(g.groupId);
            g.sendsThisEpoch = 0;
            // An extension commit carries an UpdatePath, so our leaf rotates.
            g.sendsSinceLeafRotation = 0;
            shell.putGroup(key, g);
        }
        log.i("MlsGroupMetadata: icon/subject commitments ACCEPTED → era=" + era);
        return era;
    }

    public static byte[] openStoredIconSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final boolean icon, final byte[] encryptedContent) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return null;
        final int slot = icon ? RccFileInfo.SLOT_ICON : RccFileInfo.SLOT_SUBJECT;
        // No unscoped fallback: without the current commitment we cannot tell which key applies.
        final byte[] commitment = MlsGroupMetadata.currentCommitment(shell, log, key, slot);
        if (commitment == null) {
            log.i("MlsGroupMetadata: cannot read the current "
                    + MlsMetadataKeysPolicy.slotName(slot)
                    + " commitment for " + MlsConversationKey.forLog(key)
                    + " — not guessing which stored key applies");
            return null;
        }
        final String b64 = shell.prefs()
                .getString(MlsGroupMetadata.fileInfoPrefKey(key, slot, commitment), null);
        if (b64 == null) {
            log.i("MlsGroupMetadata: no stored " + MlsMetadataKeysPolicy.slotName(slot)
                    + " key for " + MlsConversationKey.forLog(key)
                    + " — the sender has not delivered one since we joined (§9.7.1.3 gap)");
            return null;
        }
        return MlsGroupMetadata.openIconSubject(shell, log, rcsGroupId, peerE164,
                shell.base64Decode(b64), encryptedContent, commitment);
    }

    /**
     * Opens a held icon once its key is stored. Called on key arrival and on commit apply, since
     * the lookup follows the current commitment.
     */
    public static void openPendingIcon(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String rcsGroupId, final String fromE164) {
        final ConvState cs = shell.convIfAny(key);
        if (cs == null) return;
        final byte[] held;
        synchronized (cs) { held = cs.pendingIcon; cs.pendingIcon = null; }
        if (held == null) return;
        final byte[] plain = MlsGroupMetadata.openStoredIconSubject(shell, log, rcsGroupId,
                fromE164, true, held);
        if (plain == null) {
            // Re-hold for the other trigger.
            if (MlsMetadataKeysPolicy.mayHoldAnotherIcon(MlsGroupMetadata.bytesHoldingAnIcon(shell),
                    held.length)) {
                synchronized (cs) { cs.pendingIcon = held; }
            }
            log.i("MlsGroupMetadata: the held icon for " + MlsConversationKey.forLog(key)
                    + " did not open on "
                    + "this attempt — re-held for the next trigger (key arrival or commit)");
            return;
        }
        log.i("MlsGroupMetadata: DECRYPTED held group icon for " + MlsConversationKey.forLog(key)
                + " ("
                + plain.length + "B)");
        shell.applyGroupIcon(rcsGroupId, plain);
    }

    /** Opens a held subject once its key is stored; the same two triggers as the icon. */
    public static void openPendingSubject(final MlsShellPort shell, final MlsLogSink log,
            final String key, final String rcsGroupId, final String fromE164) {
        final ConvState cs = shell.convIfAny(key);
        if (cs == null) return;
        final byte[] held;
        synchronized (cs) { held = cs.pendingSubject; cs.pendingSubject = null; }
        if (held == null) return;
        final byte[] plain = MlsGroupMetadata.openStoredIconSubject(shell, log, rcsGroupId,
                fromE164, false, held);
        if (plain == null) {
            // Re-hold for the other trigger, within the held-subject bound. This conversation's
            // slot is cleared, so the count excludes it.
            if (MlsMetadataKeysPolicy.mayHoldAnotherSubject(
                    MlsGroupMetadata.conversationsHoldingASubject(shell))) {
                synchronized (cs) { cs.pendingSubject = held; }
            }
            log.i("MlsGroupMetadata: the held subject for " + MlsConversationKey.forLog(key)
                    + " did not open on "
                    + "this attempt — re-held for the next trigger (key arrival or commit)");
            return;
        }
        final String subjectText = new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        log.i("MlsGroupMetadata: DECRYPTED held group subject for " + MlsConversationKey.forLog(key)
                + " ("
                + plain.length + "B)");
        shell.applyGroupSubject(rcsGroupId, subjectText);
    }

    public static boolean onFileInfo(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String fromE164, final byte[] fileInfoProto) {
        final RccFileInfo.Parsed parsed = RccFileInfo.parse(fileInfoProto);
        if (parsed == null) {
            log.w("MlsGroupMetadata: unparseable FileInfo from " + LogMask.number(fromE164)
                    + " — dropped (a key we cannot parse is not a key)");
            return false;
        }
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, fromE164);
        // No write-time guard is needed: keys are filed under the commitment they satisfy.
        if (key == null) return false;
        final byte[] storedCommitment = RccCommitment.commit(
                parsed.slot == RccFileInfo.SLOT_ICON ? RccCommitment.LABEL_ICON
                        : RccCommitment.LABEL_SUBJECT,
                parsed.metadata == null ? null : parsed.metadata.keyMaterial);
        try {
            shell.prefs().edit()
                    .putString(MlsGroupMetadata.fileInfoPrefKey(key, parsed.slot, storedCommitment),
                            java.util.Base64.getEncoder().encodeToString(fileInfoProto))
                    .apply();
        } catch (final Throwable t) {
            log.w("MlsGroupMetadata: could not persist the FileInfo", t);
            return false;
        }
        MlsGroupMetadata.pruneRetainedContentKeys(shell, log, key, parsed.slot, storedCommitment);
        log.i("MlsGroupMetadata: stored a " + MlsMetadataKeysPolicy.slotName(parsed.slot)
                + " key from " + LogMask.number(fromE164) + " for " + MlsConversationKey.forLog(key)
                + " (len="
                + parsed.metadata.fileLengthHint + "B)");
        // One FileInfo carries one slot and we do not know which was held, so try both.
        MlsGroupMetadata.openPendingSubject(shell, log, key, rcsGroupId, fromE164);
        MlsGroupMetadata.openPendingIcon(shell, log, key, rcsGroupId, fromE164);
        return true;
    }

    /**
     * Changes the group icon end to end (RCC.16 §9.7.1.4): encrypt under a fresh key, commit the
     * key and its commitment, and deliver the key in the same request. On acceptance the icon is
     * applied to our own conversation, which no inbound will do. The only production caller so far
     * is a debug broadcast; there is no UI entry point yet.
     *
     * @return the encrypted content for local display, or {@code null} if any step failed
     */
    public static byte[] changeGroupIcon(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] iconBytes, final String contentType) {
        if (!MlsGroupMetadata.healBeforeMetadataChange(shell, log, rcsGroupId, peerE164,
                "icon")) return null;
        return MlsGroupMetadata.changeIconOrSubject(shell, log, rcsGroupId, peerE164, iconBytes,
                contentType, /*icon=*/ true);
    }

    /** The subject counterpart of {@link #changeGroupIcon} (RCC.16 §9.7.1.5). */
    public static byte[] changeGroupSubject(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164,
            final byte[] subjectUtf8, final String contentType) {
        if (!MlsGroupMetadata.healBeforeMetadataChange(shell, log, rcsGroupId, peerE164,
                "subject")) return null;
        return MlsGroupMetadata.changeIconOrSubject(shell, log, rcsGroupId, peerE164, subjectUtf8,
                contentType, /*icon=*/ false);
    }
}
