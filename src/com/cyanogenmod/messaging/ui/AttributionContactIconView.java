/*
 * Copyright (C) 2015 The CyanogenMod Project
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
package com.cyanogenmod.messaging.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.util.AttributeSet;
import com.android.messaging.ui.ContactIconView;

/**
 * <pre>
 *     Contact icon view with a lookup provider attribution
 * </pre>
 * @see {@link ContactIconView}
 */
public class AttributionContactIconView extends ContactIconView {

    // Constants
    private static final double ATTRIBUTION_SIZE_PERCENTAGE = .35; // Scales the drawable

    // Members
    private Drawable mAttributionDrawable;
    private Rect mAttributionRect = new Rect();

    public AttributionContactIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Set the attribution drawable
     *
     * @param resId {@link Integer}
     */
    public void setAttributionDrawable(int resId) {
        setAttributionDrawable(getContext().getDrawable(resId));
    }

    /**
     * Set the attribution drawable
     *
     * @param drawable {@link Drawable}
     */
    public void setAttributionDrawable(Drawable drawable) {
        mAttributionDrawable = drawable;
        invalidate();
    }

    /**
     * Get the attribution drawable
     *
     * @return {@link Drawable} or {@link LayerDrawable}
     */
    public Drawable getAttributionDrawable() {
        return mAttributionDrawable;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // We only want to calculate this once
        if (mAttributionDrawable != null) {
            canvas.getClipBounds(mAttributionRect);
            double l = canvas.getWidth() - (canvas.getWidth() * ATTRIBUTION_SIZE_PERCENTAGE);
            double t = canvas.getHeight() - (canvas.getHeight() * ATTRIBUTION_SIZE_PERCENTAGE);
            double r = canvas.getWidth();
            double b = canvas.getHeight();
            mAttributionRect.left = (int)l;
            mAttributionRect.top = (int)t;
            mAttributionRect.right = (int)r;
            mAttributionRect.bottom = (int)b;
            mAttributionDrawable.setBounds(mAttributionRect);
            mAttributionDrawable.draw(canvas);
        }
    }

}
