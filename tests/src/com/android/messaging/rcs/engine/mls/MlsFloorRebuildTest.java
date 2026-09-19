/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link MlsFloorRebuild}, the repair for a group no Commit can reach. The rosters are real
 * certificate lifetimes: members 24 to 28 days from expiry, none expired, so an expiry test answers
 * zero on all of them. See docs/mls/credentials.md.
 */
public class MlsFloorRebuildTest {

    private static final long DAY = 86400L;
    /** The instant the fixture rosters were read at. */
    private static final long NOW = 1789081799L;
    private static final long FLOOR = MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS;

    private static final String SELF = "+15715550104";     // the member whose leaf is ours
    private static final String T286 = "+15715550103";
    private static final String T010 = "+15715550107";
    private static final String PEER = "+15715550109";
    private static final String RU = "+15715550106";

    /** Three members, leaf 1 ours; two inside the floor, none expired. */
    private static Map<Integer, long[]> g65cb80d6() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { 1784775870L, 1791252270L });
        v.put(1, new long[] { 1784780714L, 1791257114L });
        v.put(2, new long[] { 1785845616L, 1792325614L });
        return v;
    }

    private static Map<Integer, String> g65cb80d6Names() {
        final Map<Integer, String> n = new LinkedHashMap<>();
        n.put(0, T286);
        n.put(1, SELF);
        n.put(2, RU);
        return n;
    }

    /** Leaf 0 at 35 days, leaf 1 (ours) at 25, leaf 2 at 24. */
    private static Map<Integer, long[]> g5f693728() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { 1785670107L, 1792150106L });
        v.put(1, new long[] { 1784780714L, 1791257114L });
        v.put(2, new long[] { 1784707350L, 1791183747L });
        return v;
    }

    private static MlsCredentialFloor.Report report(final Map<Integer, long[]> v,
            final Map<Integer, String> names) {
        return MlsCredentialFloor.classify(v, names, NOW, FLOOR);
    }

    /**
     * A peer inside the floor blocks every Commit, ours included, and our own fresh certificate
     * gives a rebuild something to build with.
     */
    @Test
    public void aPeerInsideTheFloorWithAFreshCertificateOfOurOwnIsTheCandidate() {
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        assertEquals("two members inside the floor", 2, r.insideFloor);
        assertEquals("and NONE is expired — the predicate this replaces answers zero", 0,
                r.expired);
        assertEquals(MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(r, /*ourLeafIndex=*/ 1,
                        /*ourCertNotAfterSecs=*/ NOW + 74 * DAY, NOW, FLOOR));
        assertNull("a candidate has no refusal line",
                MlsFloorRebuild.candidacyLine(MlsFloorRebuild.Candidacy.CANDIDATE, "g:65cb",
                        FLOOR));
    }

    /**
     * RCC.16 A.4.3.2 exempts our own leaf from the expiry check, so the RCC.16 §9.5.3 Self-Update
     * repairs it in one epoch without making anyone re-join.
     */
    @Test
    public void ourOwnStaleLeafAloneIsRepairedByTheSelfUpdateNotByARebuild() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { NOW - DAY, NOW + 70 * DAY });
        v.put(1, new long[] { 1784780714L, 1791257114L });   // ours, 25d
        v.put(2, new long[] { NOW - DAY, NOW + 70 * DAY });
        final MlsCredentialFloor.Report r = report(v, g65cb80d6Names());
        assertTrue("the server still refuses commits on this roster",
                r.membershipChangesWouldBeRefused());
        assertEquals(MlsFloorRebuild.Candidacy.SELF_UPDATE_SUFFICES,
                MlsFloorRebuild.assess(r, 1, NOW + 74 * DAY, NOW, FLOOR));
        final String line = MlsFloorRebuild.candidacyLine(
                MlsFloorRebuild.Candidacy.SELF_UPDATE_SUFFICES, "g:65cb", FLOOR);
        assertNotNull(line);
        assertTrue("the line must send the reader to §9.5.3, not to a rebuild",
                line.contains("9.5.3") && line.contains("A.4.3.2"));
    }

    /** A rebuild built from our own certificate inside the floor would be uncommittable at once. */
    @Test
    public void aRebuildIsRefusedWhenTheCertificateWeWouldBuildItWithIsItselfInsideTheFloor() {
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        assertEquals(MlsFloorRebuild.Candidacy.OUR_CERTIFICATE_TOO_OLD,
                MlsFloorRebuild.assess(r, 1, /*ours: 25d*/ NOW + 25 * DAY, NOW, FLOOR));
    }

    /**
     * A value {@code <= 0} means our certificate could not be read, which is not evidence it is
     * fresh.
     */
    @Test
    public void anUnknownCertificateOfOurOwnRefusesRatherThanAssumingItIsFresh() {
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        assertEquals(MlsFloorRebuild.Candidacy.OUR_CERTIFICATE_TOO_OLD,
                MlsFloorRebuild.assess(r, 1, /*unknown*/ 0L, NOW, FLOOR));
    }

    @Test
    public void aRosterAboveTheFloorIsNotACandidate() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { NOW - DAY, NOW + 42 * DAY });
        v.put(1, new long[] { NOW - DAY, NOW + 42 * DAY });
        assertEquals(MlsFloorRebuild.Candidacy.NOT_WEDGED,
                MlsFloorRebuild.assess(report(v, null), 0, NOW + 74 * DAY, NOW, FLOOR));
    }

    /** Unreadable and healthy must not give the same answer to a gate that issues era advances. */
    @Test
    public void anUnreadableRosterIsItsOwnAnswerAndNotHealth() {
        assertEquals(MlsFloorRebuild.Candidacy.UNREADABLE,
                MlsFloorRebuild.assess(null, 0, NOW + 74 * DAY, NOW, FLOOR));
        final String line =
                MlsFloorRebuild.candidacyLine(MlsFloorRebuild.Candidacy.UNREADABLE, "g:x", FLOOR);
        assertNotNull(line);
        assertTrue(line.contains("NOT concluding that it is healthy"));
    }

    /**
     * Without our leaf index every member below the floor counts, possibly ourselves. That errs
     * toward attempting: the pre-flight still has to pass, while declining would leave no repair at
     * all.
     */
    @Test
    public void anUnknownSelfLeafIndexDoesNotSilentlyDeclineTheRepair() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { NOW - DAY, NOW + 70 * DAY });
        v.put(1, new long[] { 1784780714L, 1791257114L });   // ours, unknown to the caller
        assertEquals(MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(report(v, null), /*ourLeafIndex=*/ -1,
                        NOW + 74 * DAY, NOW, FLOOR));
    }

    @Test
    public void everyClaimedPackageAboveTheFloorIsTheOnlyWayThrough() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, NOW + 74 * DAY);
        claims.put(RU, NOW + 37 * DAY);
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        assertEquals(MlsFloorRebuild.Readiness.READY, p.readiness);
        assertTrue(p.go());
        assertEquals(2, p.ready.size());
        assertNull("a pre-flight that goes has no refusal line",
                MlsFloorRebuild.preflightLine(p, "g:65cb", FLOOR));
    }

    /**
     * One member that has not republished stops the whole rebuild and is named: the new era must
     * match the server's whole roster, so there is no partial rebuild to fall back to.
     */
    @Test
    public void oneMemberStillPublishingAStaleCertificateRefusesTheRebuildAndIsNamed() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, NOW + 74 * DAY);          // renewed and republished
        claims.put(RU, 1791252270L);               // still publishing the old certificate
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        assertEquals(MlsFloorRebuild.Readiness.MEMBER_NOT_REPUBLISHED, p.readiness);
        assertFalse(p.go());
        assertEquals(1, p.notRepublished.size());
        assertTrue("the refusal must name the member and how long it has left",
                p.notRepublished.get(0).startsWith(RU + " "));
        assertTrue(p.notRepublished.get(0).endsWith("d"));
        // The line names which clock the number came from: MlsCredentialFloor.Report#below measures
        // the group's ratchet tree, this measures the served pool, and the two disagree.
        assertTrue("the refusal must name the artefact it read, got: "
                        + p.notRepublished.get(0),
                p.notRepublished.get(0).contains("poolCert="));
        final String line = MlsFloorRebuild.preflightLine(p, "g:65cb", FLOOR);
        assertNotNull(line);
        assertTrue("the line must name the member", line.contains(RU));
        assertTrue("and must say the group stays wedged", line.contains("stays wedged"));
        assertTrue("and must say whose action clears it", line.contains("THEIR device's action"));
    }

    /** An undatable package is our parse failure, not evidence that the member is stale. */
    @Test
    public void anUndatablePackageRefusesUnderItsOwnNameRatherThanAsAStaleMember() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, NOW + 74 * DAY);
        claims.put(RU, 0L);                        // the certificate could not be read
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        assertEquals(MlsFloorRebuild.Readiness.UNDATED_PACKAGE, p.readiness);
        assertFalse(p.go());
        assertTrue("it is NOT counted as a stale member", p.notRepublished.isEmpty());
        assertEquals(1, p.undated.size());
        final String line = MlsFloorRebuild.preflightLine(p, "g:65cb", FLOOR);
        assertTrue(line.contains("is UNKNOWN"));
        assertTrue("and must not claim the member is stale",
                line.contains("not evidence the member is") && line.contains("stale"));
    }

    /**
     * Both classes are carried even when one decides the verdict, so one refusal names every
     * member.
     */
    @Test
    public void aRefusalReportsEveryMemberItFoundNotOnlyTheDecidingClass() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, 1791252270L);             // stale
        claims.put(RU, 0L);                        // undated
        claims.put(PEER, NOW + 74 * DAY);            // ready
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        assertEquals(MlsFloorRebuild.Readiness.MEMBER_NOT_REPUBLISHED, p.readiness);
        assertEquals(1, p.notRepublished.size());
        assertEquals(1, p.undated.size());
        assertEquals(1, p.ready.size());
        assertEquals(3, p.total());
        final String line = MlsFloorRebuild.preflightLine(p, "g:65cb", FLOOR);
        assertTrue("the stale member is named", line.contains(T286));
        assertTrue("and so is the one we could not date", line.contains(RU));
    }

    /** An empty claim set is not a group to rebuild, and does not read as READY. */
    @Test
    public void nothingClaimedIsNotReady() {
        assertEquals(MlsFloorRebuild.Readiness.NOTHING_CLAIMED,
                MlsFloorRebuild.preflight(MlsFloorRebuild.newClaimMap(), NOW, FLOOR).readiness);
        assertEquals(MlsFloorRebuild.Readiness.NOTHING_CLAIMED,
                MlsFloorRebuild.preflight(null, NOW, FLOOR).readiness);
    }

    /**
     * The pre-flight takes the certificate's {@code notAfter}, never the LeafNode lifetime's. Under
     * {@code FIXED_365_DAYS} the lifetime is {@code cert.notBefore + 365d}, which says nothing
     * about when the certificate ends, so a remaining-lifetime floor applied to it passes a member
     * the certificate refuses. See docs/mls/rust-core.md.
     */
    @Test
    public void theLeafNodeLifetimeWouldPassAMemberTheCertificateRefuses() {
        final long certNotBefore = 1784780714L;
        final long certNotAfter = 1791257114L;
        final long lifetimeNotAfter = certNotBefore + 365L * DAY;

        assertEquals("the test certificate has 25 days left at NOW",
                25L, MlsCredentialFloor.remainingDays(certNotAfter, NOW));
        assertEquals("while its LeafNode Lifetime has 315 — a factor of twelve, on the same leaf",
                315L, MlsCredentialFloor.remainingDays(lifetimeNotAfter, NOW));

        final LinkedHashMap<String, Long> onTheLifetime = MlsFloorRebuild.newClaimMap();
        onTheLifetime.put(SELF, lifetimeNotAfter);
        assertEquals("THE WRONG CLOCK CANNOT REFUSE — it reports the rebuild is ready to proceed",
                MlsFloorRebuild.Readiness.READY,
                MlsFloorRebuild.preflight(onTheLifetime, NOW, FLOOR).readiness);

        final LinkedHashMap<String, Long> onTheCertificate = MlsFloorRebuild.newClaimMap();
        onTheCertificate.put(SELF, certNotAfter);
        assertEquals("the certificate clock is the one that refuses, which is the point",
                MlsFloorRebuild.Readiness.MEMBER_NOT_REPUBLISHED,
                MlsFloorRebuild.preflight(onTheCertificate, NOW, FLOOR).readiness);
    }

    /**
     * The same trap for our own certificate would make {@code OUR_CERTIFICATE_TOO_OLD} unreachable.
     */
    @Test
    public void ourOwnCertificateCheckIsAlsoDefeatedByTheLifetimeClock() {
        final MlsCredentialFloor.Report r = report(g5f693728(), null);
        final long certNotBefore = 1784780714L;
        assertEquals("on the certificate we hold, the rebuild is correctly refused",
                MlsFloorRebuild.Candidacy.OUR_CERTIFICATE_TOO_OLD,
                MlsFloorRebuild.assess(r, 1, /*cert*/ 1791257114L, NOW, FLOOR));
        assertEquals("on the Lifetime it would have proceeded with a stale identity",
                MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(r, 1, certNotBefore + 365L * DAY, NOW, FLOOR));
    }

    /**
     * A rebuild is a candidate, and the pre-flight then turns on whether one peer has republished,
     * which is not a fact about this group.
     */
    @Test
    public void theSecondMeasuredGroupBehavesTheSameWayForTheSameReason() {
        final Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, RU);
        names.put(1, SELF);
        names.put(2, PEER);
        final MlsCredentialFloor.Report r = report(g5f693728(), names);
        assertEquals(2, r.insideFloor);
        assertEquals("[***0104 groupLeaf=25d, ***0109 groupLeaf=24d]", r.below.toString());
        assertEquals(MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(r, 1, NOW + 74 * DAY, NOW, FLOOR));

        final LinkedHashMap<String, Long> notYet = MlsFloorRebuild.newClaimMap();
        notYet.put(RU, NOW + 35 * DAY);
        notYet.put(PEER, 1791183747L);               // that peer has not renewed
        assertFalse(MlsFloorRebuild.preflight(notYet, NOW, FLOOR).go());

        final LinkedHashMap<String, Long> afterTheyReMint = MlsFloorRebuild.newClaimMap();
        afterTheyReMint.put(RU, NOW + 35 * DAY);
        afterTheyReMint.put(PEER, NOW + 74 * DAY);
        assertTrue("and the SAME group becomes rebuildable once they do, with no change here",
                MlsFloorRebuild.preflight(afterTheyReMint, NOW, FLOOR).go());
    }

    /** Both levers set is the only combination that proceeds. */
    @Test
    public void bothLeversSetIsTheOnlyWayThrough() {
        assertNull(MlsFloorRebuild.leverRefusal(false, "all", "g:65cb"));
        assertNull("any non-off mode is accepted — the create site owns the vocabulary",
                MlsFloorRebuild.leverRefusal(false, "groups", "g:65cb"));
    }

    /**
     * The destructive create fallback refuses on its own: it can turn a wedged but present
     * conversation into a gone one.
     */
    @Test
    public void theDestructiveFallbackRefusesOnItsOwn() {
        final String r = MlsFloorRebuild.leverRefusal(true, "all", "g:65cb");
        assertNotNull(r);
        assertTrue(r.contains("mls_advance_create_fallback=true"));
        assertTrue("it must say what ON costs", r.contains("REJOIN"));
    }

    /**
     * A stored contextId refuses on its own: the advance would be silently not applied (OK, era
     * unmoved), which reads as the floor still blocking.
     */
    @Test
    public void anUnsetFreshContextIdRefusesOnItsOwn() {
        final String r = MlsFloorRebuild.leverRefusal(false, "off", "g:65cb");
        assertNotNull(r);
        assertTrue(r.contains("mls_advance_fresh_ctxid=off"));
        assertTrue("it must say the refusal is SILENT, which is the whole trap",
                r.contains("SILENTLY"));
        assertTrue("and it must give the operator both commands", r.contains("setprop"));
    }

    /** A null mode reads as off: an unread property must not authorise a rebuild. */
    @Test
    public void anAbsentModeReadsAsOffRatherThanAsPermission() {
        assertNotNull(MlsFloorRebuild.leverRefusal(false, null, "g:65cb"));
    }

    /** The refusal names the conversation, so a sweep's output can be read per group. */
    @Test
    public void theLeverRefusalNamesTheConversation() {
        assertTrue(MlsFloorRebuild.leverRefusal(true, "off", "g:abcdef").startsWith("g:abcdef "));
    }

    /** The issuing line names both halves: who is ready, and who was wedging it. */
    @Test
    public void theIssuingLineNamesTheReadyPackagesAndTheStaleLeavesTheyReplace() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, NOW + 74 * DAY);
        claims.put(RU, NOW + 37 * DAY);
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        final String line = MlsFloorRebuild.issuingLine(p, "g:65cb", r.below);
        assertTrue(line.contains(T286));
        assertTrue("the members whose IN-GROUP leaves wedged it are named too",
                line.contains("25d"));
        assertTrue("and the peer cost is stated rather than implied",
                line.contains("re-Welcomes every member"));
    }
}
