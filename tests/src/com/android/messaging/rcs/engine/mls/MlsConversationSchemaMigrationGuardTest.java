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

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A fresh install and an upgraded device must end up with the same {@code conversations}
 * table</b> — added with the change that put the twelfth column on it.
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Three separate places have to agree for a new column to exist on every device:
 * {@code DatabaseHelper.CREATE_CONVERSATIONS_TABLE_SQL} (fresh install),
 * {@code DatabaseUpgradeHelper.upgradeToVersionN} (existing install) and the
 * {@code database_version} string in {@code res/values/versions.xml} (what makes the upgrade run at
 * all). Miss the second and an upgraded device has no such column; miss the third and the migration
 * never fires, so BOTH of the first two can be perfect and the column still will not be there.
 *
 * <p><b>It fails far from the mistake and only on upgraded devices.</b> The author's own handset is
 * usually a fresh install or already past the version, so the omission is invisible where it is
 * made; it surfaces as "no such column" on every conversation query for someone else, and
 * {@code doOnUpgrade} catches the exception and calls {@code rebuildTables}, which destroys the
 * user's messages rather than reporting the bug. {@code DatabaseUpgradeHelper} warns about the
 * drift twice in prose. Prose did not stop it being possible; this does.
 *
 * <h2>Why the MLS suite guards a datamodel invariant</h2>
 *
 * <p>Because this is the module's only host-test target whose {@code srcs} are GLOBBED
 * ({@code messaging2-mls-engine-host-tests}, {@code tests/src/com/android/messaging/rcs/engine/**}).
 * Every other target ENUMERATES its sources in {@code Android.bp}, and a guard that is not listed
 * there is not compiled, not run, and indistinguishable from a guard that passes — the failure mode
 * {@code Android.bp} warns about six lines from where such fixes go. A guard in the wrong package
 * that runs beats a well-filed one that does not.
 *
 * <p>It is also not unrelated to MLS: four of the columns it protects are the MLS ones
 * ({@code encryption_protocol} and the three §9.7l re-upgrade columns), and the padlock the UI draws
 * is a read of {@code encryption_protocol}. A missing migration on those turns MLS off, loudly, on
 * every upgraded device.
 *
 * <h2>Why a SOURCE guard</h2>
 *
 * <p>{@code DatabaseHelper} and {@code DatabaseUpgradeHelper} need a {@code SQLiteDatabase} and an
 * Android {@code Context}, so the host suite cannot execute them — the standing justification in
 * {@link SourceScan}'s class doc. Both of its rules are met:
 *
 * <ul>
 *   <li>the assertions key on CONSTANT NAMES ({@code ConversationColumns.X}) and on the
 *       {@code upgradeToVersionN} method name, never on a comment or a log label;</li>
 *   <li><b>zero hits FAIL.</b> Every extraction is asserted non-empty first, and
 *       {@link #theGuardItselfCanFail()} shows each check reporting a fault on a body that has one —
 *       so a pattern that goes stale cannot report success for the fraction it still matches.</li>
 * </ul>
 */
public final class MlsConversationSchemaMigrationGuardTest {

    private static final String HELPER =
            "src/com/android/messaging/datamodel/DatabaseHelper.java";
    private static final String UPGRADER =
            "src/com/android/messaging/datamodel/DatabaseUpgradeHelper.java";
    private static final String VERSIONS = "res/values/versions.xml";

    /**
     * The AOSP v1 {@code conversations} columns — the ones that predate this project and therefore
     * legitimately have no {@code upgradeToVersionN}.
     *
     * <p><b>This list is FROZEN and can only ever shrink.</b> Version 1 shipped years ago; nothing
     * can be added to it retroactively. So a new column that "needs" to go in here is a new column
     * that needs a migration instead, and that is the whole point of writing it out rather than
     * deriving it. It is cross-checked against the live fresh-install SQL below, so a rename in
     * {@code DatabaseHelper} breaks this guard loudly instead of quietly shrinking its coverage.
     */
    private static final String[] V1_COLUMNS = {
        "_ID", "SMS_THREAD_ID", "NAME", "LATEST_MESSAGE_ID", "SNIPPET_TEXT", "SUBJECT_TEXT",
        "PREVIEW_URI", "PREVIEW_CONTENT_TYPE", "SHOW_DRAFT", "DRAFT_SNIPPET_TEXT",
        "DRAFT_SUBJECT_TEXT", "DRAFT_PREVIEW_URI", "DRAFT_PREVIEW_CONTENT_TYPE", "ARCHIVE_STATUS",
        "SORT_TIMESTAMP", "LAST_READ_TIMESTAMP", "ICON", "PARTICIPANT_CONTACT_ID",
        "PARTICIPANT_LOOKUP_KEY", "OTHER_PARTICIPANT_NORMALIZED_DESTINATION", "CURRENT_SELF_ID",
        "PARTICIPANT_COUNT", "INCLUDE_EMAIL_ADDRESS", "SMS_SERVICE_CENTER",
    };

    /** {@code ConversationColumns.X} references inside the fresh-install CREATE statement. */
    private static Set<String> freshInstallColumns(final String helperSrc) {
        final int start = helperSrc.indexOf("CREATE_CONVERSATIONS_TABLE_SQL");
        assertTrue("CREATE_CONVERSATIONS_TABLE_SQL is gone from " + HELPER + " — this guard's "
                + "anchor moved and it would otherwise pass vacuously", start >= 0);
        final int end = helperSrc.indexOf("\");", start);
        assertTrue("could not find the end of CREATE_CONVERSATIONS_TABLE_SQL", end > start);
        return referencedColumns(helperSrc.substring(start, end));
    }

    private static Set<String> referencedColumns(final String region) {
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = Pattern.compile("ConversationColumns\\.([A-Z0-9_]+)").matcher(region);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** The N of every {@code private int upgradeToVersionN(} declaration. */
    private static Set<Integer> declaredUpgradeSteps(final String upgraderSrc) {
        final Set<Integer> out = new TreeSet<>();
        final Matcher m =
                Pattern.compile("int\\s+upgradeToVersion(\\d+)\\s*\\(").matcher(upgraderSrc);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    /** The N of every {@code currentVersion = upgradeToVersionN(db);} call in the dispatcher. */
    private static Set<Integer> dispatchedUpgradeSteps(final String upgraderSrc) {
        final Set<Integer> out = new TreeSet<>();
        final Matcher m = Pattern.compile(
                "currentVersion\\s*=\\s*upgradeToVersion(\\d+)\\s*\\(").matcher(upgraderSrc);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static int declaredDatabaseVersion(final String versionsXml) {
        final Matcher m = Pattern.compile(
                "name=\"database_version\"[^>]*>(\\d+)<").matcher(versionsXml);
        assertTrue("no database_version string in " + VERSIONS, m.find());
        return Integer.parseInt(m.group(1));
    }

    @Test
    public void freshInstallSchemaStillContainsEveryV1Column() throws IOException {
        final Set<String> fresh = freshInstallColumns(SourceScan.read(HELPER));
        assertTrue("the fresh-install CREATE matched no columns at all — the extraction is stale",
                fresh.size() > V1_COLUMNS.length);
        final List<String> missing = new ArrayList<>();
        for (final String c : V1_COLUMNS) {
            if (!fresh.contains(c)) {
                missing.add(c);
            }
        }
        // A v1 column that has vanished means this guard's frozen baseline no longer describes the
        // table, so its coverage of the columns that DO need migrations has quietly shrunk. Fix the
        // baseline deliberately; do not let it rot.
        assertEquals("v1 conversations columns missing from CREATE_CONVERSATIONS_TABLE_SQL — update "
                + "V1_COLUMNS in this guard on purpose, having checked the rename really is one",
                "[]", missing.toString());
    }

    @Test
    public void everyConversationColumnAddedSinceV1HasAnUpgradeStep() throws IOException {
        final Set<String> fresh = freshInstallColumns(SourceScan.read(HELPER));
        final String upgrader = SourceScan.read(UPGRADER);
        final Set<String> migrated = referencedColumns(upgrader);
        assertTrue("no ConversationColumns references in " + UPGRADER + " — extraction is stale",
                !migrated.isEmpty());

        final Set<String> v1 = new LinkedHashSet<>();
        for (final String c : V1_COLUMNS) {
            v1.add(c);
        }
        final List<String> unmigrated = new ArrayList<>();
        for (final String c : fresh) {
            if (!v1.contains(c) && !migrated.contains(c)) {
                unmigrated.add(c);
            }
        }
        assertEquals("conversations columns that a FRESH install gets and an UPGRADED device never "
                + "does — each needs an ALTER TABLE in a new upgradeToVersionN (plus the "
                + "database_version bump). Without it, every query on the column throws on an "
                + "upgraded device and doOnUpgrade's catch rebuilds the tables, destroying the "
                + "user's messages",
                "[]", unmigrated.toString());
    }

    @Test
    public void declaredDatabaseVersionMatchesTheLastUpgradeStep() throws IOException {
        final Set<Integer> declared = declaredUpgradeSteps(SourceScan.read(UPGRADER));
        assertTrue("no upgradeToVersionN methods found — extraction is stale", !declared.isEmpty());
        final int last = ((TreeSet<Integer>) declared).last();
        // NOT >=. A database_version ahead of the last upgrade step means an upgrading device runs
        // every migration it has and still ends at a version below the target, which is exactly the
        // "Missing upgrade handler" exception checkAndUpdateVersionAtReleaseEnd throws — and
        // doOnUpgrade's catch turns that into rebuildTables.
        assertEquals("res/values/versions.xml database_version must equal the highest "
                + "upgradeToVersionN in " + UPGRADER,
                last, declaredDatabaseVersion(SourceScan.read(VERSIONS)));
    }

    @Test
    public void everyUpgradeStepIsDispatchedExactlyOnce() throws IOException {
        final String upgrader = SourceScan.read(UPGRADER);
        final Set<Integer> declared = declaredUpgradeSteps(upgrader);
        final Set<Integer> dispatched = dispatchedUpgradeSteps(upgrader);
        assertTrue("no upgradeToVersionN methods found — extraction is stale", !declared.isEmpty());
        // A declared-but-undispatched step is a migration that silently never runs; a
        // dispatched-but-undeclared one does not compile, so this asserts the direction that can
        // actually ship.
        assertEquals("every upgradeToVersionN must be called from doUpgradeWithExceptions",
                declared.toString(), dispatched.toString());
        // And the ladder must be contiguous: a gap means a device sitting on the missing version
        // has no path forward.
        final int last = ((TreeSet<Integer>) declared).last();
        final List<Integer> gaps = new ArrayList<>();
        for (int v = 2; v <= last; v++) {
            if (!declared.contains(v)) {
                gaps.add(v);
            }
        }
        assertEquals("gaps in the upgrade ladder — a device on one of these versions cannot upgrade",
                "[]", gaps.toString());
    }

    /**
     * THE GUARD ITSELF CAN FAIL. Each check is re-run against a synthetic body that carries the
     * fault it is meant to catch, so a pattern that stops matching cannot pass vacuously.
     */
    @Test
    public void theGuardItselfCanFail() {
        // A column in the fresh-install SQL with no ALTER TABLE anywhere.
        final String freshWithNewColumn =
                "CREATE_CONVERSATIONS_TABLE_SQL = \"CREATE TABLE x(\"\n"
                + "  + ConversationColumns._ID + \" INT, \"\n"
                + "  + ConversationColumns.RCS_SELF_LEFT + \" INT\"\n"
                + "  + \");\";\n";
        final Set<String> fresh = referencedColumns(freshWithNewColumn);
        assertTrue("the column extractor stopped matching ConversationColumns.X",
                fresh.contains("RCS_SELF_LEFT") && fresh.contains("_ID"));
        assertTrue("an unmigrated column must be detectable",
                !referencedColumns("no columns here").contains("RCS_SELF_LEFT"));

        // A declared step that nothing dispatches.
        final String undispatched =
                "    private int upgradeToVersion7(final SQLiteDatabase db) { return 7; }\n"
                + "    private int upgradeToVersion8(final SQLiteDatabase db) { return 8; }\n"
                + "        currentVersion = upgradeToVersion7(db);\n";
        assertEquals("[7, 8]", declaredUpgradeSteps(undispatched).toString());
        assertEquals("[7]", dispatchedUpgradeSteps(undispatched).toString());

        // A version string the parser must read as a number, not as whatever it is adjacent to.
        assertEquals(12, declaredDatabaseVersion(
                "<string name=\"database_version\" translatable=\"false\">12</string>"));
    }
}
