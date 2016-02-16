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

package com.cyanogenmod.messaging.ui;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.ImageView;
import com.android.messaging.util.ImageUtils;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.target.BitmapImageViewTarget;
import com.bumptech.glide.request.target.SizeReadyCallback;

/**
 * <pre>
 *     Logic for fetching images from web sites
 * </pre>
 *
 * @see {@link ImageView}
 */
public class WebImageView extends ImageView {

    private BitmapImageViewTarget mBitmapImageViewTarget;

    public WebImageView(Context context) {
        super(context);
    }

    public WebImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public WebImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public WebImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    //------ Interface methods
    public void setImageUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        mBitmapImageViewTarget = new Bullseye(this);
        Glide.with(getContext())
                .load(url)
                .asBitmap()
                .diskCacheStrategy(DiskCacheStrategy.RESULT)
                .into(mBitmapImageViewTarget);
    }

    private class Bullseye extends BitmapImageViewTarget {

        public Bullseye(ImageView view) {
            super(view);
        }

        @Override
        protected void setResource(final Bitmap src) {
            getSize(new SizeReadyCallback() {
                @Override
                public void onSizeReady(int width, int height) {
                    final Bitmap tgt = Bitmap.createBitmap(width, height, Config.ARGB_8888);
                    final Bitmap scaledBitmap = ImageUtils.scaleCenterCrop(src, width, height);
                    RectF dest = new RectF(0, 0, width, height);
                    ImageUtils.drawBitmapWithCircleOnCanvas(scaledBitmap, new Canvas(tgt), dest, dest,
                            null, false, 0, 0);
                    Bullseye.this.view.setImageBitmap(tgt);
                }
            });
        }
    }
}
