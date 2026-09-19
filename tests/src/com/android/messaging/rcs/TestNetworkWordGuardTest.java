/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * No source, test, doc or file name in this module uses the name of our retired open5gs test
 * network, the three letters L, A, B, as a word or as a camelCase or snake_case segment. That
 * name once spread to the RCC.16 profile, a gate, the production SIP plane and the local PKI,
 * none of which is a test network.
 *
 * <p>A hit is those letters in any case, starting a word or a camel segment (after a non-letter,
 * or an upper-case L after a lower-case letter) and ending one (before a non-lower-case letter,
 * or before a plural s). So {@code label}, {@code available} and {@code parcelable} are not hits,
 * and neither is the Latvian {@code Labi}.
 *
 * <p>One value is allowlisted, because it is data and not a name: the participant-key preferences
 * file name, which is on-device state. The MLS engine adds the participant-key derivation label.
 * Nothing else may be added here; rename the thing instead.
 */
public class TestNetworkWordGuardTest {

    /** The letters, assembled so this file does not trip its own scan. */
    private static final String W = new String(new char[] {'l', 'a', 'b'});

    /** Exactly the frozen values. Each must still exist, or its entry is stale. */
    private static final List<String> ALLOWED = Arrays.asList(
            "mls_" + W + "_participant_key");

    /** Directories never scanned: VCS metadata and build output. */
    private static final List<String> SKIP_DIRS = Arrays.asList(".git", "target", "out");

    /** The module directory, from the module dir, the tree root or one below. */
    private static File moduleRoot() {
        for (final String c : new String[] {".", "packages/apps/Messaging", ".."}) {
            final File f = new File(c);
            if (new File(f, "Android.bp").isFile()
                    && new File(f, "src/com/android/messaging").isDirectory()
                    && new File(f, "tests/src/com/android/messaging/rcs").isDirectory()) {
                return f;
            }
        }
        throw new AssertionError("module root not found from "
                + new File(".").getAbsolutePath());
    }

    /** Every file under the root, sorted, with the skipped directories left out. */
    private static List<File> files(final File root) {
        final List<File> out = new ArrayList<>();
        final Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final File[] kids = stack.pop().listFiles();
            if (kids == null) continue;
            for (final File k : kids) {
                if (Files.isSymbolicLink(k.toPath())) continue;
                if (k.isDirectory()) {
                    if (!SKIP_DIRS.contains(k.getName())) stack.push(k);
                } else if (k.isFile()) {
                    out.add(k);
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** Whether the three letters at {@code i} form a word or a segment. */
    static boolean isHit(final String s, final int i) {
        final char c0 = s.charAt(i);
        final char prev = i > 0 ? s.charAt(i - 1) : ' ';
        final char nx = i + 3 < s.length() ? s.charAt(i + 3) : ' ';
        final char nx2 = i + 4 < s.length() ? s.charAt(i + 4) : ' ';
        final boolean start = !Character.isLetter(prev)
                || (c0 == 'L' && Character.isLowerCase(prev));
        final boolean allUpper = s.substring(i, i + 3).equals(W.toUpperCase());
        final boolean end = allUpper
                ? !Character.isUpperCase(nx) || (nx == 'S' && !Character.isUpperCase(nx2))
                : !Character.isLowerCase(nx) || (nx == 's' && !Character.isLowerCase(nx2));
        return start && end;
    }

    /** Offsets of every hit in {@code s}. */
    static List<Integer> hits(final String s) {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i + 3 <= s.length(); i++) {
            if (s.regionMatches(true, i, W, 0, 3) && isHit(s, i)) out.add(i);
        }
        return out;
    }

    private static String withoutAllowed(String line) {
        for (final String a : ALLOWED) line = line.replace(a, "");
        return line;
    }

    /** Key and certificate fixtures and images, by name; anything else by a NUL byte. */
    private static final List<String> BINARY_SUFFIXES = Arrays.asList(
            ".der", ".bin", ".p8", ".png", ".jpg", ".webp", ".gif", ".jar", ".so", ".a");

    private static boolean isBinary(final File f, final byte[] b) {
        for (final String x : BINARY_SUFFIXES) if (f.getName().endsWith(x)) return true;
        for (int i = 0; i < Math.min(b.length, 8000); i++) if (b[i] == 0) return true;
        return false;
    }

    @Test
    public void theDetectorSeesTheWordAndNotTheWordsThatContainIt() {
        final String u = W.toUpperCase();
        final String t = Character.toUpperCase(W.charAt(0)) + W.substring(1);
        for (final String hit : new String[] {W, u, t, W + "2_root.der", u + "_IMS_PDN",
                "persist.rcs.mls_" + W + "_peers", W + "Peers(", "G1_" + u + "_ALLOWLIST",
                t + "AcsRequest", "the " + W + "-minted leaf", u + "S", W + "s"}) {
            assertFalse("not a hit: " + hit, hits(hit).isEmpty());
        }
        for (final String miss : new String[] {"label", "LABEL", "available", "parcelable",
                "Nullable", t + "i", "skil" + W + "oð", "sag" + W + "āti",
                "syl" + W + "le", "cal" + W + "le"}) {
            assertTrue("a hit: " + miss, hits(miss).isEmpty());
        }
    }

    @Test
    public void everyAllowedValueIsStillThere() throws IOException {
        final File root = moduleRoot();
        final int[] seen = new int[ALLOWED.size()];
        for (final File f : files(root)) {
            final byte[] b = Files.readAllBytes(f.toPath());
            if (isBinary(f, b)) continue;
            final String s = new String(b, StandardCharsets.UTF_8);
            for (int k = 0; k < ALLOWED.size(); k++) if (s.contains(ALLOWED.get(k))) seen[k]++;
        }
        for (int k = 0; k < ALLOWED.size(); k++) {
            assertTrue("the allowlisted value '" + ALLOWED.get(k) + "' is gone; remove its entry",
                    seen[k] > 0);
        }
    }

    @Test
    public void noSourceTestDocOrFileNameUsesTheWord() throws IOException {
        final File root = moduleRoot();
        final String rootPath = root.getCanonicalPath();
        final List<File> all = files(root);
        assertTrue("scanned only " + all.size() + " files — this guard lost its subject",
                all.size() > 500);
        final List<String> found = new ArrayList<>();
        int scanned = 0;
        for (final File f : all) {
            final String rel = f.getCanonicalPath().substring(rootPath.length() + 1);
            if (!hits(f.getName()).isEmpty()) found.add(rel + ": file name");
            final byte[] b = Files.readAllBytes(f.toPath());
            if (isBinary(f, b)) continue;
            scanned++;
            final String[] lines = new String(b, StandardCharsets.UTF_8).split("\n", -1);
            for (int n = 0; n < lines.length; n++) {
                if (!hits(withoutAllowed(lines[n])).isEmpty()) {
                    found.add(rel + ":" + (n + 1) + ": " + lines[n].trim());
                }
            }
        }
        assertTrue("read only " + scanned + " text files", scanned > 500);
        if (!found.isEmpty()) {
            final int shown = Math.min(found.size(), 40);
            fail(found.size() + " use(s) of the retired test-network name. Rename the thing; "
                    + "only the frozen value in ALLOWED may keep it:\n  "
                    + String.join("\n  ", found.subList(0, shown))
                    + (shown < found.size() ? "\n  ... and " + (found.size() - shown) + " more"
                            : ""));
        }
        assertEquals(0, found.size());
    }
}
