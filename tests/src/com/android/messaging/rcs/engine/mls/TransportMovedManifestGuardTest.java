/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link SourceScan#TRANSPORT_MOVED} lists every method the split moved out of
 * {@code MlsProviderTransport}, and the guards that read the transport as one unsplit file take
 * the moved code from it. A method missing from the list drops out of that view without a failure,
 * so the list is checked both ways: every entry is an engine static, and every engine static that
 * takes the {@link MlsShellPort} first, the mark of a moved method, is listed or named below.
 */
public class TransportMovedManifestGuardTest {

    private static final String ENGINE_MLS = "engine/src/com/android/messaging/rcs/engine/mls";

    /**
     * Engine statics that take the port first but were written in the engine, not moved out of
     * the transport, so the unsplit view must not append them.
     */
    private static final List<String> ENGINE_BORN = Arrays.asList(
            "MlsClaimLedger#discardUnreadable",
            "MlsDriveLoop#livePass",
            "MlsDriveLoop#noteControlVerdict",
            "MlsExternalCommitResync#afterFailedRollback",
            "MlsFetchLedger#discardUnreadable",
            "MlsInboundHold#noteDecryptFailure",
            "MlsInboundHold#pruneSupersededEras",
            "MlsInboundHold#replay",
            "MlsInboundHold#replayNow",
            "MlsInboundHold#reportDropped",
            "MlsInboundHold#takeDecryptFailure",
            "MlsWelcomeAdmission#dropParkedOnJoin");

    @Test
    public void theManifestIsSortedAndUnique() throws IOException {
        final List<String> entries = entries();
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(SourceScan.TRANSPORT_MOVED + " is not sorted and unique at "
                    + entries.get(i - 1) + " / " + entries.get(i),
                    entries.get(i - 1).compareTo(entries.get(i)) < 0);
        }
    }

    @Test
    public void everyEntryIsAnEngineStatic() throws IOException {
        final List<String> entries = entries();
        final List<String> unresolved = new ArrayList<>();
        int resolved = 0;
        for (final String e : entries) {
            final int hash = e.indexOf('#');
            final Class<?> c = SourceScan.engineClass(e.substring(0, hash));
            if (c != null && declaresStatic(c, e.substring(hash + 1))) {
                resolved++;
            } else {
                unresolved.add(e);
            }
        }
        assertEquals("entries that name no static in their engine class: " + unresolved,
                entries.size(), resolved);
    }

    @Test
    public void everyShellPortStaticIsListedOrEngineBorn() throws IOException {
        final Set<String> listed = new HashSet<>(entries());
        final Set<String> found = shellPortStatics();
        assertTrue("only " + found.size() + " engine statics take the port first; the scan has "
                + "lost its subject", found.size() > 100);
        final Set<String> missing = new TreeSet<>();
        for (final String s : found) {
            if (!listed.contains(s) && !ENGINE_BORN.contains(s)) missing.add(s);
        }
        if (!missing.isEmpty()) {
            fail("engine statics that take MlsShellPort first are neither in "
                    + SourceScan.TRANSPORT_MOVED + " nor in ENGINE_BORN: " + missing
                    + ". A method moved out of the transport goes in the manifest; one written in "
                    + "the engine goes in ENGINE_BORN.");
        }
        for (final String b : ENGINE_BORN) {
            assertTrue("ENGINE_BORN entry " + b + " is not an engine static taking the port "
                    + "first", found.contains(b));
            assertTrue("ENGINE_BORN entry " + b + " is also in the manifest", !listed.contains(b));
        }
    }

    @Test
    public void everyRemainingDelegateIsListed() throws IOException {
        final Set<String> listed = new HashSet<>(entries());
        final Matcher d = Pattern.compile(SourceScan.DELEGATE)
                .matcher(SourceScan.read(SourceScan.TRANSPORT));
        final Set<String> unlisted = new TreeSet<>();
        while (d.find()) {
            if (!listed.contains(d.group(1) + "#" + d.group(2))) {
                unlisted.add(d.group(1) + "#" + d.group(2));
            }
        }
        assertTrue("delegates in the transport that the manifest does not list: " + unlisted,
                unlisted.isEmpty());
    }

    private static List<String> entries() throws IOException {
        final List<String> out = new ArrayList<>();
        for (final String[] tm : SourceScan.transportMoved()) out.add(tm[0] + "#" + tm[1]);
        return out;
    }

    private static boolean declaresStatic(final Class<?> c, final String name) {
        for (final Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())
                    && !m.isSynthetic()) {
                return true;
            }
        }
        return false;
    }

    /** {@code Class#method} for every engine static whose first parameter is the port. */
    private static Set<String> shellPortStatics() throws IOException {
        final Set<String> out = new TreeSet<>();
        int classes = 0;
        for (final File f : SourceScan.javaSourcesUnder(ENGINE_MLS)) {
            final String name = f.getName().substring(0, f.getName().length() - ".java".length());
            final Class<?> c = SourceScan.engineClass(name);
            if (c == null) continue;
            classes++;
            for (final Method m : c.getDeclaredMethods()) {
                final Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) && !m.isSynthetic() && p.length > 0
                        && p[0] == MlsShellPort.class) {
                    out.add(name + "#" + m.getName());
                }
            }
        }
        assertTrue("only " + classes + " engine classes loaded", classes > 100);
        return out;
    }
}
