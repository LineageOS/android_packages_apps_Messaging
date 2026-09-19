/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The app's {@code MlsPeerGuard} allow/claim/reset surface as an instance, with the same names and
 * parameters, so engine code can consult it through the port. See docs/mls/budgets.md.
 */
public interface MlsPeerGuards {
    boolean allowDebugStateChange(String op, String peerE164);
    boolean allowEraAdvance(String groupId, String peerE164);
    boolean allowEraAdvance(String op, String groupId, String peerE164);
    boolean allowJoiningPeer(String op, String peerE164);
    boolean allowSelfDeparture(String op);
    boolean allowStateChange(String op, String peerE164);
    boolean claimRebuildEpisode(String conversationKey);
    boolean claimRebuildEpisode(String conversationKey, long episodeMs);
    boolean claimReestablishAttempt(String peerE164);
    boolean claimReestablishAttempt(String peerE164, long cooldownMs);
    void notePeerFailure(String peerE164);
    void notePeerRecovered(String peerE164);
    long rebuildEpisodeAgeMs(String conversationKey);
    long rebuildEpisodeWindowMs();
    long reestablishCooldownMs();
    void resetPeerHealth(String peerE164);
    void resetRebuildEpisode(String conversationKey);
    void resetReestablishCooldown(String peerE164);
}
