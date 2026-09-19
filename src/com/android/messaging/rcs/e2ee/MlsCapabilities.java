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
package com.android.messaging.rcs.e2ee;

import android.text.TextUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The MLS-eligibility predicate — the app-layer analogue of the standard-first
 * capability tree Google Messages runs over a peer's advertised MLS feature-tags.
 *
 * <p>Per peer, parse the four MLS feature-tags off its RCS capability set and run
 * the standard-first decision tree. The conversation is MLS-eligible iff
 * <b>every</b> participant passes (one failing peer drops the whole conversation
 * to Etouffee/none).
 *
 * <p>Order is load-bearing:
 * <ol>
 *   <li>{@code mls-kds} is a <b>hard gate</b> — absent ⇒ peer not MLS-capable,
 *       checked first.</li>
 *   <li>GSMA-standard {@code mls-version=v1} is tried <b>before</b> the
 *       Google-only tags (cross-platform-first).</li>
 *   <li>Google-wave {@code mls-launch-iteration == ours} fallback.</li>
 *   <li>Group-MLS {@code mls-supports-groups=true} fallback (group convos only).</li>
 * </ol>
 *
 * <p>Pure functions, no Android-runtime dependency beyond {@link TextUtils}, so the
 * tree is unit-testable with synthetic cap-sets.
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
     * Parse a peer's raw RCS feature-tag set into the four MLS tags we care about.
     * Google Messages advertises these as {@code {1:{1:name},2:{1:value},3:enum}}
     * entries in {@code ClientCapabilities}; a consumer only needs
     * name→value. Tags absent from {@code rawFeatureTags} are simply absent.
     *
     * @param rawFeatureTags name→value map of a peer's advertised feature tags
     *                       (caller's existing RCS capability layer supplies this)
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
     * Per-peer reduce: returns true iff <b>every</b> participant is
     * MLS-capable under the standard-first tree. An empty participant list is not
     * eligible (no one to be encrypted to).
     *
     * @param peers            parsed caps for each participant
     * @param ourLaunchIteration this build's Google-wave iteration (for the
     *                         {@code mls-launch-iteration == ours} fallback)
     * @param isGroup          whether this conversation is a group (enables the
     *                         {@code mls-supports-groups} fallback)
     */
    public static boolean mlsEligible(final List<PeerCaps> peers,
            final String ourLaunchIteration, final boolean isGroup) {
        if (peers == null || peers.isEmpty()) {
            return false;
        }
        for (final PeerCaps caps : peers) {
            if (!peerPassesMls(caps, ourLaunchIteration, isGroup)) {
                return false;   // one peer without an MLS path ⇒ whole convo ineligible
            }
        }
        return true;            // every participant passed
    }

    /** Single-peer evaluation of the standard-first tree. */
    public static boolean peerPassesMls(final PeerCaps caps,
            final String ourLaunchIteration, final boolean isGroup) {
        if (caps == null) {
            return false;
        }
        // HARD GATE: no mls-kds ⇒ not MLS-capable, regardless of other tags.
        if (!caps.has(RcsE2eeScheme.TAG_MLS_KDS)) {
            return false;
        }
        // GSMA-standard path tried FIRST (cross-vendor).
        if (RcsE2eeScheme.MLS_VERSION_V1.equals(caps.value(RcsE2eeScheme.TAG_MLS_VERSION))) {
            return true;
        }
        // Google-wave same-build fallback: peer iteration == ours.
        if (!TextUtils.isEmpty(ourLaunchIteration)
                && ourLaunchIteration.equals(caps.value(RcsE2eeScheme.TAG_MLS_LAUNCH_ITERATION))) {
            return true;
        }
        // Group-MLS fallback for the Google path (group conversations only).
        if (isGroup
                && "true".equals(caps.value(RcsE2eeScheme.TAG_MLS_SUPPORTS_GROUPS))) {
            return true;
        }
        return false;           // this peer passes no MLS path
    }

    /**
     * Why an otherwise-provisioned peer was refused, or {@code null} if there is nothing notable to
     * report. Pure — the caller does the logging, so this class stays host-testable.
     *
     * <p>A peer advertising {@code mls-kds} <b>is</b> MLS-provisioned: both Google Messages
     * and our own provider only add the MLS tuple once
     * a fresh cert <em>and</em> uploaded KeyPackages exist. So a refusal at that point means an
     * MLS-capable peer was turned away, and the realistic cause is <b>wave drift</b> — Google advanced
     * {@code mls-launch-iteration} past the value this build claims.
     *
     * <p>Without this, that presents as a silent, permanent downgrade to Etouffee with no error
     * anywhere. Naming both values makes the fix a one-liner:
     * {@code setprop debug.rcs.mls_launch_iteration <peer's value>} (and update the provider's
     * advertised tuple to match).
     */
    public static String refusalReason(final PeerCaps caps, final String ourLaunchIteration,
            final boolean isGroup) {
        if (caps == null || peerPassesMls(caps, ourLaunchIteration, isGroup)) {
            return null;
        }
        if (!caps.has(RcsE2eeScheme.TAG_MLS_KDS)) {
            return null;        // genuinely not MLS-provisioned — nothing surprising to report
        }
        final String peerIteration = caps.value(RcsE2eeScheme.TAG_MLS_LAUNCH_ITERATION);
        if (TextUtils.isEmpty(peerIteration)) {
            return "peer advertises mls-kds but no mls-version, no mls-launch-iteration"
                    + (isGroup ? "" : " (and mls-supports-groups is group-only)");
        }
        return "WAVE DRIFT — peer mls-launch-iteration=" + peerIteration + " but ours="
                + ourLaunchIteration + "; Google likely advanced the wave. Set "
                + RcsE2eeScheme.PROP_LAUNCH_ITERATION + "=" + peerIteration
                + " and update the provider's advertised tuple.";
    }

    /**
     * The per-claim KDS-version selector: the peer's
     * {@code mls-kds} int (Google Messages advertises {@code "2"}) → ClaimRequest field 4.
     * Defaults to {@link RcsE2eeScheme#MLS_CIPHERSUITE_P256_AES128}'s companion
     * KDS version 2 when unparseable.
     */
    public static int kdsVersionFor(final PeerCaps caps) {
        if (caps != null) {
            final String v = caps.value(RcsE2eeScheme.TAG_MLS_KDS);
            if (!TextUtils.isEmpty(v)) {
                try {
                    return Integer.parseInt(v.trim());
                } catch (final NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return 2;
    }
}
