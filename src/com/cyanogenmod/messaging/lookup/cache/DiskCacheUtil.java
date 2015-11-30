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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * <pre>
 *      Utility for performing the disk I/O operations
 * </pre>
 */
public class DiskCacheUtil {

    // Constants
    private static final String TAG = DiskCacheUtil.class.getSimpleName();
    private static final String BASE_DIR = ".provider-avatar-cache";
    private static final long MAX_CACHE_SIZE = 100000000; // 100MB
    private static final long FIVE_MINUTES_MS = 60 * 5 * 1000;
    public static final long ONE_DAY_IN_MS = 3600 * 24 * 1000;

    private static String convertToHashKey(String plain) throws NoSuchAlgorithmException {
        MessageDigest messageDigest = MessageDigest.getInstance("MD5");
        messageDigest.update(plain.getBytes());
        byte[] digest = messageDigest.digest();
        StringBuilder hexBuffer = new StringBuilder();
        for (byte b : digest) {
            hexBuffer.append(Integer.toHexString(0xFF & b));
        }
        return hexBuffer.toString();
    }

    private static long getFolderSize(File folder) {
        long size = 0;
        if (folder.isDirectory()) {
            for (File file: folder.listFiles()) {
                size += getFolderSize(file);
            }
        } else {
            size += folder.length();
        }
        return size;
    }

    /**
     * Reads from the bitmap cache
     *
     * @param context {@link Context}
     * @param key {@link String}
     * @return {@link Bitmap}
     * @throws NoSuchAlgorithmException {@link NoSuchAlgorithmException}
     * @throws IOException {@link IOException}
     */
    public static Bitmap readBitmapFromCache(Context context, String key)
            throws NoSuchAlgorithmException, IOException {
        if (context == null) {
            throw new IllegalArgumentException("'context' cannot be null!");
        }
        if (TextUtils.isEmpty(key)) {
            throw new IllegalArgumentException("'key' cannot be null or empty!");
        }
        File cacheDir = context.getExternalCacheDir();
        File cacheFileBaseDir = new File(cacheDir, BASE_DIR);
        File cacheFile = new File(cacheFileBaseDir, convertToHashKey(key));
        Bitmap bitmap = null;
        if (cacheFile.exists() && cacheFile.canRead()) {
            FileInputStream fis = new FileInputStream(cacheFile);
            try {
                bitmap = BitmapFactory.decodeStream(fis);
            } finally {
                fis.close();
            }
        }
        return bitmap;
    }

    /**
     * Writes a bitmap to the on disk cache
     *
     * @param context {@link Context}
     * @param key {@link String}
     * @param bitmap {@link Bitmap}
     * @throws NoSuchAlgorithmException {@link NoSuchAlgorithmException}
     * @throws IOException {@link IOException}
     */
    public static void writeBitmapToCache(Context context, String key, Bitmap bitmap)
            throws NoSuchAlgorithmException, IOException {
        if (context == null) {
            throw new IllegalArgumentException("'context' cannot be null!");
        }
        if (TextUtils.isEmpty(key)) {
            throw new IllegalArgumentException("'key' cannot be null or empty!");
        }
        if (bitmap == null) {
            throw new IllegalArgumentException("'bitmap' cannot be null!");
        }
        File cacheDir = context.getExternalCacheDir();
        File cacheFileBaseDir = new File(cacheDir, BASE_DIR);
        if (!cacheFileBaseDir.exists()) {
            if (!cacheFileBaseDir.mkdirs()) {
                Log.e(TAG, "Failed to create cache directory: " + cacheFileBaseDir
                        .getAbsolutePath());
                return;
            }
        }
        long folderSize = getFolderSize(cacheFileBaseDir);
        if (folderSize > MAX_CACHE_SIZE) {
            Log.w(TAG, "Cache may not exceed 100MB!");
            // Emergency, cleanup older than 5 minutes
            deleteBitmapsOlderThan(context, FIVE_MINUTES_MS);
        }
        File cacheFile = new File(cacheFileBaseDir, convertToHashKey(key));
        if (!cacheFile.exists()) {
            // If create file fails, it could mean it exists already, but also wouldn't get here
            // if it didn't exist already
            if (!cacheFile.createNewFile()) {
                Log.w(TAG, "Danger will robinson! Danger!");
                return;
            }
        }
        if (cacheFile.canWrite()) {
            FileOutputStream fos = new FileOutputStream(cacheFile);
            try {
                bitmap.compress(CompressFormat.PNG, 100, fos);
            } finally {
                fos.flush();
                fos.close();
            }
        }
    }

    /**
     * This is used by mark and sweep process
     *
     * @param context {@link Context}
     * @param deltaMs long
     */
    public static void deleteBitmapsOlderThan(Context context, long deltaMs) {
        if (context == null) {
            throw new IllegalArgumentException("'context' cannot be null!");
        }
        File cacheDir = context.getExternalCacheDir();
        File cacheFileBaseDir = new File(cacheDir, BASE_DIR);
        if (cacheFileBaseDir.exists()) {
            for (File file : cacheFileBaseDir.listFiles()) {
                if (file.lastModified() <= (System.currentTimeMillis() - deltaMs)) {
                    file.delete();
                }
            }
        }
    }

}
