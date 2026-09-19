/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.log.LogMask;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RCC.16's remaining-lifetime floor (RCC.16 A.4.3.1 §1(a): every certificate in the group needs 30
 * days left), applied to a group's roster. Reports the condition only; it is deliberately not an
 * input to the era-advance decision. Pure arithmetic; the caller supplies the clock.
 * See docs/mls/credentials.md.
 */
public final class MlsCredentialFloor {

    /**
     * RCC.16's floor, in days of remaining lifetime; the same 30 the KeyPackage claim path uses.
     */
    public static final long RCC16_MIN_REMAINING_DAYS = 30L;

    private MlsCredentialFloor() {}

    /** One member's standing against the floor. */
    public enum Standing {
        OK,
        /** Not expired, but inside {@code now + floorDays}: the server will refuse Commits. */
        INSIDE_FLOOR,
        /** {@code notAfter <= now}. */
        EXPIRED,
        /** {@code notBefore > now}: a clock or issuance problem, reported apart from expiry. */
        NOT_YET_VALID,
        /** The leaf would not parse; never folded into another standing. */
        UNREADABLE,
    }

    /** What a roster looks like against the floor. Immutable. */
    public static final class Report {
        /** Members inside the floor but not yet expired. */
        public final int insideFloor;
        /** Members expired or not yet valid. */
        public final int expired;
        /** Members whose leaf would not parse. */
        public final int unreadable;
        public final int total;
        /** Leaf index to standing, in roster order. */
        public final Map<Integer, Standing> standings;
        /**
         * "Who and how long" for a log line, e.g. {@code "***0100 groupLeaf=25d"}; empty when
         * nothing is below the floor. The {@code groupLeaf=} label marks the roster's copy of the
         * credential, which differs from the device's certificate and from the published pool's.
         */
        public final List<String> below;

        Report(final int insideFloor, final int expired, final int unreadable, final int total,
                final Map<Integer, Standing> standings, final List<String> below) {
            this.insideFloor = insideFloor;
            this.expired = expired;
            this.unreadable = unreadable;
            this.total = total;
            this.standings = Collections.unmodifiableMap(standings);
            this.below = Collections.unmodifiableList(below);
        }

        /**
         * Would the server refuse a membership Commit on this roster today? True when any member is
         * expired or inside the floor; an unreadable member does not make it true.
         */
        public boolean membershipChangesWouldBeRefused() {
            return insideFloor > 0 || expired > 0;
        }

        @Override public String toString() {
            return "floor[total=" + total + " insideFloor=" + insideFloor + " expired=" + expired
                    + " unreadable=" + unreadable + (below.isEmpty() ? "" : " below=" + below)
                    + "]";
        }
    }

    /**
     * Classify a roster.
     *
     * @param validity  leaf index to {@code {notBefore, notAfter}}, as {@code OpenMlsSession
     *                  #memberValidity} returns it; {@code {0,0}} means "could not read"
     * @param names     leaf index to MSISDN for {@link Report#below}; may be partial, in which case
     *                  the leaf index is reported
     * @param nowSecs   the caller's clock, epoch seconds
     * @param floorDays the floor to apply, normally {@link #RCC16_MIN_REMAINING_DAYS}
     * @return never null; empty input yields a report of zeroes
     */
    public static Report classify(final Map<Integer, long[]> validity,
            final Map<Integer, String> names, final long nowSecs, final long floorDays) {
        final Map<Integer, Standing> standings = new LinkedHashMap<>();
        final List<String> below = new ArrayList<>();
        int insideFloor = 0, expired = 0, unreadable = 0, total = 0;
        if (validity != null) {
            final long floorSecs = floorDays * 86400L;
            for (final Map.Entry<Integer, long[]> e : validity.entrySet()) {
                final long[] w = e.getValue();
                if (w == null || w.length < 2) continue;
                total++;
                final long nb = w[0];
                final long na = w[1];
                final Standing s;
                if (nb == 0L && na == 0L) {
                    s = Standing.UNREADABLE;
                    unreadable++;
                } else if (na <= nowSecs) {
                    s = Standing.EXPIRED;
                    expired++;
                } else if (nb > nowSecs) {
                    // Counted with the expired, but reported under its own standing: the remedy
                    // differs.
                    s = Standing.NOT_YET_VALID;
                    expired++;
                } else if (na - nowSecs < floorSecs) {
                    s = Standing.INSIDE_FLOOR;
                    insideFloor++;
                } else {
                    s = Standing.OK;
                }
                standings.put(e.getKey(), s);
                if (s == Standing.INSIDE_FLOOR || s == Standing.EXPIRED
                        || s == Standing.NOT_YET_VALID) {
                    final String who = names == null ? null : names.get(e.getKey());
                    below.add((who == null || who.isEmpty()
                            ? "leaf=" + e.getKey() : LogMask.number(who))
                            + " groupLeaf=" + remainingDays(na, nowSecs) + "d");
                }
            }
        }
        return new Report(insideFloor, expired, unreadable, total, standings, below);
    }

    /** Whole days of lifetime left at {@code nowSecs}; negative once it has lapsed. */
    public static long remainingDays(final long notAfterSecs, final long nowSecs) {
        return Math.floorDiv(notAfterSecs - nowSecs, 86400L);
    }

    /**
     * Is one credential inside the floor (or past it) at {@code nowSecs}? An unknown {@code
     * notAfter} ({@code <= 0}) is false: this predicate gates refusals.
     */
    public static boolean insideFloor(final long notAfterSecs, final long nowSecs,
            final long floorDays) {
        if (notAfterSecs <= 0L) return false;
        return notAfterSecs - nowSecs < floorDays * 86400L;
    }
}
