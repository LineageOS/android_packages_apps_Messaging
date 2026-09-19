/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.ui.rcs;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.R;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.util.LogUtil;

/**
 * The RCS terms-of-service consent dialog, shown when the provider reports
 * {@code onCarrierTosStateChanged(TOS_REQUIRED)}. The prompt text comes from the provider; this
 * activity only renders it and returns the verdict with {@link ProviderTransport#submitTosConsent},
 * a safe no-op if the provider is unbound. An activity rather than a dialog so a notification can
 * open it while the app is in the background.
 */
public final class RcsCarrierTosActivity extends AppCompatActivity {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private int mSubId = -1;
    @Nullable private AlertDialog mDialog;

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        // singleTop: a fresh prompt (for example another subscription) replaces the shown one.
        setIntent(intent);
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        showFromIntent(intent);
    }

    private void showFromIntent(@Nullable final Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        mSubId = intent.getIntExtra(RcsConstants.EXTRA_TOS_SUB_ID, -1);
        final String title = intent.getStringExtra(RcsConstants.EXTRA_TOS_TITLE);
        final String body = intent.getStringExtra(RcsConstants.EXTRA_TOS_BODY);
        final String acceptLabel = intent.getStringExtra(RcsConstants.EXTRA_TOS_ACCEPT_LABEL);
        final String rejectLabel = intent.getStringExtra(RcsConstants.EXTRA_TOS_REJECT_LABEL);

        final CharSequence accept = !TextUtils.isEmpty(acceptLabel)
                ? acceptLabel : getString(R.string.rcs_tos_accept);
        final CharSequence reject = !TextUtils.isEmpty(rejectLabel)
                ? rejectLabel : getString(R.string.rcs_tos_reject);

        final AlertDialog.Builder b = new AlertDialog.Builder(this);
        if (!TextUtils.isEmpty(title)) {
            b.setTitle(title);
        } else {
            b.setTitle(R.string.rcs_tos_settings_title);
        }
        if (!TextUtils.isEmpty(body)) {
            b.setMessage(body);
        }
        b.setCancelable(false);
        b.setPositiveButton(accept, (DialogInterface d, int which) -> submit(true));
        b.setNegativeButton(reject, (DialogInterface d, int which) -> submit(false));
        b.setOnDismissListener((DialogInterface d) -> {
            mDialog = null;
            finish();
        });
        mDialog = b.show();
    }

    private void submit(final boolean accept) {
        final ProviderTransport transport = ProviderTransport.peekInstance();
        if (transport != null) {
            LogUtil.i(TAG, "RcsCarrierTosActivity: submitTosConsent sub=" + mSubId
                    + " accept=" + accept);
            transport.submitTosConsent(mSubId, accept);
        } else {
            LogUtil.w(TAG, "RcsCarrierTosActivity: provider not bound; decision dropped");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroy();
    }
}
