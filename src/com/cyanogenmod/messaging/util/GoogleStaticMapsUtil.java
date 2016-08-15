/*
 * Copyright (C) 2016 The CyanogenMod Project
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

package com.cyanogenmod.messaging.util;

import android.content.Context;
import com.android.messaging.R;

public class GoogleStaticMapsUtil {
    private static final String STATIC_MAPS_URL = "https://maps.googleapis.com/maps/api/staticmap?";

    private static final String AND = "&";
    private static final String PIPE = "|";
    private static final String X = "x";

    private static final String SIZE = "size=";
    private static final String ZOOM = "zoom=17";
    private static final String SCALE = "scale=4";
    private static final String API_KEY = "key=";

    private static final String PICK_UP_MARKER =
            "markers=icon:http://cdn.cyngn.com/ridesharing/pickup_marker.png";

    public static String getStaticMapsUrl(Context context, int width, int height, String encodedAddress) {
        final StringBuilder sb = new StringBuilder(STATIC_MAPS_URL);
        sb.append(AND);
        sb.append(SIZE);
        sb.append(width);
        sb.append(X);
        sb.append(height);
        sb.append(AND);
        sb.append(ZOOM);
        sb.append(AND);
        sb.append(SCALE);
        sb.append(AND);
        sb.append(API_KEY);
        sb.append(context.getString(R.string.google_maps_key));
        sb.append(AND);
        sb.append(PICK_UP_MARKER);
        sb.append(PIPE);
        sb.append(encodedAddress);

        return sb.toString();
    }
}
