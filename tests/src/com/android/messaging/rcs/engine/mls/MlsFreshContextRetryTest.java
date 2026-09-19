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
 * The fresh-{@code contextId} re-dial.
 *
 * <h2>What these tests are FOR, stated as the failure they detect</h2>
 *
 * <p>Without the change they guard, an era advance that the server accepts and does not apply goes
 * straight to {@code rebuildConversation}: both halves of local state forgotten, every member
 * re-Welcomed, and the era budget charged a second time — for a refusal whose cause is known and
 * whose remedy is one field in the request we have already built. The decision table below is the
 * remedy's gate and {@link #theTransportReDialsBeforeItRebuilds} is the wire that makes production
 * use it. Revert either and something here goes red.
 *
 * <h2>The two mistakes that are easiest to make here, both covered</h2>
 *
 * <ul>
 *   <li><b>Firing on the wrong shape.</b> The remedy is for ONE outcome — accepted, era read back,
 *       era unmoved. A create the server REFUSED carries an error on the channel refusals use, and
 *       an era we could not READ is not a measurement at all.
 *       {@link #onlyTheAcceptedAndNotAppliedShapeIsRetried} names each of those states and asserts
 *       it does NOT retry, so the shape gate is a check that can fail rather than a formality.</li>
 *   <li><b>Getting the lever gate backwards on a group.</b> {@code mls_advance_fresh_ctxid=1to1}
 *       does NOT mint for a group, so a group advance under that mode still sends the stored id and
 *       still needs the re-dial. Skipping it there would leave every group in exactly the state
 *       this fixes while the sysprop says otherwise.</li>
 * </ul>
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
     * THE FLEET'S CONFIGURATION, which is the whole point: {@code mls_advance_fresh_ctxid} is unset
     * or "off" on 00AU / 0286 / 010T, so every era advance sends the stored contextId and every
     * silent non-advance is the measured shape. Both conversation shapes retry.
     */
    @Test
    public void theFleetDefaultRetries() {
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, ONE_TO_ONE, "off",
                        ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "off", ENABLED));
        // Unset reaches this as null or "" depending on how the property is read; neither may be
        // mistaken for a mode that mints.
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, null, ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "", ENABLED));
    }

    /**
     * THE SHAPE GATE IS A REAL CHECK. Each state below is reachable at the call site — the create
     * can be refused outright, the fetch ledger can refuse the era read, and the era can move — and
     * each must produce a skip rather than an RPC.
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
     * THE LEVER GATE MIRRORS THE PROVIDER'S OWN, AND THE GROUP ASYMMETRY IS THE POINT.
     *
     * <p>{@code freshContextIdForRecreate} mints for {@code all} on either shape and for
     * {@code 1to1} only when there is no RCS group id. So mode {@code 1to1} on a GROUP advance means
     * the stored/engine id went out after all — the re-dial is exactly as warranted there as at
     * {@code off}, and skipping it would leave every group in the state this fixes while the sysprop
     * reads as though it had been addressed.
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
        // Case-insensitive, like the provider's equalsIgnoreCase arms.
        assertEquals(MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "ALL", ENABLED));
        assertEquals(MlsFreshContextRetry.Decision.RETRY,
                MlsFreshContextRetry.decide(ACCEPTED, ERA_READ, NOT_MOVED, GROUP, "OFF", ENABLED));
        // An unrecognised value mints nothing on the provider side (it falls out of both
        // equalsIgnoreCase arms and returns null), so it must not be read here as though it did.
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
     * THE MINT MUST BE THE SHAPE THE FAR SIDE HONOURS, or the re-dial is a probe whose output is
     * identical whether or not it fired: the provider's resolver accepts a supplied contextId only
     * when it passes the 24-char base64url test, so a wrong shape would be discarded in silence and
     * the re-dial would repeat the stored id.
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

    /** The shape predicate rejects what we have actually been putting on the wire. */
    @Test
    public void theShapePredicateRejectsOurInternalKeys() {
        // The values the app's create sites pass today — none is Google Messages-shaped, which is why
        // moving the SUPPLIED arm above the group arm changes no existing create.
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId("p:+15715550106"));
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId(
                "g:22ac7628c97c4cab8809ff8ca4694255"));
        assertFalse("the 32-hex rcsGroupId establishGroup passes is the wrong LENGTH",
                MlsFreshContextRetry.isReferenceShapedContextId(
                        "22ac7628c97c4cab8809ff8ca4694255"));
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId(null));
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId(""));
        // A real capture of Google Messages', and one of ours from the device trials.
        assertTrue(MlsFreshContextRetry.isReferenceShapedContextId("MxwWNgNorfRjuNR6HpufapAQ"));
        assertTrue(MlsFreshContextRetry.isReferenceShapedContextId("Wi5yH5Ugq2T_4IPR3nIgyvhj"));
        assertTrue(MlsFreshContextRetry.isReferenceShapedContextId("gfZ4QFfdSd5GvLdfqu1CYknm"));
        // Padding and the standard (non-url) alphabet are both rejected.
        assertFalse(MlsFreshContextRetry.isReferenceShapedContextId("MxwWNgNorfRjuNR6Hpufap+="));
    }

    /** The sentences must NAME the lever and the measurement, since they are what an operator acts on. */
    @Test
    public void theSentencesCarryTheOperatorsNextStep() {
        final String retrying = MlsFreshContextRetry.retryingLine("g:abc", 4, "off");
        assertTrue(retrying.contains("freshly minted contextId"));
        assertTrue(retrying.contains("No KeyPackage is claimed"));
        final String skip = MlsFreshContextRetry.skipLine(
                MlsFreshContextRetry.Decision.SKIP_LEVER_MAY_HAVE_MINTED, "g:abc", 4, "all");
        assertTrue("the skip must tell the reader how to tell whether the lever ACTUALLY fired — it "
                        + "also needs a prior provider record, which this side cannot see",
                skip.contains("contextId MINTED FRESH"));
        final String granted = MlsFreshContextRetry.grantedLine("g:abc", 4, "MxwWNgNorfRjuNR6HpufapAQ");
        assertTrue(granted.contains("MxwWNgNorfRjuNR6HpufapAQ"));
        final String not = MlsFreshContextRetry.notGrantedLine("g:abc", 4, "MxwWNgNorfRjuNR6HpufapAQ", 3L);
        assertTrue("a re-dial that ALSO did not take is a real result about this conversation",
                not.contains("cause the contextId does not explain"));
        assertNotEquals(granted, not);
    }

    /**
     * THE WIRE. This is the test that fails without the {@code MlsProviderTransport} change.
     *
     * <p>A source scan, under the standing caution about source scans, because
     * {@code MlsProviderTransport} needs a {@code Context} and a bound provider and has no host test
     * at all. It keys on INVOKED METHOD NAMES, never on a log label, and zero hits FAIL: the method
     * body is located and asserted non-empty before any content check.
     *
     * <p><b>The ORDER is the property, not the presence.</b> A re-dial placed after
     * {@code rebuildConversation} costs the same RPC and saves nothing — the group has already been
     * forgotten and re-Welcomed and G2 already charged twice — so "it is called somewhere" is not
     * the invariant. It must run while the artifacts are still in hand.
     */
    @Test
    public void theTransportReDialsBeforeItRebuilds() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "eraAdvanceLocked");
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
        assertTrue("the re-dial must run BEFORE rebuildConversation. After it the group has already "
                        + "been forgotten and re-Welcomed and the era budget charged a second time, "
                        + "so the same RPC would save nothing at all.",
                retryAt < rebuildAt);
    }

    /**
     * The re-dial itself must do the three things that make it a measurement rather than a hope:
     * consult the decision, send the create, and ASK THE AUTHORITY afterwards.
     *
     * <p>The last one is the one worth guarding. {@code fwiq} has no verdict field — one wire field,
     * a two-long response header — so an OK from the re-dial establishes that the call completed and
     * nothing else. A version of this that returned success on {@code again.ok()} would report the
     * era advanced every single time, including the times it did not, which is precisely the blind
     * spot eight device trials were spent measuring around.
     */
    @Test
    public void theReDialAsksTheAuthorityRatherThanBelievingTheOk() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "freshContextIdRetry");
        assertTrue("ZERO HITS MUST FAIL: no body found for freshContextIdRetry", body.length() > 0);
        assertTrue("the decision must come from MlsFreshContextRetry.decide, on the host classpath, "
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
