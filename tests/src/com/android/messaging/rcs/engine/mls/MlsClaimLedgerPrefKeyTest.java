/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MlsClaimLedgerPrefKeyTest {


    @Test
    public void claimLedgerPrefKeyIsStableAndNamesAMissingPeer() {
        assertEquals("mls_claim_ledger_+15550000000",
                MlsClaimLedger.claimLedgerPrefKey("+15550000000"));
        assertEquals("mls_claim_ledger_<none>", MlsClaimLedger.claimLedgerPrefKey(""));
        assertEquals("mls_claim_ledger_<none>", MlsClaimLedger.claimLedgerPrefKey(null));
    }
}
