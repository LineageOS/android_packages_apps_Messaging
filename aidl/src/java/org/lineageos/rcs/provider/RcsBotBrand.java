/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * Brand of a business-messaging agent, returned by {@link IRcsProvider#getBotBrand} for the
 * conversation header and the business-info screen. The image URLs are public, so the app fetches
 * them directly.
 */
public final class RcsBotBrand implements Parcelable {

    public final String botId;
    @Nullable public final String name;
    @Nullable public final String description;
    /** Brand colour {@code #rrggbb}; null if absent. */
    @Nullable public final String color;
    /** Public logo image URL; null if absent. */
    @Nullable public final String logoUrl;
    /** Public hero/banner image URL; null if absent. */
    @Nullable public final String heroUrl;
    /** True when the agent is a verified business. */
    public final boolean verified;
    /** Name of the verifier; null if absent. */
    @Nullable public final String verifierName;
    /** Public verifier logo URL; null if absent. */
    @Nullable public final String verifierLogoUrl;
    /** Agent use-case category, as the provider reports it; null if absent. */
    @Nullable public final String category;
    /** Contact phone (tel: stripped) + its display label; null if absent. */
    @Nullable public final String phoneNumber;
    @Nullable public final String phoneLabel;
    /** Contact email (mailto: stripped) + its display label; null if absent. */
    @Nullable public final String email;
    @Nullable public final String emailLabel;
    /** Website url + its display label; null if absent. */
    @Nullable public final String website;
    @Nullable public final String websiteLabel;
    /** Terms &amp; Conditions url; null if absent. */
    @Nullable public final String termsUrl;
    /** Privacy-policy url; null if absent. */
    @Nullable public final String privacyUrl;

    public RcsBotBrand(String botId, String name, String description, String color,
                       String logoUrl, String heroUrl, boolean verified,
                       String verifierName, String verifierLogoUrl,
                       String category, String phoneNumber, String phoneLabel,
                       String email, String emailLabel, String website,
                       String websiteLabel, String termsUrl, String privacyUrl) {
        this.botId = botId;
        this.name = name;
        this.description = description;
        this.color = color;
        this.logoUrl = logoUrl;
        this.heroUrl = heroUrl;
        this.verified = verified;
        this.verifierName = verifierName;
        this.verifierLogoUrl = verifierLogoUrl;
        this.category = category;
        this.phoneNumber = phoneNumber;
        this.phoneLabel = phoneLabel;
        this.email = email;
        this.emailLabel = emailLabel;
        this.website = website;
        this.websiteLabel = websiteLabel;
        this.termsUrl = termsUrl;
        this.privacyUrl = privacyUrl;
    }

    protected RcsBotBrand(Parcel in) {
        this.botId = in.readString();
        this.name = in.readString();
        this.description = in.readString();
        this.color = in.readString();
        this.logoUrl = in.readString();
        this.heroUrl = in.readString();
        this.verified = in.readInt() != 0;
        this.verifierName = in.readString();
        this.verifierLogoUrl = in.readString();
        this.category = in.readString();
        this.phoneNumber = in.readString();
        this.phoneLabel = in.readString();
        this.email = in.readString();
        this.emailLabel = in.readString();
        this.website = in.readString();
        this.websiteLabel = in.readString();
        this.termsUrl = in.readString();
        this.privacyUrl = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(botId);
        dest.writeString(name);
        dest.writeString(description);
        dest.writeString(color);
        dest.writeString(logoUrl);
        dest.writeString(heroUrl);
        dest.writeInt(verified ? 1 : 0);
        dest.writeString(verifierName);
        dest.writeString(verifierLogoUrl);
        dest.writeString(category);
        dest.writeString(phoneNumber);
        dest.writeString(phoneLabel);
        dest.writeString(email);
        dest.writeString(emailLabel);
        dest.writeString(website);
        dest.writeString(websiteLabel);
        dest.writeString(termsUrl);
        dest.writeString(privacyUrl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<RcsBotBrand> CREATOR = new Creator<RcsBotBrand>() {
        @Override
        public RcsBotBrand createFromParcel(Parcel in) {
            return new RcsBotBrand(in);
        }

        @Override
        public RcsBotBrand[] newArray(int size) {
            return new RcsBotBrand[size];
        }
    };
}
