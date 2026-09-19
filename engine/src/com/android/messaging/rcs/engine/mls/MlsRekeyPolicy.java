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

/**
 * <b>When our own LEAF KEY must rotate, and what a refused rotation costs.</b>
 *
 * <h2>Why the rotation exists at all</h2>
 *
 * <p>Google Messages does not advance the application generation in place forever: as a leaf's
 * {@code encryption_key_usage_level} nears its limit it eagerly self-updates, which resets the
 * application ratchet to generation 0. Without that, a long in-place run eventually sits past the
 * receiver's SecretTree {@code max_skip} window and messages are <b>silently dropped</b> —
 * device-observed as generation 2 decrypting while 3 and 4 vanished.
 *
 * <h2>Both sides run this counter against this threshold, which is why it is STAGGERED</h2>
 *
 * <p>Under mutual traffic two members reach the same bound at nearly the same moment and DUEL: each
 * commit refuses the other, and the era climbs without either converging. Device-observed
 * 2026-08-09, era 13 -> 17 across one bidirectional run, ending with the pair wedged and every send
 * INVALID_ARGUMENT.
 *
 * <p>The tie-break is not invented here. {@link MlsRecoveryPolicy#weAreEraAdvancer} already defines
 * the one this codebase uses — the numerically-lower E.164 goes first — and its javadoc gives
 * exactly this reasoning. The non-advancer simply rotates later, so both sides still rotate and
 * forward secrecy is unaffected.
 *
 * <h2>A refusal must NOT restore the pre-commit counter</h2>
 *
 * <p>The counter that asked for the rotation is by definition at or past the threshold, so putting
 * it back verbatim makes the very next send attempt the same rotation again — which is the storm
 * above, one layer down. {@link #counterAfterRefusal()} is where it lands instead: below the
 * threshold by {@link #REKEY_REFUSED_BACKOFF_SENDS}, so the winner's commit can land first and the
 * rotation is deferred by a bounded number of sends rather than abandoned.
 *
 * <h2>Not a sysprop, deliberately</h2>
 *
 * <p>{@code debug.rcs.mls_rekey_limit} exists and is <b>off-limits as a commit trigger</b> (an
 * operator decision, recorded where the constant used to be declared). This behaviour must be
 * automatic rather than something an operator has to arm, so unlike
 * {@link MlsConfig#ftdMaxAttempts} — the other bound Stage 6 moved out of the same class — this one
 * is a constant and stays one.
 *
 * <p>256 matches the documented default. <b>The exact Google Messages constant has never been extracted from
 * Google's engine binary, so this number is OURS</b> and is recorded as ours rather than as a match.
 */
public final class MlsRekeyPolicy {

    private MlsRekeyPolicy() {}

    /** Rotate our leaf after this many app sends in one epoch. See the class javadoc — this is OURS. */
    public static final int REKEY_AFTER_SENDS = 256;

    /**
     * How far below {@link #REKEY_AFTER_SENDS} a REFUSED rotation drops the counter — i.e. how many
     * further sends before we try rotating again.
     *
     * <p>Sized as a fraction of the threshold rather than as an absolute delay: what has to be true
     * is that the loser of a commit race stops asking for a while, and "a while" is measured in the
     * same unit the threshold is (sends), because that is the only clock this counter has.
     */
    public static final int REKEY_REFUSED_BACKOFF_SENDS = 64;

    /**
     * The threshold THIS member rotates at, staggered by the era tie-break.
     *
     * @param weGoFirst {@link MlsRecoveryPolicy#weAreEraAdvancer} for (self, peer)
     */
    public static int rotateAt(final boolean weGoFirst) {
        return weGoFirst ? REKEY_AFTER_SENDS : REKEY_AFTER_SENDS + (REKEY_AFTER_SENDS / 2);
    }

    /**
     * Should the NEXT send carry a piggybacked key update?
     *
     * <p>Asked before the send, about the send that is about to happen — hence the {@code + 1}. It
     * reads the ROTATION counter, never the epoch one: gating on sends-this-epoch
     * meant every epoch change reset the budget, including an Add-only commit and any peer commit,
     * neither of which rotates our leaf, so in a busy group the threshold could never be reached.
     */
    public static boolean rotationDueOnNextSend(final int sendsSinceLeafRotation,
            final boolean weGoFirst) {
        return sendsSinceLeafRotation + 1 >= rotateAt(weGoFirst);
    }

    /**
     * Is the OUT-OF-BAND fallback rekey due?
     *
     * <p>Unstaggered on purpose, and it is not an oversight that this differs from
     * {@link #rotationDueOnNextSend}. Reaching this path means the piggybacked rotation did not
     * happen at all — an engine without {@code encryptResults}, or one that returned no commit — so
     * the duel this stagger avoids is not the situation. What matters here is that the fallback
     * fires at the base threshold rather than half again later, because the alternative to a late
     * rotation is none.
     */
    public static boolean outOfBandRekeyDue(final int sendsSinceLeafRotation) {
        return sendsSinceLeafRotation >= REKEY_AFTER_SENDS;
    }

    /**
     * Where both send counters land after a REFUSED rotation.
     *
     * <p>Never below zero, so a backoff wider than the threshold cannot produce a negative counter
     * that then has to climb from further away than a fresh epoch would.
     */
    public static int counterAfterRefusal() {
        return Math.max(0, REKEY_AFTER_SENDS - REKEY_REFUSED_BACKOFF_SENDS);
    }

    /**
     * The counter value that puts the NEXT send one short of the base threshold.
     *
     * <p>For {@code MlsProviderTransport.seedUsageCounter}, the test seam that exists because
     * verifying this behaviour otherwise costs {@link #REKEY_AFTER_SENDS} real sends. It seeds
     * against the BASE threshold, so it is the advancer side of a pair whose next send rotates —
     * the same thing it did before the constant moved, stated here rather than left implicit.
     */
    public static int seedForImminentRotation() {
        return REKEY_AFTER_SENDS - 1;
    }
}
