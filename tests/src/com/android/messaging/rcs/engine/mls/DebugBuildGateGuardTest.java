/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code debug.*} knob that dumps message data or key material, relaxes a check, or points the
 * carrier stack somewhere else, is read only on a debug build: adb can set a {@code debug.*}
 * property on a user build, but not {@code ro.debuggable}. Each read must follow a debug-build test
 * in its own statement, or sit in an if that requires one. Positional, because the property is
 * that the test comes first, not that both appear somewhere in the file.
 */
public class DebugBuildGateGuardTest {

    private static final String ENGINE = "engine/src/com/android/messaging/rcs/engine/mls/";
    private static final String CARRIER = "src/com/android/messaging/rcs/carrier/";
    private static final String E2EE = "src/com/android/messaging/rcs/e2ee/";

    /**
     * File, knob as it appears in the read (a literal or a constant name), and the number of reads.
     * Counted, so a new read of a listed knob fails here until it is looked at.
     * {@code debug.rcs.mls_resend_prefix} is not listed: it is read only after the gated
     * {@code mls_resend_tag} has returned for a user build. Nor are the debug-configuration reads
     * in {@code CarrierStackDrModeDriver}, which sit behind an early return for a user build.
     * {@code persist.rcs.mls_allowed_peers} is listed although it is not a {@code debug.*} knob:
     * the G1 allowlist is a debug-build restriction, and a user build must never read one.
     */
    private static final Object[][] KNOBS = {
        {ENGINE + "MlsConfig.java", "KEY_DUMP_AAD", 2},
        {ENGINE + "MlsConfig.java", "KEY_DUMP_KP", 2},
        {ENGINE + "MlsConfig.java", "KEY_SAN_IDENTITY_CHECK", 1},
        {ENGINE + "MlsConfig.java", "KEY_METADATA_KEYS_EXT", 1},
        {ENGINE + "MlsInboundDecrypt.java", "\"debug.rcs.mls_log_plaintext\"", 1},
        {ENGINE + "MlsPayloadCorruptor.java", "\"debug.rcs.mls_resend_tag\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_dump_sig\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_iver\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_status\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_inner2\"", 2},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_inner2_pos\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_reportid\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_selfid\"", 1},
        {ENGINE + "MlsImdnSigner.java", "\"debug.rcs.mls_ftd_moment\"", 1},
        {ENGINE + "OpenMlsNative.java", "\"debug.rcs.mls_pop_lenient\"", 1},
        {CARRIER + "CarrierSipRegistrar.java", "CarrierSipPlane.SYSPROP", 1},
        {CARRIER + "CarrierSipRegistrar.java", "\"debug.rcs.dr.localport\"", 1},
        {CARRIER + "CarrierSipRegistrar.java", "\"debug.rcs.dr.expires\"", 1},
        {CARRIER + "CarrierSipRegistrar.java", "\"debug.rcs.dr.noauth\"", 1},
        {CARRIER + "CarrierSipRegistrar.java", "\"debug.rcs.dr.imsapn\"", 1},
        {CARRIER + "CarrierRcsTransport.java", "\"debug.rcs.dr.ftip\"", 2},
        {CARRIER + "CarrierRcsTransport.java", "\"debug.rcs.dr.msrptls\"", 1},
        {CARRIER + "CarrierImsMode.java", "\"debug.rcs.dr.force\"", 1},
        {CARRIER + "CarrierImsService.java", "\"debug.rcs.dr.user\"", 1},
        {E2EE + "MlsPeerGuard.java", "PROP_ALLOWED_PEERS", 1},
    };

    /** A debug-build test in code, the literal aside. */
    private static final Pattern DEBUG_TEST =
            Pattern.compile("\\b(?:debuggableBuild|isDebugBuild)\\s*\\(");
    /** A local boolean, so an alias of a debug-build test counts as one. */
    private static final Pattern BOOLEAN_LOCAL =
            Pattern.compile("\\bboolean\\s+([A-Za-z_$][\\w$]*)\\s*=([^;]*);");

    @Test
    public void everyListedKnobIsReadOnlyBehindADebugBuildTest() throws IOException {
        final List<String> faults = new ArrayList<>();
        for (final Object[] k : KNOBS) {
            final String file = (String) k[0];
            final String knob = (String) k[1];
            final int expected = (Integer) k[2];
            final String src = SourceScan.read(file);
            final List<Integer> reads = readsOf(src, knob);
            assertEquals("reads of " + knob + " in " + file + ". A new read must be gated like "
                    + "the others and counted here; zero means this guard lost its subject",
                    expected, reads.size());
            for (final int at : reads) {
                if (!gated(src, at)) faults.add(file + ": " + knob + " at line " + lineOf(src, at));
            }
        }
        if (!faults.isEmpty()) {
            fail("a debug knob that dumps data or relaxes a check is read without a debug-build "
                    + "test before it. Put shell.sysprops().debuggableBuild() (engine) or "
                    + "RcsDebug.isDebugBuild() (app) first in the same statement, or around it "
                    + "in an if:\n  " + String.join("\n  ", faults));
        }
    }

    /** Both implementations read {@code ro.debuggable}, which adb cannot set on a user build. */
    @Test
    public void theDebugBuildTestsReadRoDebuggable() throws IOException {
        assertTrue("RcsDebug.isDebugBuild() must read ro.debuggable",
                SourceScan.read("src/com/android/messaging/rcs/RcsDebug.java")
                        .contains("return SystemProperties.getInt(\"ro.debuggable\", 0) == 1;"));
        assertTrue("MlsConfig's debuggableBuild(Source) must read ro.debuggable",
                SourceScan.read(ENGINE + "MlsConfig.java")
                        .contains("return s.getInt(\"ro.debuggable\", 0) == 1;"));
        assertTrue("the app's MlsSysProps must answer debuggableBuild() from RcsDebug",
                SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(
                        "src/com/android/messaging/rcs/e2ee/MlsAndroidSysProps.java")),
                        "debuggableBuild").contains("RcsDebug.isDebugBuild()"));
    }

    /** The scan must be able to fail. */
    @Test
    public void theScanCatchesAnUngatedRead() {
        final String k = "\"debug.rcs.mls_dump_sig\"";
        final String[] ungated = {
            "void f() { final boolean x = p.getBoolean(" + k + ", false); }",
            // The test after the read does not stop it being read.
            "void f() { final boolean x = p.getBoolean(" + k + ", false) && debuggableBuild(); }",
            // A test in an earlier statement does not gate this one.
            "void f() { final boolean d = x; debuggableBuild();"
                    + " if (y) { p.get(" + k + ", \"\"); } }",
        };
        for (final String s : ungated) {
            final List<Integer> reads = readsOf(s, k);
            assertEquals(1, reads.size());
            assertTrue("must flag: " + s, !gated(s, reads.get(0)));
        }
        final String[] ok = {
            "void f() { final boolean x = debuggableBuild() && p.getBoolean(" + k + ", false); }",
            "void f() { if (RcsDebug.isDebugBuild()) { log(p.get(" + k + ", \"\")); } }",
            "void f() { final boolean d = p.debuggableBuild(); final boolean e = y && d;"
                    + " final int i = e ? p.getInt(" + k + ", -1) : -1; }",
            "void f() { final boolean x = SystemProperties.getInt(\"ro.debuggable\", 0) == 1"
                    + " && SystemProperties.getBoolean(" + k + ", false); }",
        };
        for (final String s : ok) {
            assertTrue("must pass: " + s, gated(s, readsOf(s, k).get(0)));
        }
        assertTrue("a mention outside a property read is not a read",
                readsOf("void f() { log(\"set " + k.replace("\"", "") + "\"); }", k).isEmpty());
    }

    // ---- the scan ----

    /** Offsets of {@code knob} as the key argument of a {@code get*(} call, outside comments. */
    private static List<Integer> readsOf(final String src, final String knob) {
        final String code = SourceScan.codeOnly(src);
        final Matcher m = Pattern.compile("\\.get(?:Boolean|Int|Long)?\\s*\\(\\s*("
                + Pattern.quote(knob) + ")(?![\\w$])").matcher(src);
        final List<Integer> out = new ArrayList<>();
        while (m.find()) {
            // codeOnly blanks comments and keeps the quotes and the code, so a hit in a comment
            // shows up as a blank at the call.
            if (code.charAt(m.start()) == '.') out.add(m.start(1));
        }
        return out;
    }

    /**
     * True when a debug-build test, or a local boolean derived from one, comes before {@code at}
     * in its statement, or in the condition of the innermost enclosing if.
     */
    static boolean gated(final String src, final int at) {
        final String code = SourceScan.codeOnly(src);
        final Set<String> aliases = aliases(src, code);
        int start = at;
        while (start > 0 && ";{}".indexOf(code.charAt(start - 1)) < 0) start--;
        if (tests(src, code, start, at, aliases)) return true;
        return insideGatedIf(src, code, at, aliases);
    }

    private static boolean tests(final String src, final String code, final int from,
            final int to, final Set<String> aliases) {
        final String span = code.substring(from, to);
        if (DEBUG_TEST.matcher(span).find()) return true;
        // The literal is blanked in code; the quote left at its position shows it is not a comment.
        for (int i = src.indexOf("\"ro.debuggable\"", from); i >= 0 && i < to;
                i = src.indexOf("\"ro.debuggable\"", i + 1)) {
            if (code.charAt(i) == '"') return true;
        }
        for (final String a : aliases) {
            // A use, not the declaration being assigned.
            if (Pattern.compile("(?<![\\w$.])" + Pattern.quote(a) + "(?![\\w$])(?!\\s*=[^=])")
                    .matcher(span).find()) {
                return true;
            }
        }
        return false;
    }

    /** Locals assigned from a debug-build test or from another such local, to a fixed point. */
    private static Set<String> aliases(final String src, final String code) {
        final Set<String> out = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            final Matcher m = BOOLEAN_LOCAL.matcher(code);
            while (m.find()) {
                if (!out.contains(m.group(1))
                        && tests(src, code, m.start(2), m.end(2), out)) {
                    grew = out.add(m.group(1));
                }
            }
        }
        return out;
    }

    /** As in {@link MessageContentLogGuardTest}: the innermost block around {@code at}. */
    private static boolean insideGatedIf(final String src, final String code, final int at,
            final Set<String> aliases) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            final char c = code.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth-- > 0) continue;
                final int ifAt = lastIfBefore(code, i);
                if (ifAt < 0) return false;
                final String cond = code.substring(ifAt, i);
                if (cond.indexOf(';') >= 0 || cond.indexOf('{') >= 0 || cond.indexOf('}') >= 0) {
                    return false;
                }
                return tests(src, code, ifAt, i, aliases);
            }
        }
        return false;
    }

    private static int lastIfBefore(final String code, final int at) {
        for (int i = code.lastIndexOf("if", at); i >= 0; i = code.lastIndexOf("if", i - 1)) {
            final boolean wordStart = i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1));
            int j = i + 2;
            while (j < code.length() && Character.isWhitespace(code.charAt(j))) j++;
            if (wordStart && j < code.length() && code.charAt(j) == '(') return i;
            if (i == 0) break;
        }
        return -1;
    }

    private static int lineOf(final String src, final int at) {
        return 1 + (int) src.substring(0, at).chars().filter(c -> c == '\n').count();
    }
}
