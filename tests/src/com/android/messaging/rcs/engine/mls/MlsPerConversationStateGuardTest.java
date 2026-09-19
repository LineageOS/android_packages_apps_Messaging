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
 * Every DURABLE per-conversation container must be in the teardown registry.
 *
 * <h2>Why a second guard, when a teardown guard already existed</h2>
 *
 * <p>{@code MlsConversationTeardownGuardTest} guards the in-memory maps, and it passed for months
 * while {@code forget()} left five durable containers untouched: the persisted
 * {@code MlsConversationRecord}, its conversation→group alias, the parked-inbound queue, the
 * re-upgrade backoff columns and the resend ladder. It could not have caught them — it reads field
 * declarations in ONE file, and a store is a separate class with its own preferences file or table.
 *
 * <p>The gap mattered more than the one it was written for. In-memory maps are cleared by process
 * death, so the inherited-state bug they cause survives at most until the next restart. A persisted
 * record survives a reboot: a conversation could be forgotten, re-joined, and still refused a
 * self-heal because its budget was spent days earlier under a group that no longer exists.
 *
 * <p>So the enumerable set is the fix. A durable container declares itself by implementing
 * {@code MlsPerConversationState}, and this test fails if it is not in
 * {@code MlsProviderTransport.perConversationStores()}.
 *
 * <p><b>If this test fails</b> you have added a per-conversation store. Register it there — or, if
 * it is really per-MESSAGE state, do not implement the interface and add it to
 * {@link #PER_MESSAGE_NOT_PER_CONVERSATION} with the reason.
 */
public final class MlsPerConversationStateGuardTest {

    private static final String IFACE = "MlsPerConversationState";
    private static final String REGISTRY = "perConversationStores";

    /**
     * "This class implements the interface", as a property rather than as a spelling.
     *
     * <p>{@code contains("implements MlsPerConversationState")} is correct for every implementer in
     * the package today and unsound as a rule: {@code implements Closeable, MlsPerConversationState}
     * is the same declaration and the literal cannot see it. A store that grew a second interface
     * would have dropped out of {@link #everyDurablePerConversationStoreIsInTheTeardownRegistry}'s
     * enumeration entirely — and a store missing from the enumeration is not a failure, it is a
     * store nobody asks about, which is the whole shape this guard exists to close one level down.
     */
    private static final Pattern IMPLEMENTS_IFACE = Pattern.compile(
            "\\bimplements\\s[^{;]*\\b" + IFACE + "\\b");

    /**
     * Stores keyed by {@code rcsMessageId}, with the reason each is NOT conversation state.
     *
     * <p>This list is an assertion, not a comment: the test checks that none of these implements the
     * interface, so "it is per-message" cannot quietly stop being true.
     */
    private static final String[][] PER_MESSAGE_NOT_PER_CONVERSATION = {
        // MlsCiphertextCache WAS here, on the grounds that it is keyed by rcsMessageId. It is now
        // registered for teardown instead. The key is still per-message; the exemption
        // was wrong because the VALIDITY of each entry is scoped to a conversation — once that
        // conversation is forgotten the group its bytes were sealed against is gone and no resend
        // can replay them, so they are debris occupying a bounded ceiling. Removing an exemption is
        // the outcome this list is supposed to make possible, not a failure of it.
        {"MlsPendingBodyStore",
            "keyed by rcsMessageId; the body is released once the message is sent or abandoned"},
        {"MlsRendezvousStore",
            "keyed by (identity, remoteUserId, rcsMessageId, stage) and emptied per message at "
            + "chat-row insert. Retaining rows across a forget is CORRECT, not a leak: the table "
            + "answers 'did we already surface this message', and a peer's resend carries a NEW "
            + "message id, so a retained row can only prevent a real duplicate"},
    };

    /**
     * Durable PER-CONVERSATION state that must deliberately SURVIVE teardown, with the reason.
     *
     * <p>The exemption above is "this is not conversation state". This one is different and rarer:
     * it IS conversation state, it IS durable, and clearing it on forget would break the thing it
     * exists to do. Recorded as an assertion for the same reason as the other list — the argument is
     * subtle enough that a future reader tidying up "the store that forgot to register itself" would
     * be doing the obviously-right thing and would silently reintroduce a P0.
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
     * The must-survive stores must NOT be registered for teardown.
     *
     * <p>Deliberately structural rather than behavioural: registering happens by implementing the
     * interface, so checking the interface is checking the thing that would actually cause the bug.
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
            // ...AND THAT IT HAS CONTENT. isFile() above is true for a zero-byte file, so with the
            // sources emptied implementsIface() answered false and this exemption certified green
            // having read nothing.
            assertTrue(row[0] + " is present but declares no class — this check would be reading an "
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
        assertTrue(REGISTRY + "() not found in MlsProviderTransport — the registry has been renamed "
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

        assertTrue("no class in the e2ee package implements " + IFACE + " — either the interface has "
                + "been renamed or this guard's scan has gone stale and is silently passing, which is "
                + "worse than failing", implementers.size() > 0);
        if (!missing.isEmpty()) {
            fail("These classes implement " + IFACE + " but are NOT in MlsProviderTransport."
                    + REGISTRY + "(), so forget() leaves their state behind and a re-joined "
                    + "conversation inherits it — durably, across reboots: " + missing);
        }
    }

    /**
     * The per-message stores must NOT be conversation state.
     *
     * <p>Stated as a test because the alternative is a comment, and a comment cannot notice when
     * someone re-keys one of these by conversation and leaves the reasoning behind.
     */
    @Test
    public void perMessageStoresDoNotClaimToBePerConversation() throws IOException {
        final File dir = e2eeDir();
        for (final String[] row : PER_MESSAGE_NOT_PER_CONVERSATION) {
            final File f = new File(dir, row[0] + ".java");
            assertTrue(row[0] + " no longer exists — remove it from this list, or the exemption is "
                    + "guarding nothing", f.isFile());
            // ...AND THAT IT HAS CONTENT. isFile() above is true for a zero-byte file, so with the
            // sources emptied implementsIface() answered false and this exemption certified green
            // having read nothing.
            assertTrue(row[0] + " is present but declares no class — this check would be reading an "
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
     * Every implementation must be able to run inside a teardown that tolerates one of them
     * failing: the loop catches {@code RuntimeException}, so an implementation that declares a
     * CHECKED exception would not compile against it, and one that returns early on a null scope
     * field must do so by returning 0 rather than throwing.
     *
     * <p>Checked structurally: each implementation's {@code forgetConversation} must guard the scope
     * fields it reads. A store that dereferences {@code scope.groupId} without a null check would
     * throw for a conversation the engine never built a group for — which is the ordinary case for
     * the very conversations most likely to be forgotten.
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

    /** The registry must not be trivially satisfiable by an empty list. */
    @Test
    public void theRegistryIsNotEmpty() throws IOException {
        final String registry = bodyOf(codeOnly(read(new File(e2eeDir(), "MlsProviderTransport.java"))),
                "private java.util.List<MlsPerConversationState> " + REGISTRY);
        assertTrue("the teardown registry is empty — every durable container would be left behind",
                registry.contains("mRecords"));
        assertEquals("the exemption list has changed size; if a per-message store was added or "
                + "removed, update the reasons with it", 2, PER_MESSAGE_NOT_PER_CONVERSATION.length);
    }

    /**
     * A conversation field lives in exactly one of the two homes — never both.
     *
     * <h2>The mistake this catches</h2>
     *
     * <p>There are two per-conversation homes and they mean opposite things.
     * {@code MlsProviderTransport.ConvState} is IN-MEMORY: everything on it should reset on process
     * restart, because it describes work in flight. {@code MlsConversationRecord} is PERSISTED:
     * everything on it must NOT reset, because a restart is not a reason to forget that a group is
     * unhealthy or that a self-heal budget is spent.
     *
     * <p>Both directions of the mistake have already happened here. The §10.3 FTD cap was in memory
     * and reset on every restart, so a permanently-undecryptable message earned an unlimited chain
     * of reports to the peer — the exact chain the cap exists to stop. And the class once carried
     * {@code mYieldSince} AND {@code mEraYieldSince}: one concept, two containers, two timeout
     * constants, two decision sites, and a yield recorded by one was invisible to the other.
     *
     * <p>A shared field NAME is the cheap, reliable signal for that second failure. It cannot prove
     * a field is in the right home — that is a judgement — but "the same thing is recorded in both"
     * is mechanically checkable, and it is the shape that produced the duplicate.
     */
    @Test
    public void noFieldNameLivesInBothTheInMemoryStateAndThePersistedRecord() throws IOException {
        final String convState = bodyOf(codeOnly(read(new File(e2eeDir(),
                "MlsProviderTransport.java"))), "private static final class ConvState");
        assertTrue("ConvState not found in MlsProviderTransport — it has been renamed or removed, "
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

    // ---- source access -------------------------------------------------------------------------

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

    /** Does this source declare the interface, however many others it names alongside it? */
    private static boolean implementsIface(final String src) {
        return IMPLEMENTS_IFACE.matcher(codeOnly(src)).find();
    }

    /**
     * Comments and string-literal contents blanked, offsets preserved. Same helper as
     * {@code MlsGuardPersistenceTest.codeOnly}.
     *
     * <p>Needed in both directions here. A javadoc reading "implements MlsPerConversationState"
     * would enlist a class that does not (loud, but wrong), and — the silent one — a COMMENT inside
     * {@code perConversationStores()} naming a store satisfied "it is registered for teardown"
     * while the registry never mentioned it. {@code MlsProviderTransport} documents this registry
     * at length, so that was one edit away.
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
     * Locate the e2ee package, resolved from the working directory so the guard runs the same way
     * from the module dir and from the tree root.
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
