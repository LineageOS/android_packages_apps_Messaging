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
 * The automatic rebuild's rolling window, in the form that survives a restart AND a clock move.
 *
 * <h2>What changed and why</h2>
 *
 * <p>{@code MlsRebuildLimiter} has always been on disk, for a reason it states itself: <b>the loop it
 * bounds is the one that kills the process</b>. It aged that window with
 * {@code System.currentTimeMillis()}, so any large enough jump forward — an NTP correction after a
 * boot with a dead RTC, a carrier time update, a person setting the date — rolled the window and
 * handed back the full allowance, with nothing in the log to say that time had not passed. G2's
 * era budget and G4's peer-health streak had just been made monotonic, which left this
 * one the only durable bound in the package still trusting a clock anybody can move.
 *
 * <p>So the stamp is now {@code SystemClock.elapsedRealtime()} and the age comes from
 * {@link MlsMonotonicAge}, whose every branch answers the largest age it can PROVE. A clock move can
 * retain a spent window; it can never refill one.
 *
 * <h2>The decision that had to be WRITTEN DOWN rather than defaulted</h2>
 *
 * <p>It was left open whether this window should stay wall-clocked, on the grounds that it
 * bounds OUR cost (local state destroyed, KeyPackages claimed) rather than the peer's, and that the
 * price accepted for the other budgets is six times larger here — a 6 h window, so after a reboot the
 * allowance refills only once 6 h have passed IN UPTIME. It is monotonic, and these are the reasons:
 *
 * <ol>
 *   <li><b>The threat model is a process restart, which {@code elapsedRealtime} survives exactly.</b>
 *       A reproducible crash on the rebuild path is the loop this class exists to bound, and a crash
 *       loop does not reboot the device. The reboot price is therefore paid outside the case the
 *       bound is for.</li>
 *   <li><b>The wall clock is most wrong exactly when the monotonic price would be paid.</b> The
 *       canonical large jump is an NTP correction shortly after a boot with a dead RTC — the same
 *       moment at which {@code elapsedRealtime} is at its most conservative. Keeping the wall clock
 *       would mean the one branch where it can be checked against nothing is also the branch where
 *       it refills the budget.</li>
 *   <li><b>The price is bounded twice over, and one of those bounds is a person.</b> Time still
 *       refills the window, and the stalled-conversation notification's <b>Try again</b> calls
 *       {@code resetRebuildRateBound}, which removes the persisted record outright. That path is
 *       reached whenever a refused rebuild leaves the conversation stalled, so a device that
 *       rebooted into a spent window is one tap from its allowance, not six hours.</li>
 *   <li><b>Two bounds on one operation must not trust different clocks.</b> A rebuild now charges
 *       G2 as well, and G2 is monotonic. The pair of them reading "within budget"
 *       for different reasons is precisely the accounting split that let seventeen re-creations stay
 *       plausible.</li>
 * </ol>
 *
 * <h2>The stored format changed meaning, so it carries a VERSION</h2>
 *
 * <p>Records written before this change hold {@code "<count>:<wallStart>"}. Read as an elapsed stamp a
 * wall stamp is enormously far in the future, which {@link MlsMonotonicAge} treats as a reboot and
 * answers "the current uptime" — conservative by luck rather than by design, which is not a thing to
 * leave load-bearing. The new form is {@code "1|<count>|<windowStartElapsedMs>"} and the old one is
 * RECOGNISED rather than discarded: {@link #decode} answers a record flagged
 * {@link #isLegacyWallClock()}, and {@link #rolled} adopts it by <b>keeping the count and opening a
 * fresh window at the current reading</b>.
 *
 * <p>That adoption can only make the throttle last LONGER than the wall clock would have (a window
 * with minutes left on it restarts with six hours), never shorter. Over-retention is the direction
 * we choose everywhere and is recoverable — by time, and by Try again. Discarding the record
 * instead would have handed back the allowance on an upgrade, which is the refill this prevents.
 *
 * <h2>UNREADABLE is not ABSENT</h2>
 *
 * <p>{@link #decode} answers {@link #EMPTY} for a record that is not there and {@code null} for one
 * that is there and cannot be read. Sharing a value between those two would restore a full allowance
 * on the strength of a parse failure.
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
     * A ceiling on an accepted count, so a corrupt or hostile file cannot store a number that makes
     * every comparison against it meaningless. Generously above any allowance we ship, because a
     * record written by a build with a larger one must still be READABLE — refusing it would spend a
     * refusal on a version skew rather than on a real fault.
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
     * @return {@link #EMPTY} when there is nothing stored, a record when it parses — flagged
     *     {@link #isLegacyWallClock()} if it was written in the legacy form — or
     *     {@code null} when something IS stored and cannot be read, which the caller must handle as
     *     a refusal and never as an empty window.
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
        // THE LEGACY FORM, "<count>:<wallStart>". Recognised, not discarded: a discard would hand
        // back the allowance on upgrade, which is the refill this class exists to prevent. The wall
        // stamp is deliberately NOT carried over — it is unusable in elapsed arithmetic and a field
        // that must never be used is an invitation.
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
        // AN UNSTARTED WINDOW HAS ITS OWN SPELLING, and does not borrow 0. Zero is a perfectly valid
        // elapsedRealtime reading (the boot instant), so writing it here would encode "no window has
        // been opened" as "a window opened at boot", which ages out immediately and hands back the
        // allowance — the exact refill this class exists to prevent, reintroduced through the codec.
        // The limiter always stores a ROLLED record, so this branch should be unreachable in
        // product; it round-trips faithfully rather than relying on that.
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
     * How much of {@code windowMs} is left before the allowance refills, or 0 once it has.
     *
     * <p>A lower bound like every other answer derived from {@link MlsMonotonicAge}: it can overstate
     * the wait (the age it is computed from can only understate the elapsed time), never understate
     * it. An unstarted or legacy record answers the whole window, because that is what
     * {@link #rolled} is about to open.
     */
    public long remainingMs(final long windowMs, final long nowElapsedMs) {
        if (mWindowStartElapsedMs == UNSTARTED) return windowMs;
        final long age = MlsMonotonicAge.ageMs(mWindowStartElapsedMs, nowElapsedMs);
        return age >= windowMs ? 0L : windowMs - age;
    }

    /**
     * The record as it should be STORED right now — the one place three related decisions live.
     *
     * <ul>
     *   <li>A record whose window has demonstrably elapsed rolls: the count is discarded and a fresh
     *       window opens at {@code nowElapsedMs}. Without this the count would accumulate across
     *       windows and the rate bound would quietly become the permanent total the limiter exists
     *       to avoid.</li>
     *   <li>An {@link #EMPTY} record opens its first window, at zero.</li>
     *   <li>A LEGACY record keeps its count and opens a fresh window. It is not rolled — that would
     *       be the upgrade-time refill — and its wall stamp is not consulted.</li>
     * </ul>
     *
     * <p>Returns {@code this} when nothing changed, so a caller can tell whether it owes the store a
     * write.
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
     * One more rebuild recorded, in the window this record already holds.
     *
     * <p>The window start is NOT moved: a charge inside a window must not extend it, or three
     * rebuilds in quick succession would push the refill six hours past the last one instead of six
     * hours past the first.
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
