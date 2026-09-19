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
package com.android.messaging.rcs.carrier.sip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Unit tests for {@link IsComposingNotification} — RFC 3994 typing-indicator
 * XML builder/parser. Pure Java; runs on the host JVM.
 */
public class IsComposingNotificationTest {

    // ============================================================
    // Round-trips
    // ============================================================

    @Test
    public void roundTrip_active_withDefaults() throws Exception {
        IsComposingNotification sent = IsComposingNotification.active();
        IsComposingNotification parsed = IsComposingNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(IsComposingNotification.State.ACTIVE, parsed.getState());
        assertEquals("text/plain", parsed.getContentType());
        assertEquals(Integer.valueOf(60), parsed.getRefreshSec());
    }

    @Test
    public void roundTrip_idle_minimalShape() throws Exception {
        IsComposingNotification sent = IsComposingNotification.idle();
        IsComposingNotification parsed = IsComposingNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(IsComposingNotification.State.IDLE, parsed.getState());
        assertNull(parsed.getContentType());
        assertNull(parsed.getLastActive());
        assertNull(parsed.getRefreshSec());
    }

    @Test
    public void roundTrip_active_withLastActive() throws Exception {
        IsComposingNotification sent = IsComposingNotification.active(
                "text/plain", "2026-05-13T10:00:00.000Z", 30);
        IsComposingNotification parsed = IsComposingNotification.parse(sent.toXml());
        assertEquals(sent, parsed);
        assertEquals(IsComposingNotification.State.ACTIVE, parsed.getState());
        assertEquals("text/plain", parsed.getContentType());
        assertEquals("2026-05-13T10:00:00.000Z", parsed.getLastActive());
        assertEquals(Integer.valueOf(30), parsed.getRefreshSec());
    }

    // ============================================================
    // RFC 3994 §6.4 verbatim
    // ============================================================

    /**
     * RFC 3994 §6.4 Figure 3 illustrative iscomposing body with all four
     * optional elements present.
     */
    @Test
    public void parses_rfc3994_section6_example() throws Exception {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\"\r\n"
                + "             xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\r\n"
                + "             xsi:schemaLocation=\"urn:ietf:params:xml:ns:im-composing iscomposing.xsd\">\r\n"
                + "  <state>active</state>\r\n"
                + "  <contenttype>text/plain</contenttype>\r\n"
                + "  <lastactive>2003-01-27T10:43:00Z</lastactive>\r\n"
                + "  <refresh>60</refresh>\r\n"
                + "</isComposing>\r\n";
        IsComposingNotification n = IsComposingNotification.parse(xml);
        assertEquals(IsComposingNotification.State.ACTIVE, n.getState());
        assertEquals("text/plain", n.getContentType());
        assertEquals("2003-01-27T10:43:00Z", n.getLastActive());
        assertEquals(Integer.valueOf(60), n.getRefreshSec());
    }

    // ============================================================
    // State transitions
    // ============================================================

    @Test
    public void state_activeAndIdleAreDistinct() {
        IsComposingNotification a = IsComposingNotification.active();
        IsComposingNotification i = IsComposingNotification.idle();
        assertNotEquals(a, i);
    }

    // ============================================================
    // Emit shape
    // ============================================================

    @Test
    public void toXml_includesNamespacesAndSchemaLocation() {
        IsComposingNotification n = IsComposingNotification.active();
        String xml = n.toXml();
        assertTrue("default namespace present",
                xml.contains("xmlns=\"" + IsComposingNotification.NS_ISCOMPOSING + "\""));
        assertTrue("xsi namespace present",
                xml.contains("xmlns:xsi=\"" + IsComposingNotification.NS_XSI + "\""));
        assertTrue("schemaLocation present",
                xml.contains("xsi:schemaLocation=\""
                        + IsComposingNotification.SCHEMA_LOCATION + "\""));
    }

    @Test
    public void toXml_omitsAbsentOptionalFields() {
        IsComposingNotification n = IsComposingNotification.idle();
        String xml = n.toXml();
        // None of the optional fields should appear when omitted.
        assertTrue("no contenttype", !xml.contains("<contenttype>"));
        assertTrue("no lastactive",  !xml.contains("<lastactive>"));
        assertTrue("no refresh",     !xml.contains("<refresh>"));
    }

    // ============================================================
    // Malformed input
    // ============================================================

    @Test
    public void rejects_missingState() {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\">\r\n"
                + "  <refresh>60</refresh>\r\n"
                + "</isComposing>\r\n";
        try {
            IsComposingNotification.parse(xml);
            fail("missing <state> must throw");
        } catch (ImdnParseException expected) { /* ok */ }
    }

    @Test
    public void rejects_doctype_xxeSafety() {
        String xml =
                "<?xml version=\"1.0\"?>\r\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\r\n"
                + "<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\">\r\n"
                + "  <state>active</state>\r\n"
                + "</isComposing>\r\n";
        try {
            IsComposingNotification.parse(xml);
            fail("DOCTYPE must be rejected");
        } catch (ImdnParseException expected) { /* ok */ }
    }

    // ============================================================
    // Tolerant parsing
    // ============================================================

    @Test
    public void parses_brokenRefresh_returnsNullRefresh() throws Exception {
        // Non-numeric refresh should not abort the whole parse — it's optional.
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<isComposing xmlns=\"urn:ietf:params:xml:ns:im-iscomposing\">\r\n"
                + "  <state>active</state>\r\n"
                + "  <refresh>not-a-number</refresh>\r\n"
                + "</isComposing>\r\n";
        IsComposingNotification n = IsComposingNotification.parse(xml);
        assertEquals(IsComposingNotification.State.ACTIVE, n.getState());
        assertNull(n.getRefreshSec());
    }
}
