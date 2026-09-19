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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The repair for a group no Commit can reach.
 *
 * <p>Every roster below is a REAL one, read off {@code deviceA} on 2026-09-10 at device
 * instant {@code 1789081799} with {@code --ez membervalidity} (read-only). They are used as
 * fixtures rather than as prose because the numbers are the whole finding: the members are 24-28
 * days from expiry, none is expired, and the predicate the client used to ask ({@code na <= now})
 * answers zero on all of them.
 */
public class MlsFloorRebuildTest {

    private static final long DAY = 86400L;
    /** The device instant the fixtures below were measured at, on {@code 00AU}. */
    private static final long NOW = 1789081799L;
    private static final long FLOOR = MlsCredentialFloor.RCC16_MIN_REMAINING_DAYS;

    private static final String AU = "+15715550104";     // deviceA
    private static final String T286 = "+15715550103";   // deviceC
    private static final String T010 = "+15715550107";   // deviceB
    private static final String VZ = "+15715550109";     // 26181FDF6001VZ
    private static final String RU = "+15715550106";     // deviceD (Google Messages)

    // ---- the measured rosters -------------------------------------------------------------------

    /**
     * {@code g:65cb80d62ebb4cc7b77646449c10264b}, verbatim: {@code members=3 expired=0 unreadable=0
     * leaf=0 notAfter=1791252270 (25d) leaf=1 notAfter=1791257114 (25d) leaf=2 notAfter=1792325614
     * (37d)}. Leaf 1 is ours. Two members inside the floor, none expired.
     */
    private static Map<Integer, long[]> g65cb80d6() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { 1784775870L, 1791252270L });   // +15715550103, 25d
        v.put(1, new long[] { 1784780714L, 1791257114L });   // +15715550104, 25d  <- ours
        v.put(2, new long[] { 1785845616L, 1792325614L });   // +15715550106, 37d
        return v;
    }

    private static Map<Integer, String> g65cb80d6Names() {
        final Map<Integer, String> n = new LinkedHashMap<>();
        n.put(0, T286);
        n.put(1, AU);
        n.put(2, RU);
        return n;
    }

    /**
     * {@code g:5f69372803414f9698629c57d04e5461}: {@code leaf=0 35d, leaf=1 25d (ours), leaf=2 24d}.
     */
    private static Map<Integer, long[]> g5f693728() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { 1785670107L, 1792150106L });   // +15715550106, 35d
        v.put(1, new long[] { 1784780714L, 1791257114L });   // +15715550104, 25d  <- ours
        v.put(2, new long[] { 1784707350L, 1791183747L });   // +15715550109, 24d
        return v;
    }

    private static MlsCredentialFloor.Report report(final Map<Integer, long[]> v,
            final Map<Integer, String> names) {
        return MlsCredentialFloor.classify(v, names, NOW, FLOOR);
    }

    // ============================================================================================
    // STAGE 1 — candidacy
    // ============================================================================================

    /**
     * THE STATE THIS EXISTS FOR. A peer is inside the floor, so no Commit — ours included —
     * can be applied, and our own certificate is fresh, so a rebuild has something to build with.
     */
    @Test
    public void aPeerInsideTheFloorWithAFreshCertificateOfOurOwnIsTheCandidate() {
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        assertEquals("two members inside the floor", 2, r.insideFloor);
        assertEquals("and NONE is expired — the predicate this replaces answers zero", 0, r.expired);
        assertEquals(MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(r, /*ourLeafIndex=*/ 1,
                        /*ourCertNotAfterSecs=*/ NOW + 74 * DAY, NOW, FLOOR));
        assertNull("a candidate has no refusal line",
                MlsFloorRebuild.candidacyLine(MlsFloorRebuild.Candidacy.CANDIDATE, "g:65cb", FLOOR));
    }

    /**
     * OUR OWN LEAF IS NOT A REASON TO REBUILD. A.4.3.2 §3 exempts exactly that leaf from the expiry
     * check, so the §9.5.3 Self-Update repairs this in one epoch and makes nobody
     * re-join. Choosing the era advance here would be a self-inflicted P0.
     */
    @Test
    public void ourOwnStaleLeafAloneIsRepairedByTheSelfUpdateNotByARebuild() {
        // The same group with the two PEERS refreshed and only our leaf left behind.
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

    /**
     * A REBUILD WITH OUR OWN STALE CERTIFICATE RE-CREATES THE WEDGE. The new group is built from
     * the certificate we hold today; if that is inside the floor the rebuilt group is uncommittable
     * the moment it exists, and the remedy is a KDS mint.
     */
    @Test
    public void aRebuildIsRefusedWhenTheCertificateWeWouldBuildItWithIsItselfInsideTheFloor() {
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        assertEquals(MlsFloorRebuild.Candidacy.OUR_CERTIFICATE_TOO_OLD,
                MlsFloorRebuild.assess(r, 1, /*ours: 25d*/ NOW + 25 * DAY, NOW, FLOOR));
    }

    /**
     * AN UNKNOWN CERTIFICATE IS NOT A FRESH ONE. {@code <= 0} means the caller could not read what
     * we hold, and an era advance is far too expensive to issue on that.
     */
    @Test
    public void anUnknownCertificateOfOurOwnRefusesRatherThanAssumingItIsFresh() {
        final MlsCredentialFloor.Report r = report(g65cb80d6(), g65cb80d6Names());
        assertEquals(MlsFloorRebuild.Candidacy.OUR_CERTIFICATE_TOO_OLD,
                MlsFloorRebuild.assess(r, 1, /*unknown*/ 0L, NOW, FLOOR));
    }

    /** A healthy roster is not rebuilt, however cheap the check is. */
    @Test
    public void aRosterAboveTheFloorIsNotACandidate() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { NOW - DAY, NOW + 42 * DAY });
        v.put(1, new long[] { NOW - DAY, NOW + 42 * DAY });
        assertEquals(MlsFloorRebuild.Candidacy.NOT_WEDGED,
                MlsFloorRebuild.assess(report(v, null), 0, NOW + 74 * DAY, NOW, FLOOR));
    }

    /**
     * UNREADABLE IS NOT HEALTHY. The two must not produce the same answer to a gate that issues era
     * advances — the defect shape this whole area is made of.
     */
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
     * WITHOUT OUR LEAF INDEX, EVERY BELOW-FLOOR MEMBER COUNTS — including possibly ourselves. That
     * errs toward attempting the rebuild rather than toward declining, and it is the right error:
     * the pre-flight below still has to pass, so the cost of being wrong here is one roster
     * measurement, while declining would leave a wedged group with no repair at all.
     */
    @Test
    public void anUnknownSelfLeafIndexDoesNotSilentlyDeclineTheRepair() {
        final Map<Integer, long[]> v = new LinkedHashMap<>();
        v.put(0, new long[] { NOW - DAY, NOW + 70 * DAY });
        v.put(1, new long[] { 1784780714L, 1791257114L });   // ours, but we do not know that
        assertEquals(MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(report(v, null), /*ourLeafIndex=*/ -1,
                        NOW + 74 * DAY, NOW, FLOOR));
    }

    // ============================================================================================
    // STAGE 2 — the pre-flight over the CLAIMED packages
    // ============================================================================================

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
     * ONE MEMBER WHO HAS NOT REPUBLISHED STOPS THE WHOLE REBUILD, AND IS NAMED. This is the answer
     * to "what does the client do when some members are ready and some are not": refuse, say who,
     * do nothing else. A rebuild cannot be partial — the new era must match the server's whole
     * roster — so there is no smaller operation to fall back to.
     */
    @Test
    public void oneMemberStillPublishingAStaleCertificateRefusesTheRebuildAndIsNamed() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, NOW + 74 * DAY);          // re-minted and republished
        claims.put(RU, 1791252270L);               // still publishing the 2026-07-23 certificate
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        assertEquals(MlsFloorRebuild.Readiness.MEMBER_NOT_REPUBLISHED, p.readiness);
        assertFalse(p.go());
        assertEquals(1, p.notRepublished.size());
        assertTrue("the refusal must name the member and how long it has left",
                p.notRepublished.get(0).startsWith(RU + " "));
        assertTrue(p.notRepublished.get(0).endsWith("d"));
        // AND WHICH CLOCK THE NUMBER CAME FROM. Unlabelled this is byte-identical to
        // MlsCredentialFloor.Report#below, which measures the group's ratchet tree rather than the
        // served pool; the same MSISDN was recorded as 41d here and 24d there in one document.
        assertTrue("the refusal must name the artefact it measured, got: "
                        + p.notRepublished.get(0),
                p.notRepublished.get(0).contains("poolCert="));
        final String line = MlsFloorRebuild.preflightLine(p, "g:65cb", FLOOR);
        assertNotNull(line);
        assertTrue("the line must name the member", line.contains(RU));
        assertTrue("and must say the group stays wedged", line.contains("stays wedged"));
        assertTrue("and must say whose action clears it", line.contains("THEIR device's action"));
    }

    /**
     * A PACKAGE WE COULD NOT DATE IS ITS OWN REFUSAL, NOT A STALE MEMBER. Reporting it as stale
     * would send an operator to that member's KDS for a problem in our own parse.
     */
    @Test
    public void anUndatablePackageRefusesUnderItsOwnNameRatherThanAsAStaleMember() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, NOW + 74 * DAY);
        claims.put(RU, 0L);                        // kp_inspect could not read a certificate
        final MlsFloorRebuild.Preflight p = MlsFloorRebuild.preflight(claims, NOW, FLOOR);
        assertEquals(MlsFloorRebuild.Readiness.UNDATED_PACKAGE, p.readiness);
        assertFalse(p.go());
        assertTrue("it is NOT counted as a stale member", p.notRepublished.isEmpty());
        assertEquals(1, p.undated.size());
        final String line = MlsFloorRebuild.preflightLine(p, "g:65cb", FLOOR);
        assertTrue(line.contains("NOT measured"));
        assertTrue("and must not claim the member is stale",
                line.contains("not evidence the member is") && line.contains("stale"));
    }

    /**
     * BOTH CLASSES ARE CARRIED EVEN WHEN ONE DECIDES THE VERDICT, so a single refusal reports every
     * member it found rather than the first kind it hit.
     */
    @Test
    public void aRefusalReportsEveryMemberItFoundNotOnlyTheDecidingClass() {
        final LinkedHashMap<String, Long> claims = MlsFloorRebuild.newClaimMap();
        claims.put(T286, 1791252270L);             // stale
        claims.put(RU, 0L);                        // undated
        claims.put(VZ, NOW + 74 * DAY);            // ready
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

    // ============================================================================================
    // THE CLOCK. This is the test that makes the wiring load-bearing rather than decorative.
    // ============================================================================================

    /**
     * <b>THE PRE-FLIGHT MUST BE FED THE CERTIFICATE'S {@code notAfter}, NEVER THE LEAFNODE
     * LIFETIME'S — and on the Tachyon profile the wrong one says READY every single time.</b>
     *
     * <p>{@code kp_lifetime_window}'s Tachyon arm mints the LeafNode Lifetime as
     * {@code cert.notBefore + 365d}, so it carries information about when the certificate STARTED
     * and none about when it ENDS. A remaining-lifetime floor applied to it cannot fire until the
     * certificate is ~335 days old, which a ~75-day Tachyon certificate never reaches.
     *
     * <p>Replayed here on the real 2026-07-23 fleet certificate — {@code notBefore 1784780714,
     * notAfter 1791257114}, 25 days left at {@code NOW}. Its Lifetime is {@code 1784780714 + 365d
     * = 1816316714}, i.e. 315 days out. Feed the Lifetime and this member sails through; feed the
     * certificate and it is correctly refused. Without this test, wiring the pre-flight to the
     * wrong field would produce a green suite and a rebuild that re-wedges the group.
     */
    @Test
    public void theLeafNodeLifetimeWouldPassAMemberTheCertificateRefuses() {
        final long certNotBefore = 1784780714L;
        final long certNotAfter = 1791257114L;
        final long lifetimeNotAfter = certNotBefore + 365L * DAY;

        assertEquals("the fleet certificate has 25 days left at the measured instant",
                25L, MlsCredentialFloor.remainingDays(certNotAfter, NOW));
        assertEquals("while its LeafNode Lifetime has 315 — a factor of twelve, on the same leaf",
                315L, MlsCredentialFloor.remainingDays(lifetimeNotAfter, NOW));

        final LinkedHashMap<String, Long> onTheLifetime = MlsFloorRebuild.newClaimMap();
        onTheLifetime.put(AU, lifetimeNotAfter);
        assertEquals("THE WRONG CLOCK CANNOT REFUSE — it reports the rebuild is ready to proceed",
                MlsFloorRebuild.Readiness.READY,
                MlsFloorRebuild.preflight(onTheLifetime, NOW, FLOOR).readiness);

        final LinkedHashMap<String, Long> onTheCertificate = MlsFloorRebuild.newClaimMap();
        onTheCertificate.put(AU, certNotAfter);
        assertEquals("the certificate clock is the one that refuses, which is the point",
                MlsFloorRebuild.Readiness.MEMBER_NOT_REPUBLISHED,
                MlsFloorRebuild.preflight(onTheCertificate, NOW, FLOOR).readiness);
    }

    /**
     * And the same trap one layer up: a Lifetime-shaped number for OUR OWN certificate would make
     * {@link MlsFloorRebuild.Candidacy#OUR_CERTIFICATE_TOO_OLD} unreachable.
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

    // ============================================================================================
    // The measured fleet, replayed end to end.
    // ============================================================================================

    /**
     * {@code g:5f693728} — two peers, one of them {@code +15715550109} at 24 days and one
     * {@code +15715550106} at 35. A rebuild is a candidate, and the pre-flight then turns on
     * whether {@code +15715550109} has republished, which is not a fact about this group at all.
     */
    @Test
    public void theSecondMeasuredGroupBehavesTheSameWayForTheSameReason() {
        final Map<Integer, String> names = new LinkedHashMap<>();
        names.put(0, RU);
        names.put(1, AU);
        names.put(2, VZ);
        final MlsCredentialFloor.Report r = report(g5f693728(), names);
        assertEquals(2, r.insideFloor);
        assertEquals("[" + AU + " groupLeaf=25d, " + VZ + " groupLeaf=24d]", r.below.toString());
        assertEquals(MlsFloorRebuild.Candidacy.CANDIDATE,
                MlsFloorRebuild.assess(r, 1, NOW + 74 * DAY, NOW, FLOOR));

        final LinkedHashMap<String, Long> notYet = MlsFloorRebuild.newClaimMap();
        notYet.put(RU, NOW + 35 * DAY);
        notYet.put(VZ, 1791183747L);               // 01VZ has not re-minted
        assertFalse(MlsFloorRebuild.preflight(notYet, NOW, FLOOR).go());

        final LinkedHashMap<String, Long> afterTheyReMint = MlsFloorRebuild.newClaimMap();
        afterTheyReMint.put(RU, NOW + 35 * DAY);
        afterTheyReMint.put(VZ, NOW + 74 * DAY);
        assertTrue("and the SAME group becomes rebuildable once they do, with no change here",
                MlsFloorRebuild.preflight(afterTheyReMint, NOW, FLOOR).go());
    }

    // ============================================================================================
    // the two levers — the refusal that must happen BEFORE anything is spent
    // ============================================================================================

    /** Both levers set is the ONLY combination that proceeds. */
    @Test
    public void bothLeversSetIsTheOnlyWayThrough() {
        assertNull(MlsFloorRebuild.leverRefusal(false, "all", "g:65cb"));
        assertNull("any non-off mode is accepted — the create site owns the vocabulary",
                MlsFloorRebuild.leverRefusal(false, "groups", "g:65cb"));
    }

    /**
     * THE DESTRUCTIVE FALLBACK ALONE REFUSES, even with a fresh contextId. It is the half that can
     * turn a wedged-but-present conversation into a gone one, and a repair must not risk that.
     */
    @Test
    public void theDestructiveFallbackRefusesOnItsOwn() {
        final String r = MlsFloorRebuild.leverRefusal(true, "all", "g:65cb");
        assertNotNull(r);
        assertTrue(r.contains("mls_advance_create_fallback=true"));
        assertTrue("it must say what ON costs", r.contains("REJOIN"));
    }

    /**
     * A STORED CONTEXTID ALONE REFUSES, even with the fallback already killed. The advance would be
     * silently not applied — gRPC OK, era unmoved — and the natural reading of that log is "the floor
     * is still blocking us", which is the wrong conclusion about this very repair.
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

    /** A null mode is read as off, not as set — an unread sysprop must not authorise a rebuild. */
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
        assertTrue("the members whose IN-GROUP leaves wedged it are named too", line.contains("25d"));
        assertTrue("and the peer cost is stated rather than implied",
                line.contains("re-Welcomes every member"));
    }
}
