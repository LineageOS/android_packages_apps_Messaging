/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Recovering the RCS group id from a legacy group wire id: an inbound MLS IMDN carries no group
 * (RCC.16 §12.9), and legacy group ids were never stored as {@code rcs_message_id}.
 */
public class MlsLegacyGroupWireIdTest {

    private static final String GID = "5B8905CD-DEF5-414C-BC3F-5343069C256D";

    @Test public void theGroupIdComesBackOutOfARealWireId() {
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-0"));
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-2"));
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-1234"));
    }

    @Test public void aGroupIdFullOfDASHESStillSurvives() {
        // A UUID group id contains four dashes; splitting on the first dash or a fixed count would
        // yield a group that matches nothing, which looks like a successful resolution.
        assertTrue(GID.contains("-"));
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-7"));
    }

    @Test public void aNonNumericTailIsNotAGeneration() {
        // Without the digit check, a group id whose own tail looked like a segment would be
        // truncated.
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-abc"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-1a"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-"));
    }

    @Test public void otherIdShapesAreNotGroupIds() {
        // Other id shapes are not group wire ids, or a 1:1 report would be refused as an
        // unresolvable group.
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-appmls-+15715550104-e3-1"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("c9aa6af5-6b10-4074-80c7-e706349ffe46"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-rekey-+15715550107-1785543609757"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId(null));
        assertNull(MlsMessageId.groupIdFromLegacyWireId(""));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp--1"));
    }

    @Test public void theShapePredicateAgreesWithTheParser() {
        assertTrue(MlsMessageId.isLegacyGroupWireId("mls-grp-" + GID + "-0"));
        assertFalse(MlsMessageId.isLegacyGroupWireId("mls-appmls-+15715550104-e3-1"));
        assertFalse(MlsMessageId.isLegacyGroupWireId(null));
        // The predicate gates a refusal, so it must be exactly as strict as the parser.
        assertFalse(MlsMessageId.isLegacyGroupWireId("mls-grp-" + GID + "-notanumber"));
    }
}
