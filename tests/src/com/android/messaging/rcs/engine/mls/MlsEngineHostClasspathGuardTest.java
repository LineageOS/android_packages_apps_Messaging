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
 * <b>A new engine class is not host-tested until someone adds it to the classpath</b> — the
 * decoupling plan's Stage 0 "note, not a stage", enforced in Stage 7.
 *
 * <h2>Why a test and not a convention</h2>
 *
 * <p>{@code messaging-mls-policy-host} names its sources ONE BY ONE in {@code Android.bp}. There
 * is no glob, deliberately — the six exclusions below import {@code android} and would not compile
 * on a host classpath — so the list is maintained by hand, and a hand-maintained list of ninety-odd
 * entries fails silently in exactly one direction: a class that is missing from it still BUILDS,
 * still SHIPS, and simply has no host coverage. Nothing goes red.
 *
 * <p>That has happened. We have on record the {@code Android.bp} hunk adding
 * {@code MlsAdvancerElection} being swept into an unrelated commit — the policy landed, its tests
 * landed, and for a window the two were not connected. And it happened again while this guard was
 * being written: Stage 1 added {@code MlsFetchLedger} and
 * {@code MlsFetchLedgerRecord} to the engine and this test is what says they are not yet on the
 * classpath.
 *
 * <p><b>If this test fails you have added an engine class that nothing host-tests.</b> Add it to
 * {@code messaging-mls-policy-host}'s {@code srcs} in {@code Android.bp}. If it
 * genuinely cannot go there — it imports {@code android} — add it to {@link #ANDROID_DEPENDENT}
 * below <i>with the import that forces the exclusion named in the comment</i>, so the exemption is
 * a claim a reader can check rather than a name on a list.
 *
 * <p>There is a companion generator in the out-of-tree RCS provider's own repository which
 * reports the same thing from the command line and can be pointed at any revision. It is not
 * needed to run this guard: the check below reads {@code Android.bp} and the engine directory in
 * THIS repository and is complete on its own.
 */
public final class MlsEngineHostClasspathGuardTest {

    private static final String BP = "Android.bp";
    private static final String ENGINE_MLS = "engine/src/com/android/messaging/rcs/engine/mls";
    private static final String MODULE = "messaging-mls-policy-host";

    /**
     * The engine classes that CANNOT be on a host classpath, each with the reason.
     *
     * <p>All six are the JNI/session wrappers: they import {@code android} (for {@code Context},
     * {@code SystemProperties} or {@code android.util.Log}) or load the native library, so a host
     * JVM has nothing to link them against. This is the complete exemption list — the plan's §3.6
     * records that an earlier count of "28 of 99 on the classpath" was a bad regex and that the
     * real gap is only ever the hand-list going stale.
     */
    private static final String[] ANDROID_DEPENDENT = {
        "MlsEngine.java",            // the engine interface, over android-typed artifacts
        "MlsSession.java",           // ditto
        "MlsLog.java",               // android.util.Log
        "OpenMlsEngine.java",        // System.loadLibrary + android Context
        "OpenMlsNative.java",        // the JNI binding itself
        "OpenMlsSession.java",       // holds a native handle
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

        // A locator that silently found nothing would make this guard pass forever, which is the
        // failure mode it exists to prevent one level up. Refuse to be that.
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
     * The other direction: a source removed or renamed leaves a dangling entry that breaks the
     * build rather than failing quietly, so this is the cheaper failure — but it is still worth
     * catching here, where the message can say which name to delete.
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
     * The exemption list must stay true: every name on it must actually be an engine source.
     *
     * <p>Without this, deleting or renaming an exempt class leaves a name that quietly excuses
     * nothing — and the next class with that name inherits an exemption nobody granted it.
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

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * {@code Android.bp} with its comments blanked and its strings intact.
     *
     * <p>{@link #SRC_ENTRY} matches a quoted path, so string contents must survive; comments must
     * not. The srcs list of this module is heavily commented — most entries carry a line saying why
     * the class is host-listed — and <b>a commented-out entry counted as listed</b>. Commenting one
     * out to unbreak a build is the ordinary thing to do under pressure, and it would have taken
     * that class off the host classpath while the guard whose whole job is to notice exactly that
     * reported green.
     *
     * <p>Blanked in place rather than deleted so the brace matching in {@link #moduleBlock} sees the
     * same structure; a brace inside a comment would otherwise not have been a problem here and is
     * one line of insurance if the file grows one.
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
                i++;                                    // a quoted path: its contents ARE the datum
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
     * Resolve a path relative to this module, from the working directory rather than a build
     * variable — the same locator {@code MlsPeerReJoinBudgetGuardTest} uses, so the guards all run
     * the same way from the module dir and from the tree root.
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
