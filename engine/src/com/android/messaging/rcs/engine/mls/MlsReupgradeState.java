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
 * Re-upgrade bookkeeping — the three {@code conversation_encryption} columns and the two loops that
 * bring a downgraded conversation back. Rework item {@code 11.7}, §9.7l, invariants 105/106/107.
 *
 * <h2>What "coming back" costs, and why a latch was not enough</h2>
 *
 * <p>What we had was a two-bit latch with no history: an MLS bit that could be set or cleared, and
 * nothing that remembered a conversation had ever been downgraded against its will. That is
 * sufficient to stop encrypting and insufficient to ever start again — there is no signal saying
 * "this one wants to come back", no counter to space the attempts, and nothing to stop a conversation
 * whose peer is permanently non-MLS from retrying on every single send.
 *
 * <p>Three columns carry the whole of it:
 *
 * <table>
 *   <tr><th>column</th><th>written when</th></tr>
 *   <tr><td>{@link #lastUnexpectedDowngradeMs}</td>
 *       <td>a downgrade whose reason has {@code expected == false} — see
 *           {@link MlsDowngradeReason#expected}</td></tr>
 *   <tr><td>{@link #attemptCount}</td>
 *       <td>incremented on every revive attempt; reset by the stability window; never capped</td></tr>
 *   <tr><td>{@link #eagerlyDowngraded}</td>
 *       <td>set when the app's own conversation state was downgraded ahead of the engine</td></tr>
 * </table>
 *
 * <h2>Two loops, and they are not the same loop</h2>
 *
 * <p><b>Loop 1 — slow, backoff-governed.</b> Driven by {@link #lastUnexpectedDowngradeMs}, spaced by
 * {@link #backoffSeconds}, counted by {@link #attemptCount}. This is the one that handles "the peer
 * downgraded us and we would like to be encrypted again eventually".
 *
 * <p><b>Loop 2 — fast, counterless.</b> {@link #eligibleForFastReupgrade} — its whole condition is
 * "the app marked this conversation eagerly downgraded AND the engine now reports coarse status
 * HEALTHY". <b>No backoff and no counter.</b> It exists because an eager downgrade is the app getting
 * ahead of the engine, so the moment the engine says the group is fine, the reason for the downgrade
 * has evaporated and there is nothing to back off from.
 *
 * <p><b>Both loops can fire for the same conversation</b> (§22.1-60). That is Google Messages' behaviour and
 * not a defect to design out.
 *
 * <h2>The precision that will look like a bug later</h2>
 *
 * <p>{@link #backoffSeconds} computes a <b>32-bit</b> shift and <i>then</i> widens:
 * {@code (long)(int)(1 << shift) * base}, <b>not</b> {@code 1L << shift}. With the cap at or below 30
 * the two agree; above it they diverge sharply, and Google Messages' overflows. We reproduce the overflow
 * because a backoff that silently disagrees with Google Messages' is worse than one that is faithfully odd —
 * and because the cap is server-delivered, so we do not control whether it is ever pushed there.
 *
 * <p><b>There is NO absolute attempt limit.</b> The counter only feeds a clamped exponent. Google Messages
 * backs off forever but never gives up permanently, and a "max attempts, then stop" reading of this
 * loop would strand conversations that a transient outage downgraded.
 *
 * <h2>The three numbers we had to choose</h2>
 *
 * <p>The backoff base, the max shift and the stability window are Phenotype longs — <b>server-delivered
 * and NOT present in the APK</b> (NEEDS-CAPTURE §22.1-59). The defaults below are OURS, chosen to be
 * defensible rather than guessed at Google Messages', and they are knobs so a capture can correct them
 * without a code change. Do not record them anywhere as Google Messages' values.
 *
 * <p>Immutable: every mutator returns a new instance, because this is persisted state written whole.
 */
public final class MlsReupgradeState {

    /**
     * Our default backoff base, in seconds. 60s ⇒ the first retry is a minute out, the fifth about
     * half an hour, the eighth about four hours.
     */
    public static final long DEF_BACKOFF_BASE_S = 60L;
    /**
     * Our default max shift. 8 ⇒ the backoff plateaus at {@code 2^8 × 60s} ≈ 4h16m and never grows
     * further, which keeps a permanently non-MLS peer at roughly six probes a day rather than one a
     * fortnight.
     */
    public static final int DEF_BACKOFF_MAX_SHIFT = 8;
    /**
     * Our default stability window, in seconds. 24h ⇒ a conversation that ran a full day between
     * downgrades is treated as a fresh problem rather than a continuing one.
     */
    public static final long DEF_STABILITY_WINDOW_S = 86_400L;

    /**
     * The sentinel for "never downgraded, or cleared" — Google Messages writes {@code Instant.EPOCH} rather
     * than null when it clears the MLS state, and the two must be treated identically.
     */
    public static final long NEVER = 0L;

    /** {@code mls_last_unexpected_downgrade_timestamp}, ms since epoch. {@link #NEVER} if unset. */
    public final long lastUnexpectedDowngradeMs;
    /** {@code mls_reupgrade_after_unexpected_downgrade_attempt_count}. Never capped. */
    public final int attemptCount;
    /** {@code mls_eagerly_downgraded} — drives loop 2 alone. */
    public final boolean eagerlyDowngraded;

    /** Nothing has happened to this conversation. */
    public static final MlsReupgradeState NONE = new MlsReupgradeState(NEVER, 0, false);

    public MlsReupgradeState(final long lastUnexpectedDowngradeMs, final int attemptCount,
            final boolean eagerlyDowngraded) {
        this.lastUnexpectedDowngradeMs = Math.max(NEVER, lastUnexpectedDowngradeMs);
        this.attemptCount = Math.max(0, attemptCount);
        this.eagerlyDowngraded = eagerlyDowngraded;
    }

    /** Whether an unexpected downgrade has ever been recorded — the loop-1 drive condition. */
    public boolean hasUnexpectedDowngrade() { return lastUnexpectedDowngradeMs != NEVER; }

    /**
     * The backoff for the NEXT attempt, in seconds — {@code 2^min(attempts, cap) × base}.
     *
     * <p>See the class doc on the 32-bit shift. The intermediate is deliberately an {@code int}.
     */
    public static long backoffSeconds(final int attempts, final int maxShift, final long baseS) {
        final int shift = Math.min(Math.max(0, attempts), Math.max(0, maxShift));
        // (long)(int)(1 << shift) * base — NOT 1L << shift. The cast chain is the specification.
        return (long) (int) (1 << shift) * baseS;
    }

    /** {@link #backoffSeconds(int, int, long)} for this state's own attempt count. */
    public long backoffSeconds(final int maxShift, final long baseS) {
        return backoffSeconds(attemptCount, maxShift, baseS);
    }

    /**
     * When loop 1 may next attempt a revive, in ms since epoch — {@code last + backoff}.
     *
     * <p>Measured <b>from the last downgrade</b>, not from the last attempt. That is Google Messages' shape
     * and it has a consequence worth knowing: the whole retry schedule is anchored to one instant, so
     * attempts get further apart without the anchor ever moving, and a fresh downgrade restarts the
     * schedule rather than extending it.
     */
    public long nextAttemptAtMs(final int maxShift, final long baseS) {
        if (!hasUnexpectedDowngrade()) return NEVER;
        return lastUnexpectedDowngradeMs + backoffSeconds(maxShift, baseS) * 1000L;
    }

    /**
     * Loop 1's HARD SKIP — whether a revive attempt is still inside the backoff period.
     *
     * <p>A conversation that has never been unexpectedly downgraded is <b>not</b> in backoff: loop 1
     * simply does not apply to it, and reporting "in backoff" would read as "wait" where the right
     * answer is "this loop has no opinion".
     */
    public boolean withinBackoff(final long nowMs, final int maxShift, final long baseS) {
        if (!hasUnexpectedDowngrade()) return false;
        return nowMs < nextAttemptAtMs(maxShift, baseS);
    }

    /**
     * Invariant 106 — <b>RESET-THEN-STAMP</b>. Record a new unexpected downgrade.
     *
     * <p>The order inside this one method is the invariant: consult the stability window against the
     * <i>previous</i> timestamp and reset the counter <b>before</b> writing the new one. Reversing it
     * makes the window always read as zero, so the counter resets on every downgrade and the backoff
     * never grows — the loop degenerates into a fixed one-base-interval retry.
     *
     * <p>The reset rule itself: reset <b>only</b> when this downgrade happens more than the stability
     * window after the previous one — "the conversation was stable for a while, so treat this as a
     * fresh problem". A rapid succession of downgrades keeps counting up.
     *
     * @param nowMs the moment of this downgrade
     */
    public MlsReupgradeState markUnexpectedDowngrade(final long nowMs, final long stabilityWindowS) {
        int nextCount = attemptCount;
        if (hasUnexpectedDowngrade()) {
            final long windowEndMs = lastUnexpectedDowngradeMs + stabilityWindowS * 1000L;
            if (nowMs >= windowEndMs) {
                nextCount = 0;                       // stable for a while -> a fresh problem
            }
        }
        return new MlsReupgradeState(nowMs, nextCount, eagerlyDowngraded);
    }

    /**
     * Invariant 107 — <b>INCREMENT BEFORE ATTEMPT</b>.
     *
     * <p>The counter is incremented before the revive is attempted, so a revive that <b>crashes</b>
     * still backs off. Incrementing on success only turns a reproducible crash into an unthrottled
     * retry loop, which is the exact failure the counter exists to bound.
     */
    public MlsReupgradeState attempted() {
        return new MlsReupgradeState(lastUnexpectedDowngradeMs, attemptCount + 1, eagerlyDowngraded);
    }

    /** Set or clear {@code mls_eagerly_downgraded} — loop 2's only input. */
    public MlsReupgradeState withEagerlyDowngraded(final boolean v) {
        return new MlsReupgradeState(lastUnexpectedDowngradeMs, attemptCount, v);
    }

    /**
     * Loop 2 — the fast, counterless re-upgrade condition.
     *
     * <p>"The app marked this conversation eagerly downgraded AND the engine now reports the
     * <b>coarse</b> status HEALTHY." Coarse, not fine: {@code PhoenixModeRequested(17)} reports
     * {@code HEALTHY} at the coarse level while being thoroughly downgraded at the fine one, and this
     * predicate deliberately takes the coarse answer because that is what Google Messages'
     * {@code GET_GROUP_STATUS} returns.
     */
    public boolean eligibleForFastReupgrade(final boolean engineReportsCoarseHealthy) {
        return eagerlyDowngraded && engineReportsCoarseHealthy;
    }

    /**
     * {@code clearMlsState} — zero all three columns together.
     *
     * <p>Together is the point: leaving the timestamp behind while clearing the counter would make
     * the next downgrade compute a backoff from an epoch that no longer describes anything.
     */
    public MlsReupgradeState cleared() { return NONE; }

    @Override public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof MlsReupgradeState)) return false;
        final MlsReupgradeState r = (MlsReupgradeState) o;
        return lastUnexpectedDowngradeMs == r.lastUnexpectedDowngradeMs
                && attemptCount == r.attemptCount
                && eagerlyDowngraded == r.eagerlyDowngraded;
    }

    @Override public int hashCode() {
        return (int) (lastUnexpectedDowngradeMs ^ (lastUnexpectedDowngradeMs >>> 32))
                * 31 + attemptCount * 2 + (eagerlyDowngraded ? 1 : 0);
    }

    @Override public String toString() {
        return "reupgrade{lastUnexpected=" + lastUnexpectedDowngradeMs
                + " attempts=" + attemptCount
                + " eagerlyDowngraded=" + eagerlyDowngraded + "}";
    }
}
