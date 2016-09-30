/*====*====*====*====*====*====*====*====*====*====*====*====*====*====*====*
 *  Copyright (c) 2016 Cyanogen Inc.
 *     All Rights Reserved.
 *     Cyanogen Confidential and Proprietary.
 * =========================================================================*/

package com.cyanogenmod.messaging.util;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.AsyncTask;
import android.util.Log;

import com.cyanogen.ambient.common.ConnectionResult;
import com.cyanogen.ambient.common.ConnectionResult;
import com.cyanogen.ambient.common.api.AmbientApiClient;
import com.cyanogen.ambient.analytics.AnalyticsServices;
import com.cyanogen.ambient.analytics.Event;
import com.cyanogen.ambient.common.api.Result;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * MetricsJob is an aggregation and shipping service that is fired
 * once every 24 hours to pass Metrics to ModCore's analytics service.
 */
public final class MetricsJob extends JobService {

    private static final String TAG = "MetricsJob";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final long TIMEOUT_MILLIS = 1000;

    public static final int METRICS_JOB_ID = 1441;

    private MetricsTask mUploadTask;

    private AmbientApiClient ambientApiClient;

    public MetricsJob() {
        super();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (DEBUG) Log.v(TAG, "sending events");

        // AmbientClient
        ambientApiClient = new AmbientApiClient.Builder(this)
                .addApi(AnalyticsServices.API)
                .addOnConnectionFailedListener(new AmbientApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        Log.e(TAG, "Failed to connect to Ambient. reason: " + result.getErrorCode());
                    }
                }).build();

        // Send stored Specific events
        mUploadTask = new MetricsTask(params);
        mUploadTask.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void) null);

        // Running on another thread, return true.
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {

        if (ambientApiClient != null && (ambientApiClient.isConnected() || ambientApiClient.isConnecting())) {
            ambientApiClient.disconnect();
        }

        // Cancel our async task
        mUploadTask.cancel(true);

        // report that we should try again soon.
        return true;
    }


    class MetricsTask extends AsyncTask<Void, Void, Boolean> {

        JobParameters mMetricsJobParams;

        public MetricsTask(JobParameters params) {
            this.mMetricsJobParams = params;
        }

        @Override
        protected Boolean doInBackground(Void... params) {

            HashMap<String, Event.Builder> eventsToSend
                    = MetricsHelper.getEventsToSend(MetricsJob.this);

            for (String key : eventsToSend.keySet()) {

                Event.Builder eventBuilder = eventsToSend.get(key);

                if (DEBUG) Log.v(TAG, "sending:" + eventBuilder.toString());

                if (isCancelled()) {
                    return false;
                }

                Result r = AnalyticsServices.AnalyticsApi.sendEvent(
                        ambientApiClient,
                        eventBuilder.build())
                        .await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

                // if any of our results were not successful, something is wrong.
                // Stop this job for now.
                if (!r.getStatus().isSuccess()) {
                    return false;
                }

                // We sent all the data we had for this event to the database. So clear it from our
                // SharedPreferences.
                MetricsHelper.clearEventData(MetricsJob.this, key);
            }
            return true;
        }

        @Override
        protected void onCancelled() {
            if (DEBUG) Log.w(TAG, "Messaging Metrics Job Cancelled");
            // do nothing
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (DEBUG) Log.v(TAG, "was success: " + success);

            // attempt to reschedule if analytics service is unavailable for our events
            jobFinished(mMetricsJobParams, !success /* reschedule */);
        }
    }

}
