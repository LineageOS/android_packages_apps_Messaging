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
 * The one repair for a group that <b>no Commit can reach</b>.
 *
 * <h2>The state this exists for</h2>
 *
 * <p>RCC.16's floor is a <b>remaining lifetime</b>: the RCS SPN validates every credential in the
 * post-Commit roster at {@code now + 30d} (A.4.3.1 §1(a); Invariant 17 — "all Certificates in the
 * MLS Group"), so <b>one</b> member inside the window refuses <b>every</b> Commit on that group.
 * A.4.3.2 §3's expiry carve-out exempts the <b>committer's own leaf and nothing else</b>, which is
 * device-proven rather than read: our own §9.5.3 Self-Update on the 1:1 with {@code +12025550101}
 * came back {@code grpc-7 … MSISDN "+12025550101" … is not valid at time} naming the <i>peer's</i>
 * credential while ours was the one being replaced (note 4a).
 *
 * <p>So once a SECOND member is inside the floor, nobody can commit — the affected member included.
 * Five conversations were measured in that state. <b>No client-side Commit clears
 * them.</b> A certificate update is the mechanism that stops a group getting here; it
 * is not a repair for one already there.
 *
 * <h2>Why an era advance CAN clear it, when a Commit cannot</h2>
 *
 * <p>An era advance does not commit into the wedged group: it <b>re-creates</b> the group at a new
 * era around freshly claimed KeyPackages, one per member. The new roster's leaves come from those
 * packages, not from the old tree — so the stale in-group leaves are not carried forward, they are
 * replaced. The two artefacts have different clocks and different lifecycles: a member's IN-GROUP
 * leaf is frozen at whatever certificate it joined with, while its PUBLISHED pool is re-uploaded
 * every time it re-mints.
 *
 * <h2>The precondition, and it is per-member and not under our control</h2>
 *
 * <p>The rebuild only helps if every claimed package carries a certificate <b>above</b> the floor,
 * and that is true only once that member has re-minted and republished — which we cannot cause and,
 * before claiming, cannot see. Hence the two stages here:
 *
 * <ol>
 *   <li>{@link #assess} — from the ROSTER, is this group a candidate at all? (Cheap, no network.)
 *   <li>{@link #preflight} — from the CLAIMED PACKAGES' certificates, may the rebuild proceed?
 * </ol>
 *
 * <h2>Three decisions a reader should not have to re-derive</h2>
 *
 * <p><b>1. It refuses rather than making partial progress, and "partial" is not merely undesirable
 * — it is not expressible.</b> The new era must match the server's RCS roster (Google Messages'
 * self-heal-by-match-on-roster; a short member set is refused {@code mlsError 5}
 * {@code MismatchedRcsGroupState}). You cannot rebuild half a group. So the only choices are
 * all-or-nothing, and starting an advance that cannot finish costs an era-budget slot and one
 * one-time KeyPackage from every member who WAS ready.
 *
 * <p><b>2. The refusal does not depend on what the server does at create time, and that matters
 * because we have no sample of it.</b> Every device measurement of the certificate-clock refusal is
 * a Commit over an EXISTING roster; none is a
 * create. The argument here does not need one: <b>a rebuild that installs a certificate already
 * inside the floor re-creates the wedge it was run to clear</b> — the new group is uncommittable
 * from the moment it exists. That holds whether or not the server enforces Invariant 17 on a
 * create, so it is not a prediction about server behaviour. ({@code KEY_KP_CERT_FLOOR} is the same
 * measurement at the ADD site, where the argument IS a
 * prediction and is therefore behind a sysprop. Different site, different reasoning.)
 *
 * <p><b>3. A package we could not DATE blocks, and is reported apart from a stale one.</b> "We did
 * not measure a certificate" is not "the certificate is fine" — the defect class this whole area is
 * made of (a gate that cannot fail reporting PASS). It is also not "the member
 * is stale", so it gets its own list; a rebuild refused for an unreadable package should send a
 * reader to the package, not to the member's KDS.
 *
 * <p>Pure arithmetic over data the caller already holds. No clock of its own — {@code nowSecs} is
 * always the caller's — and no Android.
 */
public final class MlsFloorRebuild {

    private MlsFloorRebuild() {}

    // ============================================================================================
    // STAGE 1 — is this group a candidate? Answered from the ROSTER, before any claim.
    // ============================================================================================

    /** What the roster says about whether a rebuild is the right operation here. */
    public enum Candidacy {
        /**
         * Nothing is below the floor. Commits work; there is nothing to repair, and an era advance
         * would re-Welcome every member for no reason.
         */
        NOT_WEDGED,
        /**
         * Only OUR OWN leaf is below the floor. A.4.3.2 §3 exempts exactly that leaf from the expiry
         * check, so RCC.16 §9.5.3's Self-Update still works and is the correct, far cheaper repair
         * (one epoch, no re-join), which the certificate-update path performs. A rebuild here
         * would be a self-inflicted era advance.
         */
        SELF_UPDATE_SUFFICES,
        /**
         * A PEER is below the floor and so is our own certificate — the one we would build the new
         * group with. Rebuilding would install our own stale leaf and re-wedge the group at once
         * (and A.4.1.5 §5 forbids issuing a Self-Update on such a credential for the same reason).
         * The remedy is a KDS mint, not a Commit and not a rebuild.
         */
        OUR_CERTIFICATE_TOO_OLD,
        /**
         * The roster could not be read. NOT a synonym for {@link #NOT_WEDGED}: we did not look, and
         * an era advance is far too expensive to issue on an unmeasured group.
         */
        UNREADABLE,
        /**
         * A peer is below the floor, our own certificate is above it, and no Commit can repair this
         * group. Proceed to {@link #preflight}.
         */
        CANDIDATE,
    }

    /**
     * Is this group a candidate for a floor rebuild?
     *
     * @param roster        the roster measured against the floor, or {@code null} if unreadable
     * @param ourLeafIndex  our own leaf index, or a negative number when the engine could not say
     *                      which leaf is ours
     * @param ourCertNotAfterSecs  the {@code notAfter} of the certificate we hold TODAY — the one a
     *                      rebuild would put in the new group. {@code <= 0} means unknown
     * @param nowSecs       the caller's clock, epoch seconds
     * @param floorDays     the floor to apply, normally
     *                      {@link MlsCredentialFloor#RCC16_MIN_REMAINING_DAYS}
     */
    public static Candidacy assess(final MlsCredentialFloor.Report roster, final int ourLeafIndex,
            final long ourCertNotAfterSecs, final long nowSecs, final long floorDays) {
        if (roster == null) return Candidacy.UNREADABLE;
        if (!roster.membershipChangesWouldBeRefused()) return Candidacy.NOT_WEDGED;
        // IS A PEER BLOCKING, or only us? Read the roster MINUS our own leaf, exactly as the
        // §9.5.3 Self-Update arm does, and for the mirror-image reason: there, our leaf must not gate
        // operation that replaces it; here, our own leaf must not JUSTIFY the far more expensive
        // operation that a Self-Update would have handled.
        boolean peerBelow = false;
        for (final Map.Entry<Integer, MlsCredentialFloor.Standing> e : roster.standings.entrySet()) {
            if (e.getKey() != null && ourLeafIndex >= 0 && e.getKey().intValue() == ourLeafIndex) {
                continue;
            }
            if (blocking(e.getValue())) {
                peerBelow = true;
                break;
            }
        }
        if (!peerBelow) return Candidacy.SELF_UPDATE_SUFFICES;
        // OUR OWN CERTIFICATE, not our own group LEAF. The leaf is stale by construction on a group
        // in this state — that is what the repair is for. What must be fresh is the certificate the
        // new group would be built with, and an UNKNOWN one is refused rather than assumed fresh:
        // "we could not read it" must not authorise an era advance.
        if (ourCertNotAfterSecs <= 0L
                || MlsCredentialFloor.insideFloor(ourCertNotAfterSecs, nowSecs, floorDays)) {
            return Candidacy.OUR_CERTIFICATE_TOO_OLD;
        }
        return Candidacy.CANDIDATE;
    }

    /** EXPIRED, NOT_YET_VALID and INSIDE_FLOOR all refuse a Commit; UNREADABLE and OK do not. */
    private static boolean blocking(final MlsCredentialFloor.Standing s) {
        return s == MlsCredentialFloor.Standing.INSIDE_FLOOR
                || s == MlsCredentialFloor.Standing.EXPIRED
                || s == MlsCredentialFloor.Standing.NOT_YET_VALID;
    }

    // ============================================================================================
    // STAGE 2 — may the rebuild proceed? Answered from the CLAIMED PACKAGES' certificates.
    // ============================================================================================

    /** The verdict on a set of freshly claimed KeyPackages. */
    public enum Readiness {
        /** Every member's claimed package carries a certificate above the floor. Go. */
        READY,
        /** At least one member's published pool is still stale. Refuse, and name them. */
        MEMBER_NOT_REPUBLISHED,
        /** At least one claimed package carries no readable certificate window. Refuse, and say so. */
        UNDATED_PACKAGE,
        /** Nothing was claimed — an empty member set is not a rebuildable group. */
        NOTHING_CLAIMED,
    }

    /** The pre-flight's answer. Immutable, and it names members rather than counting them. */
    public static final class Preflight {
        public final Readiness readiness;
        /**
         * Members whose claimed package is inside the floor, as {@code "+1571… poolCert=41d"}.
         *
         * <p><b>{@code poolCert=} names which clock this is</b>: the leaf certificate carried by
         * the package the KDS actually served, which is NOT the certificate the device holds and
         * NOT the credential the group's ratchet tree carries for that member. Unlabelled it was
         * byte-identical to {@code MlsCredentialFloor.Report#below}, which measures the roster —
         * the same MSISDN appeared as {@code 41d} here and {@code 24d} there in one document.
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

        /** May the era advance be issued? */
        public boolean go() {
            return readiness == Readiness.READY;
        }

        /** How many members the pre-flight covered. */
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
     * Classify the certificates of the packages an era advance is about to build a group from.
     *
     * <p><b>Pass the packages that will actually be CONSUMED, not a separate sample.</b> A pool can
     * hold packages published before and after a re-mint, so a pre-flight that claims one package
     * and an advance that consumes a different one are measuring different bytes — the pre-flight
     * would then be an inference about the pool rather than a fact about the group being built.
     *
     * @param claimedCertNotAfter member MSISDN → the {@code notAfter} of that member's claimed
     *                            package's leaf CERTIFICATE, in claim order. {@code 0} (or any
     *                            non-positive value) means the engine could not date it
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
            // STALE OUTRANKS UNDATED in the verdict, and only in the verdict: a member we can prove
            // is not ready is the more actionable finding, and both lists are carried regardless so
            // one refusal reports every member it found rather than the first class it hit.
            r = Readiness.MEMBER_NOT_REPUBLISHED;
        } else if (!undated.isEmpty()) {
            r = Readiness.UNDATED_PACKAGE;
        } else {
            r = Readiness.READY;
        }
        return new Preflight(r, stale, undated, ready);
    }

    /** Convenience for callers that build the map as they claim. */
    public static LinkedHashMap<String, Long> newClaimMap() {
        return new LinkedHashMap<>();
    }

    // ============================================================================================
    // The sentences. Kept here so the refusal a device prints and the one a test asserts are the
    // same string — the log line IS the deliverable for an operation nobody can watch happen.
    // ============================================================================================

    /** The line for a candidacy that is not a candidate. {@code null} for {@link #assess}'s go. */
    public static String candidacyLine(final Candidacy c, final String key, final long floorDays) {
        if (c == null || c == Candidacy.CANDIDATE) return null;
        switch (c) {
            case NOT_WEDGED:
                return "floor rebuild for " + key + " — NOT NEEDED: no member is inside the "
                        + floorDays + "-day floor, so Commits are not being refused for a "
                        + "credential and an era advance would re-Welcome everyone for nothing.";
            case SELF_UPDATE_SUFFICES:
                return "floor rebuild for " + key + " — DECLINED: only OUR OWN leaf is inside the "
                        + floorDays + "-day floor, and A.4.3.2 §3 exempts exactly that leaf from "
                        + "the expiry check. RCC.16 §9.5.3's Self-Update repairs this in one epoch "
                        + "without making anyone re-join; a rebuild here would be a self-inflicted "
                        + "era advance.";
            case OUR_CERTIFICATE_TOO_OLD:
                return "floor rebuild for " + key + " is NEEDED but CANNOT HELP: the certificate WE "
                        + "hold is itself inside the " + floorDays + "-day floor (or could not be "
                        + "read), so the rebuilt group would carry our own stale leaf and be wedged "
                        + "from the moment it existed. The remedy is a fresh KDS mint, not a "
                        + "rebuild — this will re-arm on the next one.";
            case UNREADABLE:
                return "floor rebuild for " + key + " — DECLINED: the roster is unreadable. NOT "
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
                return "floor rebuild for " + key + " — REFUSED, and the group stays wedged: "
                        + p.notRepublished + " still publish a KeyPackage whose certificate is "
                        + "inside the " + floorDays + "-day floor" + (p.undated.isEmpty() ? ""
                                : " (and " + p.undated + " could not be dated)")
                        + ", while " + p.ready.size() + " of " + p.total() + " member(s) are ready. "
                        + "Rebuilding around a stale certificate re-creates the wedge, and a "
                        + "rebuild cannot be partial — the new era must match the server's whole "
                        + "roster. Nothing further is possible here until those members re-mint "
                        + "and republish; that is THEIR device's action, not ours.";
            case UNDATED_PACKAGE:
                return "floor rebuild for " + key + " — REFUSED: the claimed KeyPackage for "
                        + p.undated + " carries no readable leaf certificate window, so its "
                        + "remaining lifetime was NOT measured. That is not evidence the member is "
                        + "stale and not evidence it is fresh; an era advance is too expensive to "
                        + "issue on an unmeasured roster.";
            case NOTHING_CLAIMED:
                return "floor rebuild for " + key + " — REFUSED: no KeyPackage was claimed for any "
                        + "member, so there is nothing to rebuild the group from.";
            default:
                return null;
        }
    }

    /**
     * Are the two era-advance levers set? Returns the refusal, or {@code null} to go.
     *
     * <h2>Why these are PRECONDITIONS and not warnings</h2>
     *
     * <p>Four trials on two purpose-built groups, with the server era read
     * back every time, that <b>a {@code CreateMlsConversation} that reuses a {@code contextId} the
     * server already holds does not take</b> — and that nothing announces it: the RPC completes with
     * <b>gRPC OK</b>, the trailers carry nothing, and the only observable is that the era, re-read
     * from the server, has not moved.
     *
     * <p><b>Two of the four clauses this paragraph used to carry were withdrawn.</b> It
     * read "{@code verdict=0}, a fresh {@code serverGroupId}, no {@code grpcStatus}, no
     * {@code mlsError}". WITHDRAWN: {@code verdict=0} is our own client-side field — the response
     * has exactly ONE wire field, a header of two longs, so the body cannot carry a verdict; and
     * the id in it is a RESPONSE id, fresh on every
     * call, never a group id. STANDING: the two trailer negatives are real, because the state they
     * deny is REACHABLE on this RPC — it answers {@code grpcStatus=3} with a structured
     * {@code MlsError} ({@code INCORRECT_ERA} on the {@code mlserror-bin} trailer plane, name not
     * index, {@code 1001.3=2} arm) when it refuses a FRESH {@code group_id}. An era advance reuses
     * the group id, so that particular code's absence is expected; what the live channel buys is
     * that an empty trailer here is a READING rather than an assumption. What was wrong there was the
     * reading, not the claim: nothing on the create's OK path read the trailers until 2026-09-11.
     * The era re-read is still the measurement that discriminates.
     *
     * <p><b>And "refuses" is an interpretation, not an observation.</b> Tachyon does refuse MLS
     * operations — with a gRPC status and an {@code mlserror-bin} trailer, which this client reads
     * and has logged on other creates. This one came back OK. Whether the server declined it,
     * de-duplicated it, or created something that is not the conversation we then asked about is NOT
     * determined by any trial on record. It does not change what to do: a fresh contextId takes,
     * a stored one does not.
     *
     * <pre>
     *   group A  1st advance  1-&gt;2  fresh ctxid   GRANTED  server era=2
     *   group A  2nd advance  2-&gt;3  stored ctxid  REFUSED  server era=2
     *   group B  1st advance  1-&gt;2  stored ctxid  REFUSED  server era=1
     *   group B  2nd advance  1-&gt;2  fresh ctxid   GRANTED  server era=2
     * </pre>
     *
     * <p><b>And the silence has a positive explanation, not just an absence.</b> Google Messages'
     * outgoing postprocessor has three categories — error WITH an {@code MlsError} (terminal), error
     * WITHOUT one on a retryable status (retry), and <b>gRPC OK (success, no inspection at all)</b>.
     * This outcome is the third, so Google Messages is exactly as blind to it as we were: it is not
     * a quiet member of the refusal taxonomy, it is outside it. Its refusal-reason enum carries no
     * token for "contextId already held" either — a
     * fact about that CLIENT, not about what the server can express).
     *
     * <p><b>A floor rebuild is the worst case for it.</b> Every conversation this repairs is
     * long-established by construction — that is how its leaves aged into the floor — so each has a
     * provider record, which is exactly the condition that makes the advance reuse a stored
     * contextId. Without the lever the advance is PREDICTED to be refused, and the log would read
     * "the era did not move", which a reader naturally attributes to the floor. A correct-looking
     * log leading to the wrong conclusion about this very repair.
     *
     * <p><b>And the default failure is expensive.</b> A silent refusal triggers the automatic
     * rebuild ({@code mls_advance_create_fallback}, default ON), which drops BOTH halves of local
     * state and re-creates; on a GROUP that rung historically returns {@code rebuilt=false} and
     * leaves the conversation in REJOIN. Three fixtures were destroyed that way on 2026-09-08. A
     * repair that can turn "wedged but present" into "gone" is worse than no repair.
     *
     * <p>So this refuses rather than warning, for the same reason {@link #preflight} does: an
     * attempt we can predict will fail should not spend a peer's one-time KeyPackage and an
     * era-budget slot to confirm it.
     *
     * <p><b>What this does NOT claim.</b> Those four trials were on groups whose every leaf was
     * fresh. On a floor-wedged roster a fresh contextId removes THAT refusal; whether the server
     * then accepts the roster is a separate question nobody has measured. If it refuses, expect a
     * {@code grpc-7 Time-related validation error} NAMING a member — loud, and a different answer.
     *
     * @param destructiveFallback {@code debug.rcs.mls_advance_create_fallback}, default true
     * @param freshCtxIdMode      {@code debug.rcs.mls_advance_fresh_ctxid}, default {@code "off"}
     */
    public static String leverRefusal(final boolean destructiveFallback, final String freshCtxIdMode,
            final String key) {
        final String mode = (freshCtxIdMode == null) ? "off" : freshCtxIdMode;
        if (!destructiveFallback && !"off".equalsIgnoreCase(mode)) return null;
        return key + " — REFUSED BEFORE ATTEMPTING, because the two era-advance levers are "
                + "not set and this conversation is exactly the shape the finding is about. "
                + "debug.rcs.mls_advance_create_fallback=" + destructiveFallback + " (needs 0 — "
                + "while ON, a silent refusal drops BOTH halves of local state and on a group leaves "
                + "it in REJOIN; three fixtures were destroyed that way on 2026-09-08). "
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

    /** The line that announces a rebuild is being issued. */
    public static String issuingLine(final Preflight p, final String key,
            final List<String> below) {
        return "FLOOR REBUILD for " + key + " — every member's freshly claimed KeyPackage carries a "
                + "certificate above the floor (" + p.ready + "), so the era advance can rebuild "
                + "this group around them. The members that wedged it (" + below + ") hold stale "
                + "IN-GROUP leaves that no Commit can replace — A.4.3.2 §3 exempts only the "
                + "committer's own leaf and Invariant 17 is evaluated on the whole roster — but "
                + "their PUBLISHED pools have turned over, which is a different artefact with a "
                + "different clock. This re-Welcomes every member; it is not free and it is not "
                + "automatic.";
    }
}
