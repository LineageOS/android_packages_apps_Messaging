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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RCC.16's <b>remaining-lifetime floor</b>, applied to a group's roster.
 *
 * <h2>The blind spot this exists to close</h2>
 *
 * <p>The floor is <b>"under 30 days REMAINING"</b>, stated at every tier of RCC.16 v4.0:
 * A.4.3.1 §1(a) (the RCS SPN, on all Commits and within all proposals: "the certificate is not
 * expired <i>and has 30 days before expiry</i>"), Invariant 17 (<i>all</i> certificates in the
 * group, not just the committer's), A.4.1.2/A.4.1.3/A.4.1.5/A.4.1.6 (the client tier — which we
 * already enforce on peers at claim time, {@code MlsConfig#DEF_KP_MIN_REMAINING_DAYS}) and A.4.2.2
 * (the KDS must not even return a KeyPackage inside the window).
 *
 * <p>The client only ever asked <b>"already expired"</b>. Measured on a device,
 * 2026-09-10, group {@code 5B8905CD-…}: four members at 29/25/25/25 days remaining — every one below
 * the floor, every membership Commit refused by the server, and {@code expiredMemberCount} reporting
 * <b>zero</b>, so nothing was even considered and nothing in the log said why.
 *
 * <h2>What this deliberately does NOT do</h2>
 *
 * <p><b>It does not feed the era-advance decision.</b> Raising the expiry threshold to the floor
 * would make {@code EXPIRED_MEMBERS} fire, which drives {@code eraAdvance}, which re-creates the
 * group around the server's roster — and that rebuild must claim the stale member's KeyPackage,
 * which A.4.2.2 forbids the KDS from returning. A naive threshold change therefore turns a silently
 * wedged group into a LOOP of futile era advances, each burning an era and forcing every healthy
 * peer to re-join. This class SEES and NAMES the condition; deciding what to do about it stays with
 * the caller, and the only member we can lawfully repair is our own (A.4.3.2 §3).
 *
 * <p>Pure arithmetic over data the engine already produces, so it is host-testable and has no clock
 * of its own — {@code nowSecs} is always the caller's.
 */
public final class MlsCredentialFloor {

    /** RCC.16's floor, in days of REMAINING lifetime. The same 30 the KeyPackage claim path uses. */
    public static final long RCC16_MIN_REMAINING_DAYS = 30L;

    private MlsCredentialFloor() {}

    /** One member's standing against the floor. */
    public enum Standing {
        /** Comfortably above the floor. */
        OK,
        /** Not expired, but inside {@code now + floorDays} — the server will refuse Commits. */
        INSIDE_FLOOR,
        /** {@code notAfter <= now}. */
        EXPIRED,
        /** {@code notBefore > now} — issued for a window that has not opened. */
        NOT_YET_VALID,
        /** The leaf would not parse. NOT a synonym for any of the above. */
        UNREADABLE,
    }

    /** What a roster looks like against the floor. Immutable. */
    public static final class Report {
        /** Members inside the floor but NOT yet expired — the band nobody could see. */
        public final int insideFloor;
        /** Members whose credential has actually lapsed. */
        public final int expired;
        /** Members whose leaf would not parse. Counted apart, never folded into the others. */
        public final int unreadable;
        /** Total members examined. */
        public final int total;
        /** Leaf index → standing, in roster order. */
        public final Map<Integer, Standing> standings;
        /**
         * Human-readable "who and how long", ready for a log line — e.g.
         * {@code "+15715550104 groupLeaf=25d"}. Empty when nothing is below the floor.
         *
         * <p><b>The {@code groupLeaf=} label is load-bearing, not decoration.</b> This number is
         * the remaining life of the credential <i>the group's ratchet tree carries</i> for that
         * member, which is a different clock from the certificate the device currently holds and
         * from the one its published KeyPackage pool serves — all three routinely differ on the
         * same device at the same moment. Rendered unlabelled, this list is byte-identical to
         * {@code MlsFloorRebuild.Preflight#notRepublished}, which measures the POOL; the two
         * appeared for the same MSISDN at 24d and 41d in one document, and a roster reading was
         * quoted onward as a statement about the fleet's certificates. Print the artefact or the
         * number is not a measurement.
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
         * Would the server refuse a membership Commit on this roster today?
         *
         * <p>True when ANY member is expired or inside the floor: Invariant 17 evaluates the WHOLE
         * roster, so one stale credential is enough. An UNREADABLE member does not make this true —
         * we did not measure it, and refusing on something we could not read would stop a group we
         * have no evidence against.
         */
        public boolean membershipChangesWouldBeRefused() {
            return insideFloor > 0 || expired > 0;
        }

        @Override public String toString() {
            return "floor[total=" + total + " insideFloor=" + insideFloor + " expired=" + expired
                    + " unreadable=" + unreadable + (below.isEmpty() ? "" : " below=" + below) + "]";
        }
    }

    /**
     * Classify a roster.
     *
     * @param validity  leaf index → {@code {notBefore, notAfter}}, as {@code OpenMlsSession
     *                  #memberValidity} returns it; {@code {0,0}} means "could not read"
     * @param names     leaf index → MSISDN, for the report's {@link Report#below} lines. May be
     *                  empty or partial — a member with no name is reported by its leaf index,
     *                  which is still more than "expired=0" said
     * @param nowSecs   the caller's clock, epoch seconds
     * @param floorDays the floor to apply, normally {@link #RCC16_MIN_REMAINING_DAYS}
     * @return the report; never null, and empty input yields a report of zeroes rather than null
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
                    // Reported apart from EXPIRED even though both are "the window does not cover
                    // now": a not-yet-valid credential is a CLOCK or an issuance problem and the
                    // remedy is different, so collapsing them would send a reader after the wrong
                    // thing.
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
                    below.add((who == null || who.isEmpty() ? "leaf=" + e.getKey() : who)
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
     * Is one credential inside the floor (or past it) at {@code nowSecs}?
     *
     * <p>An UNKNOWN {@code notAfter} ({@code <= 0}) is <b>false</b>: "we could not read it" is not
     * evidence against a member, and this predicate gates refusals.
     */
    public static boolean insideFloor(final long notAfterSecs, final long nowSecs,
            final long floorDays) {
        if (notAfterSecs <= 0L) return false;
        return notAfterSecs - nowSecs < floorDays * 86400L;
    }
}
