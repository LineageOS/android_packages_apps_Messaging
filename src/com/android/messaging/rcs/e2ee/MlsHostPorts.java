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

import android.content.Context;

import com.android.messaging.rcs.engine.mls.MlsLog;
import com.android.messaging.rcs.engine.mls.MlsMessageContent;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsPorts;
import com.android.messaging.rcs.engine.mls.StoreRead;
import com.android.messaging.util.LogUtil;

/**
 * The host side of the engine boundary — rework item 1.1.
 *
 * <p>Builds the {@link MlsPorts} bundle the app hands to {@link
 * com.android.messaging.rcs.engine.mls.MlsEngine#startSession}. This class is where the
 * application's world (a Context, the message store) stops; nothing past it crosses the boundary
 * except through one of the six ports.
 *
 * <p>Item 1.4's split lands here: the {@link MlsMessageContent} <em>type</em> is a protocol fact and
 * lives in the engine module, while the <em>query</em> is an application fact and lives in this
 * layer. Of the three {@link MlsPorts.MessageAccessor} methods, only {@code generateMessageId} is
 * implemented today — it is fully specified. The two that read message rows need the
 * sibling-message join and per-client resend counts, which is real work against
 * {@code RcsMessageStore} and is scheduled with the outbound stage; until then they answer
 * {@code NotFound}, which the spec is explicit is a NORMAL answer the engine handles, rather than a
 * fabricated row.
 */
public final class MlsHostPorts {
    private static final String TAG = MlsLog.TAG;

    /** The bundle for the production engine: engine-owned storage, host clock and telemetry. */
    public static MlsPorts forApp(final Context ctx) {
        return MlsPorts.withEngineOwnedStorage(
                new AppMessageAccessor(ctx), MlsPorts.SYSTEM_CLOCK, LogcatMlsTelemetry.get());
    }

    /** Where the engine's own state lives. The engine takes a path, never a Context (item 1.1). */
    public static String storageRoot(final Context ctx) {
        return ctx.getFilesDir().getAbsolutePath();
    }

    private static final class AppMessageAccessor implements MlsPorts.MessageAccessor {
        @SuppressWarnings("unused")   // held for the message-store query, item 1.4's second half
        private final Context mCtx;

        AppMessageAccessor(final Context ctx) { mCtx = ctx.getApplicationContext(); }

        @Override public StoreRead<MlsMessageContent> getMessageContent(final String messageId) {
            // NotFound, not Err: the engine treats an absent row as normal, and reporting Err here
            // would make a missing implementation look like a storage failure to a caller whose
            // recovery ladder branches on the difference.
            LogUtil.i(TAG, "MlsHostPorts: getMessageContent(" + messageId + ") — the message-store "
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
