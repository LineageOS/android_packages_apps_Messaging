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

package com.android.messaging.datamodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A device that UPGRADES must end with the schema a device that INSTALLS FRESH gets.</b>
 *
 * <p>Those are two different code paths and nothing connected them. {@code onCreate} runs
 * {@link DatabaseHelper}'s {@code CREATE_TABLE_SQLS} at the current version and never touches a
 * migration; {@code onUpgrade} runs the {@code upgradeToVersionN} chain and never touches the
 * create SQL. A column added to one and forgotten in the other produces two populations with
 * different schemas, and the symptom is a crash on a query that works perfectly for whoever added
 * it — because they installed fresh.
 *
 * <p><b>This is the only thing guarding that.</b> There were no database tests at all when the
 * migration chain was collapsed from eleven steps to two; that collapse is safe precisely because
 * this assertion is cheap, so it was written with it.
 *
 * <p>It is a SOURCE SCAN, with the limits that implies: it compares the names that appear in the
 * create SQL against the names that appear in the migrations. It cannot catch a type mismatch or a
 * different default, only absence — which is the failure that has actually happened in this class
 * of code.
 */
public final class DatabaseSchemaGuardTest {

    private static final String HELPER = "src/com/android/messaging/datamodel/DatabaseHelper.java";
    private static final String UPGRADE =
            "src/com/android/messaging/datamodel/DatabaseUpgradeHelper.java";

    /** Tables upstream AOSP already had at version 2, which no migration of ours needs to add. */
    private static final Set<String> UPSTREAM = Set.of(
            "CONVERSATIONS_TABLE", "MESSAGES_TABLE", "PARTS_TABLE", "PARTICIPANTS_TABLE",
            "CONVERSATION_PARTICIPANTS_TABLE", "DRAFT_PARTS_VIEW", "CONVERSATION_IMAGE_PARTS_VIEW",
            "CONVERSATION_LIST_VIEW", "CONVERSATION_PARTICIPANTS_VIEW", "PRIMARY_TABLE");

    private static final Pattern CREATE_TABLE =
            Pattern.compile("CREATE TABLE \" \\+ (?:DatabaseHelper\\.)?([A-Z_]+)");
    private static final Pattern ALIAS =
            Pattern.compile("final String \\w+ = DatabaseHelper\\.([A-Z_]+);");

    /**
     * EVERY table the fresh install creates and upstream did not must also be created by a
     * migration. Zero hits fails: a scan that finds no tables has not checked anything.
     */
    @Test
    public void everyTableAFreshInstallCreatesIsAlsoCreatedByAMigration() throws IOException {
        // NOT SourceScan.codeOnly: that blanks string literals, and the schema IS a string
        // literal. Comments are stripped instead, because a guard a comment can satisfy is the
        // failure this file exists to prevent, one layer up.
        final String fresh = withoutComments(SourceScan.read(HELPER));
        final String migrations = withoutComments(SourceScan.read(UPGRADE));

        final Set<String> freshTables = matches(CREATE_TABLE, fresh);
        assertTrue("no CREATE TABLE found in " + HELPER + " — this guard has gone stale, not green",
                freshTables.size() >= 5);

        final Set<String> migrated = matches(CREATE_TABLE, migrations);
        migrated.addAll(matches(ALIAS, migrations));

        final List<String> missing = new ArrayList<>();
        for (final String t : freshTables) {
            if (!UPSTREAM.contains(t) && !migrated.contains(t)) {
                missing.add(t);
            }
        }
        if (!missing.isEmpty()) {
            fail("These tables are created on a FRESH INSTALL but by no migration: " + missing
                    + ". A device upgrading from an earlier version will not have them, and every "
                    + "query against them will fail there while working for whoever added them. "
                    + "Add them to the newest upgradeToVersionN — never to an existing one, which "
                    + "is frozen for the devices that already ran it.");
        }
    }

    /**
     * The declared version must match the migration chain's last step. They are edited in
     * different files, and a mismatch means either a migration never runs or the app claims a
     * version it cannot produce.
     */
    @Test
    public void theDeclaredVersionMatchesTheLastMigration() throws IOException {
        final String versions = SourceScan.read("res/values/versions.xml");
        final Matcher v = Pattern.compile(
                "name=\"database_version\"[^>]*>(\\d+)<").matcher(versions);
        assertTrue("database_version not found in res/values/versions.xml", v.find());
        final int declared = Integer.parseInt(v.group(1));

        final String migrations = withoutComments(SourceScan.read(UPGRADE));
        int highest = 0;
        final Matcher m =
                Pattern.compile("private int upgradeToVersion(\\d+)\\(").matcher(migrations);
        while (m.find()) {
            highest = Math.max(highest, Integer.parseInt(m.group(1)));
        }
        assertTrue("no upgradeToVersionN found — this guard has gone stale, not green", highest > 0);
        assertEquals("res/values/versions.xml declares database_version=" + declared
                        + " but the highest migration is upgradeToVersion" + highest
                        + ". If the declared version is higher, the gap never runs; if it is lower, "
                        + "the last migration never runs.",
                highest, declared);
    }

    /** Comments blanked, string literals KEPT -- the inverse of {@code SourceScan.codeOnly}. */
    private static String withoutComments(final String src) {
        final StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            final char c = src.charAt(i);
            if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < src.length()
                        && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i += 2;
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < src.length() && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') {
                        out.append(src.charAt(i));
                        i++;
                    }
                    if (i < src.length()) {
                        out.append(src.charAt(i));
                        i++;
                    }
                }
                if (i < src.length()) {
                    out.append(src.charAt(i));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Set<String> matches(final Pattern p, final String src) {
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = p.matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
