/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.rbm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed inbound business-messaging bot message, produced by {@link RbmBotParser} from the
 * {@code application/vnd.gsma.botmessage.v1.0+json} body. Pure Java.
 */
public final class RbmBotMessage {

    public enum Kind {
        /** One or more rich cards (standalone or carousel). */
        CARD,
        /** A plain agent text message. */
        TEXT,
        /** A shape that could not be classified; the renderer uses the text/plain fallback part. */
        UNKNOWN
    }

    public final Kind kind;
    /** Agent text for {@link Kind#TEXT}; null otherwise. */
    public final String text;
    /**
     * Cards for {@link Kind#CARD} (one, or several for a carousel); empty otherwise. Never null.
     */
    public final List<RbmCard> cards;
    /**
     * Message-level suggestion chips, attached to a plain text message; card chips live on each
     * {@link RbmCard}. Never null.
     */
    public final List<RbmSuggestion> suggestions;

    public RbmBotMessage(Kind kind, String text, List<RbmCard> cards) {
        this(kind, text, cards, null);
    }

    public RbmBotMessage(Kind kind, String text, List<RbmCard> cards,
                         List<RbmSuggestion> suggestions) {
        this.kind = kind;
        this.text = text;
        this.cards = cards == null
                ? Collections.<RbmCard>emptyList()
                : Collections.unmodifiableList(cards);
        this.suggestions = suggestions == null
                ? Collections.<RbmSuggestion>emptyList()
                : Collections.unmodifiableList(suggestions);
    }

    public static RbmBotMessage text(String text) {
        return new RbmBotMessage(Kind.TEXT, text, null, null);
    }

    public static RbmBotMessage text(String text, List<RbmSuggestion> suggestions) {
        return new RbmBotMessage(Kind.TEXT, text, null, suggestions);
    }

    public static RbmBotMessage unknown() {
        return new RbmBotMessage(Kind.UNKNOWN, null, null);
    }

    public boolean isCarousel() {
        return kind == Kind.CARD && cards.size() > 1;
    }

    /** All suggestions, message-level first, then each card's in order. */
    public List<RbmSuggestion> allSuggestions() {
        List<RbmSuggestion> out = new ArrayList<>();
        out.addAll(suggestions);
        for (RbmCard c : cards) out.addAll(c.suggestions);
        return out;
    }

    @Override
    public String toString() {
        return "RbmBotMessage{" + kind
                + (text != null ? " text='" + text + "'" : "")
                + (cards.isEmpty() ? "" : " cards=" + cards) + "}";
    }
}
