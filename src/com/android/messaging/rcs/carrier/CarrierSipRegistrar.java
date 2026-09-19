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
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.util.Log;

import com.android.messaging.rcs.carrier.SipDigestAuth;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import javax.sip.ClientTransaction;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.header.AuthorizationHeader;
import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.MaxForwardsHeader;
import javax.sip.header.RouteHeader;
import javax.sip.header.SupportedHeader;
import javax.sip.header.ToHeader;
import javax.sip.header.UserAgentHeader;
import javax.sip.header.ViaHeader;
import javax.sip.header.WWWAuthenticateHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * SIP REGISTER state machine for the GSMA UP 2.4 carrier-RCS path. Targets the
 * carrier P-CSCF described by an {@link RcsImsConfig} (which originates from
 * Pev3 / the carrier ACS). Uses JAIN-SIP, the same library {@code OttTransport}
 * uses for the self-hosted Kamailio path — but reuses none of its state, so
 * the OTT contract is unaffected.
 *
 * <p>Out of scope here (tracked separately):
 * <ul>
 *   <li>SIP MESSAGE / IMDN pager-mode (sub-2).</li>
 *   <li>MSRP framing + chat-session INVITE/SDP (sub-3/4).</li>
 *   <li>IMS-AKA / GBA / TLS cert pinning — most UP carriers in our scope
 *       use plain SIP Digest, so we wire that today.</li>
 * </ul>
 *
 * <p>Threading: all SIP API calls happen on the {@code sipThread}
 * HandlerThread, matching OttTransport's pattern.
 *
 * <p>Lifecycle states: see {@link State}. The expected nominal path is
 * UNREGISTERED &rarr; REGISTERING &rarr; REGISTERED. On 401/407 we re-send
 * with Digest credentials. On any 4xx/5xx other than 401/407 we go to FAILED
 * and stay there until {@link #stop()} + {@link #start()}.
 */
public final class CarrierSipRegistrar implements SipListener {

    private static final String TAG = "CarrierSipRegistrar";

    /** UA banner. Mirrors GSMA UP §A.1.4 expectations. */
    private static final String USER_AGENT =
            "IM-client/OMA1.0 lineageos-messaging/1.0";

    /** UP 2.4 feature tags advertised in the Contact header for 1:1 chat.
     *  Source: Google Messages' P-Preferred-Service constants plus the
     *  GSMA UP 2.4 §A.1.4 manifest. Group / file-transfer / presence tags
     *  are added by their respective modules.
     *
     *  <p>RFC 3840: multiple ICSI values go in a SINGLE {@code +g.3gpp.icsi-ref}
     *  media-feature-tag as a COMMA-separated quoted list — NOT repeated params.
     *  Emitting three separate {@code +g.3gpp.icsi-ref="..."} params made the
     *  S-CSCF collapse the duplicate param name to the LAST value (largemsg only),
     *  so the CPM AS saw B as largemsg-capable but NOT session-capable and its
     *  oma.cpm.session fan-out found 0 targets (observed 2026-07-19). Combine them. */
    static final String[] FEATURE_TAGS_1_TO_1 = {
            "+g.gsma.rcs.msgrevoke",
            "+g.3gpp.icsi-ref=\""
                    + "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session,"
                    + "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg,"
                    + "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
    };

    /** Lifecycle states. Defined as a top-level enum at
     *  {@link CarrierSipRegistrarState} so the carrier-RCS host-testable
     *  bridge can reference them without pulling Android. Kept as a
     *  nested type alias for source compatibility. */
    public static final class State {
        private State() {}
        public static final CarrierSipRegistrarState UNREGISTERED =
                CarrierSipRegistrarState.UNREGISTERED;
        public static final CarrierSipRegistrarState REGISTERING =
                CarrierSipRegistrarState.REGISTERING;
        public static final CarrierSipRegistrarState REGISTERED =
                CarrierSipRegistrarState.REGISTERED;
        public static final CarrierSipRegistrarState FAILED =
                CarrierSipRegistrarState.FAILED;
    }

    public interface Listener {
        void onStateChanged(CarrierSipRegistrarState state, String reason);
    }

    /**
     * Optional hook called from {@link #processRequest} for any inbound SIP
     * request reaching the registrar's stack. The carrier transport sets this
     * to forward MESSAGE (and, later, INVITE for MSRP) into its own handlers
     * — see {@code CarrierRcsTransport}. Set to null to disable.
     */
    public interface RequestForwarder {
        void onRequest(RequestEvent event);
    }

    /**
     * Optional hook for inbound SIP <em>responses</em> whose CSeq method is not
     * REGISTER (e.g. the 200 OK to an MSRP-session INVITE the transport sent). The
     * registrar only owns REGISTER transactions; everything else is forwarded to
     * {@code CarrierRcsTransport} so its MSRP session manager can complete the
     * dialog. Set to null to drop non-REGISTER responses (log-only).
     */
    public interface ResponseForwarder {
        void onResponse(ResponseEvent event);
    }

    private final Context appContext;
    private final RcsImsConfig config;

    private final HandlerThread sipThread;
    private final Handler sipHandler;

    private SipStack sipStack;
    private SipProvider sipProvider;
    private ListeningPoint listeningPoint;
    private MessageFactory messageFactory;
    private HeaderFactory headerFactory;
    private AddressFactory addressFactory;

    private String localIp;
    private int localPort;
    private String transportProto;

    // IMS-APN binding (Option A): the P-CSCF runs an N5 QoS policy-auth to the PCF
    // on REGISTER; a SIP socket on the internet APN has no IMS media-policy binding
    // so the PCF 403s -> P-CSCF 412. Routing the SIP over the IMS APN (like the
    // modem) makes the QoS auth succeed. Held so doStop can unbind + release.
    private android.net.ConnectivityManager cmForIms;
    private android.net.ConnectivityManager.NetworkCallback imsNetworkCallback;
    // P-CSCF address delivered by the IMS PDN via PCO (LinkProperties.getPcscfServers),
    // the Google Messages IMS discovery mechanism. When present it overrides the ACS doc's
    // P-CSCF (which may be an FQDN the UE can't DNS-resolve) — a real IMS client uses
    // the PCO-signalled P-CSCF, not a name lookup. Null when PCO carried none.
    private volatile String mPcoPcscf;

    /** Which bearer we register over, and therefore who is authoritative about the P-CSCF
     *. Read once per registrar so a mid-session sysprop change cannot make
     *  the network binding and the proxy address disagree. */
    private final CarrierSipPlane sipPlane =
            CarrierSipPlane.fromString(SystemProperties.get(CarrierSipPlane.SYSPROP, "lab"));

    private final AtomicLong cseq = new AtomicLong(1);
    private CallIdHeader registerCallId;
    private int registerAttempts;
    // Last Digest challenge from a successful REGISTER auth, reused to authenticate
    // the Expires:0 de-REGISTER so the S-CSCF actually purges the binding (an
    // unauthenticated de-REGISTER is 401'd and the record — with its cached iFC —
    // survives). nc is incremented per reuse for qop=auth.
    private javax.sip.header.WWWAuthenticateHeader cachedChallenge;
    private int deregNc = 2;

    private Listener listener;
    private RequestForwarder requestForwarder;
    private ResponseForwarder responseForwarder;
    private CarrierSipRegistrarState state = CarrierSipRegistrarState.UNREGISTERED;

    public CarrierSipRegistrar(Context ctx, RcsImsConfig config) {
        this.appContext = ctx.getApplicationContext();
        this.config = config;
        this.sipThread = new HandlerThread("rcs-carrier-sip");
        this.sipThread.start();
        this.sipHandler = new Handler(sipThread.getLooper());
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Install a request-forwarder. Receives any non-response SIP event the
     *  stack delivers to this registrar (typically MESSAGE / INVITE from the
     *  carrier P-CSCF). Called on the registrar's sipThread. */
    public void setRequestForwarder(RequestForwarder f) { this.requestForwarder = f; }
    public void setResponseForwarder(ResponseForwarder f) { this.responseForwarder = f; }

    public CarrierSipRegistrarState getState() { return state; }

    /** The {@link RcsImsConfig} this registrar was built with (never null). */
    public RcsImsConfig getConfig() { return config; }

    // ---------- post-start accessors (null until start() completes) ----------
    // Used by CarrierRcsTransport to construct CarrierMessageSender /
    // CarrierMessageReceiver bound to the same JAIN-SIP primitives this
    // registrar set up. All getters return null before start() has run
    // through doStart() successfully.

    public SipProvider getSipProvider()       { return sipProvider; }
    public MessageFactory getMessageFactory() { return messageFactory; }
    public HeaderFactory getHeaderFactory()   { return headerFactory; }
    public javax.sip.address.AddressFactory getAddressFactory() { return addressFactory; }
    public String getLocalIp()                { return localIp; }
    /** Effective P-CSCF host (PCO-signalled IP when present, else the config value)
     *  so the MESSAGE/MSRP path routes to the same next hop the REGISTER used. */
    public String getPcscfHost()              { return pcscfHost(); }
    public int getLocalPort()                 { return localPort; }
    public String getTransportProto()         { return transportProto; }

    public void start() { sipHandler.post(this::doStart); }

    public void stop() { sipHandler.post(this::doStop); }

    // ---------- sipThread-only ----------

    private void doStart() {
        if (sipStack != null) return;
        try {
            boolean onWifi = isOnWifi(appContext);
            // Transport comes from the config (RcsImsConfig.psSipTransport), which
            // defaults to SIPoTCP — the architectural requirement, since the P-CSCF
            // delivers large terminating INVITEs over the registered TCP flow (a
            // UDP-only UE can't receive them). Override with debug.rcs.dr.transport.
            transportProto = config.jainSipTransport(onWifi);
            int pcscfPort = config.effectivePcscfPort(onWifi);

            // Option A: route SIP over the IMS APN so the P-CSCF's N5 QoS auth to
            // the PCF succeeds (internet APN -> PCF 403 -> P-CSCF 412). Bind the
            // process to the IMS network and use its address as the local IP. Falls
            // back to the default (internet APN) if the IMS network can't be acquired.
            String imsIp = acquireImsNetwork();
            localIp = imsIp != null ? imsIp : pickLocalIp();

            SipFactory sipFactory = SipFactory.getInstance();
            Properties props = new Properties();
            props.setProperty("javax.sip.STACK_NAME",
                    "rcs-carrier-" + System.nanoTime());
            // Explicit UDP socket buffers + unbounded message size (hardening
            // against kernel SO_RCVBUF burst drops). NOTE: this does NOT fix the
            // large-terminating-MESSAGE drop — a terminating CPM MESSAGE near/over
            // the IMS link MTU (~1450B on the wire; e.g. isComposing's ~616B CPIM or
            // a 300B+ pager text) arrives at the UE NIC but JAIN-SIP never processes
            // it (no 200, sender retransmits). Root cause is UDP fragmentation on the
            // IMS bearer; the real fix is SIP-over-TCP for large terminating requests
            // (RFC 3261 §18.1.1) — tracked as a transport gap.
            props.setProperty("gov.nist.javax.sip.RECEIVE_UDP_BUFFER_SIZE", "65536");
            props.setProperty("gov.nist.javax.sip.SEND_UDP_BUFFER_SIZE", "65536");
            props.setProperty("gov.nist.javax.sip.MAX_MESSAGE_SIZE", "0");

            sipStack = sipFactory.createSipStack(props);
            messageFactory = sipFactory.createMessageFactory();
            headerFactory = sipFactory.createHeaderFactory();
            addressFactory = sipFactory.createAddressFactory();

            // This JAIN-SIP build rejects port 0 ("bad port") — it will not pick an
            // ephemeral port itself. Use the configured local port if set, else grab
            // a free one from the OS and bind that explicitly.
            // debug.rcs.dr.localport: pin the local SIP port on a debuggable build so
            // reinstalls reuse the SAME Contact (a fresh ephemeral port each build
            // accumulates stale S-CSCF bindings + staleth the AS contact cache, which
            // silently breaks terminating delivery during iterative testing).
            final int fixedPort = SystemProperties.getInt("debug.rcs.dr.localport", 0);
            int bindPort = fixedPort > 0
                    ? fixedPort
                    : (config.localSipPort > 0
                            ? config.localSipPort
                            : pickFreePort(transportProto));
            listeningPoint = sipStack.createListeningPoint(localIp, bindPort, transportProto);
            localPort = listeningPoint.getPort();
            sipProvider = sipStack.createSipProvider(listeningPoint);
            sipProvider.addSipListener(this);

            Log.i(TAG, "carrier SIP stack on " + localIp + ":" + localPort
                    + "/" + transportProto + " -> "
                    + pcscfHost() + ":" + pcscfPort);
            updateState(State.REGISTERING, null);
            Request req = buildRegister(null);
            ClientTransaction tx = sipProvider.getNewClientTransaction(req);
            tx.sendRequest();
            Log.i(TAG, "REGISTER sent (attempt " + registerAttempts + ")");
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            updateState(State.FAILED, e.getMessage());
            cleanup();
        }
    }

    private void doStop() {
        if (sipStack == null) return;
        try {
            Request req = buildDeregister();
            sipProvider.getNewClientTransaction(req).sendRequest();
        } catch (Exception e) {
            Log.w(TAG, "deregister failed", e);
        }
        cleanup();
        updateState(State.UNREGISTERED, null);
    }

    private void cleanup() {
        try {
            if (sipProvider != null) {
                sipProvider.removeSipListener(this);
                if (listeningPoint != null) sipProvider.removeListeningPoint(listeningPoint);
                sipStack.deleteSipProvider(sipProvider);
            }
            if (listeningPoint != null && sipStack != null) {
                sipStack.deleteListeningPoint(listeningPoint);
            }
            if (sipStack != null) sipStack.stop();
        } catch (Exception e) {
            Log.w(TAG, "cleanup", e);
        }
        sipProvider = null;
        listeningPoint = null;
        sipStack = null;
        registerCallId = null;
        registerAttempts = 0;
        releaseImsNetwork();
    }

    // ---------- request builders (package-private for unit tests) ----------

    /**
     * Build a UP-compliant REGISTER request. If {@code challenge} is non-null
     * the request carries an Authorization header computed by
     * {@link SipDigestAuth}.
     *
     * <p>Header set:
     * <ul>
     *   <li>Request-URI: {@code sip:<domain>}</li>
     *   <li>From / To: {@code <publicIdentity>;tag=...}</li>
     *   <li>Via: {@code SIP/2.0/<transport> <localIp>:<localPort>}</li>
     *   <li>Contact: {@code <sip:<userName>@<localIp>:<localPort>;transport=<t>>;<feature-tags>}</li>
     *   <li>Route: {@code <sip:<pcscf>:<port>;transport=<t>;lr>}</li>
     *   <li>Expires: 600000 (UP profile default).</li>
     *   <li>Supported: path</li>
     *   <li>User-Agent.</li>
     *   <li>Authorization (only when {@code challenge} provided).</li>
     * </ul>
     */
    Request buildRegister(WWWAuthenticateHeader challenge) throws ParseException, SipException {
        if (config.domain == null || config.pcscfAddress == null
                || config.userName == null || config.publicIdentity == null) {
            throw new SipException("Incomplete RcsImsConfig: " + config);
        }

        SipURI requestUri = addressFactory.createSipURI(null, config.domain);

        // Public identity may be a tel: URI; JAIN-SIP createURI handles both.
        Address selfAddr = addressFactory.createAddress(
                addressFactory.createURI(config.publicIdentity));
        FromHeader from = headerFactory.createFromHeader(selfAddr, "tag-" + System.nanoTime());
        ToHeader to = headerFactory.createToHeader(selfAddr, null);

        ArrayList<ViaHeader> vias = new ArrayList<>();
        ViaHeader via = headerFactory.createViaHeader(localIp, localPort, transportProto, null);
        via.setRPort();
        vias.add(via);

        if (registerCallId == null) {
            registerCallId = sipProvider.getNewCallId();
            registerAttempts = 0;
        }
        registerAttempts++;

        CSeqHeader cseqHeader = headerFactory.createCSeqHeader(
                cseq.getAndIncrement(), Request.REGISTER);
        MaxForwardsHeader maxFwd = headerFactory.createMaxForwardsHeader(70);

        // Build the Contact as a RAW header string. JAIN-SIP's setParameter()
        // mangles RFC 3840 feature tags two ways that make Kamailio's core parser
        // 500 (open5gs, 2026-07-18): a valueless tag becomes "name=" (trailing '='
        // with empty body — rejected), and an icsi-ref value is emitted UNQUOTED
        // (the urn contains ':' / '%' which MUST be a quoted string). The tags in
        // FEATURE_TAGS_1_TO_1 are already correctly formatted (bare for valueless,
        // double-quoted for urn values), so we emit them verbatim and parse the
        // whole Contact via createHeader — exactly how CarrierMessageSender builds
        // its Accept-Contact.
        StringBuilder cb = new StringBuilder();
        cb.append("<sip:").append(config.userName).append('@').append(localIp)
                .append(':').append(localPort)
                .append(";transport=").append(transportProto).append('>');
        for (String tag : FEATURE_TAGS_1_TO_1) {
            cb.append(';').append(tag);
        }
        // debug.rcs.dr.expires: override the registration Expires (seconds) for
        // wire-format iteration against a lab S-CSCF. Default 600000 (UP profile).
        int expiresSec = SystemProperties.getInt("debug.rcs.dr.expires", 600000);
        cb.append(";expires=").append(expiresSec);
        ContactHeader contact =
                (ContactHeader) headerFactory.createHeader("Contact", cb.toString());

        // Route through carrier P-CSCF.
        boolean onWifi = isOnWifi(appContext);
        SipURI proxyUri = addressFactory.createSipURI(null, pcscfHost());
        proxyUri.setPort(config.effectivePcscfPort(onWifi));
        proxyUri.setTransportParam(transportProto);
        proxyUri.setLrParam();
        RouteHeader route = headerFactory.createRouteHeader(
                addressFactory.createAddress(proxyUri));

        Request request = messageFactory.createRequest(requestUri, Request.REGISTER,
                registerCallId, cseqHeader, from, to, vias, maxFwd);
        request.addHeader(contact);
        request.addHeader(headerFactory.createExpiresHeader(expiresSec));
        request.addHeader(route);
        SupportedHeader supported = headerFactory.createSupportedHeader("path");
        request.addHeader(supported);
        UserAgentHeader ua = headerFactory.createUserAgentHeader(
                java.util.Collections.singletonList(USER_AGENT));
        request.addHeader(ua);

        if (challenge != null) {
            String pw = config.authDigestPassword;
            String user = config.authDigestUsername != null
                    ? config.authDigestUsername : config.userName;
            String nonce = challenge.getNonce();
            String realm = challenge.getRealm() != null
                    ? challenge.getRealm() : config.authDigestRealm;
            String qop = challenge.getQop();
            String cnonce = qop != null ? SipDigestAuth.newCnonce() : null;
            String nc = qop != null ? "00000001" : null;
            String digestUri = requestUri.toString();
            // When the credential is a precomputed HA1 (ACS AAuthType=Digest-HA1),
            // use it directly — don't re-hash MD5(user:realm:HA1).
            String resp = config.authDigestIsHa1
                    ? SipDigestAuth.responseWithHa1(pw,
                            Request.REGISTER, digestUri, nonce, qop, nc, cnonce)
                    : SipDigestAuth.response(user, realm, pw,
                            Request.REGISTER, digestUri, nonce, qop, nc, cnonce);

            AuthorizationHeader auth = headerFactory.createAuthorizationHeader("Digest");
            auth.setUsername(user);
            auth.setRealm(realm);
            auth.setNonce(nonce);
            auth.setURI(addressFactory.createURI(digestUri));
            auth.setResponse(resp);
            auth.setAlgorithm(challenge.getAlgorithm() != null
                    ? challenge.getAlgorithm() : "MD5");
            if (qop != null) {
                auth.setQop(qop);
                auth.setCNonce(cnonce);
                auth.setNonceCount(Integer.parseInt(nc, 16));
            }
            request.addHeader(auth);
        } else if (!SystemProperties.getBoolean("debug.rcs.dr.noauth", false)) {
            // IMS initial REGISTER: carry an "empty" Digest Authorization so the
            // S-CSCF/HSS can identify the IMPI and issue the 401 challenge. Without
            // it, Kamailio ims_auth cannot resolve the private identity and returns
            // 403 Forbidden (observed on the open5gs lab) rather than 401.
            // debug.rcs.dr.noauth=true skips this to test S-CSCFs that expect a
            // bare initial REGISTER (no Authorization) then challenge.
            String user = config.authDigestUsername != null
                    ? config.authDigestUsername : config.userName;
            String realm = config.authDigestRealm != null
                    ? config.authDigestRealm : config.domain;
            AuthorizationHeader auth = headerFactory.createAuthorizationHeader("Digest");
            auth.setUsername(user);
            auth.setRealm(realm);
            auth.setNonce("");
            auth.setURI(addressFactory.createURI(requestUri.toString()));
            auth.setResponse("");
            auth.setAlgorithm("MD5");
            request.addHeader(auth);
        }
        return request;
    }

    Request buildDeregister() throws ParseException, SipException {
        // Expires: 0 REGISTER. No Authorization unless we cached the challenge —
        // most carriers will 401 the deregister; we still tear down regardless.
        SipURI requestUri = addressFactory.createSipURI(null, config.domain);
        Address selfAddr = addressFactory.createAddress(
                addressFactory.createURI(config.publicIdentity));
        FromHeader from = headerFactory.createFromHeader(selfAddr,
                "tag-" + System.nanoTime());
        ToHeader to = headerFactory.createToHeader(selfAddr, null);
        ArrayList<ViaHeader> vias = new ArrayList<>();
        vias.add(headerFactory.createViaHeader(localIp, localPort, transportProto, null));
        CSeqHeader cs = headerFactory.createCSeqHeader(
                cseq.getAndIncrement(), Request.REGISTER);
        MaxForwardsHeader mf = headerFactory.createMaxForwardsHeader(70);

        SipURI contactUri = addressFactory.createSipURI(config.userName, localIp);
        contactUri.setPort(localPort);
        contactUri.setTransportParam(transportProto);
        ContactHeader contact = headerFactory.createContactHeader(
                addressFactory.createAddress(contactUri));
        ExpiresHeader exp = headerFactory.createExpiresHeader(0);

        // Route through the carrier P-CSCF — same as buildRegister. Without this
        // the de-REGISTER can't be routed (Request-URI is the home domain) and
        // the send fails, leaving the registration (and its cached iFC) live.
        boolean onWifi = isOnWifi(appContext);
        SipURI proxyUri = addressFactory.createSipURI(null, pcscfHost());
        proxyUri.setPort(config.effectivePcscfPort(onWifi));
        proxyUri.setTransportParam(transportProto);
        proxyUri.setLrParam();
        RouteHeader route = headerFactory.createRouteHeader(
                addressFactory.createAddress(proxyUri));

        Request req = messageFactory.createRequest(requestUri, Request.REGISTER,
                sipProvider.getNewCallId(), cs, from, to, vias, mf);
        req.addHeader(contact);
        req.addHeader(exp);
        req.addHeader(route);

        // Authenticate the de-REGISTER by reusing the cached challenge, so the
        // S-CSCF accepts it and PURGES the binding (unauthenticated -> 401 -> the
        // record + its cached iFC survive). Reuse the nonce with an incremented nc.
        if (cachedChallenge != null) {
            try {
                String pw = config.authDigestPassword;
                String user = config.authDigestUsername != null
                        ? config.authDigestUsername : config.userName;
                String nonce = cachedChallenge.getNonce();
                String realm = cachedChallenge.getRealm() != null
                        ? cachedChallenge.getRealm() : config.authDigestRealm;
                String qop = cachedChallenge.getQop();
                String cnonce = qop != null ? SipDigestAuth.newCnonce() : null;
                String nc = qop != null
                        ? String.format("%08x", deregNc++) : null;
                String digestUri = requestUri.toString();
                String resp = config.authDigestIsHa1
                        ? SipDigestAuth.responseWithHa1(pw,
                                Request.REGISTER, digestUri, nonce, qop, nc, cnonce)
                        : SipDigestAuth.response(user, realm, pw,
                                Request.REGISTER, digestUri, nonce, qop, nc, cnonce);
                AuthorizationHeader auth =
                        headerFactory.createAuthorizationHeader("Digest");
                auth.setUsername(user);
                auth.setRealm(realm);
                auth.setNonce(nonce);
                auth.setURI(addressFactory.createURI(digestUri));
                auth.setResponse(resp);
                auth.setAlgorithm(cachedChallenge.getAlgorithm() != null
                        ? cachedChallenge.getAlgorithm() : "MD5");
                if (qop != null) {
                    auth.setQop(qop);
                    auth.setCNonce(cnonce);
                    auth.setNonceCount(Integer.parseInt(nc, 16));
                }
                req.addHeader(auth);
            } catch (Exception e) {
                Log.w(TAG, "de-REGISTER auth build failed; sending unauthenticated", e);
            }
        }
        return req;
    }

    // ---------- SipListener ----------

    @Override
    public void processRequest(RequestEvent event) {
        // Registrar itself has no inbound requests to handle. If a forwarder
        // is wired (CarrierRcsTransport), hand the event over on the SIP
        // thread for ordering safety.
        final RequestForwarder f = requestForwarder;
        if (f == null) return;
        sipHandler.post(() -> {
            try {
                f.onRequest(event);
            } catch (Throwable t) {
                Log.w(TAG, "forwarder threw", t);
            }
        });
    }

    @Override
    public void processResponse(ResponseEvent event) {
        sipHandler.post(() -> handleResponse(event));
    }

    @Override
    public void processTimeout(TimeoutEvent event) {
        // Only a REGISTER transaction timeout means we lost the registration. A
        // MESSAGE (or other in-dialog) transaction timeout must NOT tear down the
        // registration — otherwise a single unanswered outbound MESSAGE (e.g. an
        // IMDN whose terminating leg times out) deregisters the whole client.
        String method = null;
        try {
            if (!event.isServerTransaction() && event.getClientTransaction() != null) {
                method = event.getClientTransaction().getRequest().getMethod();
            }
        } catch (final Throwable t) {
            // fall through — treat unknown as non-REGISTER (don't deregister)
        }
        Log.w(TAG, "transaction timeout, method=" + method);
        if (Request.REGISTER.equals(method)) {
            sipHandler.post(() -> updateState(State.FAILED, "REGISTER timeout"));
        }
        // else: a MESSAGE/other timeout — leave the registration intact.
    }

    @Override
    public void processIOException(IOExceptionEvent event) {
        Log.w(TAG, "ioex: " + event);
        sipHandler.post(() -> updateState(State.FAILED, "IOException"));
    }

    @Override public void processTransactionTerminated(TransactionTerminatedEvent e) {}
    @Override public void processDialogTerminated(DialogTerminatedEvent e) {}

    private void handleResponse(ResponseEvent event) {
        Response resp = event.getResponse();
        CSeqHeader cs = (CSeqHeader) resp.getHeader(CSeqHeader.NAME);
        if (!Request.REGISTER.equals(cs.getMethod())) {
            Log.i(TAG, "response " + resp.getStatusCode() + " " + resp.getReasonPhrase()
                    + " for " + cs.getMethod());
            // Non-REGISTER responses (e.g. INVITE 200 OK for an MSRP session) belong
            // to the transport's session manager — forward instead of dropping.
            final ResponseForwarder rf = responseForwarder;
            if (rf != null) {
                try {
                    rf.onResponse(event);
                } catch (Throwable t) {
                    Log.w(TAG, "response forwarder threw", t);
                }
            }
            return;
        }
        int status = resp.getStatusCode();

        // Provisional (1xx, e.g. 100 Trying) is not a final response — wait for
        // the real one instead of treating it as a failure.
        if (status < 200) return;

        if (status == Response.UNAUTHORIZED
                || status == Response.PROXY_AUTHENTICATION_REQUIRED) {
            if (registerAttempts >= 3) {
                updateState(State.FAILED, "auth retries exhausted");
                return;
            }
            WWWAuthenticateHeader challenge =
                    (WWWAuthenticateHeader) resp.getHeader(WWWAuthenticateHeader.NAME);
            if (challenge == null) {
                challenge = (WWWAuthenticateHeader) resp.getHeader("Proxy-Authenticate");
            }
            if (challenge == null) {
                updateState(State.FAILED, status + " without challenge");
                return;
            }
            this.cachedChallenge = challenge;  // reuse to authenticate de-REGISTER
            try {
                Request req = buildRegister(challenge);
                sipProvider.getNewClientTransaction(req).sendRequest();
            } catch (Exception e) {
                Log.e(TAG, "register retry failed", e);
                updateState(State.FAILED, e.getMessage());
            }
        } else if (status >= 200 && status < 300) {
            registerCallId = null;
            registerAttempts = 0;
            updateState(State.REGISTERED, null);
        } else {
            updateState(State.FAILED, "REGISTER " + status);
        }
    }

    private void updateState(CarrierSipRegistrarState s, String reason) {
        this.state = s;
        Log.i(TAG, "state=" + s + (reason != null ? " (" + reason + ")" : ""));
        if (listener != null) listener.onStateChanged(s, reason);
    }

    // ---------- helpers ----------

    private static boolean isOnWifi(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /** Acquire the cellular IMS-APN network, bind this process to it (so all
     *  JAIN-SIP sockets route over the IMS PDN), and return its local IPv4 — or
     *  null on timeout/failure (caller falls back to the default route). Gated by
     *  {@code debug.rcs.dr.imsapn} (default true). The provider holds
     *  CONNECTIVITY_USE_RESTRICTED_NETWORKS so it may request the restricted IMS
     *  capability. */
    private String acquireImsNetwork() {
        // THE DEFAULT BEARER IS A DECISION HERE, NOT AN OUTCOME. Returning null from
        // this arm is the same VALUE the catch block below returns, and that is exactly why it has
        // to be a separate, named arm: the caller cannot tell "we chose the default bearer" from
        // "we tried for the IMS PDN and failed" by looking at the return, so the log line is the
        // only place the difference exists. Before this, a device with no IMS PDN reached the
        // default bearer through the CATCH — a path reached by failure that reports like one
        // reached by design.
        if (!sipPlane.usesImsPdn()) {
            Log.i(TAG, "sipPlane=" + sipPlane + " — NOT binding the IMS PDN; registering over the "
                    + "default bearer BY DESIGN, with the P-CSCF from the ACS document. This is a "
                    + "chosen plane, not a fallback.");
            return null;
        }
        if (!SystemProperties.getBoolean("debug.rcs.dr.imsapn", true)) {
            Log.i(TAG, "IMS-APN binding disabled (debug.rcs.dr.imsapn=false)");
            return null;
        }
        try {
            cmForIms = (android.net.ConnectivityManager)
                    appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cmForIms == null) return null;
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                    // IMS is a restricted capability — drop the implicit NOT_RESTRICTED.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build();
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            final android.net.Network[] holder = new android.net.Network[1];
            imsNetworkCallback = new android.net.ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(android.net.Network network) {
                    holder[0] = network;
                    latch.countDown();
                }
            };
            cmForIms.requestNetwork(req, imsNetworkCallback);
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    || holder[0] == null) {
                Log.w(TAG, "IMS network not available within 10s; falling back to default APN");
                return null;
            }
            android.net.Network ims = holder[0];
            // Route this process's sockets over the IMS PDN.
            cmForIms.bindProcessToNetwork(ims);
            // Find the IMS network's local IPv4.
            android.net.LinkProperties lp = cmForIms.getLinkProperties(ims);
            String ip = null;
            if (lp != null) {
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    java.net.InetAddress a = la.getAddress();
                    if (a != null && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                            && a.getAddress().length == 4) {
                        ip = a.getHostAddress();
                        break;
                    }
                }
            }
            // Google Messages IMS P-CSCF discovery: the IMS PDN signals the P-CSCF address(es)
            // via PCO, surfaced in LinkProperties.getPcscfServers() (hidden API — call
            // reflectively so we build against the SDK stubs). Prefer this over the ACS
            // doc's (possibly unresolvable FQDN) P-CSCF, exactly like a real IMS client.
            mPcoPcscf = firstPcscfFromPco(lp);
            Log.i(TAG, "bound to IMS APN network " + ims + " localIp=" + ip
                    + " iface=" + (lp != null ? lp.getInterfaceName() : "?")
                    + " pcoPcscf=" + mPcoPcscf);
            return ip;
        } catch (Throwable t) {
            // A FAILED PRECONDITION OF THIS PLANE, not a neutral degrade. On
            // LAB_IMS_PDN the IMS PDN is the point; continuing on the default bearer produces a
            // registration that is not the profile anyone selected, and it used to do so while
            // pcscfHost() still returned a stale PCO address. Cleared here so a failed acquire
            // cannot leave one behind for the next attempt to read as authoritative.
            mPcoPcscf = null;
            Log.w(TAG, "acquireImsNetwork FAILED on sipPlane=" + sipPlane + " — the IMS PDN is this "
                    + "plane's precondition. Continuing on the default bearer, which is NOT this "
                    + "profile: expect the P-CSCF's N5 QoS auth to fail (412). Set "
                    + CarrierSipPlane.SYSPROP + "=carrier if the default bearer is what you want.", t);
            return null;
        }
    }

    /** Extract the first PCO-signalled P-CSCF address from the IMS network's
     *  LinkProperties via the hidden {@code getPcscfServers()} (reflection). Returns
     *  a bare host/IP string, or null if PCO carried none / the API is absent. */
    @androidx.annotation.Nullable
    private static String firstPcscfFromPco(@androidx.annotation.Nullable
            final android.net.LinkProperties lp) {
        if (lp == null) {
            return null;
        }
        try {
            final Object servers = android.net.LinkProperties.class
                    .getMethod("getPcscfServers").invoke(lp);
            if (servers instanceof java.util.List) {
                for (final Object o : (java.util.List<?>) servers) {
                    if (o instanceof java.net.InetAddress) {
                        return ((java.net.InetAddress) o).getHostAddress();
                    }
                }
            }
        } catch (final Throwable t) {
            Log.d(TAG, "getPcscfServers unavailable: " + t);
        }
        return null;
    }

    /**
     * Effective P-CSCF host for the outbound proxy.
     *
     * <p>On {@link CarrierSipPlane#LAB_IMS_PDN} the PCO-signalled address wins when the PDN provided
     * one — Google Messages IMS discovery, and preferable to an ACS FQDN the UE may not resolve.
     *
     * <p><b>On {@link CarrierSipPlane#CARRIER_DEFAULT_BEARER} the ACS document is authoritative and PCO
     * is not consulted at all</b>. This used to prefer PCO unconditionally, so a
     * PCO address left behind by any other bearer would silently point the registrar at the wrong
     * proxy — a wrong destination that looks exactly like a right one, since both are plausible
     * hostnames and the failure only appears at REGISTER.
     */
    private String pcscfHost() {
        if (sipPlane.pcoPcscfIsAuthoritative() && mPcoPcscf != null && !mPcoPcscf.isEmpty()) {
            return mPcoPcscf;
        }
        return config.pcscfAddress;
    }

    /** Undo {@link #acquireImsNetwork}: restore the default route + release the
     *  IMS network request. */
    private void releaseImsNetwork() {
        try {
            if (cmForIms != null) {
                cmForIms.bindProcessToNetwork(null);
                if (imsNetworkCallback != null) {
                    cmForIms.unregisterNetworkCallback(imsNetworkCallback);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "releaseImsNetwork failed", t);
        } finally {
            imsNetworkCallback = null;
            cmForIms = null;
        }
    }

    /** Grab a free local port from the OS for the given JAIN-SIP transport
     *  ("udp" vs "tcp"/"tls"). Opens a throwaway socket on port 0, reads the
     *  assigned port, closes it, and returns it for JAIN-SIP to bind. Small
     *  TOCTOU window, acceptable for the lab/single-client case. */
    private static int pickFreePort(String transportProto) {
        try {
            if ("udp".equalsIgnoreCase(transportProto)) {
                try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) {
                    return s.getLocalPort();
                }
            }
            try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                return s.getLocalPort();
            }
        } catch (Exception e) {
            // Last-resort fixed high port; collision is unlikely on a test device.
            return 45060;
        }
    }

    private static String pickLocalIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.getAddress().length == 4) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return "0.0.0.0";
    }
}
