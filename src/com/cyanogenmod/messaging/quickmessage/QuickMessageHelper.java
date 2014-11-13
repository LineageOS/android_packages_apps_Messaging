package com.cyanogenmod.messaging.quickmessage;

import android.content.Intent;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.support.v4.app.NotificationCompat;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.view.View;

import com.android.contacts.common.util.MaterialColorMapUtils;
import com.android.messaging.R;
import com.cyanogenmod.messaging.util.PrefsUtils;

public class QuickMessageHelper {

    public static Intent getQuickMessageIntent(Context context, NotificationInfo ni) {
        Intent qmIntent = new Intent();
        qmIntent.setClass(context, QuickMessagePopup.class);
        qmIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        qmIntent.putExtra(QuickMessagePopup.SMS_NOTIFICATION_OBJECT_EXTRA, ni);
        qmIntent.putExtra(QuickMessagePopup.QR_SHOW_KEYBOARD_EXTRA, true);

        return qmIntent;
    }

    public static void addQuickMessageAction(Context context, NotificationCompat.Builder builder,
            NotificationInfo ni) {
        if (PrefsUtils.isQuickMessagingEnabled()) {
            Intent qmIntent = getQuickMessageIntent(context, ni);
            CharSequence qmText = context.getText(R.string.notification_reply_prompt);
            PendingIntent qmPendingIntent = PendingIntent.getActivity(context, 0, qmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_reply, qmText, qmPendingIntent);
        }
    }

    public static String formatTimeStampString(Context context, long when, boolean fullFormat) {
        Time then = new Time();
        then.set(when);
        Time now = new Time();
        now.setToNow();

        // Basic settings for formatDateTime() we want for all cases.
        int format_flags = DateUtils.FORMAT_NO_NOON_MIDNIGHT |
                DateUtils.FORMAT_ABBREV_ALL |
                DateUtils.FORMAT_CAP_AMPM;

        // If the message is from a different year, show the date and year.
        if (then.year != now.year) {
            format_flags |= DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_DATE;
        } else if (then.yearDay != now.yearDay) {
            // If it is from a different day than today, show only the date.
            format_flags |= DateUtils.FORMAT_SHOW_DATE;
        } else {
            // Otherwise, if the message is from today, show the time.
            format_flags |= DateUtils.FORMAT_SHOW_TIME;
        }

        // If the caller has asked for full details, make sure to show the date
        // and time no matter what we've determined above (but still make showing
        // the year only happen if it is a different year from today).
        if (fullFormat) {
            format_flags |= (DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        }

        return DateUtils.formatDateTime(context, when, format_flags);
    }
}
