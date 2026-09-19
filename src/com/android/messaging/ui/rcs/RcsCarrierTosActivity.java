/*
 * Copyright (C) 2026 The LineageOS Project
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
package com.android.messaging.ui.rcs;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.R;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.util.LogUtil;

/**
 * Modal carrier/Google RCS Terms-of-Service consent dialog.
 *
 * <p>Surfaced from {@link com.android.messaging.rcs.RcsCarrierTosReceiver} when
 * the provider reports {@code onCarrierTosStateChanged(TOS_REQUIRED)} -- the
 * Pev3 ACS returned a gating {@code <MSG>} ServerMessage (accept + reject) and
 * the provider is blocked on the user's decision. The prompt text comes off the
 * wire (carrier-ToS) or from GMS (Google-ToS); we only render it and feed the
 * accept/reject verdict back via {@link ProviderTransport#submitTosConsent}.
 *
 * <p>A dialog-themed Activity (not a bare Dialog) so it can be launched from a
 * notification when Messaging is backgrounded -- provisioning runs headless at
 * app start, so the prompt can arrive when no RCS Activity is foregrounded.
 * Additive: it never touches the SMS/MMS path. If the provider is unbound when
 * the user acts, {@link ProviderTransport#submitTosConsent} is a safe no-op.
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
        // singleTop: a fresh TOS_REQUIRED (e.g. a different sub) replaces the
        // currently-shown prompt rather than stacking a second Activity.
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

    /** True when this state is the user-actionable gating state. */
    public static boolean isActionable(final int tosState) {
        return tosState == IRcsProviderCallback.TOS_REQUIRED;
    }
}
