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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RCC.16 §7.10.4 / §7.10.5 / §10.1.2 — the Enhanced GroupInfo protos and the self-heal procedure.
 *
 * <p>These are decode-and-decide, against a message no live server has yet sent us, so the encoder
 * exists specifically to make the parser testable. That is the only honest way to test this before a
 * capture, and it is why the round trip is asserted rather than assumed.
 */
public class RccEnhancedGroupInfoTest {

    private static byte[] ascii(final String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    @Test
    public void epochIdentifier_roundTrips() {
        final RccEpochIdentifier id = new RccEpochIdentifier(7, 42, ascii("auth"));
        final RccEpochIdentifier back = RccEpochIdentifier.parse(id.encode());
        assertNotNull(back);
        assertEquals(7, back.eraId);
        assertEquals(42, back.epochId);
        assertArrayEquals(ascii("auth"), back.epochAuthenticator);
        assertFalse(back.isEmpty());
    }

    /** proto3 omits zero-valued scalars; an all-default message is legal and parses to zeros. */
    @Test
    public void anAllDefaultEpochIdentifierIsLegalAndEmpty() {
        final RccEpochIdentifier zero = new RccEpochIdentifier(0, 0, null);
        assertEquals(0, zero.encode().length);
        final RccEpochIdentifier back = RccEpochIdentifier.parse(new byte[0]);
        assertNotNull(back);
        assertTrue(back.isEmpty());
    }

    /**
     * The authenticator WINS when both sides have one.
     *
     * <p>This project has already paid for the alternative: we were stamping our own
     * era where the server's authenticator was wanted, producing a message that looked accepted and
     * that the peer rejected. The flat pair is a fallback for our own transport's older shape, never
     * an override.
     */
    @Test
    public void sameEpochAs_prefersTheAuthenticatorAndFallsBackToTheFlatPair() {
        final RccEpochIdentifier a = new RccEpochIdentifier(1, 2, ascii("X"));
        final RccEpochIdentifier sameAuthDifferentNumbers = new RccEpochIdentifier(9, 9, ascii("X"));
        final RccEpochIdentifier sameNumbersDifferentAuth = new RccEpochIdentifier(1, 2, ascii("Y"));
        assertTrue("the authenticator is what the protocol keys on", a.sameEpochAs(sameAuthDifferentNumbers));
        assertFalse(a.sameEpochAs(sameNumbersDifferentAuth));

        // Fallback only when an authenticator is unavailable on either side.
        final RccEpochIdentifier flat = new RccEpochIdentifier(1, 2, null);
        assertTrue(flat.sameEpochAs(new RccEpochIdentifier(1, 2, null)));
        assertTrue(flat.sameEpochAs(a));
        assertFalse(flat.sameEpochAs(new RccEpochIdentifier(1, 3, null)));
        assertFalse(a.sameEpochAs(null));
    }

    private static RccEnhancedGroupInfo build(final RccEpochIdentifier latest,
            final RccEpochIdentifier paginated, final String... messageIds) {
        final List<RccEnhancedGroupInfo.ControlMessage> cms = new ArrayList<>();
        for (final String id : messageIds) {
            cms.add(new RccEnhancedGroupInfo.ControlMessage(id, ascii("body-" + id)));
        }
        return new RccEnhancedGroupInfo(latest, ascii("group-info"), cms, paginated);
    }

    /**
     * {@code committed_control_messages} is REPEATED and its contract is "apply in sequential
     * order". Parsing only the first would look like a self-heal that runs and never catches up.
     */
    @Test
    public void everyControlMessageSurvivesTheRoundTripInOrder() {
        final RccEnhancedGroupInfo src = build(new RccEpochIdentifier(1, 9, ascii("L")),
                new RccEpochIdentifier(1, 5, ascii("P")), "m1", "m2", "m3");
        final RccEnhancedGroupInfo back = RccEnhancedGroupInfo.parse(src.encode());
        assertNotNull(back);
        assertEquals(3, back.committedControlMessages.size());
        assertEquals("m1", back.committedControlMessages.get(0).rcsMessageId);
        assertEquals("m2", back.committedControlMessages.get(1).rcsMessageId);
        assertEquals("m3", back.committedControlMessages.get(2).rcsMessageId);
        assertArrayEquals(ascii("body-m2"), back.committedControlMessages.get(1).serverMlsRcsMessage);
        assertArrayEquals(ascii("group-info"), back.mlsGroupInfo);
        assertEquals(9, back.latestEpoch.epochId);
        assertEquals(5, back.paginatedEpoch.epochId);
    }

    /** Absence of paginated_epoch_identifier is a CONTROL SIGNAL, so it must survive as null. */
    @Test
    public void anAbsentPaginatedIdentifierStaysNullRatherThanBecomingEmpty() {
        final RccEnhancedGroupInfo src = build(new RccEpochIdentifier(1, 9, ascii("L")), null, "m1");
        final RccEnhancedGroupInfo back = RccEnhancedGroupInfo.parse(src.encode());
        assertNotNull(back);
        assertNull("§10.1.2 branches on absence — it must not collapse into an empty identifier",
                back.paginatedEpoch);
    }

    // ---- §10.1.2 procedure --------------------------------------------------------------------

    @Test
    public void alreadyAtLatestIsComplete() {
        final RccEpochIdentifier here = new RccEpochIdentifier(1, 9, ascii("L"));
        final MlsEnhancedSelfHeal.Plan p =
                MlsEnhancedSelfHeal.evaluate(build(here, null, "m1"), here);
        assertEquals(MlsEnhancedSelfHeal.Step.COMPLETE, p.step);
    }

    /**
     * The up-to-date check comes BEFORE the pagination check. A client that is already current must
     * not be dragged into an External Commit merely because the server sent no page.
     */
    @Test
    public void beingUpToDateWinsOverAMissingPage() {
        final RccEpochIdentifier here = new RccEpochIdentifier(2, 4, ascii("SAME"));
        final RccEnhancedGroupInfo resp = build(here, /*paginated=*/ null);
        assertEquals(MlsEnhancedSelfHeal.Step.COMPLETE,
                MlsEnhancedSelfHeal.evaluate(resp, here).step);
    }

    @Test
    public void noPaginatedIdentifierFallsBackToExternalCommit() {
        final MlsEnhancedSelfHeal.Plan p = MlsEnhancedSelfHeal.evaluate(
                build(new RccEpochIdentifier(1, 9, ascii("L")), null, "m1"),
                new RccEpochIdentifier(1, 1, ascii("HERE")));
        assertEquals(MlsEnhancedSelfHeal.Step.FALL_BACK_TO_EXTERNAL_COMMIT, p.step);
        assertTrue(p.reason.contains("paginated_epoch_identifier"));
    }

    @Test
    public void aFailedPullFallsBackRatherThanThrowing() {
        assertEquals(MlsEnhancedSelfHeal.Step.FALL_BACK_TO_EXTERNAL_COMMIT,
                MlsEnhancedSelfHeal.evaluate(null, new RccEpochIdentifier(1, 1, null)).step);
    }

    /** A page that names an end but carries nothing cannot advance us; looping would never end. */
    @Test
    public void anEmptyPageFallsBackInsteadOfLoopingForever() {
        final MlsEnhancedSelfHeal.Plan p = MlsEnhancedSelfHeal.evaluate(
                build(new RccEpochIdentifier(1, 9, ascii("L")),
                        new RccEpochIdentifier(1, 5, ascii("P"))),
                new RccEpochIdentifier(1, 1, ascii("HERE")));
        assertEquals(MlsEnhancedSelfHeal.Step.FALL_BACK_TO_EXTERNAL_COMMIT, p.step);
    }

    @Test
    public void aUsablePageIsAppliedInOrder() {
        final MlsEnhancedSelfHeal.Plan p = MlsEnhancedSelfHeal.evaluate(
                build(new RccEpochIdentifier(1, 9, ascii("L")),
                        new RccEpochIdentifier(1, 5, ascii("P")), "m1", "m2"),
                new RccEpochIdentifier(1, 1, ascii("HERE")));
        assertEquals(MlsEnhancedSelfHeal.Step.APPLY_CONTROL_MESSAGES, p.step);
        assertEquals(2, p.toApply.size());
        assertEquals("m1", p.toApply.get(0).rcsMessageId);
    }

    /**
     * ONE failure aborts the WHOLE enhanced attempt. This is a correctness property: partial
     * application leaves the group advanced to an epoch nobody named, so the next fetch compares
     * against a state neither side agrees on.
     */
    @Test
    public void anySingleFailureAbandonsTheEnhancedPathEntirely() {
        final RccEnhancedGroupInfo resp = build(new RccEpochIdentifier(1, 9, ascii("L")),
                new RccEpochIdentifier(1, 9, ascii("L")), "m1", "m2");
        assertEquals(MlsEnhancedSelfHeal.Step.FALL_BACK_TO_EXTERNAL_COMMIT,
                MlsEnhancedSelfHeal.afterPage(resp, /*allApplied=*/ false).step);
        // ...whereas a fully applied page that reached the latest epoch completes.
        assertEquals(MlsEnhancedSelfHeal.Step.COMPLETE,
                MlsEnhancedSelfHeal.afterPage(resp, /*allApplied=*/ true).step);
    }

    @Test
    public void apageThatDidNotReachTheLatestEpochRequestsAnother() {
        final RccEnhancedGroupInfo resp = build(new RccEpochIdentifier(1, 9, ascii("L")),
                new RccEpochIdentifier(1, 5, ascii("P")), "m1");
        final MlsEnhancedSelfHeal.Plan p = MlsEnhancedSelfHeal.afterPage(resp, true);
        assertEquals(MlsEnhancedSelfHeal.Step.REQUEST_NEXT_PAGE, p.step);
        assertEquals(5, p.nextRequest.epochId);
    }

    /**
     * §10.1.2's IMDN rule, both halves: silence for anything only ever seen on the replay path, and
     * a SUCCESS acknowledgement for the duplicate that later arrives on MSRP.
     */
    @Test
    public void imdnsAreSuppressedForReplayOnlyMessagesAndSentForMsrpDuplicates() {
        assertFalse("never acknowledge a message the sender did not send us",
                MlsEnhancedSelfHeal.shouldSendImdn(true, false));
        assertTrue("a duplicate on MSRP is idempotent and successful, not a failure",
                MlsEnhancedSelfHeal.shouldSendImdn(true, true));
        assertTrue("ordinary messages are unaffected",
                MlsEnhancedSelfHeal.shouldSendImdn(false, false));
    }
}
