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
import java.util.List;

/**
 * <b>The legacy continuity preference is removed only once the value is provably somewhere else</b>
 *
 * <h2>The defect this pins, and why it is not a tidiness rule</h2>
 *
 * <p>{@code continuityTokenFor} is the reader that was added for this, and it also completes the one-way
 * migration out of the old {@code mls_continuity_token_<key>} preference. As first written it
 * collapsed {@link StoreRead}'s three values to two — {@code r.isOk() ? value : null} — and then
 * removed the preference UNCONDITIONALLY, before testing whether there was anywhere to fold the
 * value into. So on a record the store could not read, a well-formed 32-byte server-issued token was
 * destroyed and the caller was handed empty.
 *
 * <p>And the unreadable case is not a transient blip. {@code MlsRecordStore.get} raises {@code Err}
 * in exactly two places, both of which mean the stored record is CORRUPT ("is not valid Base64",
 * "did not decode"). That is precisely the state in which the legacy preference is the last
 * surviving copy: the fallback was being destroyed exactly when the primary was broken.
 *
 * <p>The same commit's WRITER already had this right — {@code noteContinuityToken} tests
 * {@code isErr()} and refuses to overwrite an unreadable record — so the two halves of one change
 * treated the same three-valued read in opposite ways. {@code StoreRead}'s own class documentation
 * is that collapsing it is the failure the type exists to prevent.
 *
 * <h2>Why a SOURCE guard</h2>
 *
 * <p>{@code MlsProviderTransport} needs a {@code Context} and a bound provider, so the host suite
 * cannot reach it any other way — the standing justification in {@link SourceScan}'s class doc.
 * Its two rules are met here: the assertions key on INVOKED METHOD NAMES ({@code isErr},
 * {@code remove}, {@code put}) rather than on log labels or variable names, and a pattern that goes
 * stale FAILS rather than passing vacuously — {@link #theGuardItselfCanFail()} runs the identical
 * checks against the pre-fix body and asserts that they report both faults by name.
 */
public final class MlsContinuityMigrationGuardTest {

    /**
     * The two properties, as a list of violations so the same code can be pointed at the real body
     * and at the pre-fix one. Empty means the body is correct.
     */
    private static List<String> violations(final String body) {
        final List<String> out = new ArrayList<>();

        final int isErr = body.indexOf("isErr(");
        final int firstRemove = body.indexOf(".remove(");
        final int lastRemove = body.lastIndexOf(".remove(");
        final int put = body.lastIndexOf("mRecords.put(");

        // ZERO HITS MUST FAIL. If the migration stops calling remove() at all, or stops writing
        // through mRecords.put, these anchors are gone and the guard must say so rather than pass
        // on a body it no longer describes.
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

        // 1. An unreadable record must be handled BEFORE the preference is touched. MlsRecordStore
        //    raises Err only for a CORRUPT record, which is the state in which the preference may be
        //    the last copy of the token.
        if (isErr < 0) {
            out.add("ERR-COLLAPSED: continuityTokenFor does not test isErr() at all, so a corrupt "
                    + "record and an absent one are the same value — and the branch below deletes "
                    + "the legacy preference for both. StoreRead exists to make this impossible.");
        } else if (isErr > firstRemove) {
            out.add("ERR-TOO-LATE: continuityTokenFor tests isErr() only AFTER it has already "
                    + "removed the legacy preference, which is the same data loss with an extra "
                    + "step.");
        }

        // 2. The removal that COMPLETES the migration must come after the write that makes it safe.
        //    Pre-fix, the single remove() stood above mRecords.put and ran whatever the write did.
        if (lastRemove < put) {
            out.add("REMOVE-BEFORE-WRITE: the last remove() of the legacy preference stands ABOVE "
                    + "mRecords.put, so the preference is dropped before anything has been written "
                    + "— on a failed write the token exists nowhere.");
        }

        return out;
    }

    /**
     * The brace-matched body of {@code continuityTokenFor}.
     *
     * <p>{@link SourceScan#bodyOf} cannot find it: its {@code METHOD_DECL} pattern requires a
     * visibility modifier and this method is deliberately PACKAGE-PRIVATE — nothing outside the
     * transport may ask for a continuity token yet. The first run of this guard failed on exactly
     * that, which is the shared helper's "zero hits must FAIL" rule doing its job on the guard
     * itself rather than on the code. Located by the declaration text instead, then brace-matched
     * over {@code codeOnly} output so a brace inside a comment or a string cannot end the body
     * early.
     */
    private static String continuityTokenForBody() throws IOException {
        final String src = SourceScan.codeOnly(SourceScan.read(SourceScan.TRANSPORT));
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
        assertTrue("MlsProviderTransport.continuityTokenFor is gone — the only "
                + "thing that answers 'do we hold a continuity token for this group'.",
                body.length() > 0);
        assertTrue("continuityTokenFor no longer mentions LEGACY_CONTINUITY_PREFIX, so this guard "
                + "is pointed at the wrong method or the migration moved.",
                body.contains("LEGACY_CONTINUITY_PREFIX"));

        final List<String> v = violations(body);
        assertEquals("continuityTokenFor can destroy a continuity token: " + v, 0, v.size());
    }

    /**
     * THE MUTATION CHECK. A guard whose assertions cannot fail is not evidence, so the identical
     * {@link #violations} are run against the body as it stood BEFORE the fix. Both faults must be
     * named; a guard that passes this body is not guarding anything.
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
