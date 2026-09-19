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
package com.android.messaging.rcs;

import android.text.TextUtils;

import com.android.messaging.rcs.rbm.RbmBotMessage;
import com.android.messaging.rcs.rbm.RbmBotParser;
import com.android.messaging.rcs.rbm.RbmCard;

import com.android.messaging.Factory;
import com.android.messaging.R;

/**
 * RBM: turn a parsed bot message into a ONE-LINE human
 * summary for the conversation-list snippet + the notification preview, so those
 * surfaces never show the raw {@code application/vnd.gsma.botmessage.v1.0+json}
 * body. The in-thread renderer still parses the raw JSON (the stored body is
 * unchanged); only the snippet/notification text is derived through here.
 */
public final class RbmSummary {
    private RbmSummary() {}

    /** Parse raw botmessage JSON and summarize; falls back to {@code fallback}
     *  (the agent's text/plain part) then a generic "Rich message". */
    public static String fromJson(final String jsonBody, final String fallback) {
        RbmBotMessage parsed = null;
        if (!TextUtils.isEmpty(jsonBody)) {
            try {
                parsed = RbmBotParser.parse(jsonBody);
            } catch (final RbmBotParser.RbmParseException ignored) {
                // fall through to fallback / generic
            }
        }
        return of(parsed, fallback);
    }

    /** Summarize an already-parsed message. Null/UNKNOWN -> fallback or generic. */
    public static String of(final RbmBotMessage m, final String fallback) {
        if (m != null) {
            switch (m.kind) {
                case TEXT:
                    if (!TextUtils.isEmpty(m.text)) {
                        return m.text;
                    }
                    break;
                case CARD:
                    if (!m.cards.isEmpty()) {
                        final RbmCard c = m.cards.get(0);
                        if (!TextUtils.isEmpty(c.title)) {
                            return c.title;
                        }
                        if (!TextUtils.isEmpty(c.description)) {
                            return c.description;
                        }
                    }
                    break;
                default:
                    break;
            }
        }
        if (!TextUtils.isEmpty(fallback)) {
            return fallback;
        }
        return Factory.get().getApplicationContext().getString(R.string.rbm_summary_rich_message);
    }
}
