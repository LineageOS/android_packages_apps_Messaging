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

package com.android.messaging.sms;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.Telephony;
import android.support.v7.mms.ApnSettingsLoader;
import android.support.v7.mms.MmsManager;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/*
 * APN loader for default SMS SIM
 * This loader tries to load APNs from 3 sources in order:
 * 1. System APN table
 * 2. Local APN table
 */
public class BugleApnSettingsLoader implements ApnSettingsLoader {
    /**
     * The base implementation of an APN
     */
    private static class BaseApn implements Apn {
        /**
         * Create a base APN from parameters
         *
         * @param typesIn the APN type field
         * @param mmscIn the APN mmsc field
         * @param proxyIn the APN mmsproxy field
         * @param portIn the APN mmsport field
         * @return an instance of base APN, or null if any of the parameter is invalid
         */
        public static BaseApn from(final String typesIn, final String mmscIn, final String proxyIn,
                final String portIn) {
            if (!isValidApnType(trimWithNullCheck(typesIn), APN_TYPE_MMS)) {
                return null;
            }
            String mmsc = trimWithNullCheck(mmscIn);
            if (TextUtils.isEmpty(mmsc)) {
                return null;
            }
            mmsc = trimV4AddrZeros(mmsc);
            try {
                new URI(mmsc);
            } catch (final URISyntaxException e) {
                return null;
            }
            String mmsProxy = trimWithNullCheck(proxyIn);
            int mmsProxyPort = 80;
            if (!TextUtils.isEmpty(mmsProxy)) {
                mmsProxy = trimV4AddrZeros(mmsProxy);
                final String portString = trimWithNullCheck(portIn);
                if (portString != null) {
                    try {
                        mmsProxyPort = Integer.parseInt(portString);
                    } catch (final NumberFormatException e) {
                        // Ignore, just use 80 to try
                    }
                }
            }
            return new BaseApn(mmsc, mmsProxy, mmsProxyPort);
        }

        private final String mMmsc;
        private final String mMmsProxy;
        private final int mMmsProxyPort;

        public BaseApn(final String mmsc, final String proxy, final int port) {
            mMmsc = mmsc;
            mMmsProxy = proxy;
            mMmsProxyPort = port;
        }

        @Override
        public String getMmsc() {
            return mMmsc;
        }

        @Override
        public String getMmsProxy() {
            return mMmsProxy;
        }

        @Override
        public int getMmsProxyPort() {
            return mMmsProxyPort;
        }

        @Override
        public void setSuccess() {
            // Do nothing
        }
    }

    /**
     * APN_TYPE_ALL is a special type to indicate that this APN entry can
     * service all data connections.
     */
    public static final String APN_TYPE_ALL = "*";
    /** APN type for MMS traffic */
    public static final String APN_TYPE_MMS = "mms";

    private static final String[] APN_PROJECTION_SYSTEM = {
            Telephony.Carriers.TYPE,
            Telephony.Carriers.MMSC,
            Telephony.Carriers.MMSPROXY,
            Telephony.Carriers.MMSPORT,
    };

    private static final int COLUMN_TYPE         = 0;
    private static final int COLUMN_MMSC         = 1;
    private static final int COLUMN_MMSPROXY     = 2;
    private static final int COLUMN_MMSPORT      = 3;
    private static final int COLUMN_CURRENT      = 4;
    private static final int COLUMN_ID           = 5;

    private static final String SELECTION_APN = Telephony.Carriers.APN + "=?";
    private static final String SELECTION_CURRENT = Telephony.Carriers.CURRENT + " IS NOT NULL";

    private final Context mContext;

    // Cached APNs for subIds
    private final SparseArray<List<ApnSettingsLoader.Apn>> mApnsCache;

    public BugleApnSettingsLoader(final Context context) {
        mContext = context;
        mApnsCache = new SparseArray<>();
    }

    @Override
    public List<ApnSettingsLoader.Apn> get(final String apnName) {
        final int subId = PhoneUtils.getDefault().getEffectiveSubId(
                ParticipantData.DEFAULT_SELF_SUB_ID);
        List<ApnSettingsLoader.Apn> apns;
        boolean didLoad = false;
        synchronized (this) {
            apns = mApnsCache.get(subId);
            if (apns == null) {
                apns = new ArrayList<>();
                mApnsCache.put(subId, apns);
                loadLocked(subId, apnName, apns);
                didLoad = true;
            }
        }
        if (didLoad) {
            LogUtil.i(LogUtil.BUGLE_TAG, "Loaded " + apns.size() + " APNs");
        }
        return apns;
    }

    private void loadLocked(final int subId, final String apnName, final List<Apn> apns) {
        // Try system APN table
        loadFromSystem(subId, apnName, apns);
    }

    /**
     * Load matching APNs from telephony provider.
     * We try different combinations of the query to work around some platform quirks.
     *
     * @param subId the SIM subId
     * @param apnName the APN name to match
     * @param apns the list used to return results
     */
    private void loadFromSystem(final int subId, final String apnName, final List<Apn> apns) {
        Uri uri;
        if (subId != MmsManager.DEFAULT_SUB_ID) {
            uri = Uri.withAppendedPath(Telephony.Carriers.CONTENT_URI, "/subId/" + subId);
        } else {
            uri = Telephony.Carriers.CONTENT_URI;
        }
        Cursor cursor;
        try {
            for (; ; ) {
                // Try different combinations of queries. Some would work on some platforms.
                // So we query each combination until we find one returns non-empty result.
                cursor = querySystem(uri, true/*checkCurrent*/, apnName);
                if (cursor != null) {
                    break;
                }
                cursor = querySystem(uri, false/*checkCurrent*/, apnName);
                if (cursor != null) {
                    break;
                }
                cursor = querySystem(uri, true/*checkCurrent*/, null/*apnName*/);
                if (cursor != null) {
                    break;
                }
                cursor = querySystem(uri, false/*checkCurrent*/, null/*apnName*/);
                break;
            }
        } catch (final SecurityException e) {
            // Can't access platform APN table, return directly
            return;
        }
        if (cursor == null) {
            return;
        }
        try {
            if (cursor.moveToFirst()) {
                final ApnSettingsLoader.Apn apn = BaseApn.from(
                        cursor.getString(COLUMN_TYPE),
                        cursor.getString(COLUMN_MMSC),
                        cursor.getString(COLUMN_MMSPROXY),
                        cursor.getString(COLUMN_MMSPORT));
                if (apn != null) {
                    apns.add(apn);
                }
            }
        } finally {
            cursor.close();
        }
    }

    /**
     * Query system APN table
     *
     * @param uri The APN query URL to use
     * @param checkCurrent If add "CURRENT IS NOT NULL" condition
     * @param apnName The optional APN name for query condition
     * @return A cursor of the query result. If a cursor is returned as not null, it is
     *         guaranteed to contain at least one row.
     */
    private Cursor querySystem(final Uri uri, final boolean checkCurrent, String apnName) {
        LogUtil.i(LogUtil.BUGLE_TAG, "Loading APNs from system, "
                + "checkCurrent=" + checkCurrent + " apnName=" + apnName);
        final StringBuilder selectionBuilder = new StringBuilder();
        String[] selectionArgs = null;
        if (checkCurrent) {
            selectionBuilder.append(SELECTION_CURRENT);
        }
        apnName = trimWithNullCheck(apnName);
        if (!TextUtils.isEmpty(apnName)) {
            if (selectionBuilder.length() > 0) {
                selectionBuilder.append(" AND ");
            }
            selectionBuilder.append(SELECTION_APN);
            selectionArgs = new String[] { apnName };
        }
        try {
            final Cursor cursor = SqliteWrapper.query(
                    mContext,
                    mContext.getContentResolver(),
                    uri,
                    APN_PROJECTION_SYSTEM,
                    selectionBuilder.toString(),
                    selectionArgs,
                    null/*sortOrder*/);
            if (cursor == null || cursor.getCount() < 1) {
                if (cursor != null) {
                    cursor.close();
                }
                LogUtil.w(LogUtil.BUGLE_TAG, "Query " + uri + " with apn " + apnName + " and "
                        + (checkCurrent ? "checking CURRENT" : "not checking CURRENT")
                        + " returned empty");
                return null;
            }
            return cursor;
        } catch (final SQLiteException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "APN table query exception: " + e);
        } catch (final SecurityException e) {
            LogUtil.w(LogUtil.BUGLE_TAG, "Platform restricts APN table access: " + e);
            throw e;
        }
        return null;
    }

    private static String trimWithNullCheck(final String value) {
        return value != null ? value.trim() : null;
    }

    /**
     * Trim leading zeros from IPv4 address strings
     * Our base libraries will interpret that as octel..
     * Must leave non v4 addresses and host names alone.
     * For example, 192.168.000.010 -> 192.168.0.10
     *
     * @param addr a string representing an ip addr
     * @return a string propertly trimmed
     */
    private static String trimV4AddrZeros(final String addr) {
        if (addr == null) {
            return null;
        }
        final String[] octets = addr.split("\\.");
        if (octets.length != 4) {
            return addr;
        }
        final StringBuilder builder = new StringBuilder(16);
        String result;
        for (int i = 0; i < 4; i++) {
            try {
                if (octets[i].length() > 3) {
                    return addr;
                }
                builder.append(Integer.parseInt(octets[i]));
            } catch (final NumberFormatException e) {
                return addr;
            }
            if (i < 3) {
                builder.append('.');
            }
        }
        result = builder.toString();
        return result;
    }

    /**
     * Check if the APN contains the APN type we want
     *
     * @param types The string encodes a list of supported types
     * @param requestType The type we want
     * @return true if the input types string contains the requestType
     */
    public static boolean isValidApnType(final String types, final String requestType) {
        // If APN type is unspecified, assume APN_TYPE_ALL.
        if (TextUtils.isEmpty(types)) {
            return true;
        }
        for (final String t : types.split(",")) {
            if (t.equals(requestType) || t.equals(APN_TYPE_ALL)) {
                return true;
            }
        }
        return false;
    }
}
