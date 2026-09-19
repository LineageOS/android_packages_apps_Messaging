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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * A health verdict must not assert what nothing establishes.
 *
 * <h2>The claim that was retired</h2>
 *
 * <p>{@code Health.BEHIND} meant "an earlier value on the SAME chain, commits can still apply". At a
 * lower epoch nothing reachable establishes the "same chain" half: {@code serverStateCheck} compares
 * our CURRENT epoch's authenticator against the server's CURRENT one, and one epoch has exactly one
 * authenticator, so below the server's epoch it returns {@code DIFFERS} by construction — a check
 * that cannot come back "same". The question that would settle it cannot be asked, because
 * {@code fetchServerEpochAuthenticator} takes no epoch argument and the server serves only its
 * current anchor. So the verdict is {@code LOWER_EPOCH_CHAIN_UNKNOWN}: named for what is unknown
 * rather than for what is suspected.
 *
 * <h2>What this guards, and why a compiler is not enough</h2>
 *
 * <p>The RENAME is compiler-enforced — that is why it was done as a rename rather than as a value
 * added beside {@code BEHIND}, which would have left a constant nothing produced. What a compiler
 * cannot catch is someone RE-ADDING the retired claim later, or adding a {@code default:} arm that
 * silently absorbs the new verdict into whatever the old one did. Both are the "silently resolved
 * ambiguity" this exists to prevent, so both are asserted.
 *
 * <h2>On being a source scan</h2>
 *
 * <p>{@code MlsProviderTransport} needs a {@code Context} and a bound provider and has no host test,
 * so the enum and the dispatch are only reachable as source. The method and the enum are LOCATED
 * before anything is asserted and the locations are themselves assertions, so a rename cannot turn
 * this into a guard that passes on nothing. Located over {@code codeOnly} (a brace in a comment or a
 * string cannot end a body early), asserted over RAW text at those offsets, because {@code codeOnly}
 * blanks string-literal contents.
 */
@RunWith(JUnit4.class)
public class MlsHealthVerdictClaimGuardTest {

    private static final String VERDICT = "LOWER_EPOCH_CHAIN_UNKNOWN";

    @Test
    public void theRetiredClaimCannotComeBackAndTheNewVerdictCannotBeAbsorbed() throws Exception {
        final String raw = SourceScan.read(SourceScan.TRANSPORT);
        final String code = SourceScan.codeOnly(raw);
        assertEquals("codeOnly must preserve offsets", raw.length(), code.length());

        // ---- the enum ------------------------------------------------------------------------
        final int e = code.indexOf("public enum Health {");
        assertTrue("ZERO HITS MUST FAIL: no `public enum Health {` in " + SourceScan.TRANSPORT
                + " — if it moved or was renamed, update this guard rather than letting it pass on "
                + "nothing.", e >= 0);
        final String enumBody = raw.substring(e, braceEnd(code, code.indexOf('{', e)) + 1);
        assertTrue("the Health enum must declare " + VERDICT, enumBody.contains(VERDICT));

        // THE ASSERTION THIS CLASS EXISTS FOR. BEHIND asserted membership of the server's chain,
        // which nothing at a lower epoch establishes. Re-adding it re-adds the claim.
        assertEquals("Health must NOT declare BEHIND again: it asserted 'an earlier "
                        + "value on the SAME chain', and below the server's epoch nothing "
                        + "establishes that — the DIVERGED test can only answer DIFFERS there. If a "
                        + "discriminator has since been built, say so here and name it.",
                -1, declarationIndex(enumBody));

        // ---- the dispatch --------------------------------------------------------------------
        // OVER codeOnly, NOT RAW, and the difference is not cosmetic: both assertions below are
        // about CODE, and the first version of this guard read them off the raw text and failed on
        // a COMMENT containing the word it was looking for. Reading a code property out of prose is
        // the same mistake as reading a string literal out of blanked text, one direction over.
        final String reconcile = bodyOf(code, "reconcileAction");
        assertTrue("the reconcile dispatch must handle " + VERDICT + " EXPLICITLY — an unhandled "
                        + "verdict falls past the switch and is absorbed into whatever follows, "
                        + "which is the silently-resolved ambiguity this is about",
                reconcile.contains("case " + VERDICT + ":"));
        // THE CATCH-ALL MUST STAY THE CONSERVATIVE ONE. A first version of this guard demanded
        // there be NO `default:` at all; that was wrong, and reading the code rather than asserting
        // from the outside is what corrected it. The dispatch pairs `case UNKNOWN: default:` on one
        // arm returning PENDING — "we do not know that there is nothing to do, only that we could
        // not find out". That is the right home for an unforeseen verdict, so what is worth pinning
        // is not the absence of a default but that it remains joined to UNKNOWN: detached and given
        // its own body, it would start silently answering for values nobody classified.
        assertTrue("the reconcile dispatch's `default:` must remain paired with `case UNKNOWN:` so "
                        + "an unforeseen verdict lands on the conservative PENDING arm rather than "
                        + "on a specific one nobody chose for it",
                reconcile.contains("case UNKNOWN:")
                        && reconcile.replaceAll("\\s+", " ").contains("case UNKNOWN: default:"));
    }

    /** Index of a bare {@code BEHIND} enum-constant declaration, or -1. Commas/newlines only. */
    private static int declarationIndex(final String enumBody) {
        for (final String form : new String[] {" BEHIND,", "\nBEHIND,", "{BEHIND,", " BEHIND;",
                " BEHIND ", "\n        BEHIND"}) {
            final int i = enumBody.indexOf(form);
            if (i >= 0) return i;
        }
        return -1;
    }

    /** A method's body as EXECUTABLE source — comments and string contents already blanked. */
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
