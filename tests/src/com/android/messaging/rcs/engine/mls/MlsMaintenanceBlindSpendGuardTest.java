/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A maintenance pass does not spend an era advance on a group whose fork test our own ledger
 * refused. {@code MAINTENANCE_IDENTITY} allows one look per window and passes run on every
 * conversation open, so a refused identity look is routine; the pass then reports in-sync on
 * numbers alone and must not advance or record the server's roster. The gate keys on the refusal,
 * not on the absence of the positive, which would also stop maintenance on the AHEAD,
 * LOWER_EPOCH_CHAIN_UNKNOWN and UNKNOWN arms. See docs/mls/budgets.md.
 */
@RunWith(JUnit4.class)
public class MlsMaintenanceBlindSpendGuardTest {

    /** Where runMaintenanceOnce is declared; the transport holds only a delegate. */
    private static final String PASS =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsMaintenancePass.java";

    @Test
    public void theEraAdvanceIsDeferredWhenOurOwnLedgerRefusedTheForkTest() throws IOException {
        final String body = bodyOf(PASS, "runMaintenanceOnce");

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
        // Positional: a check that runs after the spend is not a gate.
        assertTrue("the ledger-refusal gate is present but sits AFTER eraAdvance(), so the advance "
                        + "is already spent by the time it is consulted (gate@" + gateAt
                        + " advance@" + advanceAt + ")",
                gateAt < advanceAt);
    }

    @Test
    public void theGateIsTheREFUSALNotTheAbsenceOfThePositive() throws IOException {
        // ServerComparison is declared in MlsTransportTypes.
        final String accessor = bodyOf(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsTransportTypes.java",
                "identityRefusedByLedger");
        assertTrue("identityRefusedByLedger must key on REFUSED_BY_LEDGER. Widening it to the "
                        + "absence of the positive would also catch AHEAD, "
                        + "LOWER_EPOCH_CHAIN_UNKNOWN and UNKNOWN, and stop maintenance refreshing "
                        + "every conversation not exactly at the server's epoch.",
                accessor.contains("ServerState.REFUSED_BY_LEDGER"));
        assertTrue("identityRefusedByLedger must not be spelled as !serverConfirmedOurs()",
                !accessor.contains("serverConfirmedOurs"));

        // The roster baseline write stays gated on the positive; the two gates say different
        // things.
        final String pass = bodyOf(PASS, "runMaintenanceOnce");
        assertTrue("the roster baseline write is no longer gated on identity.serverConfirmedOurs() "
                        + " — that is a separate property from this one and it must "
                        + "not be absorbed into the refusal gate",
                pass.contains("identity.serverConfirmedOurs()"));
    }

    /** Whitespace- and comment-stripped body of the single declaration named. */
    private static String bodyOf(final String rel, final String method) throws IOException {
        final String src = read(rel);
        // Modifiers are optional (the accessor is package-private), so call sites are excluded by
        // structure: a return type is required, a `.` directly before the name is rejected, and a
        // line containing `return` is skipped. The return type may itself contain a `.`. More than
        // one match fails rather than picking one.
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
