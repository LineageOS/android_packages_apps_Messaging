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
package com.android.messaging.rcs.carrier;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * JSON parser for the {@code Configuration.mImsConfiguration} blob. Lifted
 * out of {@link RcsImsConfig} so the POJO itself stays free of any JSON
 * dependency — that way the POJO compiles cleanly inside the
 * {@code messaging-rcs-carrier-bridge-host} library, which doesn't have org.json
 * on its host classpath. Production code on device continues to use
 * {@link #fromJson} when reading Pev3's persisted config.
 *
 * <p>Field set taken from a real T-Mobile US provisioning and from Google
 * Messages' own Configuration parser.
 */
public final class RcsImsConfigParser {

    private RcsImsConfigParser() {}

    /**
     * Parse the Gson-serialized {@code mImsConfiguration} sub-object out of
     * a {@code Configuration} JSON. Tolerant of missing keys (defaults applied
     * per the upstream POJO defaults).
     *
     * <p>Accepts either the inner {@code mImsConfiguration} object itself
     * OR the full {@code Configuration} object (auto-descends to
     * {@code mImsConfiguration} if present).
     */
    public static RcsImsConfig fromJson(String json) throws JSONException {
        return fromJson(new JSONObject(json));
    }

    public static RcsImsConfig fromJson(JSONObject root) throws JSONException {
        JSONObject ims = root.has("mImsConfiguration")
                ? root.getJSONObject("mImsConfiguration") : root;
        RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        b.pcscfAddress = ims.optString("mPcscfAddress", null);
        b.pcscfPort = ims.optInt("mPcsfPort", -1);  // NB: the upstream typo "mPcsfPort"
        b.domain = ims.optString("mDomain", null);
        b.privateIdentity = ims.optString("mPrivateIdentity", null);
        b.publicIdentity = ims.optString("mPublicIdentity", null);
        b.userName = ims.optString("mUserName", null);
        b.authDigestUsername = ims.optString("mAuthDigestUsername", null);
        b.authDigestPassword = ims.optString("mAuthDigestPassword", null);
        b.authDigestRealm = ims.optString("mAuthDigestRealm", null);
        b.authenticationScheme = ims.optString("mAuthenticationScheme", "Digest");
        b.psSipTransport = ims.optString("mPsSipTransport", "SIPoTLS");
        b.wifiSipTransport = ims.optString("mWifiSipTransport", "SIPoTLS");
        b.psMediaTransport = ims.optString("mPsMediaTransport", "MSRPoTLS");
        b.wifiMediaTransport = ims.optString("mWifiMediaTransport", "MSRPoTLS");
        b.phoneContext = ims.optString("mPhoneContext", null);
        b.localSipPort = ims.optInt("mLocalSipPort", 0);
        b.keepAlive = ims.optBoolean("mKeepAlive", true);
        b.t1Ms = ims.optInt("mT1", 500);
        b.t2Ms = ims.optInt("mT2", 4000);
        b.t4Ms = ims.optInt("mT4", 5000);
        b.regRetryBaseSec = ims.optInt("mRegRetryBaseTime", 30);
        b.regRetryMaxSec = ims.optInt("mRegRetryMaxTime", 1800);
        b.q = (float) ims.optDouble("mQ", 0.5);
        return b.build();
    }
}
