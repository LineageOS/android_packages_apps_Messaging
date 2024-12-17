/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.messaging.mmslib;

import android.net.Uri;
import android.provider.BaseColumns;

/**
 * The Download Manager
 */
public final class Downloads {
    private Downloads() {}

    /**
     * Implementation details
     *
     * Exposes constants used to interact with the download manager's
     * content provider.
     * The constants URI ... STATUS are the names of columns in the downloads table.
     *
     * @hide
     */
    public static final class Impl implements BaseColumns {
        private Impl() {
        }

        /**
         * The content:// URI to access downloads owned by the caller's UID.
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://downloads/my_downloads");

        /**
         * This download has successfully completed.
         * Warning: there might be other status values that indicate success
         * in the future.
         * Use isSucccess() to capture the entire category.
         */
        public static final int STATUS_SUCCESS = 200;

        /**
         * This download can't be performed because the content type cannot be
         * handled.
         */
        public static final int STATUS_NOT_ACCEPTABLE = 406;

        /**
         * This download has completed with an error.
         * Warning: there will be other status values that indicate errors in
         * the future. Use isStatusError() to capture the entire category.
         */
        public static final int STATUS_UNKNOWN_ERROR = 491;

        /**
         * This download couldn't be completed because of a storage issue.
         * Typically, that's because the filesystem is missing or full.
         */
        public static final int STATUS_FILE_ERROR = 492;
    }
}
