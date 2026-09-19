/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.util.Log;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsMessageContent;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsPorts;
import com.android.messaging.rcs.engine.mls.MlsTelemetry;
import com.android.messaging.rcs.engine.mls.StoreRead;

/**
 * The engine ports the self-test runs on. The app's {@code MlsHostPorts} is not reused because it
 * depends on app utilities that do not belong in this APK.
 *
 * <p>The message accessor answers {@code notFound} for everything: the self-test never reads a
 * message row, and the engine treats an absent row as normal where an error would read as a storage
 * failure.
 */
final class SelfTestPorts {

    private SelfTestPorts() {}

    static MlsPorts forSelfTest(final Context ctx) {
        return MlsPorts.withEngineOwnedStorage(
                new NoMessageStore(), MlsPorts.SYSTEM_CLOCK, new LogTelemetry());
    }

    static String storageRoot(final Context ctx) {
        return ctx.getFilesDir().getAbsolutePath();
    }

    /** No message store: this harness never reads one. */
    private static final class NoMessageStore implements MlsPorts.MessageAccessor {
        @Override
        public StoreRead<MlsMessageContent> getMessageContent(final String messageId) {
            return StoreRead.notFound();
        }

        @Override
        public StoreRead<MlsMessageContent.ImdnState> getDeliveryStatusAsImdn(
                final String messageId, final String msisdn) {
            return StoreRead.notFound();
        }

        @Override
        public StoreRead.Required<String> generateMessageId() {
            return StoreRead.Required.ok(MlsMessageId.generate());
        }
    }

    /** Counters to logcat; the only reader is a person watching a self-test run. */
    private static final class LogTelemetry implements MlsTelemetry {
        @Override
        public void count(final String metric) {
            count(metric, 1);
        }

        @Override
        public void count(final String metric, final int value) {
            Log.i(MlsLog.TAG, "selftest metric " + metric + " += " + value);
        }
    }
}
