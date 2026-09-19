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
import java.util.List;

/**
 * {@code ConversationColumns.ICON} has one writer per producer, and the participant-derived writer
 * writes only over its own product, so a roster change cannot replace a decrypted RCC.16 §9.7.1.4
 * group icon. Both writers are Android-bound, so the wiring is checked from source. See
 * docs/rcs/groups.md.
 */
public class MlsIconOwnershipGuardTest {

    private static final String DB_OPS = "src/com/android/messaging/datamodel/"
            + "BugleDatabaseOperations.java";
    private static final String APPLIER = "src/com/android/messaging/rcs/"
            + "GroupIconApplier.java";
    private static final String AVATAR_UTIL = "src/com/android/messaging/util/"
            + "AvatarUriUtil.java";

    /** Counted per file, so one file losing a write while another gains one cannot hide. */
    @Test
    public void theColumnHasExactlyTwoProducersAndBothAreNamed() throws IOException {
        final String dbOps = SourceScan.codeOnly(SourceScan.read(DB_OPS));
        final String applier = SourceScan.codeOnly(SourceScan.read(APPLIER));

        assertEquals("the DERIVED avatar must be written from exactly one place in "
                        + "BugleDatabaseOperations — a second would need its own ownership answer",
                1, SourceScan.count(dbOps, "values.put(ConversationColumns.ICON"));
        assertEquals("the MLS icon must be written from exactly one place in GroupIconApplier",
                1, SourceScan.count(applier, "values.put(ConversationColumns.ICON"));
    }

    /** The ownership question is asked before the put, in the same method. */
    @Test
    public void theDerivedWriteIsBehindTheOwnershipQuestion() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(DB_OPS));
        final String body = SourceScan.bodyOf(src, "fillParticipantData");
        assertTrue("fillParticipantData's body did not resolve — this guard has nothing to read",
                body != null && body.length() > 0);

        final int asks = body.indexOf("ownsItsIcon(");
        final int writes = body.indexOf("values.put(ConversationColumns.ICON");
        if (asks < 0) {
            fail("fillParticipantData no longer asks ownsItsIcon() — the derived avatar is "
                    + "overwriting whatever is in ICON again. Do not "
                    + "restore this by deleting the assertion.");
        }
        if (writes < 0) {
            fail("fillParticipantData no longer writes ICON at all. If that is deliberate the "
                    + "other assertions here are measuring nothing — delete this guard rather "
                    + "than leaving it green on an absent write.");
        }
        assertTrue("the ICON write at " + writes + " comes BEFORE the ownership question at "
                        + asks + " — the guard cannot protect a value already overwritten",
                asks < writes);
    }

    /**
     * Ownership is decided from the stored value (a derived avatar is a {@code
     * messaging://avatar/…} URI), with no dependency on the MLS layer.
     */
    @Test
    public void ownershipIsDecidedFromTheValueNotFromTheMlsLayer() throws IOException {
        final String src = SourceScan.read(DB_OPS);
        assertTrue("ownsItsIcon must decide with AvatarUriUtil.isDerivedAvatarUri, i.e. from the "
                        + "stored value",
                SourceScan.codeOnly(src).contains("AvatarUriUtil.isDerivedAvatarUri("));
        assertEquals("BugleDatabaseOperations must NOT reach into the MLS layer to decide who owns "
                        + "a column — the value answers it",
                0, SourceScan.count(src, "import com.android.messaging.rcs.e2ee."));
    }

    /**
     * {@code isDerivedAvatarUri} and {@code createAvatarUri} share their scheme and authority
     * constants, or the derived path could stop recognising its own avatars.
     */
    @Test
    public void thePredicateAndTheBuilderShareTheirConstants() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(AVATAR_UTIL));
        final String body = SourceScan.bodyOf(src, "isDerivedAvatarUri");
        assertTrue("isDerivedAvatarUri's body did not resolve", body != null && body.length() > 0);
        assertTrue("isDerivedAvatarUri must compare against the SCHEME constant, not a literal",
                body.contains("SCHEME"));
        assertTrue("isDerivedAvatarUri must compare against the AUTHORITY constant, not a literal",
                body.contains("AUTHORITY"));
        assertTrue("isDerivedAvatarUri must not hardcode the scheme string",
                !body.contains("\"messaging\""));
    }

    /**
     * The icon lives under {@code getFilesDir()}: a cache-backed file could be evicted, and a peer
     * does not resend an icon.
     */
    @Test
    public void theIconIsNotWrittenIntoTheCache() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(APPLIER));
        assertEquals("GroupIconApplier must not use MediaScratchFileProvider — it is cache-backed, "
                        + "and this file IS the displayed icon",
                0, SourceScan.count(src, "MediaScratchFileProvider"));
        assertEquals("GroupIconApplier must not write into the cache directory",
                0, SourceScan.count(src, "getCacheDir()"));
        assertTrue("GroupIconApplier must write under getFilesDir()",
                src.contains("getFilesDir()"));
    }

    /** The scanned files exist and are non-trivial. */
    @Test
    public void theSourcesActuallyLoaded() throws IOException {
        final List<String> rel = java.util.Arrays.asList(DB_OPS, APPLIER, AVATAR_UTIL);
        for (final String r : rel) {
            final String src = SourceScan.read(r);
            assertTrue(r + " did not load, so every assertion over it is satisfied by absence",
                    src != null && src.length() > 2000);
        }
    }
}
