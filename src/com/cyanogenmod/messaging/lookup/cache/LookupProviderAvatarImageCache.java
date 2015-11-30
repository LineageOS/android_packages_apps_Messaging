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

package com.cyanogenmod.messaging.lookup.cache;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <pre>
 *
 *      Handle 3 level image caching for remote avatar images.
 *
 *      - Level 1--{@link #sHardCache}: In memory hard references to {@link Bitmap} objects
 *      - Level 2--{@link #sSoftCache}: In memory {@link SoftReference} {@link Bitmap} objects
 *      - Level 3--Disk cache: Bitmaps are stored in external cache directory with limit of 100MB
 *      and "Mark and sweep" cache cleanup for anything older than a day.
 *
 * </pre>
 */
public class LookupProviderAvatarImageCache {

    // Constants
    private static final String TAG = LookupProviderAvatarImageCache.class.getSimpleName();

    // Members
    private static final int MAX_HARD_CACHE = 100;
    private static Application sApplication;
    private static MarkAndSweepRunnable sMarkAndSweepRunnable;
    private static boolean mInitialized;
    private static PendingIntent sPendingIntent;

    private static final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private static ExecutorService sThreadPool;

    // Cache maps
    private static final LruCache<String, Bitmap> sHardCache = new LruCache<String, Bitmap>
            (MAX_HARD_CACHE) {
        public void entryRemoved(boolean evicted, String key, Bitmap oldImage, Bitmap newImage) {
            if (evicted) {
                sSoftCache.put(key, new SoftReference<Bitmap>(oldImage));
            }
        }
    };
    private static final ConcurrentHashMap<String, SoftReference<Bitmap>> sSoftCache = new
            ConcurrentHashMap<String, SoftReference<Bitmap>>();

    /**
     * Add a bitmap to the cache, will remove any reference from {@link #sSoftCache},
     * and place back into {@link #sHardCache}
     *
     * @param key {@link String}
     * @param bitmap {@link Bitmap}
     */
    public static void addBitmap(String key, Bitmap bitmap) {
        if (!TextUtils.isEmpty(key) && bitmap != null) {

            // If it is in the soft cache, we need to drop it because it was recently accessed
            if (sSoftCache.containsKey(key)) {
                sSoftCache.remove(key);
            }

            // If it is in the hard cache, we need to make sure it gets updated
            // Put it at the front of the LRU hard cache
            sHardCache.put(key, bitmap);
            sThreadPool.submit(new WriteDiskCacheRunnable(sApplication, key, bitmap));

        }
    }

    /**
     * Sets up thread pool and resets disk cache mark/sweep
     *
     * @param application {@link Application}
     */
    public static void initialize(Application application) {
        if (mInitialized) {
            Log.w(TAG, "Already initialized!");
            return;
        }
        sApplication = application;
        sThreadPool = Executors.newCachedThreadPool();

        Intent intent = new Intent(sApplication, MarkAndSweepReceiver.class);
        sPendingIntent = PendingIntent.getBroadcast(sApplication, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager alarmManager =
                (AlarmManager) application.getSystemService(Context.ALARM_SERVICE);
        mInitialized = true;
        alarmManager.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis(),
                AlarmManager.INTERVAL_DAY, sPendingIntent);
    }

    /**
     * This will post the operation to an internal thread pool and return immediately
     */
    public static void startMarkAndSweep() {
        if (!mInitialized) {
            return;
        }
        if (sMarkAndSweepRunnable == null) {
            sMarkAndSweepRunnable = new MarkAndSweepRunnable(sApplication);
            sThreadPool.submit(sMarkAndSweepRunnable);
        }
    }

    public static void onLowMemory() {
        if (!mInitialized) {
            return;
        }
        sHardCache.evictAll();
        sSoftCache.clear();
        // Leave only disk cache so we can rebuild memory cache as navigation occurs
    }

    /**
     * Will shut down thread pool
     */
    public static void terminate() {
        if (!mInitialized) {
            return;
        }
        sThreadPool.shutdownNow();
        mInitialized = false;
    }

    /**
     * Fetch a bitmap from the cache.  It will cycle through all three levels and post on the
     * provided callback
     *
     * @param key {@link String}
     * @param callback {@link LookupProviderAvatarImageCacheCallback}
     */
    public static void fetchBitmap(String key, LookupProviderAvatarImageCacheCallback callback) {
        if (!mInitialized) {
            return;
        }
        if (!TextUtils.isEmpty(key) && callback != null) {
            sThreadPool.submit(new FetchRunnable(key, callback));
        }
    }

    // Some nitty gritty self explanatory classes following

    private static class FetchRunnable implements Runnable {

        private String mKey;
        private LookupProviderAvatarImageCacheCallback mCallback;

        FetchRunnable(String key, LookupProviderAvatarImageCacheCallback callback) {
            mKey = key;
            mCallback = callback;
        }

        @Override
        public void run() {
            Bitmap bitmap = sHardCache.get(mKey);
            if (bitmap == null && sSoftCache.containsKey(mKey)) {
                SoftReference<Bitmap> ref = sSoftCache.remove(mKey); // cycle it back to the front
                bitmap = ref.get();
                if (bitmap != null) {
                    sHardCache.put(mKey, bitmap);
                    fireCallback(bitmap);
                } else {
                    // GC reclaimed soft cache
                    sThreadPool.submit(new ReadDiskCacheRunnable(sApplication, mKey, mCallback));
                }
            } else {
                // No hard cache or soft cache, lets check disk
                sThreadPool.submit(new ReadDiskCacheRunnable(sApplication, mKey, mCallback));
            }
        }

        private void fireCallback(final Bitmap bitmap) {
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mCallback != null) {
                        mCallback.onImageFound(mKey, bitmap);
                    }
                }
            });
        }

    }

    public interface LookupProviderAvatarImageCacheCallback {
        void onImageFound(String key, Bitmap bitmap);
    }

    private static class WriteDiskCacheRunnable implements Runnable {

        private Context mContext;
        private String mKey;
        private Bitmap mBitmap;

        WriteDiskCacheRunnable(Context context, String key, Bitmap bitmap) {
            mContext = context;
            mKey = key;
            mBitmap = bitmap;
        }

        @Override
        public void run() {
            try {
                DiskCacheUtil.writeBitmapToCache(mContext, mKey, mBitmap);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "Failed to write bitmap", e);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write bitmap", e);
            }
        }

    }

    private static class ReadDiskCacheRunnable implements Runnable {

        private Context mContext;
        private String mKey;
        private LookupProviderAvatarImageCacheCallback mCallback;
        private Bitmap mBitmap;

        ReadDiskCacheRunnable(Context context, String key, LookupProviderAvatarImageCacheCallback
                callback) {
            mContext = context;
            mKey = key;
            mCallback = callback;
        }

        @Override
        public void run() {
            try {
                mBitmap = DiskCacheUtil.readBitmapFromCache(mContext, mKey);
                if (mBitmap != null) {
                    // If we resurrected it from the disk, lets throw it in the hot cache
                    sHardCache.put(mKey, mBitmap);
                }
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "Failed to fetch bitmap", e);
            } catch (IOException e) {
                Log.e(TAG, "Failed to fetch bitmap", e);
            }
            mUiHandler.post(new Runnable() {
                @Override
                public void run() {
                    // Always check for null in callback
                    if (mCallback != null) {
                        mCallback.onImageFound(mKey, mBitmap);
                    }
                }
            });
        }

    }

    private static class MarkAndSweepRunnable implements Runnable {

        private Context mContext;

        MarkAndSweepRunnable(Context context) {
            mContext = context;
        }

        @Override
        public void run() {
            Log.i(TAG + "$MarkAndSweepRunnable", "Starting mark and sweep of disk cache");
            DiskCacheUtil.deleteBitmapsOlderThan(mContext, DiskCacheUtil.ONE_DAY_IN_MS);
            sMarkAndSweepRunnable = null;
        }

    }

}
