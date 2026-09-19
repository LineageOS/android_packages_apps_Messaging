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

public final class MlsConversationKeyTest {


    @Test
    public void aGroupIdWinsOverAPeerAndNothingGivesNull() {
        assertEquals("g:grp", MlsConversationKey.canonicalKey("grp", "+15550000000"));
        assertEquals("p:+15550000000", MlsConversationKey.canonicalKey(null, "+15550000000"));
        assertEquals("p:+15550000000", MlsConversationKey.canonicalKey("", "+15550000000"));
        assertNull(MlsConversationKey.canonicalKey(null, ""));
    }


    @Test
    public void splitInvertsCanonicalKeyAndRefusesAnythingElse() {
        assertArrayEquals(new String[] {"grp", null}, MlsConversationKey.splitCanonicalKey(
                MlsConversationKey.canonicalKey("grp", "+1")));
        assertArrayEquals(new String[] {null, "+1"}, MlsConversationKey.splitCanonicalKey(
                MlsConversationKey.canonicalKey(null, "+1")));
        assertNull(MlsConversationKey.splitCanonicalKey("x:1"));
        assertNull(MlsConversationKey.splitCanonicalKey(null));
    }

    @Test
    public void forLogMasksTheNumberInAKeyAndLeavesEverythingElse() {
        assertEquals("p:***0123", MlsConversationKey.forLog("p:+15550100123"));
        assertEquals("p:***0123", MlsConversationKey.forLog("p:15550100123"));
        assertEquals("***0123", MlsConversationKey.forLog("+15550100123"));
        assertEquals("g:5B8905CD-DEF5-414C-BC3F-5343069C256D",
                MlsConversationKey.forLog("g:5B8905CD-DEF5-414C-BC3F-5343069C256D"));
        assertEquals("g:0144736aa9654c588f3ddeed54aac940",
                MlsConversationKey.forLog("g:0144736aa9654c588f3ddeed54aac940"));
        assertEquals("8", MlsConversationKey.forLog("8"));
        assertEquals("", MlsConversationKey.forLog(""));
        assertEquals("null", MlsConversationKey.forLog(null));
        assertEquals("p:", MlsConversationKey.forLog("p:"));
        assertEquals("a+b", MlsConversationKey.forLog("a+b"));
        assertEquals("walk from 'p:***0447' of 29",
                MlsConversationKey.forLog("walk from 'p:+15550100447' of 29"));
    }

    @Test
    public void forLogMasksTheNumberInAControlIdAndLeavesOtherIds() {
        assertEquals("mls-endmls-***4673-1790498010829",
                MlsMessageId.forLog("mls-endmls-+15550104673-1790498010829"));
        assertEquals("mls-revive-***4673-1790498154894",
                MlsMessageId.forLog("mls-revive-+15550104673-1790498154894"));
        assertEquals("mls-keyupdate-p:***4673-1790498154894",
                MlsMessageId.forLog("mls-keyupdate-p:+15550104673-1790498154894"));
        for (final String id : new String[] {
                "mls-grp-be713f6391fc4f34b298bf6490222b47-1790497138942",
                "mls-add-member-be713f6391fc4f34b298bf6490222b47-1790497046132",
                "eb6a9622-098a-44be-b404-52d27dbe85b8", "V5z1u79NJsJWfUSQ_io0kVCa"}) {
            assertEquals(id, MlsMessageId.forLog(id));
        }
    }
}
