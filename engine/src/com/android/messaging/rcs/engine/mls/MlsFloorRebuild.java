/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The repair for a group that no Commit can reach because a peer's in-group leaf is inside the
 * RCC.16 remaining-lifetime floor: an era advance re-creates the group from the members' published
 * KeyPackages, whose certificates turn over on every re-mint. Gated in two stages, {@link #assess}
 * (roster, no network) and {@link #preflight} (the packages the advance will consume); it is all or
 * nothing, since the new era must match the server's whole roster. See docs/mls/credentials.md.
 */
public final class MlsFloorRebuild {

    private MlsFloorRebuild() {}

    // ---- stage 1: is this group a candidate? From the roster, before any claim ----

    /** What the roster says about whether a rebuild is the right operation here. */
    public enum Candidacy {
        /** Nothing is below the floor; an era advance would re-Welcome every member for nothing. */
        NOT_WEDGED,
        /**
         * Only our own leaf is below the floor. RCC.16 A.4.3.2 §3 exempts exactly that leaf, so the
         * §9.5.3 Self-Update is the far cheaper repair.
         */
        SELF_UPDATE_SUFFICES,
        /**
         * A peer is below the floor and so is the certificate we would build the new group with; a
         * rebuild would re-wedge it at once. The remedy is a new certificate.
         */
        OUR_CERTIFICATE_TOO_OLD,
        /** The roster could not be read; not {@link #NOT_WEDGED}. */
        UNREADABLE,
        /**
         * A peer is below the floor and our certificate is above it. Proceed to {@link #preflight}.
         */
        CANDIDATE,
    }

    /**
     * Is this group a candidate for a floor rebuild?
     *
     * @param roster        the roster classified against the floor, or {@code null} if unreadable
     * @param ourLeafIndex  our own leaf index, or negative when the engine could not say
     * @param ourCertNotAfterSecs  {@code notAfter} of the certificate we hold now, the one a
     *                      rebuild would use; {@code <= 0} means unknown
     * @param nowSecs       the caller's clock, epoch seconds
     * @param floorDays     the floor, normally {@link MlsCredentialFloor#RCC16_MIN_REMAINING_DAYS}
     */
    public static Candidacy assess(final MlsCredentialFloor.Report roster, final int ourLeafIndex,
            final long ourCertNotAfterSecs, final long nowSecs, final long floorDays) {
        if (roster == null) return Candidacy.UNREADABLE;
        if (!roster.membershipChangesWouldBeRefused()) return Candidacy.NOT_WEDGED;
        // Is a peer blocking, or only us? Our own leaf must not justify a rebuild a Self-Update
        // handles.
        boolean peerBelow = false;
        for (final Map.Entry<Integer, MlsCredentialFloor.Standing> e :
                roster.standings.entrySet()) {
            if (e.getKey() != null && ourLeafIndex >= 0 && e.getKey().intValue() == ourLeafIndex) {
                continue;
            }
            if (blocking(e.getValue())) {
                peerBelow = true;
                break;
            }
        }
        if (!peerBelow) return Candidacy.SELF_UPDATE_SUFFICES;
        // Our certificate, not our group leaf (stale by construction here). An unknown one is
        // refused.
        if (ourCertNotAfterSecs <= 0L
                || MlsCredentialFloor.insideFloor(ourCertNotAfterSecs, nowSecs, floorDays)) {
            return Candidacy.OUR_CERTIFICATE_TOO_OLD;
        }
        return Candidacy.CANDIDATE;
    }

    /** EXPIRED, NOT_YET_VALID and INSIDE_FLOOR refuse a Commit; UNREADABLE and OK do not. */
    private static boolean blocking(final MlsCredentialFloor.Standing s) {
        return s == MlsCredentialFloor.Standing.INSIDE_FLOOR
                || s == MlsCredentialFloor.Standing.EXPIRED
                || s == MlsCredentialFloor.Standing.NOT_YET_VALID;
    }

    // ---- stage 2: may the rebuild proceed? From the claimed packages' certificates ----

    /** The verdict on a set of freshly claimed KeyPackages. */
    public enum Readiness {
        /** Every member's claimed package carries a certificate above the floor. */
        READY,
        /** At least one member's published pool is still stale. */
        MEMBER_NOT_REPUBLISHED,
        /** At least one claimed package carries no readable certificate window. */
        UNDATED_PACKAGE,
        /** Nothing was claimed. */
        NOTHING_CLAIMED,
    }

    /** The pre-flight's answer; names members rather than counting them. */
    public static final class Preflight {
        public final Readiness readiness;
        /**
         * Members whose claimed package is inside the floor, as {@code "+15550100 poolCert=41d"}.
         * The {@code poolCert=} label marks the published pool's certificate, not the roster's
         * copy.
         */
        public final List<String> notRepublished;
        /** Members whose claimed package carried no readable certificate window. */
        public final List<String> undated;
        /** Members whose claimed package is above the floor. */
        public final List<String> ready;

        Preflight(final Readiness readiness, final List<String> notRepublished,
                final List<String> undated, final List<String> ready) {
            this.readiness = readiness;
            this.notRepublished = Collections.unmodifiableList(notRepublished);
            this.undated = Collections.unmodifiableList(undated);
            this.ready = Collections.unmodifiableList(ready);
        }

        public boolean go() {
            return readiness == Readiness.READY;
        }

        public int total() {
            return notRepublished.size() + undated.size() + ready.size();
        }

        @Override public String toString() {
            return "rebuild[" + readiness + " ready=" + ready.size() + "/" + total()
                    + (notRepublished.isEmpty() ? "" : " notRepublished=" + notRepublished)
                    + (undated.isEmpty() ? "" : " undated=" + undated) + "]";
        }
    }

    /**
     * Classify the certificates of the packages an era advance is about to build a group from. Pass
     * the packages that will actually be consumed: a pool can hold packages from before and after a
     * re-mint.
     *
     * @param claimedCertNotAfter member MSISDN to the {@code notAfter} of that member's claimed
     *                            package's leaf certificate, in claim order; {@code <= 0} means the
     *                            engine could not date it
     * @param nowSecs             the caller's clock, epoch seconds
     * @param floorDays           the floor to apply
     */
    public static Preflight preflight(final Map<String, Long> claimedCertNotAfter,
            final long nowSecs, final long floorDays) {
        final List<String> stale = new ArrayList<>();
        final List<String> undated = new ArrayList<>();
        final List<String> ready = new ArrayList<>();
        if (claimedCertNotAfter != null) {
            for (final Map.Entry<String, Long> e : claimedCertNotAfter.entrySet()) {
                final String who = (e.getKey() == null || e.getKey().isEmpty())
                        ? "(unnamed)" : e.getKey();
                final Long na = e.getValue();
                if (na == null || na.longValue() <= 0L) {
                    undated.add(who);
                } else if (MlsCredentialFloor.insideFloor(na.longValue(), nowSecs, floorDays)) {
                    stale.add(who + " poolCert="
                            + MlsCredentialFloor.remainingDays(na.longValue(), nowSecs) + "d");
                } else {
                    ready.add(who);
                }
            }
        }
        final Readiness r;
        if (stale.isEmpty() && undated.isEmpty() && ready.isEmpty()) {
            r = Readiness.NOTHING_CLAIMED;
        } else if (!stale.isEmpty()) {
            // Stale outranks undated in the verdict only; both lists are carried.
            r = Readiness.MEMBER_NOT_REPUBLISHED;
        } else if (!undated.isEmpty()) {
            r = Readiness.UNDATED_PACKAGE;
        } else {
            r = Readiness.READY;
        }
        return new Preflight(r, stale, undated, ready);
    }

    public static LinkedHashMap<String, Long> newClaimMap() {
        return new LinkedHashMap<>();
    }

    // ---- log lines, shared by the device and the tests ----

    /** The line for a candidacy that is not a candidate. {@code null} for {@link #assess}'s go. */
    public static String candidacyLine(final Candidacy c, final String key, final long floorDays) {
        if (c == null || c == Candidacy.CANDIDATE) return null;
        switch (c) {
            case NOT_WEDGED:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " — NOT NEEDED: no member is inside the "
                        + floorDays + "-day floor, so Commits are not being refused for a "
                        + "credential and an era advance would re-Welcome everyone for nothing.";
            case SELF_UPDATE_SUFFICES:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " — DECLINED: only OUR OWN leaf is inside the "
                        + floorDays + "-day floor, and A.4.3.2 §3 exempts exactly that leaf from "
                        + "the expiry check. RCC.16 §9.5.3's Self-Update repairs this in one epoch "
                        + "without making anyone re-join; a rebuild here would be a self-inflicted "
                        + "era advance.";
            case OUR_CERTIFICATE_TOO_OLD:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " is NEEDED but CANNOT HELP: the certificate WE "
                        + "hold is itself inside the " + floorDays + "-day floor (or could not be "
                        + "read), so the rebuilt group would carry our own stale leaf and be wedged "
                        + "from the moment it existed. The remedy is a fresh KDS mint, not a "
                        + "rebuild — this will re-arm on the next one.";
            case UNREADABLE:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " — DECLINED: the roster is unreadable. NOT "
                        + "concluding that it is healthy and NOT era-advancing on an unmeasured "
                        + "group; an advance makes every member re-join.";
            default:
                return null;
        }
    }

    /** The line for a pre-flight that refuses. {@code null} when it goes. */
    public static String preflightLine(final Preflight p, final String key, final long floorDays) {
        if (p == null || p.go()) return null;
        switch (p.readiness) {
            case MEMBER_NOT_REPUBLISHED:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " — REFUSED, and the group stays wedged: "
                        + p.notRepublished + " still publish a KeyPackage whose certificate is "
                        + "inside the " + floorDays + "-day floor" + (p.undated.isEmpty() ? ""
                                : " (and " + p.undated + " could not be dated)")
                        + ", while " + p.ready.size() + " of " + p.total()
                        + " member(s) are ready. "
                        + "Rebuilding around a stale certificate re-creates the wedge, and a "
                        + "rebuild cannot be partial — the new era must match the server's whole "
                        + "roster. Nothing further is possible here until those members re-mint "
                        + "and republish; that is THEIR device's action, not ours.";
            case UNDATED_PACKAGE:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " — REFUSED: the claimed KeyPackage for "
                        + p.undated + " carries no readable leaf certificate window, so its "
                        + "remaining lifetime is UNKNOWN. That is not evidence the member is "
                        + "stale and not evidence it is fresh; an era advance is too expensive to "
                        + "issue on an unmeasured roster.";
            case NOTHING_CLAIMED:
                return "floor rebuild for " + MlsConversationKey.forLog(key)
                        + " — REFUSED: no KeyPackage was claimed for any "
                        + "member, so there is nothing to rebuild the group from.";
            default:
                return null;
        }
    }

    /**
     * Are the two era-advance levers set? Returns the refusal, or {@code null} to go. A create that
     * reuses a context id the server already holds returns OK without moving the era, and every
     * group a floor rebuild repairs has one; with the destructive fallback on, that silent non-take
     * drops local state. So a floor rebuild requires {@code
     * debug.rcs.mls_advance_create_fallback=0} and a fresh context id mode.
     *
     * @param destructiveFallback {@code debug.rcs.mls_advance_create_fallback}, default true
     * @param freshCtxIdMode      {@code debug.rcs.mls_advance_fresh_ctxid}, default {@code "off"}
     */
    public static String leverRefusal(final boolean destructiveFallback,
            final String freshCtxIdMode, final String key) {
        final String mode = (freshCtxIdMode == null) ? "off" : freshCtxIdMode;
        if (!destructiveFallback && !"off".equalsIgnoreCase(mode)) return null;
        return MlsConversationKey.forLog(key)
                + " — REFUSED BEFORE ATTEMPTING, because the two era-advance levers are "
                + "not set and this conversation is exactly the shape the finding is about. "
                + "debug.rcs.mls_advance_create_fallback=" + destructiveFallback + " (needs 0 — "
                + "while ON, a silent refusal drops BOTH halves of local state and on a group leaves "
                + "it in REJOIN). "
                + "debug.rcs.mls_advance_fresh_ctxid=" + mode + " (needs 'all' — a "
                + "CreateMlsConversation that reuses a contextId the server already holds does not "
                + "take, SILENTLY: the RPC returns gRPC OK, no trailer, and the era simply does not "
                + "move. EVERY group a floor rebuild repairs is long-established and therefore HAS a "
                + "stored contextId). Set:\n"
                + "  setprop debug.rcs.mls_advance_create_fallback 0   # FIRST — makes a refusal "
                + "non-destructive; the advance's snapshot then rolls back cleanly\n"
                + "  setprop debug.rcs.mls_advance_fresh_ctxid  all\n"
                + "and restore both afterwards. Confirm the second actually FIRED — look for "
                + "'contextId MINTED FRESH' — and read the server era back either way.";
    }

    public static String issuingLine(final Preflight p, final String key,
            final List<String> below) {
        return "FLOOR REBUILD for " + MlsConversationKey.forLog(key)
                + " — every member's freshly claimed KeyPackage carries a "
                + "certificate above the floor (" + p.ready + "), so the era advance can rebuild "
                + "this group around them. The members that wedged it (" + below + ") hold stale "
                + "IN-GROUP leaves that no Commit can replace — A.4.3.2 §3 exempts only the "
                + "committer's own leaf and Invariant 17 is evaluated on the whole roster — but "
                + "their PUBLISHED pools have turned over, which is a different artefact with a "
                + "different clock. This re-Welcomes every member; it is not free and it is not "
                + "automatic.";
    }
}
