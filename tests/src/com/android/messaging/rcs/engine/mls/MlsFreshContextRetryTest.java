/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link MlsFreshContextRetry}: an era advance the server accepted but did not apply is re-dialled
 * once with a fresh contextId before {@code rebuildConversation} forgets local state, re-Welcomes
 * every member and charges the era budget again. See docs/mls/group-lifecycle.md.
 */
@RunWith(JUnit4.class)
public class MlsFreshContextRetryTest {

    private static final boolean ACCEPTED = true;
    private static final boolean ERA_READ = true;
    private static final boolean NOT_MOVED = false;
    private static final boolean GROUP = true;
    private static final boolean ONE_TO_ONE = false;
    private static final boolean ENABLED = true;

    /**
     * With {@code mls_advance_fresh_ctxid} unset or off every era advance sends the stored
     * contextId, so both conversation shapes retry.
     */
    @Test
    public void theFleetDefaultRetries() {
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, ONE_TO_ONE, "off",
                        ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "off", ENABLED));
        // Unset arrives as null or "" depending on how the property is read.
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, null, ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "", ENABLED));
    }

    /**
     * Each skipped state is reachable at the call site: the create can be refused, the fetch ledger
     * can refuse the era read, and the era can move.
     */
    @Test
    public void onlyTheAcceptedAndNotAppliedShapeIsRetried() {
        assertEquals("a create the SERVER REFUSED is a different finding — it carries an error on "
                        + "the channel refusals use — and must not be re-dialled as though it were "
                        + "the silent shape",
                MlsFreshContextRetry.Decision.SKIP_NOT_A_SILENT_NON_ADVANCE,
                MlsFreshContextRetry.decide(/*createAccepted=*/ false, ERA_READ, NOT_MOVED, GROUP,
                        "off", ENABLED));
        assertEquals("an era we could not READ is not a measurement, and re-dialling on it would "
                        + "spend an RPC on a conversation that may have advanced",
                MlsFreshContextRetry.Decision.SKIP_NOT_A_SILENT_NON_ADVANCE,
                MlsFreshContextRetry.decide(ACCEPTED, /*eraWasReadBack=*/ false, NOT_MOVED, GROUP,
                        "off", ENABLED));
        assertEquals("the advance TOOK — there is nothing to remedy",
                MlsFreshContextRetry.Decision.SKIP_NOT_A_SILENT_NON_ADVANCE,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, /*eraMoved=*/ true, GROUP, "off",
                        ENABLED));
    }

    /**
     * Mirrors the provider's {@code freshContextIdForRecreate}: {@code all} mints on either shape,
     * {@code 1to1} only without an RCS group id, so a group advance under {@code 1to1} sent the
     * stored id and still needs the re-dial.
     */
    @Test
    public void theLeverGateMirrorsTheProvidersOwnConditions() {
        assertEquals(MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, ONE_TO_ONE, "1to1",
                        ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, ONE_TO_ONE, "all",
                        ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "all", ENABLED));
        assertEquals("mode 1to1 does NOT mint for a group, so a group advance under it still sent "
                        + "the stored id and still needs the re-dial",
                MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "1to1", ENABLED));
        // Case-insensitive, like the provider's.
        assertEquals(MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "ALL", ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "OFF", ENABLED));
        // An unrecognised value mints nothing on the provider side.
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "yes", ENABLED));
        assertFalse(MlsFreshContextRetry.leverCouldHaveMinted(GROUP, "yes"));
        assertFalse(MlsFreshContextRetry.leverCouldHaveMinted(GROUP, "1to1"));
        assertTrue(MlsFreshContextRetry.leverCouldHaveMinted(ONE_TO_ONE, "1to1"));
    }

    /** The kill switch beats everything but the shape gate. */
    @Test
    public void theKillSwitchIsHonoured() {
        assertEquals(MlsFreshContextRetry.Decision.SKIP_DISABLED,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "off",
                        /*retryEnabled=*/ false));
        assertEquals("the shape gate runs FIRST, so a disabled retry on a non-silent outcome still "
                        + "reports the honest reason",
                MlsFreshContextRetry.Decision.SKIP_NOT_A_SILENT_NON_ADVANCE,
                MlsFreshContextRetry.decide(/*createAccepted=*/ false, ERA_READ, NOT_MOVED, GROUP,
                        "off", /*retryEnabled=*/ false));
    }

    /**
     * The provider accepts a supplied contextId only when it is 24 base64url characters, so a
     * wrongly shaped mint would be discarded silently and the re-dial would repeat the stored id.
     */
    @Test
    public void theMintIsReferenceShapedAndNotRepeated() {
        final SecureRandom rng = new SecureRandom();
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            final String id = MlsFreshContextRetry.mintContextId(rng);
            assertEquals("a real contextId is 24 base64url characters", 24, id.length());
            assertTrue(id + " is not the shape the provider's resolver honours",
                    MlsFreshContextRetry.isReferenceShapedContextId(id));
            seen.add(id);
        }
        assertEquals("18 random bytes must not repeat across 64 mints", 64, seen.size());
    }

    /** The shape predicate rejects the keys the app's create sites pass. */
    @Test
    public void theShapePredicateRejectsOurInternalKeys() {
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId("p:+15715550106"));
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId(
                "g:22ac7628c97c4cab8809ff8ca4694255"));
        assertFalse("the 32-hex rcsGroupId establishGroup passes is the wrong LENGTH",
                MlsFreshContextRetry.isReferenceShapedContextId(
                        "22ac7628c97c4cab8809ff8ca4694255"));
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId(null));
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId(""));
        // Reference-shaped ids.
        assertTrue(MlsFreshContextRetry.isReferenceShapedContextId("MxwWNgNorfRjuNR6HpufapAQ"));
        assertTrue(MlsFreshContextRetry.isReferenceShapedContextId("Wi5yH5Ugq2T_4IPR3nIgyvhj"));
        assertTrue(MlsFreshContextRetry.isReferenceShapedContextId("gfZ4QFfdSd5GvLdfqu1CYknm"));
        // Padding and the standard (non-url) alphabet are both rejected.
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId("MxwWNgNorfRjuNR6Hpufap+="));
    }

    /** The lines name the lever and the measurement, since an operator acts on them. */
    @Test
    public void theSentencesCarryTheOperatorsNextStep() {
        final String retrying = MlsFreshContextRetry.retryingLine("g:abc", 4, "off");
        assertTrue(retrying.contains("freshly minted contextId"));
        assertTrue(retrying.contains("No KeyPackage is claimed"));
        final String skip = MlsFreshContextRetry.skipLine(
                MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED, "g:abc", 4, "all");
        assertTrue(
                        "the skip must tell the reader how to tell whether the lever ACTUALLY fired — it "
                        + "also needs a prior provider record, which this side cannot see",
                skip.contains("contextId MINTED FRESH"));
        final String granted =
                MlsFreshContextRetry.grantedLine("g:abc", 4, "MxwWNgNorfRjuNR6HpufapAQ");
        assertTrue(granted.contains("MxwWNgNorfRjuNR6HpufapAQ"));
        final String not =
                MlsFreshContextRetry.notGrantedLine("g:abc", 4, "MxwWNgNorfRjuNR6HpufapAQ", 3L);
        assertTrue("a re-dial that ALSO did not take is a real result about this conversation",
                not.contains("cause the contextId does not explain"));
        assertNotEquals(granted, not);
    }

    /**
     * The re-dial must run before {@code rebuildConversation}: afterwards the group is already
     * forgotten and re-Welcomed and G2 charged twice, so the same RPC saves nothing.
     */
    @Test
    public void theTransportReDialsBeforeItRebuilds() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "eraAdvanceLocked");
        assertTrue("ZERO HITS MUST FAIL: no body found for eraAdvanceLocked in "
                + SourceScan.TRANSPORT + " — if it was renamed, rename it here rather than "
                + "letting this guard pass on nothing.", body.length() > 0);

        final int retryAt = body.indexOf("freshContextIdRetry(");
        final int rebuildAt = body.indexOf("rebuildConversation(");
        assertTrue("eraAdvanceLocked must call freshContextIdRetry when it measures that the era "
                        + "did not move. Without it, a refusal whose cause is known and "
                        + "whose remedy is ONE field of a request already built goes straight to the "
                        + "destructive rebuild: both halves of local state forgotten, every member "
                        + "re-Welcomed, G2 charged twice.",
                retryAt >= 0);
        assertTrue("eraAdvanceLocked must still reach rebuildConversation — the re-dial DECLINES "
                        + "NOTHING and the rebuild is the repair with an end-to-end proof. "
                        + "If this is gone, the remedy has become a refusal, which is the shape that "
                        + "was rejected: teaching the automatic callers to decline converts "
                        + "'expensive but it recovers' into 'stuck forever'.",
                rebuildAt >= 0);
        assertTrue(
                        "the re-dial must run BEFORE rebuildConversation. After it the group has already "
                        + "been forgotten and re-Welcomed and the era budget charged a second time, "
                        + "so the same RPC would save nothing at all.",
                retryAt < rebuildAt);
    }

    /**
     * The create's OK response carries no verdict, so the re-dial must re-read the server era;
     * succeeding on the OK alone would report an advance on every refusal.
     */
    @Test
    public void theReDialAsksTheAuthorityRatherThanBelievingTheOk() throws IOException {
        final String body =
                SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "freshContextIdRetry");
        assertTrue("ZERO HITS MUST FAIL: no body found for freshContextIdRetry", body.length() > 0);
        assertTrue(
                        "the decision must come from MlsFreshContextRetry.decide, on the host classpath, "
                        + "not from an if written here",
                body.contains("MlsFreshContextRetry.decide("));
        assertTrue("the re-dial must actually send the create",
                body.contains("createMlsConversation("));
        assertTrue("the re-dial MUST re-read the server era afterwards: fwiq carries no verdict "
                        + "field, so an OK says the call completed and nothing about whether the "
                        + "server applied it. Returning success on the OK alone would claim an era "
                        + "advance on every refusal.",
                body.contains("lookServerEraEpoch("));
    }
}
