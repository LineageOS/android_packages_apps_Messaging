/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.ui.rcs;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;

import org.lineageos.rcs.provider.RcsBotBrand;

import com.android.messaging.R;
import com.android.messaging.datamodel.media.UriImageRequestDescriptor;
import com.android.messaging.ui.AsyncImageView;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.util.LogUtil;

/**
 * The business-info screen shown when the user taps a verified brand header: renders an
 * {@link RcsBotBrand} (hero, logo, name, category, verifier), quick actions, an About section and
 * detail rows that open the matching intent.
 */
public class RbmBusinessInfoActivity extends BugleActionBarActivity {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String EXTRA_BRAND = "rbm_brand";

    public static Intent intent(final Context context, final RcsBotBrand brand) {
        return new Intent(context, RbmBusinessInfoActivity.class)
                .putExtra(EXTRA_BRAND, brand);
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final RcsBotBrand brand = getIntent().getParcelableExtra(EXTRA_BRAND);
        if (brand == null) {
            finish();
            return;
        }
        setContentView(R.layout.rbm_business_info_activity);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.rbm_business_info_title);
        }
        bind(brand);
    }

    private void bind(final RcsBotBrand brand) {
        final int logoSize = getResources().getDimensionPixelSize(R.dimen.rbm_info_logo_size);
        final int heroH = getResources().getDimensionPixelSize(R.dimen.rbm_info_hero_height);

        // With a hero image the logo half-overlaps its bottom edge; without one there is no band.
        final AsyncImageView hero = findViewById(R.id.rbm_info_hero);
        final AsyncImageView logo = findViewById(R.id.rbm_info_logo);
        final LinearLayout.LayoutParams logoLp =
                (LinearLayout.LayoutParams) logo.getLayoutParams();
        if (!TextUtils.isEmpty(brand.heroUrl)) {
            hero.setImageResourceId(new UriImageRequestDescriptor(
                    Uri.parse(brand.heroUrl),
                    getResources().getDisplayMetrics().widthPixels, heroH));
            hero.setVisibility(View.VISIBLE);
            logoLp.topMargin =
                    -getResources().getDimensionPixelSize(R.dimen.rbm_info_logo_overlap);
        } else {
            hero.setImageResourceId(null);
            hero.setVisibility(View.GONE);
            logoLp.topMargin = getResources().getDimensionPixelSize(R.dimen.rbm_info_section_gap);
        }
        logo.setLayoutParams(logoLp);

        if (!TextUtils.isEmpty(brand.logoUrl)) {
            logo.setImageResourceId(new UriImageRequestDescriptor(Uri.parse(brand.logoUrl),
                    logoSize, logoSize, true /* cropToCircle */, 0, 0));
            logo.setVisibility(View.VISIBLE);
        }

        final TextView name = findViewById(R.id.rbm_info_name);
        name.setText(!TextUtils.isEmpty(brand.name) ? brand.name : brand.botId);

        setText(R.id.rbm_info_category,
                TextUtils.isEmpty(brand.category) ? null : brand.category.toUpperCase());

        if (brand.verified) {
            findViewById(R.id.rbm_info_verified).setVisibility(View.VISIBLE);
            final ImageView badge = findViewById(R.id.rbm_info_verified_badge);
            if (!TextUtils.isEmpty(brand.verifierLogoUrl)) {
                ((AsyncImageView) badge).setImageResourceId(new UriImageRequestDescriptor(
                        Uri.parse(brand.verifierLogoUrl),
                        getResources().getDimensionPixelSize(R.dimen.rbm_verified_badge_size),
                        getResources().getDimensionPixelSize(R.dimen.rbm_verified_badge_size)));
            }
            final String verifier = !TextUtils.isEmpty(brand.verifierName)
                    ? brand.verifierName : getString(R.string.rbm_bot_info_verifier_generic);
            ((TextView) findViewById(R.id.rbm_info_verified_text))
                    .setText(getString(R.string.rbm_bot_info_verified, verifier));
        }

        // Quick actions.
        setupAction(R.id.rbm_info_action_call, brand.phoneNumber,
                Intent.ACTION_DIAL, "tel:");
        setupAction(R.id.rbm_info_action_website, brand.website, Intent.ACTION_VIEW, null);
        setupAction(R.id.rbm_info_action_email, brand.email, Intent.ACTION_SENDTO, "mailto:");

        // About.
        if (TextUtils.isEmpty(brand.description)) {
            findViewById(R.id.rbm_info_about_header).setVisibility(View.GONE);
            findViewById(R.id.rbm_info_description).setVisibility(View.GONE);
        } else {
            ((TextView) findViewById(R.id.rbm_info_description)).setText(brand.description);
        }

        // Details rows.
        final LinearLayout details = findViewById(R.id.rbm_info_details);
        addDetailRow(details, R.drawable.ic_rbm_call, brand.phoneNumber,
                labelOr(brand.phoneLabel, R.string.rbm_info_phone),
                Intent.ACTION_DIAL, "tel:");
        addDetailRow(details, R.drawable.ic_rbm_web, brand.website,
                labelOr(brand.websiteLabel, R.string.rbm_info_website),
                Intent.ACTION_VIEW, null);
        addDetailRow(details, R.drawable.ic_rbm_email, brand.email,
                labelOr(brand.emailLabel, R.string.rbm_info_email),
                Intent.ACTION_SENDTO, "mailto:");
        addDetailRow(details, R.drawable.ic_rbm_terms, brand.termsUrl,
                getString(R.string.rbm_info_terms), Intent.ACTION_VIEW, null);
        addDetailRow(details, R.drawable.ic_rbm_terms, brand.privacyUrl,
                getString(R.string.rbm_info_privacy), Intent.ACTION_VIEW, null);
        if (details.getChildCount() == 0) {
            findViewById(R.id.rbm_info_details_header).setVisibility(View.GONE);
        }
    }

    private void setupAction(final int viewId, final String value, final String action,
            final String scheme) {
        final View v = findViewById(viewId);
        if (TextUtils.isEmpty(value)) {
            v.setVisibility(View.GONE);
            return;
        }
        v.setVisibility(View.VISIBLE);
        v.setOnClickListener(x -> fire(action, scheme, value));
    }

    private void addDetailRow(final LinearLayout parent, final int icon, final String value,
            final String label, final String action, final String scheme) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        final View row = LayoutInflater.from(this)
                .inflate(R.layout.rbm_info_detail_row, parent, false);
        ((ImageView) row.findViewById(R.id.rbm_info_row_icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.rbm_info_row_value)).setText(value);
        final TextView lbl = row.findViewById(R.id.rbm_info_row_label);
        if (!TextUtils.isEmpty(label)) {
            lbl.setText(label);
            lbl.setVisibility(View.VISIBLE);
        }
        row.setOnClickListener(x -> fire(action, scheme, value));
        parent.addView(row);
    }

    /**
     * Fire an intent for a contact or URL, prefixing {@code scheme} when {@code value} has none.
     */
    private void fire(final String action, final String scheme, final String value) {
        String data = value;
        if (scheme != null && !value.regionMatches(true, 0, scheme, 0, scheme.length())) {
            data = scheme + value;
        }
        try {
            startActivity(new Intent(action, Uri.parse(data))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (final Throwable t) {
            LogUtil.w(TAG, "RbmBusinessInfoActivity: no handler for " + data, t);
        }
    }

    private void setText(final int viewId, final String text) {
        final TextView tv = findViewById(viewId);
        if (TextUtils.isEmpty(text)) {
            tv.setVisibility(View.GONE);
        } else {
            tv.setText(text);
            tv.setVisibility(View.VISIBLE);
        }
    }

    private String labelOr(final String label, final int fallbackRes) {
        return !TextUtils.isEmpty(label) ? label : getString(fallbackRes);
    }
}
