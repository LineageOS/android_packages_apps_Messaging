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

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an inbound iOS "tapback" that arrives over RCS as an ordinary text
 * message -- e.g. {@code Disliked “<quoted message>”} -- and turns it
 * into a reaction. iOS does NOT emit structured Universal-Profile reactions
 * cross-platform; it sends a localized text description. This mirrors Google
 * Messages' inbound iOS path (its own classifier plus an
 * {@code ios_reactions_mapping} table).
 *
 * <p>The grammar (regexes) and the verb&rarr;emoji table below are Google
 * Messages' compiled <b>English defaults</b>. There
 * these are server-overridable <b>Phenotype</b> blobs
 * ({@code ios_reaction_message_grammar} + {@code ios_reactions_mapping}), NOT app
 * resources -- so the full per-locale verb set (and any iOS-18 "Reacted &lt;emoji&gt;
 * to ..." rule, absent from the compiled default) requires a live Phenotype dump
 * and can be dropped into {@link #ADD}/{@link #REMOVE}/{@link #VERB_EMOJI} later
 * without touching callers.
 */
public final class RcsIosTapback {
    private RcsIosTapback() {}

    /** The {@code reactions_xms_search_message_limit} default. */
    public static final int SEARCH_LIMIT = 50;

    // Compiled English defaults (ADD then REMOVE). The curly
    // quotes U+201C/U+201D are literal delimiters OUTSIDE the capture group, so
    // group(2) is the raw quoted target text. (?s)=DOTALL so a multi-line quoted
    // body matches; \Z anchors end-of-input.
    private static final Pattern ADD = Pattern.compile(
            "(?s)^(Loved|Liked|Disliked|Laughed\\sat|Emphasi[zs]ed|Questioned)"
                    + "\\s“(.*)”\\Z",
            Pattern.UNIX_LINES);
    private static final Pattern REMOVE = Pattern.compile(
            "(?s)^Removed\\s(?:a|an)\\s"
                    + "(heart|like|dislike|laugh|exclamation|exclamation\\smark|question\\smark)"
                    + "\\sfrom\\s“(.*)”\\Z",
            Pattern.UNIX_LINES);

    // ios_reactions_mapping default: the lowercased captured
    // verb/noun -> the reaction glyph. NB "loved"/"heart" -> RED_HEART (not LOVE);
    // "questioned"/"emphasized" -> the literal ?/!! glyphs (CUSTOM, since they are
    // not in the fixed 11-glyph set).
    private static final Map<String, String> VERB_EMOJI = new LinkedHashMap<>();
    static {
        VERB_EMOJI.put("loved", "❤️");          // RED_HEART
        VERB_EMOJI.put("heart", "❤️");          // removal noun
        VERB_EMOJI.put("liked", "👍");          // LIKE
        VERB_EMOJI.put("like", "👍");
        VERB_EMOJI.put("disliked", "👎");       // DISLIKE
        VERB_EMOJI.put("dislike", "👎");
        VERB_EMOJI.put("laughed at", "😂");     // LAUGH
        VERB_EMOJI.put("laugh", "😂");
        VERB_EMOJI.put("emphasized", "‼️");     // double-exclamation
        VERB_EMOJI.put("emphasised", "‼️");
        VERB_EMOJI.put("exclamation", "‼️");
        VERB_EMOJI.put("exclamation mark", "‼️");
        VERB_EMOJI.put("questioned", "❓");            // question mark ornament
        VERB_EMOJI.put("question mark", "❓");
    }

    /** A successfully-parsed iOS tapback. */
    public static final class Parsed {
        /** The reaction glyph the verb/noun maps to. */
        public final String emoji;
        /** The raw quoted target-message text (no surrounding curly quotes). */
        public final String quotedText;
        /** true = add the reaction, false = remove it. */
        public final boolean add;

        Parsed(final String emoji, final String quotedText, final boolean add) {
            this.emoji = emoji;
            this.quotedText = quotedText;
            this.add = add;
        }
    }

    /**
     * Parse {@code body} as an iOS tapback, or return {@code null} if it isn't
     * one (the caller then treats it as an ordinary message).
     */
    @Nullable
    public static Parsed parse(@Nullable final String body) {
        if (TextUtils.isEmpty(body)) {
            return null;
        }
        Matcher m = ADD.matcher(body);
        if (m.matches()) {
            final String emoji = VERB_EMOJI.get(lower(m.group(1)));
            return emoji == null ? null : new Parsed(emoji, m.group(2), true);
        }
        m = REMOVE.matcher(body);
        if (m.matches()) {
            final String emoji = VERB_EMOJI.get(lower(m.group(1)));
            return emoji == null ? null : new Parsed(emoji, m.group(2), false);
        }
        return null;
    }

    private static String lower(final String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
