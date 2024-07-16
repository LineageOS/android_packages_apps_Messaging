/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.messaging.datamodel.media;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;

import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;

import java.io.InputStream;

public class GifImageResource extends ImageResource {
    private ImageDecoder.Source mImageDecoderSource;

    public GifImageResource(String key, ImageDecoder.Source imageDecoderSource) {
        // GIF does not support exif tags
        super(key, ExifInterface.ORIENTATION_NORMAL);
        mImageDecoderSource = imageDecoderSource;
    }

    public static GifImageResource createGifImageResource(String key, InputStream inputStream) {
        final byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        // prepare mImageDecoderSource
        final ImageDecoder imageDecoder;
        ImageDecoder.Source source = ImageDecoder.createSource(bytes);
        if (source == null) {
            return null;
        }
        return new GifImageResource(key, source);
    }

    @Override
    public Drawable getDrawable(Resources resources) {
        try {
            return (AnimatedImageDrawable) ImageDecoder.decodeDrawable(mImageDecoderSource);
        } catch (final Throwable t) {
            // Malicious gif images can make the platform throw different kind of throwables, such
            // as OutOfMemoryError and NullPointerException. Catch them all.
            LogUtil.e(LogUtil.BUGLE_TAG, "Error getting drawable for GIF", t);
            return null;
        }
    }

    @Override
    public Bitmap getBitmap() {
        Assert.fail("GetBitmap() should never be called on a gif.");
        return null;
    }

    @Override
    public byte[] getBytes() {
        Assert.fail("GetBytes() should never be called on a gif.");
        return null;
    }

    @Override
    public Bitmap reuseBitmap() {
        return null;
    }

    @Override
    public boolean supportsBitmapReuse() {
        return false;
    }

    @Override
    public int getMediaSize() {
        Assert.fail("GifImageResource should not be used by a media cache");
        // Only used by the media cache, which this does not use.
        return 0;
    }

    @Override
    public boolean isCacheable() {
        return false;
    }

    @Override
    protected void close() {
        acquireLock();
        try {
            if (mImageDecoderSource != null) {
                mImageDecoderSource = null;
            }
        } finally {
            releaseLock();
        }
    }
}
