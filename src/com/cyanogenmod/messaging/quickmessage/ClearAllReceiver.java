/*
 * Copyright (C) 2012 Adam K
 * Modifications Copyright (C) 2012 The CyanogenMod Project
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

package com.cyanogenmod.messaging.quickmessage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * This class provides an easy way to clear held WakeLocks and re-enable the
 * Keyguard (either immediately or scheduled in the future).
 */
public class ClearAllReceiver extends BroadcastReceiver {
    private static String LOG_TAG = "ManageKeyguard";

    @Override
    public void onReceive(Context context, Intent intent) {
        clearAll();
    }

    public static synchronized void clearAll() {
        clearAll(true);
    }

    public static synchronized void clearAll(boolean reenableKeyguard) {
        if (reenableKeyguard) {
            ManageKeyguard.reenableKeyguard();
        }
        ManageWakeLock.releaseAll();
    }

    private static PendingIntent getPendingIntent(Context context) {
        return PendingIntent.getBroadcast(
                context, 0, new Intent(context, ClearAllReceiver.class), 0);
    }

    /**
     * Schedules a Broadcast to this receiver for some time in the future (timeout). Used in the
     * case the user doesn't notice the pop-up and the phone should go back to sleep
     */
    public static synchronized void setCancel(Context context, int timeout) {
        removeCancel(context);
        AlarmManager myAM = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        myAM.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + (timeout * 1000),
                getPendingIntent(context));
    }

    /**
     * Cancels the scheduled Broadcast to this receiver
     */
    public static synchronized void removeCancel(Context context) {
        AlarmManager myAM = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        myAM.cancel(getPendingIntent(context));
    }
}
