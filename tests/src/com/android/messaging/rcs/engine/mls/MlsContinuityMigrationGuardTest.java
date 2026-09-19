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
import java.util.List;

/**
 * {@code continuityTokenFor} removes the legacy {@code mls_continuity_token_<key>} preference only
 * once the value is stored elsewhere. {@code MlsRecordStore.get} answers Err only for a corrupt
 * record, which is when the preference may be the last copy, so an unreadable record is handled
 * before the preference is touched and the removal follows the write.
 *
 * <p>A source guard because the transport needs a {@code Context}; it keys on invoked method names,
 * and {@link #theGuardItselfCanFail()} runs the same checks against a body with both faults.
 */
public final class MlsContinuityMigrationGuardTest {

    /** The violations found in {@code body}; empty means the body is correct. */
    private static List<String> violations(final String body) {
        final List<String> out = new ArrayList<>();

        final int isErr = body.indexOf("isErr(");
        final int firstRemove = body.indexOf(".remove(");
        final int lastRemove = body.lastIndexOf(".remove(");
        final int put = body.lastIndexOf("mRecords.put(");

        // Zero hits fail: without these anchors the guard no longer describes the body.
        if (firstRemove < 0) {
            out.add("NO-REMOVE: nothing in continuityTokenFor removes the legacy preference any "
                    + "more, so this guard no longer describes the method it is guarding.");
            return out;
        }
        if (put < 0) {
            out.add("NO-PUT: continuityTokenFor no longer writes through mRecords.put, so 'the "
                    + "value is provably somewhere else' has no anchor to be checked against.");
            return out;
        }

        // 1. An unreadable record is handled before the preference is touched.
        if (isErr < 0) {
            out.add("ERR-COLLAPSED: continuityTokenFor does not test isErr() at all, so a corrupt "
                    + "record and an absent one are the same value — and the branch below deletes "
                    + "the legacy preference for both. StoreRead exists to make this impossible.");
        } else if (isErr > firstRemove) {
            out.add("ERR-TOO-LATE: continuityTokenFor tests isErr() only AFTER it has already "
                    + "removed the legacy preference, which is the same data loss with an extra "
                    + "step.");
        }

        // 2. The removal that completes the migration comes after the write that makes it safe.
        if (lastRemove < put) {
            out.add("REMOVE-BEFORE-WRITE: the last remove() of the legacy preference stands ABOVE "
                    + "mRecords.put, so the preference is dropped before anything has been written "
                    + "— on a failed write the token exists nowhere.");
        }

        return out;
    }

    /**
     * The brace-matched body of {@code continuityTokenFor} in the unsplit view, located by its
     * declaration text and brace-matched over {@code codeOnly} output.
     */
    private static String continuityTokenForBody() throws IOException {
        final String src = SourceScan.transportUnsplitCode();
        final int decl = src.indexOf("byte[] continuityTokenFor(");
        if (decl < 0) return "";
        final int open = src.indexOf('{', decl);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            final char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, i + 1);
        }
        return "";
    }

    @Test
    public void theMigrationNeverDropsTheLastCopy() throws IOException {
        final String body = continuityTokenForBody();
        assertTrue("MlsRecordState.continuityTokenFor is gone — the only "
                + "thing that answers 'do we hold a continuity token for this group'.",
                body.length() > 0);
        assertTrue("continuityTokenFor no longer mentions LEGACY_CONTINUITY_PREFIX, so this guard "
                + "is pointed at the wrong method or the migration moved.",
                body.contains("LEGACY_CONTINUITY_PREFIX"));

        final List<String> v = violations(body);
        assertEquals("continuityTokenFor can destroy a continuity token: " + v, 0, v.size());
    }

    /**
     * The same {@link #violations} run against a body with both faults must name both; otherwise
     * the guard cannot fail.
     */
    @Test
    public void theGuardItselfCanFail() {
        final String preFix = ""
                + "{\n"
                + "    final StoreRead<MlsConversationRecord> r = mRecords.get(self, g.groupId);\n"
                + "    final MlsConversationRecord rec = r.isOk()\n"
                + "            ? ((StoreRead.Ok<MlsConversationRecord>) r).value : null;\n"
                + "    if (rec != null && rec.hasContinuityToken()) return rec.continuityToken;\n"
                + "    final SharedPreferences prefs = mCtx.getSharedPreferences(PREFS, 0);\n"
                + "    final String legacy = prefs.getString(LEGACY_CONTINUITY_PREFIX + key, null);\n"
                + "    if (legacy == null) return new byte[0];\n"
                + "    byte[] decoded = Base64.decode(legacy, Base64.NO_WRAP);\n"
                + "    prefs.edit().remove(LEGACY_CONTINUITY_PREFIX + key).apply();\n"
                + "    if (decoded == null || decoded.length == 0 || rec == null) {\n"
                + "        return new byte[0];\n"
                + "    }\n"
                + "    final String err = mRecords.put(rec.toBuilder().continuityToken(decoded).build());\n"
                + "    return err == null ? decoded : new byte[0];\n"
                + "}\n";

        final List<String> v = violations(preFix);
        assertEquals("the pre-fix body must fail BOTH checks, else this guard is decorative: " + v,
                2, v.size());
        assertTrue("the collapsed three-valued read must be named: " + v,
                v.get(0).startsWith("ERR-COLLAPSED"));
        assertTrue("the remove-before-write must be named: " + v,
                v.get(1).startsWith("REMOVE-BEFORE-WRITE"));
    }
}
