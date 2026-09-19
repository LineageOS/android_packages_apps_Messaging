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
 * <b>A HOST ROW MUST NOT ANSWER FOR A GROUP THE ENGINE CANNOT READ.</b>
 *
 * <h2>The two halves, and why they are one test</h2>
 *
 * <p>{@code getGroup(conversationId)} is a two-stage read: the {@code mGroups} row, and on a miss a
 * REBUILD from the durable {@link MlsConversationRecord} reached through the conversation→group
 * alias. So the in-memory row is a CACHE, and a bare {@code mGroups.remove(key)} is an eviction the
 * next read undoes. What actually decides whether the host answers is the durable pair — the alias
 * and the record — and this guard pins the two places that pair can lie:
 *
 * <ol>
 *   <li><b>The alias must not outlive its record.</b> A teardown that drops the record and leaves
 *       the alias leaves a row pointing a conversation at a group id that has been deleted
 *       underneath it.</li>
 *   <li><b>{@code resolveInbound} must probe before it answers.</b> It is the reader that acts on
 *       the answer hardest: {@code applyInboundControl} reaches {@code joinFromWelcome} only when
 *       it returns null, so a host row that answers for an unreadable group cancels the one repair
 *       available to a member holding no state (door #2).</li>
 * </ol>
 *
 * <h2>What was measured, 2026-09-12, {@code deviceB}</h2>
 *
 * <p>22 alias rows against 44 engine groups in the LIVE {@code g_<hex>.bin} layout. Four rows name a
 * group with no record and no engine state:
 * {@code 0144736aa965…}, {@code 22ac7628c97c…}, {@code 70ec29ffedbd…}, {@code 976623bc50aa…}. Three
 * are test fixtures, which a {@code FORGETGROUP(…) → app=true provider=true (DEEP: both
 * halves dropped)} had reported as gone; the fourth is the real group {@code secondfresh}. The set
 * {@code alias ∧ record ∧ ¬engine} was EMPTY, which is why half 2 is a source guard over a state
 * this device does not currently hold rather than a claim that it does.
 *
 * <p>(The legacy {@code s_<hex>.bin} layout is {@code state_path} and intersects the alias table in
 * ZERO places. Zero overlap is the tell that the wrong layout was read, not a finding.)
 *
 * <h2>Why half 1 is behavioural and half 2 is a source scan</h2>
 *
 * <p>Half 1's decision — WHICH alias rows a teardown drops — is a pure function of the stored map,
 * so it lives in {@link MlsConversationRecord} beside the record's own key and is tested here for
 * real. Half 2 is an ORDERING inside {@code MlsProviderTransport}, which needs a {@code Context} and
 * a bound provider and has no host test at all; same call as
 * {@link MlsVerifyBeforeAdoptGuardTest}. {@link #theGuardCanFail} is the negative control that keeps
 * the source half from being a check whose output is the same either way.
 */
public final class MlsStaleGroupAnswerGuardTest {

    private static final String SELF = "+15715550107";
    /** Base64 of the ASCII group id, which is the form {@code putAlias} stores. */
    private static final String GID = "MDE0NDczNmFhOTY1NGM1ODhmM2RkZWVkNTRhYWM5NDA=";
    private static final String OTHER_GID = "MjJhYzc2MjhjOTdjNGNhYjg4MDlmZjhjYTQ2OTQyNTU=";

    /** The transport's canonical key — what {@code putGroup} is actually called with. */
    private static final String CANONICAL = "g:0144736aa9654c588f3ddeed54aac940";
    /** The app's own conversation row id — what {@code scopeFor} puts in the scope. */
    private static final String BUGLE_CONV_ID = "42";

    private static Map<String, String> rows(final String... kv) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static String key(final String identity, final String conversationId) {
        return MlsConversationRecord.aliasKey(identity, conversationId);
    }

    // ---- half 1: the durable alias -------------------------------------------------------------

    /**
     * The separator is the one the store writes, pinned rather than assumed.
     *
     * <p>{@code MlsRecordStore.aliasKey} now delegates here. If that shape ever drifts, every row
     * already on a device becomes unreachable AND unremovable in one move, so it is worth one
     * assertion of its own.
     */
    @Test
    public void theAliasKeyIsIdentityNulConversationId() {
        assertEquals(SELF + "\u0000" + CANONICAL, key(SELF, CANONICAL));
        assertEquals("\u0000" + CANONICAL, key(null, CANONICAL));
    }

    /**
     * THE DEFECT, as the device holds it: the row was written under the CANONICAL key and the
     * teardown was handed the app's conversation id.
     *
     * <p>With removal by key alone this finds nothing — which is exactly what
     * {@code MlsRecordStore.forgetConversation} did for five weeks while reporting that it had
     * dropped a row.
     */
    @Test
    public void theRowIsFoundByItsGroupIdWhenTheKeyIsFromAnotherKeySpace() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        final List<String> out =
                MlsConversationRecord.aliasKeysToForget(all, SELF, GID, BUGLE_CONV_ID);
        assertEquals("the alias naming the forgotten group must go whatever key it sits under",
                1, out.size());
        assertEquals(key(SELF, CANONICAL), out.get(0));
    }

    /**
     * THE OLD RULE, run rather than described: removal by the conversation-id KEY alone finds
     * nothing on exactly the row {@code 010T} holds.
     *
     * <p>This is the state that makes the assertion above capable of failing. Without it, "the fix
     * changes something" is an argument; with it, it is a measurement in the same suite.
     */
    @Test
    public void removalByTheConversationIdKeyAloneFindsNothing() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue("this is what MlsRecordStore.forgetConversation did, and it is why a DEEP forget "
                        + "left four alias rows standing on 010T",
                MlsConversationRecord.aliasKeysToForget(all, SELF, null, BUGLE_CONV_ID).isEmpty());
    }

    /** A conversation reachable under two keys names ONE group, and both rows are dead. */
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

    /** Another identity's row for the same group is a DIFFERENT MLS group in another store. */
    @Test
    public void anotherIdentityIsNeverTouched() {
        final Map<String, String> all = rows(
                key("+12025550100", CANONICAL), GID,
                key(SELF, CANONICAL), GID);
        final List<String> out = MlsConversationRecord.aliasKeysToForget(all, SELF, GID);
        assertEquals(1, out.size());
        assertEquals(key(SELF, CANONICAL), out.get(0));
    }

    /**
     * The identity prefix is a whole field, not a string prefix.
     *
     * <p>{@code +1571} is a prefix of {@code +15715550107} as text and is a different line as a
     * subscriber. The NUL is what separates them and this asserts it does.
     */
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

    /**
     * With no group id the keys are all there is — and BOTH candidates are offered, because which
     * space the writer used is the thing the teardown demonstrably cannot be trusted to know.
     */
    @Test
    public void withNoGroupIdItFallsBackToBothCandidateKeys() {
        final Map<String, String> all = rows(
                key(SELF, CANONICAL), GID,
                key(SELF, BUGLE_CONV_ID), GID);
        final List<String> out =
                MlsConversationRecord.aliasKeysToForget(all, SELF, null, BUGLE_CONV_ID, CANONICAL);
        assertEquals(2, out.size());
    }

    /**
     * The count is ROWS DROPPED, not removals attempted.
     *
     * <p>{@code clearConversationState} prints it as evidence — "we called five teardowns" and "five
     * containers had something to drop" are different claims, and only the second says the scope was
     * addressable.
     */
    @Test
    public void absentKeysAreNotCounted() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue(MlsConversationRecord.aliasKeysToForget(all, SELF, null, "no-such-conversation")
                .isEmpty());
        assertTrue(MlsConversationRecord.aliasKeysToForget(
                new LinkedHashMap<String, String>(), SELF, GID, CANONICAL).isEmpty());
    }

    /** Null-tolerant: a scope may carry no group id, no conversation id, or neither. */
    @Test
    public void nullsAreAnAbsentInputAndNotACrash() {
        final Map<String, String> all = rows(key(SELF, CANONICAL), GID);
        assertTrue(MlsConversationRecord.aliasKeysToForget(all, SELF, null, (String) null)
                .isEmpty());
        assertTrue(MlsConversationRecord.aliasKeysToForget(null, SELF, GID, CANONICAL).isEmpty());
        assertEquals(1,
                MlsConversationRecord.aliasKeysToForget(all, SELF, GID, (String[]) null).size());
    }

    // ---- half 1b: and the store must USE it ----------------------------------------------------

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
            return "forgetConversation was not found in " + STORE + " — a guard that cannot see its "
                    + "subject must fail rather than pass for the fraction it still matches.";
        }
        if (!forget.contains("removeAliasesFor(")) {
            return "forgetConversation does not call removeAliasesFor(. It is handed a Scope whose "
                    + "conversationId is the app's own conversation row id, while putGroup wrote "
                    + "the alias under the transport's CANONICAL key — so a removal keyed on that "
                    + "field cannot match a row, exactly as mConvAlias.remove(key) could not before "
                    + "2026-08-05. Four such rows were still on 010T on 2026-09-12.";
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

    // ---- half 2: resolveInbound must probe before it answers -----------------------------------

    private static final String PROBE = "groupLoads(";
    /** The group arm's refusal — a GROUP with no state is a join we have not performed. */
    private static final String GROUP_ARM = "if (rcsGroupId != null && !rcsGroupId.isEmpty())";
    /** The adoption, which must never run on an answer the probe refused. */
    private static final String ADOPT = "putGroup(key, adopted);";

    private static String resolveBody() throws Exception {
        final String body = SourceScan.bodyOf(SourceScan.transport(), "resolveInbound");
        assertTrue("resolveInbound was not found in " + SourceScan.TRANSPORT + " — if it was "
                + "renamed this guard must follow it rather than silently pass", body.length() > 400);
        return body;
    }

    /**
     * @return null when {@code resolveInbound} probes the engine before it answers from the host
     *     store and before it adopts, else the first violation found.
     */
    static String resolveFault(final String body) {
        final List<Integer> probes = SourceScan.indicesOf(body, PROBE);
        if (probes.size() < 2) {
            return "resolveInbound calls " + PROBE + " " + probes.size() + " time(s); it needs TWO. "
                    + "One before it answers from the host store — getGroup() rebuilds a Group from "
                    + "the durable record, so it answers for a group whose engine state is gone — "
                    + "and one before it ADOPTS the provider's group id, because eraEpoch() can "
                    + "report an era for a group the engine cannot load (and ensureReady "
                    + "has carried both since 2026-08-02).";
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

    // ---- the negative control ------------------------------------------------------------------

    /**
     * Every assertion above, run against the REAL bodies broken one way at a time.
     *
     * <p>Without this the guard is a check whose output is the same whether or not the property
     * holds — the defect class this guard is an instance of, one layer up in the instrument.
     */
    @Test
    public void theGuardCanFail() throws Exception {
        final String resolve = resolveBody();
        final String store = storeSource();
        assertNull("the real resolveInbound must pass before any mutation means anything",
                resolveFault(resolve));
        assertNull("the real MlsRecordStore must pass before any mutation means anything",
                storeFault(store));

        final List<String[]> mutations = new ArrayList<>();
        // Kill the first probe: back to answering straight from the host store.
        mutations.add(new String[] {"resolveInbound", resolve,
            "held.groupId == null || groupLoads(held.groupId)", "held.groupId != null"});
        // Kill the second probe: adopt on an era that is readable for a group that is not loadable.
        mutations.add(new String[] {"resolveInbound", resolve,
            "if (era < 0 || !groupLoads(gid)) {", "if (era < 0) {"});
        // Keep BOTH probes and answer above them anyway — the short-circuit restored in place.
        mutations.add(new String[] {"resolveInbound", resolve,
            "if (held.groupId == null || groupLoads(held.groupId)) return key;",
            "if (held != null) return key;\n            if (groupLoads(held.groupId)) { }"});
        // Keep the probe and move it BELOW the adoption, where it can no longer refuse one.
        mutations.add(new String[] {"resolveInbound", resolve,
            ADOPT, ADOPT + "\n            if (!groupLoads(gid)) { }"});
        // Adopt nowhere this guard can see it.
        mutations.add(new String[] {"resolveInbound", resolve, ADOPT, "adoptSomehow();"});
        // Let a GROUP fall through into the 1:1 adoption.
        mutations.add(new String[] {"resolveInbound", resolve,
            GROUP_ARM, "if (false)"});
        // Back to the single-key removal that could never match.
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
