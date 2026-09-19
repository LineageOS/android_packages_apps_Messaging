/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2026 The LineageOS Project
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
package com.android.messaging.ui.conversationsettings;

import android.content.Context;
import android.database.Cursor;
import androidx.appcompat.widget.SwitchCompat;

import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.datamodel.data.PeopleOptionsItemData;
import com.android.messaging.util.Assert;

/**
 * The view for a single entry in the options section of people & options activity.
 */
public class PeopleOptionsItemView extends LinearLayout {
    /**
     * Implemented by the host of this view that handles options click event.
     */
    public interface HostInterface {
        void onOptionsItemViewClicked(PeopleOptionsItemData item);
    }

    private TextView mTitle;
    private TextView mSubtitle;
    private SwitchCompat mSwitch;
    private final PeopleOptionsItemData mData;
    private HostInterface mHostInterface;

    public PeopleOptionsItemView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        mData = DataModel.get().createPeopleOptionsItemData(context);
    }

    @Override
    protected void onFinishInflate () {
        super.onFinishInflate();
        mTitle = findViewById(R.id.title);
        mSubtitle = findViewById(R.id.subtitle);
        setOnClickListener(v -> mHostInterface.onOptionsItemViewClicked(mData));
    }

    public void bind(final Cursor cursor, final int settingType, ParticipantData otherParticipant,
            final String conversationId, final HostInterface hostInterface) {
        Assert.isTrue(settingType < PeopleOptionsItemData.SETTINGS_COUNT && settingType >= 0);
        mData.bind(cursor, otherParticipant, settingType, conversationId);
        mHostInterface = hostInterface;

        mTitle.setText(mData.getTitle());

        final String subtitle = mData.getSubtitle();
        if (mSubtitle != null) {
            if (TextUtils.isEmpty(subtitle)) {
                mSubtitle.setVisibility(View.GONE);
            } else {
                mSubtitle.setText(subtitle);
                mSubtitle.setVisibility(View.VISIBLE);
            }
        }
    }
}
