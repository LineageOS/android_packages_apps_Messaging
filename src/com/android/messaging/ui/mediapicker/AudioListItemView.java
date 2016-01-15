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

import android.content.Context;
import android.database.Cursor;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.data.AudioListItemData;
import com.google.common.annotations.VisibleForTesting;

/**
 * Shows an item in the audio picker list view. Hosts an FileImageView with a checkbox.
 */
public class AudioListItemView extends LinearLayout {
    private static final String TAG = AudioListItemView.class.getSimpleName();
    /**
     * Implemented by the owner of this ListItemView instance to communicate on media
     * picking and selection events.
     */
    public interface HostInterface {
        void onItemClicked(View view, AudioListItemData data, boolean longClick);
        boolean isItemSelected(AudioListItemData data);
        boolean isMultiSelectEnabled();
    }

    @VisibleForTesting
    AudioListItemData mData;
    TextView mAudioFilename;
    CheckBox mCheckBox;
    ImageView mImageIcon;

    private HostInterface mHostInterface;
    private final OnClickListener mOnClickListener = new OnClickListener() {
        @Override
        public void onClick(final View v) {
            mHostInterface.onItemClicked(AudioListItemView.this, mData, false /*longClick*/);
        }
    };

    public AudioListItemView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mData = DataModel.get().createAudioListItemData();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mAudioFilename = (TextView)findViewById(R.id.audio_filename);
        mImageIcon = (ImageView) findViewById(R.id.audio_button);

        mCheckBox = (CheckBox)findViewById(R.id.audio_checkbox);
        mCheckBox.setOnClickListener(mOnClickListener);
        setOnClickListener(mOnClickListener);

        final OnLongClickListener longClickListener = new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View v) {
                mHostInterface.onItemClicked(v, mData, true /* longClick */);
                return true;
            }
        };
        setOnLongClickListener(longClickListener);
        mCheckBox.setOnLongClickListener(longClickListener);
    }


    public void bind(final Cursor cursor, final HostInterface hostInterface) {
        mData.bind(cursor);
        mHostInterface = hostInterface;
        updateViewState();
    }

    private void updateViewState() {
        updateListItemView();
        if (mHostInterface.isMultiSelectEnabled()) {
            mCheckBox.setVisibility(VISIBLE);
            mCheckBox.setClickable(true);
            mCheckBox.setChecked(mHostInterface.isItemSelected(mData));
            mImageIcon.setVisibility(GONE);
        } else {
            mCheckBox.setVisibility(GONE);
            mCheckBox.setClickable(false);
            mImageIcon.setVisibility(VISIBLE);
        }
    }

    private void updateListItemView() {
        mAudioFilename.setText(mData.getAudioFilename());
    }

}
