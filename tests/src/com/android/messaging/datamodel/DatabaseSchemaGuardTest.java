/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.datamodel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An upgraded database must end with the schema a fresh install gets: {@code onCreate} and the
 * {@code upgradeToVersionN} chain are separate paths. A source scan comparing table names and, per
 * table, column sets, so it catches absence but not a type or default mismatch.
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

    /** Upstream's version-2 columns: where the migration chain starts. */
    private static final Map<String, Set<String>> UPSTREAM_V2_COLUMNS = Map.of(
            "CONVERSATIONS_TABLE", columns("ConversationColumns", "_ID", "SMS_THREAD_ID", "NAME",
                    "LATEST_MESSAGE_ID", "SNIPPET_TEXT", "SUBJECT_TEXT", "PREVIEW_URI",
                    "PREVIEW_CONTENT_TYPE", "SHOW_DRAFT", "DRAFT_SNIPPET_TEXT",
                    "DRAFT_SUBJECT_TEXT", "DRAFT_PREVIEW_URI", "DRAFT_PREVIEW_CONTENT_TYPE",
                    "ARCHIVE_STATUS", "SORT_TIMESTAMP", "LAST_READ_TIMESTAMP", "ICON",
                    "PARTICIPANT_CONTACT_ID", "PARTICIPANT_LOOKUP_KEY",
                    "OTHER_PARTICIPANT_NORMALIZED_DESTINATION", "CURRENT_SELF_ID",
                    "PARTICIPANT_COUNT", "INCLUDE_EMAIL_ADDRESS", "SMS_SERVICE_CENTER",
                    "IS_ENTERPRISE"),
            "MESSAGES_TABLE", columns("MessageColumns", "_ID", "CONVERSATION_ID",
                    "SENDER_PARTICIPANT_ID", "SENT_TIMESTAMP", "RECEIVED_TIMESTAMP", "PROTOCOL",
                    "STATUS", "SEEN", "READ", "SMS_MESSAGE_URI", "SMS_PRIORITY",
                    "SMS_MESSAGE_SIZE", "MMS_SUBJECT", "MMS_TRANSACTION_ID",
                    "MMS_CONTENT_LOCATION", "MMS_EXPIRY", "RAW_TELEPHONY_STATUS",
                    "SELF_PARTICIPANT_ID", "RETRY_START_TIMESTAMP"),
            "PARTS_TABLE", columns("PartColumns", "_ID", "MESSAGE_ID", "TEXT", "CONTENT_URI",
                    "CONTENT_TYPE", "WIDTH", "HEIGHT", "TIMESTAMP", "CONVERSATION_ID"),
            "PARTICIPANTS_TABLE", columns("ParticipantColumns", "_ID", "SUB_ID", "SIM_SLOT_ID",
                    "NORMALIZED_DESTINATION", "SEND_DESTINATION", "DISPLAY_DESTINATION",
                    "FULL_NAME", "FIRST_NAME", "PROFILE_PHOTO_URI", "CONTACT_ID", "LOOKUP_KEY",
                    "BLOCKED", "SUBSCRIPTION_NAME", "SUBSCRIPTION_COLOR", "CONTACT_DESTINATION"),
            "CONVERSATION_PARTICIPANTS_TABLE", columns("ConversationParticipantsColumns", "_ID",
                    "CONVERSATION_ID", "PARTICIPANT_ID"));

    private static final Pattern TABLE_DEF =
            Pattern.compile("\"CREATE TABLE \"\\s*\\+\\s*(?:DatabaseHelper\\.)?(\\w+)");
    // A column reference followed by a literal starting with a type name; key and constraint
    // clauses follow theirs with "," or ")".
    private static final Pattern COLUMN_DEF = Pattern.compile(
            "(?:DatabaseHelper\\.)?(\\w+Columns\\.\\w+)\\s*\\+\\s*\"\\s*[A-Za-z]");
    private static final Pattern MIGRATION =
            Pattern.compile("private int upgradeToVersion(\\d+)\\(");
    private static final Pattern MIGRATION_STEP = Pattern.compile(
            "final String (\\w+) = DatabaseHelper\\.(\\w+);"
            + "|\"CREATE TABLE \"\\s*\\+\\s*(?:DatabaseHelper\\.)?(\\w+)"
            + "|\"ALTER TABLE \"\\s*\\+\\s*(?:DatabaseHelper\\.)?(\\w+)\\s*\\+\\s*\" ADD COLUMN \""
            + "\\s*\\+\\s*(?:DatabaseHelper\\.)?(\\w+Columns\\.\\w+)");

    /** Every table a fresh install creates and upstream did not is also created by a migration. */
    @Test
    public void everyTableAFreshInstallCreatesIsAlsoCreatedByAMigration() throws IOException {
        // Not SourceScan.codeOnly: the schema is in string literals, so only comments are blanked.
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

    /** Per table, a fresh install and an upgrade from upstream's version 2 agree on columns. */
    @Test
    public void freshInstallAndMigrationChainBuildTheSameColumns() throws IOException {
        final Map<String, Set<String>> fresh =
                freshColumns(withoutComments(SourceScan.read(HELPER)));
        final Map<String, Set<String>> migrated =
                migratedColumns(withoutComments(SourceScan.read(UPGRADE)));

        assertTrue("the fresh-install tables " + fresh.keySet() + " lack an upstream table — this "
                        + "guard has gone stale, not green",
                fresh.keySet().containsAll(UPSTREAM_V2_COLUMNS.keySet()));
        int added = 0;
        for (final Map.Entry<String, Set<String>> e : migrated.entrySet()) {
            final Set<String> upstream = UPSTREAM_V2_COLUMNS.get(e.getKey());
            added += e.getValue().size() - (upstream == null ? 0 : upstream.size());
        }
        assertTrue("the migrations add no column — this guard has gone stale, not green",
                added > 0);

        final Set<String> tables = new TreeSet<>(fresh.keySet());
        tables.addAll(migrated.keySet());
        final List<String> mismatches = new ArrayList<>();
        for (final String t : tables) {
            final Set<String> f = fresh.getOrDefault(t, Set.of());
            final Set<String> m = migrated.getOrDefault(t, Set.of());
            assertFalse("no column parsed for " + t + " in " + HELPER
                            + " — this guard has gone stale, not green",
                    fresh.containsKey(t) && f.isEmpty());
            final Set<String> freshOnly = new TreeSet<>(f);
            freshOnly.removeAll(m);
            final Set<String> migratedOnly = new TreeSet<>(m);
            migratedOnly.removeAll(f);
            if (!freshOnly.isEmpty() || !migratedOnly.isEmpty()) {
                mismatches.add(t + ": fresh install only " + freshOnly
                        + ", migrations only " + migratedOnly);
            }
        }
        if (!mismatches.isEmpty()) {
            fail("A fresh install and an upgraded device end with different columns: " + mismatches
                    + ". A column only the fresh install creates is missing on upgraded devices, "
                    + "and a later migration that adds it fails there with a duplicate column, "
                    + "which rebuilds the database. Add every new column both to the CREATE TABLE "
                    + "in DatabaseHelper and to the newest upgradeToVersionN.");
        }
    }

    /** A mismatch means a migration never runs or the app claims a version it cannot produce. */
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
        assertTrue("no upgradeToVersionN found — this guard has gone stale, not green",
                highest > 0);
        assertEquals("res/values/versions.xml declares database_version=" + declared
                        + " but the highest migration is upgradeToVersion" + highest
                        + ". If the declared version is higher, the gap never runs; if it is lower, "
                        + "the last migration never runs.",
                highest, declared);
    }

    /** Comments blanked, string literals kept: the inverse of {@code SourceScan.codeOnly}. */
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

    /** Columns of each CREATE TABLE in the fresh-install SQL. */
    private static Map<String, Set<String>> freshColumns(final String src) {
        final Map<String, Set<String>> out = new TreeMap<>();
        final Matcher m = TABLE_DEF.matcher(src);
        while (m.find()) {
            final Set<String> cols =
                    matches(COLUMN_DEF, src.substring(m.end(), statementEnd(src, m.end())));
            assertTrue("two CREATE TABLE statements for " + m.group(1),
                    out.put(m.group(1), new TreeSet<>(cols)) == null);
        }
        return out;
    }

    /** Upstream's version-2 columns with every migration past version 2 applied in order. */
    private static Map<String, Set<String>> migratedColumns(final String src) {
        final Map<String, Set<String>> out = new TreeMap<>();
        UPSTREAM_V2_COLUMNS.forEach((t, cols) -> out.put(t, new TreeSet<>(cols)));

        final TreeMap<Integer, String> bodies = new TreeMap<>();
        final Matcher method = MIGRATION.matcher(src);
        int start = -1;
        int version = 0;
        while (true) {
            final boolean found = method.find();
            if (start >= 0) {
                bodies.put(version, src.substring(start, found ? method.start() : src.length()));
            }
            if (!found) {
                break;
            }
            start = method.start();
            version = Integer.parseInt(method.group(1));
        }

        for (final Map.Entry<Integer, String> e : bodies.tailMap(2, false).entrySet()) {
            final String body = e.getValue();
            final Map<String, String> aliases = new TreeMap<>();
            final Matcher step = MIGRATION_STEP.matcher(body);
            while (step.find()) {
                if (step.group(1) != null) {
                    aliases.put(step.group(1), step.group(2));
                } else if (step.group(3) != null) {
                    final String t = table(aliases, step.group(3));
                    assertFalse("upgradeToVersion" + e.getKey() + " creates " + t
                            + ", which already exists at that version", out.containsKey(t));
                    out.put(t, matches(COLUMN_DEF,
                            body.substring(step.end(), statementEnd(body, step.end()))));
                } else {
                    final String t = table(aliases, step.group(4));
                    assertTrue("upgradeToVersion" + e.getKey() + " alters " + t
                            + ", which does not exist at that version", out.containsKey(t));
                    assertTrue("upgradeToVersion" + e.getKey() + " adds " + step.group(5)
                            + " to " + t + ", which already has it", out.get(t).add(step.group(5)));
                }
            }
        }
        return out;
    }

    private static String table(final Map<String, String> aliases, final String token) {
        if (aliases.containsKey(token)) {
            return aliases.get(token);
        }
        assertTrue("cannot resolve the table named by " + token, token.matches("[A-Z_]+"));
        return token;
    }

    /** Index of the ';' that ends the Java statement containing {@code from}. */
    private static int statementEnd(final String src, final int from) {
        boolean inString = false;
        for (int i = from; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (inString && c == '\\') {
                i++;
            } else if (c == '"') {
                inString = !inString;
            } else if (c == ';' && !inString) {
                return i;
            }
        }
        return src.length();
    }

    private static Set<String> columns(final String cls, final String... names) {
        final Set<String> out = new TreeSet<>();
        for (final String n : names) {
            out.add(cls + "." + n);
        }
        return out;
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
