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

package com.android.messaging.ui.appsettings;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NavUtils;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.R;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsConstants;
import com.android.messaging.rcs.RouteSelector;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.rcs.RcsCarrierTosActivity;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

/**
 * User-facing "RCS chats" settings. Leads with the feature toggles (enable RCS,
 * typing indicators, read receipts, business messaging), then shows the live
 * connection / account / provider status, and finally an "Advanced status"
 * entry that opens the provider's read-only transport diagnostic dump.
 *
 * <p>This screen was reorganized: the toggles were added on top, the
 * status-row titles are tinted (with the Connection value colored green when
 * Connected / gray while connecting), and the old "RCS transport status"
 * overflow item became the "Advanced status" entry here.
 *
 * <p>Status is sourced entirely from {@link ProviderTransport} /
 * {@link RouteSelector}. There is no observer on the RCS seam yet, so the
 * fragment polls: on {@code onResume}, on a light periodic tick while visible,
 * and immediately when the OTP / ToS broadcasts fire.
 */
public class RcsSettingsActivity extends BugleActionBarActivity {

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(getString(R.string.rcs_settings_title));

        final FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(android.R.id.content, new RcsSettingsFragment());
        ft.commit();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class RcsSettingsFragment extends PreferenceFragmentCompat {

        // Light periodic refresh cadence while the screen is visible. The RCS
        // seam has no push-to-UI observer yet; this keeps the status fresh
        // during an in-progress registration / provisioning without busy work.
        private static final long REFRESH_INTERVAL_MS = 3_000L;

        private final Handler mHandler = new Handler(Looper.getMainLooper());

        private Preference mConnectionPref;
        private Preference mProvisioningPref;
        private Preference mPhoneNumberPref;
        private Preference mVerifyPref;
        private Preference mProviderPref;
        private Preference mTosPref;
        private androidx.preference.SwitchPreferenceCompat mE2eePref;

        private int mSubId = PhoneUtils.getDefault().getDefaultSmsSubscriptionId();

        private final Runnable mRefreshTick = new Runnable() {
            @Override
            public void run() {
                refresh();
                mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };

        // The provider asks for an OTP (or surfaces a ToS gate) via these
        // package-scoped broadcasts; refresh immediately so the matching state
        // ("Waiting for verification code" / "Action required") shows at once.
        private final BroadcastReceiver mRcsStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                refresh();
            }
        };

        public RcsSettingsFragment() {
            // Required empty constructor.
        }

        @Override
        public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                @Nullable final String rootKey) {
            getPreferenceManager().setSharedPreferencesName(BuglePrefs.SHARED_PREFERENCES_NAME);
            addPreferencesFromResource(R.xml.preferences_rcs);

            mConnectionPref = findPreference("rcs_connection_status");
            mProvisioningPref = findPreference("rcs_provisioning_status");
            mPhoneNumberPref = findPreference("rcs_phone_number");
            mVerifyPref = findPreference("rcs_verify_status");
            mProviderPref = findPreference("rcs_provider");
            mTosPref = findPreference("rcs_carrier_tos");

            // Tint the status/account row TITLES with the dynamic
            // accent so each label reads as a distinct color from its value.
            @ColorInt final int labelColor =
                    ContextCompat.getColor(requireContext(), R.color.rcs_status_label);
            colorTitle(mConnectionPref, labelColor);
            colorTitle(mProvisioningPref, labelColor);
            colorTitle(mPhoneNumberPref, labelColor);
            colorTitle(mVerifyPref, labelColor);
            colorTitle(mProviderPref, labelColor);

            // RBM on/off: persist the switch + signal the provider to
            // re-register with/without the chatbot capability (off the main thread).
            // The rcs_enabled + rcs_typing_indicators switches persist directly to
            // their keys and are read on demand by RcsFeatureSettings — no listener.
            final androidx.preference.SwitchPreferenceCompat rbmPref =
                    findPreference("rcs_business_messaging");
            if (rbmPref != null) {
                rbmPref.setOnPreferenceChangeListener((pref, newValue) -> {
                    final boolean enabled = Boolean.TRUE.equals(newValue);
                    new Thread(() -> {
                        final ProviderTransport t = ProviderTransport.peekInstance();
                        if (t != null) {
                            t.setChatbotEnabled(enabled);
                        }
                    }, "rbm-toggle").start();
                    return true;  // persist the new value
                });
            }

            // Generic end-to-end-encryption toggle. The provider owns the state
            // (getE2eeInfo / setE2eeEnabled); this row is non-persistent and driven from
            // the provider in refresh(). Scheme-blind — the summary shows whatever scheme
            // label the provider reports (Etouffee today, UP 3.0 / MSRP later).
            mE2eePref = findPreference("rcs_e2ee");
            if (mE2eePref != null) {
                mE2eePref.setOnPreferenceChangeListener((pref, newValue) -> {
                    final boolean enabled = Boolean.TRUE.equals(newValue);
                    final int subId = mSubId;
                    new Thread(() -> {
                        final ProviderTransport t = ProviderTransport.peekInstance();
                        if (t != null) {
                            t.setE2eeEnabled(subId, enabled);
                        }
                    }, "e2ee-toggle").start();
                    return true;  // optimistic; refresh() reconciles from the provider
                });
                // Warm the cached state off-main so the toggle reflects the provider.
                new Thread(() -> {
                    final ProviderTransport t = ProviderTransport.peekInstance();
                    if (t != null) {
                        t.getE2eeInfo(mSubId);  // updates the cache
                        mHandler.post(this::refresh);
                    }
                }, "e2ee-load").start();
            }

            // The master "RCS chats" toggle grays out everything else on
            // this screen when off (routing already falls back to SMS/MMS via
            // RouteSelector.isRcsAvailableForSub reading the same pref).
            final androidx.preference.SwitchPreferenceCompat masterPref =
                    findPreference("rcs_enabled");
            if (masterPref != null) {
                applyMasterEnabledState(masterPref.isChecked());
                masterPref.setOnPreferenceChangeListener((pref, newValue) -> {
                    applyMasterEnabledState(Boolean.TRUE.equals(newValue));
                    return true;  // persist the new value
                });
            }
        }

        /**
         * Enable/disable (gray out) every row except the master toggle, following
         * the "RCS chats" master switch. Recurses into the preference categories.
         */
        private void applyMasterEnabledState(final boolean enabled) {
            final androidx.preference.PreferenceScreen screen = getPreferenceScreen();
            if (screen == null) {
                return;
            }
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                final Preference p = screen.getPreference(i);
                if ("rcs_enabled".equals(p.getKey())) {
                    continue;  // the master toggle itself stays enabled
                }
                setEnabledRecursive(p, enabled);
            }
        }

        private void setEnabledRecursive(final Preference pref, final boolean enabled) {
            pref.setEnabled(enabled);
            if (pref instanceof androidx.preference.PreferenceGroup) {
                final androidx.preference.PreferenceGroup group =
                        (androidx.preference.PreferenceGroup) pref;
                for (int i = 0; i < group.getPreferenceCount(); i++) {
                    setEnabledRecursive(group.getPreference(i), enabled);
                }
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            final IntentFilter filter = new IntentFilter(RcsConstants.ACTION_OTP_REQUIRED);
            filter.addAction(RcsConstants.ACTION_CARRIER_TOS_REQUIRED);
            filter.addAction(RcsConstants.ACTION_E2EE_STATE_CHANGED);
            // Package-scoped internal broadcasts; not exported.
            requireContext().registerReceiver(mRcsStateReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
            mHandler.removeCallbacks(mRefreshTick);
            mHandler.post(mRefreshTick);
        }

        @Override
        public void onPause() {
            super.onPause();
            mHandler.removeCallbacks(mRefreshTick);
            try {
                requireContext().unregisterReceiver(mRcsStateReceiver);
            } catch (final IllegalArgumentException ignored) {
                // Not registered.
            }
        }

        /** Pull the latest RCS state and repaint every row. */
        private void refresh() {
            if (mConnectionPref == null) {
                return;
            }
            final ProviderTransport transport = ProviderTransport.peekInstance();
            final RouteSelector selector =
                    (transport != null) ? transport.getRouteSelector() : null;

            final boolean attached = transport != null && transport.isAttached();
            final int regState = (selector != null)
                    ? selector.getRegState(mSubId) : IRcsProviderCallback.REG_UNREGISTERED;
            final int provState = (selector != null)
                    ? selector.getProvState(mSubId) : IRcsProviderCallback.PROV_NOT_PROVISIONED;
            final RcsProviderCaps caps =
                    (selector != null) ? selector.getCapsForSub(mSubId) : null;

            final int tosState = (selector != null)
                    ? selector.getTosState(mSubId) : IRcsProviderCallback.TOS_NONE;

            mConnectionPref.setSummary(connectionSummary(attached, regState));
            mProvisioningPref.setSummary(provisioningSummary(provState));
            mVerifyPref.setSummary(verifySummary(provState));
            mPhoneNumberPref.setSummary(phoneNumberSummary());
            mProviderPref.setSummary(providerSummary(caps));
            renderTos(tosState);
            renderE2ee(transport);
        }

        /** Render the generic E2EE toggle from the provider's cached state: hide the row
         *  when E2EE isn't available, else reflect enabled + show the scheme label. */
        private void renderE2ee(@Nullable final ProviderTransport transport) {
            if (mE2eePref == null) {
                return;
            }
            final org.lineageos.rcs.provider.RcsE2eeInfo info =
                    (transport != null) ? transport.peekE2eeInfo() : null;
            if (info == null || !info.available) {
                mE2eePref.setVisible(false);
                return;
            }
            mE2eePref.setVisible(true);
            if (mE2eePref.isChecked() != info.enabled) {
                mE2eePref.setChecked(info.enabled);
            }
            // Append the scheme label so the user sees WHICH E2EE is in use, scheme-blind.
            final String base = getString(R.string.rcs_e2ee_summary);
            mE2eePref.setSummary((info.schemeLabel != null && !info.schemeLabel.isEmpty())
                    ? base + " (" + info.schemeLabel + ")" : base);
        }

        /**
         * Paint the carrier-ToS row + drive its clickability. Clickable only
         * when there is an actual decision to (re)make or withdraw; otherwise a
         * read-only informational summary.
         */
        private void renderTos(final int tosState) {
            if (mTosPref == null) {
                return;
            }
            final boolean clickable;
            final int summaryRes;
            switch (tosState) {
                case IRcsProviderCallback.TOS_REQUIRED:
                    summaryRes = R.string.rcs_tos_state_pending;
                    clickable = true;
                    break;
                case IRcsProviderCallback.TOS_ACCEPTED:
                    summaryRes = R.string.rcs_tos_state_accepted;
                    clickable = true;  // tap -> withdraw confirm
                    break;
                case IRcsProviderCallback.TOS_DECLINED:
                    summaryRes = R.string.rcs_tos_state_declined;
                    clickable = false;
                    break;
                case IRcsProviderCallback.TOS_NONE:
                default:
                    summaryRes = R.string.rcs_tos_state_none;
                    clickable = false;
                    break;
            }
            mTosPref.setSummary(getString(summaryRes));
            mTosPref.setSelectable(clickable);
        }

        @Override
        public boolean onPreferenceTreeClick(@NonNull final Preference preference) {
            final String key = preference.getKey();
            if ("rcs_advanced_status".equals(key)) {
                launchAdvancedStatus();
                return true;
            }
            if (!"rcs_carrier_tos".equals(key)) {
                return super.onPreferenceTreeClick(preference);
            }
            final ProviderTransport transport = ProviderTransport.peekInstance();
            final RouteSelector selector =
                    (transport != null) ? transport.getRouteSelector() : null;
            final int tosState = (selector != null)
                    ? selector.getTosState(mSubId) : IRcsProviderCallback.TOS_NONE;

            if (tosState == IRcsProviderCallback.TOS_REQUIRED) {
                relaunchTosDialog(selector);
                return true;
            }
            if (tosState == IRcsProviderCallback.TOS_ACCEPTED) {
                confirmWithdraw();
                return true;
            }
            return true;
        }

        /**
         * Open the RCS provider app's transport-status diagnostic page, if it has one.
         *
         * <p>Sent as an explicit intent to whichever package resolved the bind action, so
         * no package name is compiled in here. If no provider is installed, or it exposes
         * no such screen, or the diagnostic is gated off on a user build, this toasts.
         */
        private void launchAdvancedStatus() {
            final String pkg = com.android.messaging.rcs.ProviderRegistry
                    .resolveProviderPackage(requireContext());
            if (pkg == null) {
                Toast.makeText(requireContext(), R.string.rcs_provider_not_installed,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // Explicit: an implicit bind/launch into another app is illegal on API 30+.
            final Intent i = new Intent("org.lineageos.rcs.provider.action.TRANSPORT_STATUS")
                    .setPackage(pkg);
            try {
                startActivity(i);
            } catch (final ActivityNotFoundException e) {
                LogUtil.w(LogUtil.BUGLE_TAG,
                        "RCS advanced status: provider activity not found", e);
                Toast.makeText(requireContext(), R.string.rcs_provider_not_installed,
                        Toast.LENGTH_SHORT).show();
            }
        }

        /** Re-show the consent dialog from the cached prompt (pending state). */
        private void relaunchTosDialog(@Nullable final RouteSelector selector) {
            final RcsTosPrompt prompt =
                    (selector != null) ? selector.getTosPrompt(mSubId) : null;
            final Intent i = new Intent(requireContext(), RcsCarrierTosActivity.class)
                    .putExtra(RcsConstants.EXTRA_TOS_SUB_ID, mSubId)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (prompt != null) {
                i.putExtra(RcsConstants.EXTRA_TOS_TITLE, prompt.title)
                 .putExtra(RcsConstants.EXTRA_TOS_BODY, prompt.message)
                 .putExtra(RcsConstants.EXTRA_TOS_ACCEPT_LABEL, prompt.acceptLabel)
                 .putExtra(RcsConstants.EXTRA_TOS_REJECT_LABEL, prompt.rejectLabel)
                 .putExtra(RcsConstants.EXTRA_TOS_KIND, prompt.kind);
            }
            startActivity(i);
        }

        /** Confirm + perform a withdrawal of a previously-accepted ToS. */
        private void confirmWithdraw() {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.rcs_tos_withdraw_title)
                    .setMessage(R.string.rcs_tos_withdraw_body)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.rcs_tos_reject,
                            (dialog, which) -> {
                                final ProviderTransport t =
                                        ProviderTransport.peekInstance();
                                if (t != null) {
                                    t.submitTosConsent(mSubId, false);
                                }
                                refresh();
                            })
                    .show();
        }

        /**
         * Connection value summary. "Connected" is tinted green and
         * the transient "Connecting…" gray, so the live state reads at a glance;
         * other states keep the default text color.
         */
        private CharSequence connectionSummary(final boolean attached, final int regState) {
            if (!attached) {
                return getString(R.string.rcs_status_connection_off);
            }
            switch (regState) {
                case IRcsProviderCallback.REG_REGISTERED:
                    return colorize(getString(R.string.rcs_status_connection_connected),
                            R.color.rcs_status_value_connected);
                case IRcsProviderCallback.REG_REGISTERING:
                    return colorize(getString(R.string.rcs_status_connection_connecting),
                            R.color.rcs_status_value_neutral);
                case IRcsProviderCallback.REG_FAILED:
                    return getString(R.string.rcs_status_connection_failed);
                case IRcsProviderCallback.REG_UNREGISTERED:
                default:
                    return getString(R.string.rcs_status_connection_off);
            }
        }

        private String provisioningSummary(final int provState) {
            switch (provState) {
                case IRcsProviderCallback.PROV_IN_PROGRESS:
                    return getString(R.string.rcs_status_prov_in_progress);
                case IRcsProviderCallback.PROV_WAITING_FOR_OTP:
                    return getString(R.string.rcs_status_prov_waiting_otp);
                case IRcsProviderCallback.PROV_CONFIGURED:
                    return getString(R.string.rcs_status_prov_configured);
                case IRcsProviderCallback.PROV_DISABLED_BY_CARRIER:
                    return getString(R.string.rcs_status_prov_disabled_carrier);
                case IRcsProviderCallback.PROV_NEEDS_REPROVISION:
                    return getString(R.string.rcs_status_prov_needs_reprovision);
                case IRcsProviderCallback.PROV_NOT_PROVISIONED:
                default:
                    return getString(R.string.rcs_status_prov_none);
            }
        }

        private String verifySummary(final int provState) {
            switch (provState) {
                case IRcsProviderCallback.PROV_CONFIGURED:
                    return getString(R.string.rcs_status_verify_done);
                case IRcsProviderCallback.PROV_WAITING_FOR_OTP:
                    return getString(R.string.rcs_status_verify_waiting);
                default:
                    return getString(R.string.rcs_status_verify_not_done);
            }
        }

        private String phoneNumberSummary() {
            String number = null;
            try {
                number = PhoneUtils.get(mSubId).getCanonicalForSelf(true /* allowOverride */);
            } catch (final Exception e) {
                // Fall through to "Unknown".
            }
            if (TextUtils.isEmpty(number)) {
                return getString(R.string.rcs_status_phone_number_unknown);
            }
            // Show the country-specific display format
            // (+15715550104 -> (571) 555-0104) instead of raw E.164.
            try {
                final String formatted = PhoneUtils.getDefault().formatForDisplay(number);
                if (!TextUtils.isEmpty(formatted)) {
                    return formatted;
                }
            } catch (final Exception e) {
                // Fall through to the E.164 value.
            }
            return number;
        }

        private String providerSummary(@Nullable final RcsProviderCaps caps) {
            if (caps == null || TextUtils.isEmpty(caps.providerLabel)) {
                return getString(R.string.rcs_status_provider_none);
            }
            return caps.providerLabel;
        }

        /** Replace a preference's title with a single-color spannable copy. */
        private void colorTitle(@Nullable final Preference pref, @ColorInt final int color) {
            if (pref == null || pref.getTitle() == null) {
                return;
            }
            pref.setTitle(span(pref.getTitle(), color));
        }

        /** Color a value string using a color RESOURCE (day/night aware). */
        private CharSequence colorize(final CharSequence text, final int colorRes) {
            return span(text, ContextCompat.getColor(requireContext(), colorRes));
        }

        private static CharSequence span(final CharSequence text, @ColorInt final int color) {
            final SpannableString s = new SpannableString(text);
            s.setSpan(new ForegroundColorSpan(color), 0, s.length(),
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            return s;
        }
    }
}
