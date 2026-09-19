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
import com.android.messaging.util.LogUtil;

import java.util.concurrent.TimeUnit;

/**
 * The MLS operation retry queue's carrier (rework item 5.6).
 *
 * <p>Deliberately thin. Every decision it makes — the delay, the bounds, whether an outcome
 * schedules a follow-up — comes from {@link MlsRetryPolicy}, which is pure and host-tested, because
 * that is where the two rules a reimplementer gets wrong live. This class does scheduling plumbing
 * and nothing else.
 *
 * <h2>What must NOT be "improved" here</h2>
 *
 * <ul>
 *   <li><b>{@link ExistingWorkPolicy#APPEND_OR_REPLACE}, never a unique-per-conversation key.</b>
 *       Deduplicating work for the same group is the obvious optimisation and it would remove the
 *       idempotence mechanism: convergence is DETECTED by an extra run finding {@code NO_OP}
 *       (§19.3-29). Duplicate runs are the design, not a leak.</li>
 *   <li><b>{@link BackoffPolicy#LINEAR}.</b> WorkManager's default is EXPONENTIAL and every other
 *       backoff in this tree is too, so this is the one line most likely to be "corrected" back
 *       into a bug (§19.3-32).</li>
 * </ul>
 *
 * <p>The other existing worker in the fleet, {@code MlsRefreshWorker}, does both of the forbidden
 * things — it dedups by unique name and takes WorkManager's exponential default. It is a different
 * queue (key-package refresh, not the operation drain) so it is not itself wrong, but it must not be
 * copied as a template for this one.
 */
public final class MlsRetryWorker extends Worker {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * Named by worker TYPE, not by group — so appended work for different conversations shares one
     * chain and nothing is collapsed by identity.
     */
    private static final String WORK_NAME = "mls-op-retry";

    private static final String KEY_GROUP_ID = "group_id";
    private static final String KEY_MSISDN = "msisdn";
    private static final String KEY_ATTEMPT = "immediate_retry_attempt";
    private static final String KEY_SUB_ID = "sub_id";

    public MlsRetryWorker(@NonNull final Context ctx, @NonNull final WorkerParameters params) {
        super(ctx, params);
    }

    /**
     * Enqueue one {@code (group_id, msisdn)} retry.
     *
     * <p>Callers must check {@link MlsSchedulingType#allowsScheduling()} first — a pass already
     * inside a retry flow scheduling another is the loop invariant 44 exists to break.
     */
    public static void enqueue(final Context ctx, final int subId, final String groupId,
            final String msisdn, final int attempt) {
        if (MlsRetryPolicy.attemptsExhausted(attempt)) {
            LogUtil.w(TAG, "MlsRetryWorker: NOT enqueuing " + groupId + "/" + msisdn
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
                // LINEAR. See the class doc — the default is exponential and that is a bug here.
                .setBackoffCriteria(BackoffPolicy.LINEAR,
                        MlsRetryPolicy.DEFAULT_RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                // The per-tranche delay is applied BEFORE every tranche, the first included.
                .setInitialDelay(item.delayMs(), TimeUnit.MILLISECONDS)
                .build();
        // APPEND_OR_REPLACE, not a unique key per conversation. See the class doc.
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

        // ONE row per batch, asserted at execution (§8.6). A later change that widens the unit
        // would otherwise quietly serialise unrelated conversations behind each other.
        final int rows = 1;
        if (rows != MlsRetryPolicy.MAX_ROWS_PER_BATCH) {
            throw new IllegalStateException("MLS retry drain is not a bulk operation: "
                    + rows + " rows, expected " + MlsRetryPolicy.MAX_ROWS_PER_BATCH);
        }

        final MlsDriveLoop.Result drive;
        try {
            drive = t.driveReconcile(emptyToNull(groupId), msisdn);
        } catch (final Throwable fatal) {
            // A throw here is a defect, not a transient failure. Retrying it would burn the whole
            // attempt ladder re-running the same broken pass and then report the bound instead of
            // the cause.
            LogUtil.e(TAG, "MlsRetryWorker: the drive threw for " + groupId + "/" + msisdn
                    + " — failing this item rather than retrying a broken pass", fatal);
            return Result.failure();
        }

        // Queued, not inline: the same FAIL_RETRY that would schedule nothing on the inline path
        // does schedule here. That asymmetry is §19.3-33 and is deliberate.
        final MlsRetryPolicy.Outcome outcome =
                MlsRetryPolicy.outcomeOf(drive.status(), /* inline= */ false);
        LogUtil.i(TAG, "MlsRetryWorker: " + groupId + "/" + msisdn + " attempt=" + attempt
                + " → " + drive.status() + " / " + outcome);

        if (MlsRetryPolicy.schedulesFollowUp(outcome)) {
            enqueue(getApplicationContext(), subId, groupId, msisdn, attempt + 1);
        }
        // Success from WorkManager's point of view either way: we schedule our own follow-up with
        // our own linear ladder, so returning retry() here would stack WorkManager's backoff on top
        // of ours and the two would compound.
        return outcome == MlsRetryPolicy.Outcome.FAILED ? Result.failure() : Result.success();
    }

    private static String emptyToNull(final String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
