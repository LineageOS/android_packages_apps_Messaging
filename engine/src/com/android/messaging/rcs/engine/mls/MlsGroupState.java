/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.log.LogMask;
/**
 * Questions about one conversation's MLS group: do we hold one, can the engine load it, what era do
 * we record, how is it named in a trace; plus inbound key resolution and the group write.
 */
public final class MlsGroupState {
    private MlsGroupState() {}

    /** Whether we hold MLS state for this RCS group; the upgrade path's "already MLS" guard. */
    public static boolean hasMlsGroup(final MlsShellPort shell, final String rcsGroupId) {
        if (!shell.ensureSession()) return false;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, /*peerE164=*/ null);
        final Group g = (key == null) ? null : shell.getGroup(key);
        return g != null && g.groupId != null;
    }

    /** Whether we hold MLS state for this conversation. */
    public static boolean conversationIsMls(final MlsShellPort shell, final String rcsGroupId,
            final String peerE164) {
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        return g != null && g.groupId != null;
    }

    /**
     * Our recorded era for a conversation, or -1; diagnostic, for comparison with the server's.
     * When the host holds nothing, a group's era is read from the engine and logged as engine-only.
     */
    public static int localEra(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g != null) return (int) g.era;
        // The engine can hold a group the host declined to adopt (a create the server discarded),
        // and the era derivation reads the engine. For a group the MLS id is the RCS group id.
        if (rcsGroupId == null || rcsGroupId.isEmpty()) return -1;
        // The session is needed only for the engine read; the host answer above works without one.
        if (!shell.ensureSession()) return -1;
        final int engineEra = MlsAppMessage.eraFrom(shell.session().eraEpoch(
                rcsGroupId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (engineEra >= 0) {
            log.w("MlsGroupState: localEra(" + rcsGroupId + ") — the HOST holds no "
                    + "group but the ENGINE holds era " + engineEra + ". That pairing is what a "
                    + "create the server discarded leaves behind: the adoption guard declined to "
                    + "record it, the engine kept it, and the era derivation reads the engine.");
        }
        return engineEra;
    }

    /**
     * Whether the engine can load this group from storage. {@code commitRequired} is used only as a
     * load probe; {@code eraEpoch} cannot tell a group whose state is gone.
     */
    public static boolean groupLoads(final MlsSession session, final MlsLogSink log,
            final byte[] groupId) {
        try {
            session.commitRequired(groupId);
            // Its boolean is false for both "no commit needed" and "could not load"; read the
            // status.
            final MlsSession.OpStatus st = session.lastStatus();
            // NO_OP is "loaded, nothing to do", the healthy answer.
            return st == MlsSession.OpStatus.OK || st == MlsSession.OpStatus.NO_OP;
        } catch (final Throwable t) {
            log.w("MlsGroupState: group liveness probe threw — treating as "
                    + "unloadable", t);
            return false;
        }
    }

    /**
     * The MLS group id for a trace line: ASCII when printable (the shape other clients print),
     * hex otherwise. Never throws, never null.
     */
    public static String groupIdForTrace(final MlsShellPort shell, final String key) {
        try {
            final Group g = (key == null) ? null : shell.getGroup(key);
            if (g == null) return "";
            return MlsTrace.groupId(g.groupId);
        } catch (final Throwable t) {
            return "";
        }
    }

    /**
     * True iff this conversation is downgraded and must not send encrypted (RCC.16 §9.1.1). Reads
     * the persisted status, not the {@code 0xF002} extension.
     */
    public static boolean isEndMls(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return false;
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        return g != null && g.groupId != null && MlsRecordState.hasEndMlsStatus(shell, log, key);
    }

    /**
     * Whether we left this conversation, for the UI's group actions. The self participant row
     * survives a leave, so it cannot answer this. True only for a conversation with an MLS record;
     * a plaintext group we left, or one we were removed from, reads false. No lock, no network.
     */
    public static boolean haveWeLeft(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        return key != null && MlsRecordState.weLeft(shell, log, key);
    }

    /**
     * Resolves the conversation key for inbound traffic, adopting the migrated 1:1 group if this
     * process has not seen it, since a conversation the peer starts must be receivable. The key is
     * peer-derived; {@code ensureReady} re-keys it on the first send.
     *
     * @return the conversation key, or {@code null} when there is no group the engine can load
     */
    public static String resolveInbound(final MlsShellPort shell, final MlsLogSink log,
            final String rcsGroupId, final String peerE164) {
        final String key = MlsConversationKey.canonicalKey(rcsGroupId, peerE164);
        if (key == null) return null;
        // A host record can outlive the engine state it names, so probe the load. A null answer is
        // what lets an inbound Welcome reach joinFromWelcome and repair the conversation. Removing
        // the cache row would not help: getGroup rebuilds it from the record.
        final Group held = shell.getGroup(key);
        if (held != null) {
            if (held.groupId == null || MlsGroupState.groupLoads(shell.session(), log, held.groupId)) return key;
            log.w("MlsGroupState: the host holds a group for " + MlsConversationKey.forLog(key)
                    + " that the "
                    + "engine can no longer LOAD — not answering for it. Inbound is routed as "
                    + "though we held nothing, so a Welcome can still repair the conversation "
                    + ".");
        }
        // A group we hold no state for is an unperformed join; never fall back to the peer's 1:1.
        if (rcsGroupId != null && !rcsGroupId.isEmpty()) return null;
        try {
            final byte[] gid = shell.rpc("getMlsGroupIdForPeer").getMlsGroupIdForPeer(peerE164);
            if (gid == null || gid.length == 0) return null;
            final int era = MlsAppMessage.eraFrom(shell.session().eraEpoch(gid));
            // A readable era does not mean a loadable group.
            if (era < 0 || !MlsGroupState.groupLoads(shell.session(), log, gid)) {
                log.e("MlsGroupState: provider has a group for " + LogMask.number(peerE164)
                        + " but the migrated engine state cannot read it (era=" + era
                        + ") — cannot accept inbound");
                return null;
            }
            final Group adopted = new Group();
            adopted.groupId = gid;
            adopted.peerE164 = peerE164;
            adopted.era = era;
            adopted.epochAuth = shell.session().epochAuth(gid);
            shell.putGroup(key, adopted);
            log.i("MlsGroupState: ADOPTED migrated 1:1 group for " + LogMask.number(peerE164)
                    + " at era=" + era);
            return key;
        } catch (final Throwable t) {
            log.w("MlsGroupState: adoption failed for " + LogMask.number(peerE164), t);
            return null;
        }
    }

    public static void putGroup(final MlsShellPort shell, final MlsLogSink log,
            final String conversationId, final Group g) {
        // One record written whole under the lock, so the in-memory and persisted rows never
        // disagree. Legacy per-field preferences are still read on load and drain on rewrite.
        shell.lock(conversationId);
        try {
        shell.groups().put(conversationId, g);
        if (g.groupId != null && g.groupId.length > 0) {
            shell.records().putAlias(shell.selfE164(), conversationId, g.groupId);
        }
        MlsRecordState.writeRecord(shell, log, g);
        } finally { shell.unlock(conversationId); }
    }
}
