/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.database.Cursor;
import android.support.v7.app.ActionBar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.MediaPickerData;
import com.android.messaging.datamodel.data.MediaPickerData.MediaPickerDataListener;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.util.Assert;
import com.android.messaging.util.OsUtil;

/**
 * Chooser which allows the user to select one or more existing audios
 */
class AudioListChooser extends MediaChooser implements
        AudioListView.AudioListViewListener, MediaPickerDataListener {
    private final AudioListAdapter mAdapter;
    private AudioListView mAudioListView;
    private View mMissingPermissionView;
    private static final String TAG = AudioListChooser.class.getSimpleName();

    AudioListChooser(final MediaPicker mediaPicker) {
        super(mediaPicker);
        mAdapter = new AudioListAdapter(Factory.get().getApplicationContext(), null);
    }

    @Override
    public int getSupportedMediaTypes() {
        return MediaPicker.MEDIA_TYPE_AUDIO;
    }

    @Override
    public View destroyView() {
        mAudioListView.setAdapter(null);
        mAdapter.setHostInterface(null);
        // The loader is started only if startMediaPickerDataLoader() is called
        if (OsUtil.hasStoragePermission()) {
            mBindingRef.getData().destroyLoader(MediaPickerData.GALLERY_AUDIO_LOADER);
        }
        return super.destroyView();
    }

    @Override
    public int getIconResource() {
        return R.drawable.ic_library_music_white_24px;
    }

    @Override
    public int getIconDescriptionResource() {
        return R.string.mediapicker_audioChooserDescription;
    }

    @Override
    public boolean canSwipeDown() {
        return mAudioListView.canSwipeDown();
    }

    @Override
    public void onItemSelected(final MessagePartData item) {
        mMediaPicker.dispatchItemsSelected(item, !mAudioListView.isMultiSelectEnabled());
    }

    @Override
    public void onItemUnselected(final MessagePartData item) {
        mMediaPicker.dispatchItemUnselected(item);
    }

    @Override
    public void onConfirmSelection() {
        // The user may only confirm if multiselect is enabled.
        Assert.isTrue(mAudioListView.isMultiSelectEnabled());
        mMediaPicker.dispatchConfirmItemSelection();
    }

    @Override
    public void onUpdate() {
        mMediaPicker.invalidateOptionsMenu();
    }

    @Override
    public void onCreateOptionsMenu(final MenuInflater inflater, final Menu menu) {
        if (mView != null) {
            mAudioListView.onCreateOptionsMenu(inflater, menu);
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        return (mView != null) ? mAudioListView.onOptionsItemSelected(item) : false;
    }

    @Override
    protected View createView(final ViewGroup container) {
        final LayoutInflater inflater = getLayoutInflater();
        final View view = inflater.inflate(
                R.layout.mediapicker_audio_list_chooser,
                container /* root */,
                false /* attachToRoot */);

        mAudioListView = (AudioListView) view.findViewById(R.id.audio_list_view);
        mAdapter.setHostInterface(mAudioListView);
        mAudioListView.setAdapter(mAdapter);
        mAudioListView.setHostInterface(this);
        mAudioListView.setDraftMessageDataModel(mMediaPicker.getDraftMessageDataModel());
        if (OsUtil.hasStoragePermission()) {
            startMediaPickerDataLoader();
        }

        mMissingPermissionView = view.findViewById(R.id.missing_permission_view);
        updateForPermissionState(OsUtil.hasStoragePermission());
        return view;
    }

    @Override
    int getActionBarTitleResId() {
        return R.string.mediapicker_audio_list_title;
    }

    @Override
    void updateActionBar(final ActionBar actionBar) {
        super.updateActionBar(actionBar);
        if (mAudioListView == null) {
            return;
        }
        final int selectionCount = mAudioListView.getSelectionCount();
        if (selectionCount > 0 && mAudioListView.isMultiSelectEnabled()) {
            actionBar.setTitle(getContext().getResources().getString(
                    R.string.mediapicker_audio_list_title_selection,
                    selectionCount));
        }
    }

    @Override
    public void onMediaPickerDataUpdated(final MediaPickerData mediaPickerData, final Object data,
            final int loaderId) {
        mBindingRef.ensureBound(mediaPickerData);
        Assert.equals(MediaPickerData.GALLERY_AUDIO_LOADER, loaderId);
        Cursor rawCursor = null;
        if (data instanceof Cursor) {
            rawCursor = (Cursor) data;
        }

        mAdapter.swapCursor(rawCursor);
    }

    @Override
    public void onResume() {
        if (OsUtil.hasStoragePermission()) {
            // Work around a bug in MediaStore where cursors querying the Files provider don't get
            // updated for changes to Images.Media or Video.Media.
            startMediaPickerDataLoader();
        }
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

    private void startMediaPickerDataLoader() {
        mBindingRef.getData().startLoader(MediaPickerData.GALLERY_AUDIO_LOADER, mBindingRef, null,
                this);
    }

    @Override
    protected void onRequestPermissionsResult(
            final int requestCode, final String permissions[], final int[] grantResults) {
        if (requestCode == MediaPicker.GALLERY_PERMISSION_REQUEST_CODE) {
            final boolean permissionGranted = grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (permissionGranted) {
                startMediaPickerDataLoader();
            }
            updateForPermissionState(permissionGranted);
        }
    }

    private void updateForPermissionState(final boolean granted) {
        // onRequestPermissionsResult can sometimes get called before createView().
        if (mAudioListView == null) {
            return;
        }

        mAudioListView.setVisibility(granted ? View.VISIBLE : View.GONE);
        mMissingPermissionView.setVisibility(granted ? View.GONE : View.VISIBLE);
    }
}
