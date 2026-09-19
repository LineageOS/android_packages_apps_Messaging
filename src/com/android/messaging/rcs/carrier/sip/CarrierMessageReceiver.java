/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier.sip;

import android.util.Log;

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
 * {@link Message.Status}), isComposing, or file-transfer descriptor. Sends a delivered
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

    private final SipProvider provider;
    private final MessageFactory messageFactory;
    private final CarrierMessageSender sender;
    private Transport.Listener listener;
    private IsComposingHandler isComposingHandler;
    private FtHttpHandler ftHttpHandler;

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
        } else {
            Log.w(TAG, "ignoring CPIM with inner content-type=" + innerCt);
        }
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
