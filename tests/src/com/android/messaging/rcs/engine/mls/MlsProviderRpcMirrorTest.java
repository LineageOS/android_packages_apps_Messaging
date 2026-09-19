/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * MlsProviderRpc's value mirrors match the AIDL types: same constants and values, same public
 * fields, and a byte-identical {@code toString}. Read from the AIDL source because the Parcelables
 * are not on the host classpath. See docs/testing.md.
 */
public final class MlsProviderRpcMirrorTest {
    private static final String AIDL = "aidl/src/java/org/lineageos/rcs/provider/";
    private static final String MIRROR =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsProviderRpc.java";

    @Test
    public void everyMirrorCarriesTheContractsConstantsAndFields() throws Exception {
        check("RcsMlsControlResult", MlsProviderRpc.ControlResult.class);
        check("RcsSendResult", MlsProviderRpc.SendResult.class);
        check("RcsMlsTransportProfile", MlsProviderRpc.TransportProfile.class);
        check("RcsMlsClaimResult", MlsProviderRpc.ClaimResult.class);
    }

    @Test
    public void everyMirrorPrintsExactlyWhatTheContractPrints() throws IOException {
        final String mirror = SourceScan.read(MIRROR);
        int compared = 0;
        for (final String[] pair : new String[][] {{"RcsMlsControlResult", "ControlResult"},
                {"RcsMlsTransportProfile", "TransportProfile"}, {"RcsMlsClaimResult",
                "ClaimResult"}}) {
            final String theirs = toStringBody(SourceScan.read(AIDL + pair[0] + ".java"));
            final String ours = toStringBody(classBody(mirror, pair[1]));
            assertTrue(pair[0] + " has no toString to compare", !theirs.isEmpty());
            assertEquals(pair[1] + ".toString must be " + pair[0] + "'s, verbatim", theirs, ours);
            compared++;
        }
        assertEquals(3, compared);
        assertEquals("RcsSendResult declares no toString, so neither may its mirror", "",
                toStringBody(SourceScan.read(AIDL + "RcsSendResult.java"))
                + toStringBody(classBody(mirror, "SendResult")));
    }

    private static void check(final String aidl, final Class<?> mirror) throws Exception {
        final String src = SourceScan.codeOnly(SourceScan.read(AIDL + aidl + ".java"));
        final Map<String, Integer> theirs = new TreeMap<>();
        final Matcher c = Pattern.compile("public static final int (\\w+)\\s*=\\s*(-?\\d+)\\s*;")
                .matcher(src);
        while (c.find()) theirs.put(c.group(1), Integer.valueOf(c.group(2)));
        final Map<String, Integer> ours = new TreeMap<>();
        final TreeSet<String> ourFields = new TreeSet<>();
        for (final Field f : mirror.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers())) continue;
            if (Modifier.isStatic(f.getModifiers())) {
                if (f.getType() == int.class) ours.put(f.getName(), f.getInt(null));
            } else {
                ourFields.add(f.getName());
            }
        }
        assertEquals(aidl + "'s constants", theirs, ours);
        final TreeSet<String> theirFields = new TreeSet<>();
        final Matcher f = Pattern.compile("public final [\\w\\[\\]<>]+ (\\w+);").matcher(src);
        while (f.find()) theirFields.add(f.group(1));
        assertTrue(aidl + " declares no public fields — the scan is broken",
                !theirFields.isEmpty());
        assertEquals(aidl + "'s public fields", theirFields, ourFields);
    }

    /** The whitespace-normalised body of the toString declared in {@code src}, or "". */
    private static String toStringBody(final String src) {
        final Matcher m = Pattern.compile("public String toString\\(\\)\\s*\\{").matcher(src);
        if (!m.find()) return "";
        int depth = 1, i = m.end();
        while (depth > 0) {
            final char ch = src.charAt(i++);
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
        }
        return src.substring(m.end(), i - 1).replaceAll("\\s+", " ").trim();
    }

    /** The body of the nested class {@code name} in {@code src}. */
    private static String classBody(final String src, final String name) {
        final int at = src.indexOf("final class " + name + " {");
        assertTrue("no nested " + name, at >= 0);
        int depth = 0, i = src.indexOf('{', at);
        final int open = i;
        do {
            final char ch = src.charAt(i++);
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
        } while (depth > 0);
        return src.substring(open, i);
    }
}
