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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.TreeSet;

/**
 * Who may write a provider-layer scheme stamp or the provider-plane bit. The send path must not
 * stamp a prediction; only an observed status or an inbound tag may.
 */
public class E2eeStampGuardTest {
    private static final String INSERT =
            "src/com/android/messaging/datamodel/action/InsertNewMessageAction.java";
    private static final String ROUTER = "src/com/android/messaging/rcs/RcsCallbackRouter.java";
    private static final String CARRIER =
            "src/com/android/messaging/rcs/carrier/CarrierImsTransport.java";

    @Test
    public void insertPath_neverStampsAScheme() throws IOException {
        final String code = SourceScan.codeOnly(SourceScan.read(INSERT));
        assertTrue(code.contains("insertNewMessageInTransaction"));
        assertFalse("InsertNewMessageAction stamps a scheme; only a reported STATUS_SENT may",
                code.contains("RCS_E2EE_SCHEME_ID") || code.contains("ETOUFFEE"));
    }

    @Test
    public void onlyTheKnownFilesWriteTheStampColumn() throws IOException {
        assertEquals(new TreeSet<>(Arrays.asList("ConversationMessageData.java",
                "DatabaseHelper.java", "DatabaseUpgradeHelper.java", "E2eeObservationStore.java",
                "ReceiveRcsMediaAction.java", "ReceiveRcsMessageAction.java")),
                filesWhoseCodeContains("RCS_E2EE_SCHEME_ID"));
    }

    @Test
    public void conversationBits_haveOneObservedTrafficWriter() throws IOException {
        assertEquals(new TreeSet<>(Arrays.asList("BugleDatabaseOperations.java",
                "E2eeObservationStore.java")),
                filesWhoseCodeContains("setConversationEncryptionProtocol("));
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
        assertTrue(SourceScan.bodyOf(code, "onCarrierMessageStatus")
                .contains("E2eeObservation.Source.CARRIER"));
    }

    @Test
    public void carrierStatuses_areLabelledCarrier() throws IOException {
        final String body = SourceScan.bodyOf(
                SourceScan.codeOnly(SourceScan.read(CARRIER)), "dispatchStatus");
        assertTrue(body.contains(".onCarrierMessageStatus("));
        assertTrue(body.indexOf(".onCarrierMessageStatus(") < body.indexOf(".onMessageStatus("));
    }

    private static TreeSet<String> filesWhoseCodeContains(final String needle)
            throws IOException {
        File root = null;
        for (final String c : new String[] {"src", "packages/apps/Messaging/src", "../src"}) {
            if (new File(c).isDirectory()) {
                root = new File(c);
                break;
            }
        }
        if (root == null) {
            throw new IOException("src not found from " + new File(".").getAbsolutePath());
        }
        final TreeSet<String> found = new TreeSet<>();
        final Deque<File> stack = new ArrayDeque<>();
        stack.push(root);
        int scanned = 0;
        while (!stack.isEmpty()) {
            final File[] kids = stack.pop().listFiles();
            if (kids == null) continue;
            for (final File k : kids) {
                if (k.isDirectory()) {
                    stack.push(k);
                } else if (k.getName().endsWith(".java")) {
                    scanned++;
                    final String code = SourceScan.codeOnly(
                            new String(Files.readAllBytes(k.toPath()), StandardCharsets.UTF_8));
                    if (code.contains(needle)) found.add(k.getName());
                }
            }
        }
        assertTrue("scanned " + scanned + " files", scanned > 100);
        return found;
    }
}
