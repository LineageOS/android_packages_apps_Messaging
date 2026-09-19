/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.rbm;

/**
 * One suggestion chip under a card or message. A suggested reply sends a postback carrying
 * {@link #postbackData}. A suggested action (open URL, dial, view location, create calendar event,
 * share location) also sends a postback, and its payload fields become an Android intent;
 * {@link #fallbackUrl} is used when no app handles that intent.
 */
public final class RbmSuggestion {

    public enum Type {
        /** Suggested reply: tap sends a postback to the agent. */
        REPLY,
        /** Open-URL action. */
        OPEN_URL,
        /** Dial-phone action. */
        DIAL,
        /** View-location action (lat/long or query). */
        VIEW_LOCATION,
        /** Create-calendar-event action. */
        CREATE_CALENDAR,
        /** Share-location (request the user's location) action. */
        SHARE_LOCATION,
        /** A suggestion shape we don't model yet. */
        UNKNOWN
    }

    public final Type type;
    /** The chip label shown to the user. */
    public final String displayText;
    /** Opaque postback data echoed to the agent on tap; null if absent. */
    public final String postbackData;
    /** OPEN_URL target; null otherwise. */
    public final String url;
    /** DIAL target (E.164/dialable); null otherwise. */
    public final String phoneNumber;
    /** VIEW_LOCATION latitude; null if the action used a {@link #locationQuery}. */
    public final Double latitude;
    /** VIEW_LOCATION longitude; null if the action used a {@link #locationQuery}. */
    public final Double longitude;
    /** VIEW_LOCATION pin label; null if absent. */
    public final String locationLabel;
    /** VIEW_LOCATION free-text map query (alternative to lat/long); null if absent. */
    public final String locationQuery;
    /** CREATE_CALENDAR start time (RFC 3339); null otherwise. */
    public final String calStart;
    /** CREATE_CALENDAR end time (RFC 3339); null otherwise. */
    public final String calEnd;
    /** CREATE_CALENDAR event title; null otherwise. */
    public final String calTitle;
    /** CREATE_CALENDAR event description; null otherwise. */
    public final String calDescription;
    /** URL used when no app handles the action; null if absent. */
    public final String fallbackUrl;

    private RbmSuggestion(Builder b) {
        this.type = b.type;
        this.displayText = b.displayText;
        this.postbackData = b.postbackData;
        this.url = b.url;
        this.phoneNumber = b.phoneNumber;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
        this.locationLabel = b.locationLabel;
        this.locationQuery = b.locationQuery;
        this.calStart = b.calStart;
        this.calEnd = b.calEnd;
        this.calTitle = b.calTitle;
        this.calDescription = b.calDescription;
        this.fallbackUrl = b.fallbackUrl;
    }

    /** Convenience for the common reply chip. */
    public static RbmSuggestion reply(String displayText, String postbackData) {
        return new Builder(Type.REPLY).displayText(displayText)
                .postbackData(postbackData).build();
    }

    /** True for a suggested action (anything tappable beyond a plain reply). */
    public boolean isAction() {
        return type != Type.REPLY && type != Type.UNKNOWN;
    }

    public static final class Builder {
        private final Type type;
        private String displayText;
        private String postbackData;
        private String url;
        private String phoneNumber;
        private Double latitude;
        private Double longitude;
        private String locationLabel;
        private String locationQuery;
        private String calStart;
        private String calEnd;
        private String calTitle;
        private String calDescription;
        private String fallbackUrl;

        public Builder(Type type) { this.type = type; }

        public Builder displayText(String v) { this.displayText = v; return this; }
        public Builder postbackData(String v) { this.postbackData = v; return this; }
        public Builder url(String v) { this.url = v; return this; }
        public Builder phoneNumber(String v) { this.phoneNumber = v; return this; }
        public Builder latitude(Double v) { this.latitude = v; return this; }
        public Builder longitude(Double v) { this.longitude = v; return this; }
        public Builder locationLabel(String v) { this.locationLabel = v; return this; }
        public Builder locationQuery(String v) { this.locationQuery = v; return this; }
        public Builder calStart(String v) { this.calStart = v; return this; }
        public Builder calEnd(String v) { this.calEnd = v; return this; }
        public Builder calTitle(String v) { this.calTitle = v; return this; }
        public Builder calDescription(String v) { this.calDescription = v; return this; }
        public Builder fallbackUrl(String v) { this.fallbackUrl = v; return this; }

        public RbmSuggestion build() { return new RbmSuggestion(this); }
    }

    @Override
    public String toString() {
        return "RbmSuggestion{" + type + " '" + displayText + "'"
                + (postbackData != null ? " postback=" + postbackData : "")
                + (url != null ? " url=" + url : "")
                + (phoneNumber != null ? " tel=" + phoneNumber : "")
                + (locationQuery != null ? " q=" + locationQuery : "")
                + (latitude != null ? " @" + latitude + "," + longitude : "")
                + (calTitle != null ? " cal=" + calTitle : "")
                + (fallbackUrl != null ? " fallback=" + fallbackUrl : "") + "}";
    }
}
