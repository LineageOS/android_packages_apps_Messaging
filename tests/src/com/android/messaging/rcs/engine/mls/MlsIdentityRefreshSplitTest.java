/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.KEY;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsIdentityRefreshSplitTest {


    private static FakeShellPort identityPort(final FakePrefs prefs, final boolean refreshes) {
        return new FakeShellPort().returns("prefs", prefs)
                .returns("refreshIdentityFromProvider", refreshes);
    }

    @Test
    public void aDueRefreshStampsSuccessOrItsOwnRetryBackoff() {
        final FakePrefs ok = new FakePrefs();
        MlsIdentityRefresh.maybeRefreshIdentity(MlsConfig.defaults(), identityPort(ok, true).port(),
                MlsLogSink.NONE);
        assertNotEquals(0L, ok.getLong("last_identity_refresh_ms", 0L));
        assertFalse(ok.contains("last_identity_attempt_ms"));
        final FakePrefs failed = new FakePrefs();
        MlsIdentityRefresh.maybeRefreshIdentity(MlsConfig.defaults(),
                identityPort(failed, false).port(), MlsLogSink.NONE);
        assertEquals(0L, failed.getLong("last_identity_refresh_ms", 0L));
        assertNotEquals(0L, failed.getLong("last_identity_attempt_ms", 0L));
    }

    @Test
    public void aRecentRefreshOrARecentFailureIsNotRetried() {
        for (final String key : new String[] {"last_identity_refresh_ms",
                "last_identity_attempt_ms"}) {
            final FakePrefs p = new FakePrefs();
            p.edit().putLong(key, System.currentTimeMillis()).apply();
            final FakeShellPort f = identityPort(p, true);
            MlsIdentityRefresh.maybeRefreshIdentity(MlsConfig.defaults(), f.port(),
                    MlsLogSink.NONE);
            assertFalse(f.calls.contains("refreshIdentityFromProvider()"));
        }
    }


    private static int firstCall(final FakeShellPort f, final String name) {
        for (int i = 0; i < f.calls.size(); i++) if (
                f.calls.get(i).startsWith(name + "(")) return i;
        return -1;
    }

    @Test
    public void aChangedIdentityDropsTheSessionBeforeReopeningAndForcesTheRepublish() {
        final FakePrefs p = new FakePrefs();
        p.edit().putLong("last_kp_publish_ms", 5L).apply();
        final FakeShellPort f =
                identityPort(p, true).on("dropSession", a -> null).returns("ensureSession", true);
        MlsIdentityRefresh.onIdentityChanged(MlsConfig.defaults(), f.port(), MlsLogSink.NONE,
                "test");
        assertTrue(firstCall(f, "dropSession") >= 0);
        assertTrue(firstCall(f, "dropSession") < firstCall(f, "ensureSession"));
        assertNotEquals(0L, p.getLong("last_identity_refresh_ms", 0L));
        assertFalse(p.contains("last_kp_publish_ms"));
    }

    @Test
    public void aFailedRefreshKeepsTheOldSessionAndClearsTheStampsForTheNextBringUp() {
        final FakePrefs p = new FakePrefs();
        p.edit().putLong("last_identity_refresh_ms", 5L).putLong("last_identity_attempt_ms", 6L)
                .apply();
        final FakeShellPort f = identityPort(p, false);
        final FakeShellPort.Log log = new FakeShellPort.Log();
        MlsIdentityRefresh.onIdentityChanged(MlsConfig.defaults(), f.port(), log, "test");
        assertTrue(log.said("W", "NOT publishing the pool under the OLD leaf"));
        assertEquals(-1, firstCall(f, "dropSession"));
        assertFalse(p.contains("last_identity_refresh_ms"));
        assertFalse(p.contains("last_identity_attempt_ms"));
    }
}
