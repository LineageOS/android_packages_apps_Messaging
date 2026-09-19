/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * §12.8: which arm of a negative receipt cleans up the cached outbound ciphertext. Every
 * server reason except the skip-list {unset (0), TRANSIENT_ERROR} cleans it; no client reason does,
 * so a decrypt failure never burns a sender-ratchet generation or puts a second ciphertext on the
 * wire for one message id. See docs/mls/health-and-recovery.md.
 */
public class MlsCacheCleanupArmTest {

    @Test public void everyServerReasonCleansExceptTransient() {
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            if (r == RccNegativeDeliveryImdn.ServerReason.TRANSIENT_ERROR) {
                assertFalse(r.xmlElement() + " must NOT clean the cache — it is the one "
                        + "'nothing is wrong with our state' reason, so the cached bytes are still "
                        + "valid and must be replayed", r.cleansCache());
            } else {
                assertTrue(r.xmlElement() + " must clean the cache (§12.8)", r.cleansCache());
            }
        }
    }

    @Test public void theQuotaReasonsCleanToo() {
        // These read like "try later" but are not in the skip-list.
        assertTrue(
                RccNegativeDeliveryImdn.ServerReason.ERA_ADVANCEMENT_QUOTA_REACHED.cleansCache());
        assertTrue(
                RccNegativeDeliveryImdn.ServerReason.EPOCH_ADVANCEMENT_QUOTA_REACHED.cleansCache());
    }

    @Test public void theInformationalLookingServerReasonsCleanToo() {
        assertTrue(RccNegativeDeliveryImdn.ServerReason.MLS_GROUP_NOT_FOUND.cleansCache());
        assertTrue(RccNegativeDeliveryImdn.ServerReason.MLS_GROUP_HAS_END_MLS.cleansCache());
        assertTrue(RccNegativeDeliveryImdn.ServerReason.PENDING_PROPOSAL.cleansCache());
    }

    @Test public void noClientReasonEverCleans() {
        // Including FAILED_TO_DECRYPT.
        for (final RccNegativeDeliveryImdn.Reason r : RccNegativeDeliveryImdn.Reason.values()) {
            assertFalse(r.xmlElement() + " is a CLIENT reason and must never clean the cache: the "
                    + "peer is making a claim about ITS state, not about our bytes. The remedy is a "
                    + "resend, which is a new message with its own id and its own cache entry.",
                    r.cleansCache());
        }
    }

    @Test public void theUnsetCodeIsAbsentNotAReason() {
        // Unset (code 0) has no constant: fromCode reports it as null, so a caller holding a
        // ServerReason has already excluded it. An added constant for it must be excluded by
        // cleansCache() too.
        org.junit.Assert.assertNull(RccNegativeDeliveryImdn.ServerReason.fromCode(0));
        org.junit.Assert.assertNull(RccNegativeDeliveryImdn.ServerReason.fromCode(-1));
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            assertFalse("no server reason may be named 'unset' without cleansCache() being "
                    + "revisited", "unset".equals(r.xmlElement()));
        }
    }
}
