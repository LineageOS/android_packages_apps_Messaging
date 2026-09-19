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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Recovering the RCS group id from a legacy group wire id.
 *
 * <p>Why it matters: an inbound MLS IMDN carries no group (§12.9), so a negative receipt is
 * correlated message-id → conversation through the message store. A legacy group id was never
 * written to {@code rcs_message_id}, so that lookup misses on exactly the messages a group FTD is
 * about — and the remedy then lands on the 1:1 with the reporter, repairing the wrong conversation.
 */
public class MlsLegacyGroupWireIdTest {

    /** The real shape, from a live interop group. */
    private static final String GID = "5B8905CD-DEF5-414C-BC3F-5343069C256D";

    @Test public void theGroupIdComesBackOutOfARealWireId() {
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-0"));
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-2"));
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-1234"));
    }

    @Test public void aGroupIdFullOfDASHESStillSurvives() {
        // The whole risk in this parser: a UUID group id contains four dashes, so splitting on the
        // FIRST dash — or on a fixed count — truncates it and yields a group that matches nothing.
        // That is worse than returning null, because it looks like a successful resolution.
        assertTrue(GID.contains("-"));
        assertEquals(GID, MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-7"));
    }

    @Test public void aNonNumericTailIsNotAGeneration() {
        // Without the digit check, ANY id starting with the prefix would be split at its last dash,
        // and a group id whose own tail happened to look like a segment would be silently truncated.
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-abc"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-1a"));
        assertNull(MlsMessageId.groupIdFromLegacyWireId("mls-grp-" + GID + "-"));
    }

    @Test public void otherIdShapesAreNotGroupIds() {
        // A 1:1 legacy id, an app UUID, and the resend register's ids must all read as "not a group
        // wire id" — otherwise a 1:1 report would be refused as an unresolvable group.
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
        // The predicate gates a REFUSAL, so a false positive here would drop legitimate 1:1
        // reports — it must be exactly as strict as the parser, not merely prefix-shaped.
        assertFalse(MlsMessageId.isLegacyGroupWireId("mls-grp-" + GID + "-notanumber"));
    }
}
