package com.android.messaging.metrics;

import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import com.android.messaging.sms.MmsUtils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MetricsTracker {
    private static final String SEND_FAILED_ACTION = "send_failed";
    private static final String SEND_SUCCESS_ACTION = "send_success";
    private static final String SMS_CATEGORY = "sms";
    private static final String MMS_CATEGORY = "mms";
    private static final String MMS_GROUP_CATEGORY = "mms_group";

    public static final String TAG = "Metrics";

    private final Context mContext;

    private static volatile MetricsTracker mInstance;
    private static final Executor EXECUTOR = AsyncTask.SERIAL_EXECUTOR;

    public static MetricsTracker getInstance(Context context) {
        if (mInstance == null) {
            synchronized (MetricsTracker.class) {
                if (mInstance == null) {
                    mInstance = new MetricsTracker(context.getApplicationContext());
                }
            }
        }
        return mInstance;
    }

    private MetricsTracker(Context context) {
        this.mContext = context.getApplicationContext();
    }

    private void writeEventAsync(final Event event) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                writeEvent(event);
            }
        });
    }

    private void writeEvent(Event event) {
        MetricsDatabase metricsDatabase = MetricsDatabase.getInstance(mContext);
        metricsDatabase.insert(event);
        Log.i(TAG, event.toString() + " event saved to database");
    }

    private void trackSmsSentFailedAsync() {
        writeEventAsync(new Event(SMS_CATEGORY, SEND_FAILED_ACTION));
    }

    private void trackSmsSentSuccessAsync() {
        writeEventAsync(new Event(SMS_CATEGORY, SEND_SUCCESS_ACTION));
    }

    private void trackMmsSentFailed() {
        writeEvent(new Event(MMS_CATEGORY, SEND_FAILED_ACTION));
    }

    private void trackMmsSentSuccess() {
        writeEvent(new Event(MMS_CATEGORY, SEND_SUCCESS_ACTION));
    }

    private void trackGroupMmsSentSuccess() {
        writeEvent(new Event(MMS_GROUP_CATEGORY, SEND_SUCCESS_ACTION));
    }

    private void trackGroupMmsSentFailed() {
        writeEvent(new Event(MMS_GROUP_CATEGORY, SEND_FAILED_ACTION));
    }

    private void trackMmsSent(boolean success, boolean isGroupMessage) {
        if (isGroupMessage) {
            if (success) {
                trackGroupMmsSentSuccess();
            } else {
                trackGroupMmsSentFailed();
            }
        } else {
            if (success) {
                trackMmsSentSuccess();
            } else {
                trackMmsSentFailed();
            }
        }
    }

    public void trackMmsSentAsync(final boolean success, final boolean isGroupMessage) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                trackMmsSent(success, isGroupMessage);
            }
        });
    }

    public void trackSmsSentAsync(boolean success) {
        if (success) {
            trackSmsSentSuccessAsync();
        } else {
            trackSmsSentFailedAsync();
        }
    }
}
