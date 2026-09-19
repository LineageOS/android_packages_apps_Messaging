/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.os.SystemProperties;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.CarrierRcsTransport;
import com.android.messaging.rcs.carrier.RcsImsConfig;

import com.android.messaging.util.LogUtil;

/**
 * The DR driver: adapts the app's own SIP/MSRP stack ({@link CarrierRcsTransport}) to
 * {@link CarrierDrModeDriver} for {@link CarrierImsService}. The configuration comes from the
 * autoconfiguration document the modem fetched; without a usable one the line reports
 * {@code REG_FAILED} and falls back to SMS. See docs/rcs/carrier-transport.md.
 */
public final class CarrierStackDrModeDriver implements CarrierDrModeDriver {
    private static final String TAG = LogUtil.BUGLE_TAG;
    private static final String SUBTAG = "CarrierDrDriver";

    private final Context mContext;

    @Nullable private volatile CarrierRcsTransport mTransport;
    @Nullable private volatile Listener mListener;
    private volatile int mSubId = -1;
    /** The latest autoconfiguration document, or null before the first. */
    @Nullable private volatile byte[] mAcsConfigXml;

    public CarrierStackDrModeDriver(final Context context) {
        this.mContext = context.getApplicationContext();
    }

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
            // The transport picks pager mode or a session by size; statuses come back on the
            // listener.
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
            return; // best effort
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
            // Delivered receipts are sent automatically by the receiver; this path sends display
            // receipts.
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

    /**
     * First match: the autoconfiguration document (or, on a debuggable build, the file named by
     * {@code debug.rcs.dr.acsfile}), then {@code debug.rcs.dr.*} properties; else null.
     */
    @Nullable
    private RcsImsConfig configFor(final int subId, @Nullable final String msisdn,
            @Nullable final String mccMnc) {
        // TCP by default: the P-CSCF sends a terminating request larger than its UDP MTU over the
        // UE's registered flow, and a UE registered over UDP has no TCP listener for it.
        final String transport = orDef(
                debuggable() ? SystemProperties.get("debug.rcs.dr.transport", "") : "",
                "SIPoTCP");

        // The password comes from the document, else, on a debuggable build, debug.rcs.dr.pw
        // (an AKA deployment keeps the key on the SIM).
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
                // The config names the IMPI and IMPU; a debug build alone logs it.
                if (RcsDebug.isDebugBuild()) {
                    LogUtil.i(TAG, SUBTAG + ": using ACS-derived RcsImsConfig: " + acsCfg);
                } else {
                    LogUtil.i(TAG, SUBTAG + ": using ACS-derived RcsImsConfig");
                }
                return acsCfg;
            }
            LogUtil.w(TAG, SUBTAG + ": ACS config present but not usable (missing "
                    + "IMS-Settings or Digest pw); falling back");
        }

        final RcsImsConfig dbg = debugConfigFromSysprops();
        if (dbg != null) {
            LogUtil.i(TAG, SUBTAG + ": using debug.rcs.dr.* debug RcsImsConfig: " + dbg);
            return dbg;
        }
        return null;
    }

    /** Debuggable builds only; null unless {@code debug.rcs.dr.impi} and {@code .pw} are set. */
    @Nullable
    private static RcsImsConfig debugConfigFromSysprops() {
        final boolean debuggable = RcsDebug.isDebugBuild();
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
        // TCP by default; see configFor.
        final String transport = orDef(SystemProperties.get("debug.rcs.dr.transport", ""),
                "SIPoTCP");

        final RcsImsConfig.Builder b = new RcsImsConfig.Builder();
        b.pcscfAddress = pcscf;
        b.pcscfPort = -1; // the transport's default port
        b.domain = domain;
        b.privateIdentity = impi;
        b.publicIdentity = impu;
        b.userName = user;
        b.authDigestUsername = impi;
        b.authDigestPassword = pw;
        // debug.rcs.dr.ha1: debug.rcs.dr.pw is a precomputed HA1 rather than a password.
        b.authDigestIsHa1 = SystemProperties.getBoolean("debug.rcs.dr.ha1", false);
        b.authDigestRealm = domain;
        b.authenticationScheme = "Digest";
        b.psSipTransport = transport;
        b.wifiSipTransport = transport;
        return b.build();
    }

    private static boolean debuggable() {
        return RcsDebug.isDebugBuild();
    }

    @Nullable
    private static String emptyToNull(@Nullable final String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** Null for an empty path or a read error. */
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

    /** Forwards the stack's events to the {@link CarrierDrModeDriver.Listener}. */
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
            // Keep the bytes: the String form cannot carry an image.
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
                return; // interim status
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

    /**
     * An {@code IRcsProviderCallback.STATUS_*}, or -1 for an interim status that is not reported.
     */
    private static int mapStatus(final Message.Status status) {
        if (status == null) {
            return IRcsProviderCallback.STATUS_FAILED;
        }
        switch (status) {
            case SENT:      return IRcsProviderCallback.STATUS_SENT;
            case DELIVERED: return IRcsProviderCallback.STATUS_DELIVERED;
            case DISPLAYED: return IRcsProviderCallback.STATUS_DISPLAYED;
            case FAILED:    return IRcsProviderCallback.STATUS_FAILED;
            default:        return -1;        }
    }

    @Nullable
    private static String stripTel(@Nullable final String uri) {
        if (uri == null) {
            return null;
        }
        String s = uri.trim();
        // An inbound From is usually a bracketed name-addr.
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
        // All digits without '+' is taken as E.164, so the conversation shows the number.
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
