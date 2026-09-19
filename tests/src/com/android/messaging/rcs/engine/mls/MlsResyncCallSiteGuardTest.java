/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.SourceScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

/**
 * The RCC.16 §10.1.1 resync is reached only through {@link MlsParticipantKeyResync#planFrom}, whose
 * {@link MlsParticipantKeyLedger.Update} cannot express a guessed key; {@code plan()} with a wrong
 * key removes every client of the participant. Scans the app layer only.
 */
public class MlsResyncCallSiteGuardTest {

    /** The app-layer files that could drive a resync. */
    private static final String[] APP_SOURCES = {
        "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java",
        "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java",
        "src/com/android/messaging/rcs/RcsDebugSendReceiver.java",
    };

    /** No app-layer file calls the unsafe {@code MlsParticipantKeyResync.plan(} overload. */
    @Test
    public void no_app_layer_caller_uses_the_bare_string_overload() throws IOException {
        for (final String rel : APP_SOURCES) {
            final String code = SourceScan.codeOnly(SourceScan.read(rel));
            assertEquals(rel + " calls MlsParticipantKeyResync.plan(currentKey) directly. Use "
                            + "planFrom(roster, ledgerUpdate) — plan()'s backstop only catches an "
                            + "ABSENT key, and the failure this guards is a caller that confidently "
                            + "passes the WRONG one, which removes every client of the participant.",
                    0, SourceScan.count(code, "MlsParticipantKeyResync.plan("));
        }
    }

    /** The safe overload still exists, so deleting it cannot satisfy the guard above. */
    @Test
    public void the_safe_overload_exists() throws IOException {
        final String engine = SourceScan.read(
                "engine/src/com/android/messaging/rcs/engine/mls/MlsParticipantKeyResync.java");
        final String code = SourceScan.codeOnly(engine);
        assertTrue("planFrom(roster, update) is the sanctioned entry point and must exist",
                code.contains("public static Plan planFrom("));
        assertTrue("and it must take the ledger's Update, not a String",
                code.contains("MlsParticipantKeyLedger.Update update"));
    }
}
