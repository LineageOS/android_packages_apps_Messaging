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
package com.android.messaging.rcs.rbm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed inbound RBM (RCS Business Messaging) agent message — the neutral model
 * the renderer (Phase 4) consumes. Produced by {@link RbmBotParser} from the raw
 * {@code application/vnd.gsma.botmessage.v1.0+json} body. UI-agnostic, pure Java.
 */
public final class RbmBotMessage {

    public enum Kind {
        /** One or more rich cards (standalone or carousel). */
        CARD,
        /** A plain agent text message. */
        TEXT,
        /** A botmessage shape we couldn't classify (renderer falls back to the
         *  agent's text/plain fallback part). */
        UNKNOWN
    }

    public final Kind kind;
    /** Agent text for {@link Kind#TEXT}; null otherwise. */
    public final String text;
    /** Cards for {@link Kind#CARD} (1 for standalone, N for carousel); empty
     *  otherwise. Never null. */
    public final List<RbmCard> cards;
    /** MESSAGE-level suggestion chips — those attached to a plain {@link Kind#TEXT}
     *  message (GSMA allows chips on a text message, not only on cards). Card
     *  chips live on each {@link RbmCard}. Never null; may be empty. */
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

    /** All suggestions across all cards, in order — convenient for the postback
     *  lookup (Phase 5) and for rendering chips under a single-card message. */
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
