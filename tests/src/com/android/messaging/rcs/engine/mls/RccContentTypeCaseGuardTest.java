/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code android.support.v7.mms.pdu.ContentType}'s type predicates are case-insensitive
 * (RFC 2045 §5.1), and the inbound media arm normalises its type once. A source scan keyed on the
 * comparison methods, because {@code ContentType} imports {@code android.webkit}; the file is a
 * carried copy that a re-sync would overwrite. {@link RccContentDisposition#canonicalType}
 * normalises the producer we control, and these predicates accept what other producers emit.
 */
public final class RccContentTypeCaseGuardTest {

    /** {@code predicate, at least this many case-insensitive comparisons, why}. */
    private static final String[][] PREDICATES = {
        {"isTextType", "3", "TEXT_PLAIN, TEXT_HTML and APP_WAP_XHTML — one per literal, and a "
                + "count below three means one of them went back to equals()"},
        {"isImageType", "1", "one prefix test, through startsWithIgnoreCase"},
        {"isVideoType", "1", "one prefix test, through startsWithIgnoreCase"},
        {"isAudioType", "2",
                "the audio/ prefix AND the AUDIO_OGG exact match — this one was ALREADY "
                + "half-insensitive, which is what made the class's inconsistency a half-finished "
                + "normalisation rather than a design"},
        {"isVCardType", "2", "TEXT_X_VCARD and TEXT_VCARD, both equalsIgnoreCase since 2021 — the "
                + "row that proves this guard can see a predicate it did not have to change"},
    };

    /** Spellings that make a comparison case-insensitive. */
    private static final String[] INSENSITIVE = {
        "equalsIgnoreCase(", "startsWithIgnoreCase(", "regionMatches(true,",
    };

    /** Case-sensitive spellings; the leading dot keeps {@code equalsIgnoreCase} out. */
    private static final String[] SENSITIVE = {".equals(", ".startsWith("};

    private static final String CONTENT_TYPE = "src/android/support/v7/mms/pdu/ContentType.java";
    private static final String RECEIVE_ACTION =
            "src/com/android/messaging/datamodel/action/ReceiveRcsMessageAction.java";

    @Test
    public void everyTypePredicateIsCaseInsensitive() throws IOException {
        final String src = codeOnly(read(CONTENT_TYPE));
        final List<String> sensitive = new ArrayList<>();
        final List<String> vanished = new ArrayList<>();
        final List<String> thin = new ArrayList<>();
        for (final String[] row : PREDICATES) {
            final String body = bodyOf(src, row[0]);
            // A predicate the scan cannot find fails.
            if (body.isEmpty()) {
                vanished.add(row[0]);
                continue;
            }
            int insensitive = 0;
            for (final String needle : INSENSITIVE) insensitive += count(body, needle);
            final int min = Integer.parseInt(row[1]);
            if (insensitive < min) {
                thin.add(row[0] + "(): " + insensitive
                        + " case-insensitive comparison(s), expected "
                        + "at least " + min + " — " + row[2]);
            }
            for (final String needle : SENSITIVE) {
                if (body.contains(needle)) sensitive.add(row[0] + "() uses " + needle);
            }
        }
        if (!vanished.isEmpty()) {
            fail("These predicates are gone from " + CONTENT_TYPE + ": " + vanished
                    + ". Either the "
                    + "vendored file was re-synced from upstream — which also drops the "
                    + "case-insensitivity fork landed — or they were renamed and every "
                    + "renderer behind the new name is now unwatched. This guard has just certified "
                    + "a file it cannot see.");
        }
        if (!thin.isEmpty()) {
            fail("Fewer case-insensitive comparisons than these predicates are declared to make: "
                    + thin + ". A predicate that lost one of its arms to an exact match is the "
                    + "canonicalisation defect returning for that arm alone, which renders as a missing "
                    + "attachment and logs nothing a user sees.");
        }
        if (!sensitive.isEmpty()) {
            fail("These type predicates compare case-SENSITIVELY: " + sensitive
                    + ". MIME types are "
                    + "case-insensitive (RFC 2045 s5.1), so a conforming peer's IMAGE/JPEG routes as "
                    + "media, stages its bytes, and then renders as NOTHING — the quiet kind of "
                    + "failure, because ConversationMessageView's Assert.isTrue logs and continues "
                    + "on userdebug. Use equalsIgnoreCase or startsWithIgnoreCase.");
        }
    }

    /** The callee must be case-insensitive too, not only its name at every call site. */
    @Test
    public void theSharedPrefixHelperIsActuallyInsensitive() throws IOException {
        final String src = codeOnly(read(CONTENT_TYPE));
        final String body = bodyOf(src, "startsWithIgnoreCase");
        assertFalse("startsWithIgnoreCase() is gone, but the predicates above still delegate to a "
                + "name — this guard is reading calls to a method it cannot check", body.isEmpty());
        assertTrue("startsWithIgnoreCase() does not use regionMatches(true, …), so it is a "
                + "case-SENSITIVE method with a case-insensitive name and every caller inherits the "
                + "bug while reading as fixed: " + body.trim(),
                body.contains("regionMatches(true,"));
        assertFalse("startsWithIgnoreCase() calls String.startsWith, which is the exact comparison "
                + "it exists to replace", body.contains(".startsWith("));
    }

    /** {@code canonicalType} lower-cases, so every literal the predicates test is lower case. */
    @Test
    public void theTwoFixesDoNotFight() throws IOException {
        assertEqualsLower("image/jpeg", RccContentDisposition.canonicalType("IMAGE/JPEG"));
        assertEqualsLower("image/jpeg", RccContentDisposition.canonicalType("  Image/Jpeg  "));
        // Parameter values keep their case.
        assertEqualsLower("image/jpeg; name=\"Photo.JPG\"",
                RccContentDisposition.canonicalType("IMAGE/JPEG; name=\"Photo.JPG\""));
        final String src = codeOnly(read(CONTENT_TYPE));
        final List<String> upper = new ArrayList<>();
        // The two prefixes that are inline literals, read from the predicates' own bodies.
        for (final String[] inline : new String[][] {
                {"isAudioType", "\"audio/\""}, {"isVideoType", "\"video/\""}}) {
            final String body = bodyOf(src, inline[0]);
            if (body.isEmpty()) {
                upper.add(inline[0] + " — no such predicate; this check is watching nothing");
            } else if (!body.contains(inline[1])) {
                upper.add(inline[0] + " no longer tests " + inline[1] + " — its prefix literal has "
                        + "changed spelling or case");
            }
        }
        for (final String[] row : new String[][] {
                {"IMAGE_PREFIX", "image/"}, {"TEXT_PLAIN", "text/plain"},
                // application/ogg, not audio/ogg: why isAudioType has an equalsIgnoreCase arm.
                {"TEXT_HTML", "text/html"}, {"AUDIO_OGG", "application/ogg"},
                {"APP_WAP_XHTML", "application/vnd.wap.xhtml+xml"}}) {
            final int at = src.indexOf("String " + row[0]);
            if (at < 0) {
                upper.add(row[0] + " — no such constant; this check is watching nothing");
                continue;
            }
            final String decl = src.substring(at, Math.min(src.length(), at + 200));
            final int q = decl.indexOf('"');
            final int q2 = (q < 0) ? -1 : decl.indexOf('"', q + 1);
            if (q < 0 || q2 < 0) {
                upper.add(row[0] + " — could not read its literal");
            } else if (!decl.substring(q + 1, q2).equals(row[1])) {
                upper.add(row[0] + " = \"" + decl.substring(q + 1, q2) + "\", expected \"" + row[1]
                        + "\"");
            }
        }
        if (!upper.isEmpty()) {
            fail("A type constant these predicates compare against is not the lower-case spelling "
                    + "the producer normalises to: " + upper + ". RccContentDisposition."
                    + "canonicalType lower-cases, so a mixed-case literal here would be defeated by "
                    + "the very fix meant to feed it — and the symptom would be identical to the "
                    + "bug that was fixed.");
        }
    }

    /**
     * The arm reads the type more than once, so it normalises at the top and never reads the raw
     * parameter afterwards.
     */
    @Test
    public void theInboundMediaArmNormalisesOnce() throws IOException {
        final String src = codeOnly(read(RECEIVE_ACTION));
        final int at = src.indexOf("case MEDIA:");
        assertTrue("ReceiveRcsMessageAction has no `case MEDIA:` arm — the inbound media path has "
                + "moved and this guard is watching nothing", at >= 0);
        final String arm = blockAfter(src, at);
        assertFalse(
                "the `case MEDIA:` arm has no brace-matched block, so this guard cannot read it "
                + "— it was given one precisely so the arm has a readable extent",
                arm.isEmpty());
        assertTrue(
                "the inbound MEDIA arm no longer calls RccContentDisposition.canonicalType, so a "
                + "peer's IMAGE/JPEG reaches the part row verbatim and renders as nothing: "
                + arm.trim(), arm.contains("RccContentDisposition.canonicalType("));
        final List<String> raw = new ArrayList<>();
        for (final String use : new String[] {
                "stageBytesToScratch(context, body, contentType)", "mediaMime = contentType",
                "\"[\" + contentType"}) {
            if (arm.contains(use)) raw.add(use);
        }
        if (!raw.isEmpty()) {
            fail("The inbound MEDIA arm reads the RAW content type after normalising it: " + raw
                    + ". Normalising once at the top is the whole fix — a consumer that takes the "
                    + "un-normalised value is the half-fix that was rejected, and it fails silently "
                    + "because the OTHER consumer still looks right.");
        }
    }

    /** The brace-matched block opened at or after {@code at}, or "" if there is none. */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    private static void assertEqualsLower(final String expected, final String actual) {
        assertTrue("canonicalType did not fold the type/subtype: expected " + expected + ", got "
                + actual, expected.equals(actual));
    }

    private static int count(final String haystack, final String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    /** The brace-matched body of a class-level method, or "" if there is none by that name. */
    private static String bodyOf(final String src, final String name) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(")
                .matcher(src);
        while (m.find()) {
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
            }
        }
        return "";
    }

    /**
     * Comments blanked character for character, string literals kept: comments in the scanned file
     * quote the case-sensitive forms, and {@link #theTwoFixesDoNotFight} reads the literals.
     */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        boolean line = false;
        boolean block = false;
        boolean str = false;
        boolean chr = false;
        for (int i = 0; i < out.length; i++) {
            final char c = out[i];
            final char n = (i + 1 < out.length) ? out[i + 1] : '\0';
            if (line) {
                if (c == '\n') line = false; else out[i] = ' ';
            } else if (block) {
                if (c == '*' && n == '/') { out[i] = ' '; out[i + 1] = ' '; i++; block = false; }
                else if (c != '\n') out[i] = ' ';
            } else if (str) {
                if (c == '\\') i++; else if (c == '"') str = false;
            } else if (chr) {
                if (c == '\\') i++; else if (c == '\'') chr = false;
            } else if (c == '/' && n == '/') { line = true; out[i] = ' '; }
            else if (c == '/' && n == '*') { block = true; out[i] = ' '; out[i + 1] = ' '; i++; }
            else if (c == '"') str = true;
            else if (c == '\'') chr = true;
        }
        return new String(out);
    }

    /** Same locator as the other source-scan guards: works from the module dir or the tree root. */
    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
