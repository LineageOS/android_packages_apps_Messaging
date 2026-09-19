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

public final class MlsCpimDocTest {


    @Test
    public void headerIsCaseInsensitiveAndStopsAtTheBlankLine() {
        final String doc = "From: <a>\r\nimdn.Message-ID: 42\r\n\r\nBody-Header: no";
        assertEquals("42", MlsCpimDoc.header(doc, "IMDN.message-id"));
        assertEquals("<a>", MlsCpimDoc.header(doc, "from"));
        assertNull("headers end at the blank line", MlsCpimDoc.header(doc, "Body-Header"));
    }


    @Test
    public void elementReturnsTrimmedTextOfTheFirstOccurrence() {
        final String x = "<imdn><message-id> m1 </message-id><message-id>m2</message-id></imdn>";
        assertEquals("m1", MlsCpimDoc.element(x, "message-id"));
        assertNull(MlsCpimDoc.element(x, "status"));
        assertNull(MlsCpimDoc.element("<a>unclosed", "a"));
    }
}
