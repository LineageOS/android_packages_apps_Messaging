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
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@code Health.AHEAD} must stop reporting "further along the same chain" and
 * "on a different chain" identically, and the five ways of NOT knowing must stay apart from the one
 * way of knowing.
 *
 * <h2>What makes each of these go red, because a check nobody can fail is not evidence</h2>
 *
 * <ul>
 *   <li>{@link #anAbsentRetainedAnchorIsNeverAFork} fails if any "we could not tell" path is ever
 *       spelled as {@code DIFFERENT_CHAIN}. That is the defect in its most damaging direction: a
 *       fork verdict demotes AHEAD to DIVERGED and aborts the maintenance pass, so inventing one
 *       from missing data stops a healthy conversation from being maintained.</li>
 *   <li>{@link #theServerEraHoleIsCheckedBeforeTheRetainedEra} fails if the checks are reordered so
 *       an unreadable server era is answered by keying the retained map on the local era. That is
 *       constraint 3, and it is reachable: the era gate above this arm reads
 *       {@code server[0] >= 0 && localEra != server[0]}, so a server era of -1 SKIPS it.</li>
 *   <li>{@link #theAheadArmActuallyRunsTheChainTest} fails if {@code healthAgainstServer} stops
 *       calling this class — a policy with no caller.</li>
 *   <li>{@link #theMaintenanceForkAbortStillKeysOnDiverged} fails if
 *       {@code runMaintenanceOnce}'s fork abort stops keying on {@code Health.DIVERGED}. The whole
 *       value of demoting a forked AHEAD is that it reaches that abort; if the abort moves, this
 *       fix becomes inert and nothing else would say so.</li>
 * </ul>
 */
@RunWith(JUnit4.class)
public class MlsAheadChainCheckTest {

    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    private static final byte[] A = fill((byte) 0xA1);
    private static final byte[] B = fill((byte) 0xB2);

    private static byte[] fill(final byte v) {
        final byte[] b = new byte[32];
        java.util.Arrays.fill(b, v);
        return b;
    }

    // ---------------------------------------------------------------- the decision

    /** The one answer. Same bytes at the server's (era, epoch) means its anchor is on our chain. */
    @Test
    public void matchingRetainedAnchorMeansTheSameChain() {
        assertEquals(MlsAheadChainCheck.Verdict.TESTABLE,
                MlsAheadChainCheck.blockedBefore(7L, 7, A));
        assertEquals(MlsAheadChainCheck.Verdict.SAME_CHAIN,
                MlsAheadChainCheck.decide(A, false, A.clone()));
    }

    /** The other answer, and the only one that is evidence of a fork. */
    @Test
    public void aDifferentAnchorAtTheServersEpochMeansAFork() {
        assertEquals(MlsAheadChainCheck.Verdict.DIFFERENT_CHAIN,
                MlsAheadChainCheck.decide(A, false, B));
        assertTrue(MlsAheadChainCheck.isFork(MlsAheadChainCheck.Verdict.DIFFERENT_CHAIN));
    }

    /**
     * THE LOAD-BEARING ONE. Every state that is not a measurement must stay out of the fork verdict,
     * in BOTH directions: it must not become DIFFERENT_CHAIN (which would demote a healthy AHEAD to
     * DIVERGED and abort its maintenance pass), and it must not become SAME_CHAIN (which would
     * assert a chain identity nobody established).
     */
    @Test
    public void anAbsentRetainedAnchorIsNeverAFork() {
        final MlsAheadChainCheck.Verdict[] notAnswers = {
            MlsAheadChainCheck.blockedBefore(-1L, 7, A),                 // server era unreadable
            MlsAheadChainCheck.blockedBefore(7L, MlsConversationRecord.ERA_UNKNOWN, A),
            MlsAheadChainCheck.blockedBefore(7L, 6, A),                  // map is for another era
            MlsAheadChainCheck.blockedBefore(7L, 7, null),               // nothing retained
            MlsAheadChainCheck.blockedBefore(7L, 7, new byte[0]),
            MlsAheadChainCheck.decide(A, true, null),                    // ledger refused
            MlsAheadChainCheck.decide(A, false, null),                   // server answer absent
            MlsAheadChainCheck.decide(A, false, new byte[0]),
            MlsAheadChainCheck.decide(null, false, B),                   // defensive
            MlsAheadChainCheck.decide(new byte[0], false, B),
        };
        for (final MlsAheadChainCheck.Verdict v : notAnswers) {
            assertFalse("a non-measurement was spelled as a fork: " + v,
                    MlsAheadChainCheck.isFork(v));
            assertFalse("a non-measurement was spelled as a chain match: " + v,
                    v == MlsAheadChainCheck.Verdict.SAME_CHAIN);
        }
    }

    /** Each non-answer names ITSELF — collapsing them would leave the log unable to say which. */
    @Test
    public void eachWayOfNotKnowingIsNamedSeparately() {
        assertEquals(MlsAheadChainCheck.Verdict.SERVER_ERA_UNKNOWN,
                MlsAheadChainCheck.blockedBefore(-1L, 7, A));
        assertEquals(MlsAheadChainCheck.Verdict.NO_RETAINED_ANCHOR,
                MlsAheadChainCheck.blockedBefore(7L, MlsConversationRecord.ERA_UNKNOWN, A));
        assertEquals(MlsAheadChainCheck.Verdict.NO_RETAINED_ANCHOR,
                MlsAheadChainCheck.blockedBefore(7L, 7, null));
        assertEquals(MlsAheadChainCheck.Verdict.LOOK_REFUSED,
                MlsAheadChainCheck.decide(A, true, null));
        assertEquals(MlsAheadChainCheck.Verdict.SERVER_ANCHOR_ABSENT,
                MlsAheadChainCheck.decide(A, false, null));
        // A refusal outranks an absent answer: nothing was asked, so nothing can be reported about
        // the server. The opposite order would report OUR budget as a fact about THEIR state.
        assertEquals(MlsAheadChainCheck.Verdict.LOOK_REFUSED,
                MlsAheadChainCheck.decide(A, true, B));
    }

    /**
     * Constraint 3, and the one a reader would not predict. The era gate above this arm
     * is {@code server[0] >= 0 && localEra != server[0]}, so an UNREADABLE server era skips it and
     * AHEAD is reachable with the eras never compared. Keying the retained map on the local era
     * there would assert exactly what the gate failed to establish — so the server-era check must
     * come FIRST, and stay first even when the retained era would have matched.
     */
    @Test
    public void theServerEraHoleIsCheckedBeforeTheRetainedEra() {
        assertEquals("an unreadable server era must be named as such, not answered from a retained "
                        + "map that happens to be populated",
                MlsAheadChainCheck.Verdict.SERVER_ERA_UNKNOWN,
                MlsAheadChainCheck.blockedBefore(-1L, 5, A));
        // ...including when the retained era is ALSO unknown, where the other reason is equally true
        // and the server-era one is the one that must be reported.
        assertEquals(MlsAheadChainCheck.Verdict.SERVER_ERA_UNKNOWN,
                MlsAheadChainCheck.blockedBefore(-1L, MlsConversationRecord.ERA_UNKNOWN, null));
    }

    /**
     * The pre-migration window (constraint 1). Such a record KEEPS its entries and only
     * the first write stamps an era, so a non-current entry may belong to an older era — and this
     * arm asks only about non-current epochs.
     */
    @Test
    public void theMigrationWindowIsRefusedRatherThanTrusted() {
        assertEquals(MlsAheadChainCheck.Verdict.NO_RETAINED_ANCHOR,
                MlsAheadChainCheck.blockedBefore(1L, MlsConversationRecord.ERA_UNKNOWN, A));
    }

    @Test
    public void decideNeverReturnsTestable() {
        for (final boolean refused : new boolean[] {true, false}) {
            for (final byte[] srv : new byte[][] {null, new byte[0], A, B}) {
                assertFalse(MlsAheadChainCheck.decide(A, refused, srv)
                        == MlsAheadChainCheck.Verdict.TESTABLE);
            }
        }
    }

    // ---------------------------------------------------------------- the wiring

    /**
     * A POLICY WITH NO CALLER IS A MISTAKE. The decision above is worth
     * nothing unless {@code healthAgainstServer}'s above-the-server arm actually reaches it.
     *
     * <p>Source scan, because {@code healthAgainstServer} is Android code with no host test. It
     * asserts the call and that the arm no longer returns the bare {@code notAsked(Health.AHEAD)} it
     * used to — either half alone is weak: the first would pass on a method that computes a verdict
     * and ignores it, the second on one that returns something else entirely.
     */
    @Test
    public void theAheadArmActuallyRunsTheChainTest() throws IOException {
        final String body = bodyOf(TRANSPORT, "healthAgainstServer");
        if (body.contains("ServerComparison.notAsked(Health.AHEAD)")) {
            fail("healthAgainstServer returns a bare notAsked(Health.AHEAD) again — the chain test "
                    + "is bypassed and a fork above the server's epoch walks past the maintenance "
                    + "fork abort once more.");
        }
        assertTrue("healthAgainstServer no longer routes its above-the-server arm through "
                        + "aheadOrForked, so MlsAheadChainCheck has no caller",
                body.contains("aheadOrForked("));

        final String arm = bodyOf(TRANSPORT, "aheadOrForked");
        assertTrue("aheadOrForked no longer calls MlsAheadChainCheck.blockedBefore, so the three "
                        + "free refusals are gone and the look is paid for unconditionally",
                arm.contains("MlsAheadChainCheck.blockedBefore("));
        assertTrue("aheadOrForked no longer calls MlsAheadChainCheck.decide",
                arm.contains("MlsAheadChainCheck.decide("));
        assertTrue("aheadOrForked no longer demotes a measured fork to DIVERGED — which is the ONLY "
                        + "thing that makes the maintenance fork abort fire on this arm",
                arm.contains("MlsAheadChainCheck.isFork(") && arm.contains("Health.DIVERGED"));
    }

    /**
     * THE OTHER HALF OF THE FIX, AND IT IS IN A DIFFERENT METHOD. Demoting a forked AHEAD to
     * DIVERGED only helps because {@code runMaintenanceOnce} aborts the pass on DIVERGED. If that
     * guard is re-keyed or removed, this whole change becomes inert — the verdict would still be
     * correct and nothing would act on it, which is the shape of defect this is about.
     */
    @Test
    public void theMaintenanceForkAbortStillKeysOnDiverged() throws IOException {
        final String body = bodyOf(TRANSPORT, "runMaintenanceOnce");
        assertTrue("runMaintenanceOnce no longer aborts on Health.DIVERGED. The demotion of a "
                        + "forked AHEAD to DIVERGED was landed BECAUSE this abort exists; if the "
                        + "abort has moved, that demotion now does nothing and the fork is back to "
                        + "walking into the add arm.",
                body.contains("identity.health==Health.DIVERGED")
                        && body.contains("maintenanceFoundAFork("));
    }

    // ---------------------------------------------------------------- scan helpers

    /** Whitespace- and comment-stripped body of the single declaration named. Zero hits, or two, FAIL. */
    private static String bodyOf(final String rel, final String method) throws IOException {
        final String src = read(rel);
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?:public|private|protected)[^\\n=;]*?\\b"
                        + java.util.regex.Pattern.quote(method) + "\\s*\\(").matcher(src);
        final java.util.List<Integer> decls = new java.util.ArrayList<>();
        while (m.find()) decls.add(Integer.valueOf(m.start()));
        assertTrue("ZERO HITS MUST FAIL: no declaration of " + method + " in " + rel,
                !decls.isEmpty());
        if (decls.size() != 1) {
            fail("ambiguous: " + decls.size() + " declarations of " + method + " at " + decls
                    + ". Refusing to pick one — name the overload this guard is about.");
        }
        final int open = src.indexOf('{', decls.get(0).intValue());
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                // COMMENTS STRIPPED BEFORE WHITESPACE. Without it every matcher here can be
                // satisfied by a COMMENT mentioning the thing it looks for — and the comments in
                // this method quote the very expressions being asserted.
                return src.substring(open, i + 1)
                        .replaceAll("(?s)/\\*.*?\\*/", " ")
                        .replaceAll("(?m)//[^\n]*", " ")
                        .replaceAll("\\s+", "");
            }
        }
        throw new AssertionError(method + "'s body did not brace-match");
    }

    private static String read(final String rel) throws IOException {
        final String[] candidates = {rel, "packages/apps/Messaging/" + rel, "../" + rel,
                "../../" + rel};
        for (final String c : candidates) {
            final File f = new File(c);
            if (f.isFile()) {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException(rel + " not found from " + new File(".").getAbsolutePath());
    }
}
