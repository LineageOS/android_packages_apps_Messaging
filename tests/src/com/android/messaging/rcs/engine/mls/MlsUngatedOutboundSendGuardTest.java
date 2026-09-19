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
 * <b>THE FIFTH VERB.</b> A content-bearing outbound send must not be added to
 * this app without someone answering, in writing, whether it can go out in the clear under a lit
 * padlock.
 *
 * <h2>Why this guard exists at all</h2>
 *
 * <p>Four plaintext-under-a-lit-padlock defects were found and fixed one verb at a time — group text
 *, media on both legs, location, reactions
 *. <b>Every one was found by a human reading code</b>, and the last two only because
 * a sweep was explicitly looking for them.
 *
 * <p>The mechanism that makes a fifth inevitable is structural rather than a run of carelessness:
 * {@code conversations.encryption_protocol} latches PER CONVERSATION, not per message
 * ({@code ReceiveRcsMessageAction} sets the MLS bit on any inbound message tagged
 * {@code RcsE2eeScheme.MLS}), so a verb added to the UI at any later date <b>inherits the padlock for
 * free and inherits no gate at all</b>. The author of verb five does not have to forget anything:
 * there is nothing at their call site telling them a question exists.
 *
 * <p>This is that thing. It is not a policy test — {@link MlsSendRoutingTest} pins the
 * DECISION, and {@link MlsGroupPlaintextRefusalGuardTest} /
 * {@code MlsMediaPlaintextRefusalGuardTest} / {@code MlsLocationReactionRefusalGuardTest} pin that
 * four specific call sites CONSULT it. This one pins that <b>the set of call sites is known</b>, so
 * a new one cannot appear unnoticed.
 *
 * <h2>The three places it can go red, and the first is the valuable one</h2>
 *
 * <ol>
 *   <li><b>A NEW VERB on the seam.</b> {@link #everySeamSendVerbIsClassified()} enumerates the
 *       {@code public send*} methods of {@code ProviderTransport} — and the {@code send*} methods of
 *       {@code IRcsProvider.aidl} one step earlier — from the sources themselves, and requires every
 *       one to appear in {@link #VERB_CLASS}. Adding {@code sendPoll} to the transport turns this
 *       red <b>before a single call site exists</b>, which is the earliest point at which the
 *       question can be asked.</li>
 *   <li><b>A NEW CALL SITE of an existing content verb.</b> {@link #everyContentSendCallSiteIsAccountedFor()}
 *       enumerates every invocation across the whole module {@code src/} tree and requires each
 *       {@code file#method} to carry a row in {@link #ROWS} saying GATED (and naming the gate), or
 *       EXEMPT / NOT_THE_SEAM (and carrying a written reason).</li>
 *   <li><b>A PLAINTEXT VERB APPEARING IN {@code MlsProviderTransport}.</b>
 *       {@link #theResendChainCarriesNoPlaintextVerb()} — the resend chain reaches the wire without
 *       consulting any verdict helper, which is correct only for as long as everything it reaches
 *       is {@link VerbClass#SEALED}. See that method; it is the answer to a scope challenge rather
 *       than an extra.</li>
 * </ol>
 *
 * <h2>Why the table is keyed on {@code file#method} and not on a line, a count, or a file</h2>
 *
 * <p>A LINE goes stale on the next edit and teaches people that red means "re-run and re-paste". A
 * FILE is too coarse: {@code ConversationFragment} holds a real {@code ProviderTransport} AND
 * declares its own unrelated {@code sendMessage(MessageData)}, so a file-level pass there would hide
 * a real new leak behind UI noise. {@code file#method} is the granularity at which a human decided
 * something.
 *
 * <p><b>Rows for the seam's own overloads and for same-named methods on other classes are NOT
 * bookkeeping.</b> {@code sendMessage} is also a SIP method, an SMS method and a UI host callback.
 * The scan is deliberately textual and unresolved, so it sees all of them — and every one gets a row
 * rather than a filter, because <b>a filter fails OPEN</b>: a genuinely new ungated site spelled in a
 * way the filter did not anticipate would be silently skipped, and this guard's whole value is its
 * failure mode.
 *
 * <h2>The one fail-open the file-level rows could have introduced, and the assertion that closes it</h2>
 *
 * <p>FOUR of those noise rows are written {@code file#*} — every invocation in the file — because the
 * file is wholly somebody else's subsystem: the three SIP engines and {@code MmsUtils}. That WOULD
 * fail open the day {@code SipDelegateClient} grows a real {@code ProviderTransport}. So a
 * {@code file#*} row carries a PRECONDITION that is checked:
 * {@link #aFileWideExemptionOnlyHoldsWhileTheFileNamesNoSeamType()} asserts the file mentions
 * neither {@code ProviderTransport} nor {@code RcsTransport} anywhere in its executable source. The
 * day one does, the row stops being valid and the test demands method-level rows instead. Measured,
 * not assumed: all four files score zero on both names today.
 *
 * <p>The files that DO hold a transport — {@code ConversationFragment}, {@code ComposeMessageView},
 * {@code CarrierStackDrModeDriver} — are kept at METHOD granularity for that reason, even though a
 * file-wide row would be shorter.
 *
 * <h2>Three gates, not one — and a guard that accepted "any of them" would be worse than none</h2>
 *
 * <p>A sweep found that "has a verdict on its path" is not one predicate:
 * <ul>
 *   <li>content sends must reach {@code MlsSendRouting.Verdict} via
 *       {@code MlsProviderTransport.groupSendVerdict} / {@code oneToOneSendVerdict};</li>
 *   <li>membership and group metadata reach a DIFFERENT family,
 *       {@code MlsProviderTransport.GroupMembershipRouting},
 *       which {@code ManageRcsGroupAction} uses for rename / add / remove;</li>
 *   <li>IMDN is gated one layer DOWN, inside the seam — see
 *       {@link #theImdnVerbIsGatedInsideTheSeamAndThatIsWhyItsCallersAreNot()}.</li>
 * </ul>
 * So every GATED row names the tokens ITS verb needs. A guard keyed on "some gate appears nearby"
 * would pass a content send that had consulted the membership router.
 *
 * <h2>The falsifier</h2>
 *
 * <p>Add an ungated {@code transport.sendLocation(...)} to any method not in the table and
 * {@link #everyContentSendCallSiteIsAccountedFor()} names the file, the method and the line. Delete
 * the {@code groupSendVerdict} call from {@code tryInsertSendingRcsGroupMessage} — the exact state
 * before the group gate landed — and {@link #everyGatedCallSiteAsksItsOwnGateFirst()} goes red on
 * the ordering. Both were run in a shadow tree before this landed.
 *
 * <p><b>What this guard does NOT claim.</b> It asserts the gate is CONSULTED and that it is consulted
 * BEFORE the send. It says nothing about whether the gate's ANSWER is right — that is
 * {@link MlsSendRoutingTest}'s table — nor about what the caller does with a refusal, which is
 * each per-verb guard's subject. A row here is not a substitute for one of those; finding this file
 * is not finding the whole rule.
 */
public class MlsUngatedOutboundSendGuardTest {

    // ------------------------------------------------------------------ ground truth: the seam

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

    /** The gate a GROUP-addressed content send must reach. */
    private static final String GROUP_GATE = "groupSendVerdict(";
    /** The gate a PEER-addressed content send must reach. */
    private static final String ONE_TO_ONE_GATE = "oneToOneSendVerdict(";

    /**
     * What a {@code send*} verb on the seam carries.
     *
     * <p>The distinction is not cosmetic: only {@link #CONTENT} verbs put a user's words, picture,
     * place or emoji on the wire, and only they can be the fifth instance of this defect family.
     */
    private enum VerbClass {
        /** Carries user content. Every call site needs a verdict. */
        CONTENT,
        /** A receipt, a typing notification or a negative IMDN. Carries no user content. */
        CONTROL,
        /**
         * Already ciphertext when it reaches the seam. Gating it would be gating the seal.
         *
         * <p><b>SEALED IS A STATEMENT ABOUT ENCRYPTION AND ABOUT NOTHING ELSE — in particular it
         * carries NO claim about status plumbing, and the next person to add a row here is the one
         * who needs to know that.</b> Measured on the provider side 2026-09-13, for
         * encrypted media, which will arrive at this enum:
         *
         * <ul>
         *   <li>{@code TachyonTransport.sendFile} emits {@code ProviderSink.onMessageStatus} for
         *       both the 1:1 and group arms — media is one of the few RCS sends whose terminal
         *       state already works;</li>
         *   <li>{@code sendMlsCiphertext} / {@code sendGroupMlsCiphertext} emit NOTHING. They
         *       return a synchronous {@code RcsSendResult} and never reach any of the provider's
         *       three {@code onMessageStatus} call sites.</li>
         * </ul>
         *
         * <p>So classifying a verb SEALED satisfies THIS guard and can still ship the
         * status defect — a send that succeeds and reads "Sending…" for ever. Routing
         * media through the sealed pair would convert a working status story into that one.
         *
         * <p><b>This guard will not catch it</b>, and the sentence is here so nobody infers
         * otherwise from a green run: it reads the app's source for gates, and status plumbing
         * lives in the provider. Said plainly because a guard's silence is indistinguishable from
         * its coverage — which is the same reason
         * {@link #theResendChainCarriesNoPlaintextVerb()} exists.
         */
        SEALED,
    }

    /**
     * <b>The classification, and the thing a new verb must be added to.</b>
     *
     * <p>Enumerated against the SOURCES in {@link #everySeamSendVerbIsClassified()} in both
     * directions, so neither a new verb nor a deleted one can sit here unnoticed.
     */
    private static final Map<String, VerbClass> VERB_CLASS = new LinkedHashMap<>();
    static {
        // ---- CONTENT: the surface this is about ------------------------------------------------
        VERB_CLASS.put("sendMessage", VerbClass.CONTENT);         // 1:1 composed text
        VERB_CLASS.put("sendGroupMessage", VerbClass.CONTENT);    // group composed text
        VERB_CLASS.put("sendFile", VerbClass.CONTENT);            // media, both legs
        VERB_CLASS.put("sendLocation", VerbClass.CONTENT);        // location share
        VERB_CLASS.put("sendReaction", VerbClass.CONTENT);        // emoji reaction
        VERB_CLASS.put("sendBotPostback", VerbClass.CONTENT);     // RBM suggestion chip  (see ROWS)

        // ---- SEALED: ciphertext by the time it arrives ------------------------------------------
        VERB_CLASS.put("sendMlsCiphertext", VerbClass.SEALED);
        VERB_CLASS.put("sendGroupMlsCiphertext", VerbClass.SEALED);

        // ---- CONTROL: no user content on the wire -----------------------------------------------
        VERB_CLASS.put("sendImdn", VerbClass.CONTROL);
        VERB_CLASS.put("sendMlsNegativeDeliveryImdn", VerbClass.CONTROL);
        VERB_CLASS.put("sendReconciliationReceipt", VerbClass.CONTROL);
        VERB_CLASS.put("sendTyping", VerbClass.CONTROL);
        VERB_CLASS.put("sendGroupTyping", VerbClass.CONTROL);
    }

    /**
     * {@code send*} verbs the AIDL declares that {@code ProviderTransport} deliberately does NOT
     * wrap, each with the reason it needs no app-side gate.
     *
     * <p>Checked in {@link #everySeamSendVerbIsClassified()}: an AIDL verb that is neither wrapped
     * nor listed here is a hole in the app-side seam, which is one step earlier than a hole in the
     * call sites.
     */
    private static final Map<String, String> AIDL_ONLY = new LinkedHashMap<>();
    static {
        AIDL_ONLY.put("sendMlsImdn",
                "no app-side wrapper: reached only from ProviderTransport.sendImdn's in-seam MLS "
                + "branch, which is pinned by theImdnVerbIsGatedInsideTheSeam...");
        AIDL_ONLY.put("sendMlsGroupImdn",
                "no app-side wrapper: the group leg of the same in-seam branch, same pin.");
    }

    // ------------------------------------------------------------------ the call-site table

    private enum Kind {
        /** A real content send that must reach a verdict. {@code gates} names which. */
        GATED,
        /** A real content send that may go unsealed. {@code reason} must say why. */
        EXEMPT,
        /** The seam's own body — this IS the verb, not a call site of it. */
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
        // ======================= GATED — the six real content sends =============================
        //
        // Each names the tokens ITS leg needs, and everyGatedCallSiteAsksItsOwnGateFirst() checks
        // the ORDERING as well as the presence. A verdict read after the message has gone is a log
        // line, which is the shape the group gate fixed.

        // 1:1 composed text. TWO tokens, and neither alone is the property:
        //   resolveForSend  — the scheme is RESOLVED rather than assumed, so an MLS conversation
        //                     frames RccMlsBody + tags message/mls and the provider seals it;
        //   appOwnedFailed  — a FAILED app-owned seal must not fall through to the plaintext send
        //                     below it. The generation is already spent; re-sending the same text in
        //                     the clear is the downgrade this whole family is about.
        // WHAT THIS ROW DOES NOT CLAIM: that resolveForSend's ANSWER is right. Whether the scheme
        // gate reads the latch correctly is E2eeSchemeGate's own subject.
        gated(INMA, "tryInsertSendingRcsMessage", "sendMessage", 1,
                "E2eeSendGate.get().resolveForSend(", "appOwnedFailed");

        // Group composed text — the original instance. Exactly ONE plaintext group send in
        // the method: a second one would be the fallback arm that was removed.
        gated(INMA, "tryInsertSendingRcsGroupMessage", "sendGroupMessage", 1, GROUP_GATE);

        // Media, both legs. An unsealed attachment is uploaded to the File Transfer Server
        // and fanned out BY REFERENCE, so it sits at a URL on a content server — which is why the
        // media call sites allow PLAINTEXT alone where the group text path also allows SEAL.
        gated(INMA, "tryInsertSendingRcsFile", "sendFile", 1, ONE_TO_ONE_GATE);
        gated(INMA, "tryInsertSendingRcsGroupFile", "sendFile", 1, GROUP_GATE);

        // Location and reactions are each BOTH legs in ONE method, so both tokens
        // are required. A row naming only one would pass a method that had lost the other leg's
        // gate — the exact half-covered shape MlsGroupPlaintextRefusalGuardTest's 1:1 test was
        // added to stop.
        gated(LOCATION_ACTION, "executeAction", "sendLocation", 1, GROUP_GATE, ONE_TO_ONE_GATE);
        gated(CONVERSATION_FRAGMENT, "onReactionSelected", "sendReaction", 1,
                GROUP_GATE, ONE_TO_ONE_GATE);

        // ======================= EXEMPT — real sends that may go unsealed ========================

        // THE ONE UNGATED CONTENT SEND IN THE TREE, and the reason it is a row rather than a fix.
        //
        // Checked over the whole 220-line file: ReceiveRcsBotMessageAction — the only Action a bot
        // thread's inbound reaches, via RcsCallbackRouter.onIncomingBotMessage — contains zero
        // occurrences of e2ee / E2EE / encryption_protocol. It cannot write the latch.
        // Checked over the whole src tree: setConversationEncryptionProtocol has exactly three
        // occurrences — its definition in BugleDatabaseOperations, ReceiveRcsMessageAction, and
        // ConversationBitsStore.store.
        // Not traced, and this is the gap: ConversationBitsStore.store is what E2eeSchemeGate
        // .selectScheme writes through, and selectScheme is reached from E2eeSendGate.resolveForSend,
        // which tryInsertSendingRcsMessage calls with dest = the bot id whenever a user types
        // ordinary text into a bot thread. Whether resolveForSend can return MLS for an RBM agent
        // address was NOT traced. If it can, the typed text is gated and this postback is not.
        // Tracing it is what would turn this row from "exempt with a reason" into "exempt, proven".
        exempt(CONVERSATION_FRAGMENT, "onBotSuggestionTapped", "sendBotPostback",
                "RBM suggestion-chip postback to an agent address. A bot thread's inbound lands in "
                + "ReceiveRcsBotMessageAction, which writes no e2ee scheme and so cannot latch the "
                + "padlock. Not traced: whether E2eeSendGate.resolveForSend can return MLS for a "
                + "bot address, which is the one route by which a bot thread could latch.");

        // The debug broadcast receivers. Developer-invoked over `am broadcast`, never reachable from
        // the UI, and their whole purpose is to put a chosen byte sequence on the wire — gating them
        // would remove the instrument. RcsDebugSendReceiver does consult E2eeSendGate under
        // `--ez gate` / `--ez e2ee` when you ask it to.
        exempt("src/com/android/messaging/rcs/RcsDebugSendReceiver.java", "onReceive", "sendMessage",
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

        // ======================= SEAM_IMPL — the verb's own body =================================

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

        // ======================= DECLARATION — an interface method, not a call ===================

        declaration("src/com/android/messaging/rcs/RcsTransport.java", "sendMessage",
                "the seam INTERFACE's own declaration. It carries no visibility modifier, so the "
                + "declaration filter cannot see it is one.");
        declaration("src/com/android/messaging/rcs/carrier/Transport.java", "sendMessage",
                "the carrier SIP stack's own unrelated Transport interface.");
        declaration("src/com/android/messaging/ui/conversation/ComposeMessageView.java",
                "sendMessage",
                "the nested ComposeMessageView.Host callback interface — a UI contract with the "
                + "fragment, not a transport.");

        // ======================= NOT_THE_SEAM — a same-named method elsewhere ====================
        //
        // Nine file-wide rows. Each is valid ONLY while its file names no seam type, which
        // aFileWideExemptionOnlyHoldsWhileTheFileNamesNoSeamType() checks — otherwise a file-wide
        // pass would be the fail-open this design is built to avoid.

        notTheSeam("src/com/android/messaging/rcs/sip/SipDelegateClient.java", "*", "sendMessage",
                "SipDelegateClient.conn.sendMessage(SipMessage, version) — a SIP transaction on the "
                + "ImsService delegate, nothing to do with the provider seam.");
        notTheSeam("src/com/android/messaging/rcs/sip/CpmSessionEngine.java", "*", "sendMessage",
                "the same SIP connection method, driving INVITE / ACK / BYE.");
        notTheSeam("src/com/android/messaging/rcs/sip/CpmIncomingSessionEngine.java", "*",
                "sendMessage", "the same SIP connection method, driving responses.");
        notTheSeam("src/com/android/messaging/sms/MmsUtils.java", "*", "sendMessage",
                "SmsSender.sendMessage — the SMS/MMS path, a different transport entirely.");
        notTheSeam("src/com/android/messaging/rcs/carrier/CarrierStackDrModeDriver.java", "sendText",
                "sendMessage",
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

    // ------------------------------------------------------------------ the scan

    /** A class-level method declaration, the same shape {@link SourceScan} uses. */
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
     * Is the match at {@code at} a WHOLE identifier, rather than the tail of a longer one?
     *
     * <p>{@link SourceScan#invocationsOf} is a substring search, and {@code "sendMessage("} is a
     * substring of {@code "getShowResendMessage("}, {@code "resendMessage("} and
     * {@code "canRedownloadMessage("}. The first run of this guard reported TWELVE call sites that
     * do not exist for exactly that reason — enough noise to get the table dismissed as busywork,
     * which is the failure mode a guard does not recover from.
     *
     * <p>It matters in the other direction too: every one of those phantom rows would have had to be
     * written up as an exemption, and an exemption list padded with fictions is how the real row
     * stops being read.
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

    /**
     * Every invocation of every {@link VerbClass#CONTENT} verb under {@code src/}, with its
     * enclosing method resolved.
     *
     * <p>Deliberately TEXTUAL and unresolved. It therefore also finds the SIP, SMS and UI methods of
     * the same name, and those get rows rather than a filter — see the class doc.
     */
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
        // ZERO HITS MUST FAIL, AND SO MUST NEARLY-ZERO. "> 0" would let a walk that had silently
        // lost 34 of its 35 hits report a clean tree — the SourceScan rule applied with an
        // exponent. 35 invocations across 33 distinct (file, method, verb) groups today; the floor
        // is set below that so ordinary edits do not churn it, and far enough above zero that a
        // broken walk or a stale declaration pattern cannot pass.
        //
        // THE STRONGER PROTECTION IS NOT THIS NUMBER, and a reader should know where it lives:
        // everyRowStillMatchesSomethingReal requires EVERY one of the table's rows to match at
        // least one hit. A collapsed scan fails there on all of them at once, which is a better
        // error than a count. This floor is the backstop for the case where the table itself has
        // been gutted in the same edit.
        // NAME WHAT WAS COUNTED AND WHERE. A bare number in a failure message is a
        // false-attribution generator: a count ratchet whose integer happens to collide with an
        // unrelated one sends the reader to the wrong change. Measured 2026-09-14 — a guard failing
        // "expected:<7> but was:<8>" an hour after an UNRELATED enum went 7 -> 8 was one message
        // from being reported as that enum's breakage, and what cleared it was that the assertion
        // named the file it scanned.
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

    /** The brace-matched body containing {@code at}, or the whole file when it is at class level. */
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

    // ------------------------------------------------------------------ 1. the verb surface

    /**
     * <b>THE EARLIEST POINT AT WHICH VERB FIVE CAN BE CAUGHT.</b> Every {@code public send*} method
     * of {@code ProviderTransport}, and every {@code send*} of {@code IRcsProvider.aidl}, must be
     * classified — before any call site exists.
     *
     * <p>Both directions. A verb that vanishes from the seam but lingers in {@link #VERB_CLASS} is
     * a stale row that would make a later reader think a surface is covered when it is gone.
     */
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
                + "row that makes this table read as covering more than it does", new TreeSet<String>(),
                stale);

        // One step earlier still: the AIDL. A verb can land on the contract before it has an
        // app-side wrapper, and that is the moment the question is cheapest to answer.
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

    // ------------------------------------------------------------------ 2. the call-site surface

    /**
     * <b>THE MAIN ASSERTION.</b> Every invocation of a CONTENT verb anywhere under {@code src/} is
     * accounted for by a row.
     *
     * <p>An unaccounted hit prints the file, the enclosing method and the line, because the fix is
     * either "gate it" or "write the sentence", and the reader needs to know which call site is
     * asking.
     */
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

    /**
     * <b>The table cannot rot.</b> Every row must match at least one real invocation.
     *
     * <p>Without this the guard degrades silently: rows accumulate for call sites that were deleted
     * or renamed, the table stops describing the tree, and the next reader trusts it. It is the same
     * property {@link MlsGroupPlaintextRefusalGuardTest} gets from counting its hits.
     */
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

    /**
     * Every GATED row's tokens are present in the enclosing method AND come BEFORE the send.
     *
     * <p><b>The ordering is the property, not the presence.</b> A verdict read after the message has
     * gone is a log line — which is what {@code tryInsertSendingRcsGroupMessage} would become if the
     * gate were moved below the send while still being "consulted".
     */
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
                assertTrue(r.key() + " must consult " + gate + " — without it this verb goes out in "
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

    /**
     * Every EXEMPT / NOT_THE_SEAM / SEAM_IMPL / DECLARATION row carries a written reason.
     *
     * <p>The point of the exemption list is that <b>a new row is a decision somebody has to write a
     * sentence about</b>. A one-word reason defeats that, so the length is asserted — crudely, but
     * the crudeness is the point: it is a floor on effort, not a quality judgement.
     */
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
            // A DECISION must be a sentence; a mechanical fact may be shorter. EXEMPT and
            // NOT_THE_SEAM are judgements a person made and can be wrong about — those are the rows
            // someone has to argue for. SEAM_IMPL and DECLARATION are statements
            // about what the file IS, and padding them to a sentence would be ceremony.
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

    /**
     * <b>The fail-open a {@code file#*} row would otherwise introduce.</b>
     *
     * <p>A file-wide NOT_THE_SEAM row says "every {@code sendMessage} in here is somebody else's
     * method". That is true today and would stop being true the moment the file acquires a
     * {@code ProviderTransport} — at which point the row would silently swallow a real, ungated
     * provider send.
     *
     * <p>So the row is valid only while the file names no seam type, and that precondition is
     * checked rather than assumed. Read on {@link SourceScan#codeOnly} source, so a comment
     * mentioning the transport does not trip it.
     */
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
                        + ", and it has just acquired a " + seamType + ". The exemption is no longer "
                        + "safe: replace it with method-level rows so a real provider send in this "
                        + "file cannot hide behind it.",
                        0, SourceScan.count(code, seamType));
            }
            checked++;
        }
        assertTrue("no file-wide NOT_THE_SEAM rows were checked — either they are gone or the "
                + "filter has gone stale, and this test would pass on nothing", checked >= 4);
    }

    // ------------------------------------------------------------------ 3. the in-seam precedent

    /**
     * <b>IMDN is the one verb already gated the way shape (b) would gate everything, and that is why
     * its five callers carry no gate of their own.</b>
     *
     * <p>{@code ProviderTransport.sendImdn} itself branches: with MLS stamps it calls the provider's
     * {@code sendMlsGroupImdn} / {@code sendMlsImdn}, otherwise the plaintext {@code sendImdn}. The
     * decision lives INSIDE the seam, so {@code MarkAsReadAction}, {@code ReceiveRcsMessageAction},
     * {@code ReceiveRcsMediaAction} and {@code RcsCallbackRouter} cannot get it wrong.
     *
     * <p>It is pinned here for two reasons. It is the existence proof that the seam in
     * shape (b) is buildable — one verb already has it. And it is the JUSTIFICATION for this guard's
     * silence about the IMDN call sites: if that branch is ever flattened, the five callers become
     * ungated content-adjacent sends and this file's scope was quietly wrong.
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
     * <b>THE RESEND CHAIN REACHES THE WIRE WITHOUT EVER CONSULTING A VERDICT HELPER — and that is
     * correct, because it reaches the SEALED verbs.</b>
     *
     * <p>Raised while scoping the user-initiated resend, and it is a
     * fair challenge to this file's scope: {@code resendOriginal} and {@code sendResendNow} carry
     * none of the gate tokens at all, {@code resendFramed} gates on {@code sendBlockedByGate} and
     * {@code sendFramedToGroup} on {@code hasEndMlsStatus}. Content goes out on the wire down a
     * chain this table says nothing about.
     *
     * <p><b>The table's silence there is a measured ABSENCE, not an exclusion</b>, and the
     * difference is the whole reason this test exists rather than a comment. The scan already walks
     * every file under {@code src/}, {@code MlsProviderTransport} included; it found ZERO
     * invocations of any CONTENT verb in it, because the resend chain routes to
     * {@code sendMlsCiphertext} / {@code sendGroupMlsCiphertext} — classified {@link VerbClass#SEALED},
     * ciphertext before they reach the seam. There is nothing there to gate against plaintext.
     *
     * <p>So the question {@code sendBlockedByGate} and {@code hasEndMlsStatus} answer is a DIFFERENT
     * one: may this be SEALED, and at which generation. Not: may this go out in the CLEAR. Folding
     * them into this table as a third gate family would make the table claim a coverage it does not
     * have.
     *
     * <p><b>What makes this go red, which is the point of asserting it instead of writing it down:</b>
     * the day a resend path routes to a plaintext verb. The user-initiated
     * over-RCS resend is scoped and not landed, and would add a public resend entry point here; if
     * it reaches {@code sendMessage} or {@code sendGroupMessage} rather than the sealed pair, this
     * fails and names the method. Nobody has to remember to carry a token.
     */
    @Test
    public void theResendChainCarriesNoPlaintextVerb() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(MLS_TRANSPORT));
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
        assertEquals("MlsProviderTransport now invokes a PLAINTEXT content verb. Every path through "
                + "this class — the resend chain especially — has reached the wire through the "
                + "SEALED verbs until now, which is why none of it carries a send verdict. A "
                + "plaintext verb here is a send that is gated by nothing at all.",
                new ArrayList<String>(), found);

        // And the sealed pair must still be the route, so this cannot pass because the class stopped
        // sending altogether.
        for (final String sealed : new String[] {"sendMlsCiphertext(", "sendGroupMlsCiphertext("}) {
            assertTrue("the sealed send " + sealed + " must still be reached from this class — a "
                    + "guard that passes because the sends were deleted is not a guard",
                    SourceScan.count(code, sealed) >= 1);
        }
    }

    /**
     * The gate tokens this table demands must be real methods on {@code MlsProviderTransport}.
     *
     * <p>Otherwise every GATED row asserts the presence of a string that nothing declares, and a
     * rename of {@code groupSendVerdict} would leave this file demanding a method that no longer
     * exists with an error message pointing at the call site rather than at the rename.
     */
    @Test
    public void theGateTokensNameMethodsThatActuallyExist() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(MLS_TRANSPORT));
        for (final String gate : new String[] {GROUP_GATE, ONE_TO_ONE_GATE}) {
            final String name = gate.substring(0, gate.length() - 1);
            assertTrue("MlsProviderTransport must declare " + name + " — the GATED rows in this "
                    + "file demand it by name, and if it was renamed they are asserting the "
                    + "presence of a dead string", SourceScan.bodyOf(code, name).length() > 0);
        }
        // And the verdict must still be composed by the host-tested table rather than re-derived at
        // either helper — the "do not add a sixth copy of 'is this encrypted'" rule.
        assertTrue("the send verdicts must compose through MlsSendRouting.decide",
                SourceScan.count(code, "MlsSendRouting.decide(") >= 1);
    }

    /**
     * The CONTENT set is the subject of this whole file, so its size is asserted rather than left
     * implicit. Six today: text, group text, media, location, reaction, bot postback.
     */
    @Test
    public void theContentVerbSetIsTheSizeThisFileClaims() {
        final List<String> content = new ArrayList<>();
        for (final Map.Entry<String, VerbClass> e : VERB_CLASS.entrySet()) {
            if (e.getValue() == VerbClass.CONTENT) {
                content.add(e.getKey());
            }
        }
        assertEquals("the content-bearing verb set has changed size. That is exactly the event this "
                + "file exists for — confirm the new verb has a verdict at every call site, then "
                + "move this number.",
                Arrays.asList("sendMessage", "sendGroupMessage", "sendFile", "sendLocation",
                        "sendReaction", "sendBotPostback"),
                content);
    }
}
