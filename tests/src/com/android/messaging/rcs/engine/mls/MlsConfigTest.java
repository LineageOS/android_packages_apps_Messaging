/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link MlsConfig}: the operator knobs, readable from a host test through
 * {@link MlsConfig.Source}. See docs/testing.md.
 */
public class MlsConfigTest {

    /** A {@link MlsConfig.Source} over a literal map. */
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
            try { return v == null ? d
                    : Integer.parseInt(v); } catch (NumberFormatException e) { return d; }
        }

        @Override public long getLong(final String k, final long d) {
            final String v = mValues.get(k);
            try { return v == null ? d
                    : Long.parseLong(v); } catch (NumberFormatException e) { return d; }
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
     * RCC.16 A.4.1 SAN-to-MSISDN equality is enforced unless explicitly turned off; it is a
     * security control, not a preference.
     */
    @Test
    public void sanIdentityCheck_isEnforcedByDefault() {
        assertTrue(MlsConfig.defaults().sanIdentityCheck);
        assertTrue(MlsConfig.from(new MapSource().set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 1))
                .sanIdentityCheck);
        assertFalse("0 is the documented, unsupported bring-up escape hatch",
                MlsConfig.from(debugBuild().set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0))
                        .sanIdentityCheck);
    }

    /**
     * On a user build the knobs that relax a check or dump data are ignored: adb can set a
     * {@code debug.*} property there, but not {@code ro.debuggable}.
     */
    @Test
    public void onAUserBuildTheDebugKnobsAreIgnored() {
        final MapSource user = new MapSource()
                .set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0)
                .set(MlsConfig.KEY_DUMP_AAD, 1)
                .set(MlsConfig.KEY_DUMP_KP, 1);
        final MlsConfig c = MlsConfig.from(user);
        assertTrue("the SAN check stays on", c.sanIdentityCheck);
        assertFalse(c.dumpAad);
        assertFalse(c.dumpAadLive());
        assertFalse(c.dumpKeyPackages);
        assertFalse(c.dumpKeyPackagesLive());

        final MlsConfig d = MlsConfig.from(user.set("ro.debuggable", 1));
        assertFalse("and a debug build still honours them", d.sanIdentityCheck);
        assertTrue(d.dumpAad);
        assertTrue(d.dumpAadLive());
        assertTrue(d.dumpKeyPackages);
        assertTrue(d.dumpKeyPackagesLive());
    }

    private static MapSource debugBuild() {
        return new MapSource().set("ro.debuggable", 1);
    }

    @Test
    public void everyKnob_isReadFromTheSource() {
        final MlsConfig c = MlsConfig.from(debugBuild()
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
     * The pool is clamped above one: a pool of one is only the last-resort package, which every
     * adding peer would consume.
     */
    @Test
    public void kpPoolCount_isClampedToTheSmallestNonDegeneratePool() {
        for (final int given : new int[] {Integer.MIN_VALUE, -1, 0, 1, 2}) {
            final int got = MlsConfig.from(new MapSource().set(MlsConfig.KEY_KP_COUNT, given))
                    .kpPoolCount;
            assertEquals("pool " + given + " must clamp to " + MlsConfig.MIN_KP_POOL_TOTAL,
                    Math.max(MlsConfig.MIN_KP_POOL_TOTAL, given), got);
        }
        assertEquals(50,
                MlsConfig.from(new MapSource().set(MlsConfig.KEY_KP_COUNT, 50)).kpPoolCount);
    }

    /** Two configs with different knobs coexist in one JVM. */
    @Test
    public void twoInstances_holdDifferentKnobsSimultaneously() {
        final MlsConfig strict = MlsConfig.defaults();
        final MlsConfig lax = MlsConfig.from(debugBuild()
                .set(MlsConfig.KEY_SAN_IDENTITY_CHECK, 0)
                .set(MlsConfig.KEY_KP_COUNT, 3));
        assertTrue(strict.sanIdentityCheck);
        assertFalse(lax.sanIdentityCheck);
        assertEquals(MlsConfig.DEF_KP_POOL_TOTAL, strict.kpPoolCount);
        assertEquals(3, lax.kpPoolCount);
    }

    /**
     * The metadata-keys extension type is 0xF007 (RCC.16 v4.0 §7.11.10.1). On a debug build the
     * override survives, so an observed value that contradicts the document wins without a build.
     */
    @Test
    public void metadataKeysExtType_isTheV4PublishedValue() {
        final MlsConfig def = MlsConfig.defaults();
        assertEquals("RCC.16 v4.0 §7.11.10.1 publishes group_metadata_keys_requested = 0xF007",
                0xF007, def.metadataKeysExtType);
        assertTrue(def.metadataKeysExtKnown());

        // An observed value that disagrees with the document still wins, with no build.
        final MlsConfig other = MlsConfig.from(new MapSource().set("ro.debuggable", 1)
                .set(MlsConfig.KEY_METADATA_KEYS_EXT, 0xF00B));
        assertTrue(other.metadataKeysExtKnown());
        assertEquals(0xF00B, other.metadataKeysExtType);

        // 0 still means unknown, so the un-evaluable path stays reachable.
        assertFalse(MlsConfig.from(new MapSource().set("ro.debuggable", 1)
                .set(MlsConfig.KEY_METADATA_KEYS_EXT, 0)).metadataKeysExtKnown());
    }

    /** A user build ignores the override: adb can set a debug.* property there. */
    @Test
    public void metadataKeysExtType_ignoresTheOverrideOnAUserBuild() {
        for (final int v : new int[] {0xF00B, 0}) {
            final MlsConfig c = MlsConfig.from(new MapSource().set("ro.debuggable", 0)
                    .set(MlsConfig.KEY_METADATA_KEYS_EXT, v));
            assertEquals(MlsConfig.DEF_METADATA_KEYS_EXT, c.metadataKeysExtType);
            assertTrue(c.metadataKeysExtKnown());
        }
    }

    /**
     * The default spec revision is v3.0, the one peers are known to accept for {@code end_mls}; a
     * v4.0 default would change bytes on a working path with no error.
     */
    @Test
    public void rcc16Version_defaultsToV3_andResolvesUnknownValuesToV3() {
        assertEquals(30, MlsConfig.DEF_RCC16_VERSION);
        assertEquals(30, MlsConfig.from(new MapSource()).rcc16Version);
        assertEquals(40,
                MlsConfig.from(new MapSource().set(MlsConfig.KEY_RCC16_VERSION, 40)).rcc16Version);

        // The config passes the raw number through; the enum maps an unrecognised value to the
        // default revision rather than to whatever is adjacent.
        assertEquals(Rcc16Version.V3_0, Rcc16Version.fromWire(30));
        assertEquals(Rcc16Version.V4_0, Rcc16Version.fromWire(40));
        for (final int bogus : new int[] {0, 3, 4, 31, 39, 41, 400, -1}) {
            assertEquals("an unrecognised RCC.16 version must resolve to V3_0, never to V4_0",
                    Rcc16Version.V3_0, Rcc16Version.fromWire(bogus));
        }
    }

    /** An absent key yields the default, not zero: a missing sysprop is unset, not off. */
    @Test
    public void absentKeys_fallBackToDefaults() {
        final MlsConfig c = MlsConfig.from(new MapSource().set(MlsConfig.KEY_KP_COUNT, 6));
        assertEquals(6, c.kpPoolCount);
        assertEquals(MlsConfig.DEF_ERA_YIELD_LOOKS, c.eraYieldLooks);
        assertEquals(MlsConfig.DEF_KP_MIN_REMAINING_DAYS, c.kpMinRemainingDays);
    }

    /**
     * {@code ftdMaxAttempts} defaults to {@link MlsFtdEscalation#MAX_FTD_ATTEMPTS}, and
     * {@code identityRefreshMs} is asserted in milliseconds against a key expressed in days.
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
