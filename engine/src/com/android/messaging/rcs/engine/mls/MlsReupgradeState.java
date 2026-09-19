/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Re-upgrade bookkeeping for one conversation: the three re-upgrade columns and the two loops that
 * bring a downgraded conversation back. Loop 1 backs off from the last unexpected downgrade with
 * no attempt limit; loop 2 re-upgrades as soon as an eagerly downgraded conversation is healthy.
 * Immutable, since it is persisted whole. See docs/mls/downgrade.md.
 */
public final class MlsReupgradeState {

    /** Default backoff base in seconds; the app's own choice. */
    public static final long DEF_BACKOFF_BASE_S = 60L;
    /** Default max shift: the backoff plateaus at {@code 2^8 × 60 s}, about 4 h 16 min. */
    public static final int DEF_BACKOFF_MAX_SHIFT = 8;
    /** Default stability window in seconds; a longer gap makes the next downgrade a fresh one. */
    public static final long DEF_STABILITY_WINDOW_S = 86_400L;

    /** Never downgraded, or cleared; the two are treated the same. */
    public static final long NEVER = 0L;

    /** When the last unexpected downgrade happened, ms since epoch; {@link #NEVER} if unset. */
    public final long lastUnexpectedDowngradeMs;
    /** Re-upgrade attempts since the counter was last reset; never capped. */
    public final int attemptCount;
    /** Whether the app downgraded ahead of the engine; loop 2's only input. */
    public final boolean eagerlyDowngraded;

    /** Nothing has happened to this conversation. */
    public static final MlsReupgradeState NONE = new MlsReupgradeState(NEVER, 0, false);

    public MlsReupgradeState(final long lastUnexpectedDowngradeMs, final int attemptCount,
            final boolean eagerlyDowngraded) {
        this.lastUnexpectedDowngradeMs = Math.max(NEVER, lastUnexpectedDowngradeMs);
        this.attemptCount = Math.max(0, attemptCount);
        this.eagerlyDowngraded = eagerlyDowngraded;
    }

    /** Whether an unexpected downgrade has been recorded; loop 1's drive condition. */
    public boolean hasUnexpectedDowngrade() { return lastUnexpectedDowngradeMs != NEVER; }

    /**
     * The backoff for the next attempt in seconds, {@code (long)(int)(1 << shift) * base}. The
     * 32-bit intermediate is deliberate and matches other clients when the shift exceeds 30.
     */
    public static long backoffSeconds(final int attempts, final int maxShift, final long baseS) {
        final int shift = Math.min(Math.max(0, attempts), Math.max(0, maxShift));
        return (long) (int) (1 << shift) * baseS;
    }

    /** {@link #backoffSeconds(int, int, long)} for this state's own attempt count. */
    public long backoffSeconds(final int maxShift, final long baseS) {
        return backoffSeconds(attemptCount, maxShift, baseS);
    }

    /**
     * When loop 1 may next attempt, ms since epoch. Measured from the last downgrade, not the last
     * attempt, so a fresh downgrade restarts the schedule.
     */
    public long nextAttemptAtMs(final int maxShift, final long baseS) {
        if (!hasUnexpectedDowngrade()) return NEVER;
        return lastUnexpectedDowngradeMs + backoffSeconds(maxShift, baseS) * 1000L;
    }

    /** Whether an attempt is still inside the backoff; false when loop 1 does not apply. */
    public boolean withinBackoff(final long nowMs, final int maxShift, final long baseS) {
        if (!hasUnexpectedDowngrade()) return false;
        return nowMs < nextAttemptAtMs(maxShift, baseS);
    }

    /**
     * Records a new unexpected downgrade: reset the counter if the previous downgrade is older than
     * the stability window, then stamp. Stamping first would make the window always read zero and
     * the backoff never grow.
     */
    public MlsReupgradeState markUnexpectedDowngrade(final long nowMs,
            final long stabilityWindowS) {
        int nextCount = attemptCount;
        if (hasUnexpectedDowngrade()) {
            final long windowEndMs = lastUnexpectedDowngradeMs + stabilityWindowS * 1000L;
            if (nowMs >= windowEndMs) {
                nextCount = 0;                       // stable for a while -> a fresh problem
            }
        }
        return new MlsReupgradeState(nowMs, nextCount, eagerlyDowngraded);
    }

    /** Increments the counter before the attempt, so an attempt that crashes still backs off. */
    public MlsReupgradeState attempted() {
        return new MlsReupgradeState(lastUnexpectedDowngradeMs, attemptCount + 1,
                eagerlyDowngraded);
    }

    /** Sets or clears the eager-downgrade flag. */
    public MlsReupgradeState withEagerlyDowngraded(final boolean v) {
        return new MlsReupgradeState(lastUnexpectedDowngradeMs, attemptCount, v);
    }

    /**
     * Loop 2: eagerly downgraded and the engine reports the coarse status healthy. Coarse to match
     * other clients' group-status query; {@code PhoenixModeRequested} reads healthy at that level.
     */
    public boolean eligibleForFastReupgrade(final boolean engineReportsCoarseHealthy) {
        return eagerlyDowngraded && engineReportsCoarseHealthy;
    }

    /** Clears all three together, so a later downgrade never backs off from a stale timestamp. */
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
