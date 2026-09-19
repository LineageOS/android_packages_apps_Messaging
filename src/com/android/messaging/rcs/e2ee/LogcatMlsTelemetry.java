/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsTelemetry;
import com.android.messaging.util.LogUtil;

import java.util.Map;
import java.util.TreeMap;

/**
 * The production {@link MlsTelemetry} sink: an in-process tally, mirrored to logcat, that a test
 * can read directly. Counts are per process and reset on restart.
 */
public final class LogcatMlsTelemetry implements MlsTelemetry {
    private static final String TAG = MlsLog.TAG;

    private static final LogcatMlsTelemetry INSTANCE = new LogcatMlsTelemetry();

    /** {@code metric} → running total; {@code metric:value} → count for that bucket. */
    private final Map<String, Long> mCounts = new TreeMap<>();

    private LogcatMlsTelemetry() {}

    public static LogcatMlsTelemetry get() { return INSTANCE; }

    @Override public void count(final String metric) {
        bump(metric);
        LogUtil.i(TAG, "METRIC " + metric);
    }

    @Override public void count(final String metric, final int value) {
        bump(metric);
        bump(metric + ":" + value);
        LogUtil.i(TAG, "METRIC " + metric + "=" + value);
    }

    private synchronized void bump(final String key) {
        final Long prev = mCounts.get(key);
        mCounts.put(key, (prev == null ? 0L : prev) + 1L);
    }

    /** Snapshot of every counter, for a test assertion or a debug dump. */
    public synchronized Map<String, Long> snapshot() {
        return new TreeMap<>(mCounts);
    }

    /** Total recorded against {@code key}; {@code 0} if never recorded. */
    public synchronized long total(final String key) {
        final Long v = mCounts.get(key);
        return v == null ? 0L : v;
    }

    public synchronized void reset() { mCounts.clear(); }

    @Override public synchronized String toString() { return "MlsTelemetry" + mCounts; }
}
