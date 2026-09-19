/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.rbm;

import java.util.Collections;
import java.util.List;

/**
 * One rich card: title, description, optional media and suggestion chips. A carousel is a list
 * of them (see {@link RbmBotMessage#cards}).
 */
public final class RbmCard {

    /** Card title (may be null/empty). */
    public final String title;
    /** Card description / body (may be null/empty). */
    public final String description;
    /** Media (image/video/file) URL shown on the card; null if none. */
    public final String mediaUrl;
    /** Media thumbnail URL; null if none. */
    public final String mediaThumbnailUrl;
    /** Media MIME (e.g. {@code image/jpeg}); null if unknown. */
    public final String mediaContentType;
    /** Media accessibility description ({@code contentInfo.altText}); null if absent. */
    public final String mediaAltText;
    /** Orientation hint from {@code layout.cardOrientation}; null if unspecified. */
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
