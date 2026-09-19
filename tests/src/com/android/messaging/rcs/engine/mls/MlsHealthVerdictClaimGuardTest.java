/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A health verdict does not assert what nothing establishes. Below the server's epoch nothing can
 * show we are on the same chain: {@code serverStateCheck} compares current authenticators, and the
 * server serves only its current one. So the verdict is {@code LOWER_EPOCH_CHAIN_UNKNOWN}, a
 * {@code BEHIND} claim must not return, and the dispatch handles the verdict explicitly.
 */
@RunWith(JUnit4.class)
public class MlsHealthVerdictClaimGuardTest {

    private static final String VERDICT = "LOWER_EPOCH_CHAIN_UNKNOWN";

    @Test
    public void theRetiredClaimCannotComeBackAndTheNewVerdictCannotBeAbsorbed() throws Exception {
        // The unsplit view: reconcileAction is in MlsConversationRebuild.
        final String code = SourceScan.transportUnsplitCode();
        // The enum is in MlsTransportTypes; the dispatch is read through the unsplit view.
        final String typesRaw = SourceScan.read(SourceScan.TRANSPORT_TYPES);
        final String typesCode = SourceScan.codeOnly(typesRaw);
        assertEquals("codeOnly must preserve offsets", typesRaw.length(), typesCode.length());

        final int e = typesCode.indexOf("public enum Health {");
        assertTrue("ZERO HITS MUST FAIL: no `public enum Health {` in " + SourceScan.TRANSPORT_TYPES
                + " — if it moved or was renamed, update this guard rather than letting it pass on "
                + "nothing.", e >= 0);
        final String enumBody =
                typesRaw.substring(e, braceEnd(typesCode, typesCode.indexOf('{', e)) + 1);
        assertTrue("the Health enum must declare " + VERDICT, enumBody.contains(VERDICT));

        // BEHIND asserted membership of the server's chain.
        assertEquals("Health must NOT declare BEHIND again: it asserted 'an earlier "
                        + "value on the SAME chain', and below the server's epoch nothing "
                        + "establishes that — the DIVERGED test can only answer DIFFERS there. If a "
                        + "discriminator has since been built, say so here and name it.",
                -1, declarationIndex(enumBody));

        // The dispatch, read over code only: both assertions are about code.
        final String reconcile = bodyOf(code, "reconcileAction");
        assertTrue("the reconcile dispatch must handle " + VERDICT + " EXPLICITLY — an unhandled "
                        + "verdict falls past the switch and is absorbed into whatever follows, "
                        + "which is the silently-resolved ambiguity this is about",
                reconcile.contains("case " + VERDICT + ":"));
        // The catch-all stays joined to `case UNKNOWN:`, whose arm returns PENDING, so an
        // unforeseen verdict lands on the conservative arm.
        assertTrue("the reconcile dispatch's `default:` must remain paired with `case UNKNOWN:` so "
                        + "an unforeseen verdict lands on the conservative PENDING arm rather than "
                        + "on a specific one nobody chose for it",
                reconcile.contains("case UNKNOWN:")
                        && reconcile.replaceAll("\\s+", " ").contains("case UNKNOWN: default:"));
    }

    /** Index of a bare {@code BEHIND} enum-constant declaration, or -1. */
    private static int declarationIndex(final String enumBody) {
        for (final String form : new String[] {" BEHIND,", "\nBEHIND,", "{BEHIND,", " BEHIND;",
                " BEHIND ", "\n        BEHIND"}) {
            final int i = enumBody.indexOf(form);
            if (i >= 0) return i;
        }
        return -1;
    }

    /** A method's body from source whose comments and string contents are already blanked. */
    private static String bodyOf(final String code, final String name) {
        int open = -1;
        for (final int[] d : SourceScan.declarations(code)) {
            if (code.substring(d[2], d[3]).equals(name)) {
                open = d[1];
                break;
            }
        }
        assertTrue("ZERO HITS MUST FAIL: no class-level declaration of " + name, open >= 0);
        return code.substring(open, braceEnd(code, open) + 1);
    }

    private static int braceEnd(final String code, final int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("unbalanced braces from " + open);
    }
}
