/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A log call writes a phone number through {@code LogMask.number} (or {@code numbers}), unless it
 * sits inside an if that requires a debug build: logcat is readable over adb and lands in bug
 * reports. Every log call in the app and the engine is scanned for a number-named identifier
 * rendered into the line: concatenated, a ternary arm, or a format argument. In MLS code a
 * conversation key ({@code p:} and the number), a message id (a 1:1 control id embeds the number)
 * and a roster carry numbers too, so there those names are checked as well, and must go through
 * {@link MlsConversationKey#forLog}, {@link MlsMessageId#forLog} or {@code LogMask.numbers}. A line
 * built outside the log call (a describe method, an edge reason) is caught where a sensitive name
 * is concatenated next to a literal with a space in it: prose, not an id or a map key. A value
 * returned by a call counts as that call's name ({@code canonicalKey(...)}, {@code scopeKey()}),
 * and in MLS code a stored string read back ({@code getString}) counts as a key: the sweep cursor
 * printed that way leaked on devices. The variable of a for-each loop over numbers or, in MLS
 * code, a roster is a number inside the loop, and a value appended to a builder that writes prose
 * anywhere in its scope is in a line: the server roster was logged that way, one
 * {@code raw.append(' ').append(e164)} at a time. A number that reaches a line as the argument of
 * another method is that method's business.
 */
public class PhoneNumberLogGuardTest {

    private static final String[] ROOTS = {"src", "engine/src"};

    /** Identifiers that hold a number wherever they appear. */
    private static final Pattern NUMBER_NAME = Pattern.compile(
            "[a-z][\\w$]*(?:E164|E164s|Msisdn|MSISDN|Tel)(?:Raw)?|e164|msisdn|tel");

    /** File (simple name) → the other identifiers that hold a number in it. */
    private static final Map<String, Set<String>> ALSO = new HashMap<>();
    static {
        also("MlsCarrierTransport", "peer");
        also("MlsClaimLedger", "peer");
        also("MlsOneToOneGroup", "peer");
        also("MlsProviderTransport", "from");
        also("MlsStalledNotifier", "who");
        also("MlsCredentialUpdate", "self");
        also("MlsServerBundle", "self", "out", "recorded", "server");
        also("MlsEraAdvance", "members");
        also("MlsGroupEstablish", "members");
        also("MlsKeyPackagePolicy", "who");
        also("MlsCredentialFloor", "who");
        also("MlsMembership", "member");
        also("RcsCallbackRouter", "from", "fromUri");
        also("ReceiveRcsGroupEventAction", "requester");
        also("MlsResendLedger", "recipientAddress");
        also("CarrierImsService", "toUri");
        also("CarrierMessageReceiver", "fromUri");
        also("CarrierMessageSender", "toUri");
        also("CarrierMsrpSessionManager", "fromUri", "toUri");
        also("CarrierRcsTransport", "fromUri", "toUri");
        also("ProviderTransport", "toUri");
    }

    /** In MLS code: conversation keys. {@code conversation} and {@code cursor} hold one too. */
    private static final Pattern KEY_NAME = Pattern.compile(
            "key|conversation|conversationId|convId|cursor|[a-z][\\w$]*Key");
    /** In MLS code: message ids, which a 1:1 control id makes a number carrier. */
    private static final Pattern ID_NAME = Pattern.compile(
            "mid|msgId|ctrlId|messageId|[a-z][\\w$]*(?:MessageId|MsgId|CtrlId)");
    /** In MLS code: member lists. */
    private static final Pattern ROSTER_NAME = Pattern.compile(
            "members|roster|participants|[a-z][\\w$]*(?:Members|Roster)");
    /** Outside the engine and the e2ee package, a method whose name contains Mls is MLS code. */
    private static final Pattern MLS_METHOD =
            Pattern.compile("[\\w$>\\]]\\s+([\\w$]*Mls[\\w$]*)\\s*\\(");

    /**
     * File (simple name) → a helper of its own that masks what it renders. Each is checked to call
     * a mask; a call to it counts as masked.
     */
    private static final Map<String, String> FILE_MASKS = new HashMap<>();
    static {
        FILE_MASKS.put("MlsFetchLedger", "safe");
        FILE_MASKS.put("MlsClaimLedger", "safe");
        FILE_MASKS.put("MlsPeerGuard", "redactKey");
    }

    /** Debug receivers: onReceive returns first on a user build, so nothing below it runs. */
    private static final List<String> DEBUG_RECEIVERS = Arrays.asList(
            "MlsSendReceiver", "RcsDebugCarrierDriveReceiver", "RcsDebugComposeSendReceiver",
            "RcsDebugFtAcceptReceiver", "RcsDebugFtSendReceiver", "RcsDebugGroupReceiver",
            "RcsDebugSendReceiver", "MlsEnrollDebugReceiver", "SipDelegateDebugReceiver");

    private static final Pattern LOG_CALL =
            Pattern.compile("\\b(?:LogUtil|Log|log|mLog)\\s*\\.\\s*(?:v|d|i|w|e|wtf)\\s*\\(");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");
    /** Calls whose arguments reach the line as they are. */
    private static final Set<String> RENDERING_CALLS = new HashSet<>(Arrays.asList(
            "String.format", "format", "String.valueOf", "valueOf"));
    /** Words that can stand right before a rendered value, where a type names a declaration. */
    private static final Set<String> RETURNS = new HashSet<>(Arrays.asList("return", "throw"));
    /** Reads of a stored string: preferences, a database cursor, a JSON object, a Data bag. */
    private static final Set<String> STORE_READ = new HashSet<>(Arrays.asList(
            "getString", "getStringSet", "optString", "getAsString"));
    private static final Set<String> MASKS = new HashSet<>(Arrays.asList(
            "LogMask.number", "LogMask.numbers", "MlsConversationKey.forLog",
            "MlsMessageId.forLog"));

    private static void also(final String file, final String... names) {
        ALSO.put(file, new HashSet<>(Arrays.asList(names)));
    }

    @Test
    public void noLogCallRendersAnUnmaskedNumber() throws IOException {
        final Scan scan = scanTree();
        assertTrue("only " + scan.seen[0] + " files scanned; the walk lost its subject",
                scan.seen[0] > 500);
        assertTrue("only " + scan.seen[1] + " log calls found; the call pattern lost its subject",
                scan.seen[1] > 2000);
        if (!scan.logLeaks.isEmpty()) {
            fail("a log call renders a phone number, a conversation key, a message id or a roster "
                    + "unmasked. Wrap it in LogMask.number(...) / LogMask.numbers(...), "
                    + "MlsConversationKey.forLog(...) or MlsMessageId.forLog(...), or put the line "
                    + "inside an if on RcsDebug.isDebugBuild() / "
                    + "shell.sysprops().debuggableBuild(). Offending:\n  "
                    + String.join("\n  ", scan.logLeaks));
        }
        assertTrue("only " + scan.seen[2] + " masked numbers in log calls; the name pattern lost "
                + "its subject", scan.seen[2] > 200);
        assertTrue("only " + scan.seen[3] + " masked keys and ids in MLS log calls; the MLS scope "
                + "or the key and id patterns lost their subject", scan.seen[3] > 400);
    }

    /**
     * A line built outside the log call (a describe method, a health-edge reason) is prose: a
     * sensitive name concatenated next to a literal with a space in it. An id or a map key is
     * built from literals without one ({@code "mls-endmls-"}, {@code "p:"}), so those stay raw.
     */
    @Test
    public void noLineBuilderConcatenatesAnUnmaskedNumber() throws IOException {
        final Scan scan = scanTree();
        if (!scan.builderLeaks.isEmpty()) {
            fail("a line builder concatenates a phone number, a conversation key, a message id or "
                    + "a roster unmasked. Mask it as in a log call. Offending:\n  "
                    + String.join("\n  ", scan.builderLeaks));
        }
        assertTrue("only " + scan.seen[4] + " masked or prose-adjacent values checked outside "
                + "log calls; the builder scan lost its subject", scan.seen[4] > 60);
    }

    /** A helper exempted as a mask must call one. */
    @Test
    public void everyFileMaskCallsAMask() throws IOException {
        final Map<String, File> byName = new HashMap<>();
        for (final String root : ROOTS) {
            for (final File f : javaUnder(root)) byName.put(f.getName(), f);
        }
        for (final Map.Entry<String, String> e : FILE_MASKS.entrySet()) {
            final File f = byName.get(e.getKey() + ".java");
            assertTrue(e.getKey() + " is gone; take it off FILE_MASKS", f != null);
            final String body = SourceScan.bodyOf(SourceScan.codeOnly(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)),
                    e.getValue());
            assertTrue(e.getKey() + "." + e.getValue() + " is gone", !body.isEmpty());
            boolean masks = false;
            for (final String m : MASKS) masks |= body.contains(m + "(");
            assertTrue(e.getKey() + "." + e.getValue() + " masks nothing", masks);
        }
    }

    /** The exemption is only as good as the early return it relies on. */
    @Test
    public void everyExemptReceiverReturnsFirstOnAUserBuild() throws IOException {
        final Map<String, File> byName = new HashMap<>();
        for (final File f : javaUnder("src")) byName.put(f.getName(), f);
        for (final String r : DEBUG_RECEIVERS) {
            final File f = byName.get(r + ".java");
            assertTrue(r + " is gone; take it off DEBUG_RECEIVERS", f != null);
            final String body = SourceScan.bodyOf(SourceScan.codeOnly(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)),
                    "onReceive");
            final int test = body.indexOf("isDebugBuild()");
            final int log = firstLogCall(body);
            assertTrue(r + ".onReceive logs before its debug-build test", test >= 0
                    && (log < 0 || test < log));
        }
    }

    /**
     * The Rust core logs through {@code alog!} on every build and cannot tell a debug build, so it
     * masks: no MSISDN rendered raw, and no epoch authenticator, which is a group secret.
     */
    @Test
    public void theRustCoreMasksNumbersAndOmitsTheEpochAuthenticator() throws IOException {
        final String ffi = SourceScan.read("rust/rcs_mls_ffi/src/ffi.rs");
        assertTrue("no hits: the encrypt-diag line was not found in ffi.rs",
                ffi.contains("alog!(\"encrypt-diag: "));
        assertTrue("ffi.rs logs the epoch authenticator", !ffi.contains("epoch_auth={"));
        assertTrue("ffi.rs renders an MSISDN unmasked; use masked_msisdn",
                !ffi.contains("from_utf8_lossy(msisdn)"));
        assertTrue("no hits: masked_msisdn is not used", SourceScan.count(ffi,
                "masked_msisdn(msisdn)") >= 2);
    }

    /** The scan must be able to fail: the shapes it replaced, through the same predicate. */
    @Test
    public void theScanCatchesTheLinesItReplaced() {
        final String[] leaking = {
            "log.i(\"x \" + peerE164 + \" y\");",
            "LogUtil.i(TAG, \"onMlsControl: from=\" + fromE164 + \" msgId=\" + id);",
            "log.w(\"a \" + (peerE164 == null ? \"-\" : peerE164));",
            "Log.i(TAG, String.format(\"to %s\", toTel));",
            "log.i(\"members \" + memberE164s);",
            "log.i(\"from \" + h.fromE164);",
            "log.i(\"for \" + peer);",
        };
        for (final String line : leaking) {
            assertTrue("must flag: " + line, !scanOne("MlsOneToOneGroup", line,
                    Collections.singleton("peer"), false).logLeaks.isEmpty());
        }
        // Seen on a device: a 1:1 key, a control id, a roster.
        final String[] leakingMls = {
            "log.i(\"maintenance (\" + cause + \") for \" + key + \" → \" + verdict);",
            "log.i(\"MlsSealSend: sendSealed conv=\" + convId + \" era=\" + era);",
            "LogUtil.i(TAG, arm + \": MLS headers OK for \" + messageId + \" from \");",
            "log.i(\"stopped at '\" + cursor + \"' of \" + n);",
            "log.i(\"roster \" + roster);",
            "log.i(\"joined key=\" + String.valueOf(conversationKey));",
            // Seen on devices (devtest8): the stored sweep cursor, read back in the log call.
            "log.i(\"from '\" + p.getString(MlsMaintenancePass.PREF_SWEEP_CURSOR, \"\") + \"'\");",
            "log.i(\"from \" + shell.prefs().getString(PREF_SWEEP_CURSOR, \"\"));",
            "log.w(\"for \" + MlsConversationKey.canonicalKey(rcsGroupId, peerE164));",
            "LogUtil.w(TAG, \"undo for \" + store.scopeKey() + \" dropped\");",
            "log.i(\"self \" + shell.selfE164());",
            // Each claimed member, named by the loop variable: six production lines did this.
            "for (final String m : members) { log.w(\"refused for \" + m); }",
            "log.i(\"our leaf \" + shell.selfE164Raw());",
        };
        for (final String line : leakingMls) {
            assertTrue("must flag: " + line, !scanOne("MlsFloorRebuild", line,
                    Collections.emptySet(), true).logLeaks.isEmpty());
        }
        final String[] leakingBuilders = {
            "return \"MLS fetch ledger charged \" + name(w) + \" on \" + conversation + \" — \";",
            "shell.moveHealth(key, HEALTHY, \"group created with \" + peer);",
            "final String line = \"for \" + key;",
            "return \"on \" + String.valueOf(conversationKey) + \" at \" + era;",
            "return key + \" — NOT re-dialling\";",
            "sb.append(\" scope=\").append(p.getString(K_SCOPE, \"<none>\"));",
            "sb.append(\" peer=\").append(peer);",
            "for (Map.Entry<String, Long> e : m.entrySet()) {"
                    + " sb.append(\" at \").append(e.getKey()); }",
            // Seen on a device (devtest11): the server roster, one entry at a time.
            "final StringBuilder raw = new StringBuilder(); for (int i = 2; ; i++) {"
                    + " final String e164 = next(i); raw.append(' ').append(e164); }",
            "for (final String m : roster) { per.append(\"\\n  \").append(m); }",
        };
        for (final String line : leakingBuilders) {
            assertTrue("must flag: " + line, !scanOne("MlsOneToOneGroup", line,
                    Collections.singleton("peer"), true).builderLeaks.isEmpty());
        }
        final String[] clean = {
            "log.i(\"x \" + LogMask.number(peerE164) + \" y\");",
            "log.i(\"n=\" + peerE164.length() + \" \" + (peerE164 == null));",
            "if (RcsDebug.isDebugBuild()) { log.i(\"x \" + peerE164); }",
            "log.i(\"members \" + LogMask.numbers(memberE164s));",
            "log.i(\"for \" + peer);",
            // A key outside MLS code is an ordinary key.
            "log.i(\"pref \" + key);",
        };
        for (final String line : clean) {
            final Scan r = scanOne("MlsGroupState", line, Collections.emptySet(), false);
            assertTrue("must pass: " + line, r.logLeaks.isEmpty() && r.builderLeaks.isEmpty());
        }
        final String[] cleanMls = {
            "log.i(\"for \" + MlsConversationKey.forLog(key) + \" → \" + verdict);",
            "LogUtil.i(TAG, \"OK for \" + MlsMessageId.forLog(messageId));",
            "log.i(\"roster \" + LogMask.numbers(roster));",
            "log.i(\"key=\" + MlsConversationKey.forLog(String.valueOf(key)));",
            "log.i(\"n=\" + roster.size() + \" \" + map.get(key));",
            // An id and a map key are built from literals without a space.
            "final String ctrlId = \"mls-endmls-\" + peerE164 + \"-\" + now;",
            "final String key = \"p:\" + peerE164;",
            "return \"on \" + safe(conversation);",
            "log.i(\"from '\" + MlsConversationKey.forLog(p.getString(PREF_SWEEP_CURSOR, \"\")));",
            "log.i(\"for \" + MlsConversationKey.forLog(MlsConversationKey.canonicalKey(g, e)));",
            "log.i(\"armed=\" + p.getBoolean(PREF_SWEEP_ARMED, false) + \" n=\" + p.getInt(N, 0));",
            "if (shell.selfE164().isEmpty()) log.i(\"no self \" + x);",
            "sb.append(\"rec{\").append(MlsConversationKey.forLog(key())).append(\" x\");",
            "return MlsConversationKey.forLog(key) + \" — NOT re-dialling\";",
            "for (Map.Entry<Integer, long[]> e : v.entrySet()) {"
                    + " sb.append(\" leaf \").append(e.getKey()); }",
            // A pref key built with a builder writes no prose.
            "final StringBuilder sb = new StringBuilder(\"fileinfo_\").append(slot).append('_')"
                    + ".append(convKey).append('_');",
            "raw.append(' ').append(LogMask.number(e164));",
            "for (final String m : roster) { log.i(\"n=\" + m.length()); }",
            "for (final String m : roster) { log.i(\"for \" + LogMask.number(m)); }",
        };
        for (final String line : cleanMls) {
            final Scan r = scanOne("MlsFetchLedger", line, Collections.emptySet(), true);
            assertTrue("must pass: " + line + " " + r.logLeaks + r.builderLeaks,
                    r.logLeaks.isEmpty() && r.builderLeaks.isEmpty());
        }
    }

    // ---- the scan ----

    /** What one or more files hold: leaks by kind, and the counters that keep the scan honest. */
    private static final class Scan {
        final List<String> logLeaks = new ArrayList<>();
        final List<String> builderLeaks = new ArrayList<>();
        /** Files, log calls, masked numbers, masked keys/ids/rosters, prose concatenations. */
        final int[] seen = new int[5];
    }

    private static Scan scanTree() throws IOException {
        final Scan scan = new Scan();
        for (final String root : ROOTS) {
            for (final File f : javaUnder(root)) {
                final String name = f.getName().substring(0, f.getName().length() - 5);
                if (DEBUG_RECEIVERS.contains(name)) continue;
                final String src =
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                final String path = f.getPath().replace('\\', '/');
                scan.seen[0]++;
                scan(scan, name, SourceScan.codeOnly(src), src,
                        ALSO.getOrDefault(name, Collections.emptySet()),
                        path.contains("engine/src/") || path.contains("/rcs/e2ee/"));
            }
        }
        return scan;
    }

    private static Scan scanOne(final String file, final String src, final Set<String> also,
            final boolean mlsFile) {
        final Scan scan = new Scan();
        scan(scan, file, SourceScan.codeOnly(src), src, also, mlsFile);
        return scan;
    }

    private static void scan(final Scan out, final String file, final String code,
            final String src, final Set<String> also, final boolean mlsFile) {
        final BitSet mls = mlsScope(code, mlsFile);
        final Map<String, BitSet> each = loopVariables(code, also, mls);
        final BitSet inLogCall = new BitSet(code.length());
        final Matcher m = LOG_CALL.matcher(code);
        while (m.find()) {
            final int open = m.end() - 1;
            final int close = closeParen(code, open);
            if (close < 0) continue;
            out.seen[1]++;
            inLogCall.set(m.start(), close + 1);
            if (insideBuildGatedIf(code, m.start())) continue;
            final Matcher id = IDENT.matcher(code);
            id.region(open + 1, close);
            while (id.find()) {
                final int start = rendered(code, id, also, mls, each);
                if (start < 0) continue;
                final int verdict = masking(code, open, start);
                if (verdict == MASKED) {
                    out.seen[isNumberName(id.group(), also) ? 2 : 3]++;
                    continue;
                }
                // An argument to another method is that method's business: a key, a builder.
                if (verdict == OTHER_CALL) continue;
                out.logLeaks.add(where(file, code, start, id.end())
                        + code.substring(m.start(), close + 1).replaceAll("\\s+", " "));
            }
        }
        final Matcher id = IDENT.matcher(code);
        while (id.find()) {
            if (inLogCall.get(id.start())) continue;
            final int start = rendered(code, id, also, mls, each);
            if (start < 0) continue;
            final int statement = statementStart(code, start);
            final int verdict = masking(code, statement, start);
            final String call = enclosingCall(code, statement, start);
            if (verdict == MASKED || call != null && call.equals(FILE_MASKS.get(file))) {
                out.seen[4]++;
                continue;
            }
            // Handed to another method, the value is prose when it is concatenated inside that
            // method's argument ("group created with " + peer), or appended to a builder that
            // writes prose (the roster line: raw.append(' ').append(e164) in a loop).
            if (!nextToProse(code, src, start, id.end())
                    && !(".append".equals(call) && builderWritesProse(code, src, start))) {
                continue;
            }
            out.seen[4]++;
            if (insideBuildGatedIf(code, start)) continue;
            out.builderLeaks.add(where(file, code, start, id.end())
                    + code.substring(statement, Math.min(code.length(), id.end() + 40))
                            .replaceAll("\\s+", " ").trim());
        }
    }

    private static final int MASKED = 0;
    private static final int OTHER_CALL = 1;
    private static final int RENDERED = 2;

    private static boolean isNumberName(final String name, final Set<String> also) {
        return NUMBER_NAME.matcher(name).matches() || also.contains(name);
    }

    private static boolean isMlsName(final String name) {
        return KEY_NAME.matcher(name).matches() || ID_NAME.matcher(name).matches()
                || ROSTER_NAME.matcher(name).matches();
    }

    /**
     * A call whose result carries a number: one named like a number anywhere, and in MLS code one
     * named like a key, an id or a roster ({@code canonicalKey(...)}, {@code scopeKey()}) or a
     * stored string read back ({@code getString(PREF_SWEEP_CURSOR, "")}): what was stored there
     * is a key or a peer as often as not, and the name at the log call no longer says so.
     */
    private static boolean isSensitiveCall(final String name, final Set<String> also,
            final boolean mls) {
        if (FILE_MASKS.containsValue(name)) return false;
        return isNumberName(name, also) || mls && (isMlsName(name) || STORE_READ.contains(name));
    }

    /**
     * Where the sensitive value named at {@code id} starts (a dotted prefix included), or -1 when
     * the name is not sensitive here or the value is not rendered (a member access, a call, an
     * assignment or a comparison).
     */
    private static int rendered(final String code, final Matcher id, final Set<String> also,
            final BitSet mls, final Map<String, BitSet> each) {
        final String name = id.group();
        if (id.start() > 0 && Character.isJavaIdentifierPart(code.charAt(id.start() - 1))) {
            return -1;
        }
        int end = id.end();
        while (end < code.length() && Character.isWhitespace(code.charAt(end))) end++;
        final boolean mlsHere = mls.get(id.start());
        if (end < code.length() && code.charAt(end) == '(') {
            // A call returns the value: a key built or looked up, a stored string read back.
            if (!isSensitiveCall(name, also, mlsHere)) return -1;
            if (name.equals("getKey") && entryKeyIsNotString(code, id.start())) return -1;
            end = closeParen(code, end) + 1;
            if (end <= 0) return -1;
            while (end < code.length() && Character.isWhitespace(code.charAt(end))) end++;
        } else if (!isNumberName(name, also) && !(mlsHere && isMlsName(name)
                && !declaredAsOtherType(code, name, id.start()))
                && !(each.containsKey(name) && each.get(name).get(id.start()))) {
            return -1;
        }
        if (end < code.length()) {
            final char c = code.charAt(end);
            if (c == '.' || c == '(' || c == '[') return -1;
            if (c == '=' && (end + 1 >= code.length() || code.charAt(end + 1) != '=')) return -1;
            if (c == '=' || c == '!' && end + 1 < code.length() && code.charAt(end + 1) == '=') {
                return -1;
            }
        }
        int start = id.start();
        while (start > 1 && code.charAt(start - 1) == '.') {
            int k = start - 1;
            // A receiver that is itself a call: prefs().getString(...)
            if (k > 0 && code.charAt(k - 1) == ')') {
                final int o = matchingOpen(code, k - 1);
                if (o < 0) break;
                k = o;
            }
            while (k > 0 && Character.isJavaIdentifierPart(code.charAt(k - 1))) k--;
            start = k;
        }
        int b = start;
        while (b > 0 && Character.isWhitespace(code.charAt(b - 1))) b--;
        if (b > 1 && (code.startsWith("==", b - 2) || code.startsWith("!=", b - 2))) return -1;
        // A declaration names the value; it does not render it. A keyword before it is no type.
        if (b > 0 && (Character.isJavaIdentifierPart(code.charAt(b - 1))
                && !RETURNS.contains(wordBefore(code, b))
                || code.charAt(b - 1) == '>' || code.charAt(b - 1) == ']')) {
            return -1;
        }
        return start;
    }

    /**
     * The variable of each for-each loop over a number or, in MLS code, a roster, mapped to the
     * offsets of the loop's body: {@code for (String m : roster)} makes {@code m} a number there.
     */
    private static Map<String, BitSet> loopVariables(final String code, final Set<String> also,
            final BitSet mls) {
        final Map<String, BitSet> out = new HashMap<>();
        final Matcher m = FOR_EACH.matcher(code);
        while (m.find()) {
            final String over = m.group(2);
            final String last = over.substring(over.lastIndexOf('.') + 1);
            if (!isNumberName(last, also)
                    && !(mls.get(m.start()) && ROSTER_NAME.matcher(last).matches())) {
                continue;
            }
            final int close = closeParen(code, code.indexOf('(', m.start()));
            if (close < 0) continue;
            int body = close + 1;
            while (body < code.length() && Character.isWhitespace(code.charAt(body))) body++;
            final int end = body < code.length() && code.charAt(body) == '{'
                    ? closeBrace(code, body) : code.indexOf(';', body);
            if (end < 0) continue;
            out.computeIfAbsent(m.group(1), k -> new BitSet(code.length())).set(body, end + 1);
        }
        return out;
    }

    private static final Pattern FOR_EACH = Pattern.compile(
            "\\bfor\\s*\\(\\s*(?:final\\s+)?String\\s+([\\w$]+)\\s*:\\s*([\\w$.]+)\\s*\\)");

    /**
     * Whether the {@code x.getKey()} at {@code at} reads a map entry declared with a key type other
     * than String ({@code Map.Entry<Integer, long[]> e}: a leaf index).
     */
    private static boolean entryKeyIsNotString(final String code, final int at) {
        int k = at - 1;
        while (k > 0 && Character.isWhitespace(code.charAt(k))) k--;
        if (k < 1 || code.charAt(k) != '.') return false;
        final String receiver = wordBefore(code, k);
        if (receiver.isEmpty()) return false;
        final Matcher d = Pattern.compile("Entry<\\s*([\\w.]+)\\s*,[^;{}()=]*?>\\s+"
                + Pattern.quote(receiver) + "\\s*[=:;]").matcher(code);
        String type = null;
        while (d.find() && d.start() < at) type = d.group(1);
        return type != null && !type.equals("String");
    }

    /**
     * Whether the nearest declaration of {@code name} before {@code at} gives it a type that holds
     * no number: a primitive, a boxed number or a class of its own ({@code int participants},
     * {@code BudgetKey key}). A String, a collection, or no declaration in the file (a field of
     * another class) keeps it sensitive.
     */
    private static boolean declaredAsOtherType(final String code, final String name,
            final int at) {
        final Matcher d = Pattern.compile("([A-Za-z_$][\\w.$]*(?:<[^;{}()=]*?>)?(?:\\[\\])*)\\s+"
                + Pattern.quote(name) + "\\s*[=;,):]").matcher(code);
        String type = null;
        while (d.find() && d.start() < at) {
            final String t = d.group(1);
            if (!DECL_NOISE.contains(t)) type = t;
        }
        if (type == null) return false;
        return !(type.equals("String") || type.equals("CharSequence")
                || COLLECTION_TYPE.matcher(type).lookingAt());
    }

    private static final Set<String> DECL_NOISE = new HashSet<>(Arrays.asList(
            "return", "new", "else", "case", "throw", "final", "static", "private", "public",
            "protected"));
    private static final Pattern COLLECTION_TYPE = Pattern.compile(
            "(?:java\\.util\\.)?(?:List|Set|Collection|ArrayList|LinkedHashSet|HashSet|Iterable|"
                    + "SortedSet|TreeSet)\\b");

    /**
     * Whether the value at {@code at} is masked, handed to another method, or rendered as it is,
     * looking out through the calls that enclose it back to {@code open}. A rendering call
     * ({@code String.valueOf}) passes its argument on, so the next call out decides.
     */
    private static int masking(final String code, final int open, final int at) {
        int from = at;
        while (true) {
            final int paren = enclosingParen(code, open, from);
            if (paren < 0) return RENDERED;
            final String call = callBefore(code, paren);
            if (call == null) {
                from = paren;
                continue;
            }
            if (MASKS.contains(call)) return MASKED;
            if (!RENDERING_CALLS.contains(call)) return OTHER_CALL;
            from = paren - call.length();
        }
    }

    /**
     * Whether the value at {@code [start, end)} is concatenated next to a string literal with a
     * space in it, looking out through grouping parentheses and rendering calls, never through the
     * parentheses of another method's call.
     */
    private static boolean nextToProse(final String code, final String src, final int start,
            final int end) {
        int a = end;
        while (a < code.length()) {
            final char c = code.charAt(a);
            if (Character.isWhitespace(c)) {
                a++;
            } else if (c == ')' && passThrough(code, matchingOpen(code, a))) {
                a++;
            } else {
                break;
            }
        }
        if (a < code.length() && code.charAt(a) == '+' && (a + 1 >= code.length()
                || code.charAt(a + 1) != '+' && code.charAt(a + 1) != '=')) {
            a++;
            while (a < code.length() && Character.isWhitespace(code.charAt(a))) a++;
            if (a < code.length() && code.charAt(a) == '"') {
                final int close = code.indexOf('"', a + 1);
                if (close > a && src.substring(a + 1, close).indexOf(' ') >= 0) return true;
            }
        }
        int b = start;
        while (b > 0) {
            final char c = code.charAt(b - 1);
            if (Character.isWhitespace(c)) {
                b--;
            } else if (c == '(' && passThrough(code, b - 1)) {
                b = callStart(code, b - 1);
            } else {
                break;
            }
        }
        if (b > 0 && code.charAt(b - 1) == '+' && (b < 2 || code.charAt(b - 2) != '+')) {
            b--;
            while (b > 0 && Character.isWhitespace(code.charAt(b - 1))) b--;
            if (b > 0 && code.charAt(b - 1) == '"') {
                final int open = code.lastIndexOf('"', b - 2);
                if (open >= 0 && src.substring(open + 1, b - 1).indexOf(' ') >= 0) return true;
            }
        }
        // A StringBuilder line: .append(" scope=").append(value)
        return b > 0 && code.charAt(b - 1) == '(' && proseAppendBefore(code, src, b - 1);
    }

    /**
     * Whether the {@code .append(} opened at {@code paren} follows an {@code .append} of a single
     * string literal with a space in it.
     */
    private static boolean proseAppendBefore(final String code, final String src,
            final int paren) {
        final String call = callBefore(code, paren);
        if (call == null || !call.equals(".append")) return false;
        int e = paren;
        while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) e--;
        e -= call.length();
        while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) e--;
        if (e < 1 || code.charAt(e - 1) != ')') return false;
        final int open = matchingOpen(code, e - 1);
        final String prev = open < 0 ? null : callBefore(code, open);
        if (prev == null || !prev.endsWith(".append")) return false;
        final String arg = code.substring(open + 1, e - 1).trim();
        return arg.length() >= 2 && arg.charAt(0) == '"' && arg.indexOf('"', 1) == arg.length() - 1
                && src.substring(open + 1, e - 1).trim().indexOf(' ') >= 0;
    }

    /**
     * Whether the builder that {@code .append}s the value at {@code at} writes prose anywhere in
     * its scope: an {@code .append} (or its constructor) of one literal, string or char, with a
     * space in it. The scope is the block that declares the builder, else the innermost block
     * around the append. A key or an id is built without one ({@code fileinfo_} and {@code '_'}).
     */
    private static boolean builderWritesProse(final String code, final String src,
            final int at) {
        final int paren = enclosingParen(code, statementStart(code, at) - 1, at);
        if (paren < 0) return false;
        final String root = chainRoot(code, paren);
        int from = -1;
        if (!root.equals("StringBuilder")) {
            final Matcher d = Pattern.compile("StringBuilder\\s+" + Pattern.quote(root)
                    + "\\s*[=;]").matcher(code);
            while (d.find() && d.start() < at) from = d.start();
        }
        if (from < 0) from = at;
        final int open = enclosingBrace(code, from);
        final int close = open < 0 ? code.length() : closeBrace(code, open);
        final Matcher a = BUILDER_LITERAL.matcher(code);
        a.region(Math.max(0, open), close < 0 ? code.length() : close);
        while (a.find()) {
            if (src.substring(a.start(1), a.end(1)).indexOf(' ') >= 0) return true;
        }
        return false;
    }

    /** An append or a builder constructor whose whole argument is one literal. */
    private static final Pattern BUILDER_LITERAL = Pattern.compile(
            "(?:\\.append|new\\s+StringBuilder)\\s*\\(\\s*[\"']([^\"'\\n]*)[\"']\\s*\\)");

    /** The variable a chain of calls ending in the call at {@code paren} starts from. */
    private static String chainRoot(final String code, final int paren) {
        int k = paren;
        while (true) {
            int e = k;
            while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) e--;
            int s = e;
            while (s > 0 && Character.isJavaIdentifierPart(code.charAt(s - 1))) s--;
            int d = s;
            while (d > 0 && Character.isWhitespace(code.charAt(d - 1))) d--;
            if (d == 0 || code.charAt(d - 1) != '.') return code.substring(s, e);
            d--;
            while (d > 0 && Character.isWhitespace(code.charAt(d - 1))) d--;
            if (d > 0 && code.charAt(d - 1) == ')') {
                k = matchingOpen(code, d - 1);
                if (k < 0) return "";
                continue;
            }
            return wordBefore(code, d);
        }
    }

    /** The innermost unclosed brace before {@code at}, or -1. */
    private static int enclosingBrace(final String code, final int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            final char c = code.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{' && depth-- == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Where the call opened by the parenthesis at {@code paren} starts: its name, else itself. */
    private static int callStart(final String code, final int paren) {
        int e = paren;
        while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) e--;
        int s = e;
        while (s > 0 && (Character.isJavaIdentifierPart(code.charAt(s - 1))
                || code.charAt(s - 1) == '.')) {
            s--;
        }
        return s == e ? paren : s;
    }

    /** Whether the parenthesis at {@code paren} groups, or calls a rendering method. */
    private static boolean passThrough(final String code, final int paren) {
        if (paren < 0) return false;
        final String call = callBefore(code, paren);
        return call == null || RENDERING_CALLS.contains(call);
    }

    private static int matchingOpen(final String code, final int close) {
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            final char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The offsets of MLS code: the whole file, or the bodies of its methods named *Mls*. */
    private static BitSet mlsScope(final String code, final boolean mlsFile) {
        final BitSet mls = new BitSet(code.length());
        if (mlsFile) {
            mls.set(0, code.length());
            return mls;
        }
        final Matcher m = MLS_METHOD.matcher(code);
        while (m.find()) {
            final String before = wordBefore(code, m.start(1));
            if (before.equals("new") || before.equals("return") || before.equals("throw")
                    || before.equals("else") || before.equals("case")) {
                continue;
            }
            final int params = closeParen(code, m.end() - 1);
            if (params < 0) continue;
            int k = params + 1;
            while (k < code.length() && code.charAt(k) != '{' && code.charAt(k) != ';'
                    && code.charAt(k) != '=' && code.charAt(k) != ')') {
                k++;
            }
            if (k >= code.length() || code.charAt(k) != '{') continue;
            final int body = closeBrace(code, k);
            if (body > k) mls.set(k, body);
        }
        return mls;
    }

    private static String wordBefore(final String code, final int at) {
        int e = at;
        while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) e--;
        int s = e;
        while (s > 0 && Character.isJavaIdentifierPart(code.charAt(s - 1))) s--;
        return code.substring(s, e);
    }

    private static int statementStart(final String code, final int at) {
        for (int i = at - 1; i >= 0; i--) {
            final char c = code.charAt(i);
            if (c == ';' || c == '{' || c == '}') return i + 1;
        }
        return 0;
    }

    private static String where(final String file, final String code, final int start,
            final int end) {
        final int line = 1 + (int) code.substring(0, start).chars().filter(c -> c == '\n').count();
        return file + ":" + line + ": `" + code.substring(start, end) + "` in ";
    }

    /** The name of the method called by the parenthesis at {@code paren}, or null. */
    private static String callBefore(final String code, final int paren) {
        int e = paren;
        while (e > 0 && Character.isWhitespace(code.charAt(e - 1))) e--;
        int s = e;
        while (s > 0 && (Character.isJavaIdentifierPart(code.charAt(s - 1))
                || code.charAt(s - 1) == '.')) {
            s--;
        }
        if (s == e) return null;
        final String name = code.substring(s, e);
        return name.equals("if") || name.equals("while") || name.equals("for")
                || name.equals("switch") || name.equals("return") ? null : name;
    }

    /** The innermost unclosed {@code (} between {@code open} (exclusive) and {@code at}, or -1. */
    private static int enclosingParen(final String code, final int open, final int at) {
        int depth = 0;
        for (int i = at - 1; i > open; i--) {
            final char c = code.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(' && depth-- == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The method whose parentheses most closely enclose {@code at} after {@code open}, or null when
     * there is none or that is a grouping parenthesis.
     */
    private static String enclosingCall(final String code, final int open, final int at) {
        final int paren = enclosingParen(code, open, at);
        return paren < 0 ? null : callBefore(code, paren);
    }

    private static int firstLogCall(final String code) {
        final Matcher m = LOG_CALL.matcher(code);
        return m.find() ? m.start() : -1;
    }

    /** As in {@link MessageContentLogGuardTest}: the innermost block around {@code at}. */
    private static boolean insideBuildGatedIf(final String code, final int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            final char c = code.charAt(i);
            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth-- > 0) continue;
                final int ifAt = lastIfBefore(code, i);
                if (ifAt < 0) return false;
                final String cond = code.substring(ifAt, i);
                if (cond.indexOf(';') >= 0 || cond.indexOf('{') >= 0 || cond.indexOf('}') >= 0) {
                    return false;
                }
                return cond.contains("debuggableBuild()") || cond.contains("isDebugBuild()");
            }
        }
        return false;
    }

    private static int lastIfBefore(final String code, final int at) {
        for (int i = code.lastIndexOf("if", at); i >= 0; i = code.lastIndexOf("if", i - 1)) {
            final boolean wordStart = i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1));
            int j = i + 2;
            while (j < code.length() && Character.isWhitespace(code.charAt(j))) j++;
            if (wordStart && j < code.length() && code.charAt(j) == '(') return i;
            if (i == 0) break;
        }
        return -1;
    }

    private static int closeParen(final String code, final int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int closeBrace(final String code, final int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<File> javaUnder(final String rel) throws IOException {
        File root = null;
        for (final String c : new String[] {rel, "packages/apps/Messaging/" + rel, "../" + rel}) {
            if (new File(c).isDirectory()) {
                root = new File(c);
                break;
            }
        }
        if (root == null) throw new IOException(rel + " not found");
        final List<File> out = new ArrayList<>();
        final Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            final File[] kids = stack.pop().listFiles();
            if (kids == null) continue;
            for (final File k : kids) {
                if (k.isDirectory()) stack.push(k);
                else if (k.getName().endsWith(".java")) out.add(k);
            }
        }
        return out;
    }
}
