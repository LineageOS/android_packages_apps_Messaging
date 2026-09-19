/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.rbm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses a raw {@code application/vnd.gsma.botmessage.v1.0+json} body, which the provider passes
 * through unparsed, into {@link RbmBotMessage}. Accepts {@code generalPurposeCard},
 * {@code richCard} (standalone and carousel) and {@code text}, with or without the
 * {@code {"message":{...}}} wrapper. Malformed JSON throws {@link RbmParseException} so the caller
 * can use the text/plain fallback; a valid but unrecognised shape yields
 * {@link RbmBotMessage.Kind#UNKNOWN}.
 */
public final class RbmBotParser {

    /** Thrown only on malformed JSON (not on an unrecognized-but-valid shape). */
    public static final class RbmParseException extends Exception {
        public RbmParseException(String msg, Throwable cause) { super(msg, cause); }
    }

    private RbmBotParser() {}

    public static RbmBotMessage parse(String jsonBody) throws RbmParseException {
        final Map<String, Object> root;
        try {
            root = Json.parseObject(jsonBody);
        } catch (Json.JsonException e) {
            throw new RbmParseException("botmessage JSON parse failed", e);
        }
        // Unwrap the optional {"message": {...}} envelope.
        Map<String, Object> msg = asMap(root.get("message"));
        if (msg == null) msg = root;

        // A text message may carry its own suggestion chips.
        String text = asString(msg.get("text"));
        if (text != null && !text.isEmpty()) {
            return RbmBotMessage.text(text, parseSuggestions(asList(msg.get("suggestions"))));
        }

        // generalPurposeCard.
        Map<String, Object> gpc = asMap(msg.get("generalPurposeCard"));
        if (gpc != null) {
            String orientation = asString(path(gpc, "layout", "cardOrientation"));
            RbmCard card = parseCardContent(asMap(gpc.get("content")), orientation);
            if (card != null) {
                List<RbmCard> cards = new ArrayList<>();
                cards.add(card);
                return new RbmBotMessage(RbmBotMessage.Kind.CARD, null, cards);
            }
        }

        // richCard: standalone and carousel.
        Map<String, Object> rich = asMap(msg.get("richCard"));
        if (rich != null) {
            Map<String, Object> standalone = asMap(rich.get("standaloneCard"));
            if (standalone != null) {
                // cardOrientation is a sibling of cardContent in standaloneCard.
                RbmCard card = parseCardContent(asMap(standalone.get("cardContent")),
                        asString(standalone.get("cardOrientation")));
                if (card != null) {
                    List<RbmCard> cards = new ArrayList<>();
                    cards.add(card);
                    return new RbmBotMessage(RbmBotMessage.Kind.CARD, null, cards);
                }
            }
            Map<String, Object> carousel = asMap(rich.get("carouselCard"));
            if (carousel != null) {
                List<Object> contents = asList(carousel.get("cardContents"));
                if (contents != null && !contents.isEmpty()) {
                    List<RbmCard> cards = new ArrayList<>();
                    for (Object o : contents) {
                        RbmCard card = parseCardContent(asMap(o), null);
                        if (card != null) cards.add(card);
                    }
                    if (!cards.isEmpty()) {
                        return new RbmBotMessage(RbmBotMessage.Kind.CARD, null, cards);
                    }
                }
            }
        }

        return RbmBotMessage.unknown();
    }

    /** A card body: {title, description, media?, suggestions:[...]}. */
    private static RbmCard parseCardContent(Map<String, Object> content, String orientation) {
        if (content == null) return null;
        String title = asString(content.get("title"));
        String description = asString(content.get("description"));

        String mediaUrl = null, mediaThumb = null, mediaCt = null, mediaAlt = null;
        Map<String, Object> media = asMap(content.get("media"));
        if (media != null) {
            // The URLs are under media.contentInfo, or directly on media in some shapes.
            Map<String, Object> ci = asMap(media.get("contentInfo"));
            Map<String, Object> src = ci != null ? ci : media;
            mediaUrl = asString(src.get("fileUrl"));
            mediaThumb = asString(src.get("thumbnailUrl"));
            mediaAlt = asString(src.get("altText"));
            mediaCt = asString(src.get("contentType"));   // often absent
            if (mediaCt == null) mediaCt = asString(media.get("contentType"));
        }

        List<RbmSuggestion> sugg = parseSuggestions(asList(content.get("suggestions")));

        // Treat as a card if it carries any renderable content.
        if (title == null && description == null && mediaUrl == null && sugg.isEmpty()) {
            return null;
        }
        return new RbmCard(title, description, mediaUrl, mediaThumb, mediaCt, mediaAlt,
                orientation, sugg);
    }

    private static List<RbmSuggestion> parseSuggestions(List<Object> arr) {
        List<RbmSuggestion> out = new ArrayList<>();
        if (arr == null) return out;
        for (Object o : arr) {
            RbmSuggestion s = parseSuggestion(asMap(o));
            if (s != null) out.add(s);
        }
        return out;
    }

    private static RbmSuggestion parseSuggestion(Map<String, Object> s) {
        if (s == null) return null;

        // Suggested reply: {reply:{displayText, postback:{data}}}
        Map<String, Object> reply = asMap(s.get("reply"));
        if (reply != null) {
            return new RbmSuggestion.Builder(RbmSuggestion.Type.REPLY)
                    .displayText(asString(reply.get("displayText")))
                    .postbackData(asString(path(reply, "postback", "data")))
                    .build();
        }

        // Suggested action: {action:{displayText, postback:{data}, fallbackUrl?, <kind>}}. It also
        // carries postback data, sent on tap like a reply.
        Map<String, Object> action = asMap(s.get("action"));
        if (action != null) {
            final String displayText = asString(action.get("displayText"));
            final String postback = asString(path(action, "postback", "data"));
            final String fallbackUrl = asString(action.get("fallbackUrl"));

            Map<String, Object> open = asMap(action.get("openUrlAction"));
            if (open != null) {
                return base(RbmSuggestion.Type.OPEN_URL, displayText, postback, fallbackUrl)
                        .url(asString(open.get("url")))
                        .build();
            }
            Map<String, Object> dial = asMap(action.get("dialAction"));
            if (dial != null) {
                return base(RbmSuggestion.Type.DIAL, displayText, postback, fallbackUrl)
                        .phoneNumber(asString(dial.get("phoneNumber")))
                        .build();
            }
            Map<String, Object> loc = asMap(action.get("viewLocationAction"));
            if (loc != null) {
                Map<String, Object> latLong = asMap(loc.get("latLong"));
                return base(RbmSuggestion.Type.VIEW_LOCATION, displayText, postback, fallbackUrl)
                        .latitude(asDouble(latLong == null ? null : latLong.get("latitude")))
                        .longitude(asDouble(latLong == null ? null : latLong.get("longitude")))
                        .locationLabel(asString(loc.get("label")))
                        .locationQuery(asString(loc.get("query")))
                        .build();
            }
            Map<String, Object> cal = asMap(action.get("createCalendarEventAction"));
            if (cal != null) {
                return base(RbmSuggestion.Type.CREATE_CALENDAR, displayText, postback, fallbackUrl)
                        .calStart(asString(cal.get("startTime")))
                        .calEnd(asString(cal.get("endTime")))
                        .calTitle(asString(cal.get("title")))
                        .calDescription(asString(cal.get("description")))
                        .build();
            }
            if (action.containsKey("shareLocationAction")) {
                return base(RbmSuggestion.Type.SHARE_LOCATION, displayText, postback, fallbackUrl)
                        .build();
            }
            // Known to be an action but an unmodelled kind.
            return base(RbmSuggestion.Type.UNKNOWN, displayText, postback, fallbackUrl).build();
        }
        return null;
    }

    /** Builder seeded with the fields every suggestion/action shares. */
    private static RbmSuggestion.Builder base(RbmSuggestion.Type type, String displayText,
            String postback, String fallbackUrl) {
        return new RbmSuggestion.Builder(type)
                .displayText(displayText)
                .postbackData(postback)
                .fallbackUrl(fallbackUrl);
    }

    // ---- typed accessors over the plain object tree ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (o instanceof List) ? (List<Object>) o : null;
    }

    private static String asString(Object o) {
        return (o instanceof String) ? (String) o : null;
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Walk nested object keys; null if any hop is missing/not an object. */
    private static Object path(Map<String, Object> m, String... keys) {
        Object cur = m;
        for (String k : keys) {
            Map<String, Object> cm = asMap(cur);
            if (cm == null) return null;
            cur = cm.get(k);
        }
        return cur;
    }
}
