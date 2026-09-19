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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The three-valued era-advance mode byte (§9.7g, invariant 103).
 *
 * <p>Small, but the numbers cross the FFI boundary and are persisted in a pending operation, so they
 * are pinned here rather than trusted to stay put.
 */
public class MlsAdvanceEraKindTest {

    @Test
    public void theModeBytesAreTheReferenceClientsZeroOneTwo() {
        assertEquals(0, MlsAdvanceEraKind.NORMAL.mode);
        assertEquals(1, MlsAdvanceEraKind.REVIVAL.mode);
        assertEquals(2, MlsAdvanceEraKind.PHOENIX_DOWNGRADE.mode);
        assertEquals(3, MlsAdvanceEraKind.values().length);
    }

    /**
     * <b>Exactly one mode may remove {@code 0xF002}</b> — INVARIANT ED-1's structural half. If this
     * ever reports two, the remover has grown a third call site.
     */
    @Test
    public void onlyRevivalMayRemoveEndMls() {
        int removers = 0;
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            if (k.mayRemoveEndMls()) removers++;
        }
        assertEquals(1, removers);
        assertTrue(MlsAdvanceEraKind.REVIVAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.mayRemoveEndMls());
    }

    /** And exactly one INSTALLS it — the half a preserve-only carry list cannot express. */
    @Test
    public void onlyPhoenixInstallsEndMls() {
        int installers = 0;
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            if (k.installsEndMls()) installers++;
        }
        assertEquals(1, installers);
        assertTrue(MlsAdvanceEraKind.PHOENIX_DOWNGRADE.installsEndMls());
    }

    /** {@link MlsAdvanceEraKind#NORMAL} touches neither — mode 0 carries whatever is there. */
    @Test
    public void normalTouchesNeither() {
        assertFalse(MlsAdvanceEraKind.NORMAL.mayRemoveEndMls());
        assertFalse(MlsAdvanceEraKind.NORMAL.installsEndMls());
    }

    /**
     * Google Messages' decode treats {@code > 2} as mode 0 — the {@code b.ne} falls through to touching
     * neither. An unknown mode carrying the extension forward is always the safe answer to "I do not
     * know what you meant"; the alternative would be an advance that silently dropped a downgrade.
     */
    @Test
    public void anUnknownModeDecodesToNormal() {
        assertEquals(MlsAdvanceEraKind.NORMAL, MlsAdvanceEraKind.fromMode(3));
        assertEquals(MlsAdvanceEraKind.NORMAL, MlsAdvanceEraKind.fromMode(255));
        assertEquals(MlsAdvanceEraKind.NORMAL, MlsAdvanceEraKind.fromMode(-1));
    }

    @Test
    public void fromModeRoundTrips() {
        for (final MlsAdvanceEraKind k : MlsAdvanceEraKind.values()) {
            assertEquals(k, MlsAdvanceEraKind.fromMode(k.mode));
        }
    }
}
