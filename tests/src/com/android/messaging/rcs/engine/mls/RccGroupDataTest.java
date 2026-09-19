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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** RCC.16 §7.13 — Encrypted Group Metadata over CPM/SIP. */
public class RccGroupDataTest {

    @Test
    public void setBuildsATargetedGroupDataRequest() {
        final String xml = RccGroupData.buildGroupData(
                RccGroupData.TARGET_ENCRYPTED_SUBJECT, "YmFzZTY0", RccGroupData.ACTION_SET);
        assertTrue(xml.contains("<target-type>encrypted-subject</target-type>"));
        assertTrue(xml.contains("<action-type>set</action-type>"));
        assertTrue(xml.contains("<encrypted-subject>YmFzZTY0</encrypted-subject>"));
    }

    /**
     * Same element shape, different meaning: the subject carries Base64 ciphertext INLINE, the icon
     * carries a URL to ciphertext on the uploader's Content Server. Feeding an icon URL to a Base64
     * decoder yields plausible-looking garbage rather than an error.
     */
    @Test
    public void theIconCarriesAUrlWhereTheSubjectCarriesCiphertext() {
        final String icon = RccGroupData.buildGroupData(RccGroupData.TARGET_ENCRYPTED_ICON,
                "https://cs.example/blob/1", RccGroupData.ACTION_SET);
        assertTrue(icon.contains("<encrypted-icon>https://cs.example/blob/1</encrypted-icon>"));
        assertFalse("the icon element must not be the subject element",
                icon.contains("<encrypted-subject>"));
    }

    /** §9.7.1.6/.7 — a delete carries no {@code <data>} at all. */
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
        // The child elements must not bleed into the value.
        assertFalse(p.encryptedSubject.value.contains("tel:"));
        assertFalse(p.encryptedSubject.value.contains("2026"));
    }

    /**
     * §7.13.2 — DELETION IS AN EMPTY STRING, not an absent element. Collapsing the two makes a
     * delete silently do nothing, which is the whole reason "absent" comes back as null.
     */
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

        // ...and a populated one is not a deletion.
        assertFalse(RccGroupData.parseConferenceInfo(NOTIFY).encryptedSubject.isDeletion());
    }

    /** Namespaces and prefixes vary by deployment; the lookup is by LOCAL name for that reason. */
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

    /** A NOTIFY we cannot read must not be acted on — an empty Parsed would read as "unchanged". */
    @Test
    public void anUnparseableBodyReturnsNullRatherThanAnEmptyResult() {
        assertNull(RccGroupData.parseConferenceInfo("<conference-info><unclosed>"));
        assertNull(RccGroupData.parseConferenceInfo(""));
        assertNull(RccGroupData.parseConferenceInfo(null));
    }

    /**
     * XXE. This body arrives from the network, so an external entity here would be arbitrary local
     * file disclosure triggered by a NOTIFY nobody asked for.
     */
    @Test
    public void externalEntitiesAreNotResolved() {
        final String attack =
                "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE r [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<conference-info><conference-description>"
                + "<encrypted-subject-description>&xxe;</encrypted-subject-description>"
                + "</conference-description></conference-info>";
        final RccGroupData.Parsed p = RccGroupData.parseConferenceInfo(attack);
        // Either the DOCTYPE is rejected outright (null) or the entity is not expanded. Both are
        // acceptable; leaking file content is not.
        if (p != null && p.encryptedSubject != null) {
            assertFalse(p.encryptedSubject.value.contains("root:"));
            assertFalse(p.encryptedSubject.value.contains("/bin/"));
        }
    }
}
