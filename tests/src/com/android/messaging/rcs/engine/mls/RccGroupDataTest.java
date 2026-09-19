/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Encrypted group metadata over CPM and SIP (RCC.16 §7.13). */
public class RccGroupDataTest {

    @Test
    public void setBuildsATargetedGroupDataRequest() {
        final String xml = RccGroupData.buildGroupData(
                RccGroupData.TARGET_ENCRYPTED_SUBJECT, "YmFzZTY0", RccGroupData.ACTION_SET);
        assertTrue(xml.contains("<target-type>encrypted-subject</target-type>"));
        assertTrue(xml.contains("<action-type>set</action-type>"));
        assertTrue(xml.contains("<encrypted-subject>YmFzZTY0</encrypted-subject>"));
    }

    /** The subject carries Base64 ciphertext inline; the icon carries a URL to its ciphertext. */
    @Test
    public void theIconCarriesAUrlWhereTheSubjectCarriesCiphertext() {
        final String icon = RccGroupData.buildGroupData(RccGroupData.TARGET_ENCRYPTED_ICON,
                "https://cs.example/blob/1", RccGroupData.ACTION_SET);
        assertTrue(icon.contains("<encrypted-icon>https://cs.example/blob/1</encrypted-icon>"));
        assertFalse("the icon element must not be the subject element",
                icon.contains("<encrypted-subject>"));
    }

    /** A delete carries no {@code <data>} (RCC.16 §9.7.1.6, §9.7.1.7). */
    @Test
    public void deleteCarriesNoData() {
        final String xml = RccGroupData.buildGroupData(
                RccGroupData.TARGET_ENCRYPTED_ICON, "ignored", RccGroupData.ACTION_DELETE);
        assertTrue(xml.contains("<action-type>delete</action-type>"));
        assertFalse(xml.contains("<data>"));
        assertFalse(xml.contains("ignored"));
    }

    @Test
    public void valuesAreXmlEscaped() {
        final String xml = RccGroupData.buildGroupData(
                RccGroupData.TARGET_ENCRYPTED_SUBJECT, "a<b>&\"c\"", RccGroupData.ACTION_SET);
        assertTrue(xml.contains("a&lt;b&gt;&amp;&quot;c&quot;"));
    }

    private static final String NOTIFY =
            "<?xml version=\"1.0\"?>"
            + "<conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\">"
            + "<conference-description>"
            + "<encrypted-subject-description>Y2lwaGVy"
            + "<participant>tel:+15551234567</participant>"
            + "<timestamp>2026-08-06T12:00:00Z</timestamp>"
            + "</encrypted-subject-description>"
            + "<encrypted-icon-description>https://cs.example/i</encrypted-icon-description>"
            + "<mls-group-info>Z3JvdXBpbmZv</mls-group-info>"
            + "</conference-description></conference-info>";

    @Test
    public void parsesBothDescriptionsAndTheMlsGroupInfo() {
        final RccGroupData.Parsed p = RccGroupData.parseConferenceInfo(NOTIFY);
        assertNotNull(p);
        assertEquals("Y2lwaGVy", p.encryptedSubject.value);
        assertEquals("tel:+15551234567", p.encryptedSubject.participant);
        assertEquals("2026-08-06T12:00:00Z", p.encryptedSubject.timestamp);
        assertEquals("https://cs.example/i", p.encryptedIcon.value);
        assertEquals("Z3JvdXBpbmZv", p.mlsGroupInfoBase64);
        // Child elements do not bleed into the value.
        assertFalse(p.encryptedSubject.value.contains("tel:"));
        assertFalse(p.encryptedSubject.value.contains("2026"));
    }

    /** Deletion is an empty string, not an absent element (RCC.16 §7.13.2); absent is null. */
    @Test
    public void anEmptyDescriptionIsADeletionAndAnAbsentOneIsNotADescriptionAtAll() {
        final RccGroupData.Parsed deleted = RccGroupData.parseConferenceInfo(
                "<conference-info><conference-description>"
                + "<encrypted-subject-description></encrypted-subject-description>"
                + "</conference-description></conference-info>");
        assertNotNull(deleted);
        assertNotNull("present-but-empty must still be a Description", deleted.encryptedSubject);
        assertTrue("an empty value IS the delete signal", deleted.encryptedSubject.isDeletion());
        assertNull("absent is a different fact from deleted", deleted.encryptedIcon);

        assertFalse(RccGroupData.parseConferenceInfo(NOTIFY).encryptedSubject.isDeletion());
    }

    /** Namespaces and prefixes vary by deployment, so the lookup is by local name. */
    @Test
    public void aPrefixedBodyFromADifferentDeploymentStillParses() {
        final RccGroupData.Parsed p = RccGroupData.parseConferenceInfo(
                "<ci:conference-info xmlns:ci=\"urn:ietf:params:xml:ns:conference-info\">"
                + "<ci:conference-description>"
                + "<ci:encrypted-icon-description>u</ci:encrypted-icon-description>"
                + "</ci:conference-description></ci:conference-info>");
        assertNotNull(p);
        assertEquals("u", p.encryptedIcon.value);
    }

    /** An unreadable NOTIFY is null; an empty result would read as "unchanged". */
    @Test
    public void anUnparseableBodyReturnsNullRatherThanAnEmptyResult() {
        assertNull(RccGroupData.parseConferenceInfo("<conference-info><unclosed>"));
        assertNull(RccGroupData.parseConferenceInfo(""));
        assertNull(RccGroupData.parseConferenceInfo(null));
    }

    /** The body comes from the network, so external entities must not be resolved. */
    @Test
    public void externalEntitiesAreNotResolved() {
        final String attack =
                "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE r [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<conference-info><conference-description>"
                + "<encrypted-subject-description>&xxe;</encrypted-subject-description>"
                + "</conference-description></conference-info>";
        final RccGroupData.Parsed p = RccGroupData.parseConferenceInfo(attack);
        // Rejecting the DOCTYPE (null) and not expanding the entity are both acceptable.
        if (p != null && p.encryptedSubject != null) {
            assertFalse(p.encryptedSubject.value.contains("root:"));
            assertFalse(p.encryptedSubject.value.contains("/bin/"));
        }
    }
}
