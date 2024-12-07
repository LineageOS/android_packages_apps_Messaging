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
package com.android.messaging.ui.animation;

import android.animation.RectEvaluator;
import android.animation.TypeEvaluator;
import android.graphics.Rect;

/**
 * This evaluator can be used to perform type interpolation between <code>Rect</code> values.
 */
public class RectEvaluatorCompat implements TypeEvaluator<Rect> {
    public static TypeEvaluator<Rect> create() {
        return new RectEvaluator();
    }

    @Override
    public Rect evaluate(float fraction, Rect startValue, Rect endValue) {
        int left = startValue.left + (int) ((endValue.left - startValue.left) * fraction);
        int top = startValue.top + (int) ((endValue.top - startValue.top) * fraction);
        int right = startValue.right + (int) ((endValue.right - startValue.right) * fraction);
        int bottom = startValue.bottom + (int) ((endValue.bottom - startValue.bottom) * fraction);
        return new Rect(left, top, right, bottom);
    }
}
