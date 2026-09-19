/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.sendstatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.SourceScan;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who may write a provider-layer scheme stamp or the Scytale bit. The send path must not stamp a
 * prediction; only an observed status or an inbound tag may.
 */
public class E2eeStampGuardTest {
    private static final String ACTIONS = "src/com/android/messaging/datamodel/action/";
    private static final String INSERT = ACTIONS + "InsertNewMessageAction.java";
    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String CARRIER =
            "src/com/android/messaging/rcs/carrier/CarrierImsTransport.java";
    private static final String GATE = "src/com/android/messaging/rcs/e2ee/E2eeSchemeGate.java";

    /** Every file whose code names the row's scheme column. */
    private static final List<String> STAMP_COLUMN_USERS = Arrays.asList(
            "ConversationMessageData.java", "DatabaseHelper.java", "DatabaseUpgradeHelper.java",
            "E2eeObservationStore.java", "InsertNewMessageAction.java",
            "ReceiveRcsMediaAction.java", "ReceiveRcsMessageAction.java");

    @Test
    public void insertPath_neverNamesTheProviderScheme() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(INSERT));
        assertTrue(code.contains("RCS_E2EE_SCHEME_ID"));
        assertFalse("InsertNewMessageAction stamps or tests the provider-layer scheme; only a "
                + "reported STATUS_SENT may stamp it", code.contains("ETOUFFEE"));
    }

    @Test
    public void onlyTheKnownFilesWriteTheStampColumn() throws IOException {
        final TreeSet<String> found = new TreeSet<>();
        for (final File f : SourceScan.javaSourcesUnder("src")) {
            final String code = SourceScan.codeOnly(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (code.contains("RCS_E2EE_SCHEME_ID")) found.add(f.getName());
        }
        assertEquals(new TreeSet<>(STAMP_COLUMN_USERS), found);
    }

    @Test
    public void insertPath_stampsOnlyMls() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(INSERT));
        final Matcher m = Pattern.compile("RCS_E2EE_SCHEME_ID\\s*,\\s*([^)]*)\\)").matcher(code);
        final List<String> values = new ArrayList<>();
        while (m.find()) values.add(m.group(1).trim());
        assertFalse("no stamp found in " + INSERT, values.isEmpty());
        for (final String v : values) {
            assertTrue("unexpected stamp value " + v,
                    v.equals("RcsE2eeScheme.MLS") || v.equals("stampedScheme"));
        }
        assertTrue(code.contains("stampedScheme = dispatchedViaMls ? RcsE2eeScheme.MLS : null;"));
    }

    @Test
    public void conversationBits_haveOneObservedTrafficWriter() throws IOException {
        final TreeSet<String> found = new TreeSet<>();
        for (final File f : SourceScan.javaSourcesUnder("src")) {
            final String code = SourceScan.codeOnly(
                    new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (code.contains("setConversationEncryptionProtocol(")) found.add(f.getName());
        }
        assertEquals(new TreeSet<>(Arrays.asList("BugleDatabaseOperations.java",
                "ConversationBitsStore.java", "E2eeObservationStore.java")), found);
    }

    @Test
    public void router_forwardsTheReportedScheme() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(ROUTER));
        final String aidl = SourceScan.bodyOf(code, "onMessageStatus",
                "@Nullable final String e2eeSchemeId)");
        assertTrue("the 5-argument AIDL onMessageStatus is gone", !aidl.isEmpty());
        assertTrue(aidl.contains("errorReason, e2eeSchemeId,"));
        assertTrue(aidl.contains("E2eeObservation.Source.PROVIDER"));
        final String inner = SourceScan.bodyOf(code, "onMessageStatus",
                "final E2eeObservation.Source source)");
        assertTrue(inner.contains("UpdateRcsMessageStatusAction.forStatus(messageId, status, "
                + "e2eeSchemeId, source)"));
        final String carrier = SourceScan.bodyOf(code, "onCarrierMessageStatus");
        assertTrue(carrier.contains("E2eeObservation.Source.CARRIER"));
    }

    @Test
    public void carrierStatuses_areLabelledCarrier() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(CARRIER)), "dispatchStatus");
        assertTrue(body.contains(".onCarrierMessageStatus("));
        assertTrue(body.indexOf(".onCarrierMessageStatus(") < body.indexOf(".onMessageStatus("));
    }

    @Test
    public void gate_neverSetsTheScytaleBit() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(GATE));
        final Matcher m = Pattern.compile("\\.accumulate\\(([^,]*),").matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
            assertEquals("the gate latches the Scytale bit", "false", m.group(1).trim());
        }
        assertEquals(1, n);
        assertFalse(code.contains("EtouffeeAvailability"));
    }
}
