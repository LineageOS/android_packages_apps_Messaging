/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.engine.mls.RccNegativeDeliveryImdn.Reason;
import com.android.messaging.rcs.engine.mls.RccNegativeDeliveryImdn.ServerReason;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * The two failure-reason enums, pinned to the reference client's numbers (§12.3: never map
 * by index; the XML and proto enums diverge in both arms). The assertions are literal, one per
 * token, because a loop over {@code values()} would re-derive the numbering from order. See
 * docs/mls/health-and-recovery.md.
 */
public class MlsFailureReasonCodeTest {

    // The client arm (C.5, §12.3).

    @Test public void clientCodesMatchTheReferenceClient() {
        assertEquals(1, Reason.MESSAGE_FROM_NON_MEMBER.code());
        assertEquals(2, Reason.INVALID_CREDENTIAL.code());
        assertEquals(3, Reason.INVALID_COMMIT.code());
        assertEquals(4, Reason.FAILED_TO_DECRYPT.code());
        assertEquals(5, Reason.COMMIT_IN_PRIVATEMESSAGE.code());
        // From here the numbers diverge from declaration order.
        assertEquals(6, Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT.code());
        assertEquals(7, Reason.COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL.code());
        assertEquals(8, Reason.COMMIT_PROCESSED_IN_SELF_HEAL.code());
        assertEquals(9, Reason.COMMIT_FAILED_THEN_ERA_ADVANCEMENT.code());
        assertEquals(10, Reason.CONTROL_MESSAGE_FAILED_DUE_TO_DOWNGRADE.code());
        assertEquals(11, Reason.PROPOSAL_PROCESSED_IN_ENHANCED_SELF_HEAL.code());
        assertEquals(12, Reason.PROPOSAL_PROCESSED_IN_SELF_HEAL.code());
        assertEquals(13, Reason.CONTROL_MESSAGE_FAILED.code());
    }

    @Test public void theClientCodeIsNotTheDeclarationIndex() {
        // If these coincide again for the diverging tokens, someone has renumbered by order.
        assertNotEquals(Reason.CONTROL_MESSAGE_FAILED.ordinal() + 1,
                Reason.CONTROL_MESSAGE_FAILED.code());
        assertNotEquals(Reason.COMMIT_FAILED_THEN_ERA_ADVANCEMENT.ordinal() + 1,
                Reason.COMMIT_FAILED_THEN_ERA_ADVANCEMENT.code());
    }

    @Test public void clientCodesAreUnique() {
        final Set<Integer> seen = new HashSet<>();
        for (final Reason r : Reason.values()) {
            assertTrue("duplicate client code " + r.code() + " at " + r, seen.add(r.code()));
        }
        assertEquals(13, seen.size());
    }

    // The server arm (§12.3), which diverges at the last two.

    /**
     * The server arm's proto numbers, read back through {@code SERVER_BASE}:
     * {@code ServerReason.code()} is {@code SERVER_BASE + protoNumber}, so the two vocabularies
     * occupy disjoint integer spaces on the AIDL (§12.5), and the table below compares line
     * for line with §12.3.
     */
    @Test public void serverCodesMatchTheReferenceClient() {
        assertEquals(1, proto(ServerReason.INCORRECT_ERA));
        assertEquals(2, proto(ServerReason.INCORRECT_EPOCH));
        assertEquals(3, proto(ServerReason.INCORRECT_EPOCH_AUTHENTICATOR));
        assertEquals(4, proto(ServerReason.EXPIRED_CREDENTIAL));
        assertEquals(5, proto(ServerReason.MISMATCHED_RCS_GROUP_STATE));
        assertEquals(6, proto(ServerReason.UNPARSABLE_COMMIT));
        assertEquals(7, proto(ServerReason.MISMATCHED_CONFIRMATION_TAG));
        assertEquals(8, proto(ServerReason.PENDING_PROPOSAL));
        assertEquals(9, proto(ServerReason.TRANSIENT_ERROR));
        assertEquals(10, proto(ServerReason.ENCRYPTION_NOT_AVAILABLE));
        assertEquals(11, proto(ServerReason.INVALID_COMMIT));
        assertEquals(12, proto(ServerReason.MLS_GROUP_NOT_FOUND));
        assertEquals(13, proto(ServerReason.INVALID_INPUT));
        assertEquals(14, proto(ServerReason.ERA_ADVANCEMENT_QUOTA_REACHED));
        // Not in declaration order: the values come from the proto enum, where
        // 15=MLS_GROUP_HAS_END_MLS and 16=EPOCH_ADVANCEMENT_QUOTA_REACHED, while the XML-token enum
        // declares them the other way round. Do not replace this with a loop.
        assertEquals(15, proto(ServerReason.MLS_GROUP_HAS_END_MLS));
        assertEquals(16, proto(ServerReason.EPOCH_ADVANCEMENT_QUOTA_REACHED));
    }

    private static int proto(final ServerReason r) {
        return r.code() - RccNegativeDeliveryImdn.SERVER_BASE;
    }

    @Test public void serverCodesAreUnique() {
        final Set<Integer> seen = new HashSet<>();
        for (final ServerReason r : ServerReason.values()) {
            assertTrue("duplicate server code " + r.code() + " at " + r, seen.add(r.code()));
        }
        assertEquals(16, seen.size());
    }

    // The two vocabularies do not share an integer space (§12.5).

    @Test public void theTwoVocabulariesShareNoInteger() {
        // SERVER_BASE keeps a code from ever resolving against the wrong arm, which is what
        // protects fromCode().
        final Set<Integer> client = new HashSet<>();
        for (final Reason r : Reason.values()) client.add(r.code());
        for (final ServerReason r : ServerReason.values()) {
            assertFalse(r + " collides with a client code", client.contains(r.code()));
        }
    }

    @Test public void invalidCommitIsTheTokenThatProvesItMatters() {
        // The only token in both vocabularies: its proto numbers (3 vs 11) and its AIDL codes
        // differ.
        assertEquals(3, Reason.INVALID_COMMIT.code());
        assertEquals(11, proto(ServerReason.INVALID_COMMIT));
        assertNotEquals(Reason.INVALID_COMMIT.code(), ServerReason.INVALID_COMMIT.code());
    }

    // Failure versus recovery outcome.

    @Test public void exactlySixClientTokensAreOutcomes() {
        int outcomes = 0;
        for (final Reason r : Reason.values()) if (!r.isFailure()) outcomes++;
        assertEquals("six of thirteen, §12.4", 6, outcomes);
    }

    @Test public void theOutcomeSetIsExactlyTheSix() {
        assertFalse(Reason.COMMIT_PROCESSED_IN_ENHANCED_SELF_HEAL.isFailure());
        assertFalse(Reason.COMMIT_PROCESSED_IN_SELF_HEAL.isFailure());
        assertFalse(Reason.COMMIT_FAILED_THEN_ERA_ADVANCEMENT.isFailure());
        assertFalse(Reason.CONTROL_MESSAGE_FAILED_DUE_TO_DOWNGRADE.isFailure());
        assertFalse(Reason.PROPOSAL_PROCESSED_IN_ENHANCED_SELF_HEAL.isFailure());
        assertFalse(Reason.PROPOSAL_PROCESSED_IN_SELF_HEAL.isFailure());
    }

    @Test public void theFailureSetIsEverythingElse() {
        assertTrue(Reason.MESSAGE_FROM_NON_MEMBER.isFailure());
        assertTrue(Reason.INVALID_CREDENTIAL.isFailure());
        assertTrue(Reason.INVALID_COMMIT.isFailure());
        assertTrue(Reason.FAILED_TO_DECRYPT.isFailure());
        assertTrue(Reason.COMMIT_IN_PRIVATEMESSAGE.isFailure());
        assertTrue(Reason.RESENT_MESSAGE_FOR_ME_FAILED_TO_DECRYPT.isFailure());
        // control-message-failed is a failure although its …_DUE_TO_DOWNGRADE sibling is an
        // outcome.
        assertTrue(Reason.CONTROL_MESSAGE_FAILED.isFailure());
    }

    // Name-based resolution is the contract (§12.3).

    @Test public void everyClientTokenRoundTripsByNAME() {
        for (final Reason r : Reason.values()) {
            assertEquals(r, Reason.fromXmlElement(r.xmlElement()));
        }
    }

    @Test public void everyServerTokenRoundTripsByNAME() {
        for (final ServerReason r : ServerReason.values()) {
            assertEquals(r, ServerReason.fromXmlElement(r.xmlElement()));
        }
    }

    @Test public void anUnknownTokenResolvesToNullRatherThanADefault() {
        // An unknown token resolves to null, not a neighbour; otherwise an outcome could be treated
        // as a failure and draw a remedy the peer never asked for.
        assertNull(Reason.fromXmlElement("not-a-token"));
        assertNull(ServerReason.fromXmlElement("not-a-token"));
        assertNull(Reason.fromXmlElement(null));
        assertNull(ServerReason.fromXmlElement(null));
    }

    @Test public void codeRoundTripsToo() {
        for (final Reason r : Reason.values()) assertEquals(r, Reason.fromCode(r.code()));
        for (final ServerReason r : ServerReason.values()) {
            assertEquals(r, ServerReason.fromCode(r.code()));
        }
        assertNull(Reason.fromCode(99));
        assertNull(ServerReason.fromCode(99));
    }

    // The arm rule the cache clean-up depends on (§12.8).

    @Test public void noClientReasonEverCleansTheCache() {
        for (final Reason r : Reason.values()) {
            assertFalse(r + " must not clean the cache", r.cleansCache());
        }
    }
    // The arm rule (§12.2).

    @Test public void bothArmsSetIsRefusedWithTheReferenceClientsText() {
        try {
            RccNegativeDeliveryImdn.checkArmRule(true, true, false, true, true);
            fail("both arms must be refused");
        } catch (final IllegalStateException e) {
            assertEquals("Either mls-server-failure-reason or mls-client-failure-reason "
                    + "should be set, but not both", e.getMessage());
        }
    }

    @Test public void anArmOnAnENCRYPTEDReceiptIsRefused() {
        // The failure-reason IMDN is plaintext XML authenticated by a CPIM header, not an encrypted
        // IMDN.
        try {
            RccNegativeDeliveryImdn.checkArmRule(true, true, /*hasEncryptedData=*/ true, true,
                    false);
            fail("an arm on an encrypted receipt must be refused");
        } catch (final IllegalStateException e) {
            assertEquals("Only set mls-client-failure-reason for a failed MLS delivery receipt",
                    e.getMessage());
        }
    }

    @Test public void anArmOnANonFailedOrNonDeliveryReceiptIsRefused() {
        for (final boolean delivery : new boolean[] { true, false }) {
            for (final boolean failed : new boolean[] { true, false }) {
                if (delivery && failed) continue;   // the legal combination
                try {
                    RccNegativeDeliveryImdn.checkArmRule(delivery, failed, false, false, true);
                    fail("server arm illegal at delivery=" + delivery + " failed=" + failed);
                } catch (final IllegalStateException e) {
                    assertEquals("Only set mls-server-failure-reason for a failed MLS delivery "
                            + "receipt", e.getMessage());
                }
            }
        }
    }

    @Test public void zeroArmsIsLegalEverywhere() {
        // A plain <failed/> with no reason is a valid receipt, so this checks rather than requires.
        RccNegativeDeliveryImdn.checkArmRule(true, true, false, false, false);
        RccNegativeDeliveryImdn.checkArmRule(false, false, true, false, false);
    }

    @Test public void exactlyOneArmOnAFailedUnencryptedDeliveryPasses() {
        RccNegativeDeliveryImdn.checkArmRule(true, true, false, true, false);
        RccNegativeDeliveryImdn.checkArmRule(true, true, false, false, true);
    }

    @Test public void aReceiptCarryingBOTHArmsIsRefusedRatherThanPreferred() {
        final String xml = "<?xml version=\"1.0\"?><imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">"
                + "<message-id>m1</message-id><delivery-notification><status><failed>"
                + "<mls-client-failure-reason><failed-to-decrypt/></mls-client-failure-reason>"
                + "<mls-server-failure-reason><incorrect-era/></mls-server-failure-reason>"
                + "</failed></status></delivery-notification></imdn>";
        // Rejected, not thrown: the input is peer-controlled, and a throw would let a remote party
        // kill the inbound drain thread. The reference client's arm-rule exceptions are emit-side.
        assertNull(RccNegativeDeliveryImdn.parse(xml));
    }

    @Test public void oneArmStillParses() {
        assertEquals(Reason.FAILED_TO_DECRYPT,
                RccNegativeDeliveryImdn.parse("<imdn><message-id>m1</message-id>"
                        + "<delivery-notification><status><failed>"
                        + "<mls-client-failure-reason><failed-to-decrypt/></mls-client-failure-reason>"
                        + "</failed></status></delivery-notification></imdn>").reason);
    }
}
