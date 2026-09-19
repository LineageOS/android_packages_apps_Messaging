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
 * Every certificate-age number the {@code --ez membervalidity} probe prints must name WHICH CLOCK it
 * came from.
 *
 * <h2>Why this is a guard and not a style note</h2>
 *
 * <p>Three artefacts carry a remaining-lifetime and routinely disagree on one device at one moment:
 *
 * <ol>
 *   <li>the <b>client certificate</b> the device holds ({@code kds_cert_expires_ms}) — 73d;</li>
 *   <li>the <b>KDS pool</b> it has published, which only a claim reads — 41.7d;</li>
 *   <li>the <b>group-stored leaf</b> a conversation carries — 24d, and the one the server validates
 *       on a Commit.</li>
 * </ol>
 *
 * <p>This probe prints (3). It used to print it as a bare {@code remainingDays=} in a line whose own
 * tail labels {@code groupRemainingDays}/{@code clientRemainingDays} — the same quantity named in
 * one half of the line and left unnamed in the other. Unlabelled numbers of this kind are how a
 * roster reading became "the fleet sits at 25 days" and was quoted onward as a statement about the
 * fleet's certificates; the engine-side twin of the defect had the two clocks rendering to
 * byte-identical strings, {@code notRepublished=[+15715550103 41d]} against
 * {@code below=[+15715550103 24d]}, in one document.
 *
 * <h2>How this meets {@code SourceScan}'s two rules</h2>
 *
 * <p><b>It keys on a log label, which that class explicitly cautions against</b>, and does so
 * knowingly: here the label IS the property. There is no behavioural form to assert instead —
 * {@code MlsProviderTransport} needs a {@code Context} and a bound provider, so it has no host test,
 * and the artefact this fix produces is a string a human reads in a capture.
 *
 * <p><b>Zero hits FAIL.</b> The method is located through {@link SourceScan#declarations} and
 * brace-matched, and both are asserted before any content check, so a rename cannot turn this into
 * a guard that silently passes on nothing.
 *
 * <p>The brace match runs over {@link SourceScan#codeOnly} — a brace inside a comment or a string
 * cannot end the body early — while the assertions read the RAW text at those offsets, because
 * {@code codeOnly} blanks string-literal CONTENTS and a check for a literal against blanked text is
 * a check that cannot fail.
 */
@RunWith(JUnit4.class)
public class MlsCertClockLabelGuardTest {

    private static final String METHOD = "dumpMemberValidity";

    /** The bare spelling the fix removed. Quoted with its delimiters so it cannot match a comment. */
    private static final String BARE = "\" remainingDays=\"";

    @Test
    public void theMemberValidityProbeNamesWhichClockEachNumberCameFrom() throws Exception {
        final String body = rawBodyOf(METHOD);

        assertTrue("the per-leaf window must name its artefact — it is the GROUP-STORED leaf, not "
                        + "the certificate the device holds and not the leaf its pool serves. "
                        + "Expected a \" groupLeafDays=\" label in " + METHOD + ".",
                body.contains("\" groupLeafDays=\""));

        assertEquals("a bare " + BARE + " in " + METHOD + " names a clock-bearing number without "
                        + "saying which clock. Label it (groupLeafDays=) rather than removing the "
                        + "number.",
                0, SourceScan.count(body, BARE));

        // The tail already did this right, and is asserted so a future edit cannot "simplify" the
        // line back to one unnamed quantity by stripping these instead of the one above.
        assertTrue("the self-leaf tail must keep naming both of ITS clocks",
                body.contains("\" groupRemainingDays=\"")
                        && body.contains("\" clientRemainingDays=\""));
    }

    /**
     * The method's body as RAW source, located and brace-matched over {@code codeOnly} so the bounds
     * cannot be thrown off by a brace inside a comment or a string literal.
     */
    private static String rawBodyOf(final String name) throws Exception {
        final String raw = SourceScan.read(SourceScan.TRANSPORT);
        final String code = SourceScan.codeOnly(raw);
        assertEquals("codeOnly must preserve offsets for the slice below to be meaningful",
                raw.length(), code.length());

        int open = -1;
        for (final int[] d : SourceScan.declarations(code)) {
            if (code.substring(d[2], d[3]).equals(name)) {
                open = d[1];
                break;
            }
        }
        assertTrue("ZERO HITS MUST FAIL: no class-level declaration of " + name + " in "
                + SourceScan.TRANSPORT + " — if it was renamed, rename it here too rather than "
                + "letting this guard pass on nothing.", open >= 0);

        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return raw.substring(open, i + 1);
            }
        }
        throw new AssertionError(name + "'s body did not brace-match to a close");
    }
}
