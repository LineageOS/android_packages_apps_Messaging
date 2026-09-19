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

import java.io.IOException;

import org.junit.Test;

/**
 * <b>THE ENGINE'S §7.5.3.1 MESSAGE-ID CHECK IS DEAD AT BOTH ENDS, AND ARMING EITHER ONE ALONE WOULD
 * BE WORSE THAN LEAVING IT</b>.
 *
 * <h2>The measurement, both ends, 2026-09-14</h2>
 *
 * <p><b>INPUT — unarmed.</b> {@code note_message_id_check} reads a thread-local
 * {@code EXPECTED_MESSAGE_ID} and returns immediately when it is empty. The only thing that sets it
 * is {@code MlsSession.setRequestMessageId}, which has <b>no production caller anywhere in either
 * repo</b>. {@code git log -S} settles the open question: it was ADDED by {@code 0a01b096}
 * ("remove the host-supplied AAD seam — the engine builds it now") together with its
 * implementation, and never acquired one. It is scaffolding for a caller that never landed, not a
 * caller that was removed.
 *
 * <p><b>OUTPUT — unread.</b> Even when it fires, {@code note_message_id_check} does not fail the
 * decrypt, return an error or drop the message. It logs and stores {@code LAST_ID_MISMATCH}. That
 * thread-local IS exported ({@code rcs_mls_last_message_id_mismatch}), IS bridged
 * ({@code nativeLastMessageIdMismatch}) and IS surfaced as {@code MlsSession.lastMessageIdMismatch()}
 * — and <b>nothing calls that either</b>. A scan for it finds exactly two hits, the interface
 * default and the {@code OpenMlsSession} override, which is what makes the absence of callers a
 * measurement rather than a failed search.
 *
 * <h2>Why this is a guard and not just a deletion</h2>
 *
 * <p>The tempting fix is "call {@code setRequestMessageId} and the check starts working". It does
 * not: it would start the check FIRING, into a thread-local no code reads. That converts a visibly
 * dead check into an <b>invisibly</b> dead one — a mismatch would be detected, recorded and
 * discarded, and the next reader would find a check that is called, armed and apparently live. That
 * is strictly worse than today, where its deadness is obvious at the first end you look at.
 *
 * <p>So the property worth holding is not "wire it" or "delete it" but <b>BOTH ENDS MOVE
 * TOGETHER</b>. This test fails the moment one end is wired without the other, in either direction.
 *
 * <h2>What actually protects the wire today, so nobody reads this as "no check exists"</h2>
 *
 * <p>The HOST check does — {@code MlsAppMessage.aadMessageIdMatches}, refusing with
 * {@code AAD_MESSAGE_ID_MISBINDING} on the provider leg and dropping on the carrier leg.
 *
 * <p><b>The carrier leg had neither check until the envelope id was threaded to its decrypt point</b>
 * — and the reason it had none was structural rather than an omission:
 * {@code onInboundCpim(conversationId, senderE164, contentType, payload)} never received a transport
 * message id, and neither did the {@code setMlsInboundHandler} lambda above it, so there was nothing
 * at that point to compare an AAD against. The id now flows
 * {@code CarrierMessageReceiver.handleInboundMls} (from the CPIM {@code imdn.Message-ID}) ->
 * {@code MlsInboundHandler.onMls} -> {@code E2eeConversationTransport.onInboundCpim} ->
 * {@code MlsCarrierTransport}, which applies the check. Pinned by the three tests at the end of this
 * class.
 *
 * <p><b>Why that check is safe on our own traffic, which is the bar it had to clear:</b>
 * {@code CarrierRcsTransport.sendMlsBody} puts ONE {@code messageId} into
 * {@code CpimMessage.newMls} and passes that SAME variable to {@code encryptForSend}
 * — so the AAD id and the envelope id are identical by construction for anything we
 * send, and a message of ours cannot fail it. A check that drops good messages would have been
 * strictly worse than no check, which is why this was verified before landing rather than after.
 *
 * <h2>The falsifier</h2>
 *
 * <p>Add a production call to either {@code setRequestMessageId} or {@code lastMessageIdMismatch}
 * and the corresponding test goes red naming the other end. Run before landing, both directions.
 */
public class MlsEngineIdCheckWiringGuardTest {

    /** Module-relative engine sources. */
    private static final String SESSION =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsSession.java";

    /**
     * Production call sites of {@code name} across BOTH repos' production trees — declarations and
     * overrides excluded, so a hit is a CALLER.
     *
     * <p>Counting declarations separately is what makes a zero here meaningful: if the scan were
     * broken it would find neither, and {@link #theScanCanSeeTheDeclarationsItIsCountingCallersOf()}
     * fails first.
     */
    private static int callers(final String name) throws IOException {
        int n = 0;
        for (final String root : new String[] {"src", "engine/src"}) {
            for (final java.io.File f : SourceScan.javaSourcesUnder(root)) {
                final String code = SourceScan.codeOnly(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                for (final Integer at : SourceScan.indicesOf(code, name + "(")) {
                    final int lineStart = code.lastIndexOf('\n', at.intValue()) + 1;
                    final String line = code.substring(lineStart,
                            Math.min(code.length(), at.intValue() + name.length() + 1));
                    // A DECLARATION, not a call: `default void x(`, `@Override public boolean x(`,
                    // `static native int x(`. Everything else with this token is an invocation.
                    if (line.contains("default ") || line.contains("@Override")
                            || line.contains("native ") || line.contains("abstract ")) {
                        continue;
                    }
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * <b>The control that makes the zeros below evidence.</b> Both members must EXIST — if the scan
     * cannot see their declarations it cannot be trusted to have seen callers either, and a clean
     * zero would mean "broken search", not "no callers".
     */
    @Test
    public void theScanCanSeeTheDeclarationsItIsCountingCallersOf() throws IOException {
        final String session = SourceScan.read(SESSION);
        assertTrue("MlsSession must declare setRequestMessageId — if it was renamed, every count in "
                + "this class is a zero for the wrong reason",
                session.contains("setRequestMessageId("));
        assertTrue("MlsSession must declare lastMessageIdMismatch — same reason",
                session.contains("lastMessageIdMismatch("));
    }

    /**
     * <b>BOTH ENDS MOVE TOGETHER.</b> The input arm and the output arm of the engine's §7.5.3.1
     * check must be wired the same way — both unwired (today) or both wired.
     *
     * <p>Wiring the INPUT alone makes the check fire into a thread-local nothing reads: a detected
     * mismatch is recorded and thrown away, and it then LOOKS live. Wiring the OUTPUT alone reads a
     * verdict that can never be anything but "no mismatch", which is a check that cannot fail — the
     * defect this guard is an instance of.
     */
    @Test
    public void theEnginesIdCheckIsWiredAtBothEndsOrNeither() throws IOException {
        final int armed = callers("setRequestMessageId");
        final int read = callers("lastMessageIdMismatch");

        assertEquals("the engine's §7.5.3.1 check is HALF-WIRED: setRequestMessageId has " + armed
                + " production caller(s) and lastMessageIdMismatch has " + read + ".\n"
                + "  Arming the INPUT alone makes the check fire into LAST_ID_MISMATCH, which\n"
                + "  nothing reads — the mismatch is detected, recorded and discarded, and the\n"
                + "  check then LOOKS live to the next reader. That is worse than today, where it\n"
                + "  is visibly dead at the first end you inspect.\n"
                + "  Reading the OUTPUT alone consumes a verdict that can never be anything but\n"
                + "  'no mismatch', because nothing arms the input — a check that cannot fail.\n"
                + "  Wire both, or neither.",
                armed == 0, read == 0);
    }

    /**
     * And today that shared state is UNWIRED, stated as a number rather than implied. If this ever
     * passes with a non-zero count the premise has changed and this analysis needs rereading —
     * which is why it is asserted rather than left to the test above, where both-wired would also
     * pass.
     */
    @Test
    public void todayBothEndsAreUnwiredAndThatIsTheRecordedState() throws IOException {
        assertEquals("setRequestMessageId has acquired a production caller. That is not wrong — but "
                + "the whole analysis above rests on it having none, so re-read it before "
                + "moving this number", 0, callers("setRequestMessageId"));
        assertEquals("lastMessageIdMismatch has acquired a production caller. Same — re-read the "
                + "analysis first", 0, callers("lastMessageIdMismatch"));
    }

    // ------------------------------------------------------------------ the carrier leg's check

    /**
     * <b>The carrier leg now makes the §7.5.3.1 cross-check, and the id it needs reaches it.</b>
     *
     * <p>Keyed on the whole chain rather than the final call, because any one seam dropping the id
     * silently restores the original gap — the check would compile, run, and compare against null
     * for ever, which {@code aadMessageIdMatches} fail-opens on. A check that has quietly stopped
     * receiving its input is exactly this guard's subject.
     */
    @Test
    public void theCarrierLegReceivesTheEnvelopeIdAndChecksIt() throws IOException {
        final String receiver = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/rcs/carrier/sip/CarrierMessageReceiver.java"));
        assertTrue("the receiver must read the envelope's own id from the CPIM headers — without it "
                + "nothing below this point has anything to compare an AAD against",
                receiver.contains("HDR_IMDN_MESSAGE_ID"));
        assertTrue("and must hand it to the MLS handler",
                receiver.contains("cpim.getPayload(),"));

        final String carrier = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java"));
        assertTrue("MlsCarrierTransport.onInboundCpim must accept the envelope id",
                carrier.contains("final String envelopeMessageId"));
        assertTrue("and must run the §7.5.3.1 cross-check on it",
                carrier.contains("MlsAppMessage.aadMessageIdMatches(inboundAad, envelopeMessageId)"));
    }

    /**
     * <b>A mismatch DROPS, and the ordering is the property.</b> The check must be reached before the
     * plaintext is surfaced — a comparison made after {@code Inbound.message(pt)} has been returned
     * is unreachable, and one made after the ledger has been written is a log line.
     */
    @Test
    public void theCarrierMisbindingIsDroppedBeforeThePlaintextIsSurfaced() throws IOException {
        final String carrier = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java"));
        final int check = carrier.indexOf("aadMessageIdMatches(inboundAad, envelopeMessageId)");
        final int surface = carrier.indexOf("return Inbound.message(pt);");
        assertTrue("the check must be present", check >= 0);
        assertTrue("the plaintext surface must still be present — a guard that passes because the "
                + "decrypt path was deleted is not a guard", surface >= 0);
        assertTrue("the check must come BEFORE the plaintext is surfaced (check at " + check
                + ", surface at " + surface + ")", check < surface);
    }

    /**
     * <b>A misbinding is a REFUSAL, not a decrypt failure</b> — the distinction the provider leg had
     * to learn the hard way. {@code Inbound.control()} reads as "this was not an
     * application message"; returning it here would silently swallow a message that DID decrypt.
     */
    @Test
    public void theCarrierMisbindingIsNotReportedAsControlOrDecryptFailure() throws IOException {
        final String carrier = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java"));
        final int check = carrier.indexOf("aadMessageIdMatches(inboundAad, envelopeMessageId)");
        assertTrue("the check must be present", check >= 0);
        final String arm = carrier.substring(check,
                Math.min(carrier.length(), carrier.indexOf("return Inbound.message(pt);", check)));
        assertTrue("the misbinding arm must DROP the message", arm.contains("Inbound.ignored()"));
        assertEquals("it must not return control() — that reads as 'not an application message' and "
                + "would swallow a message that demonstrably decrypted", 0,
                SourceScan.count(arm, "Inbound.control()"));
    }

    /**
     * <b>The live protection must not be mistaken for this one.</b> If someone deletes the HOST
     * check believing the engine covers it, the provider leg loses its only §7.5.3.1 enforcement —
     * and the engine would not notice, because it is dead at both ends.
     */
    @Test
    public void theHostCheckThatActuallyProtectsTheProviderLegIsStillThere() throws IOException {
        final String transport = SourceScan.transport();
        assertTrue("MlsProviderTransport must still call MlsAppMessage.aadMessageIdMatches — it is "
                + "the ONLY live §7.5.3.1 enforcement in the tree, on either leg",
                transport.contains("MlsAppMessage.aadMessageIdMatches("));
        assertTrue("and must still refuse on a mismatch rather than reporting a decrypt failure",
                transport.contains("AAD_MESSAGE_ID_MISBINDING"));
    }
}
