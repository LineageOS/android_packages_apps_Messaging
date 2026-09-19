/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import org.junit.Test;

public final class MlsMetricsStateWriteTest {


    @Test
    public void noteStateWriteBucketsANonZeroWriteAndIgnoresNoSession() {
        final FakeShellPort f = new FakeShellPort();
        final MlsTelemetry t = f.stub(MlsTelemetry.class, "count",
                (java.util.function.Function<Object[], Object>) a -> null);
        f.returns("telemetry", t);
        f.returns("session", f.stub(MlsSession.class, "takeStateWriteBytes", 4096L));
        MlsMetrics.noteStateWrite(f.port());
        assertTrue(f.calls.contains("MlsTelemetry.count"));

        final FakeShellPort none = new FakeShellPort().returns("session", null);
        MlsMetrics.noteStateWrite(none.port());   // no session: nothing to read, nothing counted

        final FakeShellPort zero = new FakeShellPort();
        zero.returns("telemetry", zero.stub(MlsTelemetry.class));
        zero.returns("session", zero.stub(MlsSession.class, "takeStateWriteBytes", 0L));
        MlsMetrics.noteStateWrite(zero.port());
        assertFalse(zero.calls.contains("MlsTelemetry.count"));
    }
}
