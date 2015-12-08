package com.android.messaging.metrics;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import java.util.Calendar;

public class MetricsSendService extends IntentService {
    private static final String ANALYTIC_INTENT = "com.cyngn.stats.action.SEND_ANALYITC_EVENT";
    private static final String ANALYTIC_PERMISSION = "com.cyngn.stats.SEND_ANALYTICS";

    public MetricsSendService() {
        super(MetricsSendService.class.getName());
    }

    public static void schedule(Context context) {
        PendingIntent intent = PendingIntent.getService(context, 0,
                new Intent(context, MetricsSendService.class),
                PendingIntent.FLAG_UPDATE_CURRENT);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.HOUR, 0);
        calendar.set(Calendar.AM_PM, Calendar.AM);
        calendar.add(Calendar.DAY_OF_MONTH, 1);

        AlarmManager alarmManager = (AlarmManager)context.getSystemService(ALARM_SERVICE);
        alarmManager.setRepeating(AlarmManager.RTC, calendar.getTimeInMillis(),
                AlarmManager.INTERVAL_DAY, intent);
    }

    @Override
    protected void onHandleIntent(Intent i) {
        MetricsDatabase metricsDatabase = MetricsDatabase.getInstance(this);

        Intent intent = new Intent();
        intent.setAction(ANALYTIC_INTENT);

        for (EventStatistic statistic : metricsDatabase.getAllStatistics()) {
            intent.putExtra(MetricsDatabase.CATEGORY, statistic.getCategory());
            intent.putExtra(MetricsDatabase.ACTION, statistic.getAction());
            intent.putExtra(MetricsDatabase.LABEL, statistic.getLabel());
            intent.putExtra(MetricsDatabase.VALUE, statistic.getValue());
            sendBroadcast(intent, ANALYTIC_PERMISSION);
        }

        metricsDatabase.clearStatistics();
    }
}
