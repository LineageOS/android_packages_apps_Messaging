/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024 The LineageOS Project
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

package com.android.messaging.util;


/**
 * List of gservices keys and default values which are in use.
 */
public final class BugleGservicesKeys {
    private BugleGservicesKeys() {}   // do not instantiate

    /**
     * Time in milliseconds of initial (attempt 1) resend backoff for failing messages
     */
    public static final long INITIAL_MESSAGE_RESEND_DELAY_MS_DEFAULT = 5 * 1000L;

    /**
     * Time in milliseconds of max resend backoff for failing messages
     */
    public static final long MAX_MESSAGE_RESEND_DELAY_MS_DEFAULT = 2 * 60 * 60 * 1000L;

    /**
     * Time in milliseconds of resend window for unsent messages
     */
    public static final long MESSAGE_RESEND_TIMEOUT_MS_DEFAULT = 20 * 60 * 1000L;

    /**
     * Time in milliseconds of download window for new mms notifications
     */
    public static final long MESSAGE_DOWNLOAD_TIMEOUT_MS_DEFAULT = 20 * 60 * 1000L;

    /**
     * Time in milliseconds for SMS send timeout
     */
    public static final long SMS_SEND_TIMEOUT_IN_MILLIS_DEFAULT = 5 * 60 * 1000L;

    /**
     * Keys to control the SMS sync batch size. The batch size is defined by the number
     * of messages that incur local database change, e.g. importing messages and
     * deleting messages.
     *
     * 1. The minimum size for a batch and
     * 2. The maximum size for a batch.
     * The first batch uses the minimum size for probing. Set this to a small number for the
     * first sync batch to make sure the user sees SMS showing up in conversations quickly
     * Use these two settings to limit the number of messages to sync in each batch.
     * The minimum is to make sure we always make progress during sync. The maximum is
     * to limit the sync batch size within a reasonable range (needs to fit in an intent).
     * 3. The time limit controls the limit of time duration of a sync batch. We can
     * not control this directly due to the batching nature of sync. So this provides
     * heuristics. We may sometime exceeds the limit if our calculation is off due to
     * whatever reasons. Keeping this low ensures responsiveness of the application.
     * 4. The limit on number of total messages to scan in one batch.
     */
    public static final int SMS_SYNC_BATCH_SIZE_MIN_DEFAULT = 80;
    public static final int SMS_SYNC_BATCH_SIZE_MAX_DEFAULT = 1000;
    public static final long SMS_SYNC_BATCH_TIME_LIMIT_MILLIS_DEFAULT = 400;
    public static final int SMS_SYNC_BATCH_MAX_MESSAGES_TO_SCAN_DEFAULT =
            SMS_SYNC_BATCH_SIZE_MAX_DEFAULT * 4;

    /**
     * Time in ms for sync to backoff from "now" to the latest message that will be sync'd.
     *
     * This controls the best case for how out of date the application will appear to be
     * when bringing in changes made outside the application. It also represents a buffer
     * to ensure that sync doesn't trigger based on changes made within the application.
     */
    public static final long SMS_SYNC_BACKOFF_TIME_MILLIS_DEFAULT = 5000L;

    /**
     * Just in case if we fall into a loop of full sync -> still not synchronized -> full sync ...
     * This forces a backoff time so that we at most do full sync once a while (an hour by default)
     */
    public static final long SMS_FULL_SYNC_BACKOFF_TIME_MILLIS_DEFAULT = 60 * 60 * 1000;
    /**
     * MMS UA profile url.
     *
     * This is used on all Android devices running Hangout, so cannot just host the profile of the
     * latest and greatest phones. However, if we're on KitKat or below we can't get the phone's
     * UA profile and thus we need to send them the default url.
     */
    public static final String MMS_UA_PROFILE_URL_DEFAULT =
            "http://www.gstatic.com/android/sms/mms_ua_profile.xml";

    /**
     * When receiving or importing an mms, limit the length of text to this limit. Huge blocks
     * of text can cause the app to hang/ANR/or crash in native text code..
     */
    public static final int MMS_TEXT_LIMIT_DEFAULT = 2000;

    /**
     * Max number of attachments the user may add to a single message.
     */
    public static final int MMS_ATTACHMENT_LIMIT_DEFAULT = 10;

    /**
     * The max number of messages to show in a single conversation notification. We always show
     * the most recent message. If this value is >1, we may also include prior messages as well.
     */
    public static final int MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_DEFAULT = 7;

    /**
     * Time (in seconds) between notification ringing for incoming messages of the same
     * conversation. We won't ding more often than this value for messages coming in at a high rate.
     */
    public static final int NOTIFICATION_TIME_BETWEEN_RINGS_SECONDS_DEFAULT = 10;

    /**
     * The max number of messages to show in a single conversation notification, when a wearable
     * device (i.e. smartwatch) is paired with the phone. Watches have a different UX model and
     * less screen real estate, so we may want to optimize for that case. Note that if a wearable
     * is paired, this value will apply to notifications as shown both on the watch and the phone.
     */
    public static final int MAX_MESSAGES_IN_CONVERSATION_NOTIFICATION_WITH_WEARABLE_DEFAULT = 1;

    /**
     * We concatenate all text parts in an MMS to form the message text. This specifies
     * the separator between the combinated text parts. Default is ' ' (space).
     */
    public static final String MMS_TEXT_CONCAT_SEPARATOR_DEFAULT = " ";
}
