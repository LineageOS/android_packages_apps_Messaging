/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every engine class is on the host-test classpath. {@code messaging-mls-policy-host} lists its
 * sources one by one in {@code Android.bp}, since the classes that import {@code android} cannot
 * compile on a host, and a class missing from that list still builds and ships with no host
 * coverage.
 *
 * <p>If this fails, add the class to {@code messaging-mls-policy-host}'s {@code srcs}; if it
 * imports {@code android}, add it to {@link #ANDROID_DEPENDENT} naming the import that forces the
 * exclusion. See docs/testing.md.
 */
public final class MlsEngineHostClasspathGuardTest {

    private static final String BP = "Android.bp";
    private static final String ENGINE_MLS = "engine/src/com/android/messaging/rcs/engine/mls";
    private static final String MODULE = "messaging-mls-policy-host";

    /**
     * The engine classes that cannot be on a host classpath: the JNI and session wrappers, which
     * import {@code android} or load the native library.
     */
    private static final String[] ANDROID_DEPENDENT = {
        "MlsEngine.java",            // the engine interface, over android-typed artifacts
        "MlsSession.java",           // ditto
        "MlsLog.java",               // android.util.Log
        "OpenMlsEngine.java",        // System.loadLibrary + android Context
        "OpenMlsNative.java",        // the JNI binding itself
        "OpenMlsSession.java",       // holds a native handle
        // No android import, but every method calls OpenMlsNative's bindings. The RCC.16 encoder it
        // fronts is tested in Rust (rcc16_build.rs) against an independent encoder.
        "Rcc16Der.java",
    };

    /** {@code "engine/src/.../mls/Something.java"} inside a {@code srcs: [...]} list. */
    private static final Pattern SRC_ENTRY = Pattern.compile(
            "\"engine/src/com/android/messaging/rcs/engine/mls/(\\w+\\.java)\"");

    @Test
    public void everyEngineMlsClassIsOnTheHostClasspathOrIsANamedExemption() throws IOException {
        final String block = moduleBlock(withoutComments(read(BP)));
        final Set<String> listed = new HashSet<>();
        final Matcher m = SRC_ENTRY.matcher(block);
        while (m.find()) listed.add(m.group(1));

        // A locator that found nothing would pass forever.
        assertTrue("no engine sources matched inside " + MODULE + " — the Android.bp shape has "
                + "changed and this guard is silently passing, which is worse than failing",
                listed.size() > 20);

        final List<String> onDisk = listFiles(ENGINE_MLS);
        assertTrue("no .java files found under " + ENGINE_MLS + " from "
                + new File(".").getAbsolutePath() + " — the locator is wrong, not the classpath",
                onDisk.size() > 20);

        final Set<String> exempt = new HashSet<>(Arrays.asList(ANDROID_DEPENDENT));
        final List<String> unlisted = new ArrayList<>();
        for (final String f : onDisk) {
            if (!listed.contains(f) && !exempt.contains(f)) unlisted.add(f);
        }
        Collections.sort(unlisted);
        if (!unlisted.isEmpty()) {
            fail("These engine classes are on NEITHER the host classpath NOR the exemption list, "
                    + "so nothing host-tests them and nothing said so: " + unlisted
                    + ". Add each to " + MODULE + "'s srcs in Messaging/" + BP + ", or — if it "
                    + "imports android — to ANDROID_DEPENDENT in this test WITH the import named. "
                    + "(" + listed.size() + " listed, " + onDisk.size() + " on disk, "
                    + exempt.size() + " exempt.)");
        }
    }

    /**
     * The other direction: a removed or renamed source leaves a dangling entry. That breaks the
     * build anyway, but the message here names the entry to delete.
     */
    @Test
    public void theHostClasspathNamesNoSourceThatIsGone() throws IOException {
        final Set<String> onDisk = new HashSet<>(listFiles(ENGINE_MLS));
        final Matcher m = SRC_ENTRY.matcher(moduleBlock(withoutComments(read(BP))));
        final List<String> dangling = new ArrayList<>();
        while (m.find()) {
            if (!onDisk.contains(m.group(1))) dangling.add(m.group(1));
        }
        Collections.sort(dangling);
        if (!dangling.isEmpty()) {
            fail(MODULE + " lists sources that no longer exist: " + dangling
                    + ". Remove them from Messaging/" + BP + ".");
        }
    }

    /**
     * Every exempt name is an engine source, so a deleted exemption cannot be inherited by the next
     * class with that name.
     */
    @Test
    public void everyNamedExemptionIsAnEngineSourceThatStillExists() throws IOException {
        final Set<String> onDisk = new HashSet<>(listFiles(ENGINE_MLS));
        final List<String> stale = new ArrayList<>();
        for (final String f : ANDROID_DEPENDENT) {
            if (!onDisk.contains(f)) stale.add(f);
        }
        if (!stale.isEmpty()) {
            fail("ANDROID_DEPENDENT names engine sources that do not exist: " + stale
                    + ". An exemption for a file that is gone excuses nothing and misleads the "
                    + "next reader; delete the entry.");
        }
    }

    /**
     * {@code Android.bp} with comments blanked in place and strings intact: {@link #SRC_ENTRY}
     * matches quoted paths, and a commented-out entry must not count as listed. Blanking in place
     * keeps {@link #moduleBlock}'s brace matching aligned.
     */
    private static String withoutComments(final String bp) {
        final char[] out = bp.toCharArray();
        final int n = out.length;
        int i = 0;
        while (i < n) {
            final char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < n) out[i++] = ' ';
                if (i < n) out[i++] = ' ';
            } else if (c == '"') {
                i++;                                    // a quoted path: its contents are the datum
                while (i < n && out[i] != '"') {
                    if (out[i] == '\\' && i + 1 < n) i++;
                    i++;
                }
                if (i < n) i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** The {@code java_library_host} block for {@link #MODULE}, brace-matched from its name. */
    private static String moduleBlock(final String bp) {
        final int at = bp.indexOf("name: \"" + MODULE + "\"");
        if (at < 0) throw new IllegalStateException(MODULE + " not found in " + BP);
        final int open = bp.lastIndexOf('{', at);
        int depth = 0;
        for (int i = open; i < bp.length(); i++) {
            final char c = bp.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return bp.substring(open, i + 1);
        }
        throw new IllegalStateException(MODULE + "'s block is unterminated in " + BP);
    }

    private static List<String> listFiles(final String relDir) {
        final File dir = locate(relDir);
        final String[] names = (dir == null) ? null : dir.list();
        final List<String> out = new ArrayList<>();
        if (names != null) {
            for (final String n : names) {
                if (n.endsWith(".java")) out.add(n);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * Resolves a path relative to this module from the working directory, as
     * {@code MlsPeerReJoinBudgetGuardTest} does, so the guard runs from the module dir or the tree
     * root.
     */
    private static File locate(final String rel) {
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            final File f = new File(c);
            if (f.exists()) return f;
        }
        return null;
    }

    private static String read(final String rel) throws IOException {
        final File f = locate(rel);
        if (f == null || !f.isFile()) {
            throw new IOException(rel + " not found from " + new File(".").getAbsolutePath());
        }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
