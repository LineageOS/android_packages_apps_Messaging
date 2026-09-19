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
 * How old a DURABLE GUARD ENTRY is — measured with a clock nobody can move.
 *
 * <h2>The rule</h2>
 *
 * <p><b>A budget that survives a restart must not be refillable by moving the clock.</b> Every
 * budget in this codebase that lives on disk ages its entries by subtracting a stored
 * {@code System.currentTimeMillis()} from the current one, and that arithmetic answers "this entry
 * is old enough to drop" for any clock jump large enough — an NTP correction after a boot with a
 * dead RTC, a user setting the date, a carrier time update. The entry then disappears and the
 * allowance refills, with nothing in the log to say that time did not actually pass.
 *
 * <p>So the ages here are computed from {@code SystemClock.elapsedRealtime()} instead, which counts
 * milliseconds since boot INCLUDING deep sleep and which {@code settimeofday} cannot touch. The
 * caller stores the elapsed reading taken when the entry was written and asks this class how much
 * time has demonstrably passed since.
 *
 * <h2>Why this is a LOWER BOUND, deliberately, in every branch</h2>
 *
 * <p>{@code elapsedRealtime} resets to zero on a device reboot, so a stored reading and a current
 * one are only comparable within one boot. Both branches below therefore answer with the largest
 * age that can be proven, never the largest that is plausible:
 *
 * <ul>
 *   <li><b>Same boot</b> ({@code charged <= now}): the difference is exact.</li>
 *   <li><b>The reading went BACKWARDS</b> ({@code charged > now}): the device rebooted since the
 *       charge, so the entry predates this boot and at least the current uptime has passed. That is
 *       all we can prove — the device may have been switched off for a week, and no clock reachable
 *       from here can tell us so without being the wall clock we just refused to trust.</li>
 * </ul>
 *
 * <p>Because every answer is a lower bound on the true elapsed time, an entry can be RETAINED
 * longer than its window but can never be dropped EARLY. That is the same direction chosen for
 * epoch-secret retention — over-retention is recoverable, and a clock moving
 * backwards must never expire something early — applied to the mirror-image asset: there the thing
 * that must not disappear was a decryption secret, here it is the evidence that a budget was spent.
 *
 * <p><b>The price, stated plainly.</b> After a device reboot a spent budget refills only once the
 * WINDOW HAS ELAPSED IN UPTIME, however long the device was actually off. A conversation whose
 * hourly allowance was spent before a reboot waits an hour of uptime; one that spent its daily
 * allowance waits a day of uptime. That is deliberate — the alternative is to believe the wall
 * clock, which is exactly the refill this class exists to prevent — and it is bounded rather than
 * permanent: time still refills it, and the stalled-conversation notification's <b>Try again</b>
 * clears it outright when a person decides otherwise.
 */
public final class MlsMonotonicAge {

    private MlsMonotonicAge() {}

    /**
     * Milliseconds that have DEMONSTRABLY passed since an entry stamped {@code chargedElapsedMs} was
     * written, given the current {@code SystemClock.elapsedRealtime()} reading.
     *
     * <p>Never negative, and never larger than the true elapsed time. A nonsensical input (either
     * reading negative, which {@code elapsedRealtime} never returns) answers 0 — the youngest, most
     * conservative age — because "I cannot tell" must not be spelled the same way as "old enough to
     * drop".
     */
    public static long ageMs(final long chargedElapsedMs, final long nowElapsedMs) {
        if (chargedElapsedMs < 0L || nowElapsedMs < 0L) return 0L;
        // BACKWARDS means REBOOTED. elapsedRealtime is monotonic within a boot, so a stored reading
        // ahead of the current one can only have come from an earlier boot: the entry is at least as
        // old as this boot, and the uptime is the whole of what we can prove.
        if (chargedElapsedMs > nowElapsedMs) return nowElapsedMs;
        return nowElapsedMs - chargedElapsedMs;
    }

    /**
     * Whether the device has rebooted since {@code chargedElapsedMs} was recorded.
     *
     * <p>One-directional and honest about it: {@code true} is proof (the reading cannot go backwards
     * within a boot), {@code false} is only "no reboot is detectable this way" — a reboot followed by
     * more uptime than the entry ever saw is indistinguishable from an ordinary passage of time. That
     * asymmetry is safe because the undetected case makes {@link #ageMs} answer with a SMALLER age
     * than the truth, which retains the entry rather than dropping it.
     */
    public static boolean rebootedSince(final long chargedElapsedMs, final long nowElapsedMs) {
        return chargedElapsedMs >= 0L && nowElapsedMs >= 0L && chargedElapsedMs > nowElapsedMs;
    }
}
