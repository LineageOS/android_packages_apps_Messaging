/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.RcsSendStatus;

import org.junit.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RcsSendStatus} restates constants from Android-coupled owners to stay host-testable; this
 * reads each owner's source and fails when a value has moved.
 */
public class RcsSendStatusMirrorTest {

    private static final String MESSAGE_DATA =
            "src/com/android/messaging/datamodel/data/MessageData.java";
    private static final String RCS_CONSTANTS = "src/com/android/messaging/rcs/RcsConstants.java";
    private static final String CALLBACK_AIDL =
            "aidl/src/aidl/org/lineageos/rcs/provider/IRcsProviderCallback.aidl";

    /**
     * The value of the single {@code <name> = <digits>;} declaration in {@code rel}; asserts one.
     */
    private static int declaredValue(final String rel, final String name) throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(rel));
        final Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*;")
                .matcher(src);
        int found = 0;
        int value = -1;
        while (m.find()) {
            found++;
            value = Integer.parseInt(m.group(1));
        }
        assertEquals("expected exactly one declaration of " + name + " in " + rel
                + " — " + found + " matched, so this mirror is reading the wrong thing",
                1, found);
        return value;
    }

    @Test
    public void bugleStatusesMirrorMessageData() throws IOException {
        assertEquals(declaredValue(MESSAGE_DATA, "BUGLE_STATUS_OUTGOING_COMPLETE"),
                RcsSendStatus.BUGLE_STATUS_OUTGOING_COMPLETE);
        assertEquals(declaredValue(MESSAGE_DATA, "BUGLE_STATUS_OUTGOING_YET_TO_SEND"),
                RcsSendStatus.BUGLE_STATUS_OUTGOING_YET_TO_SEND);
        assertEquals(declaredValue(MESSAGE_DATA, "BUGLE_STATUS_OUTGOING_AWAITING_RETRY"),
                RcsSendStatus.BUGLE_STATUS_OUTGOING_AWAITING_RETRY);
        assertEquals(declaredValue(MESSAGE_DATA, "BUGLE_STATUS_OUTGOING_FAILED"),
                RcsSendStatus.BUGLE_STATUS_OUTGOING_FAILED);
    }

    @Test
    public void rcsStatusNoneMirrorsRcsConstants() throws IOException {
        assertEquals(declaredValue(RCS_CONSTANTS, "RCS_STATUS_NONE"),
                RcsSendStatus.RCS_STATUS_NONE);
    }

    /** {@code rcs_status} stores {@code IRcsProviderCallback.STATUS_*} values verbatim. */
    @Test
    public void rcsStatusesMirrorTheCallbackContract() throws IOException {
        assertEquals(declaredValue(CALLBACK_AIDL, "STATUS_SENT"), RcsSendStatus.RCS_STATUS_SENT);
        assertEquals(declaredValue(CALLBACK_AIDL, "STATUS_FAILED"),
                RcsSendStatus.RCS_STATUS_FAILED);
    }
}
