/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.text.TextUtils;

import com.android.messaging.rcs.rbm.RbmBotMessage;
import com.android.messaging.rcs.rbm.RbmBotParser;
import com.android.messaging.rcs.rbm.RbmCard;

import com.android.messaging.Factory;
import com.android.messaging.R;

/**
 * One-line summary of a business-messaging bot message for the conversation-list snippet and the
 * notification, so neither shows raw JSON. The stored body is unchanged.
 */
public final class RbmSummary {
    private RbmSummary() {}

    /**
     * Parses raw bot-message JSON and summarises it; falls back to {@code fallback}, then a generic
     * label.
     */
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

    /**
     * Summarises an already-parsed message; null or unknown falls back as {@link #fromJson} does.
     */
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
