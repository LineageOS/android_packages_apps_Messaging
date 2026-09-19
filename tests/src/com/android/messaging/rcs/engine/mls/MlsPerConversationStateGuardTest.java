/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Every durable per-conversation store is in the teardown registry. A store declares itself by
 * implementing {@code MlsPerConversationState}, and this test fails if it is missing from
 * {@code MlsProviderTransport.perConversationStores()}: a persisted record that outlives
 * {@code forget()} survives a reboot and can refuse a re-joined conversation on a budget spent
 * under a group that no longer exists. Per-message state goes in
 * {@link #PER_MESSAGE_NOT_PER_CONVERSATION} with its reason. See docs/mls/transport-and-port.md.
 */
public final class MlsPerConversationStateGuardTest {

    private static final String IFACE = "MlsPerConversationState";
    private static final String REGISTRY = "perConversationStores";

    /**
     * "Implements the interface" as a property, so {@code implements Closeable,
     * MlsPerConversationState} is found too.
     */
    private static final Pattern IMPLEMENTS_IFACE = Pattern.compile(
            "\\bimplements\\s[^{;]*\\b" + IFACE + "\\b");

    /**
     * Stores keyed by {@code rcsMessageId}, with the reason each is not conversation state; the
     * test checks none implements the interface.
     */
    private static final String[][] PER_MESSAGE_NOT_PER_CONVERSATION = {
        // MlsCiphertextCache is registered for teardown although keyed per message: its entries are
        // valid only while their conversation's group exists.
        {"MlsPendingBodyStore",
            "keyed by rcsMessageId; the body is released once the message is sent or abandoned"},
        {"MlsRendezvousStore",
            "keyed by (identity, remoteUserId, rcsMessageId, stage) and emptied per message at "
            + "chat-row insert. Retaining rows across a forget is CORRECT, not a leak: the table "
            + "answers 'did we already surface this message', and a peer's resend carries a NEW "
            + "message id, so a retained row can only prevent a real duplicate"},
    };

    /**
     * Durable per-conversation state that deliberately survives teardown, with the reason; clearing
     * it on forget would break what it exists for.
     */
    private static final String[][] MUST_SURVIVE_TEARDOWN = {
        {"MlsRebuildLimiter",
            "bounds the RATE of automatic conversation rebuilds, and a rebuild's FIRST "
            + "ACT is to forget the conversation. Registering it for teardown would make every "
            + "rebuild erase its own record of having happened, the rate bound would read zero on "
            + "the next attempt, and a conversation that cannot be repaired by rebuilding would "
            + "rebuild in a tight loop forever — claiming a peer key package and forcing a "
            + "re-Welcome each time. This counter must outlive exactly the state it bounds the "
            + "destruction of"},
    };

    /**
     * The must-survive stores are not registered, checked on the interface that would register
     * them.
     */
    @Test
    public void storesThatMustSurviveTeardownAreNotRegistered() throws IOException {
        final File dir = e2eeDir();
        final String registry = bodyOf(codeOnly(read(new File(dir, "MlsProviderTransport.java"))),
                "private java.util.List<MlsPerConversationState> " + REGISTRY);
        for (final String[] row : MUST_SURVIVE_TEARDOWN) {
            final File f = new File(dir, row[0] + ".java");
            assertTrue(row[0] + " no longer exists — remove it from this list, or the exemption is "
                    + "guarding nothing", f.isFile());
            // And it has content: isFile() is true for a zero-byte file.
            assertTrue(row[0]
                    + " is present but declares no class — this check would be reading an "
                    + "empty file and reporting the exemption as holding",
                    read(f).contains("class " + row[0]));
            if (implementsIface(read(f))) {
                fail(row[0] + " now implements " + IFACE + ", so teardown would clear it. That "
                        + "contradicts the recorded reason it must survive: " + row[1]);
            }
            if (registry.contains(row[0])) {
                fail(row[0] + " has been added to " + REGISTRY + "(), so teardown would clear it. "
                        + "That contradicts the recorded reason it must survive: " + row[1]);
            }
        }
    }

    @Test
    public void everyDurablePerConversationStoreIsInTheTeardownRegistry() throws IOException {
        final File dir = e2eeDir();
        final String registry = bodyOf(codeOnly(read(new File(dir, "MlsProviderTransport.java"))),
                "private java.util.List<MlsPerConversationState> " + REGISTRY);
        assertTrue(REGISTRY
                + "() not found in MlsProviderTransport — the registry has been renamed "
                + "or removed, which is exactly the regression this guards", registry.length() > 0);

        final List<String> implementers = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        for (final File f : sourcesIn(dir)) {
            final String name = f.getName().replace(".java", "");
            if (name.equals(IFACE)) continue;               // the interface itself
            if (!implementsIface(read(f))) continue;
            implementers.add(name);
            if (!registry.contains(name)) missing.add(name);
        }

        assertTrue("no class in the e2ee package implements " + IFACE
                + " — either the interface has "
                + "been renamed or this guard's scan has gone stale and is silently passing, which is "
                + "worse than failing", implementers.size() > 0);
        if (!missing.isEmpty()) {
            fail("These classes implement " + IFACE + " but are NOT in MlsProviderTransport."
                    + REGISTRY + "(), so forget() leaves their state behind and a re-joined "
                    + "conversation inherits it — durably, across reboots: " + missing);
        }
    }

    /** The per-message stores are not conversation state. */
    @Test
    public void perMessageStoresDoNotClaimToBePerConversation() throws IOException {
        final File dir = e2eeDir();
        for (final String[] row : PER_MESSAGE_NOT_PER_CONVERSATION) {
            final File f = new File(dir, row[0] + ".java");
            assertTrue(row[0] + " no longer exists — remove it from this list, or the exemption is "
                    + "guarding nothing", f.isFile());
            // And it has content: isFile() is true for a zero-byte file.
            assertTrue(row[0]
                    + " is present but declares no class — this check would be reading an "
                    + "empty file and reporting the exemption as holding",
                    read(f).contains("class " + row[0]));
            if (implementsIface(read(f))) {
                fail(row[0] + " now implements " + IFACE + ", contradicting the recorded reason it "
                        + "is per-message state: " + row[1] + ". Either that reason has stopped "
                        + "being true — in which case register it and delete the exemption — or the "
                        + "interface was added by mistake.");
            }
        }
    }

    /**
     * Every {@code forgetConversation} guards the scope fields it reads: the teardown loop catches
     * only {@code RuntimeException}, and a conversation with no engine group has a null
     * {@code groupId}.
     */
    @Test
    public void everyImplementationGuardsTheScopeFieldsItReads() throws IOException {
        for (final File f : sourcesIn(e2eeDir())) {
            final String src = codeOnly(read(f));
            if (!implementsIface(src)) continue;
            final String body = bodyOf(src, "public int forgetConversation(final Scope scope)");
            assertTrue(f.getName() + " implements " + IFACE + " but its forgetConversation(Scope) "
                    + "body could not be located — has the signature changed?", body.length() > 0);
            for (final String field : new String[] {"groupId", "conversationId", "canonicalKey"}) {
                if (!body.contains("scope." + field)) continue;
                assertTrue(f.getName() + ".forgetConversation reads scope." + field
                        + " without a null guard. The scope's fields are nullable by design — a "
                        + "conversation with no engine group has a null groupId, one with no thread "
                        + "has a null conversationId — and teardown must not throw on the ordinary "
                        + "case.",
                        body.contains("scope." + field + " == null")
                                || body.contains("scope." + field + " != null"));
            }
        }
    }

    /** The registry is not trivially satisfiable by an empty list. */
    @Test
    public void theRegistryIsNotEmpty() throws IOException {
        final String registry = bodyOf(
                codeOnly(read(new File(e2eeDir(), "MlsProviderTransport.java"))),
                "private java.util.List<MlsPerConversationState> " + REGISTRY);
        assertTrue("the teardown registry is empty — every durable container would be left behind",
                registry.contains("mRecords"));
        assertEquals("the exemption list has changed size; if a per-message store was added or "
                + "removed, update the reasons with it", 2,
                PER_MESSAGE_NOT_PER_CONVERSATION.length);
    }

    /**
     * A conversation field lives in exactly one home: {@code ConvState} is in-memory and resets on
     * restart, {@code MlsConversationRecord} is persisted and must not. A shared field name is the
     * checkable sign of one concept recorded in both.
     */
    @Test
    public void noFieldNameLivesInBothTheInMemoryStateAndThePersistedRecord() throws IOException {
        // ConvState is in MlsTransportTypes.
        final String convState = bodyOf(codeOnly(com.android.messaging.rcs.SourceScan.read(
                com.android.messaging.rcs.SourceScan.TRANSPORT_TYPES)),
                "public static final class ConvState");
        assertTrue("ConvState not found in MlsTransportTypes — it has been renamed or removed, "
                + "and this guard is silently passing", convState.length() > 0);

        final String recordSrc = codeOnly(read(recordFile()));
        final List<String> both = new ArrayList<>();
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(?:final\\s+)?[A-Za-z][\\w.<>,\\[\\]\\s]*?\\s([a-z]\\w*)\\s*(?:=|;)")
                .matcher(convState);
        while (m.find()) {
            final String field = m.group(1);
            if (field.length() < 4) continue;                   // loop vars, `n`, `s`
            if (recordSrc.contains("public final ") && recordSrc.contains(" " + field + ";")) {
                both.add(field);
            }
        }
        if (!both.isEmpty()) {
            fail("These names appear in BOTH MlsProviderTransport.ConvState (in-memory, resets on "
                    + "restart) and MlsConversationRecord (persisted, must not reset): " + both
                    + ". One concept in two homes is how mYieldSince/mEraYieldSince drifted — a "
                    + "yield recorded by one was invisible to the other. Decide which home is "
                    + "right and delete the other.");
        }
    }

    private static File recordFile() throws IOException {
        final String rel =
                "engine/src/com/android/messaging/rcs/engine/mls/MlsConversationRecord.java";
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            final File f = new File(c);
            if (f.isFile()) return f;
        }
        throw new IOException("MlsConversationRecord.java not found from "
                + new File(".").getAbsolutePath());
    }

    private static List<File> sourcesIn(final File dir) {
        final File[] all = dir.listFiles((d, n) -> n.endsWith(".java"));
        return all == null ? new ArrayList<>() : Arrays.asList(all);
    }

    private static String read(final File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Whether this source declares the interface, however many others it names alongside it. */
    private static boolean implementsIface(final String src) {
        return IMPLEMENTS_IFACE.matcher(codeOnly(src)).find();
    }

    /**
     * Comments and string-literal contents blanked, offsets preserved: a comment naming a store
     * must not count as registering it.
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
     * The e2ee package, resolved from the working directory so the guard runs from the module or
     * the tree root.
     */
    private static File e2eeDir() throws IOException {
        final String rel = "src/com/android/messaging/rcs/e2ee";
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            final File f = new File(c);
            if (f.isDirectory()) return f;
        }
        throw new IOException("e2ee package not found from " + new File(".").getAbsolutePath());
    }
}
