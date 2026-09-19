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

import java.util.Collections;
import java.util.List;

/**
 * One RBM rich card — title + description + optional media + suggestion chips.
 * A standalone card is a single {@link RbmCard}; a carousel is a list of them
 * (see {@link RbmBotMessage#cards}). Neutral model from {@link RbmBotParser}.
 */
public final class RbmCard {

    /** Card title (may be null/empty). */
    public final String title;
    /** Card description / body (may be null/empty). */
    public final String description;
    /** Media (image/video/file) URL shown on the card; null if none. To be
     *  capture-confirmed in Phase 6 — the test agent does not send media yet. */
    public final String mediaUrl;
    /** Media thumbnail URL; null if none. */
    public final String mediaThumbnailUrl;
    /** Media MIME (e.g. {@code image/jpeg}); null if unknown. */
    public final String mediaContentType;
    /** Media accessibility description ({@code contentInfo.altText}); null if absent. */
    public final String mediaAltText;
    /** Card orientation hint from {@code layout.cardOrientation}
     *  ({@code VERTICAL}/{@code HORIZONTAL}); null if unspecified. */
    public final String orientation;
    /** Suggestion chips under the card (never null; may be empty). */
    public final List<RbmSuggestion> suggestions;

    public RbmCard(String title, String description, String mediaUrl,
                   String mediaThumbnailUrl, String mediaContentType, String mediaAltText,
                   String orientation, List<RbmSuggestion> suggestions) {
        this.title = title;
        this.description = description;
        this.mediaUrl = mediaUrl;
        this.mediaThumbnailUrl = mediaThumbnailUrl;
        this.mediaContentType = mediaContentType;
        this.mediaAltText = mediaAltText;
        this.orientation = orientation;
        this.suggestions = suggestions == null
                ? Collections.<RbmSuggestion>emptyList()
                : Collections.unmodifiableList(suggestions);
    }

    public boolean hasMedia() {
        return mediaUrl != null && !mediaUrl.isEmpty();
    }

    @Override
    public String toString() {
        return "RbmCard{title='" + title + "', desc='" + description + "'"
                + (hasMedia() ? ", media=" + mediaContentType : "")
                + ", suggestions=" + suggestions + "}";
    }
}
