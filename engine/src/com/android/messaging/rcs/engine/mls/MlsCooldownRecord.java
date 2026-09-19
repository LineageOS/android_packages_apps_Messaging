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
 * "We last attempted this at T" — in the form that survives a process restart.
 *
 * <h2>What it is, and why it is one class rather than two</h2>
 *
 * <p>Two of the subsystem's gates are the same shape: a single stamp, and a window during which the
 * operation it stamps may not be repeated. Neither counts anything — the whole state is "when did we
 * last try", so a record is either absent or one number.
 *
 * <ul>
 *   <li>The <b>rebuild episode suppressor</b> ({@code MlsProviderTransport.REBUILD_EPISODE_MS}, 60s).
 *       It sits in front of the two durable rebuild allowances and exists so that one BURST of
 *       traffic cannot spend hours of them on a single fault — its own site says "charging first and
 *       de-duplicating afterwards is what let a burst spend six hours of allowance in ten
 *       seconds".</li>
 *   <li>The <b>1:1 re-establish cooldown</b> ({@code MlsProviderTransport.REESTABLISH_COOLDOWN_MS},
 *       10 min). It meters re-establish ATTEMPTS, MOST of which reach a claim on one of the peer's
 *       one-time KeyPackages out of a small pool we do not replenish — so this is a bound on
 *       somebody else's scarce resource. Attempts rather than claims because two arms return before
 *       the claim is reached; the error over-protects the pool and never under-protects it. See
 *       {@code MlsPeerGuard.claimReestablishAttempt}.</li>
 * </ul>
 *
 * <p>They share the codec and the clock rule and nothing else: they live in different key spaces, so
 * spending one cannot spend the other. That is the same sharing {@link MlsRebuildWindowRecord}
 * already does for two limiters in two preference files.
 *
 * <h2>Why these have to be durable — the durability argument, on the counters it did not name</h2>
 *
 * <p>G2's era budget and G4's peer-health streak were moved onto disk because <b>the loop
 * they bound is the one that kills the process</b>: an in-memory bound over a crash loop is handed
 * back on every restart while the cost it was protecting is paid again. Both stamps here have
 * exactly that shape and were never asked the question.
 *
 * <p>The suppressor is the sharper of the two. A crash-restart loop <i>is</i> a burst, and it is a
 * burst whose suppressor reset on every restart while the six-hour allowance it protects did not —
 * so the very failure mode the suppressor was written for could walk straight through it. The
 * cooldown is the mirror image on somebody else's resource: a restart handed back the whole
 * ten-minute allowance and the peer paid for it a KeyPackage at a time.
 *
 * <h2>The clock — the retention rule, and which direction the error goes</h2>
 *
 * <p>Stamps are {@code SystemClock.elapsedRealtime()} and ages come from {@link MlsMonotonicAge},
 * whose every branch answers <b>the largest age it can PROVE</b>. Because the age is a lower bound
 * on the true elapsed time, {@link #withinCooldown} says "still cooling" for at least as long as it
 * should and never for less: <b>a clock move can retain a spent cooldown and can never refill
 * one.</b> The price, stated plainly, is the same one {@code MlsMonotonicAge} charges everywhere
 * else — after a reboot the window elapses in UPTIME, however long the device was actually off — and
 * it is bounded rather than permanent, because the operator lever (the stalled-conversation
 * notification's <b>Try again</b>) removes the record outright.
 *
 * <h2>UNREADABLE is not ABSENT</h2>
 *
 * <p>{@link #decode} answers {@link #NONE} for "nothing is stored" and {@code null} for "something IS
 * stored and cannot be read". A caller must not spell those the same way: {@link #NONE} means the
 * operation is free to proceed, while an unreadable record means we cannot say whether it just ran,
 * and the safe reading of that is to defer once and discard — which is what
 * {@code MlsRebuildLimiter.claim} already does for its own record.
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
     * @return {@link #NONE} when there is nothing stored, a record when it parses, or {@code null}
     *     when something IS stored and cannot be read.
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
        // elapsedRealtime is never negative, so a negative stamp is a record we cannot read rather
        // than one that happens to be very old — and "cannot read" must not become "free to go".
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
     * Milliseconds demonstrably elapsed since the attempt, or {@link Long#MAX_VALUE} for
     * {@link #NONE}.
     *
     * <p>{@link Long#MAX_VALUE} rather than 0 for the absent case, and the direction is the whole
     * point: "never attempted" is the OLDEST possible answer, and spelling it 0 would make an
     * unattempted operation read as one that just ran.
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
     * How much of {@code windowMs} is left, or 0 when the window has elapsed or nothing is recorded.
     *
     * <p>For the log line only. A refusal that cannot say how long it will last is the shape a person
     * reads as "broken" rather than "wait".
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
