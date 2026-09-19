/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

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
 * reports. Every log call in the app is scanned for a number-named identifier rendered into the
 * line: concatenated, a ternary arm, or a format argument. A line built outside the log call is
 * caught where such a name is concatenated next to a literal with a space in it: prose, not an id
 * or a map key. A number that reaches a line as the argument of another method is that method's
 * business.
 */
public class PhoneNumberLogGuardTest {

    private static final String[] ROOTS = {"src"};

    /** Identifiers that hold a number wherever they appear. */
    private static final Pattern NUMBER_NAME = Pattern.compile(
            "[a-z][\\w$]*(?:E164|E164s|Msisdn|MSISDN|Tel)|e164|msisdn|tel");

    /** File (simple name) → the other identifiers that hold a number in it. */
    private static final Map<String, Set<String>> ALSO = new HashMap<>();
    static {
        also("RcsCallbackRouter", "from", "fromUri");
        also("ReceiveRcsGroupEventAction", "requester");
        also("CarrierImsService", "toUri");
        also("CarrierMessageReceiver", "fromUri");
        also("CarrierMessageSender", "toUri");
        also("CarrierMsrpSessionManager", "fromUri", "toUri");
        also("CarrierRcsTransport", "fromUri", "toUri");
        also("ProviderTransport", "toUri");
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
    private static final Set<String> MASKS = new HashSet<>(Arrays.asList(
            "LogMask.number", "LogMask.numbers"));

    private static void also(final String file, final String... names) {
        ALSO.put(file, new HashSet<>(Arrays.asList(names)));
    }

    @Test
    public void noLogCallRendersAnUnmaskedNumber() throws IOException {
        final Scan scan = scanTree();
        assertTrue("only " + scan.seen[0] + " files scanned; the walk lost its subject",
                scan.seen[0] > 400);
        assertTrue("only " + scan.seen[1] + " log calls found; the call pattern lost its subject",
                scan.seen[1] > 1000);
        if (!scan.logLeaks.isEmpty()) {
            fail("a log call renders a phone number unmasked. Wrap it in LogMask.number(...), or "
                    + "put the line inside an if on RcsDebug.isDebugBuild(). Offending:\n  "
                    + String.join("\n  ", scan.logLeaks));
        }
        assertTrue("only " + scan.seen[2] + " masked numbers in log calls; the name pattern lost "
                + "its subject", scan.seen[2] > 30);
    }

    /**
     * A line built outside the log call (a describe method, a status reason) is prose: a number
     * concatenated next to a literal with a space in it. An id or a map key is built from literals
     * without one, so those stay raw.
     */
    @Test
    public void noLineBuilderConcatenatesAnUnmaskedNumber() throws IOException {
        final Scan scan = scanTree();
        if (!scan.builderLeaks.isEmpty()) {
            fail("a line builder concatenates a phone number unmasked. Mask it as in a log call. "
                    + "Offending:\n  "
                    + String.join("\n  ", scan.builderLeaks));
        }
        // Series 1 has no line builder that renders a number, so the walk is the subject; the
        // falsifier below shows the scan can fail.
        assertTrue("only " + scan.seen[0] + " files scanned; the walk lost its subject",
                scan.seen[0] > 400);
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
            assertTrue("must flag: " + line, !scanOne("CarrierRcsTransport", line,
                    Collections.singleton("peer")).logLeaks.isEmpty());
        }
        final String[] leakingBuilders = {
            "return \"removed by \" + requester + \" at \" + when;",
            "setStatus(id, \"sent to \" + peerE164);",
            "final String line = \"for \" + String.valueOf(toTel);",
        };
        for (final String line : leakingBuilders) {
            assertTrue("must flag: " + line, !scanOne("ReceiveRcsGroupEventAction", line,
                    Collections.singleton("requester")).builderLeaks.isEmpty());
        }
        final String[] clean = {
            "log.i(\"x \" + LogMask.number(peerE164) + \" y\");",
            "log.i(\"n=\" + peerE164.length() + \" \" + (peerE164 == null));",
            "if (RcsDebug.isDebugBuild()) { log.i(\"x \" + peerE164); }",
            "log.i(\"members \" + LogMask.numbers(memberE164s));",
            "log.i(\"for \" + peer);",
            "return \"removed by \" + LogMask.number(requester);",
            // An id is built from literals without a space.
            "final String id = \"tel:\" + peerE164;",
            "log.i(\"n=\" + map.get(peerE164) + \" of \" + n);",
        };
        for (final String line : clean) {
            final Scan r = scanOne("RcsMessageStore", line, Collections.emptySet());
            assertTrue("must pass: " + line, r.logLeaks.isEmpty() && r.builderLeaks.isEmpty());
        }
    }

    // ---- the scan ----

    /** What one or more files hold: leaks by kind, and the counters that keep the scan honest. */
    private static final class Scan {
        final List<String> logLeaks = new ArrayList<>();
        final List<String> builderLeaks = new ArrayList<>();
        /** Files, log calls, masked numbers, masked or prose-adjacent numbers outside log calls. */
        final int[] seen = new int[4];
    }

    private static Scan scanTree() throws IOException {
        final Scan scan = new Scan();
        for (final String root : ROOTS) {
            for (final File f : javaUnder(root)) {
                final String name = f.getName().substring(0, f.getName().length() - 5);
                if (DEBUG_RECEIVERS.contains(name)) continue;
                final String src =
                        new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                scan.seen[0]++;
                scan(scan, name, SourceScan.codeOnly(src), src,
                        ALSO.getOrDefault(name, Collections.emptySet()));
            }
        }
        return scan;
    }

    private static Scan scanOne(final String file, final String src, final Set<String> also) {
        final Scan scan = new Scan();
        scan(scan, file, SourceScan.codeOnly(src), src, also);
        return scan;
    }

    private static void scan(final Scan out, final String file, final String code,
            final String src, final Set<String> also) {
        final boolean[] inLogCall = new boolean[code.length()];
        final Matcher m = LOG_CALL.matcher(code);
        while (m.find()) {
            final int open = m.end() - 1;
            final int close = closeParen(code, open);
            if (close < 0) continue;
            out.seen[1]++;
            for (int i = m.start(); i <= close; i++) inLogCall[i] = true;
            if (insideBuildGatedIf(code, m.start())) continue;
            final Matcher id = IDENT.matcher(code);
            id.region(open + 1, close);
            while (id.find()) {
                final int start = rendered(code, id, also);
                if (start < 0) continue;
                final int verdict = masking(code, open, start);
                if (verdict == MASKED) {
                    out.seen[2]++;
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
            if (inLogCall[id.start()]) continue;
            final int start = rendered(code, id, also);
            if (start < 0) continue;
            final int statement = statementStart(code, start);
            if (masking(code, statement, start) == MASKED) {
                out.seen[3]++;
                continue;
            }
            // Handed to another method, the value is prose when it is concatenated inside that
            // method's argument ("group created with " + peer).
            if (!nextToProse(code, src, start, id.end())) continue;
            out.seen[3]++;
            if (insideBuildGatedIf(code, start)) continue;
            out.builderLeaks.add(where(file, code, start, id.end())
                    + code.substring(statement, Math.min(code.length(), id.end() + 40))
                            .replaceAll("\\s+", " ").trim());
        }
    }

    private static final int MASKED = 0;
    private static final int OTHER_CALL = 1;
    private static final int RENDERED = 2;

    /**
     * Where the number named at {@code id} starts (a dotted prefix included), or -1 when the name
     * holds no number here or the value is not rendered (a member access, a call, an assignment or
     * a comparison).
     */
    private static int rendered(final String code, final Matcher id, final Set<String> also) {
        final String name = id.group();
        if (id.start() > 0 && Character.isJavaIdentifierPart(code.charAt(id.start() - 1))) {
            return -1;
        }
        if (!NUMBER_NAME.matcher(name).matches() && !also.contains(name)) return -1;
        int end = id.end();
        while (end < code.length() && Character.isWhitespace(code.charAt(end))) end++;
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
            while (k > 0 && Character.isJavaIdentifierPart(code.charAt(k - 1))) k--;
            start = k;
        }
        int b = start;
        while (b > 0 && Character.isWhitespace(code.charAt(b - 1))) b--;
        if (b > 1 && (code.startsWith("==", b - 2) || code.startsWith("!=", b - 2))) return -1;
        // A declaration names the value; it does not render it.
        if (b > 0 && (Character.isJavaIdentifierPart(code.charAt(b - 1))
                || code.charAt(b - 1) == '>' || code.charAt(b - 1) == ']')) {
            return -1;
        }
        return start;
    }

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
        return false;
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
