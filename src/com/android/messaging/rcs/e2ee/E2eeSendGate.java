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
package com.android.messaging.rcs.e2ee;

import org.lineageos.rcs.provider.RcsE2eeInfo;
import com.android.messaging.Factory;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.util.LogUtil;

/**
 * The send-path front door to {@link E2eeSchemeGate}: a process singleton that wires the
 * gate to its live seams and resolves the outbound E2EE scheme for a conversation. The
 * send path ({@code InsertNewMessageAction}) calls {@link #resolveForSend} on the
 * DataModel action thread, then routes:
 *
 * <ul>
 *   <li>{@link RcsE2eeScheme#ETOUFFEE} &rarr; the plaintext {@code sendMessage} path (the
 *       <i>provider</i> encrypts transparently), tagging the row so the padlock lights;</li>
 *   <li>{@code null} &rarr; plaintext RCS.</li>
 * </ul>
 *
 * <p>Seams:
 * <ul>
 *   <li><b>Etouffee availability</b> — the provider's cached {@link RcsE2eeInfo}
 *       ({@code available && schemeId == google.etouffee}).</li>
 *   <li><b>Bits store</b> — {@link ConversationBitsStore} (durable, latching).</li>
 * </ul>
 */
public final class E2eeSendGate {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static volatile E2eeSendGate sInstance;

    private final E2eeSchemeGate mGate;

    public static E2eeSendGate get() {
        if (sInstance == null) {
            synchronized (E2eeSendGate.class) {
                if (sInstance == null) {
                    sInstance = new E2eeSendGate();
                }
            }
        }
        return sInstance;
    }

    private E2eeSendGate() {
        mGate = new E2eeSchemeGate(
                new ProviderEtouffeeAvailability(),
                new ConversationBitsStore());
    }

    /**
     * Resolve + persist the outbound E2EE scheme for a send. Returns
     * {@link RcsE2eeScheme#ETOUFFEE} or {@code null} (plaintext). Never throws — any
     * failure degrades to plaintext.
     *
     * <p>{@code recipientE164} and {@code isGroup} describe the send being asked about and
     * are part of this method's stable contract with the send path, but this build does not
     * consult them: the provider plane is provisioned <i>per subscription</i>, so the answer
     * depends only on {@code subId} and on what is already latched for {@code conversationId}.
     */
    public String resolveForSend(final String conversationId, final int subId,
            final String recipientE164, final boolean isGroup) {
        try {
            return mGate.selectScheme(conversationId, subId);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "E2eeSendGate.resolveForSend failed; sending plaintext", t);
            return RcsE2eeScheme.NONE;
        }
    }

    // ---- seams ----

    /** Etouffee availability from the provider's cached {@link RcsE2eeInfo}. */
    private static final class ProviderEtouffeeAvailability
            implements E2eeSchemeGate.EtouffeeAvailability {
        @Override
        public boolean isEtouffeeAvailable(final int subId) {
            try {
                final ProviderTransport pt =
                        ProviderTransport.getInstance(Factory.get().getApplicationContext());
                final RcsE2eeInfo info = pt.peekE2eeInfo();
                return info != null && info.available
                        && RcsE2eeScheme.ETOUFFEE.equals(info.schemeId);
            } catch (final Throwable t) {
                return false;
            }
        }
    }
}
