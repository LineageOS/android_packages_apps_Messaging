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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The guards' counters are on disk, aged by a clock nobody can move, and a person can still
 * clear them</b>.
 *
 * <h2>Why a SOURCE guard for this half</h2>
 *
 * <p>{@link MlsEraBudgetRecordTest} and {@link MlsPeerHealthRecordTest} pin the two properties on
 * the records themselves: a charge survives the encode/decode boundary, and no clock reading refills
 * a spent budget. What they cannot reach is the WIRING — {@code MlsPeerGuard} needs a {@code Context}
 * and system properties, so it is not on the host classpath, and the failure this exists for is
 * precisely a counter that is correct in isolation and never written down.
 *
 * <p>The three things asserted here are the three ways that wiring can be undone without anything
 * else failing: charging into a field instead of the store, reaching for the wall clock because it
 * is the obvious clock, and leaving the operator reset pointed at memory while the record that is
 * actually refusing the repair sits on disk. The last one is the trap: it looks fine in review and a
 * person presses a button that does nothing.
 */
public final class MlsGuardPersistenceTest {

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /**
     * The three needles below are PATTERNS rather than literal substrings, because each of them was
     * a spelling of the property rather than the property.
     *
     * <ul>
     *   <li>{@code .apply()} / {@code .commit()} — whitespace inside the call is legal Java and a
     *       reformat is not a decision. The forbidden one matters most: an {@code assertFalse} that
     *       a respelling defeats passes SILENTLY while the code still schedules the write.</li>
     *   <li>{@code implements MlsPerConversationState} — the literal cannot see
     *       {@code implements Something, MlsPerConversationState}. Every implementer in the package
     *       happens to name one interface today, so adding a second would have taken a class out of
     *       this check (and out of {@code MlsPerConversationStateGuardTest}'s registry check with
     *       it) without anything going red.</li>
     * </ul>
     */
    private static final Pattern APPLY = Pattern.compile("\\.\\s*apply\\s*\\(\\s*\\)");
    private static final Pattern COMMIT = Pattern.compile("\\.\\s*commit\\s*\\(\\s*\\)");
    private static final Pattern IMPLEMENTS_PER_CONVERSATION = Pattern.compile(
            "\\bimplements\\s[^{;]*\\bMlsPerConversationState\\b");

    /**
     * <p><b>Re-pointed at {@code decide} by Stage 2.</b> The read-modify-write moved out
     * of {@code allowEraAdvance} into the one decision path shared by all four tiers; the entry
     * point is now a single delegating line, so asserting on ITS body would have asserted the
     * property of a delegate. The property is unchanged and so is the store — decision D1(c), which
     * would have moved the store behind an engine port, was rejected precisely to leave this
     * alone. What is no longer asserted here is the budget KEY derivation, because it is no longer
     * unreachable: {@code MlsStateChangeGate.eraBudgetKey} is host-tested directly in
     * {@link MlsStateChangeGateTest#aOneToOneIsChargedToThePeer}, which is strictly better than a
     * needle saying the method was called.
     */
    @Test
    public void theEraBudgetIsChargedToAPersistentStore() throws IOException {
        final String guard = readGuard();
        assertTrue("MlsPeerGuard no longer opens a SharedPreferences file — the budgets are back in "
                + "memory, and the loop they bound is the one that restarts the process",
                guard.contains("getSharedPreferences("));
        final String body = bodyOf(guard, "decide", "final Tier tier");
        assertTrue("MlsPeerGuard.decide is gone — nothing gathers the guards' facts",
                body.length() > 0);
        assertTrue("the decision path does not READ the stored record, so it is counting something "
                + "this process happens to remember", body.contains("load("));
        assertTrue("the decision path does not WRITE the charge back, so a restart forgets it — "
                + "which is the whole point", body.contains("store("));
        assertTrue("the decision path no longer charges MlsEraBudgetRecord.charged(), so whatever "
                + "it writes is not an era-budget charge", body.contains(".charged("));
        assertTrue("the era budget key is no longer derived by MlsStateChangeGate.eraBudgetKey. A "
                + "second derivation here is how a 1:1 once fell through the guard: the group "
                + "arm and the peer arm must be one function, and it must be the host-tested one.",
                body.contains("MlsStateChangeGate.eraBudgetKey("));
    }

    /**
     * The charge must be on disk before the operation it pays for begins.
     *
     * <p>{@code apply()} returns as soon as the value is in memory and flushes on a background
     * thread. That survives an orderly process death and not a crash — and a reproducible crash on
     * the rebuild path is exactly the loop being bounded, so the one case the write must survive is
     * the one {@code apply()} does not promise.
     */
    @Test
    public void everyWriteIsCommittedSynchronously() throws IOException {
        final String guard = readGuard();
        assertFalse("MlsPeerGuard writes a budget with apply(). The loop this budget bounds KILLS "
                + "THE PROCESS, so a write that is only scheduled is a charge that can be lost "
                + "exactly when it matters — use commit().", APPLY.matcher(guard).find());
        assertTrue("no commit() anywhere: nothing is being written at all",
                COMMIT.matcher(guard).find());
    }

    /**
     * A durable budget aged by the wall clock is refilled by any large enough clock jump, silently.
     * The same question was settled one level down for epoch secrets: a clock that
     * moved must not expire something early.
     */
    @Test
    public void theBudgetsAreAgedByTheMonotonicClock() throws IOException {
        final String guard = readGuard();
        assertFalse("MlsPeerGuard reads System.currentTimeMillis(). A charge stamped with the wall "
                + "clock ages out on any jump forward — an NTP correction after a boot with a dead "
                + "RTC will do it — and the budget refills with nothing in the log to say time did "
                + "not pass. Stamp with SystemClock.elapsedRealtime() and age with MlsMonotonicAge.",
                guard.contains("System.currentTimeMillis()"));
        assertTrue("MlsPeerGuard no longer reads elapsedRealtime, so its ages come from somewhere "
                + "this test cannot see", guard.contains("SystemClock.elapsedRealtime()"));
    }

    /**
     * G2 asks for "stop, log loudly, require manual reset". Persisting the counters
     * must not put the manual reset out of reach.
     */
    @Test
    public void theManualResetReachesTheDisk() throws IOException {
        final String guard = readGuard();
        // A later change added the third and fourth durable records in this file — the rebuild EPISODE
        // suppressor and the 1:1 re-establish cooldown — and each carries the same obligation: a
        // durable refusal whose operator lever does not reach the disk is a button that does
        // nothing. Named per item, and the loop below fails per item, so adding a fifth record
        // without its reset fails HERE rather than on a device a month later.
        for (final String m : new String[] {
            "resetEraBudget", "resetPeerHealth", "resetRebuildEpisode", "resetReestablishCooldown",
        }) {
            final String body = bodyOf(guard, m);
            assertTrue("MlsPeerGuard." + m + " is gone — the Try again lever has nothing to call",
                    body.length() > 0);
            assertTrue("MlsPeerGuard." + m + " does not remove the PERSISTED record. A reset that "
                    + "clears only what this process remembers leaves the record that is actually "
                    + "refusing the repair on disk, and the button does nothing.",
                    body.contains("drop("));
        }
    }

    /**
     * The §11.2.2 lever reaches the DISK, not just a method.
     *
     * <p>{@link #theManualResetReachesTheDisk} makes this demand of {@code MlsPeerGuard}'s four
     * records and cannot make it of this one: {@code MlsExternalCommitBudget} is its own class with
     * its own preferences file. Same rule, asserted where the record actually lives — a reset that
     * clears only what this process remembers leaves the record that is refusing the repair on disk,
     * and the button does nothing.
     */
    @Test
    public void theExternalCommitLeverReachesTheDisk() throws IOException {
        final String budget = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsExternalCommitBudget.java"));
        final String body = bodyOf(budget, "reset");
        assertTrue("MlsExternalCommitBudget.reset is gone — the Try again lever has nothing to call",
                body.length() > 0);
        assertTrue("MlsExternalCommitBudget.reset no longer removes the PERSISTED record",
                body.contains("remove(") && body.contains("commit("));

        final String transport = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java"));
        final String lever = bodyOf(transport, "resetExternalCommitBudget");
        assertTrue("MlsProviderTransport.resetExternalCommitBudget is gone — the retry arm's call "
                + "to it no longer resolves", lever.length() > 0);
        assertTrue("resetExternalCommitBudget no longer reaches the budget. It is keyed on the "
                + "CANONICAL conversation key, the same one resyncViaExternalCommit charges under "
                + "(it derives its key from resolveInbound, which returns canonicalKey's answer) "
                + "and the same one MlsStalledNotifier puts in the intent — a lever that reaches "
                + "the right store under the wrong key is the same trap in another hat.",
                lever.contains("xcBudget(") && lever.contains("reset("));
    }

    /** Both halves of the escape hatch are still wired to the notification's Try again. */
    @Test
    public void tryAgainStillClearsBothBudgets() throws IOException {
        final String receiver = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsStalledActionReceiver.java"));
        final int retry = receiver.indexOf("ACTION_RETRY.equals(action)");
        final int downgrade = receiver.indexOf("ACTION_DOWNGRADE.equals(action)");
        assertTrue("the retry arm is gone", retry > 0 && downgrade > retry);
        // THE RETRY ARM'S OWN BLOCK, brace-matched — not everything between it and the next arm.
        // The sixth axis. A span bounded by a NEIGHBOUR is not the arm: insert a third arm
        // between the two markers and its body answers for the retry arm's. Measured before this
        // was changed — delete MlsPeerGuard.resetEraBudget from the retry arm, put it in a new arm
        // in between, and this test returned PASS. A person then presses Try again, the era budget
        // stays spent, and the button does nothing, which is the exact failure the assertion below
        // is worded to prevent.
        final String arm = blockAfter(receiver, retry);
        assertTrue("the retry arm's block could not be brace-matched", arm.length() > 0);
        assertTrue("Try again no longer resets the era budget",
                arm.contains("MlsPeerGuard.resetEraBudget("));
        assertTrue("Try again no longer clears the peer-health streak. That streak "
                + "gates the automatic rebuild and survives a restart — so leaving it "
                + "set is a conversation that can never repair itself again.",
                arm.contains("resetPeerHealth("));
        assertFalse("Try again clears the streak through notePeerRecovered, which means 'the peer "
                + "processed something of ours' and logs that claim. A person pressing a button is "
                + "not evidence about the peer; use resetPeerHealth.",
                arm.contains("notePeerRecovered("));
        assertTrue("Try again no longer clears the 1:1 re-establish cooldown. That "
                + "cooldown bounds an attempt which claims one of the peer's KeyPackages, so it was "
                + "made durable — and a durable refusal the lever does not reach is the trap.",
                arm.contains("resetReestablishCooldown("));
        assertTrue("Try again no longer clears the §11.2.2 external-commit allowance. "
                + "That budget's reset was public with ZERO production callers, so a conversation "
                + "whose recovery ladder was stuck behind a spent 50-per-day allowance had no "
                + "operator lever at all and a wait of up to 24 h — the trap in its limit case, "
                + "the lever not reaching the record because it does not reach it. This is also "
                + "the answer to whether a person may clear a bound the SPEC places on us: yes, "
                + "and the argument is at MlsProviderTransport.resetExternalCommitBudget.",
                arm.contains("resetExternalCommitBudget("));

        // AND THE EPISODE SUPPRESSOR, which the retry arm reaches INDIRECTLY. Asserted where it
        // actually is rather than in the arm: resetRebuildRateBound is what the arm calls, and the
        // two bounds refuse the SAME operation, so folding them is right and a needle in the arm
        // would be a needle for a call that should not be there.
        final String rateBound = bodyOf(codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java")),
                "resetRebuildRateBound");
        assertTrue("MlsProviderTransport.resetRebuildRateBound is gone — the retry arm's call to it "
                + "no longer resolves", rateBound.length() > 0);
        assertTrue("resetRebuildRateBound no longer clears the rebuild EPISODE "
                + "suppressor. Since that stamp went to disk, a Try again pressed within a minute of a "
                + "crashed attempt — in a process with no memory of the conversation — is swallowed "
                + "by a record nothing clears, and the button does nothing.",
                rateBound.contains("MlsPeerGuard.resetRebuildEpisode("));
        assertTrue("resetRebuildRateBound no longer clears the rate bound itself",
                rateBound.contains("reset("));

        // And the group case: the receiver holds a group id, not a roster, while allowedToJoinAll
        // refuses a whole group rebuild for ONE member's streak.
        final String transport = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java"));
        final String body = bodyOf(transport, "resetPeerHealth");
        assertTrue("MlsProviderTransport.resetPeerHealth is gone, so a GROUP retry clears no "
                + "member's streak and one wedged member refuses the rebuild forever",
                body.length() > 0);
        assertTrue("it does not resolve a roster, so it can only ever clear the 1:1 peer",
                body.contains("rosterForRebuild("));
        assertTrue("it does not reach MlsPeerGuard", body.contains("MlsPeerGuard.resetPeerHealth("));
    }

    /**
     * <b>Every fail-closed stop has a route to a person</b> — the half of
     * G4's "STOP … and surface it" that did not exist.
     *
     * <p>{@code reconcileAction}'s AHEAD / DIVERGED / ERA_GAP / REJOIN arms rebuild, return
     * {@code FAIL_RETRY}, and are re-driven from later triggers. A re-drive cannot lift a guard
     * refusal or the rebuild rate bound, and the guards' own clearing condition — the peer
     * processing something of ours — is unreachable for exactly the conversation that cannot reach
     * the peer. So those arms retried forever and told nobody.
     *
     * <p>Asserted as a COUNT rather than by naming the arms: an arm added later that rebuilds and
     * does not surface fails here, which is the whole point. A behavioural test cannot ask this
     * question — the transport needs a Context — and the defect is invisible in a passing run
     * because retrying forever looks exactly like working.
     */
    @Test
    public void everyRebuildingArmCanReachAPerson() throws IOException {
        final String transport = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java"));
        final String arms = bodyOf(transport, "reconcileAction");
        assertTrue("reconcileAction is gone and this check no longer means anything",
                arms.length() > 0);
        final int rebuilds = count(arms, "rebuildConversation(");
        final int surfaces = count(arms, "surfaceIfNobodyElseWill(");
        assertTrue("reconcileAction no longer rebuilds at all — the last rung of the self-repair "
                + "ladder has been removed from it", rebuilds > 0);
        assertEquals("an arm of reconcileAction rebuilds without surfacing a deliberate stop ("
                + rebuilds + " rebuilds, " + surfaces + " surfacings). A guard refusal there is "
                + "re-driven forever and nobody is ever told.",
                rebuilds, surfaces);
        assertTrue("reconcileAction never clears the alert, so one raised by a refusal outlives the "
                + "condition and trains a person to dismiss the alert that matters",
                arms.contains("stallCleared("));

        // And the surfacing itself must be NARROW and must actually raise.
        final String surface = bodyOf(transport, "surfaceIfNobodyElseWill");
        assertTrue("surfaceIfNobodyElseWill is gone", surface.length() > 0);
        assertTrue("surfaceIfNobodyElseWill does not gate on needsAPerson(), so it either alerts on "
                + "the 60-second episode guard and on failures nobody can act on, or it alerts on "
                + "nothing", surface.contains("needsAPerson()"));
        assertTrue("surfaceIfNobodyElseWill does not raise the stalled-conversation notification, "
                + "so the stop still reaches no one", surface.contains("MlsStalledNotifier.raise("));
    }

    /**
     * The brace-matched block opened by the arm at {@code at}, or "" if that arm has no block.
     *
     * <p><b>It must be THAT arm's block.</b> Taking "the next {@code &#123;} after the offset" is the
     * same defect as a character window one step removed: a BRACELESS arm
     * ({@code if (refused) log(…);}) has no block of its own, so the search runs on and hands back a
     * NEIGHBOUR's — and the assertion then reports on code the arm does not contain. Measured before
     * this guard existed: make the refusal arm braceless and non-returning, leave a
     * {@code REFUSED_BY_GUARD} in the arm below it, and the check returned PASS on a rebuild that
     * charges the budget, is refused, and rebuilds anyway.
     *
     * <p>So a {@code ;} before the {@code &#123;} ends the search: a statement has intervened, the arm
     * is braceless, and there is no block to read. That is the third time something written AFTER
     * naming the defect class contained it — the same shape turned up in another per-arm lookup on
     * the same day.
     */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        final int stop = src.indexOf(';', at);
        if (stop >= 0 && stop < open) return "";        // braceless arm: that brace is not its own
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    private static int count(final String haystack, final String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    /**
     * The OTHER two durable windows in the package, found by a sweep.
     *
     * <p>{@code MlsRebuildLimiter} (3 per 6 h, our own bound on a rebuild loop) and
     * {@code MlsExternalCommitBudget} (RCC.16 §11.2.2's 50 per day, a bound the spec places on us for
     * the group's benefit) both stored {@code "count:wallStart"} and rolled on
     * {@code System.currentTimeMillis()}. Between them that is 6 h and 24 h of allowance handed back
     * by any clock jump large enough, on the two paths that re-Welcome a whole group.
     *
     * <p>Asserted at the source, for the same reason as {@link #theBudgetsAreAgedByTheMonotonicClock}
     * above: both classes need a {@code Context} and are not on the host classpath, and the
     * regression is a one-line reach for the obvious clock.
     */
    @Test
    public void theOtherDurableWindowsAreAlsoMonotonic() throws IOException {
        for (final String name : new String[] {"MlsRebuildLimiter", "MlsExternalCommitBudget"}) {
            final String src = codeOnly(read("src/com/android/messaging/rcs/e2ee/" + name + ".java"));
            assertFalse(name + " reads System.currentTimeMillis(). A durable window aged by the wall "
                    + "clock is rolled by any jump forward — an NTP correction after a boot with a "
                    + "dead RTC will do it — and the allowance comes back with nothing in the log to "
                    + "say time did not pass. Stamp with SystemClock.elapsedRealtime() and age with "
                    + "MlsMonotonicAge, via MlsRebuildWindowRecord.",
                    src.contains("System.currentTimeMillis()"));
            assertTrue(name + " no longer reads elapsedRealtime, so its window ages from somewhere "
                    + "this test cannot see", src.contains("SystemClock.elapsedRealtime()"));
            assertTrue(name + " no longer delegates to MlsWindowBudget, so its codec, its rolling "
                    + "rule and its no-store posture are back in the class where nothing "
                    + "host-testable can reach them (Stage 3 moved them out; "
                    + "MlsRebuildWindowRecord is reached THROUGH the policy now)",
                    src.contains("MlsWindowBudget"));
            assertFalse(name + " writes a charge with apply(). The loops these windows bound KILL "
                    + "THE PROCESS, so a write that is only scheduled is a charge that can be lost "
                    + "exactly when it matters — use commit().", APPLY.matcher(src).find());
            assertTrue(name + " does not commit() anything: nothing is being written at all",
                    COMMIT.matcher(src).find());
        }
    }

    /**
     * <b>The two SEND-SIDE RETENTION stores age on the monotonic clock too.</b>
     *
     * <p>Every other case in this file is a BUDGET, where a forward clock jump rolls the window and
     * REFILLS an allowance. These two run the arithmetic the other way and the failure is worse:
     * {@code MlsPendingBodyStore} holds the only copy of a message we have framed and not yet had
     * acknowledged, {@code MlsCiphertextCache} holds the bytes a resend replays instead of
     * re-encrypting, and a jump forward makes both look old and DELETES them. One NTP correction
     * after a boot with a dead RTC can empty both stores in a single pass, with the log saying only
     * that entries aged out.
     *
     * <p><b>What would make this go red</b>, so the PASS means something: reverting either store's
     * stamp or sweep to {@code System.currentTimeMillis()} — which is exactly what both did until
     * 2026-09-13 — or pointing the transport's sweep at the wall clock while the stores read an
     * elapsed one, which is the silent half: the stamps would be elapsed readings compared against a
     * wall reading, so every entry would look older than the age of the universe and the first sweep
     * would empty the store.
     *
     * <p>Asserted at the source for the same reason as the two tests above: both stores need a
     * {@code Context}, so they are not on the host classpath, and the regression is a one-line reach
     * for the obvious clock.
     */
    @Test
    public void theSendSideRetentionStoresAreMonotonic() throws IOException {
        for (final String name : new String[] {"MlsPendingBodyStore", "MlsCiphertextCache"}) {
            final String src = codeOnly(read("src/com/android/messaging/rcs/e2ee/" + name + ".java"));
            assertFalse(name + " reads System.currentTimeMillis(). A RETENTION store aged by the "
                    + "wall clock DELETES its entries on any jump forward — an NTP correction after "
                    + "a boot with a dead RTC will do it — and what it deletes is the last copy of a "
                    + "message still in flight. Stamp with SystemClock.elapsedRealtime() and age "
                    + "with MlsSendRetentionPolicy.expiredMonotonic.",
                    src.contains("System.currentTimeMillis()"));
            assertTrue(name + " no longer reads elapsedRealtime, so its entries age from somewhere "
                    + "this test cannot see", src.contains("SystemClock.elapsedRealtime()"));
            assertTrue(name + " no longer ages through MlsSendRetentionPolicy.expiredMonotonic, so "
                    + "whatever it is comparing is not the age MlsMonotonicAge can prove",
                    src.contains("expiredMonotonic("));
        }
        // AND THE CALLER, which is the half neither store can defend. sweepExpired(maxAge, now)
        // takes its reading from the transport, so a wall-clock argument there defeats both stores
        // while leaving every assertion above green.
        final String sweep = bodyOf(codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java")),
                "sweepExpiredSendMaterial");
        assertTrue("MlsProviderTransport.sweepExpiredSendMaterial is gone — nothing enforces the "
                + "retention window outside the stores' own ceilings", sweep.length() > 0);
        assertFalse("sweepExpiredSendMaterial hands the ciphertext cache a wall-clock reading. The "
                + "stamps in that store are elapsedRealtime readings, so comparing them against "
                + "currentTimeMillis makes every entry look old enough to drop and the first sweep "
                + "empties the store.", sweep.contains("System.currentTimeMillis()"));
        assertTrue("sweepExpiredSendMaterial no longer reads elapsedRealtime",
                sweep.contains("SystemClock.elapsedRealtime()"));
    }

    /**
     * The wall-clock expiry helper is GONE from the policy, not merely unused.
     *
     * <p>{@code MlsSendRetentionPolicy.expired(nowMs, storedAtMs, maxAgeMs)} was the function both
     * stores called, and leaving it in place would leave the next person a wall-clock answer sitting
     * in the policy class with a name that reads like the right one. Reflection rather than a source
     * needle: the question is whether the method EXISTS, and the engine is on the host classpath, so
     * it can be asked directly.
     */
    @Test
    public void thePolicyOffersNoWallClockExpiry() {
        for (final java.lang.reflect.Method m
                : MlsSendRetentionPolicy.class.getDeclaredMethods()) {
            assertFalse("MlsSendRetentionPolicy.expired is back. It ages a stored time against "
                    + "System.currentTimeMillis(), which is the deletion-on-a-clock-jump this "
                    + "removed — use expiredMonotonic over SystemClock.elapsedRealtime().",
                    m.getName().equals("expired"));
        }
    }

    /**
     * The budget file must NOT be cleared by conversation teardown, for {@code MlsRebuildLimiter}'s
     * stated reason: a rebuild's first act is to forget the conversation, so a counter cleared by
     * teardown erases its own evidence and the loop it bounds runs free.
     */
    @Test
    public void theBudgetFileOutlivesConversationTeardown() throws IOException {
        final String guard = readGuard();
        assertFalse("MlsPeerGuard implements MlsPerConversationState, so teardown clears the era "
                + "budget — and a rebuild's FIRST ACT is that teardown. The counter would erase the "
                + "evidence of the operation it is counting.",
                IMPLEMENTS_PER_CONVERSATION.matcher(guard).find());
        final String transport = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java"));
        final String registry = bodyOf(transport, "perConversationStores");
        assertTrue("perConversationStores is gone and this check no longer means anything",
                registry.length() > 0);
        assertFalse("the peer-guard budgets are registered as per-conversation state; see above",
                registry.contains("MlsPeerGuard"));
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** The brace-matched body of the first method whose declaration contains {@code name}. */
    private static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // The DECLARATION only, never into the body: a method that merely CALLS the one asked
            // for would otherwise answer as though it were it. Exact name, never a substring.
            final String decl = src.substring(m.start(), open);
            if (!src.substring(m.start(1), m.end(1)).equals(name)) continue;
            if (!declFragment.isEmpty() && !decl.contains(declFragment)) continue;
            int depth = 0;
            for (int i = open; i < src.length(); i++) {
                final char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
            }
            return "";
        }
        return "";
    }

    private static String readGuard() throws IOException {
        return codeOnly(read("src/com/android/messaging/rcs/e2ee/MlsPeerGuard.java"));
    }

    /**
     * The EXECUTABLE source: comments and string-literal contents blanked, offsets and line breaks
     * preserved.
     *
     * <p>Every assertion here is about what the code DOES, and this file's own prose names the
     * things it forbids — {@code apply()}, the wall clock, {@code notePeerRecovered} — in the
     * comments explaining why they are forbidden. Searching the raw text would make a class fail its
     * own guard for documenting it, and, worse, would let a guard PASS because a comment mentioned
     * the call it was checking for.
     */
    private static String codeOnly(final String src) {
        final char[] out = src.toCharArray();
        int i = 0;
        while (i < out.length) {
            final char c = out[i];
            final char next = (i + 1 < out.length) ? out[i + 1] : '\0';
            if (c == '/' && next == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && next == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
                if (i < out.length) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                i++;                                    // keep the opening quote: it is code
                while (i < out.length && out[i] != c) {
                    if (out[i] == '\\') {
                        out[i++] = ' ';
                        if (i < out.length) out[i++] = ' ';
                        continue;
                    }
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                i++;                                    // and the closing quote
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** Same locator as {@code MlsPeerReJoinBudgetGuardTest}: works from the module dir or the root. */
    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath()
                + " — tried " + String.join(", ", candidates));
    }
}
