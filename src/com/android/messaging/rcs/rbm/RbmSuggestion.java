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

/**
 * A single RBM suggestion — the chips a user can tap under a card or message
 * (GSMA RBM / RCS Business Messaging). Neutral, UI-agnostic model produced by
 * {@link RbmBotParser}.
 *
 * <p>Two families:
 * <ul>
 *   <li><b>Suggested reply</b> ({@code reply}) — taps send a postback message
 *       back to the agent carrying the opaque {@link #postbackData}. This is the
 *       capture-confirmed shape (the invitation's "Make me a tester" / "Decline"
 *       chips).</li>
 *   <li><b>Suggested action</b> ({@code action}) — opens a URL / dials a number /
 *       shows a location / creates a calendar event on tap, and ALSO sends a
 *       postback. Modelled from the GSMA RBM spec (Phase 6). The action-specific
 *       payload ({@link #url}, {@link #phoneNumber}, {@link #latitude}/{@link
 *       #longitude}/{@link #locationQuery}, the {@code cal*} fields) is what the
 *       renderer turns into an Android intent; {@link #fallbackUrl} is the
 *       graceful-degradation target when the device lacks a dialer/maps/calendar
 *       app.</li>
 * </ul>
 */
public final class RbmSuggestion {

    public enum Type {
        /** Suggested reply: tap → send postback to the agent. */
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
    /** Opaque postback data echoed to the agent on tap (e.g.
     *  {@code "accept:<agent>@rbm.goog"}); null if absent. */
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
    /** CREATE_CALENDAR start time (RFC3339); null otherwise. */
    public final String calStart;
    /** CREATE_CALENDAR end time (RFC3339); null otherwise. */
    public final String calEnd;
    /** CREATE_CALENDAR event title; null otherwise. */
    public final String calTitle;
    /** CREATE_CALENDAR event description; null otherwise. */
    public final String calDescription;
    /** Graceful-degradation URL for an action whose native app is unavailable;
     *  null if absent. */
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
