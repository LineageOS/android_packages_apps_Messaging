/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsPeerGuards;

/** {@link MlsPeerGuards} over MlsPeerGuard's static surface: every call forwards unchanged. */
final class MlsPeerGuardBinding implements MlsPeerGuards {
    static final MlsPeerGuards INSTANCE = new MlsPeerGuardBinding();

    private MlsPeerGuardBinding() {}

    @Override public boolean allowDebugStateChange(final String op, final String peerE164) {
        return MlsPeerGuard.allowDebugStateChange(op, peerE164);
    }
    @Override public boolean allowEraAdvance(final String groupId, final String peerE164) {
        return MlsPeerGuard.allowEraAdvance(groupId, peerE164);
    }
    @Override public boolean allowEraAdvance(final String op, final String groupId,
            final String peerE164) {
        return MlsPeerGuard.allowEraAdvance(op, groupId, peerE164);
    }
    @Override public boolean allowJoiningPeer(final String op, final String peerE164) {
        return MlsPeerGuard.allowJoiningPeer(op, peerE164);
    }
    @Override public boolean allowSelfDeparture(final String op) {
        return MlsPeerGuard.allowSelfDeparture(op);
    }
    @Override public boolean allowStateChange(final String op, final String peerE164) {
        return MlsPeerGuard.allowStateChange(op, peerE164);
    }
    @Override public boolean claimRebuildEpisode(final String conversationKey) {
        return MlsPeerGuard.claimRebuildEpisode(conversationKey);
    }
    @Override public boolean claimRebuildEpisode(final String conversationKey,
            final long episodeMs) {
        return MlsPeerGuard.claimRebuildEpisode(conversationKey, episodeMs);
    }
    @Override public boolean claimReestablishAttempt(final String peerE164) {
        return MlsPeerGuard.claimReestablishAttempt(peerE164);
    }
    @Override public boolean claimReestablishAttempt(final String peerE164, final long cooldownMs) {
        return MlsPeerGuard.claimReestablishAttempt(peerE164, cooldownMs);
    }
    @Override public void notePeerFailure(final String peerE164) {
        MlsPeerGuard.notePeerFailure(peerE164);
    }
    @Override public void notePeerRecovered(final String peerE164) {
        MlsPeerGuard.notePeerRecovered(peerE164);
    }
    @Override public long rebuildEpisodeAgeMs(final String conversationKey) {
        return MlsPeerGuard.rebuildEpisodeAgeMs(conversationKey);
    }
    @Override public long rebuildEpisodeWindowMs() { return MlsPeerGuard.rebuildEpisodeWindowMs(); }
    @Override public long reestablishCooldownMs() { return MlsPeerGuard.reestablishCooldownMs(); }
    @Override public void resetPeerHealth(final String peerE164) {
        MlsPeerGuard.resetPeerHealth(peerE164);
    }
    @Override public void resetRebuildEpisode(final String conversationKey) {
        MlsPeerGuard.resetRebuildEpisode(conversationKey);
    }
    @Override public void resetReestablishCooldown(final String peerE164) {
        MlsPeerGuard.resetReestablishCooldown(peerE164);
    }
}
