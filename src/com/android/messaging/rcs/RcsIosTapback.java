/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * Parses an iOS tapback, which arrives over RCS as ordinary text such as
 * {@code Disliked “<quoted message>”} rather than as a structured reaction, into a reaction. The
 * grammar and the verb-to-emoji table are English defaults; other locales need their own entries
 * in {@link #ADD}, {@link #REMOVE} and {@link #VERB_EMOJI}.
 */
public final class RcsIosTapback {
    private RcsIosTapback() {}

    /** How many recent messages are searched for the quoted target. */
    public static final int SEARCH_LIMIT = 50;

    // The curly quotes are delimiters outside the capture group, so group(2) is the quoted text.
    // (?s) lets a multi-line quote match; \Z anchors the end of input.
    private static final Pattern ADD = Pattern.compile(
            "(?s)^(Loved|Liked|Disliked|Laughed\\sat|Emphasi[zs]ed|Questioned)"
                    + "\\s“(.*)”\\Z",
            Pattern.UNIX_LINES);
    private static final Pattern REMOVE = Pattern.compile(
            "(?s)^Removed\\s(?:a|an)\\s"
                    + "(heart|like|dislike|laugh|exclamation|exclamation\\smark|question\\smark)"
                    + "\\sfrom\\s“(.*)”\\Z",
            Pattern.UNIX_LINES);

    // Lowercased verb or removal noun to reaction glyph. "Questioned" and "emphasized" map to the
    // literal question and double-exclamation glyphs.
    private static final Map<String, String> VERB_EMOJI = new LinkedHashMap<>();
    static {
        VERB_EMOJI.put("loved", "❤️");
        VERB_EMOJI.put("heart", "❤️");
        VERB_EMOJI.put("liked", "👍");
        VERB_EMOJI.put("like", "👍");
        VERB_EMOJI.put("disliked", "👎");
        VERB_EMOJI.put("dislike", "👎");
        VERB_EMOJI.put("laughed at", "😂");
        VERB_EMOJI.put("laugh", "😂");
        VERB_EMOJI.put("emphasized", "‼️");
        VERB_EMOJI.put("emphasised", "‼️");
        VERB_EMOJI.put("exclamation", "‼️");
        VERB_EMOJI.put("exclamation mark", "‼️");
        VERB_EMOJI.put("questioned", "❓");
        VERB_EMOJI.put("question mark", "❓");
    }

    /** A parsed tapback. */
    public static final class Parsed {
        /** The reaction glyph the verb/noun maps to. */
        public final String emoji;
        /** The quoted target text, without the curly quotes. */
        public final String quotedText;
        /** true = add the reaction, false = remove it. */
        public final boolean add;

        Parsed(final String emoji, final String quotedText, final boolean add) {
            this.emoji = emoji;
            this.quotedText = quotedText;
            this.add = add;
        }
    }

    /** Parses {@code body} as a tapback, or returns {@code null} if it is an ordinary message. */
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
