/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.messaging.datamodel.action;

import android.app.IntentService;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DataModelException;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.LoggingTimer;
import com.android.messaging.util.WakeLockHelper;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

import com.android.messaging.R;

/**
 * Background worker service is an initial example of a background work queue handler
 * Used to actually "send" messages which may take some time and should not block ActionService
 * or UI
 */
public class BackgroundWorkerService extends IntentService {
    private static final String TAG = LogUtil.BUGLE_DATAMODEL_TAG;
    private static final boolean VERBOSE = false;
    private static final String CHANNEL_ID = "messaging_channel";

    private static final String WAKELOCK_ID = "bugle_background_worker_wakelock";
    @VisibleForTesting
    static WakeLockHelper sWakeLock = new WakeLockHelper(WAKELOCK_ID);

    private final ActionService mHost;

    public BackgroundWorkerService() {
        super("BackgroundWorker");
        mHost = DataModel.get().getActionService();
    }

    /**
     * Queue a list of requests from action service to this worker
     */
    public static void queueBackgroundWork(final List<Action> actions) {
        for (final Action action : actions) {
            startServiceWithAction(action, 0);
        }
    }

    // ops
    @VisibleForTesting
    protected static final int OP_PROCESS_REQUEST = 400;

    // extras
    @VisibleForTesting
    protected static final String EXTRA_OP_CODE = "op";
    @VisibleForTesting
    protected static final String EXTRA_ACTION = "action";
    @VisibleForTesting
    protected static final String EXTRA_ATTEMPT = "retry_attempt";

    /**
     * Queue action intent to the BackgroundWorkerService after acquiring wake lock
     */
    private static void startServiceWithAction(final Action action,
            final int retryCount) {
        final Intent intent = new Intent();
        intent.putExtra(EXTRA_ACTION, action);
        intent.putExtra(EXTRA_ATTEMPT, retryCount);
        startServiceWithIntent(OP_PROCESS_REQUEST, intent);
    }

    /**
     * Queue intent to the BackgroundWorkerService after acquiring wake lock
     */
    private static void startServiceWithIntent(final int opcode, final Intent intent) {
        final Context context = Factory.get().getApplicationContext();

        intent.setClass(context, BackgroundWorkerService.class);
        intent.putExtra(EXTRA_OP_CODE, opcode);
        sWakeLock.acquire(context, intent, opcode);
        if (VERBOSE) {
            LogUtil.v(TAG, "acquiring wakelock for opcode " + opcode);
        }

        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        createChannel();
        Context context = Factory.get().getApplicationContext();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.background_worker_notif))
                .build();
        int notifId = (int) System.currentTimeMillis() % 10000;
        startForeground(notifId, notification);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        if (intent == null) {
            // Shouldn't happen but sometimes does following another crash.
            LogUtil.w(TAG, "BackgroundWorkerService.onHandleIntent: Called with null intent");
            return;
        }
        final int opcode = intent.getIntExtra(EXTRA_OP_CODE, 0);
        sWakeLock.ensure(intent, opcode);

        try {
            switch(opcode) {
                case OP_PROCESS_REQUEST: {
                    final Action action = intent.getParcelableExtra(EXTRA_ACTION);
                    final int attempt = intent.getIntExtra(EXTRA_ATTEMPT, -1);
                    doBackgroundWork(action, attempt);
                    break;
                }

                default:
                    throw new RuntimeException("Unrecognized opcode in BackgroundWorkerService");
            }
        } finally {
            sWakeLock.release(intent, opcode);
        }
    }

    /**
     * Local execution of background work for action on ActionService thread
     */
    private void doBackgroundWork(final Action action, final int attempt) {
        action.markBackgroundWorkStarting();
        Bundle response = null;
        try {
            final LoggingTimer timer = new LoggingTimer(
                    TAG, action.getClass().getSimpleName() + "#doBackgroundWork");
            timer.start();

            response = action.doBackgroundWork();

            timer.stopAndLog();
            action.markBackgroundCompletionQueued();
            mHost.handleResponseFromBackgroundWorker(action, response);
        } catch (final Exception exception) {
            final boolean retry = false;
            LogUtil.e(TAG, "Error in background worker", exception);
            if (!(exception instanceof DataModelException)) {
                // DataModelException is expected (sort-of) and handled in handleFailureFromWorker
                // below, but other exceptions should crash ENG builds
                Assert.fail("Unexpected error in background worker - abort");
            }
            if (retry) {
                action.markBackgroundWorkQueued();
                startServiceWithAction(action, attempt + 1);
            } else {
                action.markBackgroundCompletionQueued();
                mHost.handleFailureFromBackgroundWorker(action, exception);
            }
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }

        Context context = Factory.get().getApplicationContext();
        String title = context.getString(R.string.notification_channel_title);
        NotificationChannel newChannel = new NotificationChannel(CHANNEL_ID,
                title, NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(newChannel);
    }
}
