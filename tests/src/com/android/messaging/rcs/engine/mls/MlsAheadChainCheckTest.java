/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * {@link MlsAheadChainCheck}: {@code Health.AHEAD} tells "further along the same chain" from "on a
 * different chain", and the ways of not knowing stay apart from the one way of knowing. A fork
 * verdict demotes AHEAD to DIVERGED and aborts the maintenance pass, so it must never be inferred
 * from missing data. See docs/mls/health-and-recovery.md.
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
     * No state that is not a measurement reaches the fork verdict in either direction: not
     * DIFFERENT_CHAIN (which would abort a healthy maintenance pass) and not SAME_CHAIN (which
     * would assert a chain identity nobody established).
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

    /** Each non-answer names itself, so the log can say which one it was. */
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
        // A refusal outranks an absent answer: nothing was asked, so reporting the server answer as
        // absent would present our budget as a fact about the server.
        assertEquals(MlsAheadChainCheck.Verdict.LOOK_REFUSED,
                MlsAheadChainCheck.decide(A, true, B));
    }

    /**
     * The era gate above this arm is {@code server[0] >= 0 && localEra != server[0]}, so an
     * unreadable server era skips it and AHEAD is reachable with the eras never compared. The
     * server-era check therefore comes first, even when the retained era would have matched.
     */
    @Test
    public void theServerEraHoleIsCheckedBeforeTheRetainedEra() {
        assertEquals("an unreadable server era must be named as such, not answered from a retained "
                        + "map that happens to be populated",
                MlsAheadChainCheck.Verdict.SERVER_ERA_UNKNOWN,
                MlsAheadChainCheck.blockedBefore(-1L, 5, A));
        // ...including when the retained era is also unknown; the server-era reason is reported.
        assertEquals(MlsAheadChainCheck.Verdict.SERVER_ERA_UNKNOWN,
                MlsAheadChainCheck.blockedBefore(-1L, MlsConversationRecord.ERA_UNKNOWN, null));
    }

    /**
     * A record from before era stamping keeps its entries and only the first write stamps an era,
     * so a non-current entry may belong to an older era.
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

    /**
     * {@code healthAgainstServer}'s above-the-server arm calls {@link MlsAheadChainCheck} and does
     * not return a bare {@code notAsked(Health.AHEAD)}. Source scan, because that method has no
     * host test.
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
        assertTrue(
                        "aheadOrForked no longer demotes a detected fork to DIVERGED — which is the ONLY "
                        + "thing that makes the maintenance fork abort fire on this arm",
                arm.contains("MlsAheadChainCheck.isFork(") && arm.contains("Health.DIVERGED"));
    }

    /**
     * {@code runMaintenanceOnce} still aborts the pass on {@code Health.DIVERGED}; demoting a
     * forked AHEAD helps only because of that abort.
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

    /**
     * Whitespace- and comment-stripped body of the single declaration named; zero or two hits fail.
     */
    private static String bodyOf(final String rel, final String method) throws IOException {
        // The transport as if unsplit: aheadOrForked moved to MlsAheadChainCheck.
        final String src = rel.equals(TRANSPORT)
                ? com.android.messaging.rcs.SourceScan.transportAsUnsplit() : read(rel);
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
                // Comments are stripped before whitespace, so a comment quoting an asserted
                // expression cannot satisfy a matcher.
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
