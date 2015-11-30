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
package com.android.messaging.ui.contact;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.messaging.BugleApplication;
import com.cyanogenmod.messaging.lookup.LookupProviderManager.LookupProviderListener;
import com.android.messaging.R;
import com.cyanogenmod.messaging.ui.AttributionContactIconView;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.AccessibilityUtil;
import com.cyanogen.lookup.phonenumber.response.LookupResponse;

public class AddContactsConfirmationDialog implements DialogInterface.OnClickListener,
        LookupProviderListener {
    private final Context mContext;
    private final Uri mAvatarUri;
    private final String mNormalizedDestination;
    private AttributionContactIconView mContactIconView;
    private TextView mTextView;

    public AddContactsConfirmationDialog(final Context context, final Uri avatarUri,
            final String normalizedDestination) {
        mContext = context;
        mAvatarUri = avatarUri;
        mNormalizedDestination = normalizedDestination;
        BugleApplication.getLookupProviderClient().addLookupProviderListener
                (mNormalizedDestination, this);
    }

    public void show() {
        final int confirmAddContactStringId = R.string.add_contact_confirmation;
        final int cancelStringId = android.R.string.cancel;
        final AlertDialog alertDialog = new AlertDialog.Builder(mContext)
        .setTitle(R.string.add_contact_confirmation_dialog_title)
        .setView(createBodyView())
        .setPositiveButton(confirmAddContactStringId, this)
        .setNegativeButton(cancelStringId, this)
         .setOnCancelListener(new OnCancelListener() {
             @Override
             public void onCancel(DialogInterface dialog) {
                 BugleApplication.getLookupProviderClient().removeLookupProviderListener
                         (mNormalizedDestination, AddContactsConfirmationDialog.this);
             }
         })
        .create();
        alertDialog.show();
        final Resources resources = mContext.getResources();
        final Button cancelButton = alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (cancelButton != null) {
            cancelButton.setTextColor(resources.getColor(R.color.contact_picker_button_text_color));
        }
        final Button addButton = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (addButton != null) {
            addButton.setTextColor(resources.getColor(R.color.contact_picker_button_text_color));
        }
    }

    @Override
    public void onClick(final DialogInterface dialog, final int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                UIIntents.get().launchAddContactActivity(mContext, mNormalizedDestination);
                break;
            case DialogInterface.BUTTON_NEGATIVE:
            default:
                break;
        }
        BugleApplication.getLookupProviderClient().removeLookupProviderListener
                (mNormalizedDestination, this);
    }

    private View createBodyView() {
        final View view = LayoutInflater.from(mContext).inflate(
                R.layout.add_contacts_confirmation_dialog_body, null);
        mContactIconView = (AttributionContactIconView) view.findViewById(R.id.contact_icon);
        if (mContactIconView != null) {
            mContactIconView.setImageResourceUri(mAvatarUri);
        }
        mTextView = (TextView) view.findViewById(R.id.participant_name);
        if (mTextView != null) {
            mTextView.setText(mNormalizedDestination);
        }
        // Accessibility reason : in case phone numbers are mixed in the display name,
        // we need to vocalize it for talkback.
        final String vocalizedDisplayName = AccessibilityUtil.getVocalizedPhoneNumber(
                mContext.getResources(), mNormalizedDestination);
        if (mTextView != null) {
            mTextView.setContentDescription(vocalizedDisplayName);
        }

        BugleApplication.getLookupProviderClient()
                .lookupInfoForPhoneNumber(mNormalizedDestination);

        return view;
    }

    @Override
    public void onNewInfoAvailable(LookupResponse response) {
        if (response != null) {
            if (mNormalizedDestination != null && mNormalizedDestination.equals(response.mNumber)) {
                if (mContactIconView != null) {
                    mContactIconView.setAttributionDrawable(response.mAttributionLogo);
                }
                if (!TextUtils.isEmpty(response.mName)) {
                    if (mTextView != null) {
                        mTextView.setText(response.mName);
                    }
                    // Accessibility reason : in case phone numbers are mixed in the display name,
                    // we need to vocalize it for talkback.
                    final String vocalizedDisplayName = AccessibilityUtil.getVocalizedPhoneNumber(
                            mContext.getResources(), mNormalizedDestination);
                    if (mTextView != null) {
                        mTextView.setContentDescription(vocalizedDisplayName);
                    }
                }
                if (!TextUtils.isEmpty(response.mPhotoUrl)) {
                    mContactIconView.setImageUrl(response.mPhotoUrl);
                }
                mContactIconView.setLookupResponse(response);
            }
        }
    }

}
