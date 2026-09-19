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
package com.android.messaging.rcs.e2ee;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsTelemetry;
import com.android.messaging.util.LogUtil;

import java.util.Map;
import java.util.TreeMap;

/**
 * The production {@link MlsTelemetry} sink: an in-process tally, mirrored to logcat.
 *
 * <p>Rework item 13.3. We have no metrics backend, and standing one up is not what the counters are
 * for — the spec's point is that counters are a <em>cheaper CI channel than logcat</em>, because
 * asserting "{@code MaxLoopReached} stayed at zero" beats scraping text for it. A tally that a test
 * can read directly, plus a line for a human reading a capture, delivers that today; a real backend
 * can replace this class without touching a single call site.
 *
 * <p>Counts are per-process and reset on restart. That is fine for what they are used for (one test
 * run, one capture) and deliberately not fixed here — persisting them would make the sink a storage
 * component, which is exactly the sort of scope creep the six-port rule exists to prevent.
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
