/*
* Copyright (C) 2015 The CyanogenMod Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.messaging.util;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import com.android.messaging.BugleApplication;
import com.android.messaging.R;

/**
 * <pre>
 *
 * </pre>
 */
public class DialogUtil {
    /**
     * Creates a dialog to handle contact blocking confirmation
     *
     * @param context {@link Context}
     * @param positiveClickListener {@link DialogInterface.OnClickListener}
     *
     * @return {@link Dialog}
     */
    public static Dialog createBlockContactConfirmationDialog(Context context,
                                            String title,
                                            DialogInterface.OnClickListener positiveClickListener) {

        return createBlockContactConfirmationDialog(context, title, positiveClickListener, null,
                null);
    }

    /**
     * Creates a dialog to handle contact blocking confirmation
     *
     * @param context {@link Context}
     * @param positiveClickListener {@link DialogInterface.OnClickListener}
     * @param negativeClickListener {@link DialogInterface.OnClickListener}
     * @param onCancelListener {@link DialogInterface.OnCancelListener}
     *
     * @return {@link Dialog}
     */
    public static Dialog createBlockContactConfirmationDialog(Context context,
                                            String title,
                                            DialogInterface.OnClickListener positiveClickListener,
                                            DialogInterface.OnClickListener negativeClickListener,
                                            DialogInterface.OnCancelListener onCancelListener) {
        if (context == null) {
            throw new IllegalArgumentException("'context' cannot be null!");
        }

        View view = LayoutInflater.from(context)
                .inflate(R.layout.dialog_confirm_block_contact, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(context.getString(R.string.block_confirmation_message))
                .setNegativeButton(android.R.string.cancel, negativeClickListener)
                .setPositiveButton(android.R.string.ok, positiveClickListener)
                .setOnCancelListener(onCancelListener);

        if (view != null && BugleApplication.getLookupProviderClient().hasSpamReporting()) {
            builder.setView(view);
            CheckBox checkBox = (CheckBox) view.findViewById(R.id.chkReportSpam);
            String reportSpamBlurb = context.getString(R.string.report_as_spam_to, BugleApplication
                    .getLookupProviderClient().getProviderName());
            checkBox.setText(reportSpamBlurb);
        }

        return builder.create();

    }

}
