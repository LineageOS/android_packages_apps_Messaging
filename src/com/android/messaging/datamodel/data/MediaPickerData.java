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

package com.android.messaging.datamodel.data;

import android.content.Context;

import com.android.messaging.datamodel.binding.BindableData;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.BuglePrefsKeys;

/**
 * Services data needs for MediaPicker.
 */
public class MediaPickerData extends BindableData {
    private final Context mContext;

    public MediaPickerData(final Context context) {
        mContext = context;
    }

    /**
     * Gets the last selected chooser index, or -1 if no selection has been saved.
     */
    public int getSelectedChooserIndex() {
        return BuglePrefs.getApplicationPrefs().getInt(
                BuglePrefsKeys.SELECTED_MEDIA_PICKER_CHOOSER_INDEX,
                BuglePrefsKeys.SELECTED_MEDIA_PICKER_CHOOSER_INDEX_DEFAULT);
    }

    /**
     * Saves the selected media chooser index.
     * @param selectedIndex the selected media chooser index.
     */
    public void saveSelectedChooserIndex(final int selectedIndex) {
        BuglePrefs.getApplicationPrefs().putInt(BuglePrefsKeys.SELECTED_MEDIA_PICKER_CHOOSER_INDEX,
                selectedIndex);
    }

    @Override
    protected void unregisterListeners() {

    }
}
