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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.engine.mls.RccNegativeDeliveryImdn.Parsed;
import com.android.messaging.rcs.engine.mls.RccNegativeDeliveryImdn.Reason;

import org.junit.Test;

/**
 * RCC.16 v3.0 §7.7.2.2 / §7.7.2.3 negative-delivery IMDN.
 *
 * <p>The expectations here are written from the spec text, not from our own encoder — a
 * round-trip test would pass just as happily with the wrong element names, and the peers we
 * need to interoperate with cannot be asked. So the element names and reason codes are spelled
 * out literally below.
 */
public final class RccNegativeDeliveryImdnTest {

    private static final String MID = "1f2e3d4c-0000-4000-8000-abcdefabcdef";
    private static final String WHEN = "2026-07-30T00:00:00Z";

    // ---------------------------------------------------------------- reason codes

    /**
     * §7.6.3.2 binary values. These cross the AIDL and end up in the signed
     * VerifiableDeliveryImdn, so a renumbering would be silently wrong on the wire.
     */
    @Test
    public void reasonCodesMatchSection7632() {
        assertEquals(1, Reason.MESSAGE_FROM_NON_MEMBER.code());
        assertEquals(2, Reason.INVALID_CREDENTIAL.code());
        assertEquals(3, Reason.INVALID_COMMIT.code());
        assertEquals(4, Reason.FAILED_TO_DECRYPT.code());
        assertEquals(5, Reason.COMMIT_IN_PRIVATEMESSAGE.code());
    }

    /**
     * The three codes {@code MlsProviderTransport.onMlsNegativeDelivery} switches on by LITERAL.
     *
     * <h2>Why this test exists</h2>
     *
     * <p>A Java {@code switch} label must be a compile-time constant, so that switch cannot say
     * {@code Reason.CONTROL_MESSAGE_FAILED.code()} — it writes {@code 13} and a comment. Comments do
     * not fail the build when the table moves, and these three had drifted apart with real
     * consequences (2026-08-09):
     *
     * <ul>
     *   <li>{@code case 6} was labelled <i>control-message-failed</i> and drove an ERA ADVANCE, but 6
     *       is <i>resent-message-for-me-failed-to-decrypt</i>.</li>
     *   <li>{@code case 7} was labelled <i>resent-message-for-me-failed-to-decrypt</i> and logged a
     *       broken-resend alarm, but 7 is <i>commit-processed-in-enhanced-self-heal</i> — a peer
     *       reporting it had already REPAIRED itself.</li>
     *   <li>The real <i>control-message-failed</i> (13) matched no arm and fell through to
     *       "take no action". That code is what {@code requestReWelcome()} sends — the only way a
     *       member holding no group can ask to be re-admitted — so a device asked to rejoin
     *       repeatedly and was ignored every time.</li>
     * </ul>
     *
     * <p>If this test fails, the switch in {@code MlsProviderTransport.onMlsNegativeDelivery} must be
     * renumbered in the same change. That is the entire point of asserting it here.
     */
    @Test
    public void codesSwitchedOnByLiteralInTheHostAreStable() {
        assertEquals("control-message-failed is the re-Welcome request; MlsProviderTransport "
                + "switches on this literal", 13, Reason.CONTROL_MESSAGE_FAILED.code());
        assertEquals("resent-message-for-me-failed-to-decrypt", 6,
                Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT.code());
        assertEquals("commit-processed-in-enhanced-self-heal is an OUTCOME, not a failure", 7,
                Reason.COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL.code());
    }

    /** §7.7.2.3 element names, which are NOT the enum names. */
    @Test
    public void reasonElementNamesMatchSection7723() {
        assertEquals("message-from-non-member", Reason.MESSAGE_FROM_NON_MEMBER.xmlElement());
        assertEquals("invalid-credential", Reason.INVALID_CREDENTIAL.xmlElement());
        assertEquals("invalid-commit", Reason.INVALID_COMMIT.xmlElement());
        assertEquals("commit-in-privatemessage", Reason.COMMIT_IN_PRIVATEMESSAGE.xmlElement());
    }

    /**
     * The one that actually bites: §7.7.2.3's element is {@code failed-to-decrypt} while
     * §7.6.3.2's enum constant is {@code failure_to_decrypt}. Using the enum spelling in the XML
     * produces a report a conformant peer ignores, with no error anywhere.
     */
    @Test
    public void failedToDecryptUsesTheXmlSpellingNotTheEnumSpelling() {
        assertEquals("failed-to-decrypt", Reason.FAILED_TO_DECRYPT.xmlElement());
        assertTrue("the XML spelling must not leak the binary enum's underscore form",
                !Reason.FAILED_TO_DECRYPT.xmlElement().contains("failure"));
        assertTrue(RccNegativeDeliveryImdn.build(MID, Reason.FAILED_TO_DECRYPT, WHEN)
                .contains("<failed-to-decrypt/>"));
    }

    /** The spec's five keep their §7.6.3.2 codes; Google Messages' extra eight round-trip too. */
    @Test
    public void allThirteenClientReasonsRoundTrip() {
        assertEquals(13, Reason.values().length);
        assertNull("0 is not a defined reason", Reason.fromCode(0));
        assertNull("14 is beyond the set", Reason.fromCode(14));
        for (final Reason r : Reason.values()) {
            assertEquals(r, Reason.fromCode(r.code()));
            assertEquals(r, Reason.fromXmlElement(r.xmlElement()));
        }
    }

    /**
     * THE SAFETY PROPERTY. Six client tokens report an OUTCOME — the peer telling us what it already
     * did — not a failure. A caller that branches on "a reason is present" rather than
     * {@link Reason#isFailure()} would answer "commit-processed-in-self-heal" (I fixed it myself)
     * with a rekey and a resend, and would answer "commit-failed-then-era-advancement" by advancing
     * the era a SECOND time — manufacturing the divergence the code exists to repair.
     */
    @Test
    public void outcomeTokensAreNotFailures() {
        assertTrue(Reason.FAILED_TO_DECRYPT.isFailure());
        assertTrue(Reason.INVALID_COMMIT.isFailure());
        assertTrue(Reason.CONTROL_MESSAGE_FAILED.isFailure());
        assertTrue(Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT.isFailure());

        assertTrue(!Reason.COMMIT_PROCESSED_IN_SELF_HEAL.isFailure());
        assertTrue(!Reason.COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL.isFailure());
        assertTrue(!Reason.PROPOSAL_PROCESSED_IN_SELF_HEAL.isFailure());
        assertTrue(!Reason.PROPOSAL_PROCESSED_IN_ENHANCED_SELF_HEAL.isFailure());
        assertTrue(!Reason.COMMIT_FAILED_THEN_ERA_ADVANCEMENT.isFailure());
        assertTrue(!Reason.CONTROL_MESSAGE_FAILED_DUE_TO_DOWNGRADE.isFailure());

        int failures = 0;
        for (final Reason r : Reason.values()) {
            if (r.isFailure()) failures++;
        }
        assertEquals("7 failures + 6 outcomes", 7, failures);
    }

    /** The five spec codes must never drift — they are §7.6.3.2's binary values. */
    @Test
    public void theSpecFiveKeepTheirBinaryCodes() {
        assertEquals(1, Reason.MESSAGE_FROM_NON_MEMBER.code());
        assertEquals(5, Reason.COMMIT_IN_PRIVATEMESSAGE.code());
        assertEquals(4, Reason.FAILED_TO_DECRYPT.code());
    }

    @Test
    public void unknownElementDoesNotResolve() {
        assertNull(Reason.fromXmlElement("failure_to_decrypt"));  // the binary spelling
        assertNull(Reason.fromXmlElement("something-else"));
        assertNull(Reason.fromXmlElement(null));
    }

    // ---------------------------------------------------------------- build

    /**
     * The nesting is load-bearing: §7.7.2.3 puts the reason INSIDE
     * {@code <status><failed>}, not beside it. A reason hung off {@code <status>} directly is
     * schema-invalid.
     */
    @Test
    public void buildNestsReasonInsideStatusFailed() {
        final String xml = RccNegativeDeliveryImdn.build(MID, Reason.FAILED_TO_DECRYPT, WHEN);
        assertTrue(xml.contains("<delivery-notification><status><failed>"
                + "<mls-client-failure-reason><failed-to-decrypt/>"
                + "</mls-client-failure-reason>"
                + "</failed></status></delivery-notification>"));
    }

    /** RFC 5438 essentials: the imdn namespace, the message-id, and a datetime. */
    @Test
    public void buildCarriesRfc5438Envelope() {
        final String xml = RccNegativeDeliveryImdn.build(MID, Reason.INVALID_COMMIT, WHEN);
        assertTrue(xml.contains("<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"));
        assertTrue(xml.contains("<message-id>" + MID + "</message-id>"));
        assertTrue(xml.contains("<datetime>" + WHEN + "</datetime>"));
    }

    /**
     * A negative report must never be able to read as a positive one. If a
     * {@code <delivered/>} or {@code <displayed/>} element appeared anywhere in this document, a
     * peer scanning for it — which is exactly how our own receipt path reads IMDNs — would apply
     * it as proof of delivery.
     */
    @Test
    public void buildNeverLooksLikeAPositiveReceipt() {
        for (final Reason r : Reason.values()) {
            final String xml = RccNegativeDeliveryImdn.build(MID, r, WHEN);
            assertTrue(r + " must not contain <delivered", !xml.contains("<delivered"));
            assertTrue(r + " must not contain <displayed", !xml.contains("<displayed"));
            assertTrue(r + " must declare status failed", xml.contains("<failed>"));
        }
    }

    /** §7.7.2.2 allows this report only for a defined reason, so a reasonless one is refused. */
    @Test
    public void buildRefusesMissingReasonOrMessageId() {
        try {
            RccNegativeDeliveryImdn.build(MID, null, WHEN);
            fail("a negative-delivery IMDN with no reason is not permitted by §7.7.2.2");
        } catch (final IllegalArgumentException expected) {
            // expected
        }
        try {
            RccNegativeDeliveryImdn.build("", Reason.FAILED_TO_DECRYPT, WHEN);
            fail("RFC 5438 requires a message-id");
        } catch (final IllegalArgumentException expected) {
            // expected
        }
    }

    // ---------------------------------------------------------------- parse

    @Test
    public void parseRecoversWhatBuildWrote() {
        for (final Reason r : Reason.values()) {
            final Parsed p = RccNegativeDeliveryImdn.parse(
                    RccNegativeDeliveryImdn.build(MID, r, WHEN));
            assertNotNull(p);
            assertEquals(MID, p.messageId);
            assertEquals(r, p.reason);
        }
    }

    /**
     * A positive receipt must NOT parse as a negative report. This is the guard that keeps the
     * existing delivered/displayed path untouched.
     */
    @Test
    public void parseIgnoresPositiveReceipts() {
        assertNull(RccNegativeDeliveryImdn.parse(
                "<?xml version=\"1.0\"?><imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"
                + "<message-id>" + MID + "</message-id>"
                + "<delivery-notification><status><delivered/></status>"
                + "</delivery-notification></imdn>"));
        assertNull(RccNegativeDeliveryImdn.parse(
                "<imdn><message-id>" + MID + "</message-id>"
                + "<display-notification><status><displayed/></status>"
                + "</display-notification></imdn>"));
        assertNull(RccNegativeDeliveryImdn.parse(null));
        assertNull(RccNegativeDeliveryImdn.parse(""));
    }

    /**
     * Another vendor's formatting must not defeat the parse. Apple implements RCC.16, so these
     * may be arriving from a real peer, and whitespace or attribute differences are not our call
     * to reject.
     */
    @Test
    public void parseToleratesForeignFormatting() {
        final Parsed p = RccNegativeDeliveryImdn.parse(
                "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\n"
                + "  <message-id> " + MID + " </message-id>\n"
                + "  <delivery-notification>\n"
                + "    <status>\n"
                + "      <failed>\n"
                + "        <mls-client-failure-reason>\n"
                + "          <failed-to-decrypt />\n"
                + "        </mls-client-failure-reason>\n"
                + "      </failed>\n"
                + "    </status>\n"
                + "  </delivery-notification>\n"
                + "</imdn>");
        assertNotNull(p);
        assertEquals(MID, p.messageId);
        assertEquals(Reason.FAILED_TO_DECRYPT, p.reason);
    }

    /**
     * A {@code <failed>} whose reason we do not know still has to be recognised as a FAILURE. A
     * future spec revision or a vendor extension must not read as a delivery — and the raw element
     * is kept so it can be named in a log rather than vanishing.
     */
    @Test
    public void parseKeepsAnUnknownReasonAsAFailure() {
        final Parsed p = RccNegativeDeliveryImdn.parse(
                "<imdn><message-id>" + MID + "</message-id>"
                + "<delivery-notification><status><failed>"
                + "<mls-client-failure-reason><some-future-reason/>"
                + "</mls-client-failure-reason></failed></status>"
                + "</delivery-notification></imdn>");
        assertNotNull("an unknown reason is still a failure report", p);
        assertEquals(MID, p.messageId);
        assertNull(p.reason);
        assertEquals("some-future-reason", p.rawReasonElement);
    }

    /**
     * §7.7.2.1's SERVER reasons arrive in the same {@code <failed>} arm and must be decoded, not
     * reported as unknown. Device-observed: a refused rekey came back as {@code <incorrect-era/>},
     * and treating it as a client reason applied the failed-to-decrypt remedy to it.
     */
    @Test
    public void parseDecodesServerFailureReasons() {
        final Parsed p = RccNegativeDeliveryImdn.parse(
                "<imdn><message-id>" + MID + "</message-id>"
                + "<delivery-notification><status><failed>"
                + "<mls-server-failure-reason><incorrect-era/>"
                + "</mls-server-failure-reason></failed></status>"
                + "</delivery-notification></imdn>");
        assertNotNull(p);
        assertEquals(MID, p.messageId);
        assertNull("a SERVER reason is not one of the five CLIENT reasons", p.reason);
        assertEquals(RccNegativeDeliveryImdn.ServerReason.INCORRECT_ERA, p.serverReason);
        // SERVER_BASE + 1, not SERVER_BASE: codes are explicit rather than ordinal-derived, so
        // the first token is 1 and inserting a token cannot renumber the others.
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 1, p.code());
    }

    /**
     * All SIXTEEN server reasons decode — the eleven in the published schema plus the five Google Messages
     * carries that it does not list. Two of those matter especially:
     * {@code era-advancement-quota-reached} is the named wire form of the silent no-move, and
     * {@code mls-group-not-found} / {@code mls-group-has-end-mls} must NOT be answered with a
     * self-heal or a resend, because the group is gone or downgraded.
     */
    @Test
    public void allSixteenServerReasonsDecode() {
        assertEquals(16, RccNegativeDeliveryImdn.ServerReason.values().length);
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            final Parsed p = RccNegativeDeliveryImdn.parse(
                    "<imdn><message-id>" + MID + "</message-id>"
                    + "<delivery-notification><status><failed>"
                    + "<mls-server-failure-reason><" + r.xmlElement() + "/>"
                    + "</mls-server-failure-reason></failed></status>"
                    + "</delivery-notification></imdn>");
            assertNotNull(r.xmlElement(), p);
            assertEquals(r, p.serverReason);
            assertEquals(r, RccNegativeDeliveryImdn.ServerReason.fromCode(r.code()));
        }
    }

    /**
     * {@code invalid-commit} exists in BOTH lists with DIFFERENT meanings — client: "I could not
     * validate your commit"; server: "the messaging server rejected it" — and the remedies differ.
     * A shared code space would silently conflate them, so the codes must not collide.
     */
    @Test
    public void clientAndServerCodeSpacesDoNotCollide() {
        for (final Reason c : Reason.values()) {
            for (final RccNegativeDeliveryImdn.ServerReason srv
                    : RccNegativeDeliveryImdn.ServerReason.values()) {
                assertTrue("client " + c + " and server " + srv + " share code " + c.code(),
                        c.code() != srv.code());
            }
        }
        // And the ambiguous element name resolves to the arm it was found under, not the other.
        final Parsed asServer = RccNegativeDeliveryImdn.parse(
                "<imdn><message-id>" + MID + "</message-id>"
                + "<delivery-notification><status><failed>"
                + "<mls-server-failure-reason><invalid-commit/>"
                + "</mls-server-failure-reason></failed></status>"
                + "</delivery-notification></imdn>");
        assertNotNull(asServer);
        assertNull("found under the SERVER arm, so it is not a client reason", asServer.reason);
        assertEquals(RccNegativeDeliveryImdn.ServerReason.INVALID_COMMIT, asServer.serverReason);
    }

    /**
     * Server codes are EXPLICIT, not ordinal-derived. These went from eleven to sixteen within a
     * day; an ordinal-based code renumbers every token after an insertion, so an old peer would read
     * a NEW meaning from an OLD number. Pinning two anchors catches that regression.
     */
    @Test
    public void serverCodesAreStableUnderInsertion() {
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 1,
                RccNegativeDeliveryImdn.ServerReason.INCORRECT_ERA.code());
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 11,
                RccNegativeDeliveryImdn.ServerReason.INVALID_COMMIT.code());
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 14,
                RccNegativeDeliveryImdn.ServerReason.ERA_ADVANCEMENT_QUOTA_REACHED.code());
    }

    /** No message-id means nothing actionable, even though the document is a failure report. */
    @Test
    public void parseRejectsAReportWithNoMessageId() {
        assertNull(RccNegativeDeliveryImdn.parse(
                "<imdn><delivery-notification><status><failed>"
                + "<mls-client-failure-reason><failed-to-decrypt/>"
                + "</mls-client-failure-reason></failed></status>"
                + "</delivery-notification></imdn>"));
    }

    // ---- disposition totality: the property that keeps the remedy dispatch from accumulating ----

    /**
     * EVERY server reason has a disposition. This is the test that makes the remedy dispatch
     * exhaustive by construction rather than by accumulation.
     *
     * <p>Before it, eleven of sixteen reasons had a remedy arm and five fell to a {@code default}
     * that logged "no remedy implemented for this reason yet". Nothing failed when a token joined
     * that set — which is precisely the shape of a gap that survives review. Adding a
     * {@code ServerReason} now fails here until somebody decides what it means.
     */
    @Test
    public void everyServerReasonIsClassified() {
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            assertNotNull(r + " has no disposition — classify it in ServerReason.disposition()",
                    r.disposition());
        }
    }

    /**
     * The classification of the reasons we have actually SEEN on the wire, pinned individually.
     *
     * <p>Totality alone would be satisfied by mapping everything to {@code NONE}, so the reasons
     * with observed behaviour are pinned to the remedy that behaviour demands.
     */
    @Test
    public void theObservedReasonsKeepTheirRemedies() {
        // Device-observed on a Google Messages peer: the send is refused and our state is behind.
        assertEquals(RccNegativeDeliveryImdn.Disposition.SELF_HEAL,
                RccNegativeDeliveryImdn.ServerReason.INCORRECT_EPOCH_AUTHENTICATOR.disposition());
        // Device-observed 2026-07-29 on a refused rekey.
        assertEquals(RccNegativeDeliveryImdn.Disposition.SELF_HEAL,
                RccNegativeDeliveryImdn.ServerReason.INCORRECT_ERA.disposition());
        // A confirmation tag is DERIVED from group state, so a mismatch is a divergence report.
        assertEquals(RccNegativeDeliveryImdn.Disposition.SELF_HEAL,
                RccNegativeDeliveryImdn.ServerReason.MISMATCHED_CONFIRMATION_TAG.disposition());
        // The one reason Google Messages treats as "nothing is wrong, just retry".
        assertEquals(RccNegativeDeliveryImdn.Disposition.RETRYABLE,
                RccNegativeDeliveryImdn.ServerReason.TRANSIENT_ERROR.disposition());
        // A quota is not cleared by consuming more of it.
        assertEquals(RccNegativeDeliveryImdn.Disposition.OUT_OF_QUOTA,
                RccNegativeDeliveryImdn.ServerReason.ERA_ADVANCEMENT_QUOTA_REACHED.disposition());
        // Terminal: the group is gone or is no longer encrypted.
        assertEquals(RccNegativeDeliveryImdn.Disposition.TERMINAL,
                RccNegativeDeliveryImdn.ServerReason.MLS_GROUP_NOT_FOUND.disposition());
        assertEquals(RccNegativeDeliveryImdn.Disposition.TERMINAL,
                RccNegativeDeliveryImdn.ServerReason.MLS_GROUP_HAS_END_MLS.disposition());
        // Bytes, not state — self-healing a code bug loops.
        assertEquals(RccNegativeDeliveryImdn.Disposition.OURS_TO_FIX,
                RccNegativeDeliveryImdn.ServerReason.UNPARSABLE_COMMIT.disposition());
        // We have a real mechanism for this one, so it gets one.
        assertEquals(RccNegativeDeliveryImdn.Disposition.COMMIT_PENDING_PROPOSALS,
                RccNegativeDeliveryImdn.ServerReason.PENDING_PROPOSAL.disposition());
    }

    /**
     * {@code NONE} is a DECISION and is deliberately rare — if it grows, the dispatch is drifting
     * back to the catch-all this replaced.
     */
    @Test
    public void noneIsTheExceptionNotTheRule() {
        int none = 0;
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            if (r.disposition() == RccNegativeDeliveryImdn.Disposition.NONE) none++;
        }
        assertEquals("exactly invalid-commit and encryption-not-available are unclassifiable today;"
                + " if this changed, say WHY on the enum rather than relaxing the number", 2, none);
    }

    /** The cache-clean rule and the disposition are independent axes and must not be conflated. */
    @Test
    public void transientIsTheOnlyReasonThatSkipsTheCacheClean() {
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            final boolean expected = r != RccNegativeDeliveryImdn.ServerReason.TRANSIENT_ERROR;
            assertEquals(r + " cleansCache()", expected, r.cleansCache());
        }
    }

    /**
     * GOOGLE MESSAGES' ACTUAL NEGATIVE RECEIPT, captured 2026-08-08 and reproduced here byte for byte.
     *
     * <p>This is the whole document — there is no reason element, no MLS header, and no signature,
     * and Google Messages sent it unencrypted. Every other test in this file exercises a shape from the
     * spec; this one exercises the shape that actually arrives.
     */
    private static final String REFERENCE_NEGATIVE_REPORT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"
            + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"
            + "<message-id>mls-grp-b32278650e8840baa663371253f85a20-1786224036558</message-id>"
            + "<datetime>2026-08-08T21:20:34.540Z</datetime>"
            + "<delivery-notification><status><failed/></status></delivery-notification></imdn>";

    @Test
    public void aReasonlessReportParsesAndIsDistinguishableFromAnUnknownElement() {
        final RccNegativeDeliveryImdn.Parsed p = RccNegativeDeliveryImdn.parse(REFERENCE_NEGATIVE_REPORT);
        assertNotNull("Google Messages' own receipt must parse", p);
        assertEquals("mls-grp-b32278650e8840baa663371253f85a20-1786224036558", p.messageId);
        assertNull(p.reason);
        assertNull(p.serverReason);
        assertEquals("a report with NO reason is its own case, not 'unrecognised'",
                RccNegativeDeliveryImdn.NO_REASON_GIVEN, p.code());
    }

    /**
     * The distinction the remedy depends on: absent reason is actionable, an unrecognised element is
     * not. Collapsing them to one code is what made us ignore the first Google Messages report we ever got.
     */
    @Test
    public void anUnrecognisedReasonElementStaysZeroAndIsNotConfusedWithAnAbsentOne() {
        final String unknownEl =
                "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"
                + "<message-id>m1</message-id>"
                + "<delivery-notification><status><failed/></status></delivery-notification>"
                + "<mls-client-failure-reason><some-future-token/></mls-client-failure-reason></imdn>";
        final RccNegativeDeliveryImdn.Parsed p = RccNegativeDeliveryImdn.parse(unknownEl);
        assertNotNull(p);
        assertEquals("some-future-token", p.rawReasonElement);
        assertEquals("an element we do not know must NOT become the actionable no-reason case",
                0, p.code());
        assertNotEquals(RccNegativeDeliveryImdn.NO_REASON_GIVEN, p.code());
    }

    /** NO_REASON_GIVEN must not collide with any real client or server code. */
    @Test
    public void theNoReasonCodeCollidesWithNothing() {
        for (final RccNegativeDeliveryImdn.Reason r : RccNegativeDeliveryImdn.Reason.values()) {
            assertNotEquals(RccNegativeDeliveryImdn.NO_REASON_GIVEN, r.code());
        }
        assertTrue("must sit above the server range so it cannot be read as a server reason",
                RccNegativeDeliveryImdn.NO_REASON_GIVEN > RccNegativeDeliveryImdn.SERVER_BASE);
    }
}
