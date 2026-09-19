/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;
import com.android.messaging.rcs.carrier.msrp.MsrpReportStatus;

import java.io.IOException;
import org.junit.Test;

/**
 * The SR-path CPM session ({@code CpmSessionEngine}) reads an MSRP REPORT's Status: a 2xx is a
 * delivery, anything else a failure (RFC 4975 §7.1.2). It answers a peer's BYE with 200, which the
 * framework does not do for it. The engine is bound to the IMS SIP delegate, so its half is a
 * source scan.
 */
public final class CpmSessionReportTest {

    private static final String ENGINE = "src/com/android/messaging/rcs/sip/CpmSessionEngine.java";

    private static String engine() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ENGINE));
        assertTrue("CpmSessionEngine not found or empty — this guard is scanning nothing",
                code.contains("class CpmSessionEngine"));
        return code;
    }

    /** The REPORT arm of the MSRP listener, from its method test to the end of onFrame. */
    private static String reportArm() throws IOException {
        final String onFrame = SourceScan.bodyOfDeclaredAs(engine(), "public void onFrame(");
        assertTrue("MsrpConnListener.onFrame not found — this guard is scanning nothing",
                onFrame.length() > 0);
        final int at = onFrame.indexOf("MsrpMethod.REPORT");
        assertTrue("onFrame no longer tests for a REPORT — this guard is scanning nothing", at > 0);
        return onFrame.substring(at);
    }

    @Test
    public void onlyA2xxReportStatusIsADelivery() {
        assertTrue(MsrpReportStatus.delivered("000 200 OK"));
        assertTrue(MsrpReportStatus.delivered("000 202"));
        assertFalse(MsrpReportStatus.delivered("000 408 Request Timeout"));
        assertFalse(MsrpReportStatus.delivered("000 413 Message Too Large"));
        assertFalse("an unparseable Status is not a delivery", MsrpReportStatus.delivered("OK"));
        assertFalse(MsrpReportStatus.delivered(null));
        assertEquals(481, MsrpReportStatus.code("000 481 No Such Session"));
    }

    @Test
    public void aReportIsADeliveryOnlyWhenItsStatusSaysSo() throws IOException {
        final String arm = reportArm();
        final int delivered = arm.indexOf("Stage.DELIVERED");
        assertTrue("the REPORT arm no longer marks a delivery", delivered > 0);
        final int verdict = arm.indexOf("MsrpReportStatus.delivered(");
        assertTrue("a REPORT for the in-flight message marks it DELIVERED without reading its "
                + "Status, so a failure REPORT (e.g. 000 408) reads as a delivery",
                verdict >= 0 && verdict < delivered);
        assertTrue("a failure REPORT does not fail the send", arm.contains("failAndWake("));
    }

    @Test
    public void aPeersByeIsAnsweredWith200() throws IOException {
        final String body = SourceScan.bodyOf(engine(), "onSipRequest");
        assertTrue("CpmSessionEngine.onSipRequest not found — this guard is scanning nothing",
                body.length() > 0);
        assertTrue("a peer's BYE is not answered: the framework does not send the 200, so the "
                + "peer's BYE transaction times out instead of completing",
                body.contains("buildResponse(") && body.contains("200"));
        assertTrue("the answer is not sent", body.contains("sendMessage("));
    }
}
