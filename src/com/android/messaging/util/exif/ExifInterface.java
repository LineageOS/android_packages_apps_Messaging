/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2024-2025 The LineageOS Project
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

package com.android.messaging.util.exif;

public class ExifInterface {
    /**
     * Constants for ExifInterface.TAG_ORIENTATION. They can be interpreted as
     * follows:
     * <ul>
     * <li>TOP_LEFT is the normal orientation.</li>
     * <li>TOP_RIGHT is a left-right mirror.</li>
     * <li>BOTTOM_LEFT is a 180 degree rotation.</li>
     * <li>BOTTOM_RIGHT is a top-bottom mirror.</li>
     * <li>LEFT_TOP is mirrored about the top-left<->bottom-right axis.</li>
     * <li>RIGHT_TOP is a 90 degree clockwise rotation.</li>
     * <li>LEFT_BOTTOM is mirrored about the top-right<->bottom-left axis.</li>
     * <li>RIGHT_BOTTOM is a 270 degree clockwise rotation.</li>
     * </ul>
     */
    public interface Orientation {
        short TOP_LEFT = 1;
        short TOP_RIGHT = 2;
        short BOTTOM_LEFT = 3;
        short BOTTOM_RIGHT = 4;
        short LEFT_TOP = 5;
        short RIGHT_TOP = 6;
        short LEFT_BOTTOM = 7;
        short RIGHT_BOTTOM = 8;
    }

    public ExifInterface() {
    }

    public static OrientationParams getOrientationParams(int orientation) {
        OrientationParams params = new OrientationParams();
        switch (orientation) {
            case Orientation.TOP_RIGHT:     // Flip horizontal
                params.scaleX = -1;
                break;
            case Orientation.BOTTOM_RIGHT:  // Flip vertical
                params.scaleY = -1;
                break;
            case Orientation.BOTTOM_LEFT:   // Rotate 180
                params.rotation = 180;
                break;
            case Orientation.RIGHT_BOTTOM:  // Rotate 270
                params.rotation = 270;
                params.invertDimensions = true;
                break;
            case Orientation.RIGHT_TOP:     // Rotate 90
                params.rotation = 90;
                params.invertDimensions = true;
                break;
            case Orientation.LEFT_TOP:      // Transpose
                params.rotation = 90;
                params.scaleX = -1;
                params.invertDimensions = true;
                break;
            case Orientation.LEFT_BOTTOM:   // Transverse
                params.rotation = 270;
                params.scaleX = -1;
                params.invertDimensions = true;
                break;
        }
        return params;
    }

    public static class OrientationParams {
        public int rotation = 0;
        public int scaleX = 1;
        public int scaleY = 1;
        public boolean invertDimensions = false;
    }
}
