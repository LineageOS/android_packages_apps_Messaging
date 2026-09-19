/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The negative-delivery IMDN (RCC.16 v3.0 §7.7.2.2, §7.7.2.3). Element names and reason codes are
 * spelled out from the spec text, since a round trip would pass with the wrong ones. See
 * docs/mls/health-and-recovery.md.
 */
public final class RccNegativeDeliveryImdnTest {

    private static final String MID = "1f2e3d4c-0000-4000-8000-abcdefabcdef";
    private static final String WHEN = "2026-07-30T00:00:00Z";

    /** RCC.16 §7.6.3.2 binary values; they cross the AIDL and are signed on the wire. */
    @Test
    public void reasonCodesMatchSection7632() {
        assertEquals(1, Reason.MESSAGE_FROM_NON_MEMBER.code());
        assertEquals(2, Reason.INVALID_CREDENTIAL.code());
        assertEquals(3, Reason.INVALID_COMMIT.code());
        assertEquals(4, Reason.FAILED_TO_DECRYPT.code());
        assertEquals(5, Reason.COMMIT_IN_PRIVATEMESSAGE.code());
    }

    /**
     * {@code MlsProviderTransport.onMlsNegativeDelivery} switches on these codes as literals (a
     * {@code switch} label must be a constant). If this fails, renumber that switch in the same
     * change. 13 is also what {@code requestReWelcome()} sends.
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

    /** RCC.16 §7.7.2.3 element names, which are not the enum names. */
    @Test
    public void reasonElementNamesMatchSection7723() {
        assertEquals("message-from-non-member", Reason.MESSAGE_FROM_NON_MEMBER.xmlElement());
        assertEquals("invalid-credential", Reason.INVALID_CREDENTIAL.xmlElement());
        assertEquals("invalid-commit", Reason.INVALID_COMMIT.xmlElement());
        assertEquals("commit-in-privatemessage", Reason.COMMIT_IN_PRIVATEMESSAGE.xmlElement());
    }

    /**
     * The element is {@code failed-to-decrypt} (RCC.16 §7.7.2.3); the binary enum spells it
     * {@code failure_to_decrypt} (RCC.16 §7.6.3.2). A peer ignores the enum spelling in XML.
     */
    @Test
    public void failedToDecryptUsesTheXmlSpellingNotTheEnumSpelling() {
        assertEquals("failed-to-decrypt", Reason.FAILED_TO_DECRYPT.xmlElement());
        assertTrue("the XML spelling must not leak the binary enum's underscore form",
                !Reason.FAILED_TO_DECRYPT.xmlElement().contains("failure"));
        assertTrue(RccNegativeDeliveryImdn.build(MID, Reason.FAILED_TO_DECRYPT, WHEN)
                .contains("<failed-to-decrypt/>"));
    }

    /** The spec's five keep their codes; the eight further tokens peers send round-trip too. */
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
     * Six client tokens report what the peer already did, not a failure; answering them as failures
     * would rekey, resend, or advance the era a second time.
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

    @Test
    public void theSpecFiveKeepTheirBinaryCodes() {
        assertEquals(1, Reason.MESSAGE_FROM_NON_MEMBER.code());
        assertEquals(5, Reason.COMMIT_IN_PRIVATEMESSAGE.code());
        assertEquals(4, Reason.FAILED_TO_DECRYPT.code());
    }

    @Test
    public void unknownElementDoesNotResolve() {
        assertNull(Reason.fromXmlElement("failure_to_decrypt"));
        assertNull(Reason.fromXmlElement("something-else"));
        assertNull(Reason.fromXmlElement(null));
    }

    /** The reason sits inside {@code <status><failed>} (RCC.16 §7.7.2.3), not beside it. */
    @Test
    public void buildNestsReasonInsideStatusFailed() {
        final String xml = RccNegativeDeliveryImdn.build(MID, Reason.FAILED_TO_DECRYPT, WHEN);
        assertTrue(xml.contains("<delivery-notification><status><failed>"
                + "<mls-client-failure-reason><failed-to-decrypt/>"
                + "</mls-client-failure-reason>"
                + "</failed></status></delivery-notification>"));
    }

    /** RFC 5438: the imdn namespace, the message-id and a datetime. */
    @Test
    public void buildCarriesRfc5438Envelope() {
        final String xml = RccNegativeDeliveryImdn.build(MID, Reason.INVALID_COMMIT, WHEN);
        assertTrue(xml.contains("<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"));
        assertTrue(xml.contains("<message-id>" + MID + "</message-id>"));
        assertTrue(xml.contains("<datetime>" + WHEN + "</datetime>"));
    }

    /** A receipt path scanning for {@code <delivered/>} must never find it in a negative report. */
    @Test
    public void buildNeverLooksLikeAPositiveReceipt() {
        for (final Reason r : Reason.values()) {
            final String xml = RccNegativeDeliveryImdn.build(MID, r, WHEN);
            assertTrue(r + " must not contain <delivered", !xml.contains("<delivered"));
            assertTrue(r + " must not contain <displayed", !xml.contains("<displayed"));
            assertTrue(r + " must declare status failed", xml.contains("<failed>"));
        }
    }

    /** RCC.16 §7.7.2.2 allows this report only for a defined reason. */
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

    /** Other clients' whitespace and formatting must not defeat the parse. */
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

    /** An unknown reason is still a failure, and its raw element is kept for the log. */
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

    /** Server reasons (RCC.16 §7.7.2.1) arrive in the same {@code <failed>} arm. */
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
        // Codes are explicit, not ordinal-derived, so the first token is SERVER_BASE + 1.
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 1, p.code());
    }

    /** The eleven server reasons in the published schema, plus five more peers send. */
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

    /** {@code invalid-commit} is in both lists with different meanings and remedies. */
    @Test
    public void clientAndServerCodeSpacesDoNotCollide() {
        for (final Reason c : Reason.values()) {
            for (final RccNegativeDeliveryImdn.ServerReason srv
                    : RccNegativeDeliveryImdn.ServerReason.values()) {
                assertTrue("client " + c + " and server " + srv + " share code " + c.code(),
                        c.code() != srv.code());
            }
        }
        // The element name resolves to the arm it was found under.
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

    /** Explicit codes: an insertion must not renumber the tokens after it. */
    @Test
    public void serverCodesAreStableUnderInsertion() {
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 1,
                RccNegativeDeliveryImdn.ServerReason.INCORRECT_ERA.code());
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 11,
                RccNegativeDeliveryImdn.ServerReason.INVALID_COMMIT.code());
        assertEquals(RccNegativeDeliveryImdn.SERVER_BASE + 14,
                RccNegativeDeliveryImdn.ServerReason.ERA_ADVANCEMENT_QUOTA_REACHED.code());
    }

    @Test
    public void parseRejectsAReportWithNoMessageId() {
        assertNull(RccNegativeDeliveryImdn.parse(
                "<imdn><delivery-notification><status><failed>"
                + "<mls-client-failure-reason><failed-to-decrypt/>"
                + "</mls-client-failure-reason></failed></status>"
                + "</delivery-notification></imdn>"));
    }

    /** A new {@code ServerReason} fails here until it has a disposition. */
    @Test
    public void everyServerReasonIsClassified() {
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            assertNotNull(r + " has no disposition — classify it in ServerReason.disposition()",
                    r.disposition());
        }
    }

    /** Totality alone would pass with everything {@code NONE}, so known reasons are pinned. */
    @Test
    public void theObservedReasonsKeepTheirRemedies() {
        // The send is refused and our state is behind.
        assertEquals(RccNegativeDeliveryImdn.Disposition.SELF_HEAL,
                RccNegativeDeliveryImdn.ServerReason.INCORRECT_EPOCH_AUTHENTICATOR.disposition());
        // A refused rekey.
        assertEquals(RccNegativeDeliveryImdn.Disposition.SELF_HEAL,
                RccNegativeDeliveryImdn.ServerReason.INCORRECT_ERA.disposition());
        // A confirmation tag is derived from group state, so a mismatch reports divergence.
        assertEquals(RccNegativeDeliveryImdn.Disposition.SELF_HEAL,
                RccNegativeDeliveryImdn.ServerReason.MISMATCHED_CONFIRMATION_TAG.disposition());
        // The one reason that means "nothing is wrong, retry".
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
        // Bytes, not state: self-healing a code bug would loop.
        assertEquals(RccNegativeDeliveryImdn.Disposition.OURS_TO_FIX,
                RccNegativeDeliveryImdn.ServerReason.UNPARSABLE_COMMIT.disposition());
        assertEquals(RccNegativeDeliveryImdn.Disposition.COMMIT_PENDING_PROPOSALS,
                RccNegativeDeliveryImdn.ServerReason.PENDING_PROPOSAL.disposition());
    }

    /** {@code NONE} is a decision and deliberately rare. */
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

    /** The cache-clean rule is independent of the disposition. */
    @Test
    public void transientIsTheOnlyReasonThatSkipsTheCacheClean() {
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            final boolean expected = r != RccNegativeDeliveryImdn.ServerReason.TRANSIENT_ERROR;
            assertEquals(r + " cleansCache()", expected, r.cleansCache());
        }
    }

    /** A negative receipt as a peer sent it: unencrypted, with no reason, header or signature. */
    private static final String REFERENCE_NEGATIVE_REPORT =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"
            + "<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"
            + "<message-id>mls-grp-b32278650e8840baa663371253f85a20-1786224036558</message-id>"
            + "<datetime>2026-08-08T21:20:34.540Z</datetime>"
            + "<delivery-notification><status><failed/></status></delivery-notification></imdn>";

    @Test
    public void aReasonlessReportParsesAndIsDistinguishableFromAnUnknownElement() {
        final RccNegativeDeliveryImdn.Parsed p =
                RccNegativeDeliveryImdn.parse(REFERENCE_NEGATIVE_REPORT);
        assertNotNull("a peer's own receipt must parse", p);
        assertEquals("mls-grp-b32278650e8840baa663371253f85a20-1786224036558", p.messageId);
        assertNull(p.reason);
        assertNull(p.serverReason);
        assertEquals("a report with NO reason is its own case, not 'unrecognised'",
                RccNegativeDeliveryImdn.NO_REASON_GIVEN, p.code());
    }

    /** An absent reason is actionable; an unrecognised element is not. */
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

    /** {@code NO_REASON_GIVEN} collides with no client or server code. */
    @Test
    public void theNoReasonCodeCollidesWithNothing() {
        for (final RccNegativeDeliveryImdn.Reason r : RccNegativeDeliveryImdn.Reason.values()) {
            assertNotEquals(RccNegativeDeliveryImdn.NO_REASON_GIVEN, r.code());
        }
        assertTrue("must sit above the server range so it cannot be read as a server reason",
                RccNegativeDeliveryImdn.NO_REASON_GIVEN > RccNegativeDeliveryImdn.SERVER_BASE);
    }
}
