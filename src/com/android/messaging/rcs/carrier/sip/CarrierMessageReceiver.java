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
package com.android.messaging.rcs.carrier.sip;

import android.util.Log;

import com.android.messaging.rcs.carrier.Transport;
import com.android.messaging.rcs.carrier.Message;

import javax.sip.RequestEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipProvider;
import javax.sip.header.FromHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * Inbound SIP MESSAGE handler for the carrier-RCS pager-mode path. Wired in
 * by the carrier transport on top of an active
 * {@link com.android.messaging.rcs.carrier.CarrierSipRegistrar}: any
 * {@link RequestEvent} whose method is {@code MESSAGE} is fed through
 * {@link #handle(RequestEvent)}, which:
 *
 * <ol>
 *   <li>Sends a 200 OK on the underlying transaction (RFC 3428 §7).</li>
 *   <li>Unwraps the CPIM body if Content-Type is {@code message/cpim}.</li>
 *   <li>Dispatches based on the inner Content-Type:
 *     <ul>
 *       <li>{@code text/plain*}: invoke
 *           {@link Transport.Listener#onIncomingMessage}</li>
 *       <li>{@code message/imdn+xml}: parse the IMDN report and invoke
 *           {@link Transport.Listener#onMessageStatus} with the mapped
 *           {@link Message.Status} (DELIVERED / DISPLAYED / FAILED).</li>
 *       <li>{@code application/im-iscomposing+xml}: parse and pass to the
 *           {@link IsComposingHandler} if set (NO-OP by default — typing
 *           indicators do not have a Transport.Listener slot today).</li>
 *     </ul>
 *   </li>
 *   <li>If the original message asked for {@code positive-delivery} in
 *       its {@code imdn.Disposition-Notification} header, auto-emit a
 *       delivered IMDN report back to the sender via the supplied
 *       {@link CarrierMessageSender}.</li>
 * </ol>
 *
 * <p>This class does NOT register itself as a {@link javax.sip.SipListener}
 * — it's a handler the surrounding transport calls from its
 * {@code processRequest} dispatch. That keeps the JAIN-SIP listener wiring
 * localized to one place per package (CarrierSipRegistrar at registration
 * time, the future CarrierMessageTransport for messaging).
 */
public final class CarrierMessageReceiver {

    private static final String TAG = "CarrierMessageRx";

    /** Optional surface for incoming typing indicators. */
    public interface IsComposingHandler {
        void onIsComposing(String fromUri, IsComposingNotification notification);
    }

    /** Optional surface for an incoming FT-HTTP file-info descriptor
     *  (application/vnd.gsma.rcs-ft-http+xml). {@code descriptorXml} is the raw
     *  rcs-ft-http+xml body; the handler parses the {@code <data url>} and fetches. */
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
     * Entry point from the surrounding transport's
     * {@code SipListener#processRequest} when method == MESSAGE.
     *
     * <p>This method does its own I/O for the 200 OK and (optionally) the
     * auto-IMDN reply. Caller must run it on a SIP-stack-compatible thread
     * (the registrar's sipThread is the typical choice). Exceptions are
     * caught and logged; we do not propagate to the JAIN-SIP layer.
     */
    public void handle(RequestEvent event) {
        Request req = event.getRequest();
        try {
            // 1. 200 OK first per RFC 3428 §7.
            Response resp = messageFactory.createResponse(Response.OK, req);
            ServerTransaction tx = event.getServerTransaction();
            if (tx == null) tx = provider.getNewServerTransaction(req);
            tx.sendResponse(resp);
        } catch (Exception e) {
            Log.w(TAG, "200 OK send failed", e);
            // continue — we may still surface the message even if the ACK failed
        }

        FromHeader fromHdr = (FromHeader) req.getHeader(FromHeader.NAME);
        String fromUri = fromHdr != null ? fromHdr.getAddress().getURI().toString() : null;
        byte[] body = req.getRawContent();
        if (body == null || body.length == 0) {
            Log.w(TAG, "MESSAGE without body from " + fromUri);
            return;
        }

        String contentType = headerValue(req, "Content-Type");
        if (contentType != null && contentType.toLowerCase().startsWith("message/cpim")) {
            handleCpimBody(fromUri, body);
        } else if (contentType != null
                && contentType.toLowerCase().startsWith("text/plain")) {
            // Some peers send plain text inside SIP MESSAGE without the CPIM
            // wrapper. Surface as-is — caller will treat the message id as
            // unknown.
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
     * Routes a disposition notification (delivered/displayed IMDN) back over
     * the SAME MSRP session the original message arrived on, as an in-session
     * {@code message/imdn+xml} CPIM SEND. Per RFC 5438 / GSMA RCC.07 a
     * disposition notification uses the transport of the original message:
     * session-mode message => session-mode (MSRP) IMDN, NOT a standalone pager
     * SIP MESSAGE (which routes terminating-toward-the-sender and times out).
     */
    public interface InSessionImdnRouter {
        void route(String toUri, ImdnNotification imdn, String reportMessageId);
    }

    /**
     * Entry point for a {@code message/cpim} body received over an MSRP chat
     * session (RFC 4975 SEND) rather than a SIP MESSAGE. The MSRP-session
     * receive path hands us the raw CPIM bytes; parse + dispatch + auto-IMDN
     * identically to the SIP-MESSAGE path (text/plain, imdn+xml, isComposing,
     * ft-http all handled). Empty keepalive/bind SENDs must be filtered by the
     * caller. {@code imdnRouter} (non-null for a session context) sends any
     * auto-IMDN back in-session instead of via a pager MESSAGE.
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

        // Prefer the CPIM-level From URI over the SIP From (RFC 3862 §3
        // paragraph 2 — CPIM headers survive store-and-forward intermediaries
        // whereas SIP From may be the SBC's spoofed identity).
        String fromUri = cpim.getFrom();
        if (fromUri == null) fromUri = sipFromUri;

        String innerCt = cpim.getContentType();
        if (innerCt == null) innerCt = "";
        String lower = innerCt.toLowerCase();
        Log.i(TAG, "inbound CPIM from " + fromUri + " innerCt=" + innerCt
                + " isComposingHandler=" + (isComposingHandler != null));

        if (lower.startsWith("text/plain")
                || lower.startsWith(CpimMessage.CT_VND_GOOGLE_RCS_ENCRYPTED)) {
            String text = new String(cpim.getPayload(), CpimMessage.UTF_8);
            String msgId = cpim.getMessageId();
            if (listener != null) listener.onIncomingMessage(fromUri, text, msgId);
            // Auto-emit delivered IMDN if requested (in-session over MSRP when
            // imdnRouter is set, else pager MESSAGE).
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
            Log.w(TAG, "IMDN parse failure from " + fromUri + ": " + e.getMessage());
        }
    }

    private void handleInboundFtHttp(String fromUri, CpimMessage cpim) {
        final byte[] xml = cpim.getPayload();
        Log.i(TAG, "inbound FT-HTTP descriptor from " + fromUri + " ("
                + (xml == null ? 0 : xml.length) + "B) handler="
                + (ftHttpHandler != null));
        if (ftHttpHandler != null) {
            ftHttpHandler.onFileInfo(fromUri, cpim.getMessageId(), xml);
        }
        // FT wants a delivered IMDN like a normal message (pager MESSAGE path).
        maybeAutoImdn(fromUri, cpim, /*imdnRouter=*/ null);
    }

    private void handleInboundIsComposing(String fromUri, CpimMessage cpim) {
        if (isComposingHandler == null) return;
        try {
            IsComposingNotification n = IsComposingNotification.parse(cpim.getPayload());
            isComposingHandler.onIsComposing(fromUri, n);
        } catch (ImdnParseException e) {
            Log.w(TAG, "iscomposing parse failure from " + fromUri + ": " + e.getMessage());
        }
    }

    private void maybeAutoImdn(String fromUri, CpimMessage cpim,
            InSessionImdnRouter imdnRouter) {
        String[] dispos = cpim.getDispositionNotifications();
        // A session-mode IMDN goes over MSRP (imdnRouter); a pager IMDN needs
        // the SIP-MESSAGE sender. If neither channel is available, bail.
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
            // Fresh message id for the IMDN report frame itself.
            String reportId = "imdn-" + java.util.UUID.randomUUID();
            if (imdnRouter != null) {
                // In-session (RFC 5438 / RCC.07): the notification rides the
                // same MSRP session the original message arrived on.
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
        // Header.toString() returns "Name: value\r\n" in nist-sip.
        int colon = s.indexOf(':');
        if (colon < 0) return s.trim();
        return s.substring(colon + 1).trim();
    }
}
