/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The compiled-in test-network root, its intermediate and that network's KDS are reachable only
 * on a debug build. A user build trusts only the provider's verified anchors, and without them runs
 * no carrier-path MLS (see {@code MlsCarrierTrust}). Three halves, each able to fail alone:
 * <ol>
 *   <li>the values live in {@code MlsTrustAnchors} and in no other source file, comments included;
 *   <li>every read of them there follows {@link com.android.messaging.rcs.RcsDebug#isDebugBuild()}
 *       in its statement or its if, positionally, as {@link DebugBuildGateGuardTest} judges it;
 *   <li>every caller of the {@code MlsCarrierTrust} decisions passes
 *       {@code RcsDebug.isDebugBuild()} as the debug-build argument, not a constant or a knob.
 * </ol>
 * Counted, so a new read or call fails here until it is looked at, and zero hits fail too.
 */
public class TestNetworkTrustGuardTest {

    private static final String ANCHORS =
            "src/com/android/messaging/rcs/e2ee/MlsTrustAnchors.java";
    private static final String CARRIER = "src/com/android/messaging/rcs/carrier/";

    /** The production source trees. */
    private static final String[] PRODUCTION = {"src", "engine/src", "selftest/src", "aidl/src"};

    /**
     * Distinctive fragments of the compiled-in values: the root's and the intermediate's first
     * base64 line, and the test network's KDS domain.
     */
    private static final String[] MARKERS = {
        "MIICBTCCAYygAwIBAgIUTdwsz0lXB1LtfXoaMkoP6dYaQmQw",
        "MIIC+jCCAn+gAwIBAgIUU9gajdSqP+m7H+1yu4yNeoJ5HcAw",
        "mcc001.pub.3gppnetwork.org",
    };

    /** The constants holding them in {@link #ANCHORS}, and how many reads each has. */
    private static final Object[][] CONSTANTS = {
        {"ROOT_DER_B64", 1},
        {"ICA_DER_B64", 1},
        {"TEST_NETWORK_KDS_URL", 1},
    };

    /**
     * Every caller of a decision that takes the debug-build test: file, decision, number of calls.
     * The debug-build argument is the second.
     */
    private static final Object[][] DECISIONS = {
        {ANCHORS, "anchors", 1},
        {CARRIER + "CarrierRcsTransport.java", "kdsBaseUrl", 1},
        {CARRIER + "CarrierImsTransport.java", "readyInApp", 1},
    };

    private static final String DEBUG_ARG = "RcsDebug.isDebugBuild()";

    @Test
    public void theCompiledInValuesAppearOnlyInMlsTrustAnchors() throws IOException {
        final TreeSet<String> holders = new TreeSet<>();
        int scanned = 0;
        for (final String dir : PRODUCTION) {
            for (final File f : SourceScan.javaSourcesUnder(dir)) {
                scanned++;
                final String src = new String(Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                for (final String m : MARKERS) {
                    if (src.contains(m)) holders.add(relative(f) + " (" + m + ")");
                }
            }
        }
        assertTrue("scanned only " + scanned + " files", scanned > 500);
        final TreeSet<String> expected = new TreeSet<>();
        for (final String m : MARKERS) expected.add(ANCHORS + " (" + m + ")");
        assertEquals("the test network's root, intermediate and KDS must live only in "
                + "MlsTrustAnchors, behind its debug-build test. Reach them through "
                + "MlsTrustAnchors.compiledIn*() instead of copying them", expected, holders);
    }

    @Test
    public void everyReadOfTheCompiledInValuesIsBehindTheDebugBuildTest() throws IOException {
        final String src = SourceScan.read(ANCHORS);
        final List<String> faults = new ArrayList<>();
        for (final Object[] c : CONSTANTS) {
            final String name = (String) c[0];
            final List<Integer> reads = readsOf(src, name);
            assertEquals("reads of " + name + " in " + ANCHORS + ". A new read must be gated "
                    + "and counted here; zero means this guard lost its subject",
                    (int) (Integer) c[1], reads.size());
            for (final int at : reads) {
                if (!DebugBuildGateGuardTest.gated(src, at)) {
                    faults.add(name + " at line "
                            + (1 + src.substring(0, at).chars().filter(ch -> ch == '\n').count()));
                }
            }
        }
        if (!faults.isEmpty()) {
            fail("a compiled-in test-network value is read without RcsDebug.isDebugBuild() "
                    + "before it in its statement or its if: " + faults);
        }
    }

    @Test
    public void everyTrustDecisionIsGivenTheRealDebugBuildTest() throws IOException {
        final TreeSet<String> expected = new TreeSet<>();
        for (final Object[] d : DECISIONS) expected.add(d[0] + " " + d[1] + " x" + d[2]);
        final TreeSet<String> found = new TreeSet<>();
        final List<String> faults = new ArrayList<>();
        for (final String dir : PRODUCTION) {
            for (final File f : SourceScan.javaSourcesUnder(dir)) {
                final String src = new String(Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8);
                for (final Object[] d : DECISIONS) {
                    final String decision = (String) d[1];
                    final List<List<String>> calls = callsOf(src, decision);
                    if (calls.isEmpty()) continue;
                    found.add(relative(f) + " " + decision + " x" + calls.size());
                    for (final List<String> args : calls) {
                        if (args.size() < 2 || !DEBUG_ARG.equals(args.get(1))) {
                            faults.add(relative(f) + ": " + decision + args);
                        }
                    }
                }
            }
        }
        assertEquals("the callers of the carrier trust decisions. A new caller must pass "
                + DEBUG_ARG + " and be counted here; a missing one means this guard lost its "
                + "subject", expected, found);
        if (!faults.isEmpty()) {
            fail("the debug-build argument of a carrier trust decision must be " + DEBUG_ARG
                    + ", which reads ro.debuggable: " + faults);
        }
    }

    /** Each half must be able to fail. */
    @Test
    public void theScansCatchWhatTheyAreFor() {
        final String ungated = "class A { static byte[] r() { return decode(ROOT_DER_B64); } }";
        assertEquals(1, readsOf(ungated, "ROOT_DER_B64").size());
        assertFalse("an ungated read must be flagged", DebugBuildGateGuardTest.gated(ungated,
                readsOf(ungated, "ROOT_DER_B64").get(0)));
        final String gated = "class A { static byte[] r() {"
                + " return RcsDebug.isDebugBuild() ? decode(ROOT_DER_B64) : null; } }";
        assertTrue(DebugBuildGateGuardTest.gated(gated, readsOf(gated, "ROOT_DER_B64").get(0)));
        assertTrue("the declaration is not a read", readsOf(
                "private static final String ROOT_DER_B64 = \"x\";", "ROOT_DER_B64").isEmpty());

        final List<List<String>> forced = callsOf("boolean b = MlsCarrierTrust.readyInApp("
                + "isAttached(), true);", "readyInApp");
        assertEquals(1, forced.size());
        assertEquals("true", forced.get(0).get(1));
        final List<List<String>> real = callsOf("x = MlsCarrierTrust.anchors(f(a, b),"
                + " RcsDebug.isDebugBuild(), MlsTrustAnchors::c);", "anchors");
        assertEquals(DEBUG_ARG, real.get(0).get(1));
    }

    // ---- the scans ----

    /** Offsets of {@code name} in code, the declaration aside. */
    private static List<Integer> readsOf(final String src, final String name) {
        final String code = SourceScan.codeOnly(src);
        final Matcher m = Pattern.compile("(?<![\\w$])" + Pattern.quote(name)
                + "(?![\\w$])(?!\\s*=[^=])").matcher(code);
        final List<Integer> out = new ArrayList<>();
        while (m.find()) out.add(m.start());
        return out;
    }

    /**
     * The argument lists of {@code MlsCarrierTrust.<decision>(...)} calls in code, each argument
     * trimmed and whitespace-collapsed. String literals keep their quotes and lose their content.
     */
    private static List<List<String>> callsOf(final String src, final String decision) {
        final String code = SourceScan.codeOnly(src);
        final Matcher m = Pattern.compile("\\bMlsCarrierTrust\\s*\\.\\s*" + Pattern.quote(decision)
                + "\\s*\\(").matcher(code);
        final List<List<String>> out = new ArrayList<>();
        while (m.find()) {
            final List<String> args = new ArrayList<>();
            int depth = 0;
            int from = m.end();
            for (int i = m.end(); i < code.length(); i++) {
                final char ch = code.charAt(i);
                if (ch == '(' || ch == '[' || ch == '{') {
                    depth++;
                } else if ((ch == ')' || ch == ']' || ch == '}') && depth > 0) {
                    depth--;
                } else if (ch == ')' || (ch == ',' && depth == 0)) {
                    args.add(code.substring(from, i).replaceAll("\\s+", " ").trim());
                    from = i + 1;
                    if (ch == ')') break;
                }
            }
            out.add(args);
        }
        return out;
    }

    /** The module-relative path of a file {@link SourceScan#javaSourcesUnder} returned. */
    private static String relative(final File f) {
        String p = f.getPath().replace(File.separatorChar, '/');
        for (final String prefix : new String[] {"packages/apps/Messaging/", "../", "./"}) {
            if (p.startsWith(prefix)) p = p.substring(prefix.length());
        }
        return p;
    }
}
