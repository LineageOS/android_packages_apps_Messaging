/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.messaging.util.LogUtil;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class stores the EXIF header in IFDs according to the JPEG
 * specification. It is the result produced by {@link ExifReader}.
 *
 * @see ExifReader
 * @see IfdData
 */
class ExifData {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private final IfdData[] mIfdDatas = new IfdData[IfdId.TYPE_IFD_COUNT];
    private byte[] mThumbnail;
    private final ArrayList<byte[]> mStripBytes = new ArrayList<>();
    private final ByteOrder mByteOrder;

    ExifData(ByteOrder order) {
        mByteOrder = order;
    }

    /**
     * Sets the compressed thumbnail.
     */
    protected void setCompressedThumbnail(byte[] thumbnail) {
        mThumbnail = thumbnail;
    }

    /**
     * Adds an uncompressed strip.
     */
    protected void setStripBytes(int index, byte[] strip) {
        if (index < mStripBytes.size()) {
            mStripBytes.set(index, strip);
        } else {
            for (int i = mStripBytes.size(); i < index; i++) {
                mStripBytes.add(null);
            }
            mStripBytes.add(strip);
        }
    }

    /**
     * Returns the {@link IfdData} object corresponding to a given IFD if it
     * exists or null.
     */
    protected IfdData getIfdData(int ifdId) {
        if (ExifTag.isValidIfd(ifdId)) {
            return mIfdDatas[ifdId];
        }
        return null;
    }

    /**
     * Adds IFD data. If IFD data of the same type already exists, it will be
     * replaced by the new data.
     */
    protected void addIfdData(IfdData data) {
        mIfdDatas[data.getId()] = data;
    }

    /**
     * Returns the tag with a given TID in the given IFD if the tag exists.
     * Otherwise returns null.
     */
    protected ExifTag getTag(short tag, int ifd) {
        IfdData ifdData = mIfdDatas[ifd];
        return (ifdData == null) ? null : ifdData.getTag(tag);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof ExifData) {
            ExifData data = (ExifData) obj;
            if (data.mByteOrder != mByteOrder ||
                    data.mStripBytes.size() != mStripBytes.size() ||
                    !Arrays.equals(data.mThumbnail, mThumbnail)) {
                return false;
            }
            for (int i = 0; i < mStripBytes.size(); i++) {
                if (!Arrays.equals(data.mStripBytes.get(i), mStripBytes.get(i))) {
                    return false;
                }
            }
            for (int i = 0; i < IfdId.TYPE_IFD_COUNT; i++) {
                IfdData ifd1 = data.getIfdData(i);
                IfdData ifd2 = getIfdData(i);
                if (ifd1 != ifd2 && ifd1 != null && !ifd1.equals(ifd2)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

}
