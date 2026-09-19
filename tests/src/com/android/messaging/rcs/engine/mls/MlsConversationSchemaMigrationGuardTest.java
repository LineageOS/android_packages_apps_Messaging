/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * A fresh install and an upgraded device end up with the same {@code conversations} table. Three
 * places must agree: {@code DatabaseHelper.CREATE_CONVERSATIONS_TABLE_SQL},
 * {@code DatabaseUpgradeHelper.upgradeToVersionN}, and {@code database_version} in
 * {@code res/values/versions.xml}. A miss surfaces only on upgraded devices, where
 * {@code doOnUpgrade} catches the failure and rebuilds the tables, losing the user's messages.
 *
 * <p>Lives in this suite because its sources are globbed, so the guard is always compiled and run;
 * the MLS columns ({@code encryption_protocol} and the re-upgrade columns) are among those it
 * protects. A source guard because both helpers need a {@code SQLiteDatabase}; it keys on constant
 * and method names, and {@link #theGuardItselfCanFail()} shows each check can fail. See
 * docs/testing.md.
 */
public final class MlsConversationSchemaMigrationGuardTest {

    private static final String HELPER =
            "src/com/android/messaging/datamodel/DatabaseHelper.java";
    private static final String UPGRADER =
            "src/com/android/messaging/datamodel/DatabaseUpgradeHelper.java";
    private static final String VERSIONS = "res/values/versions.xml";

    /**
     * The upstream v1 {@code conversations} columns, which have no {@code upgradeToVersionN}.
     * Frozen: a new column needs a migration instead. Cross-checked against the fresh-install SQL
     * so a rename fails loudly.
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
        // A missing v1 column means the frozen baseline no longer describes the table; fix it
        // deliberately.
        assertEquals(
                "v1 conversations columns missing from CREATE_CONVERSATIONS_TABLE_SQL — update "
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
        // Equality, not >=: a database_version ahead of the last step makes
        // checkAndUpdateVersionAtReleaseEnd throw "Missing upgrade handler", which doOnUpgrade
        // turns into rebuildTables.
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
        // A declared but undispatched step silently never runs; the reverse does not compile.
        assertEquals("every upgradeToVersionN must be called from doUpgradeWithExceptions",
                declared.toString(), dispatched.toString());
        // The ladder is contiguous: a gap leaves a device on the missing version with no path
        // forward.
        final int last = ((TreeSet<Integer>) declared).last();
        final List<Integer> gaps = new ArrayList<>();
        for (int v = 2; v <= last; v++) {
            if (!declared.contains(v)) {
                gaps.add(v);
            }
        }
        assertEquals(
                "gaps in the upgrade ladder — a device on one of these versions cannot upgrade",
                "[]", gaps.toString());
    }

    /**
     * Each check re-run against a synthetic body carrying the fault it catches, so a pattern that
     * stops matching cannot pass vacuously.
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
