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
 * Rework 10.2 / 10.3 — the two failure-reason enums, pinned to Google Messages' NUMBERS.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Both enums were numbered by DECLARATION ORDER, which is the one thing §12.3 explicitly forbids:
 * <em>"Never map these by index. The XML enum and the proto enum diverge in both arms."</em> Six of
 * the thirteen client codes and both of the flagged server codes were wrong, and nothing caught it
 * because sequential numbering looks right — the first five happen to agree, which is exactly what
 * makes the rest plausible.
 *
 * <p>These assertions are deliberately literal, one per token. A loop over {@code values()} would
 * re-derive the numbering from order and reproduce the bug it is meant to catch.
 */
public class MlsFailureReasonCodeTest {

    // ---- 10.2: the client arm, C.5 / §12.3 ----

    @Test public void clientCodesMatchTheReferenceClient() {
        assertEquals(1, Reason.MESSAGE_FROM_NON_MEMBER.code());
        assertEquals(2, Reason.INVALID_CREDENTIAL.code());
        assertEquals(3, Reason.INVALID_COMMIT.code());
        assertEquals(4, Reason.FAILED_TO_DECRYPT.code());
        assertEquals(5, Reason.COMMIT_IN_PRIVATEMESSAGE.code());
        // The scramble starts here. Every one of these was wrong.
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
        // The property that was violated, stated directly: if these ever coincide again for the
        // scrambled tokens, someone has renumbered by order.
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

    // ---- 10.2: the server arm, §12.3 — "note the divergence at the last two" ----

    /**
     * The server arm's PROTO numbers, read back through {@code SERVER_BASE}.
     *
     * <p>{@code ServerReason.code()} is deliberately {@code SERVER_BASE + protoNumber}, so the two
     * vocabularies occupy DISJOINT integer spaces on the AIDL — which is §12.5's requirement, and
     * stronger than merely numbering them correctly. The offset is subtracted here so the table below
     * can be compared line-for-line against §12.3 rather than against §12.3-plus-100.
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
        // THE TWO THAT DIVERGE, and the reason this test is written line-by-line rather than as a
        // loop over ordinals. NOT in declaration order — mls-group-has-end-mls is 15 despite being
        // declared LAST in Google Messages' XML-token enum, because the VALUES come from the proto
        // mirror and the two enums are maintained in different orders. Confirmed against both
        // sources: the proto mirror has 15=MLS_GROUP_HAS_END_MLS
        // and 16=EPOCH_ADVANCEMENT_QUOTA_REACHED, while a declaration-ordered listing has them
        // the other way round.
        //
        // This assertion has now caught the same inversion twice — once when it was introduced, and
        // again on 2026-08-03 when a declaration-ordered token list was read as ordinals and these two
        // were swapped back. Do not replace it with a loop.
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

    // ---- the two vocabularies must not share an integer space (§12.5) ----

    @Test public void theTwoVocabulariesShareNoInteger() {
        // §12.5: "Never share one integer space between client and server failure reasons." Our
        // ServerReason.code() offsets by SERVER_BASE precisely so a code can never be resolved
        // against the wrong arm — a stronger guarantee than numbering each arm correctly, and the
        // one that actually protects fromCode().
        final Set<Integer> client = new HashSet<>();
        for (final Reason r : Reason.values()) client.add(r.code());
        for (final ServerReason r : ServerReason.values()) {
            assertFalse(r + " collides with a client code", client.contains(r.code()));
        }
    }

    @Test public void invalidCommitIsTheTokenThatProvesItMatters() {
        // The only token present in BOTH vocabularies. Its proto numbers differ (3 vs 11) AND its
        // AIDL codes differ, so nothing can silently resolve it against the wrong arm.
        assertEquals(3, Reason.INVALID_COMMIT.code());
        assertEquals(11, proto(ServerReason.INVALID_COMMIT));
        assertNotEquals(Reason.INVALID_COMMIT.code(), ServerReason.INVALID_COMMIT.code());
    }

    // ---- 10.3: FAILURE vs recovery OUTCOME ----

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
        // control-message-failed is a FAILURE even though its
        // …_DUE_TO_DOWNGRADE sibling is an outcome — the pair is the easiest to get backwards.
        assertTrue(Reason.CONTROL_MESSAGE_FAILED.isFailure());
    }

    // ---- name-based resolution is the contract (§12.3) ----

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
        // A token this build cannot name must not become a plausible neighbour — that is how an
        // OUTCOME would get treated as a FAILURE and draw a remedy the peer never asked for.
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

    // ---- §12.8, the arm rule the cache clean-up depends on ----

    @Test public void noClientReasonEverCleansTheCache() {
        for (final Reason r : Reason.values()) {
            assertFalse(r + " must not clean the cache", r.cleansCache());
        }
    }
    // ---- 10.1: the arm rule, §12.2 ----

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
        // The conjunct most likely to be broken by a well-meaning change: the failure-reason IMDN is
        // plaintext XML authenticated by a CPIM header, NOT an encrypted IMDN.
        try {
            RccNegativeDeliveryImdn.checkArmRule(true, true, /*hasEncryptedData=*/ true, true, false);
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
        // Explicitly legal, and worth pinning: a plain <failed/> with no reason is a valid receipt,
        // which is why this is a checker rather than a requirement that one be present.
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
        // REJECTED, not thrown: this input is peer-controlled, and throwing on peer-controlled
        // bytes hands a remote party a way to kill the inbound drain thread. Google Messages' arm-rule
        // exceptions are emit-side, where the input is ours.
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
