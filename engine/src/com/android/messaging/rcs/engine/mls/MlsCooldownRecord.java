/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * "We last attempted this at T", durable across a process restart: one {@code elapsedRealtime}
 * stamp and a window during which the stamped operation may not repeat. Backs the rebuild episode
 * suppressor and the 1:1 re-establish cooldown, in separate key spaces. Ages are lower bounds
 * ({@link MlsMonotonicAge}), so a clock move can retain a spent cooldown but never refill one.
 * See docs/mls/budgets.md.
 */
public final class MlsCooldownRecord {

    /** No record: this operation has not been attempted, as far as the store knows. */
    public static final MlsCooldownRecord NONE = new MlsCooldownRecord(false, 0L);

    private static final String VERSION = "1";

    private final boolean mPresent;
    private final long mLastAttemptElapsedMs;

    private MlsCooldownRecord(final boolean present, final long lastAttemptElapsedMs) {
        mPresent = present;
        mLastAttemptElapsedMs = lastAttemptElapsedMs;
    }

    /**
     * Parse a stored record.
     *
     * @return {@link #NONE} when nothing is stored, a record when it parses, or {@code null} when
     *     something is stored and cannot be read (callers defer once and discard)
     */
    public static MlsCooldownRecord decode(final String raw) {
        if (raw == null || raw.isEmpty()) return NONE;
        final int bar = raw.indexOf('|');
        if (bar < 0 || !VERSION.equals(raw.substring(0, bar))) return null;
        final long at;
        try {
            at = Long.parseLong(raw.substring(bar + 1));
        } catch (final NumberFormatException e) {
            return null;
        }
        // elapsedRealtime is never negative: a negative stamp is unreadable, not very old.
        if (at < 0L) return null;
        return new MlsCooldownRecord(true, at);
    }

    /** A record stamped at {@code nowElapsedMs}. Zero is a legitimate stamp just after a boot. */
    public static MlsCooldownRecord attemptedAt(final long nowElapsedMs) {
        return new MlsCooldownRecord(true, nowElapsedMs < 0L ? 0L : nowElapsedMs);
    }

    /** The stored form. Round-trips through {@link #decode}. */
    public String encode() {
        return VERSION + "|" + mLastAttemptElapsedMs;
    }

    /** Whether this record names an attempt at all. {@link #NONE} is the only one that does not. */
    public boolean present() {
        return mPresent;
    }

    /**
     * Milliseconds demonstrably elapsed since the attempt, or {@link Long#MAX_VALUE} for {@link
     * #NONE} ("never attempted" is the oldest possible answer, not 0).
     */
    public long ageMs(final long nowElapsedMs) {
        if (!mPresent) return Long.MAX_VALUE;
        return MlsMonotonicAge.ageMs(mLastAttemptElapsedMs, nowElapsedMs);
    }

    /** Whether the attempt this record names is still inside {@code windowMs}. */
    public boolean withinCooldown(final long windowMs, final long nowElapsedMs) {
        if (!mPresent || windowMs <= 0L) return false;
        return ageMs(nowElapsedMs) < windowMs;
    }

    /**
     * How much of {@code windowMs} is left, or 0 when it has elapsed or nothing is recorded; for
     * logs.
     */
    public long remainingMs(final long windowMs, final long nowElapsedMs) {
        if (!withinCooldown(windowMs, nowElapsedMs)) return 0L;
        return windowMs - ageMs(nowElapsedMs);
    }

    /** Whether writing this record back would change anything the store already holds. */
    public boolean sameAs(final MlsCooldownRecord other) {
        return other != null && other.mPresent == mPresent
                && other.mLastAttemptElapsedMs == mLastAttemptElapsedMs;
    }
}
