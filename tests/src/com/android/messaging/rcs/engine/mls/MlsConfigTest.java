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

import java.util.HashMap;
import java.util.Map;

/**
 * {@link MlsConfig} — rework item 14.4.
 *
 * <p>The point of this file is not the getters. It is that this file <em>exists at all</em>: every
 * assertion here was unreachable from a host test while these knobs were {@code SystemProperties}
 * reads at the call site, because {@code SystemProperties} is an Android class. That is the concrete
 * blocker 14.4 removes.
 */
public class MlsConfigTest {

    /** A {@link MlsConfig.Source} over a literal map — what a test uses instead of a device. */
    private static final class MapSource implements MlsConfig.Source {
        private final Map<String, String> mValues = new HashMap<>();

        MapSource set(final String k, final Object v) {
            mValues.put(k, String.valueOf(v));
            return this;
        }

        @Override public boolean getBoolean(final String k, final boolean d) {
            final String v = mValues.get(k);
            return v == null ? d : ("1".equals(v) || "true".equals(v));
        }

        @Override public int getInt(final String k, final int d) {
            final String v = mValues.get(k);
            try { return v == null ? d : Integer.parseInt(v); } catch (NumberFormatException e) { return d; }
        }

        @Override public long getLong(final String k, final long d) {
            final String v = mValues.get(k);
            try { return v == null ? d : Long.parseLong(v); } catch (NumberFormatException e) { return d; }
        }
    }

    @Test
    public void defaults_areTheShippedBehaviour() {
        final MlsConfig c = MlsConfig.defaults();
        assertEquals(MlsConfig.DEF_ERA_YIELD_LOOKS, c.eraYieldLooks);
        assertEquals(MlsConfig.DEF_ERA_ADVANCE_MODE, c.eraAdvanceMode);
        assertEquals(MlsConfig.DEF_KP_MIN_REMAINING_DAYS, c.kpMinRemainingDays);
        assertEquals(MlsConfig.DEF_KP_POOL_TOTAL, c.kpPoolCount);
        assertFalse("the KeyPackage dump is a diagnostic, off by default", c.dumpKeyPackages);
    }

    /**
     * The one default that is a security control rather than a preference: A.4.1 SAN↔MSISDN
     * equality is ENFORCED unless someone explicitly turns it off. It shipped disabled once
     * ({@code expected_msisdn = None}), which is how we came to accept a certificate for a
     * different phone number.
     */
    @Test
    public void sanIdentityCheck_isEnforcedByDefault() {
        assertTrue(MlsConfig.defaults().sanIdentityCheck);
        assertTrue(MlsConfig.from(new MapSource().set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 1))
                .sanIdentityCheck);
        assertFalse("0 is the documented, unsupported bring-up escape hatch",
                MlsConfig.from(new MapSource().set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0))
                        .sanIdentityCheck);
    }

    @Test
    public void everyKnob_isReadFromTheSource() {
        final MlsConfig c = MlsConfig.from(new MapSource()
                .set(MlsConfig.KEY_DUMP_KP, 1)
                .set(MlsConfig.KEY_ERA_YIELD_LOOKS, 7)
                .set(MlsConfig.KEY_ERA_ADVANCE_MODE, 2)
                .set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0)
                .set(MlsConfig.KEY_KP_MIN_DAYS, 7L)
                .set(MlsConfig.KEY_KP_COUNT, 4));
        assertEquals(7, c.eraYieldLooks);
        assertTrue(c.dumpKeyPackages);
        assertEquals(2, c.eraAdvanceMode);
        assertFalse(c.sanIdentityCheck);
        assertEquals(7L, c.kpMinRemainingDays);
        assertEquals(4, c.kpPoolCount);
    }

    /**
     * The pool clamp used to live in {@code kpPoolCount()} beside the sysprop read. A pool of one
     * is the last-resort package and nothing claimable, so every peer that tried to add us would
     * consume the one package that is meant to be reusable.
     */
    @Test
    public void kpPoolCount_isClampedToTheSmallestNonDegeneratePool() {
        for (final int given : new int[] {Integer.MIN_VALUE, -1, 0, 1, 2}) {
            final int got = MlsConfig.from(new MapSource().set(MlsConfig.KEY_KP_COUNT, given))
                    .kpPoolCount;
            assertEquals("pool " + given + " must clamp to " + MlsConfig.MIN_KP_POOL_TOTAL,
                    Math.max(MlsConfig.MIN_KP_POOL_TOTAL, given), got);
        }
        assertEquals(50, MlsConfig.from(new MapSource().set(MlsConfig.KEY_KP_COUNT, 50)).kpPoolCount);
    }

    /**
     * Two configs with different knobs, alive in ONE JVM at the same time. Impossible before 14.4 —
     * the flag state belonged to the device, so a second test could only observe whatever the first
     * had left behind.
     */
    @Test
    public void twoInstances_holdDifferentKnobsSimultaneously() {
        final MlsConfig strict = MlsConfig.defaults();
        final MlsConfig lax = MlsConfig.from(new MapSource()
                .set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0)
                .set(MlsConfig.KEY_KP_COUNT, 3));
        assertTrue(strict.sanIdentityCheck);
        assertFalse(lax.sanIdentityCheck);
        assertEquals(MlsConfig.DEF_KP_POOL_TOTAL, strict.kpPoolCount);
        assertEquals(3, lax.kpPoolCount);
    }

    /**
     * The metadata-keys extension type is <b>0xF007</b> — RCC.16 v4.0 §7.11.10.1.
     *
     * <p>This assertion is the inverse of what it was, and the inversion is the point. For months
     * the test pinned "unset", because the value was a guess and nothing observed had named one
     * from the 0xF007–0xF00F region — a guess fails silently in BOTH directions (too low
     * and the gate is never seen, so a group that should refresh never does; too high and some other
     * extension is read AS the gate, so we burn an era the server never asked for). None of that
     * reasoning was wrong. The number simply lived in a spec revision that had not been published.
     *
     * <p>The override survives, so a capture that contradicts the document still wins without a
     * build — including all the way back to "unknown".
     */
    @Test
    public void metadataKeysExtType_isTheV4PublishedValue() {
        final MlsConfig def = MlsConfig.defaults();
        assertEquals("RCC.16 v4.0 §7.11.10.1 publishes group_metadata_keys_requested = 0xF007",
                0xF007, def.metadataKeysExtType);
        assertTrue(def.metadataKeysExtKnown());

        // A capture that disagrees with the document still wins, with no build.
        final MlsConfig other = MlsConfig.from(
                new MapSource().set(MlsConfig.KEY_METADATA_KEYS_EXT, 0xF00B));
        assertTrue(other.metadataKeysExtKnown());
        assertEquals(0xF00B, other.metadataKeysExtType);

        // And 0 still means UNKNOWN, so the un-evaluable path stays reachable.
        assertFalse(MlsConfig.from(new MapSource().set(MlsConfig.KEY_METADATA_KEYS_EXT, 0))
                .metadataKeysExtKnown());
    }

    /**
     * The default spec revision must be the one we have WIRE EVIDENCE for.
     *
     * <p>Tachyon accepted our v3.0-form {@code end_mls} and a Google Messages peer decrypted the result;
     * no v4.0 shape has ever been observed from it. A default of v4.0 would silently change bytes
     * on a path that currently works, on every device that took the update, with no error anywhere.
     */
    @Test
    public void rcc16Version_defaultsToV3_andResolvesUnknownValuesToV3() {
        assertEquals(30, MlsConfig.DEF_RCC16_VERSION);
        assertEquals(30, MlsConfig.from(new MapSource()).rcc16Version);
        assertEquals(40,
                MlsConfig.from(new MapSource().set(MlsConfig.KEY_RCC16_VERSION, 40)).rcc16Version);

        // The config layer passes the raw number through; the ENUM is where an unrecognised value
        // is caught, so that a typo'd sysprop lands on the revision we have proven rather than on
        // whatever happens to be adjacent.
        assertEquals(Rcc16Version.V3_0, Rcc16Version.fromWire(30));
        assertEquals(Rcc16Version.V4_0, Rcc16Version.fromWire(40));
        for (final int bogus : new int[] {0, 3, 4, 31, 39, 41, 400, -1}) {
            assertEquals("an unrecognised RCC.16 version must resolve to V3_0, never to V4_0",
                    Rcc16Version.V3_0, Rcc16Version.fromWire(bogus));
        }
    }

    /** An absent key must yield the default, not zero — a missing sysprop is "unset", not "off". */
    @Test
    public void absentKeys_fallBackToDefaults() {
        final MlsConfig c = MlsConfig.from(new MapSource().set(MlsConfig.KEY_KP_COUNT, 6));
        assertEquals(6, c.kpPoolCount);
        assertEquals(MlsConfig.DEF_ERA_YIELD_LOOKS, c.eraYieldLooks);
        assertEquals(MlsConfig.DEF_KP_MIN_REMAINING_DAYS, c.kpMinRemainingDays);
    }

    /**
     * The two knobs Stage 6 added, at their defaults and through the Source.
     *
     * <p>{@code ftdMaxAttempts} defaults to {@link MlsFtdEscalation#MAX_FTD_ATTEMPTS} rather than to
     * a 5 typed here, so raising the spec cap cannot leave the default behind. {@code
     * identityRefreshMs} is asserted in MILLISECONDS against a key expressed in DAYS — the unit
     * conversion is the part that can silently be wrong by a factor of 86,400,000.
     */
    @Test
    public void stage6Knobs_defaultsAndUnits() {
        final MlsConfig d = MlsConfig.defaults();
        assertEquals(MlsFtdEscalation.MAX_FTD_ATTEMPTS, d.ftdMaxAttempts);
        assertEquals(7L * 24L * 3600_000L, d.identityRefreshMs);

        final MlsConfig over = MlsConfig.from(new MlsConfig.Source() {
            @Override public boolean getBoolean(final String k, final boolean v) { return v; }
            @Override public int getInt(final String k, final int v) {
                return MlsConfig.KEY_FTD_MAX_ATTEMPTS.equals(k) ? 13 : v;
            }
            @Override public long getLong(final String k, final long v) {
                return MlsConfig.KEY_IDENTITY_REFRESH_DAYS.equals(k) ? 1L : v;
            }
        });
        assertEquals(13, over.ftdMaxAttempts);
        assertEquals(24L * 3600_000L, over.identityRefreshMs);
        assertTrue(over.toString(), over.toString().contains("ftdMaxAttempts=13"));
    }

}
