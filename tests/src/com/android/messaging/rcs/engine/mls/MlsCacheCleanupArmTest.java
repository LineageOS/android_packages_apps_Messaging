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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * §12.8 — WHICH ARM of a negative receipt cleans up the cached outbound ciphertext.
 *
 * <p>This exists because we shipped it exactly inverted: no server reason ever
 * cleaned anything, while the client decrypt-failure arm invalidated the cache and re-encrypted —
 * burning a sender-ratchet generation on the one arm Google Messages leaves alone, and putting a second
 * ciphertext for one message id on a wire whose server dedupes on message id.
 *
 * <p>The inversion is easy to reach twice, so the rule is asserted from both directions rather than
 * spot-checked. It is a two-part rule and it was implemented half-right: the skip-list was honoured
 * and the clean-up was never written.
 */
public class MlsCacheCleanupArmTest {

    @Test public void everyServerReasonCleansExceptTransient() {
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            if (r == RccNegativeDeliveryImdn.ServerReason.TRANSIENT_ERROR) {
                assertFalse(r.xmlElement() + " must NOT clean the cache — it is Google Messages' one "
                        + "'nothing is wrong with our state' reason, so the cached bytes are still "
                        + "valid and must be replayed", r.cleansCache());
            } else {
                assertTrue(r.xmlElement() + " must clean the cache (§12.8)", r.cleansCache());
            }
        }
    }

    @Test public void theQuotaReasonsCleanToo() {
        // Called out because they read like "wait and try later", which invites a skip-list entry.
        // They are not in Google Messages' skip-list, and the skip-list is exactly two long.
        assertTrue(RccNegativeDeliveryImdn.ServerReason.ERA_ADVANCEMENT_QUOTA_REACHED.cleansCache());
        assertTrue(
                RccNegativeDeliveryImdn.ServerReason.EPOCH_ADVANCEMENT_QUOTA_REACHED.cleansCache());
    }

    @Test public void theInformationalLookingServerReasonsCleanToo() {
        assertTrue(RccNegativeDeliveryImdn.ServerReason.MLS_GROUP_NOT_FOUND.cleansCache());
        assertTrue(RccNegativeDeliveryImdn.ServerReason.MLS_GROUP_HAS_END_MLS.cleansCache());
        assertTrue(RccNegativeDeliveryImdn.ServerReason.PENDING_PROPOSAL.cleansCache());
    }

    @Test public void noClientReasonEverCleans() {
        // Including — especially — FAILED_TO_DECRYPT, which is the one that used to.
        for (final RccNegativeDeliveryImdn.Reason r : RccNegativeDeliveryImdn.Reason.values()) {
            assertFalse(r.xmlElement() + " is a CLIENT reason and must never clean the cache: the "
                    + "peer is making a claim about ITS state, not about our bytes. The remedy is a "
                    + "resend, which is a new message with its own id and its own cache entry.",
                    r.cleansCache());
        }
    }

    @Test public void theUnsetCodeIsAbsentNotAReason() {
        // Google Messages' skip-list is {UNSET, TRANSIENT_ERROR}. UNSET has no constant here on purpose:
        // fromCode reports it as null, so a caller holding a ServerReason has already excluded it.
        // If someone ever adds an UNSET constant, cleansCache() must exclude it too — and this test
        // is where that shows up.
        org.junit.Assert.assertNull(RccNegativeDeliveryImdn.ServerReason.fromCode(0));
        org.junit.Assert.assertNull(RccNegativeDeliveryImdn.ServerReason.fromCode(-1));
        for (final RccNegativeDeliveryImdn.ServerReason r
                : RccNegativeDeliveryImdn.ServerReason.values()) {
            assertFalse("no server reason may be named 'unset' without cleansCache() being "
                    + "revisited", "unset".equals(r.xmlElement()));
        }
    }
}
