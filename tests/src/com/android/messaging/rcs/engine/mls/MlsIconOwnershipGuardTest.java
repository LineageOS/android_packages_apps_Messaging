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
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * {@code ConversationColumns.ICON} has exactly ONE writer per producer, and the derived one asks
 * before it writes.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>{@code BugleDatabaseOperations.fillParticipantData} wrote the participant-derived avatar into
 * the column unconditionally, on both refresh paths. A member add or remove therefore reverted a
 * decrypted RCC.16 §9.7.1.4 group icon to the derived avatar — silently, and to something that
 * looks correct, so the user had no way to tell an icon had been dropped. {@code NAME} had the
 * ownership rule this needed; {@code ICON} did not.
 *
 * <h2>Why a SOURCE SCAN and not a behavioural test</h2>
 *
 * <p>Both writers are Android-bound — a {@code ContentValues} put inside a database transaction,
 * and a {@code file://} URI built from a {@code Context} — so neither runs on this host suite. What
 * is checkable here is the WIRING, which is what the defect was: not a wrong value, but a second
 * writer nobody had asked to be exclusive. Same choice, and the same reason, as
 * {@code MlsSelfDepartureGuardTest} pinning {@code applyLocalMirror}'s sites.
 *
 * <h2>The ownership rule, which is the one being enforced</h2>
 *
 * <p>When you establish who owns a column, make sure exactly ONE writer clears it. Here that means:
 * the derived-avatar path may write only over its own product, and the MLS path is the only other
 * writer. A third would be a producer with no ownership answer, which is how the leave/rejoin case
 * went wrong.
 */
public class MlsIconOwnershipGuardTest {

    private static final String DB_OPS = "src/com/android/messaging/datamodel/"
            + "BugleDatabaseOperations.java";
    private static final String APPLIER = "src/com/android/messaging/rcs/"
            + "GroupIconApplier.java";
    private static final String AVATAR_UTIL = "src/com/android/messaging/util/"
            + "AvatarUriUtil.java";

    /**
     * RED WHEN a third writer appears, or when either existing one moves.
     *
     * <p>The count is deliberately per-FILE rather than a total: a total that stays at two while one
     * file loses a write and another gains one is a total that hid the thing this exists to catch.
     */
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

    /**
     * RED WHEN the derived write stops being guarded — the exact regression, restored.
     *
     * <p>Asserts ORDER, not co-presence: the ownership question must be asked BEFORE the put, in
     * the same method. A test that merely found both tokens somewhere in the file would go green on
     * a guard that runs after the write, or on one attached to a different column entirely — which
     * is the membership form this suite has already been bitten by twice.
     */
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
     * RED WHEN the predicate stops being read off the COLUMN.
     *
     * <p>The rule is answerable from the stored value — a derived avatar is a
     * {@code messaging://avatar/…} URI — and that is what keeps ownership a datamodel decision with
     * no dependency on the MLS layer. If {@code BugleDatabaseOperations} ever imports the applier to
     * ask it instead, the layering has inverted and the column's owner is no longer discoverable
     * from the column.
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
     * RED WHEN the derived-avatar predicate stops matching what the derived path actually builds.
     *
     * <p>{@code isDerivedAvatarUri} and {@code createAvatarUri} must agree about scheme and
     * authority, and they agree by both naming the same two constants. A predicate that hardcoded
     * the strings would pass this file's other tests and silently stop recognising its own product
     * the day either constant changed — at which point the derived path would treat its OWN avatars
     * as owned by someone else and stop refreshing them.
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
     * The MLS icon must live somewhere PERSISTENT, and this is the falsifier for the claim that it
     * does.
     *
     * <p>The first version of the applier wrote the displayed copy into
     * {@code MediaScratchFileProvider}, which is backed by {@code getCacheDir()} — the system may
     * evict it, and a peer does not resend an icon on demand, so the column would point at nothing
     * with no way back. Going back to a cache-backed provider is the regression; the file is the
     * displayed icon now, so there is no second copy to fall back on.
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

    /** The scan itself is not vacuous: these files exist and are non-trivial. */
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
