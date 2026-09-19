/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.util.Log;

import com.android.messaging.rcs.RcsDebug;
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
 * The DR SIP registration state machine (JAIN-SIP) against the P-CSCF in an
 * {@link RcsImsConfig}. A 401 or 407 is answered with Digest credentials up to three attempts;
 * any other final failure, a transport error or a REGISTER timeout is {@code FAILED}, which holds
 * until {@link #stop()} and {@link #start()}. All SIP calls run on {@code sipThread}. See
 * docs/rcs/carrier-transport.md.
 */
public final class CarrierSipRegistrar implements SipListener {

    private static final String TAG = "CarrierSipRegistrar";

    private static final String USER_AGENT =
            "IM-client/OMA1.0 lineageos-messaging/1.0";

    /**
     * Contact feature tags for 1:1 chat. RFC 3840: several ICSIs go in one {@code +g.3gpp.icsi-ref}
     * as a quoted comma-separated list; repeated parameters would let a server keep only the last.
     */
    static final String[] FEATURE_TAGS_1_TO_1 = {
            "+g.gsma.rcs.msgrevoke",
            "+g.3gpp.icsi-ref=\""
                    + "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.session,"
                    + "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg,"
                    + "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"",
    };

    /**
     * Aliases for {@link CarrierSipRegistrarState}, which is top-level so host tests can use it.
     */
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
     * Receives every inbound request (pager messages, session invitations), on {@code sipThread}.
     */
    public interface RequestForwarder {
        void onRequest(RequestEvent event);
    }

    /**
     * Receives responses to anything but REGISTER, such as the 200 to a session invitation, so the
     * transport's session manager can complete the dialog.
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

    // The IMS bearer: over the internet bearer the P-CSCF's QoS authorisation for the registration
    // fails (412). Held so doStop can unbind and release it.
    private android.net.ConnectivityManager cmForIms;
    private android.net.ConnectivityManager.NetworkCallback imsNetworkCallback;
    // The P-CSCF address the IMS PDN signalled in PCO, or null. See pcscfHost().
    private volatile String mPcoPcscf;

    /**
     * Read once, so the network binding and the proxy address cannot disagree mid-session. A user
     * build always takes the default plane.
     */
    private final CarrierSipPlane sipPlane = CarrierSipPlane.fromString(RcsDebug.isDebugBuild()
            ? SystemProperties.get(CarrierSipPlane.SYSPROP, "ims") : null);

    private final AtomicLong cseq = new AtomicLong(1);
    private CallIdHeader registerCallId;
    private int registerAttempts;
    // The last Digest challenge, reused to authenticate the de-REGISTER: an unauthenticated one is
    // challenged and the binding survives. nc increments per reuse.
    private javax.sip.header.WWWAuthenticateHeader cachedChallenge;
    // Whether that challenge was a proxy's 407, which is answered with Proxy-Authorization.
    private boolean cachedChallengeIsProxy;
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

    /** On {@code sipThread}. */
    public void setRequestForwarder(RequestForwarder f) { this.requestForwarder = f; }
    public void setResponseForwarder(ResponseForwarder f) { this.responseForwarder = f; }

    public CarrierSipRegistrarState getState() { return state; }

    public RcsImsConfig getConfig() { return config; }

    // Valid once doStart() has succeeded, null before; for the pager sender and receiver.

    public SipProvider getSipProvider()       { return sipProvider; }
    public MessageFactory getMessageFactory() { return messageFactory; }
    public HeaderFactory getHeaderFactory()   { return headerFactory; }
    public javax.sip.address.AddressFactory getAddressFactory() { return addressFactory; }
    public String getLocalIp()                { return localIp; }
    /** The P-CSCF the REGISTER used, so other requests take the same next hop. */
    public String getPcscfHost()              { return pcscfHost(); }
    public int getLocalPort()                 { return localPort; }
    public String getTransportProto()         { return transportProto; }

    public void start() { sipHandler.post(this::doStart); }

    public void stop() { sipHandler.post(this::doStop); }

    // sipThread only.

    private void doStart() {
        if (sipStack != null) return;
        try {
            boolean onWifi = isOnWifi(appContext);
            // RcsImsConfig.psSipTransport, TCP by default; see docs/rcs/carrier-transport.md.
            transportProto = config.jainSipTransport(onWifi);
            int pcscfPort = config.effectivePcscfPort(onWifi);

            String imsIp = acquireImsNetwork();
            localIp = imsIp != null ? imsIp : pickLocalIp();

            SipFactory sipFactory = SipFactory.getInstance();
            Properties props = new Properties();
            props.setProperty("javax.sip.STACK_NAME",
                    "rcs-carrier-" + System.nanoTime());
            // Larger UDP buffers and no message-size cap. A request over the IMS link MTU still
            // cannot arrive over UDP; TCP is the fix (RFC 3261 §18.1.1).
            props.setProperty("gov.nist.javax.sip.RECEIVE_UDP_BUFFER_SIZE", "65536");
            props.setProperty("gov.nist.javax.sip.SEND_UDP_BUFFER_SIZE", "65536");
            props.setProperty("gov.nist.javax.sip.MAX_MESSAGE_SIZE", "0");

            sipStack = sipFactory.createSipStack(props);
            messageFactory = sipFactory.createMessageFactory();
            headerFactory = sipFactory.createHeaderFactory();
            addressFactory = sipFactory.createAddressFactory();

            // This JAIN-SIP rejects port 0, so pick a free port. On a debug build,
            // debug.rcs.dr.localport pins one so reinstalls keep the same Contact rather than
            // leaving stale bindings.
            final int fixedPort = RcsDebug.isDebugBuild()
                    ? SystemProperties.getInt("debug.rcs.dr.localport", 0) : 0;
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
            Request req = buildRegister(null, false);
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

    /**
     * REGISTER to {@code sip:<domain>}, From and To the public identity, a raw Contact with
     * {@link #FEATURE_TAGS_1_TO_1}, Route to the P-CSCF with {@code lr}, {@code Supported: path},
     * and Expires 600000 unless {@code debug.rcs.dr.expires} says otherwise. With
     * {@code challenge}, Digest credentials, in Proxy-Authorization when {@code proxy} says it
     * was a 407; without, the empty Authorization described below.
     */
    Request buildRegister(WWWAuthenticateHeader challenge, boolean proxy)
            throws ParseException, SipException {
        if (config.domain == null || config.pcscfAddress == null
                || config.userName == null || config.publicIdentity == null) {
            throw new SipException("Incomplete RcsImsConfig: " + config);
        }

        SipURI requestUri = addressFactory.createSipURI(null, config.domain);

        // The public identity may be a tel: URI.
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

        // Contact as a raw header string: JAIN-SIP's setParameter writes a valueless tag as "name="
        // and leaves a URN unquoted, both of which a SIP parser rejects.
        StringBuilder cb = new StringBuilder();
        cb.append("<sip:").append(config.userName).append('@').append(localIp)
                .append(':').append(localPort)
                .append(";transport=").append(transportProto).append('>');
        for (String tag : FEATURE_TAGS_1_TO_1) {
            cb.append(';').append(tag);
        }
        int expiresSec = RcsDebug.isDebugBuild()
                ? SystemProperties.getInt("debug.rcs.dr.expires", 600000) : 600000;
        cb.append(";expires=").append(expiresSec);
        ContactHeader contact =
                (ContactHeader) headerFactory.createHeader("Contact", cb.toString());

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
            // A provisioned HA1 is used directly, not hashed again.
            String resp = config.authDigestIsHa1
                    ? SipDigestAuth.responseWithHa1(pw,
                            Request.REGISTER, digestUri, nonce, qop, nc, cnonce)
                    : SipDigestAuth.response(user, realm, pw,
                            Request.REGISTER, digestUri, nonce, qop, nc, cnonce);

            AuthorizationHeader auth = credentialsHeader(proxy);
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
        } else if (!(RcsDebug.isDebugBuild()
                && SystemProperties.getBoolean("debug.rcs.dr.noauth", false))) {
            // An initial REGISTER carries an empty Digest Authorization so the S-CSCF can identify
            // the IMPI and challenge with 401 rather than refuse with 403. On a debug build,
            // debug.rcs.dr.noauth omits it.
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

    /**
     * The header that answers a challenge: Proxy-Authorization for a proxy's 407, Authorization
     * for a 401 (RFC 3261 §22.3). A proxy does not read Authorization, so a 407 answered with it
     * is challenged again.
     */
    private AuthorizationHeader credentialsHeader(boolean proxy) throws ParseException {
        return proxy ? headerFactory.createProxyAuthorizationHeader("Digest")
                : headerFactory.createAuthorizationHeader("Digest");
    }

    Request buildDeregister() throws ParseException, SipException {
        // Expires: 0, authenticated with the cached challenge when there is one.
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

        // Routed through the P-CSCF like the REGISTER; the Request-URI alone cannot be routed.
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

        // Reuse the cached nonce with the next nc.
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
                AuthorizationHeader auth = credentialsHeader(cachedChallengeIsProxy);
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

    @Override
    public void processRequest(RequestEvent event) {
        // Hand inbound requests to the forwarder on sipThread, in order.
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
        // Only a REGISTER timeout loses the registration; a timed-out pager message must not.
        String method = null;
        try {
            if (!event.isServerTransaction() && event.getClientTransaction() != null) {
                method = event.getClientTransaction().getRequest().getMethod();
            }
        } catch (final Throwable t) {
            // unknown: not REGISTER
        }
        Log.w(TAG, "transaction timeout, method=" + method);
        if (Request.REGISTER.equals(method)) {
            sipHandler.post(() -> updateState(State.FAILED, "REGISTER timeout"));
        }
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
            // Other responses belong to the transport's session manager.
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

        if (status < 200) return;

        if (status == Response.UNAUTHORIZED
                || status == Response.PROXY_AUTHENTICATION_REQUIRED) {
            if (registerAttempts >= 3) {
                updateState(State.FAILED, "auth retries exhausted");
                return;
            }
            // A 401 is answered from WWW-Authenticate, a 407 from Proxy-Authenticate, each in its
            // own credentials header.
            final boolean proxy = SipDigestAuth.isProxyChallenge(status);
            WWWAuthenticateHeader challenge = (WWWAuthenticateHeader) resp.getHeader(
                    SipDigestAuth.challengeHeaderName(status));
            if (challenge == null) {
                updateState(State.FAILED, status + " without "
                        + SipDigestAuth.challengeHeaderName(status));
                return;
            }
            this.cachedChallenge = challenge;  // kept for the de-REGISTER
            this.cachedChallengeIsProxy = proxy;
            try {
                Request req = buildRegister(challenge, proxy);
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

    /**
     * Requests the cellular IMS network, binds the process to it so JAIN-SIP's sockets use it, and
     * returns its IPv4 address; null if the plane does not use it, {@code debug.rcs.dr.imsapn} is
     * false, or it cannot be had within 10 s. Needs {@code CONNECTIVITY_USE_RESTRICTED_NETWORKS}.
     */
    private String acquireImsNetwork() {
        // The default bearer as a choice: the caller cannot tell it from a failure by the return
        // value, so the log says which.
        if (!sipPlane.usesImsPdn()) {
            Log.i(TAG, "sipPlane=" + sipPlane + " — NOT binding the IMS PDN; registering over the "
                    + "default bearer BY DESIGN, with the P-CSCF from the ACS document. This is a "
                    + "chosen plane, not a fallback.");
            return null;
        }
        if (RcsDebug.isDebugBuild()
                && !SystemProperties.getBoolean("debug.rcs.dr.imsapn", true)) {
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
                    // IMS is restricted: drop the implicit NOT_RESTRICTED.
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
            cmForIms.bindProcessToNetwork(ims);
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
            // PCO-signalled P-CSCF, through the hidden LinkProperties.getPcscfServers()
            // (reflection).
            mPcoPcscf = firstPcscfFromPco(lp);
            Log.i(TAG, "bound to IMS APN network " + ims + " localIp=" + ip
                    + " iface=" + (lp != null ? lp.getInterfaceName() : "?")
                    + " pcoPcscf=" + mPcoPcscf);
            return ip;
        } catch (Throwable t) {
            // A failed precondition of this plane, not a neutral fallback; clear any PCO address so
            // the next attempt cannot take it as authoritative.
            mPcoPcscf = null;
            Log.w(TAG, "acquireImsNetwork FAILED on sipPlane=" + sipPlane
                    + " — the IMS PDN is this "
                    + "plane's precondition. Continuing on the default bearer, which is NOT this "
                    + "profile: expect the P-CSCF's N5 QoS auth to fail (412). Set "
                    + CarrierSipPlane.SYSPROP + "=carrier (debug builds) if the default bearer is "
                    + "what you want.",
                    t);
            return null;
        }
    }

    /** The first P-CSCF address in PCO, or null if none or the hidden API is missing. */
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
     * On {@link CarrierSipPlane#IMS_PDN}, the PCO address when the PDN gave one, since the
     * configuration may name a host the device cannot resolve. On
     * {@link CarrierSipPlane#CARRIER_DEFAULT_BEARER} the configuration only: a PCO address from
     * another bearer would point at the wrong proxy.
     */
    private String pcscfHost() {
        if (sipPlane.pcoPcscfIsAuthoritative() && mPcoPcscf != null && !mPcoPcscf.isEmpty()) {
            return mPcoPcscf;
        }
        return config.pcscfAddress;
    }

    /** Unbinds the process and releases the IMS network request. */
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

    /** A free port for the transport, from a throwaway socket; racy, but acceptable here. */
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
            // a fixed high port as a last resort
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
        }
        return "0.0.0.0";
    }
}
