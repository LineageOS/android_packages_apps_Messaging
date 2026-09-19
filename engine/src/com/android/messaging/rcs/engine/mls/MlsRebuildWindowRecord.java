/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * The automatic rebuild's durable rolling window, aged with {@link MlsMonotonicAge} so a clock move
 * can retain a spent window but never refill one. Stored as
 * {@code 1|<count>|<windowStartElapsedMs>}; the legacy {@code <count>:<wallStart>} form is adopted
 * with its count kept. {@link #decode} answers {@code null}, not {@link #EMPTY}, for an unreadable
 * record. See docs/mls/budgets.md.
 */
public final class MlsRebuildWindowRecord {

    /** No window has been opened yet. Never a valid {@code elapsedRealtime} reading. */
    public static final long UNSTARTED = -1L;

    /** The record that is not there. */
    public static final MlsRebuildWindowRecord EMPTY =
            new MlsRebuildWindowRecord(0, UNSTARTED, false);

    private static final String VERSION = "1";

    /** How {@link #UNSTARTED} is spelled on disk. Never a number; see {@link #encode()}. */
    private static final String UNSTARTED_TOKEN = "-";

    /**
     * Ceiling on an accepted count, so a corrupt file cannot store a meaningless number; well above
     * any shipped allowance so a record from a build with a larger one stays readable.
     */
    private static final int MAX_COUNT = 1024;

    private final int mCount;
    private final long mWindowStartElapsedMs;
    private final boolean mLegacyWallClock;

    private MlsRebuildWindowRecord(final int count, final long windowStartElapsedMs,
            final boolean legacyWallClock) {
        mCount = count;
        mWindowStartElapsedMs = windowStartElapsedMs;
        mLegacyWallClock = legacyWallClock;
    }

    /**
     * Parse a stored record, in either format.
     *
     * @return {@link #EMPTY} when nothing is stored, a record when it parses (flagged
     *     {@link #isLegacyWallClock()} for the legacy form), or {@code null} when something is
     *     stored and cannot be read, which the caller must treat as a refusal
     */
    public static MlsRebuildWindowRecord decode(final String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY;
        final int bar = raw.indexOf('|');
        if (bar >= 0) {
            if (!VERSION.equals(raw.substring(0, bar))) return null;
            final int second = raw.indexOf('|', bar + 1);
            if (second < 0) return null;
            final int count = parseCount(raw.substring(bar + 1, second));
            final String stamp = raw.substring(second + 1);
            if (count < 0) return null;
            if (UNSTARTED_TOKEN.equals(stamp)) {
                return new MlsRebuildWindowRecord(count, UNSTARTED, false);
            }
            final long start = parseStamp(stamp);
            if (start < 0L) return null;
            return new MlsRebuildWindowRecord(count, start, false);
        }
        // Legacy "<count>:<wallStart>": keep the count, drop the wall stamp.
        final int colon = raw.indexOf(':');
        if (colon <= 0) return null;
        final int count = parseCount(raw.substring(0, colon));
        if (count < 0 || parseStamp(raw.substring(colon + 1)) < 0L) return null;
        return new MlsRebuildWindowRecord(count, UNSTARTED, true);
    }

    private static int parseCount(final String s) {
        try {
            final int n = Integer.parseInt(s);
            return (n < 0 || n > MAX_COUNT) ? -1 : n;
        } catch (final NumberFormatException e) {
            return -1;
        }
    }

    private static long parseStamp(final String s) {
        try {
            final long n = Long.parseLong(s);
            return n < 0L ? -1L : n;
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }

    /** The stored form. Round-trips through {@link #decode}; never the legacy shape. */
    public String encode() {
        // An unstarted window is "-", not 0: zero is a valid elapsedRealtime reading and would age
        // out at once, refilling the allowance.
        return VERSION + '|' + mCount + '|'
                + (mWindowStartElapsedMs == UNSTARTED ? UNSTARTED_TOKEN
                        : Long.toString(mWindowStartElapsedMs));
    }

    public int count() {
        return mCount;
    }

    public long windowStartElapsedMs() {
        return mWindowStartElapsedMs;
    }

    /** Whether this came off disk in the legacy wall-clock form. */
    public boolean isLegacyWallClock() {
        return mLegacyWallClock;
    }

    /**
     * How much of {@code windowMs} is left before the allowance refills, or 0 once it has. May
     * overstate the wait, never understate it; an unstarted or legacy record answers the whole
     * window.
     */
    public long remainingMs(final long windowMs, final long nowElapsedMs) {
        if (mWindowStartElapsedMs == UNSTARTED) return windowMs;
        final long age = MlsMonotonicAge.ageMs(mWindowStartElapsedMs, nowElapsedMs);
        return age >= windowMs ? 0L : windowMs - age;
    }

    /**
     * The record as it should be stored now: an elapsed window rolls to a zero count at
     * {@code nowElapsedMs}, an empty record opens its first window, and a legacy record keeps its
     * count in a fresh window. Returns {@code this} when nothing changed, so no write is owed.
     */
    public MlsRebuildWindowRecord rolled(final long windowMs, final long nowElapsedMs) {
        if (mLegacyWallClock) return new MlsRebuildWindowRecord(mCount, nowElapsedMs, false);
        if (mWindowStartElapsedMs == UNSTARTED) {
            return new MlsRebuildWindowRecord(0, nowElapsedMs, false);
        }
        if (MlsMonotonicAge.ageMs(mWindowStartElapsedMs, nowElapsedMs) >= windowMs) {
            return new MlsRebuildWindowRecord(0, nowElapsedMs, false);
        }
        return this;
    }

    /** Whether {@code maxPerWindow} has been reached. Ask {@link #rolled} first. */
    public boolean spent(final int maxPerWindow) {
        return mCount >= maxPerWindow;
    }

    /**
     * One more rebuild in the current window. The window start does not move, so the refill is
     * counted from the first rebuild, not the last.
     */
    public MlsRebuildWindowRecord charged() {
        return new MlsRebuildWindowRecord(Math.min(mCount + 1, MAX_COUNT), mWindowStartElapsedMs,
                false);
    }

    @Override
    public String toString() {
        return "MlsRebuildWindowRecord{count=" + mCount + ", windowStart=" + mWindowStartElapsedMs
                + (mLegacyWallClock ? ", LEGACY wall clock" : "") + '}';
    }
}
