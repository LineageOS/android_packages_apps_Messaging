/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import android.util.Log;
import com.android.messaging.rcs.engine.mls.MlsHeaderGate;

import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.Message;
import com.android.messaging.rcs.log.LogMask;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.header.FromHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * Inbound pager-mode SIP {@code MESSAGE} and session CPIM handling on the DR path. Answers 200
 * (RFC 3428 §7), unwraps CPIM, and dispatches by inner type: text, IMDN (mapped to a
 * {@link Message.Status}), isComposing, file-transfer descriptor, or MLS. Sends a delivered
 * receipt when the original asked for {@code positive-delivery}. Called from the transport's
 * {@code processRequest}; it is not a {@link javax.sip.SipListener} itself.
 */
public final class CarrierMessageReceiver {

    private static final String TAG = "CarrierMessageRx";

    public interface IsComposingHandler {
        void onIsComposing(String fromUri, IsComposingNotification notification);
    }

    /** {@code descriptorXml} is the raw {@code application/vnd.gsma.rcs-ft-http+xml} body. */
    public interface FtHttpHandler {
        void onFileInfo(String fromUri, String messageId, byte[] descriptorXml);
    }

    /**
     * Decrypts or applies an MLS-plane CPIM body. Returns the decrypted application payload, or
     * null if the body was control (Welcome, Commit) and fully consumed.
     */
    public interface MlsInboundHandler {
        /**
         * @param envelopeMessageId the envelope's CPIM {@code imdn.Message-ID}, or null if the peer
         *     sent none; RCC.16 §7.5.3.1 requires checking it against the id bound into the AAD.
         *     Our own send path binds the same id it puts on the envelope.
         */
        byte[] onMls(String senderE164, String contentType, byte[] payload,
                String envelopeMessageId);
    }

    private final SipProvider provider;
    private final MessageFactory messageFactory;
    private final CarrierMessageSender sender;
    private Transport.Listener listener;
    private IsComposingHandler isComposingHandler;
    private FtHttpHandler ftHttpHandler;
    private volatile MlsInboundHandler mlsHandler;

    public CarrierMessageReceiver(SipProvider provider, MessageFactory messageFactory,
            CarrierMessageSender sender) {
        this.provider = provider;
        this.messageFactory = messageFactory;
        this.sender = sender;
    }

    public void setListener(Transport.Listener listener) {
        this.listener = listener;
    }

    public void setIsComposingHandler(IsComposingHandler h) {
        this.isComposingHandler = h;
    }

    public void setFtHttpHandler(FtHttpHandler h) {
        this.ftHttpHandler = h;
    }

    public void setMlsInboundHandler(MlsInboundHandler h) {
        this.mlsHandler = h;
    }

    /**
     * For a {@code MESSAGE} request, on the registrar's SIP thread. Sends its own 200 and any
     * automatic receipt; exceptions are logged, never propagated to JAIN-SIP.
     */
    public void handle(RequestEvent event) {
        Request req = event.getRequest();
        try {
            // RFC 3428 §7: 200 first.
            Response resp = messageFactory.createResponse(Response.OK, req);
            ServerTransaction tx = event.getServerTransaction();
            if (tx == null) tx = provider.getNewServerTransaction(req);
            tx.sendResponse(resp);
        } catch (Exception e) {
            Log.w(TAG, "200 OK send failed", e);
            // The message can still be surfaced.
        }

        FromHeader fromHdr = (FromHeader) req.getHeader(FromHeader.NAME);
        String fromUri = fromHdr != null ? fromHdr.getAddress().getURI().toString() : null;
        byte[] body = req.getRawContent();
        if (body == null || body.length == 0) {
            Log.w(TAG, "MESSAGE without body from " + LogMask.number(fromUri));
            return;
        }

        String contentType = headerValue(req, "Content-Type");
        if (contentType != null && contentType.toLowerCase().startsWith("message/cpim")) {
            handleCpimBody(fromUri, body);
        } else if (contentType != null
                && contentType.toLowerCase().startsWith("text/plain")) {
            // Plain text without a CPIM wrapper; the message id may be unknown.
            String text = new String(body, CpimMessage.UTF_8);
            String msgId = headerValue(req, "Message-ID");
            if (listener != null) {
                listener.onIncomingMessage(fromUri, text, msgId);
            }
        } else {
            Log.w(TAG, "ignoring MESSAGE with content-type=" + contentType);
        }
    }

    /**
     * Sends a receipt over the MSRP session the original arrived on. A receipt uses the original's
     * transport (RFC 5438, GSMA RCC.07): a pager-mode receipt for a session message would not
     * arrive.
     */
    public interface InSessionImdnRouter {
        void route(String toUri, ImdnNotification imdn, String reportMessageId);
    }

    /**
     * A CPIM body from an MSRP SEND, handled as the pager path handles one. The caller filters
     * empty SENDs; {@code imdnRouter} sends any automatic receipt in-session.
     */
    public void handleInboundSessionCpim(String fromUri, byte[] cpimBody,
            InSessionImdnRouter imdnRouter) {
        if (cpimBody == null || cpimBody.length == 0) {
            return;
        }
        handleCpimBody(fromUri, cpimBody, imdnRouter);
    }

    private void handleCpimBody(String sipFromUri, byte[] body) {
        handleCpimBody(sipFromUri, body, /*imdnRouter=*/ null);
    }

    private void handleCpimBody(String sipFromUri, byte[] body,
            InSessionImdnRouter imdnRouter) {
        CpimMessage cpim;
        try {
            cpim = CpimMessage.parse(body);
        } catch (CpimParseException e) {
            Log.w(TAG, "CPIM parse failure from " + sipFromUri + ": " + e.getMessage());
            return;
        }

        // RFC 3862 §3: prefer the CPIM From, which survives intermediaries, to the SIP From.
        String fromUri = cpim.getFrom();
        if (fromUri == null) fromUri = sipFromUri;

        String innerCt = cpim.getContentType();
        if (innerCt == null) innerCt = "";
        String lower = innerCt.toLowerCase();
        Log.i(TAG, "inbound CPIM from " + LogMask.number(fromUri) + " innerCt=" + innerCt
                + " isComposingHandler=" + (isComposingHandler != null));

        if (lower.startsWith("text/plain")
                || lower.startsWith(CpimMessage.CT_VND_GOOGLE_RCS_ENCRYPTED)) {
            String text = new String(cpim.getPayload(), CpimMessage.UTF_8);
            String msgId = cpim.getMessageId();
            if (listener != null) listener.onIncomingMessage(fromUri, text, msgId);
            maybeAutoImdn(fromUri, cpim, imdnRouter);
        } else if (lower.startsWith(CpimMessage.CT_IMDN_XML)) {
            handleInboundImdn(fromUri, cpim);
        } else if (lower.startsWith(CpimMessage.CT_ISCOMPOSING_XML)) {
            handleInboundIsComposing(fromUri, cpim);
        } else if (lower.startsWith(CpimMessage.CT_FT_HTTP_XML)) {
            handleInboundFtHttp(fromUri, cpim);
        } else if (CpimMessage.isMlsContentType(innerCt)) {
            handleInboundMls(fromUri, cpim, imdnRouter);
        } else {
            Log.w(TAG, "ignoring CPIM with inner content-type=" + innerCt);
        }
    }

    /**
     * Hands an MLS body to the {@link MlsInboundHandler}; decrypted application payloads leave as
     * bytes through {@link Transport.Listener#onIncomingContent}.
     */
    private void handleInboundMls(String fromUri, CpimMessage cpim,
            InSessionImdnRouter imdnRouter) {
        final MlsInboundHandler h = this.mlsHandler;
        if (h == null) {
            Log.w(TAG, "inbound MLS body but no MLS handler registered (ct=" + cpim.getContentType()
                    + ")");
            return;
        }
        // RCC.16 §7.9: an MLS body without Era-ID and Epoch-Authenticator is dropped before
        // decryption. The shared MlsHeaderGate keeps this check identical on every transport.
        final MlsHeaderGate.Verdict gate = MlsHeaderGate.check(new MlsHeaderGate.Headers() {
            @Override public String get(final String ns, final String name) {
                // This leg's CPIM accessor is already scoped to the MLS namespace.
                if (MlsHeaderGate.HDR_ERA_ID.equals(name)) {
                    return cpim.getCpimHeader(CpimMessage.HDR_MLS_ERA_ID);
                }
                if (MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR.equals(name)) {
                    return cpim.getCpimHeader(CpimMessage.HDR_MLS_EPOCH_AUTH);
                }
                return null;
            }
        });
        if (!gate.accepted()) {
            // Silent by contract: a log line, no receipt, no failure report, no health request.
            Log.w(TAG, gate.logLine());
            return;
        }
        // Null when the peer sent no imdn.Message-ID, which the RCC.16 §7.5.3.1 check treats as
        // nothing asserted rather than a mismatch.
        final byte[] plaintext = h.onMls(telFromUri(fromUri), cpim.getContentType(),
                cpim.getPayload(), cpim.getCpimHeader(CpimMessage.HDR_IMDN_MESSAGE_ID));
        if (plaintext != null && listener != null) {
            // The plaintext is an RCC.16 MIME entity (RccMlsBody): unframe it here, where it was
            // produced, and pass bytes with the real inner type; a String would corrupt anything
            // that is not UTF-8. An unframed payload comes back as text/plain.
            final com.android.messaging.rcs.engine.mls.RccMlsBody.Parsed parsed =
                    com.android.messaging.rcs.engine.mls.RccMlsBody.parse(plaintext);
            // Tagged with the MLS scheme so the message shows as end-to-end encrypted.
            listener.onIncomingContent(fromUri, parsed.body, parsed.contentType,
                    cpim.getMessageId(),
                    com.android.messaging.rcs.e2ee.RcsE2eeScheme.MLS);
            maybeAutoImdn(fromUri, cpim, imdnRouter);
        }
    }

    /** The bare {@code +E164} of a {@code tel:} or {@code sip:+E164@domain} URI. */
    private static String telFromUri(String uri) {
        if (uri == null) return null;
        String u = uri.trim();
        int lt = u.indexOf('<');
        if (lt >= 0) { int gt = u.indexOf('>', lt); u = gt > lt ? u.substring(lt + 1, gt)
                : u.substring(lt + 1); }
        if (u.startsWith("tel:")) u = u.substring(4);
        else if (u.startsWith("sip:")) { u = u.substring(4); int at =
                u.indexOf('@'); if (at > 0) u = u.substring(0, at); }
        int semi = u.indexOf(';');
        if (semi >= 0) u = u.substring(0, semi);
        return u.trim();
    }

    private void handleInboundImdn(String fromUri, CpimMessage cpim) {
        try {
            ImdnNotification imdn = ImdnNotification.parse(cpim.getPayload());
            String originalMessageId = imdn.getMessageId();
            Message.Status status;
            switch (imdn.getStatus()) {
                case DELIVERED: status = Message.Status.DELIVERED; break;
                case DISPLAYED: status = Message.Status.DISPLAYED; break;
                case FAILED:
                case ERROR:
                case FORBIDDEN: status = Message.Status.FAILED;    break;
                case PROCESSED:
                default:
                    Log.i(TAG, "ignoring informational IMDN: " + imdn);
                    return;
            }
            if (listener != null) {
                listener.onMessageStatus(originalMessageId, status,
                        imdn.getStatus().token);
            }
        } catch (ImdnParseException e) {
            Log.w(TAG, "IMDN parse failure from " + LogMask.number(fromUri) + ": "
                    + e.getMessage());
        }
    }

    private void handleInboundFtHttp(String fromUri, CpimMessage cpim) {
        final byte[] xml = cpim.getPayload();
        Log.i(TAG, "inbound FT-HTTP descriptor from " + LogMask.number(fromUri) + " ("
                + (xml == null ? 0 : xml.length) + "B) handler="
                + (ftHttpHandler != null));
        if (ftHttpHandler != null) {
            ftHttpHandler.onFileInfo(fromUri, cpim.getMessageId(), xml);
        }
        // File transfer gets a delivered receipt like any message, in pager mode.
        maybeAutoImdn(fromUri, cpim, /*imdnRouter=*/ null);
    }

    private void handleInboundIsComposing(String fromUri, CpimMessage cpim) {
        if (isComposingHandler == null) return;
        try {
            IsComposingNotification n = IsComposingNotification.parse(cpim.getPayload());
            isComposingHandler.onIsComposing(fromUri, n);
        } catch (ImdnParseException e) {
            Log.w(TAG, "iscomposing parse failure from " + LogMask.number(fromUri) + ": "
                    + e.getMessage());
        }
    }

    private void maybeAutoImdn(String fromUri, CpimMessage cpim,
            InSessionImdnRouter imdnRouter) {
        String[] dispos = cpim.getDispositionNotifications();
        if (dispos.length == 0) return;
        if (imdnRouter == null && sender == null) return;
        boolean wantsDelivered = false;
        for (String token : dispos) {
            if (CpimMessage.DISPO_POSITIVE_DELIVERY.equalsIgnoreCase(token)) {
                wantsDelivered = true;
                break;
            }
        }
        if (!wantsDelivered) return;
        if (cpim.getMessageId() == null || fromUri == null) return;
        try {
            ImdnNotification delivered = ImdnNotification.newDelivered(
                    cpim.getMessageId(), CpimDateTime.now());
            String reportId = "imdn-" + java.util.UUID.randomUUID();
            if (imdnRouter != null) {
                imdnRouter.route(fromUri, delivered, reportId);
            } else {
                sender.sendImdnReport(fromUri, delivered, reportId);
            }
        } catch (Exception e) {
            Log.w(TAG, "auto-IMDN send failed", e);
        }
    }

    private static String headerValue(Request req, String name) {
        javax.sip.header.Header h = req.getHeader(name);
        if (h == null) return null;
        String s = h.toString();
        // nist-sip renders a header as "Name: value\r\n".
        int colon = s.indexOf(':');
        if (colon < 0) return s.trim();
        return s.substring(colon + 1).trim();
    }
}
