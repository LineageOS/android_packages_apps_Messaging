/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A host row must not answer for a group the engine cannot read. {@code getGroup} rebuilds a row
 * from the durable alias and record, so the guard pins the two places that pair can lie: a teardown
 * must drop every alias naming the forgotten group (tested behaviourally through
 * {@link MlsConversationRecord#aliasKeysToForget}), and {@code resolveInbound} must probe the
 * engine before it answers or adopts (a source scan, since the transport has no host test).
 * See docs/mls/transport-and-port.md.
 */
public final class MlsStaleGroupAnswerGuardTest {

    private static final String SELF = "+15715550107";
    /** Base64 of the ASCII group id, which is the form {@code putAlias} stores. */
    private static final String GID = "MDE0NDczNmFhOTY1NGM1ODhmM2RkZWVkNTRhYWM5NDA=";
    private static final String OTHER_GID = "MjJhYzc2MjhjOTdjNGNhYjg4MDlmZjhjYTQ2OTQyNTU=";

    /** The transport's canonical key, what {@code putGroup} is called with. */
    private static final String CANONICAL = "g:0144736aa9654c588f3ddeed54aac940";
    /** The app's conversation row id, what {@code scopeFor} puts in the scope. */
    private static final String BUGLE_CONV_ID = "42";

    private static Map<String, String> rows(final String... kv) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String key(final String identity, final String conversationId) {
        return MlsConversationRecord.aliasKey(identity, conversationId);
    }

    /** {@code MlsRecordStore.aliasKey} delegates here; a drift strands every stored row. */
    @Test
    public void theAliasKeyIsIdentityNulConversationId() {
        assertEquals(SELF + "\u0000" + CANONICAL, key(SELF, CANONICAL));
        assertEquals("\u0000" + CANONICAL, key(null, CANONICAL));
    }

    /** The row was written under the canonical key and the teardown holds the app's id. */
    @Test
    public void theRowIsFoundByItsGroupIdWhenTheKeyIsFromAnotherKeySpace() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        final List<String> out =
                MlsConversationRecord.aliasKeysToForget(all, SELF, GID, BUGLE_CONV_ID);
        assertEquals("the alias naming the forgotten group must go whatever key it sits under",
                1, out.size());
        assertEquals(key(SELF, CANONICAL), out.get(0));
    }

    /** Removal by conversation-id key alone finds nothing: the state the test above must beat. */
    @Test
    public void removalByTheConversationIdKeyAloneFindsNothing() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue(
                        "this is what MlsRecordStore.forgetConversation did, and it is why a DEEP forget "
                        + "left alias rows standing",
                MlsConversationRecord.aliasKeysToForget(all, SELF, null, BUGLE_CONV_ID).isEmpty());
    }

    /** A conversation reachable under two keys names one group, and both rows go. */
    @Test
    public void everyRowNamingTheGroupGoes() {
        final Map<String, String> all = rows(
                key(SELF, CANONICAL), GID,
                key(SELF, BUGLE_CONV_ID), GID,
                key(SELF, "p:+15715550103"), OTHER_GID);
        final List<String> out =
                MlsConversationRecord.aliasKeysToForget(all, SELF, GID, BUGLE_CONV_ID, CANONICAL);
        assertEquals(2, out.size());
        assertTrue(out.contains(key(SELF, CANONICAL)));
        assertTrue(out.contains(key(SELF, BUGLE_CONV_ID)));
    }

    /** Another identity's row for the same group is a different MLS group in another store. */
    @Test
    public void anotherIdentityIsNeverTouched() {
        final Map<String, String> all = rows(
                key("+12025550100", CANONICAL), GID,
                key(SELF, CANONICAL), GID);
        final List<String> out = MlsConversationRecord.aliasKeysToForget(all, SELF, GID);
        assertEquals(1, out.size());
        assertEquals(key(SELF, CANONICAL), out.get(0));
    }

    /** The identity is a whole field delimited by NUL, not a string prefix. */
    @Test
    public void aShorterIdentityIsNotAPrefixMatch() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue(MlsConversationRecord.aliasKeysToForget(all, "+1571", GID).isEmpty());
    }

    /** A row naming a different group survives: the teardown is scoped to one conversation. */
    @Test
    public void anotherGroupIsNeverTouched() {
        final Map<String, String> all = rows(key(SELF, "g:other"), OTHER_GID);
        assertTrue(MlsConversationRecord.aliasKeysToForget(all, SELF, GID, CANONICAL).isEmpty());
    }

    /** With no group id both candidate keys are tried: the teardown cannot know the writer's. */
    @Test
    public void withNoGroupIdItFallsBackToBothCandidateKeys() {
        final Map<String, String> all = rows(
                key(SELF, CANONICAL), GID,
                key(SELF, BUGLE_CONV_ID), GID);
        final List<String> out =
                MlsConversationRecord.aliasKeysToForget(all, SELF, null, BUGLE_CONV_ID, CANONICAL);
        assertEquals(2, out.size());
    }

    /** The result counts rows dropped, not removals attempted. */
    @Test
    public void absentKeysAreNotCounted() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue(MlsConversationRecord.aliasKeysToForget(all, SELF, null, "no-such-conversation")
                .isEmpty());
        assertTrue(MlsConversationRecord.aliasKeysToForget(
                new LinkedHashMap<String, String>(), SELF, GID, CANONICAL).isEmpty());
    }

    @Test
    public void nullsAreAnAbsentInputAndNotACrash() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue(MlsConversationRecord.aliasKeysToForget(all, SELF, null, (String) null)
                .isEmpty());
        assertTrue(MlsConversationRecord.aliasKeysToForget(null, SELF, GID, CANONICAL).isEmpty());
        assertEquals(1,
                MlsConversationRecord.aliasKeysToForget(all, SELF, GID, (String[]) null).size());
    }

    private static final String STORE = "src/com/android/messaging/rcs/e2ee/MlsRecordStore.java";

    private static String storeSource() throws Exception {
        return SourceScan.codeOnly(SourceScan.read(STORE));
    }

    /**
     * @return null when {@code MlsRecordStore}'s teardown chooses rows by group id, else the fault.
     */
    static String storeFault(final String src) {
        final String forget = SourceScan.bodyOf(src, "forgetConversation");
        if (forget.length() < 40) {
            return "forgetConversation was not found in " + STORE
                    + " — a guard that cannot see its "
                    + "subject must fail rather than pass for the fraction it still matches.";
        }
        if (!forget.contains("removeAliasesFor(")) {
            return "forgetConversation does not call removeAliasesFor(. It is handed a Scope whose "
                    + "conversationId is the app's own conversation row id, while putGroup wrote "
                    + "the alias under the transport's CANONICAL key — so a removal keyed on that "
                    + "field cannot match a row, just as mConvAlias.remove(key) alone cannot, "
                    + "and the alias rows are left standing.";
        }
        if (forget.contains("removeAlias(")) {
            return "forgetConversation still calls the single-key removeAlias(. That method is "
                    + "correct only for a caller that KNOWS which key putGroup used — "
                    + "rollBackAdoption does, the teardown does not.";
        }
        final String byGroup = SourceScan.bodyOf(src, "removeAliasesFor");
        if (!byGroup.contains("MlsConversationRecord.aliasKeysToForget(")) {
            return "removeAliasesFor does not ask MlsConversationRecord.aliasKeysToForget(, so the "
                    + "selection this file's tests exercise is not the selection it runs.";
        }
        return null;
    }

    @Test
    public void theRecordStoreTeardownChoosesRowsByGroupId() throws Exception {
        final String fault = storeFault(storeSource());
        if (fault != null) fail(fault);
    }

    private static final String PROBE = "groupLoads(";
    /** The group arm's refusal: a group with no state is a join we have not performed. */
    private static final String GROUP_ARM = "if (rcsGroupId != null && !rcsGroupId.isEmpty())";
    /** The adoption, which must not run on an answer the probe refused. */
    private static final String ADOPT = "putGroup(key, adopted);";

    private static String resolveBody() throws Exception {
        // The unsplit view: resolveInbound lives in MlsGroupState.
        final String body = SourceScan.bodyOf(SourceScan.transportUnsplitCode(), "resolveInbound");
        assertTrue("resolveInbound was not found in " + SourceScan.TRANSPORT + " — if it was "
                + "renamed this guard must follow it rather than silently pass",
                body.length() > 400);
        return body;
    }

    /**
     * @return null when {@code resolveInbound} probes the engine before it answers from the host
     *     store and before it adopts, else the first violation found.
     */
    static String resolveFault(final String body) {
        final List<Integer> probes = SourceScan.indicesOf(body, PROBE);
        if (probes.size() < 2) {
            return "resolveInbound calls " + PROBE + " " + probes.size()
                    + " time(s); it needs TWO. "
                    + "One before it answers from the host store — getGroup() rebuilds a Group from "
                    + "the durable record, so it answers for a group whose engine state is gone — "
                    + "and one before it ADOPTS the provider's group id, because eraEpoch() can "
                    + "report an era for a group the engine cannot load (and ensureReady "
                    + "carries both).";
        }
        final int shortCircuit = body.indexOf("return key;");
        if (shortCircuit < 0) {
            return "resolveInbound no longer returns the key, so this guard's whole subject — WHAT "
                    + "it answers before probing — cannot be located.";
        }
        if (probes.get(0).intValue() > shortCircuit) {
            return "resolveInbound returns the conversation key BEFORE its first " + PROBE + ". "
                    + "That is the short-circuit itself: applyInboundControl reaches joinFromWelcome "
                    + "only on a null, so answering for an unreadable group cancels the one repair a "
                    + "member holding no state has (door #2).";
        }
        final int groupArm = body.indexOf(GROUP_ARM);
        if (groupArm < 0) {
            return "resolveInbound no longer refuses a GROUP outright with `" + GROUP_ARM + "`. "
                    + "Without it a group whose state we cannot read falls into the 1:1 adoption "
                    + "below and is answered for under the PEER's group — the misroute this exists "
                    + "to end, and the null return with it.";
        }
        if (groupArm < probes.get(0).intValue()) {
            return "resolveInbound refuses the GROUP arm before it has probed, so the probe cannot "
                    + "affect the answer for the case it was added for.";
        }
        final List<Integer> adopts = SourceScan.indicesOf(body, ADOPT);
        if (adopts.size() != 1) {
            return "resolveInbound contains " + adopts.size() + " occurrences of `" + ADOPT
                    + "`; this guard asserts WHERE that statement sits and needs exactly one.";
        }
        if (adopts.get(0).intValue() < probes.get(probes.size() - 1).intValue()) {
            return "resolveInbound ADOPTS before its last " + PROBE + ". A probe whose answer "
                    + "arrives after the adoption cannot refuse it, and re-adopting on a failed "
                    + "probe is the one implementation that benefit does not survive.";
        }
        return null;
    }

    @Test
    public void resolveInboundProbesBeforeItAnswersAndBeforeItAdopts() throws Exception {
        final String fault = resolveFault(resolveBody());
        if (fault != null) fail(fault);
    }

    /** Falsifier: the real bodies, broken one way at a time, must each be caught. */
    @Test
    public void theGuardCanFail() throws Exception {
        final String resolve = resolveBody();
        final String store = storeSource();
        assertNull("the real resolveInbound must pass before any mutation means anything",
                resolveFault(resolve));
        assertNull("the real MlsRecordStore must pass before any mutation means anything",
                storeFault(store));

        final List<String[]> mutations = new ArrayList<>();
        // Drop the first probe: answer straight from the host store.
        mutations.add(new String[] {"resolveInbound", resolve,
            "held.groupId == null || groupLoads(held.groupId)", "held.groupId != null"});
        // Drop the second probe: adopt on a readable era for a group that does not load.
        mutations.add(new String[] {"resolveInbound", resolve,
            "if (era < 0 || !groupLoads(gid)) {", "if (era < 0) {"});
        // Keep both probes and answer above them anyway.
        mutations.add(new String[] {"resolveInbound", resolve,
            "if (held.groupId == null || groupLoads(held.groupId)) return key;",
            "if (held != null) return key;\n            if (groupLoads(held.groupId)) { }"});
        // Move the probe below the adoption, where it can no longer refuse one.
        mutations.add(new String[] {"resolveInbound", resolve,
            ADOPT, ADOPT + "\n            if (!groupLoads(gid)) { }"});
        // Adopt nowhere this guard can see it.
        mutations.add(new String[] {"resolveInbound", resolve, ADOPT, "adoptSomehow();"});
        // Let a group fall through into the 1:1 adoption.
        mutations.add(new String[] {"resolveInbound", resolve,
            GROUP_ARM, "if (false)"});
        // The single-key removal, which cannot match.
        mutations.add(new String[] {"MlsRecordStore", store,
            "removeAliasesFor(scope.identity, scope.groupId, byConvId, byCanonicalKey);",
            "removeAlias(scope.identity, byConvId);"});
        // Keep the method, drop the selection it exists to run.
        mutations.add(new String[] {"MlsRecordStore", store,
            "MlsConversationRecord.aliasKeysToForget(", "noSuchSelection("});

        for (final String[] m : mutations) {
            final String subject = m[0];
            final String body = m[1];
            final String from = m[2];
            final String to = m[3];
            assertEquals("the mutation anchor `" + from + "` must appear EXACTLY once in " + subject
                    + " — a mutation that does not land proves nothing",
                    1, SourceScan.count(body, from));
            final String broken = body.replace(from, to);
            final String fault = "resolveInbound".equals(subject)
                    ? resolveFault(broken) : storeFault(broken);
            if (fault == null) {
                fail("breaking " + subject + " by replacing `" + from + "` with `" + to + "` left "
                        + "this guard GREEN. It cannot distinguish the property from its absence.");
            }
        }
    }
}
