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

import org.junit.Test;

import java.io.IOException;

/**
 * The §10.1.1 resync must be reached through {@link MlsParticipantKeyResync#planFrom}.
 *
 * <h2>Why a guard for a call site that does not exist yet</h2>
 *
 * <p>{@code plan()} takes a bare {@code currentKey} string, and passing one the caller has not
 * established removed <b>every client of the participant</b> — a catastrophic group wipe, not a
 * resync bug. {@code 572d92d9} added a backstop so an absent key now plans nothing, but a backstop
 * only catches the ABSENT case. It cannot catch a caller that confidently passes the WRONG key,
 * because a wrong key is indistinguishable from a right one at that signature.
 *
 * <p>{@link MlsParticipantKeyResync#planFrom} removes the choice: it takes a
 * {@link MlsParticipantKeyLedger.Update}, which either carries an established key or explicitly
 * carries none, and the ledger refuses to invent one. So the guarantee the wiring needs is not
 * "the backstop exists" — it is "production reaches the plan through the type that cannot express
 * a guess".
 *
 * <p>Today there is no production caller at all, which is exactly when this is worth writing. The
 * wiring is device-gated (a real participant-key roll has to be observed first), so whoever
 * finally adds the call site will be doing it weeks from now with this context gone. A
 * guard that goes red on the wrong overload is the only thing that will still be saying so.
 *
 * <p>Scans the app layer only. The engine itself and the tests use {@code plan()} directly and
 * should — narrowing it would move the test surface rather than the risk.
 */
public class MlsResyncCallSiteGuardTest {

    /** The app-layer files that could plausibly drive a resync. */
    private static final String[] APP_SOURCES = {
        "src/com/android/messaging/rcs/e2ee/MlsProviderTransport.java",
        "src/com/android/messaging/rcs/e2ee/MlsCarrierTransport.java",
        "src/com/android/messaging/rcs/RcsDebugSendReceiver.java",
    };

    /**
     * No app-layer file calls {@code MlsParticipantKeyResync.plan(} — the unsafe overload.
     *
     * <p>Comments and string literals are blanked first, so the existing prose reference in
     * {@code RcsDebugSendReceiver} ("the input MlsParticipantKeyResync.plan() has been waiting
     * for") does not trip it. A guard that cannot tell a mention from a call would be turned off
     * by the first person it inconvenienced.
     */
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

    /**
     * And the safe overload still exists to be reached.
     *
     * <p>Without this the guard above is satisfied by DELETING {@code planFrom}, which would pass
     * while making the situation strictly worse — the check would then be enforcing "nobody
     * resyncs at all" and reporting it as "everybody resyncs safely".
     */
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
