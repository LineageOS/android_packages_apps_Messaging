/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The guards' counters are on disk, aged by the monotonic clock, and reachable by Try again.
 * {@code MlsEraBudgetRecordTest} and {@code MlsPeerHealthRecordTest} pin the records; this pins the
 * Context-bound wiring, which is not on the host classpath. See docs/mls/budgets.md.
 */
public final class MlsGuardPersistenceTest {

    /** A method declaration at class level: 4-space indent, a visibility modifier. */
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(?m)^    (?:public|private|protected)\\s[^\\n=;]*?\\b([A-Za-z_]\\w*)\\s*\\(");

    /**
     * Patterns rather than literals: whitespace inside {@code .apply()} is legal Java, and
     * {@code implements A, MlsPerConversationState} must match too.
     */
    private static final Pattern APPLY = Pattern.compile("\\.\\s*apply\\s*\\(\\s*\\)");
    private static final Pattern COMMIT = Pattern.compile("\\.\\s*commit\\s*\\(\\s*\\)");
    private static final Pattern IMPLEMENTS_PER_CONVERSATION = Pattern.compile(
            "\\bimplements\\s[^{;]*\\bMlsPerConversationState\\b");

    /**
     * The read-modify-write lives in {@code MlsPeerGuard.decide}, shared by all four tiers; the
     * budget key derivation is host-tested in {@link
     * MlsStateChangeGateTest#aOneToOneIsChargedToThePeer}.
     */
    @Test
    public void theEraBudgetIsChargedToAPersistentStore() throws IOException {
        final String guard = readGuard();
        assertTrue(
                "MlsPeerGuard no longer opens a SharedPreferences file — the budgets are back in "
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
     * The charge is on disk before the operation begins: {@code apply()} survives an orderly
     * process death but not a crash, and a crash loop on the rebuild path is exactly what is
     * bounded.
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

    /** A durable budget aged by the wall clock is refilled by any large enough clock jump. */
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

    /** Persisting the counters must not put the manual reset out of reach. */
    @Test
    public void theManualResetReachesTheDisk() throws IOException {
        final String guard = readGuard();
        // One reset per durable record in MlsPeerGuard, checked per item.
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
     * The RCC.16 §11.2.2 external-commit budget has its own preferences file, so its reset is
     * checked where the record lives.
     */
    @Test
    public void theExternalCommitLeverReachesTheDisk() throws IOException {
        final String budget = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsExternalCommitBudget.java"));
        final String body = bodyOf(budget, "reset");
        assertTrue(
                "MlsExternalCommitBudget.reset is gone — the Try again lever has nothing to call",
                body.length() > 0);
        assertTrue("MlsExternalCommitBudget.reset no longer removes the PERSISTED record",
                body.contains("remove(") && body.contains("commit("));

        final String transport = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
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

    /** Every durable refusal is cleared by the notification's Try again. */
    @Test
    public void tryAgainStillClearsBothBudgets() throws IOException {
        final String receiver = codeOnly(
                read("src/com/android/messaging/rcs/e2ee/MlsStalledActionReceiver.java"));
        final int retry = receiver.indexOf("ACTION_RETRY.equals(action)");
        final int downgrade = receiver.indexOf("ACTION_DOWNGRADE.equals(action)");
        assertTrue("the retry arm is gone", retry > 0 && downgrade > retry);
        // The retry arm's own brace-matched block, not the span up to the next arm: a third arm
        // inserted between them would otherwise answer for it.
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

        // The episode suppressor is reached through resetRebuildRateBound: both refuse the same
        // operation.
        final String rateBound = bodyOf(com.android.messaging.rcs.SourceScan.transportUnsplitCode(),
                "resetRebuildRateBound");
        assertTrue(
                "MlsProviderTransport.resetRebuildRateBound is gone — the retry arm's call to it "
                + "no longer resolves", rateBound.length() > 0);
        assertTrue("resetRebuildRateBound no longer clears the rebuild EPISODE "
                + "suppressor. Since that stamp went to disk, a Try again pressed within a minute of a "
                + "crashed attempt — in a process with no memory of the conversation — is swallowed "
                + "by a record nothing clears, and the button does nothing.",
                rateBound.contains("MlsPeerGuard.resetRebuildEpisode("));
        assertTrue("resetRebuildRateBound no longer clears the rate bound itself",
                rateBound.contains("reset("));

        // The group case: the receiver holds a group id, and allowedToJoinAll refuses a group
        // rebuild for one member's streak.
        final String transport = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        final String body = bodyOf(transport, "resetPeerHealth");
        assertTrue("MlsProviderTransport.resetPeerHealth is gone, so a GROUP retry clears no "
                + "member's streak and one wedged member refuses the rebuild forever",
                body.length() > 0);
        assertTrue("it does not resolve a roster, so it can only ever clear the 1:1 peer",
                body.contains("rosterForRebuild("));
        assertTrue("it does not reach MlsPeerGuard",
                body.contains("MlsPeerGuard.resetPeerHealth("));
    }

    /**
     * Every rebuilding arm of {@code reconcileAction} can reach a person: a re-drive cannot lift a
     * guard refusal or the rebuild rate bound, so without surfacing those arms retry forever and
     * tell nobody. Asserted as a count, so an arm added later must surface too.
     */
    @Test
    public void everyRebuildingArmCanReachAPerson() throws IOException {
        final String transport = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
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
        assertTrue(
                "reconcileAction never clears the alert, so one raised by a refusal outlives the "
                + "condition and trains a person to dismiss the alert that matters",
                arms.contains("stallCleared("));

        // The surfacing must be narrow and must raise; it is declared in MlsStallAlert.
        final String surface = bodyOf(codeOnly(read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsStallAlert.java")),
                "surfaceIfNobodyElseWill");
        assertTrue("surfaceIfNobodyElseWill is gone", surface.length() > 0);
        assertTrue(
                "surfaceIfNobodyElseWill does not gate on needsAPerson(), so it either alerts on "
                + "the 60-second episode guard and on failures nobody can act on, or it alerts on "
                + "nothing", surface.contains("needsAPerson()"));
        // Through raiseStall, the transport's Context-bound effect; both halves are asserted.
        assertTrue("surfaceIfNobodyElseWill does not raise the stalled-conversation notification, "
                + "so the stop still reaches no one", surface.contains("raiseStall("));
        assertTrue("raiseStall no longer posts the stalled-conversation notification",
                bodyOf(transport, "raiseStall").contains("MlsStalledNotifier.raise("));
    }

    /**
     * The brace-matched block opened by the arm at {@code at}, or "" if the arm is braceless: a
     * {@code ;} before the brace means the brace belongs to a neighbour.
     */
    private static String blockAfter(final String src, final int at) {
        final int open = src.indexOf('{', at);
        if (open < 0) return "";
        final int stop = src.indexOf(';', at);
        if (stop >= 0 && stop < open) return "";        // braceless arm
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
     * {@code MlsRebuildLimiter} (3 per 6 h) and {@code MlsExternalCommitBudget} (RCC.16 §11.2.2, 50
     * per day) roll on the monotonic clock too; a clock jump would otherwise hand back allowance on
     * the two paths that re-Welcome a whole group.
     */
    @Test
    public void theOtherDurableWindowsAreAlsoMonotonic() throws IOException {
        for (final String name : new String[] {"MlsRebuildLimiter", "MlsExternalCommitBudget"}) {
            final String src =
                    codeOnly(read("src/com/android/messaging/rcs/e2ee/" + name + ".java"));
            assertFalse(name
                    + " reads System.currentTimeMillis(). A durable window aged by the wall "
                    + "clock is rolled by any jump forward — an NTP correction after a boot with a "
                    + "dead RTC will do it — and the allowance comes back with nothing in the log to "
                    + "say time did not pass. Stamp with SystemClock.elapsedRealtime() and age with "
                    + "MlsMonotonicAge, via MlsRebuildWindowRecord.",
                    src.contains("System.currentTimeMillis()"));
            assertTrue(name + " no longer reads elapsedRealtime, so its window ages from somewhere "
                    + "this test cannot see", src.contains("SystemClock.elapsedRealtime()"));
            assertTrue(name + " no longer delegates to MlsWindowBudget, so its codec, its rolling "
                    + "rule and its no-store posture are back in the class where nothing "
                    + "host-testable can reach them ("
                    + "MlsRebuildWindowRecord is reached THROUGH the policy)",
                    src.contains("MlsWindowBudget"));
            assertFalse(name + " writes a charge with apply(). The loops these windows bound KILL "
                    + "THE PROCESS, so a write that is only scheduled is a charge that can be lost "
                    + "exactly when it matters — use commit().", APPLY.matcher(src).find());
            assertTrue(name + " does not commit() anything: nothing is being written at all",
                    COMMIT.matcher(src).find());
        }
    }

    /**
     * The send-side retention stores age on the monotonic clock. A forward jump would make their
     * entries look old and delete them: {@code MlsPendingBodyStore} holds the only copy of a
     * framed, unacknowledged message, and {@code MlsCiphertextCache} the bytes a resend replays.
     */
    @Test
    public void theSendSideRetentionStoresAreMonotonic() throws IOException {
        for (final String name : new String[] {"MlsPendingBodyStore", "MlsCiphertextCache"}) {
            final String src =
                    codeOnly(read("src/com/android/messaging/rcs/e2ee/" + name + ".java"));
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
        // The caller too: sweepExpired takes its reading from the transport, and a wall-clock
        // reading against elapsed stamps would make every entry look old.
        final String sweep = bodyOf(com.android.messaging.rcs.SourceScan.transportUnsplitCode(),
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

    /** The wall-clock expiry helper is gone from the policy, asked by reflection. */
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
     * The budget file is not cleared by conversation teardown: a rebuild's first act is to forget
     * the conversation, so a counter cleared by teardown would erase its own evidence.
     */
    @Test
    public void theBudgetFileOutlivesConversationTeardown() throws IOException {
        final String guard = readGuard();
        assertFalse("MlsPeerGuard implements MlsPerConversationState, so teardown clears the era "
                + "budget — and a rebuild's FIRST ACT is that teardown. The counter would erase the "
                + "evidence of the operation it is counting.",
                IMPLEMENTS_PER_CONVERSATION.matcher(guard).find());
        final String transport = com.android.messaging.rcs.SourceScan.transportUnsplitCode();
        final String registry = bodyOf(transport, "perConversationStores");
        assertTrue("perConversationStores is gone and this check no longer means anything",
                registry.length() > 0);
        assertFalse("the peer-guard budgets are registered as per-conversation state; see above",
                registry.contains("MlsPeerGuard"));
    }

    /** The brace-matched body of the first method named exactly {@code name}. */
    private static String bodyOf(final String src, final String name) {
        return bodyOf(src, name, "");
    }

    private static String bodyOf(final String src, final String name, final String declFragment) {
        final Matcher m = METHOD_DECL.matcher(src);
        while (m.find()) {
            final int open = src.indexOf('{', m.end() - 1);
            if (open < 0) continue;
            // Match the declaration only, by exact name: a method that calls the one asked for must
            // not answer.
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
     * The source with comments and string contents blanked, offsets preserved, so neither a comment
     * nor a string can satisfy or fail a check.
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
                while (i < out.length
                        && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
                if (i < out.length) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                i++;                                    // keep the opening quote
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

    /** Works from the module directory or the tree root. */
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
