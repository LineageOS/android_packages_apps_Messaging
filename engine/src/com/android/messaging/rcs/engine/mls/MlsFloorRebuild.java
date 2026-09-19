/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.RosterClaim;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Claim;
import com.android.messaging.rcs.log.LogMask;
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

    /**
     * Log what the floor report found, whether or not anything is below the floor, so a healthy
     * group and an unmeasured one never look alike.
     */
    public static void logFloorReport(final MlsConfig cfg, final MlsLogSink log, final String key,
            final MlsCredentialFloor.Report r, final String cause) {
        if (r == null) {
            log.i("MlsFloorRebuild: credential floor (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — the roster is unreadable; NOT concluding that it is healthy.");
            return;
        }
        if (!r.membershipChangesWouldBeRefused()) {
            log.i("MlsFloorRebuild: credential floor (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — " + r.total + " member(s), all above the " + cfg.kpMinRemainingDays
                    + "-day floor"
                    + (r.unreadable > 0 ? " (" + r.unreadable + " unreadable)" : ""));
            return;
        }
        log.w("MlsFloorRebuild: credential floor (" + cause + ") for "
                + MlsConversationKey.forLog(key) + " — "
                + r.insideFloor + " member(s) INSIDE RCC.16's " + cfg.kpMinRemainingDays
                + "-day remaining-lifetime floor and " + r.expired + " expired, out of " + r.total
                + ": " + r.below + ". The server validates member credentials at now+"
                + cfg.kpMinRemainingDays + "d (A.4.3.1 §1(a), Invariant 17 — the WHOLE roster), so "
                + "every membership Commit on this group is refused until each of those members "
                + "issues its OWN Self-Update. A Remove gets no carve-out (A.4.3.2 exempts only the "
                + "committer's own leaf), so no COMMIT of ours repairs a PEER's — only ours. "
                + "What CAN repair it is a rebuild: an era advance re-creates the group from "
                + "the members' PUBLISHED KeyPackages, which are a different artefact from their "
                + "in-group leaves and turn over on every re-mint ("
                + MlsConfig.KEY_FLOOR_REBUILD + ").");
    }

    /**
     * A floor rebuild was refused because a member's freshly claimed KeyPackage is still inside the
     * floor. Distinct from -1: retrying changes nothing until another device re-mints.
     */
    public static final int ERA_ADVANCE_ROSTER_NOT_READY = -3;

    /**
     * Claim one KeyPackage per member for an era advance, and date each package's leaf certificate
     * {@code notAfter} (not the LeafNode lifetime, which the provider's arm mints as
     * {@code notBefore + 365d}). Every caller measures the floor; only {@code
     * requireRebuildableRoster} refuses on it.
     *
     * @return the packages, or the failure the advance must return
     */
    public static RosterClaim claimRosterForAdvance(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key,
            final java.util.List<String> members, final boolean requireRebuildableRoster) {
        final java.util.List<byte[]> kps = new java.util.ArrayList<>();
        // Judged once after the loop, so one refusal names every member that is not ready.
        final java.util.LinkedHashMap<String, Long> claimedCerts = MlsFloorRebuild.newClaimMap();
        for (final String m : members) {
            final Claim<byte[]> eraClaim = shell.claimOne(MlsClaimLedger.Caller.ERA_ADVANCE, m);
            if (eraClaim.refused()) {
                // Normally the era budget binds long before this ration; kept correct anyway.
                log.w("MlsFloorRebuild: era advance ABORTED — our own claim ledger "
                        + "refused the KeyPackage claim for " + LogMask.number(m)
                        + ", so we never asked. This is "
                        + "NOT the peer being short a package; the roster is intact and the advance "
                        + "is retried from the next trigger. " + eraClaim.why());
                return RosterClaim.refused(-1);
            }
            final byte[] kp = eraClaim.orNull();
            if (kp == null) {
                // Whose block this is comes from the provider's claim outcome, not the bare null.
                log.w("MlsFloorRebuild: " + MlsConversationKey.forLog(key) + ": "
                        + MlsClaimLedger.rosterClaimBlockedLine(m, eraClaim.attribution()));
                return RosterClaim.refused(-1);
            }
            // The advance consumes one package per member, as a create does, and the same floor
            // applies.
            if (!shell.keyPackageUsable(kp, m)) return RosterClaim.refused(-1);
            long certNotAfter = 0L;
            try {
                final MlsSession.KeyPackageInfo kpi = shell.session().inspectKeyPackage(kp);
                if (kpi != null) certNotAfter = kpi.certNotAfterSecs;
            } catch (final Throwable t) {
                log.w("MlsFloorRebuild: could not date the KeyPackage claimed for "
                        + LogMask.number(m) + " during the era advance for "
                        + MlsConversationKey.forLog(key), t);
            }
            claimedCerts.put(m, Long.valueOf(certNotAfter));
            kps.add(kp);
        }
        final long floorDays = cfg.kpMinRemainingDays;
        final MlsFloorRebuild.Preflight pre = MlsFloorRebuild.preflight(
                claimedCerts, System.currentTimeMillis() / 1000L, floorDays);
        if (!pre.go()) {
            if (requireRebuildableRoster) {
                log.e("MlsFloorRebuild: "
                        + MlsFloorRebuild.preflightLine(pre, key, floorDays) + " [" + pre + "]");
                return RosterClaim.refused(ERA_ADVANCE_ROSTER_NOT_READY);
            }
            log.w("MlsFloorRebuild: the era advance for " + MlsConversationKey.forLog(key)
                    + " is building a "
                    + "group whose roster is NOT fully above the " + floorDays + "-day floor ("
                    + pre + "). PROCEEDING — this is not a floor rebuild, and a group that carries "
                    + "messages but refuses Commits beats no recovery at all. If the create is then "
                    + "refused naming one of those members, that settles whether the server applies "
                    + "Invariant 17 to a CREATE, which is not yet known.");
        }
        return RosterClaim.of(kps);
    }

    /**
     * Rebuild a group the RCC.16 floor has wedged: once a second member is inside the floor nobody
     * can commit, and {@link MlsCredentialUpdate#maybeUpdateGroupCredential} correctly declines.
     * Claims once, dates every package it would consume, and either goes or refuses naming the
     * members that are not ready; it does not wait or retry. One attempt per certificate generation
     * of ours.
     *
     * @return the new era, {@link #ERA_ADVANCE_ROSTER_NOT_READY} when a member has not republished,
     *         or -1 for every local refusal
     */
    public static int floorRebuild(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final Group g, final String rcsGroupId,
            final String peerE164, final String cause, final boolean ignoreAttemptMarker) {
        try {
            if (shell.session() == null || g == null || g.groupId == null) return -1;
            // Downgraded or left: never re-created around us. Repeated here because the debug lever
            // skips the maintenance pass.
            if (MlsRecordState.isDowngradedStatus(shell, log, key)
                    || MlsRecordState.weLeft(shell, log, key)) {
                log.i("MlsFloorRebuild: floor rebuild (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " — DECLINED, the conversation is downgraded or we left it (INVARIANT "
                        + "ED-1). An era advance re-creates the group WITH US IN IT.");
                return -1;
            }
            final long floorDays = cfg.kpMinRemainingDays;
            final long now = System.currentTimeMillis() / 1000L;
            // One call gives our leaf index (so our stale leaf is not taken for a peer's) and our
            // certificate (what the new group would use). Null is un-evaluable, not "fine".
            final MlsSelfLeafStatus st = shell.session().selfLeafStatus(g.groupId);
            if (st == null) {
                log.w("MlsFloorRebuild: floor rebuild (" + cause + ") for "
                        + MlsConversationKey.forLog(key)
                        + " — UN-EVALUABLE: the engine did not report our own leaf status, so we "
                        + "cannot tell OUR stale leaf from a PEER's and cannot date the certificate "
                        + "a rebuild would use. NOT era-advancing on that.");
                return -1;
            }
            final MlsCredentialFloor.Report roster =
                    MlsServerBundle.rosterFloorReport(cfg, shell.session(), log, g);
            final MlsFloorRebuild.Candidacy c = MlsFloorRebuild.assess(
                    roster, st.leafIndex, st.clientNotAfter, now, floorDays);
            if (c != MlsFloorRebuild.Candidacy.CANDIDATE) {
                final String line = MlsFloorRebuild.candidacyLine(c, key, floorDays);
                if (c == MlsFloorRebuild.Candidacy.NOT_WEDGED) {
                    log.i("MlsFloorRebuild: " + line);
                } else {
                    log.w("MlsFloorRebuild: " + line);
                }
                return -1;
            }
            // Levers are checked before the attempt marker is taken, so a missing lever costs
            // nothing.
            final String leverRefusal = shell.eraAdvanceLeverRefusal(key);
            if (leverRefusal != null) {
                log.e("MlsFloorRebuild: floor rebuild (" + cause + ") for "
                        + leverRefusal);
                return -1;
            }
            // One attempt per certificate generation: our next re-mint re-arms it. The debug lever
            // ignores the marker.
            final ConvState cs = shell.conv(key);
            if (!ignoreAttemptMarker) {
                synchronized (cs) {
                    if (cs.floorRebuildAttemptedFor == st.clientNotAfter) {
                        log.i("MlsFloorRebuild: floor rebuild (" + cause + ") for "
                                + MlsConversationKey.forLog(key)
                                + " — already attempted since we last re-minted (notAfter="
                                + st.clientNotAfter + "). Whether it would work now depends on "
                                + "whether a PEER has republished, and the only way to ask is to "
                                + "claim a KeyPackage from every member, which consumes one each. "
                                + "NOT re-claiming the roster on a guess; the next MINT re-arms "
                                + "this, and the debug lever ignores it.");
                        return -1;
                    }
                    cs.floorRebuildAttemptedFor = st.clientNotAfter;
                }
            }
            log.w("MlsFloorRebuild: FLOOR REBUILD (" + cause + ") for "
                    + MlsConversationKey.forLog(key)
                    + " — " + roster.insideFloor + " of " + roster.total + " member(s) are inside "
                    + "RCC.16's " + floorDays + "-day floor (" + roster.below
                    + ") and at least one "
                    + "is a PEER, so NO Commit can repair this group: A.4.3.2 §3 exempts only the "
                    + "committer's own leaf and Invariant 17 is evaluated on the whole roster. "
                    + "Claiming a KeyPackage from every member to see whether their PUBLISHED pools "
                    + "have turned over; if any has not, this refuses and names them rather than "
                    + "burning an era.");
            // The era advance claims, pre-flights and refuses on the exact packages it would
            // consume.
            final int era = shell.eraAdvance(rcsGroupId, peerE164, /*carryGroupInfo=*/ null,
                    MlsAdvanceEraKind.NORMAL, /*requireRebuildableRoster=*/ true);
            if (era == MlsFloorRebuild.ERA_ADVANCE_ROSTER_NOT_READY) {
                return era;
            }
            if (era < 0) {
                log.w("MlsFloorRebuild: the floor rebuild for " + MlsConversationKey.forLog(key)
                        + " did not "
                        + "complete (" + era + "). The group is still wedged and the reason is "
                        + "logged above by the advance itself — most often the era budget (G2), "
                        + "which bounds this exactly as it bounds every other re-Welcome.");
                return era;
            }
            log.i("MlsFloorRebuild: FLOOR REBUILD for " + MlsConversationKey.forLog(key)
                    + " SUCCEEDED → era "
                    + era + ". The group was re-created around freshly claimed KeyPackages, so the "
                    + "stale in-group leaves that wedged it (" + roster.below + ") are gone. "
                    + "Membership Commits should work on this conversation again; every member "
                    + "re-joins by Welcome.");
            return era;
        } catch (final Throwable t) {
            log.w("MlsFloorRebuild: the floor rebuild threw for "
                    + MlsConversationKey.forLog(key), t);
            return -1;
        }
    }

    /**
     * The maintenance pass's floor arm: {@link #floorRebuild} behind
     * {@link MlsConfig#KEY_FLOOR_REBUILD}, off by default because it re-Welcomes every member. The
     * debug lever reaches the repair without the property.
     */
    public static void maybeFloorRebuild(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String key, final Group g, final String rcsGroupId,
            final String peerE164, final MlsCredentialFloor.Report r, final String cause) {
        if (!cfg.floorRebuild) {
            if (r != null && r.membershipChangesWouldBeRefused()) {
                final long floorDays = cfg.kpMinRemainingDays;
                // Only a CANDIDATE needs a rebuild; when the stale leaf is ours, say so instead.
                final MlsSelfLeafStatus st = (shell.session() == null || g == null
                        || g.groupId == null) ? null : shell.session().selfLeafStatus(g.groupId);
                final Candidacy c = (st == null) ? Candidacy.CANDIDATE : assess(r, st.leafIndex,
                        st.clientNotAfter, System.currentTimeMillis() / 1000L, floorDays);
                if (c != Candidacy.CANDIDATE) {
                    log.w("MlsFloorRebuild: " + candidacyLine(c, key, floorDays));
                    return;
                }
                log.w("MlsFloorRebuild: " + MlsConversationKey.forLog(key) + " is wedged by the "
                        + floorDays + "-day floor (" + r.below + ") and a REBUILD is "
                        + "the only repair that exists for it — but " + MlsConfig.KEY_FLOOR_REBUILD
                        + " is off, so nothing automatic will attempt one. It re-Welcomes every "
                        + "member, which is a decision per conversation: run the lever "
                        + "(--ez floorrebuild true --es to " + LogMask.number(peerE164)
                        + (rcsGroupId == null || rcsGroupId.isEmpty()
                                ? "" : " --es rcsgid " + rcsGroupId)
                        + ") to attempt it.");
            }
            return;
        }
        MlsFloorRebuild.floorRebuild(cfg, shell, log, key, g, rcsGroupId, peerE164, cause,
                /*ignoreAttemptMarker=*/ false);
    }

    /** {@code --ez floorrebuild}'s entry point. Ignores the per-certificate attempt marker. */
    public static int debugFloorRebuild(final MlsConfig cfg, final MlsShellPort shell,
            final MlsLogSink log, final String rcsGroupId, final String peerE164) {
        if (!shell.ensureSession()) return -1;
        final String key = shell.resolveInbound(rcsGroupId, peerE164);
        final Group g = (key == null) ? null : shell.getGroup(key);
        if (g == null || g.groupId == null) {
            log.w("MlsFloorRebuild: floor rebuild (debug lever) — no MLS group for "
                    + LogMask.number(peerE164));
            return -1;
        }
        return MlsFloorRebuild.floorRebuild(cfg, shell, log, key, g, rcsGroupId, peerE164,
                "debug lever",
                /*ignoreAttemptMarker=*/ true);
    }

    /**
     * The lever check with live property reads (not {@link MlsConfig}, which the create site does
     * not use either). The decision itself is {@link MlsFloorRebuild#leverRefusal}.
     */
    public static String eraAdvanceLeverRefusal(final MlsShellPort shell, final String key) {
        return MlsFloorRebuild.leverRefusal(
                shell.sysprops().getBoolean(
                        "debug.rcs.mls_advance_create_fallback", true),
                shell.sysprops().get("debug.rcs.mls_advance_fresh_ctxid", "off"),
                key);
    }
}
