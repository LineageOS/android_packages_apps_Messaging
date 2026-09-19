/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.android.messaging.rcs.engine.mls.MlsDriveLoop;
import com.android.messaging.rcs.engine.mls.MlsRetryPolicy;
import com.android.messaging.rcs.engine.mls.MlsSchedulingType;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.concurrent.TimeUnit;

/**
 * Carries the MLS operation retry queue. Every decision (delay, bounds, whether an outcome
 * schedules a follow-up) comes from {@link MlsRetryPolicy}; this class only schedules. Two choices
 * here are deliberate:
 * <ul>
 *   <li>{@link ExistingWorkPolicy#APPEND_OR_REPLACE} under one name, never a unique key per
 *       conversation: convergence is detected by an extra run finding {@code NO_OP}, so duplicate
 *       runs are intended.</li>
 *   <li>{@link BackoffPolicy#LINEAR}, not WorkManager's exponential default.</li>
 * </ul>
 * See docs/mls/health-and-recovery.md.
 */
public final class MlsRetryWorker extends Worker {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** Named by worker type, not by group, so work for different conversations shares one chain. */
    private static final String WORK_NAME = "mls-op-retry";

    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_MSISDN = "msisdn";
    private static final String KEY_ATTEMPT = "immediate_retry_attempt";
    private static final String KEY_SUB_ID = "sub_id";

    public MlsRetryWorker(@NonNull final Context ctx, @NonNull final WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Enqueue one {@code (group_id, msisdn)} retry. Callers check
     * {@link MlsSchedulingType#allowsScheduling()} first, so a retry flow cannot schedule another.
     */
    public static void enqueue(final Context ctx, final int subId, final String groupId,
            final String msisdn, final int attempt) {
        if (MlsRetryPolicy.attemptsExhausted(attempt)) {
            LogUtil.w(TAG, "MlsRetryWorker: NOT enqueuing " + groupId + "/" + LogMask.number(msisdn)
                    + " — attempt " + attempt + " reaches the outermost bound ("
                    + MlsRetryPolicy.MAX_ATTEMPTS_PER_ITEM + "). Giving up on this item.");
            return;
        }
        final MlsRetryPolicy.WorkItem item =
                new MlsRetryPolicy.WorkItem(groupId, msisdn, attempt);
        final Data data = new Data.Builder()
                .putString(KEY_GROUP_ID, item.groupId)
                .putString(KEY_MSISDN, item.msisdn)
                .putInt(KEY_ATTEMPT, item.immediateRetryAttempt)
                .putInt(KEY_SUB_ID, subId)
                .build();
        final OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(MlsRetryWorker.class)
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.LINEAR,
                        MlsRetryPolicy.DEFAULT_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                // The per-tranche delay applies before every tranche, the first included.
                .setInitialDelay(item.delayMs(), TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req);
        LogUtil.i(TAG, "MlsRetryWorker: enqueued " + item);
    }

    @NonNull
    @Override
    public Result doWork() {
        final String groupId = getInputData().getString(KEY_GROUP_ID);
        final String msisdn = getInputData().getString(KEY_MSISDN);
        final int attempt = getInputData().getInt(KEY_ATTEMPT, 1);
        final int subId = getInputData().getInt(KEY_SUB_ID, -1);
        if (msisdn == null || msisdn.isEmpty()) {
            LogUtil.w(TAG, "MlsRetryWorker: no msisdn in the payload — nothing to drive");
            return Result.failure();
        }

        final MlsProviderTransport t =
                MlsProviderTransport.get(getApplicationContext(), subId);

        // One row per batch, checked at execution, so a wider unit cannot serialise unrelated
        // conversations behind each other.
        final int rows = 1;
        if (rows != MlsRetryPolicy.MAX_ROWS_PER_BATCH) {
            throw new IllegalStateException("MLS retry drain is not a bulk operation: "
                    + rows + " rows, expected " + MlsRetryPolicy.MAX_ROWS_PER_BATCH);
        }

        final MlsDriveLoop.Result drive;
        try {
            drive = t.driveReconcile(emptyToNull(groupId), msisdn);
        } catch (final Throwable fatal) {
            // A throw is a defect, not a transient failure; retrying would burn the ladder and
            // report the bound instead of the cause.
            LogUtil.e(TAG, "MlsRetryWorker: the drive threw for " + groupId + "/"
                    + LogMask.number(msisdn) + " — failing it, not retrying a broken pass", fatal);
            return Result.failure();
        }

        // Queued rather than inline: here FAIL_RETRY does schedule a follow-up.
        final MlsRetryPolicy.Outcome outcome =
                MlsRetryPolicy.outcomeOf(drive.status(), /* inline= */ false);
        LogUtil.i(TAG, "MlsRetryWorker: " + groupId + "/" + LogMask.number(msisdn) + " attempt="
                + attempt + " → " + drive.status() + " / " + outcome);

        if (MlsRetryPolicy.schedulesFollowUp(outcome)) {
            enqueue(getApplicationContext(), subId, groupId, msisdn, attempt + 1);
        }
        // Success either way: the follow-up uses our own linear ladder, and retry() would compound
        // WorkManager's backoff with it.
        return outcome == MlsRetryPolicy.Outcome.FAILED ? Result.failure() : Result.success();
    }

    private static String emptyToNull(final String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
