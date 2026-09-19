/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log statements in the classes that handle message content log ids, sizes, content types and
 * status, never the content: logcat is readable over adb and lands in bug reports. A dump of
 * content is allowed only inside an if that requires a debuggable build.
 */
public class MessageContentLogGuardTest {

    /** File → the identifiers that hold message content in it. */
    private static final Object[][] SUBJECTS = {
        {"src/com/android/messaging/rcs/RcsCallbackRouter.java",
                Arrays.asList("subject", "plain", "second", "emoji", "body")},
        {"engine/src/com/android/messaging/rcs/engine/mls/MlsGroupMetadata.java",
                Arrays.asList("subjectText", "plain", "keyMaterial", "contentKey", "fileName")},
        {"engine/src/com/android/messaging/rcs/engine/mls/MlsCommitApplication.java",
                Arrays.asList("payload", "plain", "body")},
        {"engine/src/com/android/messaging/rcs/engine/mls/MlsInboundDecrypt.java",
                Arrays.asList("plain", "hx", "headers", "body")},
        {"src/com/android/messaging/rcs/carrier/sip/CarrierMessageSender.java",
                Arrays.asList("req", "text", "cpim", "content")},
        {"src/com/android/messaging/datamodel/action/InsertRbmPostbackEchoAction.java",
                Arrays.asList("text")},
        {"src/com/android/messaging/datamodel/action/ReceiveRcsMessageAction.java",
                Arrays.asList("emoji", "reactorUri", "quotedText", "text", "body")},
        {"src/com/android/messaging/rcs/OsmStaticMap.java",
                Arrays.asList("x", "y", "z", "lat", "lon", "latitude", "longitude", "url", "t")},
        // Not message content, but no safer in logcat: the provisioning document can carry
        // credentials, and a raw SIP request carries the parties' addresses.
        {"src/com/android/messaging/rcs/sip/ShannonRcsConfigTrigger.java",
                Arrays.asList("configXml")},
        {"src/com/android/messaging/rcs/carrier/msrp/session/CarrierMsrpSessionManager.java",
                Arrays.asList("byeReq", "encode")},
        // The SIP wire and SDP (identities, access-network info), the IMS config (IMPI, IMPU)
        // and the provider's session token.
        {"src/com/android/messaging/rcs/sip/CpmSessionEngine.java",
                Arrays.asList("rendered", "sdpBytes", "body", "dumpNetworkHeaders")},
        {"src/com/android/messaging/rcs/carrier/CarrierStackDrModeDriver.java",
                Arrays.asList("acsCfg")},
        {"src/com/android/messaging/rcs/ProviderTransport.java", Arrays.asList("token")},
        {"src/com/android/messaging/rcs/BoundProviderTransport.java", Arrays.asList("token")},
        // The debug receivers: a debug build is not a reason to put a body or a subject in
        // logcat. RcsDebugFtAcceptReceiver and MlsEnrollDebugReceiver hold no content.
        {"src/com/android/messaging/rcs/RcsDebugSendReceiver.java",
                Arrays.asList("body", "aBody", "bBody", "gBody", "rcBody", "outBody", "subjTxt",
                        "iconTxt", "nName")},
        {"src/com/android/messaging/rcs/RcsDebugGroupReceiver.java",
                Arrays.asList("body", "name")},
        {"src/com/android/messaging/rcs/RcsDebugComposeSendReceiver.java",
                Arrays.asList("body")},
        {"src/com/android/messaging/rcs/MlsSendReceiver.java", Arrays.asList("body")},
        {"src/com/android/messaging/rcs/RcsDebugCarrierDriveReceiver.java",
                Arrays.asList("body")},
        {"src/com/android/messaging/rcs/RcsDebugFtSendReceiver.java",
                Arrays.asList("caption")},
        {"src/com/android/messaging/rcs/sip/SipDelegateDebugReceiver.java",
                Arrays.asList("body")},
    };

    private static final Pattern LOG_CALL =
            Pattern.compile("\\b(?:LogUtil|Log|log)\\.[vdiwe]\\s*\\(");
    private static final Pattern IDENT =
            Pattern.compile("(?<![\\w$.])[A-Za-z_$][\\w$]*|(?<=\\.)[A-Za-z_$][\\w$]*");
    private static final Pattern HOLDER = Pattern.compile("^\\s*\\.\\s*[A-Za-z_$]");
    private static final Pattern TO_STRING = Pattern.compile("^\\s*\\.\\s*toString\\s*\\(");
    private static final Pattern ALLOWED_USE = Pattern.compile(
            "^\\s*(\\.length\\s*\\(\\s*\\)|\\.length|\\.size\\s*\\(\\s*\\)|\\.isEmpty\\s*\\(\\s*\\)"
                    + "|\\.getClass\\s*\\(\\s*\\)|==|!=)");

    @Test
    public void noLoggedMessageContent() throws IOException {
        final List<String> leaks = new ArrayList<>();
        for (final Object[] s : SUBJECTS) {
            @SuppressWarnings("unchecked")
            final List<String> names = (List<String>) s[1];
            leaks.addAll(scan((String) s[0], SourceScan.codeOnly(SourceScan.read((String) s[0])),
                    names));
        }
        if (!leaks.isEmpty()) {
            fail("message content reaches logcat. Log ids, sizes, content types and "
                    + "status instead; a debugging dump goes inside an if on "
                    + "shell.sysprops().debuggableBuild() / RcsDebug.isDebugBuild(). Offending:\n  "
                    + String.join("\n  ", leaks));
        }
    }

    /** The debug plaintext dump needs a debuggable build, which adb cannot fake with a prop. */
    @Test
    public void theMlsPlaintextDumpRequiresADebuggableBuild() throws IOException {
        final String src = SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsInboundDecrypt.java");
        final String code = SourceScan.codeOnly(src);
        final int dump = src.indexOf("log.i(\"MLS-PLAINTEXT ");
        assertTrue("no hits: the MLS-PLAINTEXT dump was not found — if it was removed, "
                + "remove this assertion with it", dump >= 0);
        assertTrue("the MLS-PLAINTEXT dump must sit inside an if that requires debuggableBuild()",
                insideBuildGatedIf(code, dump));

        final String props = SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsSysProps.java");
        assertTrue("MlsSysProps.debuggableBuild() must read ro.debuggable (not a debug.* prop)",
                props.contains("return getInt(\"ro.debuggable\", 0) == 1;"));
    }

    /** The Rust receive diagnostic logs the plaintext length only; its own test is not run here. */
    @Test
    public void theRustDecryptPathDoesNotLogPlaintext() throws IOException {
        final String ffi = SourceScan.read("rust/rcs_mls_ffi/src/ffi.rs");
        final int testMod = ffi.indexOf("mod no_plaintext_log_tests");
        final String prod = testMod < 0 ? ffi : ffi.substring(0, testMod);
        assertTrue("no hits: the recv-diag line was not found in ffi.rs",
                prod.contains("\"recv-diag: sender_leaf="));
        assertTrue("ffi.rs logs decrypted plaintext as hex",
                !prod.contains("plaintext_hex") && !prod.contains("pt.iter()"));
    }

    /** The SIP wire line logs {@code wireSummary(req)}: method, Call-ID and body SIZE only. */
    @Test
    public void theSipWireLogIsASummary() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/rcs/carrier/sip/CarrierMessageSender.java"));
        final String body = SourceScan.bodyOf(code, "wireSummary");
        assertTrue("no hits: CarrierMessageSender.wireSummary not found",
                !body.isEmpty());
        assertTrue("wireSummary must report the body's SIZE and never the request text: " + body,
                body.contains("content.length") && !body.contains("toString(")
                        && !body.contains("new String("));
    }

    /** The scan must be able to fail: content-logging statements through the same predicate. */
    @Test
    public void theScanCatchesTheLinesItReplaced() {
        final String[] old = {
            "LogUtil.i(TAG, \"x\" + groupId + \" (\" + plain.length + \"B) = \\\"\" + subject + \"\\\"\");",
            "log.i(\"x\" + key + \" key=\" + MlsHex.hexPrefix(contentKey));",
            "Log.i(TAG, \"MESSAGE wire >>>\\n\" + req.toString() + \"\\n<<< MESSAGE wire\");",
            "LogUtil.w(TAG, \"OSM tile \" + z + \"/\" + x + \"/\" + y + \" HTTP \" + code);",
            "LogUtil.i(TAG, \"tapback \" + tapback.emoji + \" -> \" + targetRcsId);",
            "LogUtil.i(TAG, \"DEBUG SEND_TEST_RCS to=\" + to + \" body=\" + body);",
            "LogUtil.i(TAG, \"DEBUG MLS CHANGESUBJ '\" + subjTxt + \"' \" + ok);",
            "LogUtil.i(TAG, \"DEBUG CREATE_GROUP result name=\" + gi.name);",
        };
        final List<String> names = Arrays.asList(
                "subject", "contentKey", "req", "x", "y", "z", "emoji", "body", "subjTxt", "name");
        for (final String line : old) {
            assertTrue("the scan must flag: " + line,
                    !scan("<synthetic>", SourceScan.codeOnly(line), names).isEmpty());
        }
        assertTrue("sizes are allowed",
                scan("<synthetic>", SourceScan.codeOnly(
                        "LogUtil.i(TAG, \"x\" + plain.length + \" \" + text.length());"),
                        Arrays.asList("plain", "text")).isEmpty());
    }

    // ---- the scan ----

    private static List<String> scan(final String file, final String code,
            final List<String> names) {
        final List<String> leaks = new ArrayList<>();
        int statements = 0;
        final Matcher m = LOG_CALL.matcher(code);
        while (m.find()) {
            final int end = closeParen(code, m.end() - 1);
            final String stmt = code.substring(m.start(), end + 1);
            statements++;
            if (insideBuildGatedIf(code, m.start())) continue;
            // wireSummary(req) summarises a request; its body is pinned by theSipWireLogIsASummary.
            final String view = stmt.replaceAll("\\bwireSummary\\s*\\(\\s*req\\s*\\)",
                    "wireSummary()");
            final Matcher id = IDENT.matcher(view);
            while (id.find()) {
                if (!names.contains(id.group())) continue;
                final String after = view.substring(id.end());
                if (ALLOWED_USE.matcher(after).find()) continue;
                // A holder (tapback in tapback.emoji) is checked through its member; only
                // toString() renders the holder itself.
                if (HOLDER.matcher(after).find() && !TO_STRING.matcher(after).find()) continue;
                final int line = 1 + (int) code.substring(0, m.start()).chars()
                        .filter(c -> c == '\n').count();
                leaks.add(file + ":" + line + ": `" + id.group() + "` in "
                        + stmt.replaceAll("\\s+", " "));
            }
        }
        assertTrue("no hits: no log statement found in " + file
                + " — if its logging moved, move this guard with it", statements > 0);
        return leaks;
    }

    /** True when the innermost block around {@code at} is an if requiring a debuggable build. */
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

    /** Offset of the last {@code if (} keyword before {@code at}, or -1. */
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
        throw new AssertionError("unterminated log call at offset " + open);
    }
}
