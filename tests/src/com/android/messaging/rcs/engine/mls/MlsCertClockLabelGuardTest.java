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
 * Every certificate-age number the {@code --ez membervalidity} probe prints names the clock it came
 * from: the client certificate ({@code kds_cert_expires_ms}), the published KeyPackage pool, or the
 * group-stored leaf a conversation carries (the one validated on a Commit). The three routinely
 * disagree, and an unlabelled number is misread as another.
 *
 * <p>Keys on a log label deliberately, since the label is the property and the transport has no
 * host test. The method is located through {@link SourceScan#declarations} and brace-matched over
 * {@link SourceScan#codeOnly}, with zero hits failing; assertions read the raw text at those
 * offsets, because {@code codeOnly} blanks string-literal contents.
 */
@RunWith(JUnit4.class)
public class MlsCertClockLabelGuardTest {

    private static final String METHOD = "dumpMemberValidity";

    /**
     * The bare spelling that is not allowed. Quoted with its delimiters so it cannot match a
     * comment.
     */
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

        // The tail's labels are asserted too, so the line cannot be simplified back to one unnamed
        // quantity by stripping these instead.
        assertTrue("the self-leaf tail must keep naming both of ITS clocks",
                body.contains("\" groupRemainingDays=\"")
                        && body.contains("\" clientRemainingDays=\""));
    }

    /**
     * The method's body as raw source, located and brace-matched over {@code codeOnly} so the
     * bounds cannot be thrown off by a brace inside a comment or a string literal.
     */
    private static String rawBodyOf(final String name) throws Exception {
        // The transport as if unsplit: dumpMemberValidity lives in MlsTransportDiagnostics.
        final String raw = SourceScan.transportAsUnsplit();
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
