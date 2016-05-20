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

package com.android.messaging.datamodel.data;

import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.provider.BaseColumns;
import android.provider.MediaStore.Images.Media;
import android.text.TextUtils;
import android.util.Log;
import com.android.messaging.datamodel.media.FileImageRequestDescriptor;
import com.android.messaging.datamodel.media.ImageRequest;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.datamodel.media.VideoThumbnailRequestDescriptor;
import com.android.messaging.util.Assert;
import com.android.messaging.util.UriUtil;

/**
 * Provides data for GalleryGridItemView
 */
public class AudioListItemData {
    private static final String TAG = AudioListItemData.class.getSimpleName();
    public static final String[] AUDIO_PROJECTION = new String[] {
        Media._ID,
        Media.DATA,
        Media.MIME_TYPE,
        Media.DATE_MODIFIED};

    public static final String[] SPECIAL_ITEM_COLUMNS = new String[] {
        BaseColumns._ID
    };

    private static final int INDEX_ID = 0;

    // For local image gallery.
    private static final int INDEX_DATA_PATH = 1;
    private static final int INDEX_MIME_TYPE = 2;
    private static final int INDEX_DATE_MODIFIED = 3;

    private Uri mAudioUri;
    private String mContentType;
    private long mDateSeconds;

    public AudioListItemData() {
    }

    public void bind(final Cursor cursor) {
        mContentType = cursor.getString(INDEX_MIME_TYPE);
        final String dateModified = cursor.getString(INDEX_DATE_MODIFIED);
        mDateSeconds = !TextUtils.isEmpty(dateModified) ? Long.parseLong(dateModified) : -1;
        mAudioUri = UriUtil.getUriForResourceFile(cursor.getString(INDEX_DATA_PATH));
    }

    public Uri getAudioUri() {
        return mAudioUri;
    }

    public String getAudioFilename() {
        return mAudioUri.getLastPathSegment();
    }

    public MessagePartData constructMessagePartData(final Rect startRect) {
        return new MediaPickerMessagePartData(startRect, mContentType,
                mAudioUri, 0, 0);
    }

    /**
     * @return The date in seconds. This can be negative if we could not retreive date info
     */
    public long getDateSeconds() {
        return mDateSeconds;
    }

    public String getContentType() {
        return mContentType;
    }
}
