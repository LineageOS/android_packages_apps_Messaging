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
package com.android.messaging.rcs.sendstatus;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.RcsSendStatus;

import org.junit.Test;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RcsSendStatus} restates eight numbers whose owners are all Android-coupled. This reads the
 * owners and fails if any of them has moved.
 *
 * <p>The duplication is not laziness: {@code MessageData} pulls in {@code ContentValues},
 * {@code Parcel} and {@code Uri}, {@code RcsConstants} is app-side, and
 * {@code IRcsProviderCallback} is a generated AIDL stub — importing any of them would put
 * {@code RcsSendStatus} out of reach of a host test, which is the whole point of the class. What the
 * duplication buys has to be paid for by this test: a renumber elsewhere must fail HERE, loudly,
 * rather than silently make {@code bugleStatusForMeasuredHandoff} write a status that means
 * something else.
 *
 * <p>Each lookup asserts EXACTLY ONE declaration matched. Zero would make this guard green by
 * matching nothing, which is the failure mode a mirror test exists to avoid.
 */
public class RcsSendStatusMirrorTest {

    private static final String MESSAGE_DATA =
            "src/com/android/messaging/datamodel/data/MessageData.java";
    private static final String RCS_CONSTANTS = "src/com/android/messaging/rcs/RcsConstants.java";
    private static final String CALLBACK_AIDL =
            "aidl/src/aidl/org/lineageos/rcs/provider/IRcsProviderCallback.aidl";

    /** The value of the single {@code <name> = <digits>;} declaration in {@code rel}. */
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

    /**
     * {@code rcs_status} is stored VERBATIM from {@code IRcsProviderCallback.STATUS_*}, so the two
     * values the app-owned path writes have to be the provider's own, not a parallel vocabulary.
     */
    @Test
    public void rcsStatusesMirrorTheCallbackContract() throws IOException {
        assertEquals(declaredValue(CALLBACK_AIDL, "STATUS_SENT"), RcsSendStatus.RCS_STATUS_SENT);
        assertEquals(declaredValue(CALLBACK_AIDL, "STATUS_FAILED"), RcsSendStatus.RCS_STATUS_FAILED);
    }
}
