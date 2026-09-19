/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every per-conversation map and set in {@code MlsProviderTransport} is cleared on forget; entries
 * that survive gate a freshly re-joined conversation. A source guard because the transport cannot
 * run in the host suite, and because the question is whether a new container can be added without
 * teardown.
 *
 * <p>If this fails you added per-conversation state: clear it in {@code clearConversationState}, or
 * add it to {@link #NOT_PER_CONVERSATION} with what it is keyed by. See docs/testing.md.
 */
public final class MlsConversationTeardownGuardTest {

    /** Containers keyed by something other than a conversation, each with a comment on its key. */
    private static final String[] NOT_PER_CONVERSATION = {
        // Empty. Per-peer bounds such as the re-establish cooldown live in durable records
        // (MlsCooldownRecord in MlsPeerGuard's store), not in transport fields.
    };

    /**
     * Maps keyed by the app's conversation id rather than the canonical key, and the variable the
     * teardown must remove them by. Removing such a map by the canonical key is well-formed and
     * clears nothing, so the key space is pinned per map.
     */
    private static final String[][] CONVERSATION_ID_KEYED = {
        {"mConvAlias", "convId"},
    };

    /**
     * {@code private [final] Map<String, …> mFoo}: a per-conversation map field. {@code final} is
     * optional and any java.util map type matches; the name must be followed by {@code =} or
     * {@code ;} so a method returning a map is not read as a field.
     */
    private static final Pattern MAP_FIELD = Pattern.compile(
            "private\\s+(?:final\\s+)?(?:java\\.util\\.)?"
                    + "(?:Map|HashMap|ConcurrentHashMap|LinkedHashMap|TreeMap|ConcurrentSkipListMap)"
                    + "\\s*<\\s*String\\s*,[^>]*>[^=;(]*?\\b(m[A-Z][A-Za-z0-9]*)\\s*(?:=|;)");

    /**
     * {@code private final Set<String> mFoo}: a conversation-keyed set gates behaviour exactly as a
     * map does, so it carries the same obligation.
     */
    private static final Pattern SET_FIELD = Pattern.compile(
            "private\\s+(?:final\\s+)?(?:java\\.util\\.)?"
                    + "(?:Set|HashSet|LinkedHashSet|TreeSet|ConcurrentSkipListSet)"
                    + "\\s*<\\s*String\\s*>[^=;(]*?\\b(m[A-Z][A-Za-z0-9]*)\\s*(?:=|;)");

    /**
     * The number of per-conversation containers the transport declares, so a pattern that stops
     * matching fails rather than passing on what it still sees. Raise it when adding one; lowering
     * it claims one was deleted.
     */
    private static final int DECLARED_CONTAINERS = 7;

    @Test
    public void forgetClearsEveryPerConversationMap() throws IOException {
        // Code only: a javadoc showing a declaration would count as a field, and a comment naming
        // mFoo.remove( would satisfy the clearing check.
        final String src = codeOnly(readTransport());
        final String teardown = bodyOf(src, "private void clearConversationState");
        assertTrue("clearConversationState not found — the teardown has been renamed or removed, "
                + "which is exactly the regression this guards", teardown.length() > 0);

        final List<String> missing = new ArrayList<>();
        int found = 0;
        for (final Pattern p : new Pattern[] { MAP_FIELD, SET_FIELD }) {
            final Matcher m = p.matcher(src);
            while (m.find()) {
                final String field = m.group(1);
                found++;
                if (isExempt(field)) continue;
                if (!teardown.contains(field + ".remove(")) missing.add(field);
            }
        }

        assertTrue("only " + found + " per-conversation map/set declarations matched, and " 
                + DECLARED_CONTAINERS + " are known to exist. This guard ENUMERATES CONTAINERS; it "
                + "is not a style check. Fewer than that means the declarations have been respelled "
                + "past the patterns — a type they do not list, a `final` dropped, a rename — and "
                + "every container behind the change is now invisible while this test reports green. "
                + "If one really was deleted, lower DECLARED_CONTAINERS and say which.",
                found >= DECLARED_CONTAINERS);
        if (!missing.isEmpty()) {
            fail("These per-conversation maps/sets in MlsProviderTransport are NOT cleared by "
                    + "clearConversationState, so a forgotten-then-rejoined conversation inherits "
                    + "their entries and they gate its behaviour: " + missing
                    + ". Clear them there, or add them to NOT_PER_CONVERSATION with a reason.");
        }
    }

    /**
     * A conversation-id-keyed map is removed by the conversation id, not the canonical key.
     * Separate from the coverage test: this failure is a teardown that looks complete and clears
     * nothing.
     */
    @Test
    public void conversationIdKeyedMapsAreRemovedByConversationId() throws IOException {
        final String teardown = bodyOf(codeOnly(readTransport()),
                "private void clearConversationState");
        assertTrue("clearConversationState not found — the teardown has been renamed or removed",
                teardown.length() > 0);
        for (final String[] row : CONVERSATION_ID_KEYED) {
            final String field = row[0];
            final String want = field + ".remove(" + row[1] + ")";
            if (teardown.contains(want)) continue;
            fail(field + " is keyed by the app's conversation id, but clearConversationState does "
                    + "not remove it by one — expected `" + want
                    + "`. Removing it by the canonical "
                    + "key is well-formed and matches nothing, which is how this stayed a silent "
                    + "no-op.");
        }
    }

    private static boolean isExempt(final String field) {
        for (final String e : NOT_PER_CONVERSATION) {
            if (e.equals(field)) return true;
        }
        return false;
    }

    /**
     * Comments and string-literal contents blanked character for character, offsets preserved, as
     * in {@code MlsGuardPersistenceTest.codeOnly}.
     */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        final int n = out.length;
        int i = 0;
        while (i < n) {
            final char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                final char quote = c;
                i++;
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                        if (out[i] != '\n') out[i] = ' ';
                        i++;
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** The brace-matched body of the named method, or "" if it is absent. */
    private static String bodyOf(final String src, final String signature) {
        final int at = src.indexOf(signature);
        if (at < 0) return "";
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

    /**
     * The transport source, resolved from the working directory so the guard runs the same from the
     * module dir and the tree root.
     */
    private static String readTransport() throws IOException {
        final String rel = "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";
        final String[] candidates = {
            rel,
            "packages/apps/Messaging/" + rel,
            "../" + rel,
        };
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        }
        throw new IOException("MlsProviderTransport.java not found from "
                + new File(".").getAbsolutePath() + " — tried " + String.join(", ", candidates));
    }
}
