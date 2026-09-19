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

import android.content.Context;
import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.CarrierRcsTransport;
import com.android.messaging.rcs.carrier.RcsImsConfig;

import com.android.messaging.util.LogUtil;

/**
 * The REAL Dual-Registration (DR) driver (design §6.3, D6): drives the
 * OpenRCSChat app-owned carrier SIP/MSRP stack ({@link CarrierRcsTransport} +
 * {@code CarrierSipRegistrar} + JAIN-SIP + its own MSRP) which does its OWN SIP
 * {@code REGISTER} over the IMS APN, independent of the modem's single
 * registration. Selected by {@link CarrierImsService} when the per-sub
 * {@link CarrierImsMode#probe} reports {@link CarrierImsMode#DR} (the modem does
 * not expose the {@code SipDelegate} single-registration API).
 *
 * <p>This is the "one transport, two internal modes" wiring the design assumes:
 * the DR stack is now on this app's classpath (it pulls in {@code nist-sip}) and this class
 * adapts its {@link Transport} surface to the {@link CarrierDrModeDriver}
 * interface {@link CarrierImsService} already forwards through the {@code :ims}
 * seam.
 *
 * <hr>
 * <b>The one real rig gap — SIP credentials.</b> {@link CarrierRcsTransport}
 * needs an {@link RcsImsConfig} (P-CSCF address, private/public identity, IMS-AKA
 * or Digest credentials). On the DR path those come from the carrier's RCC.14 /
 * TS.43 <b>autoconfig</b> fetched over the IMS APN — a real ACS round-trip that
 * only completes against a live carrier IMS. We build the config from the modem
 * autoconfig when it is available and usable; when it is not (no ACS response
 * yet, or a shape we can't map), we surface a terminal failure so the sub cleanly
 * falls to SMS rather than wedging.
 *
 * RIG-VERIFY(rcs-framework): wire the RCC.14 autoconfig XML -> {@link RcsImsConfig}
 * mapping against a live DR carrier's ACS. {@link com.android.messaging.rcs.carrier.RcsImsConfigParser}
 * parses Google Messages' <i>JSON</i> {@code mImsConfiguration} blob; the RCC.14 <i>XML</i>
 * autoconfig from a bare-DR carrier is a different encoding. Until that mapping +
 * a captured ACS response exist, {@link #configFor} returns null and DR reports
 * REG_FAILED (SMS fallback). Everything else (REGISTER, pager MESSAGE, MSRP
 * session send, inbound receive, state mapping) is fully wired below and exercised
 * the moment a usable config is supplied.
 */
public final class CarrierStackDrModeDriver implements CarrierDrModeDriver {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierDrDriver";

    private final Context mContext;

    @Nullable private volatile CarrierRcsTransport mTransport;
    @Nullable private volatile Listener mListener;
    private volatile int mSubId = -1;
    /** The most recent RCC.07 autoconfig doc from the ACS (via the modem's
     *  onConfigurationChanged, forwarded by CarrierImsService.onRcsConfig). Parsed
     *  by {@link AcsImsConfigParser} in {@link #configFor} to build the real
     *  RcsImsConfig. Null until the first ACS config arrives. */
    @Nullable private volatile byte[] mAcsConfigXml;

    public CarrierStackDrModeDriver(final Context context) {
        this.mContext = context.getApplicationContext();
    }

    /** Feed the ACS RCC.07 config doc (the real IMS-Settings source). Called by
     *  CarrierImsService when the modem's autoconfig callback delivers it. */
    @Override
    public void setAcsConfig(@Nullable final byte[] configXml) {
        this.mAcsConfigXml = configXml;
        LogUtil.i(TAG, SUBTAG + ": ACS RCC.07 config received ("
                + (configXml == null ? 0 : configXml.length) + " bytes)");
    }

    @Override
    public void startForSub(final int subId, @Nullable final String msisdn,
            @Nullable final String mccMnc, final Listener listener) {
        mSubId = subId;
        mListener = listener;
        final RcsImsConfig config = configFor(subId, msisdn, mccMnc);
        if (config == null || !CarrierRcsTransport.isConfigUsable(config)) {
            LogUtil.w(TAG, SUBTAG + ": no usable RcsImsConfig for sub=" + subId
                    + " (RCC.14 autoconfig -> RcsImsConfig is RIG-VERIFY) -> REG_FAILED");
            if (listener != null) {
                listener.onProvisioningState(subId,
                        IRcsProviderCallback.PROV_NOT_PROVISIONED);
                listener.onRegistrationState(subId, IRcsProviderCallback.REG_FAILED,
                        "DR autoconfig not available (no usable RcsImsConfig)");
            }
            return;
        }
        LogUtil.i(TAG, SUBTAG + ": config usable, building/starting DR transport");
        CarrierRcsTransport transport = mTransport;
        if (transport == null) {
            transport = new CarrierRcsTransport(mContext, config);
            transport.setListener(new TransportListener(subId));
            mTransport = transport;
        }
        if (listener != null) {
            listener.onProvisioningState(subId, IRcsProviderCallback.PROV_IN_PROGRESS);
        }
        transport.start(); // idempotent
    }

    @Override
    public void stopForSub(final int subId) {
        final CarrierRcsTransport transport = mTransport;
        if (transport != null) {
            transport.stop();
        }
        mTransport = null;
    }

    @Override
    public boolean sendText(final int subId, final String fromE164, final String toE164,
            final String messageId, final String body) {
        final CarrierRcsTransport transport = mTransport;
        if (transport == null
                || transport.getRegistrationState() != Transport.RegistrationState.REGISTERED) {
            LogUtil.w(TAG, SUBTAG + ": sendText not REGISTERED -> reject (SMS fallback)");
            return false;
        }
        try {
            // CarrierRcsTransport routes pager-MESSAGE vs MSRP-session by the
            // switchover-size rule internally; SENDING/SENT/FAILED come back on
            // the Transport.Listener -> onMessageStatus below.
            transport.sendMessage("tel:" + toE164, body, messageId);
            return true;
        } catch (final Throwable t) {
            LogUtil.e(TAG, SUBTAG + ": DR sendText failed", t);
            return false;
        }
    }

    @Override
    public boolean isRegistered(final int subId) {
        final CarrierRcsTransport transport = mTransport;
        return transport != null
                && transport.getRegistrationState() == Transport.RegistrationState.REGISTERED;
    }

    @Override
    public void sendTyping(final int subId, final String fromE164, final String toE164,
            final boolean active) {
        final CarrierRcsTransport transport = mTransport;
        if (transport == null) {
            return; // typing is best-effort
        }
        try {
            transport.sendTyping("tel:" + toE164, active);
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": DR sendTyping failed", t);
        }
    }

    @Override
    public void sendImdn(final int subId, final String fromE164, final String toE164,
            final String messageId, final int imdnType) {
        final CarrierRcsTransport transport = mTransport;
        if (transport == null) {
            return;
        }
        try {
            // Delivery IMDNs are auto-emitted by the receiver; the explicit path here
            // carries the display/read report the seam requests.
            transport.sendImdnDisplay("tel:" + toE164, messageId);
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": DR sendImdn failed", t);
        }
    }

    @Override
    public void debugFtUpload(final int subId, final String path, final String toE164) {
        final CarrierRcsTransport transport = mTransport;
        if (transport == null) {
            LogUtil.w(TAG, SUBTAG + ": debugFtUpload no transport (not registered?)");
            return;
        }
        try {
            final String toUri = (toE164 != null && !toE164.isEmpty())
                    ? "tel:" + toE164 : null;
            transport.uploadFileDebug(path, toUri);
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": debugFtUpload failed", t);
        }
    }

    @Override
    public void sendPlainDebug(final int subId, final String fromE164,
            final String toE164, final String text, final String messageId) {
        final CarrierRcsTransport transport = mTransport;
        if (transport == null) {
            LogUtil.w(TAG, SUBTAG + ": sendPlainDebug no transport (not registered?)");
            return;
        }
        try {
            final String toUri = "tel:" + toE164;
            transport.sendPlainDebug(toUri, text, messageId);
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": sendPlainDebug failed", t);
        }
    }

    /** Send an MLS-E2EE message to {@code toE164} over the held CPM/MSRP session. */
    public void sendMls(final int subId, final String fromE164,
            final String toE164, final byte[] framedBody, final String messageId) {
        final CarrierRcsTransport transport = mTransport;
        if (transport == null) {
            LogUtil.w(TAG, SUBTAG + ": sendMls no transport (not registered?)");
            return;
        }
        try {
            transport.sendMls(subId, "tel:" + toE164, toE164, framedBody, messageId);
        } catch (final Throwable t) {
            LogUtil.w(TAG, SUBTAG + ": sendMls failed", t);
        }
    }

    /**
     * Build the DR SIP config for this sub from the carrier autoconfig. See the
     * class RIG-VERIFY: the RCC.14-XML -> {@link RcsImsConfig} mapping requires a
     * live ACS response, so this returns null today (DR falls to SMS). The method
     * is the single, clearly-marked seam to fill once that capture exists — the
     * rest of the driver is complete and will function unchanged.
     */
    @Nullable
    private RcsImsConfig configFor(final int subId, @Nullable final String msisdn,
            @Nullable final String mccMnc) {
        // Default SIPoTCP, not UDP: this IMS core delivers terminating traffic to
        // the UE over its REGISTERED flow, and the P-CSCF upgrades to TCP for any
        // terminating body > its udp_mtu (~1300B — e.g. a CPM/MSRP INVITE's SDP).
        // A UDP-registered UE has no TCP listener for that leg and structurally
        // cannot receive large terminating messages (open5gs terminating-path
        // rearchitecture, validated 2026-07-20 — a CPM session A->B delivered to B
        // only once B registered over TCP). So TCP is the architectural default;
        // debug.rcs.dr.transport overrides it (SIPoUDP/SIPoTCP/SIPoTLS) for tests.
        final String transport = orDef(
                debuggable() ? SystemProperties.get("debug.rcs.dr.transport", "") : "",
                "SIPoTCP");

        // 1) PRODUCTION path: the real RCC.14/RCC.07 autoconfig doc from the ACS
        //    (fetched by the modem, forwarded via setAcsConfig). Parse the standard
        //    IMS-Settings + APPAUTH into an RcsImsConfig. The Digest password comes
        //    from the doc's APPAUTH/UserPwd when the ACS serves it; on an AKA/lab
        //    deployment where UserPwd is absent, fall back to the injected SIM Ki
        //    (debug.rcs.dr.pw) so lab bring-up still works.
        //    LAB: when the modem's ACS fetch is blocked (the reason DR exists), a
        //    debuggable build can point debug.rcs.dr.acsfile at a real ACS doc on
        //    disk; it feeds the SAME parse->config path a modem-delivered doc would.
        byte[] acs = mAcsConfigXml;
        if (acs == null && debuggable()) {
            acs = readAcsDebugFile(SystemProperties.get("debug.rcs.dr.acsfile", ""));
        }
        if (acs != null) {
            final AcsImsConfigParser.ImsSettings s = AcsImsConfigParser.parse(acs);
            final String pwFallback = debuggable()
                    ? emptyToNull(SystemProperties.get("debug.rcs.dr.pw", "")) : null;
            final RcsImsConfig acsCfg =
                    AcsImsConfigParser.toRcsImsConfig(s, pwFallback, transport);
            if (acsCfg != null) {
                LogUtil.i(TAG, SUBTAG + ": using ACS-derived RcsImsConfig: " + acsCfg);
                return acsCfg;
            }
            LogUtil.w(TAG, SUBTAG + ": ACS config present but not usable (missing "
                    + "IMS-Settings or Digest pw); falling back");
        }

        // 2) DEBUG lab override: build the RcsImsConfig entirely from debug.rcs.dr.*
        //    sysprops (debuggable builds only; gated on impi + pw). Used until the ACS
        //    fetch is wired end-to-end (and when the doc omits UserPwd).
        final RcsImsConfig dbg = debugConfigFromSysprops();
        if (dbg != null) {
            LogUtil.i(TAG, SUBTAG + ": using debug.rcs.dr.* lab RcsImsConfig: " + dbg);
            return dbg;
        }
        return null;
    }

    /** Build a lab {@link RcsImsConfig} from {@code debug.rcs.dr.*} sysprops on a
     *  debuggable build. Returns null unless both {@code impi} and {@code pw} are
     *  set (so production / non-lab installs fall through to REG_FAILED -> SMS). */
    @Nullable
    private static RcsImsConfig debugConfigFromSysprops() {
        final boolean debuggable =
                "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
        if (!debuggable) {
            return null;
        }
        final String impi = SystemProperties.get("debug.rcs.dr.impi", "");
        final String pw = SystemProperties.get("debug.rcs.dr.pw", "");
        if (TextUtils.isEmpty(impi) || TextUtils.isEmpty(pw)) {
            return null;
        }
        final String domain = orDef(SystemProperties.get("debug.rcs.dr.domain", ""),
                "ims.mnc001.mcc001.3gppnetwork.org");
        final String user = orDef(SystemProperties.get("debug.rcs.dr.user", ""),
                userFromImpi(impi));
        final String impu = orDef(SystemProperties.get("debug.rcs.dr.impu", ""),
                "sip:" + user + "@" + domain);
        final String pcscf = orDef(SystemProperties.get("debug.rcs.dr.pcscf", ""),
                "172.22.0.21");
        // Default SIPoTCP — see configFor: the P-CSCF delivers large terminating
        // over the UE's registered TCP flow; a UDP-only UE structurally misses it.
        final String transport = orDef(SystemProperties.get("debug.rcs.dr.transport", ""),
                "SIPoTCP");

        final RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        b.pcscfAddress = pcscf;
        b.pcscfPort = -1; // -1 => 5060 for udp/tcp
        b.domain = domain;
        b.privateIdentity = impi;
        b.publicIdentity = impu;
        b.userName = user;
        b.authDigestUsername = impi;
        b.authDigestPassword = pw;
        // debug.rcs.dr.ha1=true => treat debug.rcs.dr.pw as a precomputed HA1
        // (MD5(impi:realm:secret)) instead of a cleartext password, to lab-test the
        // HSS-HA1 / cleartext-free credential path.
        b.authDigestIsHa1 = SystemProperties.getBoolean("debug.rcs.dr.ha1", false);
        b.authDigestRealm = domain;
        b.authenticationScheme = "Digest";
        b.psSipTransport = transport;
        b.wifiSipTransport = transport;
        return b.build();
    }

    private static boolean debuggable() {
        return "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
    }

    @Nullable
    private static String emptyToNull(@Nullable final String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** DEBUG: read an ACS wap-provisioningdoc from {@code path} (debug.rcs.dr.acsfile)
     *  so the lab can exercise the ACS-driven config path without a modem fetch.
     *  Returns null on empty path / read error. */
    @Nullable
    private static byte[] readAcsDebugFile(@Nullable final String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            final java.io.File f = new java.io.File(path);
            final byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                    off += n;
                }
            }
            LogUtil.i(TAG, SUBTAG + ": read ACS debug file " + path + " (" + buf.length + "B)");
            return buf;
        } catch (final java.io.IOException | RuntimeException e) {
            LogUtil.w(TAG, SUBTAG + ": cannot read debug.rcs.dr.acsfile " + path + ": " + e);
            return null;
        }
    }

    private static String orDef(final String v, final String def) {
        return TextUtils.isEmpty(v) ? def : v;
    }

    private static String userFromImpi(final String impi) {
        final int at = impi.indexOf('@');
        return at > 0 ? impi.substring(0, at) : impi;
    }

    /** Bridges the app-stack {@link Transport.Listener} to the
     *  {@link CarrierDrModeDriver.Listener} the {@code :ims} service forwards. */
    private final class TransportListener implements Transport.Listener {
        private final int mSub;

        TransportListener(final int sub) {
            this.mSub = sub;
        }

        @Override
        public void onIncomingMessage(final String fromUri, final String body,
                final String messageId) {
            onIncomingMessage(fromUri, body, messageId, null);
        }

        @Override
        public void onIncomingMessage(final String fromUri, final String body,
                final String messageId, final String e2eeSchemeId) {
            final Listener l = mListener;
            if (l != null) {
                l.onIncomingText(mSub, stripTel(fromUri), body,
                        messageId != null ? messageId
                                : java.util.UUID.randomUUID().toString(),
                        e2eeSchemeId);
            }
        }

        @Override
        public void onIncomingContent(final String fromUri, final byte[] body,
                final String contentType, final String messageId, final String e2eeSchemeId) {
            // The last app-stack hop before the :ims seam. Keeping the bytes here is
            // the whole point — the String overload above cannot carry a decrypted image.
            final Listener l = mListener;
            if (l != null) {
                l.onIncomingContent(mSub, stripTel(fromUri), body, contentType,
                        messageId != null ? messageId
                                : java.util.UUID.randomUUID().toString(),
                        e2eeSchemeId);
            }
        }

        @Override
        public void onMessageStatus(final String messageId, final Message.Status status,
                final String errorReason) {
            final Listener l = mListener;
            if (l == null) {
                return;
            }
            final int mapped = mapStatus(status);
            if (mapped < 0) {
                return; // interim (SENDING) — no terminal callback yet.
            }
            l.onMessageStatus(mSub, messageId, mapped, errorReason);
        }

        @Override
        public void onRegistrationStateChanged(final Transport.RegistrationState state,
                final String reason) {
            final Listener l = mListener;
            if (l == null) {
                return;
            }
            l.onRegistrationState(mSub, mapRegState(state), reason);
            if (state == Transport.RegistrationState.REGISTERED) {
                l.onProvisioningState(mSub, IRcsProviderCallback.PROV_CONFIGURED);
            } else if (state == Transport.RegistrationState.FAILED) {
                l.onProvisioningState(mSub, IRcsProviderCallback.PROV_NOT_PROVISIONED);
            }
        }
    }

    private static int mapRegState(final Transport.RegistrationState state) {
        switch (state) {
            case REGISTERED:   return IRcsProviderCallback.REG_REGISTERED;
            case REGISTERING:  return IRcsProviderCallback.REG_REGISTERING;
            case FAILED:       return IRcsProviderCallback.REG_FAILED;
            case UNREGISTERED:
            default:           return IRcsProviderCallback.REG_UNREGISTERED;
        }
    }

    /** @return an IRcsProviderCallback.STATUS_* code, or -1 for a non-terminal
     *  (SENDING) status that must not be reported as a terminal outcome. */
    private static int mapStatus(final Message.Status status) {
        if (status == null) {
            return IRcsProviderCallback.STATUS_FAILED;
        }
        switch (status) {
            case SENT:      return IRcsProviderCallback.STATUS_SENT;
            case DELIVERED: return IRcsProviderCallback.STATUS_DELIVERED;
            case DISPLAYED: return IRcsProviderCallback.STATUS_DISPLAYED;
            case FAILED:    return IRcsProviderCallback.STATUS_FAILED;
            default:        return -1; // SENDING / QUEUED / etc.
        }
    }

    @Nullable
    private static String stripTel(@Nullable final String uri) {
        if (uri == null) {
            return null;
        }
        String s = uri.trim();
        // Strip name-addr angle brackets first: an inbound From is typically
        // "<sip:11012026331@domain>" — without this the scheme checks below never
        // match and the whole raw URI leaks into the conversation title.
        if (s.startsWith("<") && s.endsWith(">") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.startsWith("tel:")) {
            s = s.substring(4);
        } else if (s.startsWith("sip:") || s.startsWith("sips:")) {
            s = s.substring(s.indexOf(':') + 1);
            final int at = s.indexOf('@');
            if (at > 0) {
                s = s.substring(0, at);
            }
        }
        final int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi);
        }
        s = s.trim();
        // Normalize a bare E.164 (all digits, no '+') to +E.164 so the conversation
        // displays the phone number (and can match a contact), not the SIP user part.
        if (!s.isEmpty() && !s.startsWith("+")) {
            boolean allDigits = true;
            for (int i = 0; i < s.length(); i++) {
                if (!Character.isDigit(s.charAt(i))) { allDigits = false; break; }
            }
            if (allDigits) {
                s = "+" + s;
            }
        }
        return s;
    }
}
