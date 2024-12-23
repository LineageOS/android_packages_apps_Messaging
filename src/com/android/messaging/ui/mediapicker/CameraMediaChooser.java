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

package com.android.messaging.ui.mediapicker;

import static com.android.messaging.util.ContentType.VIDEO_UNSPECIFIED;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.android.messaging.R;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.data.MediaPickerMessagePartData;
import com.android.messaging.util.OsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooser which allows the user to take pictures or video without leaving the current app/activity
 */
class CameraMediaChooser extends MediaChooser {
    private View mEnabledView;
    private View mMissingPermissionView;
    private final ActivityResultLauncher<Uri> mPictureLauncher;
    private final ActivityResultLauncher<Uri> mVideoLauncher;
    private final ActivityResultLauncher<String> mRequestPermissionLauncher;
    private final DocumentImagePicker mDocumentImagePicker;
    private Uri mOutputUri;

    CameraMediaChooser(final MediaPicker mediaPicker) {
        super(mediaPicker);
        mDocumentImagePicker = new DocumentImagePicker(mMediaPicker, data -> {
            if (mBindingRef.isBound()) {
                mMediaPicker.dispatchPendingItemAdded(data);
            }
        });
        mPictureLauncher = mediaPicker.registerForActivityResult(
                new ActivityResultContracts.TakePicture(), result -> {
                    if (result) {
                        List<Uri> list = new ArrayList<>();
                        list.add(mOutputUri);
                        mDocumentImagePicker.onDocumentsPicked(list);
                    }
                });
        mVideoLauncher = mediaPicker.registerForActivityResult(
                new ActivityResultContracts.CaptureVideo(), result -> {
                    if (result) {
                        final Rect startRect = new Rect();
                        // It's possible to throw out the chooser while taking the
                        // picture/video. In that case, still use the attachment, just
                        // skip the startRect
                        if (mView != null) {
                            mView.getGlobalVisibleRect(startRect);
                        }
                        mMediaPicker.dispatchItemsSelected(
                                new MediaPickerMessagePartData(startRect, VIDEO_UNSPECIFIED,
                                        mOutputUri, MessagingContentProvider.UNSPECIFIED_SIZE,
                                        MessagingContentProvider.UNSPECIFIED_SIZE),
                                true /* dismissMediaPicker */);
                    }
                });
        mRequestPermissionLauncher = mediaPicker.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    updateForPermissionState(granted);
                });
    }

    @Override
    public int getSupportedMediaTypes() {
        Activity activity = mMediaPicker.getActivity();
        if (activity == null) {
            return MediaPicker.MEDIA_TYPE_NONE;
        }
        PackageManager pm = activity.getPackageManager();
        if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            return MediaPicker.MEDIA_TYPE_IMAGE | MediaPicker.MEDIA_TYPE_VIDEO;
        } else {
            return MediaPicker.MEDIA_TYPE_NONE;
        }
    }

    @Override
    public View destroyView() {
        return super.destroyView();
    }

    @Override
    protected View createView(final ViewGroup container) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(
                R.layout.mediapicker_camera_chooser,
                container /* root */,
                false /* attachToRoot */);
        mEnabledView = view.findViewById(R.id.mediapicker_enabled);
        mMissingPermissionView = view.findViewById(R.id.missing_permission_view);
        View takePictureView = view.findViewById(R.id.take_picture);
        takePictureView.setOnClickListener(v -> {
            mOutputUri = MediaScratchFileProvider.buildMediaScratchSpaceUri(null);
            mPictureLauncher.launch(mOutputUri);
        });
        View takeVideoView = view.findViewById(R.id.take_video);
        takeVideoView.setOnClickListener(v -> {
            if (!OsUtil.hasRecordAudioPermission()) {
                requestRecordAudioPermission();
            } else {
                mOutputUri = MediaScratchFileProvider.buildMediaScratchSpaceUri(null);
                mVideoLauncher.launch(mOutputUri);
            }
        });
        updateForPermissionState(hasCameraPermission());
        return view;
    }

    @Override
    public int getIconResource() {
        return R.drawable.ic_camera_light;
    }

    @Override
    public int getIconDescriptionResource() {
        return R.string.mediapicker_cameraChooserDescription;
    }

    @Override
    protected void setSelected(final boolean selected) {
        super.setSelected(selected);
        if (selected) {
            if (!hasCameraPermission()) {
                requestCameraPermission();
            }
        }
    }

    private void requestCameraPermission() {
        mRequestPermissionLauncher.launch(Manifest.permission.CAMERA);
    }

    private void requestRecordAudioPermission() {
        mRequestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void updateForPermissionState(final boolean granted) {
        // onRequestPermissionsResult can sometimes get called before createView().
        if (mEnabledView == null) {
            return;
        }

        mEnabledView.setVisibility(granted ? View.VISIBLE : View.GONE);
        mMissingPermissionView.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    @Override
    int getActionBarTitleResId() {
        return 0;
    }

    private static boolean hasCameraPermission() {
        return OsUtil.hasPermission(Manifest.permission.CAMERA);
    }
}
