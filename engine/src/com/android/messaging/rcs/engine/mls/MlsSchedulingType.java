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
 * How the work now in flight was scheduled — the re-entrancy marker (rework item 5.5, invariant 44).
 *
 * <p>The guard that stops a retry from scheduling another retry. Its stated defer cost is an
 * infinite schedule/drain loop, and we already have the hazard shape without the guard:
 * {@code onControlRefused} self-heals on a divergence verdict, {@code reconcile} can advance an era,
 * an era advance sends control, and a refusal re-enters {@code onControlRefused}.
 *
 * <h2>Three-valued, not boolean — because there are two independent consumers</h2>
 *
 * <p>We model this today as {@code mDraining}, a {@code Set<String>} of conversations, set by the
 * inbound replay and read by exactly ONE site whose purpose is strand accounting. That is the right
 * instinct at the wrong shape, and the shape loses the second consumer entirely:
 *
 * <ul>
 *   <li>the <b>scheduler guard</b> — a handler about to schedule a retry checks this and declines,
 *       logging {@code Not scheduling the retry because already part of retry flow};</li>
 *   <li>the <b>transport guard</b> — a hard precondition that THROWS rather than declining, because
 *       reaching it means work arrived by a path that should have been impossible.</li>
 * </ul>
 *
 * <p>A boolean can express "am I in a retry?" but not "which kind of re-entrancy is this?", so the
 * two guards would collapse into one and the throwing one would silently become a soft decline. The
 * spec's own draft made this mistake and was corrected, which is why the three-valued reading is the
 * verified one.
 *
 * <h2>Ordering: stamp BEFORE results reach the postprocessors</h2>
 *
 * <p>Invariant 44, and it is the whole guard. Stamping after means the postprocessors run with the
 * marker still clear, and the first one that decides to schedule a retry does so — which is the loop.
 * The drain stamps twice: once on the outer context and once per element.
 */
public enum MlsSchedulingType {

    /**
     * Ordinary work, reached by its normal trigger. Scheduling a follow-up is allowed.
     *
     * <p>The default, and deliberately the zero value: an unstamped context must behave like
     * ordinary work, not like a privileged one.
     */
    NORMAL(0),

    /**
     * This work is ALREADY part of a retry or drain. Schedulers must not schedule more.
     *
     * <p>Convergence comes from the drain running again and finding nothing to do (§8.6), not from
     * each pass queuing its own successor. A pass that schedules while draining produces two
     * schedulers racing over the same conversation, each seeing the other's work as new.
     */
    RETRY_FLOW(1),

    /**
     * Work driven by an inbound notification, which the transport path must never see.
     *
     * <p>The second consumer's value. {@link #requireTransportAllowed} THROWS on it rather than
     * declining: arriving here means the work reached a transport entry point by a route that
     * should not exist, and continuing would send something built for a different context.
     */
    NOTIFICATION_DRIVEN(2);

    /** Persisted / logged. Never assume it equals {@link #ordinal()}. */
    public final int wire;

    MlsSchedulingType(final int wire) { this.wire = wire; }

    /**
     * Whether a handler holding this marker may schedule follow-up work.
     *
     * <p>Only {@link #NORMAL} may. Both other values mean something is already driving this
     * conversation.
     */
    public boolean allowsScheduling() { return this == NORMAL; }

    /**
     * The transport guard. Hard precondition, per §5.5 — throws rather than declining.
     *
     * @param entryPoint the transport entry point being guarded, named in the message
     * @throws IllegalArgumentException if this is {@link #NOTIFICATION_DRIVEN}
     */
    public void requireTransportAllowed(final String entryPoint) {
        if (this == NOTIFICATION_DRIVEN) {
            throw new IllegalArgumentException("Failed requirement: notification-driven MLS work "
                    + "reached the transport entry point '" + entryPoint + "'. This path should be "
                    + "unreachable — the work was built for a different context and sending it "
                    + "would put the wrong thing on the wire.");
        }
    }

    /** Decode. Anything unrecognised is {@link #NORMAL} — see the class doc on the zero value. */
    public static MlsSchedulingType fromWire(final int wire) {
        for (final MlsSchedulingType t : values()) {
            if (t.wire == wire) return t;
        }
        return NORMAL;
    }
}
