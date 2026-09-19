/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Every content-bearing outbound send is classified. The encryption flag latches per
 * conversation, so a new send verb inherits the padlock without inheriting a gate. This guard
 * fails on an unclassified {@code send*} verb of the seam or the AIDL, on a content-verb call site
 * with no row in {@link #ROWS}, and on a plaintext verb in {@code MlsProviderTransport}. Gated
 * rows must consult their own verb's gate before the send. Same-named methods get rows rather than
 * a filter, because a filter fails open. See docs/testing.md.
 */
public class MlsUngatedOutboundSendGuardTest {

    private static final String TRANSPORT = "src/com/android/messaging/rcs/ProviderTransport.java";
    private static final String AIDL =
            "aidl/src/aidl/org/lineageos/rcs/provider/IRcsProvider.aidl";
    private static final String MLS_TRANSPORT =
            "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java";

    private static final String INMA =
            "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java";
    private static final String LOCATION_ACTION =
            "src/com/android/messaging/datamodel/action/SendRcsLocationAction.java";
    private static final String CONVERSATION_FRAGMENT =
            "src/com/android/messaging/ui/conversation/ConversationFragment.java";

    /** The gate a group-addressed content send must reach. */
    private static final String GROUP_GATE = "groupSendVerdict(";
    /** The gate a peer-addressed content send must reach. */
    private static final String ONE_TO_ONE_GATE = "oneToOneSendVerdict(";

    /** What a {@code send*} verb on the seam carries. */
    private enum VerbClass {
        /** Carries user content. Every call site needs a verdict. */
        CONTENT,
        /** A receipt, a typing notification or a negative IMDN. Carries no user content. */
        CONTROL,
        /**
         * Already ciphertext when it reaches the seam. Says nothing about whether the provider
         * reports send status for the verb, which this guard does not check.
         */
        SEALED,
    }

    /** Checked against the sources in both directions. */
    private static final Map<String, VerbClass> VERB_CLASS = new LinkedHashMap<>();
    static {
        VERB_CLASS.put("sendMessage", VerbClass.CONTENT);         // 1:1 composed text
        VERB_CLASS.put("sendGroupMessage", VerbClass.CONTENT);    // group composed text
        VERB_CLASS.put("sendFile", VerbClass.CONTENT);            // media, both legs
        VERB_CLASS.put("sendLocation", VerbClass.CONTENT);        // location share
        VERB_CLASS.put("sendReaction", VerbClass.CONTENT);        // emoji reaction
        VERB_CLASS.put("sendBotPostback", VerbClass.CONTENT);     // RBM suggestion chip  (see ROWS)

        VERB_CLASS.put("sendMlsCiphertext", VerbClass.SEALED);
        VERB_CLASS.put("sendGroupMlsCiphertext", VerbClass.SEALED);

        VERB_CLASS.put("sendImdn", VerbClass.CONTROL);
        VERB_CLASS.put("sendMlsNegativeDeliveryImdn", VerbClass.CONTROL);
        VERB_CLASS.put("sendReconciliationReceipt", VerbClass.CONTROL);
        VERB_CLASS.put("sendTyping", VerbClass.CONTROL);
        VERB_CLASS.put("sendGroupTyping", VerbClass.CONTROL);
    }

    /** AIDL {@code send*} verbs {@code ProviderTransport} does not wrap, each with its reason. */
    private static final Map<String, String> AIDL_ONLY = new LinkedHashMap<>();
    static {
        AIDL_ONLY.put("sendMlsImdn",
                "no app-side wrapper: reached only from ProviderTransport.sendImdn's in-seam MLS "
                + "branch, which is pinned by theImdnVerbIsGatedInsideTheSeam...");
        AIDL_ONLY.put("sendMlsGroupImdn",
                "no app-side wrapper: the group leg of the same in-seam branch, same pin.");
    }

    private enum Kind {
        /** A real content send that must reach a verdict. {@code gates} names which. */
        GATED,
        /** A real content send that may go unsealed. {@code reason} must say why. */
        EXEMPT,
        /** The seam's own body: the verb itself, not a call site of it. */
        SEAM_IMPL,
        /** A declaration the textual scan cannot tell from a call (an interface method). */
        DECLARATION,
        /** A same-named method on something that is not the provider seam. */
        NOT_THE_SEAM,
    }

    private static final class Row {
        final String file;
        /** A method name, or {@code "*"} for every invocation in the file. */
        final String method;
        final String verb;
        final Kind kind;
        /** Expected invocation count, or {@code -1} for "any". */
        final int expected;
        /** For {@link Kind#GATED}: every token that must precede the send. */
        final String[] gates;
        final String reason;

        Row(final String file, final String method, final String verb, final Kind kind,
                final int expected, final String[] gates, final String reason) {
            this.file = file;
            this.method = method;
            this.verb = verb;
            this.kind = kind;
            this.expected = expected;
            this.gates = gates;
            this.reason = reason;
        }

        String key() {
            return file + "#" + method + "#" + verb;
        }
    }

    private static final List<Row> ROWS = new ArrayList<>();

    private static void gated(final String file, final String method, final String verb,
            final int expected, final String... gates) {
        ROWS.add(new Row(file, method, verb, Kind.GATED, expected, gates, ""));
    }

    private static void exempt(final String file, final String method, final String verb,
            final String reason) {
        ROWS.add(new Row(file, method, verb, Kind.EXEMPT, -1, null, reason));
    }

    private static void seam(final String file, final String verb, final String reason) {
        ROWS.add(new Row(file, "*", verb, Kind.SEAM_IMPL, -1, null, reason));
    }

    private static void declaration(final String file, final String verb, final String reason) {
        ROWS.add(new Row(file, "<class level>", verb, Kind.DECLARATION, -1, null, reason));
    }

    private static void notTheSeam(final String file, final String method, final String verb,
            final String reason) {
        ROWS.add(new Row(file, method, verb, Kind.NOT_THE_SEAM, -1, null, reason));
    }

    static {
        // GATED: the real content sends, each with the tokens its own leg needs.

        // 1:1 text: the scheme is resolved rather than assumed, and a failed app-owned seal does
        // not fall through to the plaintext send below it.
        gated(INMA, "tryInsertSendingRcsMessage", "sendMessage", 1,
                "E2eeSendGate.get().resolveForSend(", "appOwnedFailed");

        // Group text: exactly one group send; a second would be a plaintext fallback.
        gated(INMA, "tryInsertSendingRcsGroupMessage", "sendGroupMessage", 1, GROUP_GATE);

        // Media: an unsealed attachment would sit at a URL on a content server, so only a
        // PLAINTEXT verdict may proceed.
        gated(INMA, "tryInsertSendingRcsFile", "sendFile", 1, ONE_TO_ONE_GATE);
        gated(INMA, "tryInsertSendingRcsGroupFile", "sendFile", 1, GROUP_GATE);

        // Location and reactions handle both legs in one method, so both gates are required.
        gated(LOCATION_ACTION, "executeAction", "sendLocation", 1, GROUP_GATE, ONE_TO_ONE_GATE);
        gated(CONVERSATION_FRAGMENT, "onReactionSelected", "sendReaction", 1,
                GROUP_GATE, ONE_TO_ONE_GATE);

        // EXEMPT: real sends that may go unsealed.

        // TODO: confirm E2eeSendGate.resolveForSend cannot return MLS for a bot address; if it
        // can, typed text in a bot thread is gated and this postback is not.
        exempt(CONVERSATION_FRAGMENT, "onBotSuggestionTapped", "sendBotPostback",
                "RBM suggestion-chip postback to an agent address. A bot thread's inbound lands in "
                + "ReceiveRcsBotMessageAction, which writes no e2ee scheme and so cannot latch the "
                + "padlock. Not traced: whether E2eeSendGate.resolveForSend can return MLS for a "
                + "bot address, which is the one route by which a bot thread could latch.");

        // Debug broadcast receivers, not reachable from the UI.
        exempt("src/com/android/messaging/rcs/RcsDebugSendReceiver.java", "onReceive",
                "sendMessage",
                "debug broadcast: developer-invoked probe, not reachable from the UI. It can be "
                + "asked to run the real gate with --ez gate / --ez e2ee.");
        exempt("src/com/android/messaging/rcs/RcsDebugGroupReceiver.java", "onReceive",
                "sendGroupMessage",
                "debug broadcast: developer-invoked group-send probe, not reachable from the UI.");
        exempt("src/com/android/messaging/rcs/RcsDebugFtSendReceiver.java", "onReceive", "sendFile",
                "debug broadcast: developer-invoked file-transfer probe, not reachable from the UI.");
        exempt("src/com/android/messaging/rcs/RcsDebugCarrierDriveReceiver.java", "driveCarrier",
                "sendMessage",
                "debug broadcast: drives the carrier IMS stack directly for bring-up, not reachable "
                + "from the UI.");

        // SEAM_IMPL: the verb's own body.

        seam(TRANSPORT, "sendMessage", "the seam's own AIDL forward.");
        seam(TRANSPORT, "sendGroupMessage", "the seam's own AIDL forward.");
        seam(TRANSPORT, "sendLocation", "the seam's own AIDL forward.");
        seam(TRANSPORT, "sendFile", "the seam's own AIDL forward, plus the arity-delegating "
                + "overload that calls the 9-arg form.");
        seam(TRANSPORT, "sendReaction", "the seam's own AIDL forward.");
        seam(TRANSPORT, "sendBotPostback", "the seam's own AIDL forward.");
        seam("src/com/android/messaging/rcs/BoundProviderTransport.java", "sendMessage",
                "the per-component seam's own AIDL forward — the same verb, a second implementation "
                + "of RcsTransport.");

        // DECLARATION: an interface method, not a call.

        declaration("src/com/android/messaging/rcs/RcsTransport.java", "sendMessage",
                "the seam INTERFACE's own declaration. It carries no visibility modifier, so the "
                + "declaration filter cannot see it is one.");
        declaration("src/com/android/messaging/rcs/carrier/Transport.java", "sendMessage",
                "the carrier SIP stack's own unrelated Transport interface.");
        declaration("src/com/android/messaging/ui/conversation/ComposeMessageView.java",
                "sendMessage",
                "the nested ComposeMessageView.Host callback interface — a UI contract with the "
                + "fragment, not a transport.");

        // NOT_THE_SEAM: a same-named method elsewhere. A file-wide row holds only while its file
        // names no seam type; files that hold a transport are kept at method granularity.

        notTheSeam("src/com/android/messaging/rcs/sip/SipDelegateClient.java", "*", "sendMessage",
                "SipDelegateClient.conn.sendMessage(SipMessage, version) — a SIP transaction on the "
                + "ImsService delegate, nothing to do with the provider seam.");
        notTheSeam("src/com/android/messaging/rcs/sip/CpmSessionEngine.java", "*", "sendMessage",
                "the same SIP connection method, driving INVITE / ACK / BYE.");
        notTheSeam("src/com/android/messaging/rcs/sip/CpmIncomingSessionEngine.java", "*",
                "sendMessage", "the same SIP connection method, driving responses.");
        notTheSeam("src/com/android/messaging/sms/MmsUtils.java", "*", "sendMessage",
                "SmsSender.sendMessage — the SMS/MMS path, a different transport entirely.");
        notTheSeam("src/com/android/messaging/rcs/carrier/CarrierStackDrModeDriver.java",
                "sendText", "sendMessage",
                "CarrierRcsTransport.sendMessage(toUri, body, messageId) — the in-app carrier SIP "
                + "stack. NOT an independent call site: it is reached from "
                + "InsertNewMessageAction#tryInsertSendingRcsMessage via CarrierImsTransport -> "
                + "CarrierImsSeam -> CarrierImsService.driveSend, so the gate on that row covers it.");
        notTheSeam("src/com/android/messaging/ui/conversation/ComposeMessageView.java",
                "sendMessageInternal", "sendMessage",
                "mHost.sendMessage(MessageData) — the UI callback into the fragment, which is where "
                + "the composed message enters the DataModel. Not a wire send.");
        notTheSeam(CONVERSATION_FRAGMENT, "sendMessage", "sendMessage",
                "ConversationFragment's own implementation of that UI callback, which starts an "
                + "InsertNewMessageAction. Kept at METHOD granularity deliberately: this file does "
                + "hold a real ProviderTransport, so a file-wide pass here would hide a new leak.");
    }

    /** A {@code send*(} token in the AIDL source. */
    private static final Pattern AIDL_SEND = Pattern.compile("\\b(send[A-Za-z0-9_]*)\\s*\\(");

    /** One located invocation. */
    private static final class Hit {
        final String file;
        final String method;
        final String verb;
        final int at;
        final int line;

        Hit(final String file, final String method, final String verb, final int at,
                final int line) {
            this.file = file;
            this.method = method;
            this.verb = verb;
            this.at = at;
            this.line = line;
        }

        String key() {
            return file + "#" + method + "#" + verb;
        }

        String fileWideKey() {
            return file + "#*#" + verb;
        }

        @Override
        public String toString() {
            return file + ":" + line + "  " + method + "() -> " + verb;
        }
    }

    /**
     * Whether the match at {@code at} is a whole identifier: {@link SourceScan#invocationsOf} is a
     * substring search, and {@code sendMessage(} is inside {@code resendMessage(}.
     */
    private static boolean wholeIdentifierAt(final String code, final int at) {
        if (at == 0) {
            return true;
        }
        final char before = code.charAt(at - 1);
        return !(Character.isLetterOrDigit(before) || before == '_' || before == '$');
    }

    /** {@link SourceScan#invocationsOf}, narrowed to whole-identifier matches. */
    private static List<Integer> callsOf(final String code, final List<int[]> decls,
            final String verb) {
        final List<Integer> out = new ArrayList<>();
        for (final Integer at : SourceScan.invocationsOf(code, decls, verb + "(")) {
            if (wholeIdentifierAt(code, at.intValue())) {
                out.add(at);
            }
        }
        return out;
    }

    /** Module-relative path of a file the walker returned, e.g. {@code src/com/.../Foo.java}. */
    private static String relativise(final File f) {
        final String p = f.getPath().replace(File.separatorChar, '/');
        final int at = p.indexOf("src/");
        return at >= 0 ? p.substring(at) : p;
    }

    private static String codeOf(final File f) throws IOException {
        return SourceScan.codeOnly(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** Every textual invocation of a {@link VerbClass#CONTENT} verb under {@code src/}. */
    private static List<Hit> contentSendHits() throws IOException {
        final List<Hit> out = new ArrayList<>();
        for (final File f : SourceScan.javaSourcesUnder("src")) {
            final String rel = relativise(f);
            final String code = codeOf(f);
            final List<int[]> decls = SourceScan.declarations(code);
            for (final Map.Entry<String, VerbClass> e : VERB_CLASS.entrySet()) {
                if (e.getValue() != VerbClass.CONTENT) {
                    continue;
                }
                final String verb = e.getKey();
                for (final Integer at : callsOf(code, decls, verb)) {
                    final int i = at.intValue();
                    out.add(new Hit(rel, SourceScan.enclosingMethod(code, decls, i), verb, i,
                            lineOf(code, i)));
                }
            }
        }
        // A floor, not "> 0": a walk that lost most of its hits must fail. The per-row check in
        // everyRowStillMatchesSomethingReal is the stronger protection.
        assertTrue("the scan found only " + out.size() + " invocations of a CONTENT send verb "
                + "(sendMessage / sendGroupMessage / sendFile / sendLocation / sendReaction / "
                + "sendBotPostback) walking packages/apps/Messaging/src/**.java; there were 35 "
                + "when this guard landed. A walk that lost most of its hits reports a clean tree "
                + "exactly as a clean tree does.", out.size() >= 30);
        return out;
    }

    private static int lineOf(final String src, final int at) {
        int n = 1;
        for (int i = 0; i < at && i < src.length(); i++) {
            if (src.charAt(i) == '\n') n++;
        }
        return n;
    }

    /** The brace-matched body containing {@code at}, or the whole file at class level. */
    private static String enclosingBody(final String code, final List<int[]> decls, final int at) {
        int[] chosen = null;
        for (final int[] d : decls) {
            if (d[0] > at) {
                break;
            }
            chosen = d;
        }
        if (chosen == null) {
            return code;
        }
        final int open = chosen[1];
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return code.substring(open, i + 1);
            }
        }
        return code.substring(open);
    }

    private static Row rowFor(final Hit h) {
        for (final Row r : ROWS) {
            if (r.verb.equals(h.verb) && r.file.equals(h.file)
                    && ("*".equals(r.method) || r.method.equals(h.method))) {
                return r;
            }
        }
        return null;
    }

    /** A new verb fails here before any call site exists; a removed one leaves a stale row. */
    @Test
    public void everySeamSendVerbIsClassified() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        final Set<String> declared = new TreeSet<>();
        for (final int[] d : SourceScan.declarations(code)) {
            final String name = code.substring(d[2], d[3]);
            final String header = code.substring(d[0], d[1]);
            if (name.startsWith("send") && header.contains("public")) {
                declared.add(name);
            }
        }
        assertTrue("no public send* methods found on ProviderTransport — the declaration pattern "
                + "has gone stale and this guard is reading nothing", declared.size() >= 10);

        final Set<String> unclassified = new TreeSet<>(declared);
        unclassified.removeAll(VERB_CLASS.keySet());
        assertEquals("a send verb exists on the seam that nobody has classified. THIS IS THE FIFTH "
                + "VERB ARRIVING. Decide whether it carries user content: if it does, it needs a "
                + "verdict at every call site (see ROWS) and a CONTENT entry here; if it is a "
                + "receipt or a typing notification it is CONTROL; if it takes ciphertext it is "
                + "SEALED.", new TreeSet<String>(), unclassified);

        final Set<String> stale = new TreeSet<>(VERB_CLASS.keySet());
        stale.removeAll(declared);
        assertEquals("VERB_CLASS classifies a verb ProviderTransport no longer declares — a stale "
                + "row that makes this table read as covering more than it does",
                new TreeSet<String>(), stale);

        // The AIDL, where a verb can land before it has an app-side wrapper.
        final String aidl = SourceScan.codeOnly(SourceScan.read(AIDL));
        final Set<String> aidlVerbs = new TreeSet<>();
        final Matcher m = AIDL_SEND.matcher(aidl);
        while (m.find()) {
            aidlVerbs.add(m.group(1));
        }
        assertTrue("no send* methods found in IRcsProvider.aidl — the scan is reading the wrong "
                + "file", aidlVerbs.size() >= 10);

        final Set<String> unaccounted = new TreeSet<>(aidlVerbs);
        unaccounted.removeAll(VERB_CLASS.keySet());
        unaccounted.removeAll(AIDL_ONLY.keySet());
        assertEquals("IRcsProvider declares a send verb that ProviderTransport does not wrap and "
                + "AIDL_ONLY does not excuse. Either wrap and classify it, or add a row saying why "
                + "the app can never call it.", new TreeSet<String>(), unaccounted);

        for (final Map.Entry<String, String> e : AIDL_ONLY.entrySet()) {
            assertTrue("AIDL_ONLY names " + e.getKey() + ", which IRcsProvider does not declare",
                    aidlVerbs.contains(e.getKey()));
            assertTrue("an AIDL_ONLY row must carry a reason, not a placeholder: " + e.getKey(),
                    e.getValue().length() >= 40);
        }
    }

    @Test
    public void everyContentSendCallSiteIsAccountedFor() throws IOException {
        final List<String> unaccounted = new ArrayList<>();
        for (final Hit h : contentSendHits()) {
            if (rowFor(h) == null) {
                unaccounted.add(h.toString());
            }
        }
        assertEquals("an outbound content send has appeared at a call site nobody has classified.\n"
                + "  If it can be reached on a conversation the app draws a padlock on, it needs a\n"
                + "  verdict — MlsProviderTransport.groupSendVerdict for a group, oneToOneSendVerdict\n"
                + "  for a peer — taken BEFORE the send, with PLAINTEXT the only value permitted to\n"
                + "  proceed. If it genuinely cannot, add an exempt(...) row saying why in a sentence.\n"
                + "  Do NOT add encryption_protocol != 0 as the test: that is the\n"
                + "  padlock's own predicate and it folds Etouffee in.\n"
                + "  Unaccounted:\n    " + String.join("\n    ", unaccounted),
                new ArrayList<String>(), unaccounted);
    }

    @Test
    public void everyRowStillMatchesSomethingReal() throws IOException {
        final Set<String> seen = new LinkedHashSet<>();
        for (final Hit h : contentSendHits()) {
            seen.add(h.key());
            seen.add(h.fileWideKey());
        }
        final List<String> dead = new ArrayList<>();
        for (final Row r : ROWS) {
            if (!seen.contains(r.key())) {
                dead.add(r.key());
            }
        }
        assertEquals("a row in ROWS matches no invocation in the tree. The call site was renamed, "
                + "moved or deleted; delete the row or repoint it. A table that no longer describes "
                + "the tree is worse than no table, because it reads as coverage.",
                new ArrayList<String>(), dead);
    }

    /** Ordering, not presence: a verdict read after the send is a log line. */
    @Test
    public void everyGatedCallSiteAsksItsOwnGateFirst() throws IOException {
        int checked = 0;
        for (final Row r : ROWS) {
            if (r.kind != Kind.GATED) {
                continue;
            }
            final String code = SourceScan.codeOnly(SourceScan.read(r.file));
            final List<int[]> decls = SourceScan.declarations(code);
            final List<Integer> hits = new ArrayList<>();
            for (final Integer at : callsOf(code, decls, r.verb)) {
                if (r.method.equals(SourceScan.enclosingMethod(code, decls, at.intValue()))) {
                    hits.add(at);
                }
            }
            assertTrue("GATED row " + r.key() + " matches no invocation — this check would "
                    + "otherwise pass on nothing", hits.size() > 0);
            if (r.expected >= 0) {
                assertEquals("GATED row " + r.key() + " expects exactly " + r.expected
                        + " invocation(s) of " + r.verb + " in that method. A SECOND one is how a "
                        + "refusal turns back into a fallback: the seal fails, the generation is "
                        + "spent, and the same text goes out in the clear.",
                        r.expected, hits.size());
            }

            final int send = hits.get(0).intValue();
            final String body = enclosingBody(code, decls, send);
            final int bodyAt = code.indexOf(body);
            for (final String gate : r.gates) {
                final int g = body.indexOf(gate);
                assertTrue(r.key() + " must consult " + gate
                        + " — without it this verb goes out in "
                        + "the clear on a conversation the app is drawing a padlock on.",
                        g >= 0);
                assertTrue(r.key() + ": " + gate + " must be reached BEFORE the send (gate at "
                        + (bodyAt + g) + ", send at " + send + "). A verdict taken after the "
                        + "message has gone is a log line, not a gate.", bodyAt + g < send);
                checked++;
            }
        }
        assertEquals("the GATED rows must contribute exactly the gate checks this table declares — "
                + "if this number moves, a gate was added or dropped from a row and the change "
                + "should be deliberate", 9, checked);
    }

    /** Every non-GATED row carries a reason; the length is a floor on effort. */
    @Test
    public void everyExemptionCarriesAWrittenReason() {
        int reasons = 0;
        for (final Row r : ROWS) {
            if (r.kind == Kind.GATED) {
                assertEquals("a GATED row carries gates, not a reason: " + r.key(), "", r.reason);
                assertTrue("a GATED row must name at least one gate: " + r.key(),
                        r.gates != null && r.gates.length > 0);
                continue;
            }
            // Judgements (EXEMPT, NOT_THE_SEAM) need a sentence; statements of fact may be shorter.
            final int floor = (r.kind == Kind.EXEMPT || r.kind == Kind.NOT_THE_SEAM) ? 40 : 20;
            assertTrue("row " + r.key() + " is " + r.kind + " and must say WHY in a sentence. The "
                    + "exemption list is the place the decision gets written down; without that it "
                    + "is just a suppression.",
                    r.reason != null && r.reason.length() >= floor);
            reasons++;
        }
        assertEquals("the number of exempted rows is itself the interesting number in this file — "
                + "if it moves, someone decided a send may go out unsealed and this assertion is "
                + "where they had to say so", 22, reasons);
    }

    /** A file-wide row would swallow a real send once its file acquires a seam type. */
    @Test
    public void aFileWideExemptionOnlyHoldsWhileTheFileNamesNoSeamType() throws IOException {
        int checked = 0;
        for (final Row r : ROWS) {
            if (r.kind != Kind.NOT_THE_SEAM || !"*".equals(r.method)) {
                continue;
            }
            final String code = SourceScan.codeOnly(SourceScan.read(r.file));
            for (final String seamType : new String[] {"ProviderTransport", "RcsTransport"}) {
                assertEquals(r.file + " carries a FILE-WIDE 'not the seam' exemption for " + r.verb
                        + ", and it has just acquired a " + seamType
                        + ". The exemption is no longer "
                        + "safe: replace it with method-level rows so a real provider send in this "
                        + "file cannot hide behind it.",
                        0, SourceScan.count(code, seamType));
            }
            checked++;
        }
        assertTrue("no file-wide NOT_THE_SEAM rows were checked — either they are gone or the "
                + "filter has gone stale, and this test would pass on nothing", checked >= 4);
    }

    /**
     * {@code ProviderTransport.sendImdn} chooses the MLS or plaintext receipt inside the seam,
     * which is why its callers carry no gate of their own.
     */
    @Test
    public void theImdnVerbIsGatedInsideTheSeamAndThatIsWhyItsCallersAreNot() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(TRANSPORT));
        final String body = SourceScan.bodyOf(code, "sendImdn", "rcsGroupId");
        assertTrue("could not find the 4-arg ProviderTransport.sendImdn — this test reads nothing "
                + "and would otherwise pass on an empty string", body.length() > 0);

        for (final String sealed : new String[] {"sendMlsImdn(", "sendMlsGroupImdn("}) {
            assertTrue("ProviderTransport.sendImdn must route to " + sealed + " when the "
                    + "conversation holds MLS stamps. Flattening this branch silently turns five "
                    + "receipt call sites into ungated sends on padlocked threads, and none of them "
                    + "has a gate of its own because this one exists.",
                    body.contains(sealed));
        }
        assertTrue("the plaintext leg must still exist — a guard that passes because the fallback "
                + "was deleted is not a guard", body.contains("provider.sendImdn("));
    }

    /**
     * The resend chain consults no send verdict because it reaches only the SEALED verbs; its own
     * checks decide whether and at which generation to seal, not whether to send in the clear.
     */
    @Test
    public void theResendChainCarriesNoPlaintextVerb() throws IOException {
        // The unsplit view, so the scan covers MlsSealSend as well.
        final String code = SourceScan.transportUnsplitCode();
        final List<int[]> decls = SourceScan.declarations(code);
        assertTrue("MlsProviderTransport parsed to no method declarations — this test would "
                + "otherwise pass on nothing", decls.size() >= 50);

        final List<String> found = new ArrayList<>();
        for (final Map.Entry<String, VerbClass> e : VERB_CLASS.entrySet()) {
            if (e.getValue() != VerbClass.CONTENT) {
                continue;
            }
            for (final Integer at : callsOf(code, decls, e.getKey())) {
                found.add(SourceScan.enclosingMethod(code, decls, at.intValue())
                        + "() -> " + e.getKey());
            }
        }
        assertEquals(
                "MlsProviderTransport now invokes a PLAINTEXT content verb. Every path through "
                + "this class — the resend chain especially — has reached the wire through the "
                + "SEALED verbs until now, which is why none of it carries a send verdict. A "
                + "plaintext verb here is a send that is gated by nothing at all.",
                new ArrayList<String>(), found);

        // The sealed pair is still reached, so this cannot pass on a class that stopped sending.
        for (final String sealed : new String[] {"sendMlsCiphertext(", "sendGroupMlsCiphertext("}) {
            assertTrue("the sealed send " + sealed + " must still be reached from this class — a "
                    + "guard that passes because the sends were deleted is not a guard",
                    SourceScan.count(code, sealed) >= 1);
        }
    }

    /** A renamed gate fails here, pointing at the rename rather than at a call site. */
    @Test
    public void theGateTokensNameMethodsThatActuallyExist() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(MLS_TRANSPORT));
        for (final String gate : new String[] {GROUP_GATE, ONE_TO_ONE_GATE}) {
            final String name = gate.substring(0, gate.length() - 1);
            assertTrue("MlsProviderTransport must declare " + name + " — the GATED rows in this "
                    + "file demand it by name, and if it was renamed they are asserting the "
                    + "presence of a dead string", SourceScan.bodyOf(code, name).length() > 0);
        }
        // The verdict is composed by the host-tested table, not re-derived.
        assertTrue("the send verdicts must compose through MlsSendRouting.decide",
                SourceScan.count(code, "MlsSendRouting.decide(") >= 1);
    }

    @Test
    public void theContentVerbSetIsTheSizeThisFileClaims() {
        final List<String> content = new ArrayList<>();
        for (final Map.Entry<String, VerbClass> e : VERB_CLASS.entrySet()) {
            if (e.getValue() == VerbClass.CONTENT) {
                content.add(e.getKey());
            }
        }
        assertEquals(
                "the content-bearing verb set has changed size. That is exactly the event this "
                + "file exists for — confirm the new verb has a verdict at every call site, then "
                + "move this number.",
                Arrays.asList("sendMessage", "sendGroupMessage", "sendFile", "sendLocation",
                        "sendReaction", "sendBotPostback"),
                content);
    }
}
