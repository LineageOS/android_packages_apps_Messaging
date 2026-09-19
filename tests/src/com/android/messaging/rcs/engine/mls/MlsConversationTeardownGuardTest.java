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
 * Every per-conversation map in {@code MlsProviderTransport} must be cleared on forget.
 *
 * <h2>Why this is a SOURCE guard and not a behavioural test</h2>
 *
 * <p>{@code MlsProviderTransport} is Android-dependent and cannot run in the host suite, so the
 * recovery behaviour these maps encode is otherwise verified only on a device — which is exactly how
 * the defect survived: {@code forget()} cleared one map of eighteen, and the other seventeen kept
 * entries that then gated a freshly re-joined conversation.
 *
 * <p>The pattern is also self-accelerating. Each new recovery mechanism adds a map, and nothing
 * forces the author to extend the teardown; one session alone added five. So the check that matters
 * is not "does teardown work today" but "can a new map be added without teardown" — and only a guard
 * over the source can answer that.
 *
 * <p>If this test fails you have added a per-conversation map. Either clear it in
 * {@code clearConversationState}, or — if it genuinely is not per-conversation state — rename it or
 * add it to {@link #NOT_PER_CONVERSATION} with a reason.
 */
public final class MlsConversationTeardownGuardTest {

    /**
     * Maps that are keyed by something OTHER than a conversation key, with the reason.
     *
     * <p>Deliberately empty today. Adding an entry here is a claim that a map is not per-conversation
     * state; it should carry a comment saying what it IS keyed by.
     */
    private static final String[] NOT_PER_CONVERSATION = {
        // EMPTY AGAIN. The single entry was `mLastReestablish`, the re-establish
        // cooldown, exempted here because it is keyed by PEER E.164 rather than by a
        // conversation key: ensureReady() CLAIMS A PEER KEYPACKAGE out of a small pool we do not
        // replenish, so the bound protects the PEER across every conversation we have with them and
        // clearing it on teardown would let a forget/rejoin loop drain that pool at will.
        //
        // The field is gone, not the obligation. The cooldown was made DURABLE — it is now a
        // MlsCooldownRecord in MlsPeerGuard's store, keyed by the normalised peer — because in
        // memory a process restart handed the whole ten-minute allowance back, which is the same
        // drain by another route. The exemption is deleted rather than left, for this file's own
        // stated reason: a classification for a name that is gone is inherited silently by the next
        // field to take it.
    };

    /**
     * Maps keyed by the app's CONVERSATION ID rather than the canonical key, and the variable the
     * teardown must remove them by.
     *
     * <h2>Why "it is named in the teardown" was not enough</h2>
     *
     * <p>{@code mConvAlias} maps <em>conversation id → canonical key</em>. The teardown is handed the
     * canonical key, so {@code mConvAlias.remove(key)} was well-formed, present, and could never
     * match a single entry — it had always been a no-op, and this guard passed the whole time
     * because the field name did appear with {@code .remove(} in the body.
     *
     * <p>That is the general lesson rather than one map's slip: a teardown check that only asks
     * "is it mentioned" cannot distinguish clearing from appearing to clear. So the key space is
     * pinned per map, which is a claim a reader can check against the field's own javadoc.
     */
    private static final String[][] CONVERSATION_ID_KEYED = {
        {"mConvAlias", "convId"},
    };

    /**
     * {@code private [final] Map<String, …> mFoo} — a per-conversation map field.
     *
     * <p>{@code final} is OPTIONAL and the collection list is the whole java.util family, because
     * both were spellings rather than properties: dropping {@code final}, or
     * declaring a {@code LinkedHashMap} because insertion order mattered, took a map out of this
     * guard's sight without changing anything about its obligation. The name must be followed by
     * {@code =} or {@code ;} so a method whose RETURN TYPE is a map is not read as a field.
     */
    private static final Pattern MAP_FIELD = Pattern.compile(
            "private\\s+(?:final\\s+)?(?:java\\.util\\.)?"
                    + "(?:Map|HashMap|ConcurrentHashMap|LinkedHashMap|TreeMap|ConcurrentSkipListMap)"
                    + "\\s*<\\s*String\\s*,[^>]*>[^=;(]*?\\b(m[A-Z][A-Za-z0-9]*)\\s*(?:=|;)");

    /**
     * {@code private final Set<String> mFoo} — the SAME obligation, and it was invisible here.
     *
     * <p>A {@code Set<String>} keyed by conversation gates behaviour exactly as a map does; the only
     * difference is that it stores membership rather than a value. Scanning only maps let
     * {@code mEraQuotaReported} sit uncleared beside {@code mEraAsked} — the map was caught, its
     * set sibling was not, and they were declared two lines apart and cleared by the same fix.
     *
     * <p>That is the same shape as the {@code mConvAlias} lesson already recorded above: a guard is
     * only as good as the declarations it can see, and the gap is silent by construction. Widening
     * it here rather than exempting the set, because "the guard did not look" is not a reason.
     */
    private static final Pattern SET_FIELD = Pattern.compile(
            "private\\s+(?:final\\s+)?(?:java\\.util\\.)?"
                    + "(?:Set|HashSet|LinkedHashSet|TreeSet|ConcurrentSkipListSet)"
                    + "\\s*<\\s*String\\s*>[^=;(]*?\\b(m[A-Z][A-Za-z0-9]*)\\s*(?:=|;)");

    /**
     * The minimum number of per-conversation containers this file is known to declare.
     *
     * <p>{@code > 0} was the old floor and it is not a floor at all: one surviving match certifies a
     * file the patterns can no longer read. Seven are declared today; the number is here so that
     * losing six of them is a RED test rather than a quiet one. Raise it when
     * you add one; lowering it is a claim that a map was deleted, and should read like one.
     *
     * <p><b>8 → 7, and the claim is exactly that:</b> {@code mLastReestablish}
     * was DELETED. The re-establish cooldown it held became durable — a {@code MlsCooldownRecord}
     * in {@code MlsPeerGuard}'s store, keyed by the normalised peer — because in memory every
     * process restart handed its whole ten-minute allowance back, on a bound whose resource is the
     * PEER'S KeyPackage pool. It was the sole {@link #NOT_PER_CONVERSATION} exemption, and that
     * entry went with it.
     */
    private static final int DECLARED_CONTAINERS = 7;

    @Test
    public void forgetClearsEveryPerConversationMap() throws IOException {
        // CODE ONLY. Both halves of this scan were reading prose. A javadoc that shows a field
        // declaration would be counted as a field, and — the silent half — a COMMENT inside
        // clearConversationState naming `mFoo.remove(` satisfied the check that the map is cleared.
        // The whole point of this guard is that a teardown which appears to clear is worse than one
        // that visibly does not (the mConvAlias lesson below), and it was itself readable that way.
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
     * A conversation-id-keyed map must be removed BY the conversation id, not by the canonical key.
     *
     * <p>Separate test from the coverage one on purpose: the failure modes are different and read
     * differently. "You added a map and forgot the teardown" is an omission; "you cleared it in the
     * wrong key space" is a teardown that looks complete and clears nothing.
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
                    + "not remove it by one — expected `" + want + "`. Removing it by the canonical "
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
     * Comments and string-literal contents blanked, character for character and offsets preserved.
     *
     * <p>Same helper as {@code MlsGuardPersistenceTest.codeOnly}, for the same reason: every
     * assertion here is about what the code DOES, and this file's own subject matter is named
     * repeatedly in the transport's prose.
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
     * Locate the transport source relative to this module.
     *
     * <p>Resolved from the working directory rather than a build variable so the guard runs the same
     * way from the module dir and from the tree root.
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
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException("MlsProviderTransport.java not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
