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

package com.android.messaging.ui.mediapicker;

import android.Manifest;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.messaging.R;
import com.android.messaging.util.OsUtil;

/**
 * Chooser which allows the user to select one or more existing images or videos or audios.
 */
class GalleryMediaChooser extends MediaChooser {

    private View mEnabledView;
    private View mMissingPermissionView;

    /** Handles picking a media from the document picker. */
    private final DocumentImagePicker mDocumentImagePicker;

    GalleryMediaChooser(final MediaPicker mediaPicker) {
        super(mediaPicker);
        mDocumentImagePicker = new DocumentImagePicker(mMediaPicker, data -> {
            if (mBindingRef.isBound()) {
                mMediaPicker.dispatchPendingItemAdded(data);
            }
        });
    }

    @Override
    public int getSupportedMediaTypes() {
        return (MediaPicker.MEDIA_TYPE_IMAGE
                | MediaPicker.MEDIA_TYPE_VIDEO
                | MediaPicker.MEDIA_TYPE_AUDIO);
    }

    @Override
    public int getIconResource() {
        return R.drawable.ic_image_light;
    }

    @Override
    public int getIconDescriptionResource() {
        return R.string.mediapicker_galleryChooserDescription;
    }

    @Override
    protected View createView(final ViewGroup container) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(
                R.layout.mediapicker_gallery_chooser,
                container /* root */,
                false /* attachToRoot */);

        mEnabledView = view.findViewById(R.id.mediapicker_enabled);
        mMissingPermissionView = view.findViewById(R.id.missing_permission_view);
        mEnabledView.setOnClickListener(v -> {
            // Launch an external picker to pick item from document picker as attachment.
            mDocumentImagePicker.launchPicker();
        });
        updateForPermissionState(OsUtil.hasStoragePermission());
        return view;
    }

    @Override
    int getActionBarTitleResId() {
        return R.string.mediapicker_gallery_title;
    }

    @Override
    protected void setSelected(final boolean selected) {
        super.setSelected(selected);
        if (selected && !OsUtil.hasStoragePermission()) {
            mMediaPicker.requestPermissions(
                    new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                    MediaPicker.GALLERY_PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    protected void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == MediaPicker.GALLERY_PERMISSION_REQUEST_CODE) {
            final boolean permissionGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
            updateForPermissionState(permissionGranted);
        }
    }

    private void updateForPermissionState(final boolean granted) {
        // onRequestPermissionsResult can sometimes get called before createView().
        mEnabledView.setVisibility(granted ? View.VISIBLE : View.GONE);
        mMissingPermissionView.setVisibility(granted ? View.GONE : View.VISIBLE);
    }
}
