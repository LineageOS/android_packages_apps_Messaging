/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsLogSink;
import com.android.messaging.rcs.engine.mls.MlsMessageContent;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsPorts;
import com.android.messaging.rcs.engine.mls.StoreRead;
import com.android.messaging.util.LogUtil;

/**
 * The host side of the engine boundary: builds the {@link MlsPorts} bundle the app passes to
 * {@link com.android.messaging.rcs.engine.mls.MlsEngine#startSession}. Nothing app-side crosses the
 * boundary except through a port. See docs/mls/overview.md.
 *
 * <p>The message accessor implements {@code generateMessageId} only; the two row reads answer
 * {@code NotFound}, which the engine handles as a normal answer.
 */
public final class MlsHostPorts {
    private static final String TAG = MlsLog.TAG;

    /** The bundle for the production engine: engine-owned storage, host clock and telemetry. */
    public static MlsPorts forApp(final Context ctx) {
        return MlsPorts.withEngineOwnedStorage(
                new AppMessageAccessor(ctx), MlsPorts.SYSTEM_CLOCK, LogcatMlsTelemetry.get());
    }

    /**
     * The app's {@link MlsLogSink}: forwards to {@code LogUtil} under {@link MlsLog#TAG}, so engine
     * and transport lines are identical.
     */
    public static MlsLogSink logSink() {
        return LOG_SINK;
    }

    private static final MlsLogSink LOG_SINK = new MlsLogSink() {
        @Override public void d(final String msg) { LogUtil.d(TAG, msg); }
        @Override public void i(final String msg) { LogUtil.i(TAG, msg); }
        @Override public void w(final String msg) { LogUtil.w(TAG, msg); }
        @Override public void w(final String msg, final Throwable t) { LogUtil.w(TAG, msg, t); }
        @Override public void e(final String msg) { LogUtil.e(TAG, msg); }
        @Override public void e(final String msg, final Throwable t) { LogUtil.e(TAG, msg, t); }
    };

    /** Where the engine keeps its state. The engine takes a path, never a Context. */
    public static String storageRoot(final Context ctx) {
        return ctx.getFilesDir().getAbsolutePath();
    }

    private static final class AppMessageAccessor implements MlsPorts.MessageAccessor {
        @SuppressWarnings("unused")   // for the message-store query, not yet implemented
        private final Context mCtx;

        AppMessageAccessor(final Context ctx) { mCtx = ctx.getApplicationContext(); }

        @Override public StoreRead<MlsMessageContent> getMessageContent(final String messageId) {
            // NotFound, not Err: the engine treats an absent row as normal, while Err would read as
            // a storage failure to a recovery ladder that branches on the difference.
            LogUtil.i(TAG, "MlsHostPorts: getMessageContent(" + MlsMessageId.forLog(messageId)
                    + ") — the message-store "
                    + "query is not implemented yet (item 1.4); answering NotFound");
            return StoreRead.notFound();
        }

        @Override public StoreRead<MlsMessageContent.ImdnState> getDeliveryStatusAsImdn(
                final String messageId, final String msisdn) {
            return StoreRead.notFound();
        }

        @Override public StoreRead.Required<String> generateMessageId() {
            return StoreRead.Required.ok(MlsMessageId.generate());
        }
    }

    private MlsHostPorts() {}
}
