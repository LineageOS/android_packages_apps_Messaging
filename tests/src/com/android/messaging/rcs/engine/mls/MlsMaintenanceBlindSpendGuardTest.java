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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A maintenance pass must not spend an ERA ADVANCE on a group whose fork test our own ration
 * refused.
 *
 * <h2>Why this is a guard and not a unit test</h2>
 *
 * <p>The decision is two tokens inside a 250-line Android method that reaches the engine, the store
 * and the transport; there is nothing to extract that would still be the thing being asserted. What
 * CAN be pinned is the property that makes it correct — <b>the refusal check stands BEFORE the
 * advance</b> — and that is positional, so it is scanned.
 *
 * <h2>The defect, and why the refusal is ordinary rather than exotic</h2>
 *
 * <p>{@code MlsFetchLedger.Caller.MAINTENANCE_IDENTITY} allows ONE look per conversation per
 * {@code WINDOW_MS} (200 s). The pass is triggered by conversation OPEN and by nothing else (§8.7
 * says "Build no timer"), with no debounce, and {@code runMaintenance}'s {@code finally} drives
 * {@code continueGroupSweep} as a second source of passes. So a second pass inside the window is a
 * person re-opening a chat. On it {@code serverStateCheckFor} returns {@code REFUSED_BY_LEDGER},
 * {@code healthAgainstServer} reports IN_SYNC on numbers alone and says so — and the pass used to go
 * on to {@code eraAdvance} plus {@code recordMembership(serverRoster)} on a group it had not shown
 * was the server's. The identity reconciliation refuses exactly that uncertainty for the roster BASELINE write one
 * branch up; this arm spends strictly more.
 *
 * <h2>What makes each assertion go red, confirmed reachable</h2>
 *
 * <ul>
 *   <li>Delete the gate, or move it below the advance, and
 *       {@link #theEraAdvanceIsDeferredWhenOurOwnLedgerRefusedTheForkTest} fails — the first on the
 *       token, the second on the ordering, which is why both are asserted.</li>
 *   <li>Widen the predicate to {@code !serverConfirmedOurs()} and
 *       {@link #theGateIsTheREFUSALNotTheAbsenceOfThePositive} fails. That widening is the tempting
 *       mistake and it is a much larger behaviour change: the positive is ALSO absent on the AHEAD,
 *       LOWER_EPOCH_CHAIN_UNKNOWN and UNKNOWN arms, so it would stop maintenance refreshing every
 *       conversation not exactly at the server's epoch — the "conversations stop being maintained"
 *       symptom {@code MAINTENANCE_IDENTITY}'s own javadoc warns about.</li>
 * </ul>
 */
@RunWith(JUnit4.class)
public class MlsMaintenanceBlindSpendGuardTest {

    private static final String TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    @Test
    public void theEraAdvanceIsDeferredWhenOurOwnLedgerRefusedTheForkTest() throws IOException {
        final String body = bodyOf(TRANSPORT, "runMaintenanceOnce");

        final int advanceAt = body.indexOf("eraAdvance(");
        assertTrue("ZERO HITS MUST FAIL: runMaintenanceOnce no longer calls eraAdvance — if the "
                + "advance moved, this guard must follow it rather than pass on nothing.",
                advanceAt >= 0);

        final int gateAt = body.indexOf("identity.identityRefusedByLedger()");
        if (gateAt < 0) {
            fail("runMaintenanceOnce no longer checks identity.identityRefusedByLedger() before "
                    + "advancing an era. A pass whose fork test the ledger refused "
                    + "reports IN_SYNC on numbers alone; spending an era advance and writing "
                    + "recordMembership(serverRoster) on it is how a fork acquires a membership "
                    + "history — the failure the roster baseline is gated against, one branch up.");
        }
        // POSITIONAL, and it is the half that matters: a check that runs AFTER the spend is not a
        // gate. Asserted by index rather than by a concatenated needle, because the surrounding
        // text is comment-stripped prose that would let a spliced literal match the wrong thing.
        assertTrue("the ledger-refusal gate is present but sits AFTER eraAdvance(), so the advance "
                        + "is already spent by the time it is consulted (gate@" + gateAt
                        + " advance@" + advanceAt + ")",
                gateAt < advanceAt);
    }

    @Test
    public void theGateIsTheREFUSALNotTheAbsenceOfThePositive() throws IOException {
        final String accessor = bodyOf(TRANSPORT, "identityRefusedByLedger");
        assertTrue("identityRefusedByLedger must key on REFUSED_BY_LEDGER. Widening it to the "
                        + "absence of the positive would also catch AHEAD, "
                        + "LOWER_EPOCH_CHAIN_UNKNOWN and UNKNOWN, and stop maintenance refreshing "
                        + "every conversation not exactly at the server's epoch.",
                accessor.contains("ServerState.REFUSED_BY_LEDGER"));
        assertTrue("identityRefusedByLedger must not be spelled as !serverConfirmedOurs()",
                !accessor.contains("serverConfirmedOurs"));

        // ...and the roster baseline write must STILL be gated on the positive. The two gates say
        // different things on purpose; collapsing them either way loses one of the two properties.
        final String pass = bodyOf(TRANSPORT, "runMaintenanceOnce");
        assertTrue("the roster baseline write is no longer gated on identity.serverConfirmedOurs() "
                        + " — that is a separate property from this one and it must "
                        + "not be absorbed into the refusal gate",
                pass.contains("identity.serverConfirmedOurs()"));
    }

    /** Whitespace- and comment-stripped body of the single declaration named. */
    private static String bodyOf(final String rel, final String method) throws IOException {
        final String src = read(rel);
        // The modifier is OPTIONAL, unlike the sibling scanners in this suite: the accessor this
        // guard reads is package-private, as its two neighbours already are. Dropping the required
        // modifier makes the pattern match CALL SITES too — it matched `return runMaintenanceOnce(`
        // and `identity.identityRefusedByLedger()` on the first cut — so a return type is required,
        // a `.` immediately before the name is excluded by the required whitespace (which is what
        // rejects `identity.identityRefusedByLedger()`), and a `return` anywhere on the line
        // disqualifies it (which is what rejects `return runMaintenanceOnce(`). The return type
        // itself MAY contain a `.` — `MlsMaintenancePolicy.Refresh` does, and excluding it was the
        // second wrong cut. The ambiguity check below still FAILS rather than picking one, and
        // it is what caught those two: a selector that silently chooses cannot be trusted.
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?m)^\\s*(?!.*\\breturn\\b)(?:(?:public|private|protected|static|final)\\s+)*"
                        + "[A-Za-z_][A-Za-z0-9_<>\\[\\],.\\s]*\\s"
                        + java.util.regex.Pattern.quote(method) + "\\s*\\(").matcher(src);
        final java.util.List<Integer> decls = new java.util.ArrayList<>();
        while (m.find()) decls.add(Integer.valueOf(m.start()));
        assertTrue("ZERO HITS MUST FAIL: no declaration of " + method + " in " + rel,
                !decls.isEmpty());
        if (decls.size() != 1) {
            fail("ambiguous: " + decls.size() + " declarations of " + method + " at " + decls);
        }
        final int open = src.indexOf('{', decls.get(0).intValue());
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
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
