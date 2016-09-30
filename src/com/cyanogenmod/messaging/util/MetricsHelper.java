/*====*====*====*====*====*====*====*====*====*====*====*====*====*====*====*
 *  Copyright (c) 2016 Cyanogen Inc.
 *     All Rights Reserved.
 *     Cyanogen Confidential and Proprietary.
 * =========================================================================*/

package com.cyanogenmod.messaging.util;

import android.content.SharedPreferences;
import android.util.Log;

//import com.android.internal.annotations.VisibleForTesting;
import com.cyanogen.ambient.analytics.Event;
import android.content.ComponentName;
import android.content.Context;

import java.util.HashMap;
import java.util.Map;

public final class MetricsHelper {

    private static final String TAG = "MetricsHelper";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String METRICS_SHARED_PREFERENCES = "messaging_metrics";
    public static final String METRICS_VALIDATION = "messaging_metrics_validation";
    public static final String DELIMIT = ":";
    private static final String CATEGORY_BASE = "messaging.metrics.";

    // Positions in our shared preference keys
    private static final int POS_COMPONENT_NAME = 0;
    private static final int POS_CATEGORY_VALUE = 1;
    private static final int POS_EVENT_VALUE = 2;
    private static final int POS_PARAM_VALUE = 3;

    public static final String CATEGORY_MESSAGING_RIDESHARING_INTEGRATION = "category_messaging_ridesharing_integration";
    public static final String EVENT_RIDESHARING_MAP_SHOWN = "event_ridesharing_map_shown";
    public static final String EVENT_UBER_RIDE_REQUESTED = "event_uber_ride_requested";
    public static final String PARAM_COUNT = "param_count";
    public static final String PARAM_PROVIDER = "provider";

    public static enum MetricEvent {
        RIDESHARING_MAP_SHOWN,
        UBER_RIDE_REQUESTED;
    }

    //@VisibleForTesting
    /* package */ static void storeEvent(Context context, ComponentName cn,
                                         HashMap<String, String> data, String category, String event) {

        SharedPreferences sp = context.getSharedPreferences(
                METRICS_SHARED_PREFERENCES, Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = sp.edit();

        for (String param : data.keySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append(cn.flattenToShortString()); // Add ComponentName String
            sb.append(DELIMIT);
            sb.append(category); // Add our category value
            sb.append(DELIMIT);
            sb.append(event); // Add our event value
            sb.append(DELIMIT);
            sb.append(param); // add our param value
            editor.putString(sb.toString(), data.get(param));
        }
        editor.apply();
    }

    /**
     * Get the sharedpreferences events and output a hashmap for the event's values.
     *
     * @param context the current context
     * @param componentName ComponentName who created the event
     *
     * @return HashMap of our params and their values.
     */
    /* package*/ static HashMap<String, String> getStoredEventParams(Context context,
                                                                     ComponentName componentName, String category, String event) {

        SharedPreferences sp = context.getSharedPreferences(
                METRICS_SHARED_PREFERENCES, Context.MODE_PRIVATE);

        StringBuilder sb = new StringBuilder();
        sb.append(componentName.flattenToShortString()); // Add ComponentName String
        sb.append(DELIMIT);
        sb.append(category); // Add our category value
        sb.append(DELIMIT);
        sb.append(event); // Add our event value
        sb.append(DELIMIT);

        HashMap<String, String> eventMap = new HashMap<>();
        Map<String, ?> map = sp.getAll();

        for(Map.Entry<String,?> entry : map.entrySet()) {
            if (entry.getKey().startsWith(sb.toString())) {
                String[] keyParts = entry.getKey().split(DELIMIT);
                String key = keyParts[POS_PARAM_VALUE];
                eventMap.put(key, String.valueOf(entry.getValue()));
            }
        }
        return eventMap;
    }

    /**
     * Helper method to increase the count of event metric if the last action was not
     * the same as the current action.
     *
     * @param context
     */
    public static void increaseCountOfEventMetricAfterValidate(Context context, ComponentName componentName,
                                                              MetricEvent metricEvent) {

        StringBuilder sb = new StringBuilder();
        sb.append(componentName.flattenToShortString()); // Add ComponentName String
        sb.append(DELIMIT);
        sb.append(CATEGORY_MESSAGING_RIDESHARING_INTEGRATION); // Add our category value

        String validationKey = sb.toString();
        String event;
        switch (metricEvent) {
            case UBER_RIDE_REQUESTED: 
                 event = EVENT_UBER_RIDE_REQUESTED;
                 break;
            case RIDESHARING_MAP_SHOWN:
            default:
                 event = EVENT_RIDESHARING_MAP_SHOWN;
                 break;
        }

        if (checkLastEvent(context, validationKey, event)) {
            HashMap<String, String> metricsData
                    = getStoredEventParams(context, componentName, CATEGORY_MESSAGING_RIDESHARING_INTEGRATION,
                    event);

            int count = 1;
            if (metricsData.containsKey(PARAM_COUNT)) {
                count += Integer.valueOf(metricsData.get(PARAM_COUNT));
            }

            metricsData.put(PARAM_COUNT, String.valueOf(count));
            storeEvent(context, componentName, metricsData, CATEGORY_MESSAGING_RIDESHARING_INTEGRATION, event);
        }
    }

    /* package */ static boolean checkLastEvent(Context context, String validationKey, String event) {
        SharedPreferences preferences = context.getSharedPreferences(METRICS_VALIDATION,
                Context.MODE_PRIVATE);

        SharedPreferences.Editor editor = preferences.edit();
        String lastEvent = preferences.getString(validationKey, null);

        if (lastEvent != null && lastEvent.equals(event)) {
            return false;
        } else {
            editor.putString(validationKey, event);
        }

        editor.apply();
        return true;
    }


    /**
     * Prepares all our metrics for sending.
     */
    public static HashMap<String, Event.Builder> getEventsToSend(Context c) {
        SharedPreferences sp = c.getSharedPreferences(METRICS_SHARED_PREFERENCES,
                Context.MODE_PRIVATE);

        Map<String, ?> map = sp.getAll();

        HashMap<String, Event.Builder> unBuiltEvents = new HashMap<>();

        for(Map.Entry<String,?> entry : map.entrySet()){
            String[] keyParts = entry.getKey().split(DELIMIT);

            if (keyParts.length ==  POS_PARAM_VALUE + 1) {
                String componentString = keyParts[POS_COMPONENT_NAME];
                String eventCategory = keyParts[POS_CATEGORY_VALUE];
                String parameter = keyParts[POS_PARAM_VALUE];
                String eventAction = keyParts[POS_EVENT_VALUE];

                StringBuilder sb = new StringBuilder();
                sb.append(componentString); // Add ComponentName String
                sb.append(DELIMIT);
                sb.append(eventCategory); // Add our category value
                sb.append(DELIMIT);
                sb.append(eventAction); // Add our event value
                String eventKey = sb.toString();

                Event.Builder eventBuilder;
                if (unBuiltEvents.containsKey(eventKey)) {
                    eventBuilder = unBuiltEvents.get(eventKey);
                } else {
                    eventBuilder = new Event.Builder(CATEGORY_BASE + eventCategory, eventAction);
                    eventBuilder.addField(PARAM_PROVIDER, componentString);
                }

                eventBuilder.addField(parameter, String.valueOf(entry.getValue()));
                unBuiltEvents.put(eventKey, eventBuilder);
            }
        }
        return unBuiltEvents;
    }

    public static void clearEventData(Context c, String key) {
        SharedPreferences sp = c.getSharedPreferences(METRICS_SHARED_PREFERENCES,
                Context.MODE_PRIVATE);

        Map<String, ?> map = sp.getAll();
        SharedPreferences.Editor editor = sp.edit();

        for(Map.Entry<String,?> entry : map.entrySet()){
            String storedKey = entry.getKey();
            if (storedKey.startsWith(key)) {
                editor.remove(storedKey);
            }
        }
        editor.apply();
    }

}
