/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.text.TextUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MLS eligibility from peers' advertised MLS capability tags. A conversation is eligible iff every
 * participant passes, checked in this order:
 * <ol>
 *   <li>{@code mls-kds} is required; without it the peer is not MLS-capable.</li>
 *   <li>{@code mls-version=v1} (the GSMA tag) is tried first.</li>
 *   <li>{@code mls-launch-iteration} equal to ours.</li>
 *   <li>{@code mls-supports-groups=true}, for group conversations only.</li>
 * </ol>
 * Pure functions, so the tree is host-testable.
 */
public final class MlsCapabilities {
    private MlsCapabilities() {}

    /** A single participant's parsed MLS capability tags. */
    public static final class PeerCaps {
        private final Map<String, String> mTags;

        public PeerCaps(final Map<String, String> tags) {
            mTags = (tags == null) ? Collections.emptyMap() : tags;
        }

        public boolean has(final String tag) {
            return mTags.containsKey(tag);
        }

        public String value(final String tag) {
            return mTags.get(tag);
        }
    }

    /**
     * Keep only the four MLS tags from a peer's advertised feature tags (name to value). Absent
     * tags stay absent.
     */
    public static PeerCaps parseCaps(final Map<String, String> rawFeatureTags) {
        final Map<String, String> mls = new HashMap<>();
        if (rawFeatureTags != null) {
            copyIfPresent(rawFeatureTags, mls, RcsE2eeScheme.TAG_MLS_VERSION);
            copyIfPresent(rawFeatureTags, mls, RcsE2eeScheme.TAG_MLS_KDS);
            copyIfPresent(rawFeatureTags, mls, RcsE2eeScheme.TAG_MLS_LAUNCH_ITERATION);
            copyIfPresent(rawFeatureTags, mls, RcsE2eeScheme.TAG_MLS_SUPPORTS_GROUPS);
        }
        return new PeerCaps(mls);
    }

    private static void copyIfPresent(final Map<String, String> from,
            final Map<String, String> to, final String key) {
        final String v = from.get(key);
        if (v != null) {
            to.put(key, v);
        }
    }

    /**
     * True iff every participant passes {@link #peerPassesMls}. An empty list is not eligible.
     *
     * @param ourLaunchIteration for the {@code mls-launch-iteration == ours} path
     * @param isGroup            enables the {@code mls-supports-groups} path
     */
    public static boolean mlsEligible(final List<PeerCaps> peers,
            final String ourLaunchIteration, final boolean isGroup) {
        if (peers == null || peers.isEmpty()) {
            return false;
        }
        for (final PeerCaps caps : peers) {
            if (!peerPassesMls(caps, ourLaunchIteration, isGroup)) {
                return false;
            }
        }
        return true;
    }

    /** One peer through the tree in the class comment. */
    public static boolean peerPassesMls(final PeerCaps caps,
            final String ourLaunchIteration, final boolean isGroup) {
        if (caps == null) {
            return false;
        }
        if (!caps.has(RcsE2eeScheme.TAG_MLS_KDS)) {
            return false;
        }
        if (RcsE2eeScheme.MLS_VERSION_V1.equals(caps.value(RcsE2eeScheme.TAG_MLS_VERSION))) {
            return true;
        }
        if (!TextUtils.isEmpty(ourLaunchIteration)
                && ourLaunchIteration.equals(caps.value(RcsE2eeScheme.TAG_MLS_LAUNCH_ITERATION))) {
            return true;
        }
        if (isGroup
                && "true".equals(caps.value(RcsE2eeScheme.TAG_MLS_SUPPORTS_GROUPS))) {
            return true;
        }
        return false;
    }

    /**
     * Why a peer that advertises {@code mls-kds} was refused, or {@code null} if it passed or does
     * not advertise it. A peer advertises {@code mls-kds} only once it holds a certificate and
     * uploaded KeyPackages, so refusing one is notable; the likely cause is a launch-iteration
     * mismatch, fixed with {@link RcsE2eeScheme#PROP_LAUNCH_ITERATION}. Pure; the caller logs.
     */
    public static String refusalReason(final PeerCaps caps, final String ourLaunchIteration,
            final boolean isGroup) {
        if (caps == null || peerPassesMls(caps, ourLaunchIteration, isGroup)) {
            return null;
        }
        if (!caps.has(RcsE2eeScheme.TAG_MLS_KDS)) {
            return null;
        }
        final String peerIteration = caps.value(RcsE2eeScheme.TAG_MLS_LAUNCH_ITERATION);
        if (TextUtils.isEmpty(peerIteration)) {
            return "peer advertises mls-kds but no mls-version, no mls-launch-iteration"
                    + (isGroup ? "" : " (and mls-supports-groups is group-only)");
        }
        return "WAVE DRIFT — peer mls-launch-iteration=" + peerIteration + " but ours="
                + ourLaunchIteration
                + "; the network has likely moved to a newer launch iteration. Set "
                + RcsE2eeScheme.PROP_LAUNCH_ITERATION + "=" + peerIteration
                + " and update the provider's advertised tuple.";
    }

    /**
     * The KDS version to claim with: the peer's {@code mls-kds} value, or 2 when absent or not a
     * number.
     */
    public static int kdsVersionFor(final PeerCaps caps) {
        if (caps != null) {
            final String v = caps.value(RcsE2eeScheme.TAG_MLS_KDS);
            if (!TextUtils.isEmpty(v)) {
                try {
                    return Integer.parseInt(v.trim());
                } catch (final NumberFormatException ignored) {
                }
            }
        }
        return 2;
    }
}
