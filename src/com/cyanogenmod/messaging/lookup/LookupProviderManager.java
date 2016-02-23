/*
* Copyright (C) 2015 The CyanogenMod Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.cyanogenmod.messaging.lookup;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.cyanogen.lookup.phonenumber.provider.LookupProviderImpl;
import com.cyanogen.lookup.phonenumber.util.LookupHandlerThread;
import com.cyanogen.lookup.phonenumber.request.LookupRequest;
import com.cyanogen.lookup.phonenumber.response.LookupResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>
 *      Class intended for handling communication and interaction between the lookup provider and
 *      the application
 * </pre>
 *
 * @see {@link Application.ActivityLifecycleCallbacks}
 * @see {@link LookupRequest.Callback}
 * @see {@link ILookupClient}
 */
public class LookupProviderManager implements Application.ActivityLifecycleCallbacks,
        LookupRequest.Callback, ILookupClient {

    private static final String TAG = "LookupProviderManager";
    private static final String THREAD_NAME = "PhoneLookupProviderThread";

    // Members
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private Application mApplication;
    private ConcurrentHashMap<String, LookupResponse> mPhoneNumberLookupCache;
    private ConcurrentHashMap<String, HashSet<LookupProviderListener>> mLookupListeners;
    private LookupHandlerThread mLookupHandlerThread;
    private boolean mIsPhoneNumberLookupInitialized;
    private short mActivityCount = 0;

    /**
     * Constructor
     *
     * @param application {@link Application}
     */
    public LookupProviderManager(Application application) {
        log("LookupProviderManager(" + application + ")");
        if (application == null) {
            throw new IllegalArgumentException("'application' must not be null!");
        }
        mPhoneNumberLookupCache = new ConcurrentHashMap<String, LookupResponse>();
        mLookupListeners = new ConcurrentHashMap<String, HashSet<LookupProviderListener>>();
        application.registerActivityLifecycleCallbacks(this);
        mApplication = application;
    }

    private boolean isDebug() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    private void log(String str) {
        if (isDebug()) {
            Log.d(TAG, "::" + str);
        }
    }

    private boolean start() {
        log("start()");
        if (mLookupHandlerThread == null) {
            mLookupHandlerThread = new LookupHandlerThread(THREAD_NAME, mApplication,
                    new LookupProviderImpl(mApplication));
            mLookupHandlerThread.initialize();
        }
        return mLookupHandlerThread.isAlive();
    }

    private void stop() {
        log("stop()");
        if (mLookupHandlerThread != null) {
            mLookupHandlerThread.tearDown();
            mLookupHandlerThread = null;
        }
        mPhoneNumberLookupCache.clear();
        mLookupListeners.clear();
        mIsPhoneNumberLookupInitialized = false;
    }

    /**
     * Registration mechanism for anyone interested in new contact info being available from an
     * external provider.  The updates aren't granular as of now - you will be notified of
     * updates to all contact info request
     *
     * @param number {@link String}
     * @param listener {@link LookupProviderListener}
     */
    public void addLookupProviderListener(String number, LookupProviderListener listener) {
        log("addLookupProviderListener(" + number + ", " + listener + ")");
        if (TextUtils.isEmpty(number) || listener == null) {
            return;
        }
        if (!mLookupListeners.contains(number)) {
            mLookupListeners.put(number, new HashSet<LookupProviderListener>());
        }
        if (!mLookupListeners.get(number).contains(listener)) { // prevent adding same listener
            mLookupListeners.get(number).add(listener);
        }
    }

    /**
     * Stop getting updates about newly added contact info
     *
     * @param number {@link String}
     * @param listener {@link LookupProviderListener}
     */
    public void removeLookupProviderListener(String number, LookupProviderListener listener) {
        log("removeLookupProviderListener(" + number + ", " + listener + ")");
        if (TextUtils.isEmpty(number)) {
            return;
        }
        if (mLookupListeners.containsKey(number)) {
            mLookupListeners.get(number).remove(listener);
        }
    }

    public void onLowMemory() {
        log("onLowMemory()");
        mPhoneNumberLookupCache.clear();
    }

    /* ---------------------  LookupRequest callback interfaces  --------------------------------*/
    @Override
    public void onNewInfo(LookupRequest lookupRequest, final LookupResponse response) {
        log("onNewInfo(" + lookupRequest + ", " + response + ")");
        mPhoneNumberLookupCache.put(lookupRequest.mPhoneNumber, response);
        if (mLookupListeners.containsKey(lookupRequest.mPhoneNumber)) {
            int i = 0;
            List<Integer> removalIndexes = new ArrayList<Integer>();
            for (final LookupProviderListener listener : mLookupListeners.get(lookupRequest
                    .mPhoneNumber)) {
                if (listener == null) {
                    removalIndexes.add(i);
                }
                sHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) {
                            listener.onNewInfoAvailable(response);
                        }
                    }
                });
                i++;
            }
            for (int index : removalIndexes) {
                mLookupListeners.get(lookupRequest.mPhoneNumber).remove(index);
            }
        }
    }

    /* ---------------------  Activity callback interfaces  -------------------------------------*/
    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        log("onActivityCreated(" + activity + ", " + savedInstanceState + ")");
        ++mActivityCount;
        if (mActivityCount == 1) {
            mIsPhoneNumberLookupInitialized = start();
        }
    }

    /* ---------------------  Lookup Client interfaces  -----------------------------------------*/
    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        log("onActivityCreated(" + activity + ")");
        --mActivityCount;
        if (mActivityCount == 0) {
            if (mIsPhoneNumberLookupInitialized) {
                stop();
                mIsPhoneNumberLookupInitialized = false;
            }
        }
    }

    @Override
    public void lookupInfoForPhoneNumber(String phoneNumber) {
        log("lookupInfoForPhoneNumber(" + phoneNumber + ")");
        lookupInfoForPhoneNumber(phoneNumber, false);
    }

    /**
     * @param phoneNumber {@link String} not null or empty and should be formatted to E164 already!
     * @param requery {@link Boolean}
     *
     * In the messaging app we store a normalized version of the number and are using this to call
     * this method.  If you call PhoneNumberUtils.formatNumberToE164 with an already formatted
     * number, it returns null.  So we just use the number and expect it to be formatted.
     */
    @Override
    public void lookupInfoForPhoneNumber(String phoneNumber, boolean requery) {
        log("lookupInfoForPhoneNumber(" + phoneNumber + ", " + requery + ")");

        if (TextUtils.isEmpty(phoneNumber)) {
            return;
        }

        // If we have already fetched it before
        if (mPhoneNumberLookupCache.containsKey(phoneNumber)) {
            // Short circuit and call callback
            if (mLookupListeners.containsKey(phoneNumber)) {
                for (LookupProviderListener listener : mLookupListeners.get(phoneNumber)) {
                    listener.onNewInfoAvailable(mPhoneNumberLookupCache.get(phoneNumber));
                }
            }
            return;
        }

        if (mIsPhoneNumberLookupInitialized) {
            LookupRequest request = new LookupRequest(phoneNumber, this);
            // [TODO][MSB]: Could pass up the return of this
            mLookupHandlerThread.fetchInfoForPhoneNumber(request);
        }

    }

    @Override
    public void markAsSpam(String phoneNumber) {
        log("markAsSpam(" + phoneNumber + ")");
        if (TextUtils.isEmpty(phoneNumber)) {
            throw new IllegalArgumentException("'phoneNumber' cannot be null!");
        }
        phoneNumber = PhoneNumberUtils.formatNumberToE164(phoneNumber,
                mApplication.getResources().getConfiguration().locale.getISO3Country());
        markAsSpamInternal(phoneNumber);
    }

    private void markAsSpamInternal(String phoneNumber) {
        log("markAsSpamInternal(" + phoneNumber + ")");
        if (TextUtils.isEmpty(phoneNumber)) {
            throw new IllegalArgumentException("'phoneNumber' cannot be null!");
        }
        if (mIsPhoneNumberLookupInitialized) {
            mLookupHandlerThread.markAsSpam(phoneNumber);
            // Don't remove from cache in case user presses "undo" in snackbar.
        }
    }

    @Override
    public boolean hasSpamReporting() {
        log("hasSpamReporting()");
        return mIsPhoneNumberLookupInitialized && mLookupHandlerThread.isProviderInterestedInSpam();
    }

    @Override
    public String getProviderName() {
        log("getProviderName()");
        if (mIsPhoneNumberLookupInitialized) {
            return mLookupHandlerThread.getProviderName();
        }
        return null;
    }

    /* ---------------------  Interface  --------------------------------------------------------*/
    /**
     * Callback for clients requesting phone number lookups
     */
    public interface LookupProviderListener {
        // generic callback for when new info is available

        /**
         * Tell listener to handle update
         *
         * @param response {@link LookupResponse}
         */
        void onNewInfoAvailable(LookupResponse response);
    }

}
