/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

/**
 * The engine's RCC.16 §7.5.3.1 message-id check has an input end
 * ({@code MlsSession.setRequestMessageId}) and an output end
 * ({@code MlsSession.lastMessageIdMismatch}), and the two must be wired together. Arming only the
 * input would make the check fire into state nothing reads, which looks live; wiring only the
 * output reads a verdict that can never be a mismatch. Both ends live in one helper,
 * {@code MlsEngineIdCheck.process}, which both inbound legs call.
 *
 * <p>The engine's verdict is the only check: it refuses with {@code AAD_MESSAGE_ID_MISBINDING} on
 * the provider leg and drops on the carrier leg, where the CPIM {@code imdn.Message-ID} is threaded
 * from {@code CarrierMessageReceiver.handleInboundMls} to {@code MlsCarrierTransport}. The host
 * parse that used to decide beside it is gone, and {@link #legFault} fails if it comes back or the
 * verdict stops refusing. Our own carrier sends use one message id for the CPIM envelope and the
 * seal, so they cannot fail it. See docs/mls/rcc16-map.md.
 */
public class MlsEngineIdCheckWiringGuardTest {

    /** Module-relative engine sources. */
    private static final String SESSION =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsSession.java";

    /**
     * Production call sites of {@code name}, declarations and overrides excluded; the declarations
     * are counted separately by {@link #theScanCanSeeTheDeclarationsItIsCountingCallersOf()} so a
     * zero here is meaningful.
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
                    // A declaration, not a call: `default void x(`, `@Override public boolean x(`,
                    // `static native int x(`. Anything else with this token is an invocation.
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
     * Both members' declarations are visible to the scan; otherwise a zero-caller result would mean
     * a broken search.
     */
    @Test
    public void theScanCanSeeTheDeclarationsItIsCountingCallersOf() throws IOException {
        final String session = SourceScan.read(SESSION);
        assertTrue(
                "MlsSession must declare setRequestMessageId — if it was renamed, every count in "
                + "this class is a zero for the wrong reason",
                session.contains("setRequestMessageId("));
        assertTrue("MlsSession must declare lastMessageIdMismatch — same reason",
                session.contains("lastMessageIdMismatch("));
    }

    /** The input and output ends are wired the same way: both unwired or both wired. */
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

    private static final String HELPER =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsEngineIdCheck.java";
    private static final String DECRYPT =
            "engine/src/com/android/messaging/rcs/engine/mls/MlsInboundDecrypt.java";
    private static final String CARRIER =
            "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java";

    /**
     * Both ends are wired, and only inside the helper: one arm, one disarm, one read. Asserted as
     * counts, since the test above also passes with both unwired.
     */
    @Test
    public void bothEndsAreWiredOnlyInsideTheHelper() throws IOException {
        final String helper = SourceScan.codeOnly(SourceScan.read(HELPER));
        assertEquals("arm and disarm, both in MlsEngineIdCheck.process and nowhere else", 2,
                callers("setRequestMessageId"));
        assertEquals(2, SourceScan.count(helper, "setRequestMessageId("));
        assertEquals("one read, in the helper", 1, callers("lastMessageIdMismatch"));
        assertEquals(1, SourceScan.count(helper, "lastMessageIdMismatch("));
    }

    /**
     * Arm, process, read, then disarm in a {@code finally}, in that order in one method: the
     * engine's armed id is sticky per thread.
     */
    @Test
    public void theHelperArmsProcessesReadsAndDisarmsInOrder() throws IOException {
        final String body = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(HELPER)),
                "process");
        final int arm = body.indexOf("setRequestMessageId(");
        final int process = body.indexOf(".process(");
        final int read = body.indexOf("lastMessageIdMismatch()");
        final int fin = body.indexOf("finally");
        final int disarm = body.indexOf("setRequestMessageId(null)");
        assertTrue("arm " + arm + " < process " + process + " < read " + read + " < finally "
                + fin + " < disarm " + disarm,
                arm >= 0 && arm < process && process < read && read < fin && fin < disarm);
    }

    /**
     * Each inbound leg runs its decrypt through the helper exactly once and refuses on the
     * engine's verdict, before the plaintext goes anywhere. Nothing else calls the helper.
     */
    @Test
    public void eachLegRefusesOnTheEnginesVerdict() throws IOException {
        int total = 0;
        for (final String root : new String[] {"src", "engine/src"}) {
            for (final java.io.File f : SourceScan.javaSourcesUnder(root)) {
                total += SourceScan.count(SourceScan.codeOnly(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8)), "MlsEngineIdCheck.process(");
            }
        }
        assertEquals("the provider leg and the carrier leg, nothing else", 2, total);
        for (final String[] leg : LEGS) {
            final String body = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(leg[0])),
                    leg[1]);
            assertEquals(leg[0] + "." + leg[1], "", legFault(body, leg[2], leg[3]));
        }
    }

    /** File, method, the refusal inside the verdict's arm, and what must come after it. */
    private static final String[][] LEGS = {
        {DECRYPT, "decryptInbound", "MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING",
                "MlsResendReceive.evaluate("},
        {CARRIER, "onInboundCpim", "return Inbound.ignored();", "return Inbound.message(pt);"},
    };

    private static final String VERDICT = "if (processed.idMismatch) {";

    /**
     * "" when {@code body} decrypts through the helper, has no host AAD parse, and refuses with
     * {@code refusal} inside the arm of the engine's verdict, ahead of {@code after}; else why.
     */
    static String legFault(final String body, final String refusal, final String after) {
        if (body.isEmpty()) return "the leg was not found; a guard with no subject passes nothing";
        if (SourceScan.count(body, "MlsAppMessage.aadMessageId") != 0) {
            return "a host AAD parse is back in the leg. The engine decides RCC.16 §7.5.3.1 "
                    + "alone; a second parser disagrees with it on version, charset and era";
        }
        if (SourceScan.count(body, "MlsEngineIdCheck.process(") != 1
                || SourceScan.count(body, "session().process(")
                        + SourceScan.count(body, "mSelf.process(") != 0) {
            return "the leg must decrypt through MlsEngineIdCheck.process exactly once, and "
                    + "never through the session directly";
        }
        final int decrypt = body.indexOf("MlsEngineIdCheck.process(");
        final int verdict = body.indexOf(VERDICT);
        if (verdict < decrypt || SourceScan.count(body, "processed.idMismatch") != 1) {
            return "the leg must read the engine's verdict once, as `" + VERDICT + "`, after it "
                    + "decrypts";
        }
        final String arm = braced(body, verdict + VERDICT.length() - 1);
        if (!arm.contains(refusal)) {
            return "the engine's verdict is ignored: its arm does not reach `" + refusal + "`";
        }
        final int next = body.indexOf(after, verdict);
        if (next < 0 || body.indexOf(after) < verdict) {
            return "the verdict must be acted on before `" + after + "`";
        }
        return "";
    }

    /** The brace-matched block opening at {@code open}, or "". */
    private static String braced(final String s, final int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            else if (s.charAt(i) == '}' && --depth == 0) return s.substring(open, i + 1);
        }
        return "";
    }

    /** The guard fails when the host check is re-added or the engine's verdict is ignored. */
    @Test
    public void theLegGuardCanFail() {
        final String ok = "{\n"
                + "  final MlsEngineIdCheck.Processed processed ="
                + " MlsEngineIdCheck.process(s, g, c, id);\n"
                + "  if (processed.plain == null) return null;\n"
                + "  if (processed.idMismatch) {\n"
                + "    return refuse(MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING);\n"
                + "  }\n"
                + "  MlsResendReceive.evaluate(x);\n"
                + "}";
        final String refusal = "MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING";
        final String after = "MlsResendReceive.evaluate(";
        assertEquals("the shipping shape", "", legFault(ok, refusal, after));
        final String[] bad = {
            // the host check back beside the engine's
            ok.replace(VERDICT, "if (processed.idMismatch"
                    + " || !MlsAppMessage.aadMessageIdMatches(aad, id)) {"),
            // the host check back on its own
            ok.replace("  MlsResendReceive", "  if (!MlsAppMessage.aadMessageIdMatches(aad, id)) {"
                    + " return refuse(MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING); }\n"
                    + "  MlsResendReceive"),
            // the verdict read and ignored
            ok.replace("    return refuse(MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING);\n",
                    "    log(id);\n"),
            // the verdict not read at all
            ok.replace(VERDICT, "if (false) {"),
            // acted on only after the plaintext moved on
            "{\n  final MlsEngineIdCheck.Processed processed ="
                    + " MlsEngineIdCheck.process(s, g, c, id);\n  MlsResendReceive.evaluate(x);\n"
                    + "  if (processed.idMismatch) {\n    return refuse("
                    + "MlsInboundRefusal.Reason.AAD_MESSAGE_ID_MISBINDING);\n  }\n}",
            // decrypting around the helper
            ok.replace("MlsEngineIdCheck.process(s, g, c, id)", "session().process(g, c)"),
            "",
        };
        for (final String b : bad) {
            assertTrue("must fail:\n" + b, !legFault(b, refusal, after).isEmpty());
        }
    }

    /** The host parse is gone from the tree, not only from the two legs. */
    @Test
    public void noProductionCodeComparesTheAadIdOnTheHost() throws IOException {
        int files = 0;
        for (final String root : new String[] {"src", "engine/src"}) {
            for (final java.io.File f : SourceScan.javaSourcesUnder(root)) {
                files++;
                final String code = SourceScan.codeOnly(new String(
                        java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(f + " compares the AAD message id on the host; the engine decides",
                        0, SourceScan.count(code, "aadMessageIdMatches"));
            }
        }
        assertTrue("the scan saw no sources", files > 100);
    }

    /**
     * The carrier leg arms the engine with the envelope id, which reaches it through the whole
     * chain; a seam dropping it would arm an empty id and refuse every AAD that names one.
     */
    @Test
    public void theCarrierLegReceivesTheEnvelopeIdAndChecksIt() throws IOException {
        final String receiver = SourceScan.codeOnly(SourceScan.read(
                "src/com/android/messaging/rcs/carrier/sip/CarrierMessageReceiver.java"));
        assertTrue(
                "the receiver must read the envelope's own id from the CPIM headers — without it "
                + "nothing below this point has anything to compare an AAD against",
                receiver.contains("HDR_IMDN_MESSAGE_ID"));
        assertTrue("and must hand it to the MLS handler",
                receiver.contains("cpim.getPayload(),"));

        final String carrier = SourceScan.codeOnly(SourceScan.read(CARRIER));
        assertTrue("MlsCarrierTransport.onInboundCpim must accept the envelope id",
                carrier.contains("final String envelopeMessageId"));
        assertTrue("and must arm the engine's §7.5.3.1 check with it",
                carrier.contains("MlsEngineIdCheck.process(mSelf, g.groupId, payload, "
                        + "envelopeMessageId)"));
    }

    /**
     * A misbinding is a refusal, not a control message: returning {@code Inbound.control()} would
     * silently swallow a message that did decrypt.
     */
    @Test
    public void theCarrierMisbindingIsNotReportedAsControlOrDecryptFailure() throws IOException {
        final String carrier = SourceScan.bodyOf(SourceScan.codeOnly(SourceScan.read(CARRIER)),
                "onInboundCpim");
        final int check = carrier.indexOf(VERDICT);
        assertTrue("the verdict must be read", check >= 0);
        final String arm = braced(carrier, check + VERDICT.length() - 1);
        assertTrue("the misbinding arm must DROP the message", arm.contains("Inbound.ignored()"));
        assertEquals(
                "it must not return control() — that reads as 'not an application message' and "
                + "would swallow a message that demonstrably decrypted", 0,
                SourceScan.count(arm, "Inbound.control()"));
    }

    /** The provider leg, read through the unsplit transport, still refuses on the verdict. */
    @Test
    public void theProviderLegStillRefusesAMismatch() throws IOException {
        // The unsplit view: decryptInbound, which carries the check, lives in MlsInboundDecrypt.
        final String transport = SourceScan.transportUnsplitCode();
        assertEquals("one verdict read in the unsplit transport", 1,
                SourceScan.count(transport, VERDICT));
        assertTrue("and it must refuse rather than report a decrypt failure",
                transport.contains("AAD_MESSAGE_ID_MISBINDING"));
    }
}
