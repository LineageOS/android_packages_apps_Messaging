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
 * <b>When OUR published KeyPackage pool is refreshed.</b>
 *
 * <h2>The estimate this policy runs on is structurally optimistic, and cannot be fixed by tuning</h2>
 *
 * <p>{@code PREF_KP_REMAINING} only decrements when we OPEN A WELCOME — but a package is consumed
 * the moment a peer CLAIMS it, whether or not a Welcome ever reaches us. Every abandoned establish,
 * every rebuild that re-claims, every claim whose Welcome we never open, consumes one and is counted
 * zero. So the number this policy is handed is an UPPER BOUND, never a count.
 *
 * <p>Device-measured 2026-08-15: establishing a 4-member group found BOTH of our devices serving
 * <b>last-resort</b> leaves — their one-time pools were empty — while the Google Messages peer in the same
 * group served a healthy one-time package. Their own estimates read 7, 7 and 9, and the weekly tick
 * had not fired (last publish 6.2, 6.4 and 5.5 days earlier). All three believed they were fine
 * while two were empty.
 *
 * <p>And there is no ground truth to consult: the KDS never reports our published count, and
 * {@code claimForUpgrade} CONSUMES a package per participant, so asking costs the thing being
 * measured.
 *
 * <p><b>With no reliable signal the safe direction is obvious</b>, and it is the reason every number
 * here leans the way it does: republishing is cheap and idempotent, while serving a last-resort leaf
 * makes every peer rotate their leaf on EVERY commit for as long as the pool stays empty.
 *
 * <h2>Three bounds, three different questions</h2>
 *
 * <p>They are stated together because the transport's comment on one of them was wrong twice about
 * another — it claimed the pool repair used "the same weekly gate as any other publish", and the
 * publish gate was not weekly and the repair gate was not the publish gate.
 *
 * <ul>
 *   <li>{@link #KP_REPUBLISH_MS} — the periodic tick, absent any consumption signal.</li>
 *   <li>{@link #KP_REPLENISH_AT} — the consumption signal itself: republish EARLY when the estimate
 *       says the pool is nearly gone.</li>
 *   <li>{@link #POOL_REPAIR_MIN_INTERVAL_MS} — a different trigger entirely. A Welcome we could not
 *       open means the KDS is serving a package whose private half this engine does not hold, which
 *       is a property of the PUBLISHED POOL rather than of any one peer.</li>
 * </ul>
 */
public final class MlsKeyPackagePolicy {

    private MlsKeyPackagePolicy() {}

    /**
     * How often the published pool is refreshed absent a consumption signal. 24 hours.
     *
     * <p><b>Was 7 days, and that was far too slow</b> — the 2026-08-15 measurement in the class
     * javadoc is what a weekly backstop looks like when the drain is faster than the backstop. A day
     * bounds the drain to a day at the cost of one small upload; the previous setting bounded it to
     * a week.
     */
    public static final long KP_REPUBLISH_MS = 24L * 60 * 60 * 1000;

    /**
     * Republish once the estimate reaches this — <b>not at zero</b>, or a peer arrives to an empty
     * pool.
     *
     * <p>Three rather than one for the reason the estimate exists at all: it runs high, so "three
     * left" may already be none, and the cost of being early is one idempotent upload.
     */
    public static final int KP_REPLENISH_AT = 3;

    /**
     * One pool repair per hour.
     *
     * <p>Time-based rather than a counter, deliberately: the condition is a property of the
     * published pool, not of any one peer, so a second peer claiming the same stale package must not
     * trigger a second upload.
     *
     * <p><b>Worth knowing before loosening it:</b> the trigger is PEER-SUPPLIED. Anything that
     * reaches the repair — including a malformed or hostile blob that merely LOOKS like a Welcome —
     * fires a pool regeneration, because the join failure is not currently distinguished by cause.
     * This interval is what bounds that. Do not widen the trigger without first making the republish
     * conditional on "no matching key package" rather than on "join returned null".
     */
    public static final long POOL_REPAIR_MIN_INTERVAL_MS = 60L * 60L * 1000L;

    /** {@link #POOL_REPAIR_MIN_INTERVAL_MS}, for a log line that prints the interval it refused on. */
    public static long poolRepairIntervalMs() {
        return POOL_REPAIR_MIN_INTERVAL_MS;
    }

    /**
     * Is the published pool low enough to replenish on its own account?
     *
     * @param remaining the LOWER of the two estimates — see the class javadoc; an upper bound either
     *     way, so this answers "possibly nearly empty", never "nearly empty"
     */
    public static boolean poolLow(final int remaining) {
        return remaining <= KP_REPLENISH_AT;
    }

    /**
     * The same question, asked only of a device that has published at least once.
     *
     * <p>The {@code lastPublishMs != 0} guard is not noise: on a device that has never published,
     * the estimate is zero because nothing has been counted rather than because a pool drained, and
     * treating that as "drained" would log a replenish-ahead-of-the-tick line about a pool that does
     * not exist yet. The first publish is driven by {@link #republishDue} instead.
     */
    public static boolean poolDrained(final long lastPublishMs, final int remaining) {
        return lastPublishMs != 0L && poolLow(remaining);
    }

    /**
     * Should we republish now?
     *
     * <p>Two independent triggers, and the clock alone is not enough: a device that is popular for a
     * week can be drained long before the week is out, and the timer would keep it silent.
     *
     * @param lastPublishMs {@code 0} if we have never published — which always republishes
     * @param drained       {@link #poolDrained} for this device
     */
    public static boolean republishDue(final long lastPublishMs, final long nowMs,
            final boolean drained) {
        if (lastPublishMs == 0L) return true;
        return drained || nowMs - lastPublishMs >= KP_REPUBLISH_MS;
    }

    /**
     * May we repair the pool after a Welcome we could not open?
     *
     * <p>Note the asymmetry with {@link #republishDue}: a never-repaired device has
     * {@code lastRepairMs == 0}, and {@code now - 0} is enormous, so the first repair is always
     * allowed. That is correct — the FIRST unopenable Welcome is exactly the one worth acting on.
     */
    public static boolean poolRepairAllowed(final long lastRepairMs, final long nowMs) {
        return nowMs - lastRepairMs >= POOL_REPAIR_MIN_INTERVAL_MS;
    }
}
