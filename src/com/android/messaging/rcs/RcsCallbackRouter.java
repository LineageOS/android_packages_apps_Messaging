/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.lineageos.rcs.provider.IRcsProviderCallback;
import org.lineageos.rcs.provider.RcsIncomingBotMessage;
import org.lineageos.rcs.provider.RcsIncomingFile;
import org.lineageos.rcs.provider.RcsIncomingMessage;
import org.lineageos.rcs.provider.RcsProviderCaps;
import org.lineageos.rcs.provider.RcsTosPrompt;

import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.action.MarkRcsFileUnavailableAction;
import com.android.messaging.datamodel.action.ReceiveRcsBotMessageAction;
import com.android.messaging.datamodel.action.ReceiveRcsGroupEventAction;
import com.android.messaging.datamodel.action.ReceiveRcsMediaAction;
import com.android.messaging.datamodel.action.ReceiveRcsMessageAction;
import com.android.messaging.datamodel.action.UpdateRcsMessageStatusAction;
import com.android.messaging.datamodel.action.UpdateRcsReactionAction;
import com.android.messaging.Factory;
import com.android.messaging.rcs.GroupIconApplier;
import com.android.messaging.rcs.e2ee.E2eeObservation;
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.rcs.e2ee.MlsInboundRedelivery;
import com.android.messaging.rcs.e2ee.MlsSubjectApplier;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.RccMlsBody;
import com.android.messaging.rcs.engine.mls.RccContentDisposition;
import com.android.messaging.rcs.engine.mls.RccCpimReaction;
import com.android.messaging.rcs.engine.mls.RccGroupMetadataKeys;
import com.android.messaging.rcs.engine.mls.RccFileInfo;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;
import com.android.messaging.rcs.engine.mls.MlsInboundRefusal;
import com.android.messaging.rcs.engine.mls.MlsHeaderGate;
import com.android.messaging.rcs.engine.mls.MlsTrace;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes;

/**
 * The single inbound sink for every RCS transport: an {@link IRcsProviderCallback} that hands each
 * callback to a DataModel action, so database writes run on the action-service thread. The
 * provider transports pass it to {@code attach}; the in-process carrier transport calls it
 * directly. It also owns the shared {@link RouteSelector}, the cached E2EE snapshot and the typing
 * listener fan-out. See docs/rcs/architecture.md.
 *
 * <p>Callbacks arrive on a binder thread (a provider) or the carrier events handler (in process).
 * Each hands off at once to an action, a worker thread or the main thread, and the router never
 * calls back into a transport while holding a lock.
 */
public final class RcsCallbackRouter extends IRcsProviderCallback.Stub
        implements MlsProviderTransport.PendingAppReplay {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static volatile RcsCallbackRouter sInstance;

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final RouteSelector mRouteSelector = new RouteSelector();

    /**
     * Last pushed E2EE state; {@link ProviderTransport#getE2eeInfo} prefers a fresh binder call and
     * falls back to this.
     */
    @Nullable private volatile org.lineageos.rcs.provider.RcsE2eeInfo mE2eeInfo;

    // In-process typing fan-out, posted to listeners on the main thread; the ACTION_TYPING
    // broadcast is still sent for other listeners.
    private final java.util.Set<ProviderTransport.TypingListener> mTypingListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    // Group-typing fan-out, as mTypingListeners.
    private final java.util.Set<ProviderTransport.GroupTypingListener> mGroupTypingListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    private RcsCallbackRouter(final Context context) {
        mAppContext = context.getApplicationContext();
        // Install the parked-message replay hook as part of becoming the sink: a parked message
        // that reaches its moment with no hook installed is lost.
        MlsProviderTransport.setPendingAppReplay(this);
    }

    /** Process-wide singleton — the one sink every transport feeds. */
    public static RcsCallbackRouter getInstance(final Context context) {
        if (sInstance == null) {
            synchronized (RcsCallbackRouter.class) {
                if (sInstance == null) {
                    sInstance = new RcsCallbackRouter(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * How long one peer report suppresses an identical repeat. See {@link #claimNegativeDelivery}.
     */
    private static final long NEGATIVE_DELIVERY_DEDUPE_MS = 30_000L;

    /** (reporter|reported id|reason) -> when we last acted on it. */
    private final java.util.LinkedHashMap<String, Long> mNegativeDeliverySeen =
            new java.util.LinkedHashMap<String, Long>(64, 0.75f, false) {
                @Override protected boolean removeEldestEntry(
                        final java.util.Map.Entry<String, Long> eldest) {
                    return size() > 512;
                }
            };

    /**
     * Claim one peer failure report, so a duplicate dispatch runs the remedy once. Time-boxed: the
     * same report much later means the peer regressed and deserves a fresh remedy.
     */
    private boolean claimNegativeDelivery(final String from, final String mid, final int reason) {
        final String key = from + "|" + mid + "|" + reason;
        final long now = android.os.SystemClock.elapsedRealtime();
        synchronized (mNegativeDeliverySeen) {
            final Long prev = mNegativeDeliverySeen.get(key);
            if (prev != null && now - prev < NEGATIVE_DELIVERY_DEDUPE_MS) return false;
            mNegativeDeliverySeen.put(key, now);
            return true;
        }
    }

    /**
     * Message ids claimed for insertion. A duplicate dispatch arrives milliseconds after the first,
     * so a small LRU is enough and the set cannot grow without bound.
     */
    private final java.util.Set<String> mInsertClaims =
            java.util.Collections.newSetFromMap(
                    new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                        @Override protected boolean removeEldestEntry(
                                final java.util.Map.Entry<String, Boolean> eldest) {
                            return size() > 512;
                        }
                    });

    /**
     * Claim {@code mid} for insertion; false if another dispatch already holds it. Atomic, because
     * the database existence check alone lets two concurrent dispatches both insert. Never
     * released: a claim means "handled", and releasing it would reopen the window for a redelivery.
     */
    private boolean claimInboundInsert(final String mid) {
        if (mid == null || mid.isEmpty()) return true;   // nothing to key on; do not block delivery
        synchronized (mInsertClaims) {
            return mInsertClaims.add(mid);
        }
    }

    /** Whether {@code mid} is claimed for insertion; a read, the claim is not taken. */
    private boolean insertClaimed(final String mid) {
        synchronized (mInsertClaims) {
            return mInsertClaims.contains(mid);
        }
    }

    /** Whether a stored message carries {@code rcs_message_id} {@code mid}. */
    private static boolean storedLocally(final String mid) {
        final DatabaseWrapper db = DataModel.get().getDatabase();
        return db != null && RcsMessageStore.findLocalIdByRcsMessageId(db, mid) != null;
    }

    /** Cached accessor; only valid after getInstance() has run once. */
    @Nullable
    public static RcsCallbackRouter peek() {
        return sInstance;
    }

    /** Shared per-sub route/selection + peer-capability cache. */
    public RouteSelector getRouteSelector() {
        return mRouteSelector;
    }

    /** Clear cached reg/prov state when a provider goes away. */
    void onProviderGone() {
        mRouteSelector.onProviderGone();
    }

    // ---- E2EE state cache ----

    /** Update the cached E2EE snapshot (set from a fresh binder read). */
    void cacheE2eeInfo(@Nullable final org.lineageos.rcs.provider.RcsE2eeInfo info) {
        mE2eeInfo = info;
    }

    /** Last cached E2EE snapshot, or null until the first push or read. */
    @Nullable
    org.lineageos.rcs.provider.RcsE2eeInfo peekE2eeInfo() {
        return mE2eeInfo;
    }

    // ---- typing listeners (ProviderTransport delegates here) ----

    /** Register a typing listener. Idempotent. */
    void registerTypingListener(final ProviderTransport.TypingListener listener) {
        if (listener != null) {
            mTypingListeners.add(listener);
        }
    }

    /** Unregister a typing listener. Safe even if never registered. */
    void unregisterTypingListener(final ProviderTransport.TypingListener listener) {
        if (listener != null) {
            mTypingListeners.remove(listener);
        }
    }

    /** Register a group-typing listener. Idempotent. */
    void registerGroupTypingListener(final ProviderTransport.GroupTypingListener listener) {
        if (listener != null) {
            mGroupTypingListeners.add(listener);
        }
    }

    /** Unregister a group-typing listener. Safe even if never registered. */
    void unregisterGroupTypingListener(final ProviderTransport.GroupTypingListener listener) {
        if (listener != null) {
            mGroupTypingListeners.remove(listener);
        }
    }

    /** Fan an inbound typing event out to registered listeners on the main thread. */
    private void dispatchTyping(final int subId, final String fromUri, final boolean active) {
        // The typing toggle governs showing as well as sending.
        if (!RcsFeatureSettings.isTypingIndicatorsEnabled()) {
            return;
        }
        if (mTypingListeners.isEmpty()) {
            return;
        }
        mHandler.post(() -> {
            for (final ProviderTransport.TypingListener l : mTypingListeners) {
                try {
                    l.onTyping(subId, fromUri, active);
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "TypingListener.onTyping threw", t);
                }
            }
        });
    }

    /** Fan an inbound group-typing event out to listeners on the main thread. */
    private void dispatchGroupTyping(final int subId, final String groupId,
            final String fromUri, final boolean active) {
        // The typing toggle governs showing as well as sending.
        if (!RcsFeatureSettings.isTypingIndicatorsEnabled()) {
            return;
        }
        if (mGroupTypingListeners.isEmpty()) {
            return;
        }
        mHandler.post(() -> {
            for (final ProviderTransport.GroupTypingListener l : mGroupTypingListeners) {
                try {
                    l.onGroupTyping(subId, groupId, fromUri, active);
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "GroupTypingListener.onGroupTyping threw", t);
                }
            }
        });
    }

    // ---- inbound-media FD ingest helpers ----

    /**
     * Copy a file descriptor handed over by the provider into {@link MediaScratchFileProvider}
     * storage and return a durable content URI string, or null on failure. Consumes and closes the
     * descriptor. Runs on the binder thread; a blob larger than {@link #MAX_INBOUND_FILE_BYTES}
     * is dropped.
     */
    private static String ingestFdToScratch(final Context ctx,
            final ParcelFileDescriptor pfd, final String mime) {
        final android.net.Uri scratch =
                MediaScratchFileProvider.buildMediaScratchSpaceUri(extensionFor(mime));
        boolean oversize = false;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                OutputStream out = ctx.getContentResolver().openOutputStream(scratch)) {
            if (out == null) {
                LogUtil.w(TAG, "onIncomingMedia: could not open scratch output");
                return null;
            }
            final byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_INBOUND_FILE_BYTES) {
                    oversize = true;
                    break;
                }
                out.write(buf, 0, n);
            }
            if (!oversize) return scratch.toString();
        } catch (final Exception e) {
            LogUtil.w(TAG, "onIncomingMedia: FD->scratch ingest failed", e);
            return null;
        }
        LogUtil.w(TAG, "onIncomingMedia: blob exceeds " + MAX_INBOUND_FILE_BYTES
                + " bytes; dropped");
        try {
            ctx.getContentResolver().delete(scratch, null, null);
        } catch (final Exception e) {
            LogUtil.w(TAG, "onIncomingMedia: could not delete the partial copy", e);
        }
        return null;
    }

    /**
     * The largest inbound blob copied into scratch storage: 100 MiB, the file-transfer size limit
     * carriers advertise. The provider already refuses larger transfers; this bounds one that
     * does not.
     */
    static final long MAX_INBOUND_FILE_BYTES = 100L * 1024L * 1024L;

    /** Best-effort file extension for a MIME (MediaScratch keys files by it). */
    private static String extensionFor(final String mime) {
        final String ext = (mime == null) ? null
                : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return TextUtils.isEmpty(ext) ? "dat" : ext;
    }

    // ---- IRcsProviderCallback ----

    /**
     * The single path from any inbound transport into the message store. {@code msg.contentType}
     * and {@code msg.body} are final: the producer has already removed any RCC.16 framing, and
     * nothing downstream parses them again. See docs/rcs/architecture.md.
     */
    @Override
    public void onIncomingMessage(final RcsIncomingMessage msg) {
        if (msg == null) {
            return;
        }
        receiveMessage(msg, RcsInboundConfirmation.forBinderCall(msg.subId, msg.messageId));
    }

    /** Stores {@code msg}; the action confirms {@code ticket} once the row is stored. */
    private void receiveMessage(final RcsIncomingMessage msg,
            @Nullable final RcsInboundConfirmation.Ticket ticket) {
        new ReceiveRcsMessageAction(msg).confirming(ticket).start();
    }

    /**
     * Inbound MLS control messages, applied in order on one thread. A metadata commit ships with a
     * key delivery sealed at the post-commit epoch, so the pair must be applied in sequence.
     */
    @Override
    public void onMlsControlBundle(final int subId, final String fromE164, final String messageId,
            final byte[] packedMessages, final boolean convergenceAck, final String groupId,
            final long eraId, final byte[] epochAuthenticator, final String originalMessageId) {
        if (packedMessages == null || packedMessages.length == 0) return;
        // Report-only on this arm: header violations are logged, not dropped. The convergence ACK
        // that releases our send gate travels here, and small frames and one delivery plane have
        // not been shown to carry Era-ID and Epoch-Authenticator; dropping such a message would
        // wedge the conversation silently. The ciphertext arm below enforces.
        reportMlsHeaderGate("onMlsControlBundle", messageId, fromE164, eraId,
                epochAuthenticator, originalMessageId, /*enforcing=*/ false);
        final boolean ack = convergenceAck
                || (messageId != null && messageId.startsWith("ack:"));
        final String realId = (messageId != null && messageId.startsWith("ack:"))
                ? messageId.substring(4) : messageId;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                final MlsProviderTransport t = MlsProviderTransport
                        .get(Factory.get().getApplicationContext(), subId);
                int off = 0, n = 0;
                while (off + 4 <= packedMessages.length) {
                    final int len = ((packedMessages[off] & 0xFF) << 24)
                            | ((packedMessages[off + 1] & 0xFF) << 16)
                            | ((packedMessages[off + 2] & 0xFF) << 8)
                            | (packedMessages[off + 3] & 0xFF);
                    off += 4;
                    if (len < 0 || off + len > packedMessages.length) break;
                    final byte[] msg = java.util.Arrays.copyOfRange(packedMessages, off, off + len);
                    off += len;
                    n++;
                    try {
                        final boolean applied =
                                t.applyInboundControl(fromE164, realId, msg, ack, groupId);
                        LogUtil.i(TAG, "onMlsControlBundle: [" + n + "] " + msg.length
                                + "B applied=" + applied);
                    } catch (final Throwable th) {
                        LogUtil.w(TAG, "onMlsControlBundle: [" + n + "] apply threw", th);
                    }
                }
                } finally {
                    // Confirm once per bundle, in a finally: get() can throw before the loop, and
                    // an unconfirmed message is re-offered until the provider gives up.
                    confirmApplied(subId, realId);
                }
            }
        }, "mls-inbound-bundle").start();
    }

    /**
     * Tell the provider an inbound MLS message has been applied, so it stops redelivering it. Every
     * MLS handler must reach this on every path, including failures. See
     * docs/rcs/provider-contract.md.
     */
    private void confirmApplied(final int subId, final String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        // Test hook: debug.rcs.mls_skip_apply_ack suppresses the confirmation, to exercise the
        // provider's re-offer path on demand. Read live so it can be toggled between messages.
        if (skipApplyAck()) {
            LogUtil.w(TAG, "confirmApplied(" + messageId + ") SUPPRESSED by "
                    + "debug.rcs.mls_skip_apply_ack — the provider should re-offer this message "
                    + "off-pull after PENDING_ACK_REOFFER_MS. TEST ONLY; unset it afterwards or "
                    + "inbound will be re-offered until the provider gives up and DROPS it.");
            return;
        }
        try {
            ProviderTransport.getInstance(Factory.get().getApplicationContext())
                    .ackInboundMessages(subId, messageId);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "confirmApplied(" + messageId + ") failed — the provider will "
                    + "re-offer it", t);
        }
    }

    /** The {@code debug.rcs.mls_skip_apply_ack} test hook; see {@link #confirmApplied}. */
    private static boolean skipApplyAck() {
        return android.os.SystemProperties.getBoolean("debug.rcs.mls_skip_apply_ack", false);
    }

    @Override
    public void onMlsControl(final int subId, final String fromE164, final String messageId,
            final byte[] payload, final String groupId) {
        // The provider marks the peer-converged signal with an "ack:" id prefix, outside the MLS
        // payload.
        final boolean ack = messageId != null && messageId.startsWith("ack:");
        final String realId = ack ? messageId.substring(4) : messageId;
        LogUtil.i(TAG, "onMlsControl: sub=" + subId + " from=" + LogMask.number(fromE164)
                + " msgId=" + realId
                + " payload=" + (payload == null ? 0 : payload.length) + "B ack=" + ack);
        // Apply off this callback thread; the engine call can block and the provider is waiting.
        final int s = subId;
        final String from = fromE164;
        final byte[] p = payload;
        final String gid = groupId;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final boolean applied = MlsProviderTransport
                            .get(Factory.get().getApplicationContext(), s)
                            .applyInboundControl(from, realId, p, ack, gid);
                    LogUtil.i(TAG, "onMlsControl: applied=" + applied);
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "onMlsControl: apply threw", t);
                }
            }
        }, "mls-inbound-control").start();
    }

    /**
     * How long a failed decrypt waits for an in-flight join before it becomes a failure report. The
     * observed race is tens of milliseconds; waiting longer only delays a report, while too short a
     * wait sends a spurious report and provokes a resend.
     */
    private static final long JOIN_RACE_WINDOW_MS = 3000L;

    /**
     * Inbound MLS application ciphertext. The app decrypts and unframes it and inserts it through
     * {@link ReceiveRcsMessageAction} tagged with the MLS scheme. Nothing is inserted if it cannot
     * be decrypted.
     */
    @Override
    public void onMlsCiphertext(final int subId, final String fromE164, final String messageId,
            final byte[] ciphertext, final String groupId,
            final long eraId, final byte[] epochAuthenticator, final String originalMessageId) {
        LogUtil.i(TAG, "onMlsCiphertext: sub=" + subId + " from=" + LogMask.number(fromE164)
                + " msgId=" + MlsMessageId.forLog(messageId)
                + " ct=" + (ciphertext == null ? 0 : ciphertext.length) + "B");
        // A message missing the required RCC.16 headers is dropped before the decrypt, so it cannot
        // consume a ratchet step.
        if (!reportMlsHeaderGate("onMlsCiphertext", messageId, fromE164, eraId, epochAuthenticator,
                originalMessageId, /*enforcing=*/ true)) {
            return;
        }
        // Decrypt off this thread. The era goes with it: a new era restarts the epoch at 0, so
        // without it a message from the next era looks like a stale one. The ticket is taken here,
        // where the caller is known.
        decryptClassifyInsert(subId, fromE164, messageId, ciphertext, groupId, eraId,
                /*replayOfParked=*/ false, originalMessageId,
                RcsInboundConfirmation.forBinderCall(subId, messageId));
    }

    /**
     * Replays a parked message when its group moment arrives. The header gate is not re-run: it
     * passed at admission, and the headers were not stored.
     */
    @Override
    public void replayParkedMlsCiphertext(final int subId, final String fromE164,
            final String messageId, final byte[] ciphertext, final String groupId,
            final long eraId) {
        LogUtil.i(TAG, "replayParkedMlsCiphertext: " + MlsMessageId.forLog(messageId) + " from "
                + LogMask.number(fromE164) + " ("
                + (ciphertext == null ? 0 : ciphertext.length) + "B era=" + eraId
                + ") — its moment arrived; running the ordinary inbound pipeline");
        // A replay has no Original-Message-ID (the park keeps no headers). It is only a cross-check
        // on the AAD, which travels with the ciphertext, so no decision changes.
        decryptClassifyInsert(subId, fromE164, messageId, ciphertext, groupId, eraId,
                /*replayOfParked=*/ true, /*originalMessageId=*/ null, /*ticket=*/ null);
    }

    /**
     * Decrypt, classify and insert one inbound MLS application ciphertext, off the caller's thread.
     *
     * @param replayOfParked true for a parked message being replayed; suppresses the second
     *     {@code confirmApplied}, since the message was confirmed when it was parked
     * @param originalMessageId the {@code Original-Message-ID} header, set only on an RCC.16 §10.3
     *     resend
     * @param ticket the confirmation of this delivery, or null; a message that is stored is
     *     confirmed by the action that stores it, after the row is written, and not here
     */
    private void decryptClassifyInsert(final int subId, final String fromE164,
            final String messageId, final byte[] ciphertext, final String groupId,
            final long eraId, final boolean replayOfParked, final String originalMessageId,
            @Nullable final RcsInboundConfirmation.Ticket ticket) {
        final int s = subId;
        final String from = fromE164;
        final String mid = messageId;
        final byte[] ct = ciphertext;
        final String gid = groupId;
        final long era = eraId;
        final String origMid = originalMessageId;
        // With the test hook set nothing is handed over, and confirmApplied suppresses it.
        final RcsInboundConfirmation.Ticket storeTicket = skipApplyAck() ? null : ticket;
        new Thread(new Runnable() {
            @Override public void run() {
                // Set when the confirmation is left to a store that runs later.
                boolean confirmedByStore = false;
                try {
                    // A stored message delivered again (our confirmation did not reach the
                    // provider) no longer decrypts, and a failed decrypt is reported to the sender,
                    // whose resend would be a second row. Asked before the decrypt; the finally
                    // confirms it as it would a stored message, and nothing else is sent.
                    final MlsInboundRedelivery.Verdict seen = MlsInboundRedelivery.beforeDecrypt(
                            mid, RcsCallbackRouter.this::insertClaimed,
                            RcsCallbackRouter::storedLocally);
                    if (seen.isRedelivery()) {
                        // A claimed id not yet stored is confirmed by the claim holder's store.
                        confirmedByStore = seen == MlsInboundRedelivery.Verdict.CLAIMED
                                && !storedLocally(mid);
                        LogUtil.i(TAG, "onMlsCiphertext: " + MlsMessageId.forLog(mid) + " is "
                                + seen + " already — a redelivery: not decrypted, not reported, "
                                + (confirmedByStore ? "confirmed once its first copy is stored"
                                        : "confirmed so the provider releases it"));
                        return;
                    }
                    RccMlsBody.Parsed parsed = MlsProviderTransport
                            .get(Factory.get().getApplicationContext(), s)
                            .decryptInbound(from, mid, ct, gid, origMid);
                    if (parsed == null || parsed.body == null) {
                        // The first application message can beat the Welcome that provides its
                        // keys. Everything below is irreversible (a failure report provokes a
                        // resend), so wait for an in-flight join first. Only when no group is held
                        // at all: that case cannot be parked, since the queue is keyed by group.
                        parsed = MlsProviderTransport
                                .get(Factory.get().getApplicationContext(), s)
                                .awaitJoinAndRetryDecrypt(from, mid, ct, gid, JOIN_RACE_WINDOW_MS,
                                        origMid);
                    }
                    if (parsed == null || parsed.body == null) {
                        // RCC.16 §10: self-heal first, report only if the failure persists. A
                        // ciphertext strictly from the future is parked instead of reported;
                        // onDecryptFailure makes that call.
                        LogUtil.w(TAG, "onMlsCiphertext: " + mid + " not decrypted — entering "
                                + "§10 recovery (self-heal, then report if it persists), unless it "
                                + "is strictly from the future, in which case it is parked");
                        MlsProviderTransport.get(Factory.get().getApplicationContext(), s)
                                .onDecryptFailure(gid, from, mid, ct, era);
                        return;
                    }
                    // An MLS reaction is an ordinary text/plain application message whose reaction
                    // headers are in the encrypted CPIM, so it is recognised here, after the
                    // decrypt and before the content-type switch (which would render it as a
                    // bubble).
                    final RccCpimReaction cpim = RccCpimReaction.parse(parsed.body);
                    if (cpim != null && cpim.isReaction()) {
                        final String target = cpim.reactedMessageId();
                        final String emoji = cpim.emoji();
                        if (target == null || target.isEmpty() || emoji.isEmpty()) {
                            // A reaction that cannot be attached is logged, not rendered.
                            LogUtil.w(TAG, "onMlsCiphertext: " + mid + " is a REACTION but is "
                                    + "unusable (target=" + target + " emojiLen=" + emoji.length()
                                    + ") — dropping it rather than rendering it as a message");
                            return;
                        }
                        LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is a REACTION "
                                + (cpim.isAdd() ? "ADD" : "REMOVE") + " on " + target
                                + " from " + LogMask.number(from)
                                + " — routing to the reaction store, not the "
                                + "thread");
                        receiveReaction(target, from, emoji, cpim.isAdd(), storeTicket);
                        confirmedByStore = storeTicket != null;
                        // A reaction does not pass through onIncomingMessage, so send its delivered
                        // receipt here. Not a displayed receipt: a reaction is applied to a row,
                        // not shown as one.
                        try {
                            ProviderTransport.getInstance(Factory.get().getApplicationContext())
                                    .sendImdn(mid, from,
                                            IRcsProviderCallback.IMDN_DELIVERED, gid);
                        } catch (final Throwable ackFail) {
                            LogUtil.w(TAG, "onMlsCiphertext: reaction " + mid + " was applied but "
                                    + "its delivery receipt could not be sent — the sender will "
                                    + "show it undelivered", ackFail);
                        }
                        return;
                    }

                    // One classifier for the whole inbound path; anything it misses would become a
                    // bubble.
                    final int disp = RccContentDisposition.classify(parsed.contentType);
                    if (disp == RccContentDisposition.DROP_CONTROL) {
                        // RCC.16 §7.6.2: an MLS-carried IMDN is a receipt. Verify its signature and
                        // apply it; an unverified one is discarded. Typing indicators land here too
                        // and are not shown.
                        if (parsed.contentType != null
                                && parsed.contentType.startsWith("message/imdn")) {
                            final MlsProviderTransport t = MlsProviderTransport
                                    .get(Factory.get().getApplicationContext(), s);
                            final MlsTransportTypes.ImdnCheck chk =
                                    t.verifyDecryptedImdn(gid, from, parsed.body);
                            if (chk != null && !chk.ok()) {
                                LogUtil.w(TAG,
                                        "onMlsCiphertext: DISCARDING a signed IMDN that failed "
                                        + "validation (" + chk
                                        + ") — it is not evidence of delivery");
                                return;
                            }
                            LogUtil.i(TAG, "onMlsCiphertext: MLS IMDN " + mid + " → "
                                    + (chk == null ? "unsigned (applied unverified)"
                                                   : "VERIFIED " + chk));
                            // Fall through: the provider's own IMDN path applies the status.
                        } else if (MlsInboundRefusal.isRefusal(parsed.contentType)) {
                            // A refusal: the message was readable and deliberately not accepted.
                            // Logged apart from signalling, and never routed into recovery, which
                            // would tell the sender we could not decrypt it.
                            LogUtil.w(TAG, "onMlsCiphertext: " + mid + " from "
                                    + LogMask.number(from) + " was "
                                    + "REFUSED by the decrypt path ("
                                    + MlsInboundRefusal.forMarker(parsed.contentType) + ") — not "
                                    + "inserted, not receipted, and NOT routed into §10 recovery. "
                                    + "The refusal reason is on the MlsProviderTransport line above.");
                        } else {
                            LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is signalling ("
                                    + parsed.contentType + ") — not inserted");
                        }
                        return;
                    }
                    if (disp == RccContentDisposition.DROP_KEY) {
                        // A key, not a message: route by content type to the RCC.16 §7.13.4
                        // GroupMetadataKeys or the §7.8.1 FileInfo handler.
                        final boolean isMetadataKeys = parsed.contentType != null
                                && parsed.contentType.trim().toLowerCase(java.util.Locale.US)
                                        .startsWith(RccGroupMetadataKeys.CONTENT_TYPE);
                        final MlsProviderTransport tx = MlsProviderTransport
                                .get(Factory.get().getApplicationContext(), s);
                        final boolean stored = isMetadataKeys
                                ? tx.onGroupMetadataKeys(gid, from, parsed.body)
                                : tx.onFileInfo(gid, from, parsed.body);
                        LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is a "
                                + (isMetadataKeys ? "§7.13.4 GroupMetadataKeys" : "§7.8.1 FileInfo")
                                + " → " + (stored ? "stored" : "DROPPED") + " (not inserted)");
                        return;
                    }
                    if (RccContentDisposition.isDrop(disp)) {
                        // An unrouted type is dropped rather than rendered; the log names it.
                        LogUtil.w(TAG, "onMlsCiphertext: " + mid + " has UNROUTED contentType="
                                + parsed.contentType + " (" + RccContentDisposition.name(disp)
                                + ", " + parsed.body.length + "B) — dropped, NOT shown. If a peer "
                                + "legitimately sends this, it needs a handler.");
                        return;
                    }
                    // One row per message id. An in-memory claim closes the race between concurrent
                    // dispatches; the durable check covers a redelivery after a restart.
                    if (!claimInboundInsert(mid)) {
                        LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is already being inserted by "
                                + "another dispatch — dropping this duplicate rather than writing a "
                                + "second row for one message");
                        // That dispatch's store confirms it, unless it is stored already.
                        confirmedByStore = !storedLocally(mid);
                        return;
                    }
                    try {
                        final DatabaseWrapper idb = DataModel.get().getDatabase();
                        if (idb != null
                                && RcsMessageStore.findLocalIdByRcsMessageId(idb, mid) != null) {
                            LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is ALREADY in the message "
                                    + "store — a duplicate dispatch (or a redelivery across a "
                                    + "restart). Not inserting it a second time.");
                            return;
                        }
                    } catch (final Throwable dupCheck) {
                        // A failed read never blocks delivery; a possible duplicate beats a lost
                        // message.
                        LogUtil.w(TAG, "onMlsCiphertext: duplicate check failed for " + mid
                                + " — inserting anyway", dupCheck);
                    }
                    // This is the producer: hand down the unframed content.
                    receiveMessage(new RcsIncomingMessage(s, mid, from, parsed.contentType,
                            parsed.body, /*serverTimestampUsec=*/ 0L, /*wantsDeliveredImdn=*/ true,
                            /*wantsDisplayedImdn=*/ true, gid, RcsE2eeScheme.MLS), storeTicket);
                    confirmedByStore = storeTicket != null;
                    // The message is stored, so its rendezvous rows are released: every stage and
                    // every sender attribution of this id, since a narrower delete leaks a row per
                    // message.
                    final int dropped = MlsProviderTransport
                            .get(Factory.get().getApplicationContext(), s)
                            .forgetRendezvous(mid);
                    LogUtil.i(TAG, "onMlsCiphertext: inserted " + mid + " ("
                            + parsed.body.length + "B " + parsed.contentType + ")"
                            + (dropped > 0 ? " — released " + dropped + " rendezvous row(s)" : ""));
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "onMlsCiphertext: decrypt/insert threw", t);
                } finally {
                    // In a finally: every early return above is a final answer for this message and
                    // must be confirmed. A parked message was confirmed when it was parked, so a
                    // replay does not confirm again, and a message handed to a store is confirmed
                    // by that store once the row exists.
                    if (!replayOfParked && !confirmedByStore) {
                        confirmApplied(s, mid);
                    }
                }
            }
        }, "mls-inbound-message").start();
    }

    @Override
    public void onIncomingMedia(final RcsIncomingFile file) {
        // The provider has already downloaded the file (and the thumbnail) and resolved it to a URI
        // or a descriptor; this only hands it to the receive action.
        if (file == null) {
            return;
        }
        // Taken before the calling identity is cleared: it names the provider to confirm to.
        final RcsInboundConfirmation.Ticket ticket =
                RcsInboundConfirmation.forBinderCall(file.subId, file.messageId);
        // A descriptor cannot survive the action queue, so copy it into our own storage now. Clear
        // the calling identity first: on the binder thread it is the provider's uid, which cannot
        // write our non-exported MediaScratchFileProvider.
        final long token = Binder.clearCallingIdentity();
        String durableFileUri = null;
        String durableThumbUri = file.contentUriThumbnail;
        try {
        if (file.fd != null) {
            if (TextUtils.isEmpty(file.contentUri)) {
                durableFileUri = ingestFdToScratch(mAppContext, file.fd, file.mimeType);
            } else {
                // contentUri already durable; close the redundant FD.
                try {
                    file.fd.close();
                } catch (final java.io.IOException ignored) {
                }
            }
        }
        // Same for the thumbnail.
        if (file.fdThumbnail != null) {
            if (TextUtils.isEmpty(durableThumbUri)) {
                final String tmime = !TextUtils.isEmpty(file.mimeTypeThumbnail)
                        ? file.mimeTypeThumbnail : "image/jpeg";
                durableThumbUri = ingestFdToScratch(mAppContext, file.fdThumbnail, tmime);
            } else {
                try {
                    file.fdThumbnail.close();
                } catch (final java.io.IOException ignored) {
                }
            }
        }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        if (file.fd != null && TextUtils.isEmpty(file.contentUri)
                && TextUtils.isEmpty(durableFileUri)) {
            // The file came and could not be kept. Not stored, so not confirmed: the provider
            // offers it again. Storing a pending row instead would strand it, as the provider has
            // nothing left to download on a tap.
            LogUtil.w(TAG, "onIncomingMedia: " + file.messageId + ": the file could not be "
                    + "copied; not stored or confirmed, the provider offers it again");
            return;
        }
        new ReceiveRcsMediaAction(file, durableFileUri, durableThumbUri).confirming(ticket)
                .start();
    }

    @Override
    public void onIncomingFileUnavailable(final int subId, final String messageId,
            final int reason) {
        // A pending file the user tapped can no longer be downloaded. Nothing to confirm: the file
        // message itself was confirmed when its pending row was stored.
        LogUtil.i(TAG, "onIncomingFileUnavailable: " + messageId + " reason=" + reason);
        if (TextUtils.isEmpty(messageId)) {
            return;
        }
        new MarkRcsFileUnavailableAction(messageId, reason).start();
    }

    @Override
    public void onIncomingReaction(final int subId, final String targetMessageId,
            final String fromUri, final String emoji, final boolean add,
            @Nullable final String groupId, @Nullable final String confirmId) {
        receiveReaction(targetMessageId, fromUri, emoji, add,
                RcsInboundConfirmation.forBinderCall(subId, confirmId));
    }

    /** Stores a reaction; the action confirms {@code ticket} once it is stored. */
    private void receiveReaction(final String targetMessageId, final String fromUri,
            final String emoji, final boolean add,
            @Nullable final RcsInboundConfirmation.Ticket ticket) {
        // Only the reaction's effect is stored, in rcs_reactions keyed by the target's wire id;
        // group reactions aggregate per emoji.
        if (TextUtils.isEmpty(targetMessageId) || TextUtils.isEmpty(emoji)) {
            // Nothing can be stored, which is a final answer.
            if (ticket != null) RcsInboundConfirmation.send(ticket, "onIncomingReaction");
            return;
        }
        new UpdateRcsReactionAction(targetMessageId, fromUri, emoji, add,
                System.currentTimeMillis()).confirming(ticket).start();
    }

    @Override
    public void onIncomingBotMessage(final RcsIncomingBotMessage msg) {
        // The raw GSMA JSON goes to the receive action; no receipt is sent to a bot.
        if (msg == null) {
            return;
        }
        new ReceiveRcsBotMessageAction(msg)
                .confirming(RcsInboundConfirmation.forBinderCall(msg.subId, msg.messageId))
                .start();
    }

    @Override
    public void onMessageStatus(final int subId, final String messageId, final int status,
            @Nullable final String errorReason, @Nullable final String e2eeSchemeId) {
        onMessageStatus(subId, messageId, status, errorReason, e2eeSchemeId,
                E2eeObservation.Source.PROVIDER);
    }

    /**
     * A send status from the in-process carrier transport. It never changes a row's scheme or a
     * conversation's encryption bits: the carrier transport applies no provider-layer scheme.
     */
    public void onCarrierMessageStatus(final int subId, final String messageId, final int status,
            @Nullable final String errorReason) {
        onMessageStatus(subId, messageId, status, errorReason, null,
                E2eeObservation.Source.CARRIER);
    }

    private void onMessageStatus(final int subId, final String messageId, final int status,
            @Nullable final String errorReason, @Nullable final String e2eeSchemeId,
            final E2eeObservation.Source source) {
        // STATUS_FAILED is terminal: release this attempt's resend material. Not on SENT, since a
        // sent message can still draw a failure report whose resend must replay the same bytes.
        if (status == IRcsProviderCallback.STATUS_FAILED) {
            // Releases the failed attempt only, not the whole chain: the ledger rows resolve a
            // resend's id to the root that holds the body.
            LogUtil.i(TAG, "RcsCallbackRouter: STATUS_FAILED for " + messageId + " (errorReason="
                    + errorReason + ") — releasing THIS attempt's resend material and keeping the "
                    + "rest of its chain. If this id is a RESEND, the chain survives and the failure "
                    + "is what to question.");
            final MlsProviderTransport t = MlsProviderTransport.peek();
            if (t != null) t.releaseSealedOnPermanentFailure(messageId);
        } else {
            LogUtil.v(TAG, "RcsCallbackRouter: status " + status + " for " + messageId
                    + " — not terminal, resend material kept.");
        }
        UpdateRcsMessageStatusAction.forStatus(messageId, status, e2eeSchemeId, source).start();
    }

    @Override
    public void onImdnReceipt(final int subId, final String messageId, final int imdnType,
            @Nullable final String confirmId) {
        // A delivered receipt may release cached resend material; releaseSealedOnPositiveReceipt
        // decides (a 1:1 releases, a group keeps it until every member confirms). Sending is never
        // terminal.
        if (imdnType == IRcsProviderCallback.IMDN_DELIVERED) {
            LogUtil.i(TAG, "RcsCallbackRouter: IMDN DELIVERED for " + messageId
                    + " — handing it to the send-retention policy, which decides 1:1 (release) vs "
                    + "GROUP (keep until every member confirms). Its verdict is the NEXT line.");
            final MlsProviderTransport t = MlsProviderTransport.peek();
            if (t != null) t.releaseSealedOnPositiveReceipt(messageId);
        } else {
            LogUtil.i(TAG, "RcsCallbackRouter: IMDN type " + imdnType + " for " + messageId
                    + " — NOT a delivery receipt, send material kept");
        }
        new UpdateRcsMessageStatusAction(
                messageId, UpdateRcsMessageStatusAction.KIND_IMDN, imdnType,
                System.currentTimeMillis())
                .confirming(RcsInboundConfirmation.forBinderCall(subId, confirmId))
                .start();
    }

    @Override
    public void onImdnReceiptForPeer(final int subId, final String fromUri,
            final int imdnType) {
        // TODO: thread-wide receipt: mark all outstanding outgoing RCS messages to fromUri
        // delivered/displayed. The per-message onImdnReceipt covers the common case.
        LogUtil.i(LogUtil.BUGLE_TAG, "onImdnReceiptForPeer sub=" + subId
                + " peer=" + LogMask.number(fromUri) + " type=" + imdnType
                + " (thread-wide; TODO)");
    }

    @Override
    public void onMlsNegativeDelivery(final int subId, final String messageId,
            @Nullable final String fromUri, @Nullable final String groupId,
            final int failureReason) {
        // RCC.16 §7.7.2.2: a peer reports that our message failed on its side.
        LogUtil.w(LogUtil.BUGLE_TAG, "onMlsNegativeDelivery sub=" + subId + " mid="
                + MlsMessageId.forLog(messageId)
                + " from=" + LogMask.number(fromUri) + " group=" + groupId + " reason="
                + failureReason);
        if (fromUri == null || messageId == null) return;
        // One remedy per report. The provider can dispatch the same report twice, and two resends
        // of one message look like a failing resend and escalate. A legitimate second report names
        // the resend's new id, so it does not collide with this key.
        if (!claimNegativeDelivery(fromUri, messageId, failureReason)) {
            LogUtil.i(LogUtil.BUGLE_TAG, "onMlsNegativeDelivery: duplicate report of "
                    + MlsMessageId.forLog(messageId)
                    + " from " + LogMask.number(fromUri) + " reason=" + failureReason
                    + " within the dedupe window "
                    + "— ignoring. One report, one remedy.");
            return;
        }
        final int s = subId;
        final String from = fromUri;
        final String mid = messageId;
        final String gid = groupId;
        final int reason = failureReason;
        // Off the callback thread: the remedy does engine work and server round trips.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    MlsProviderTransport.get(Factory.get().getApplicationContext(), s)
                            .onPeerReportedFailure(gid, from, mid, reason);
                } catch (final Throwable t) {
                    LogUtil.e(LogUtil.BUGLE_TAG, "onMlsNegativeDelivery: remedy threw", t);
                }
            }
        }, "mls-negative-delivery").start();
    }

    @Override
    public void onRegistrationStateChanged(final int subId, final int state,
            @Nullable final String reason) {
        mRouteSelector.onRegistrationStateChanged(subId, state);
    }

    @Override
    public void onProvisioningStateChanged(final int subId, final int provState,
            @Nullable final RcsProviderCaps caps) {
        mRouteSelector.onProvisioningStateChanged(subId, provState, caps);
    }

    @Override
    public void onE2eeStateChanged(final int subId,
            @Nullable final org.lineageos.rcs.provider.RcsE2eeInfo info) {
        // Cache the state for the settings row and broadcast so an open settings screen updates.
        mE2eeInfo = info;
        LogUtil.i(TAG, "onE2eeStateChanged: available="
                + (info != null && info.available) + " enabled="
                + (info != null && info.enabled) + " scheme="
                + (info != null ? info.schemeId : null));
        final Intent i = new Intent(RcsConstants.ACTION_E2EE_STATE_CHANGED)
                .setPackage(mAppContext.getPackageName())
                .putExtra(RcsConstants.EXTRA_SUB_ID, subId);
        mAppContext.sendBroadcast(i);
    }

    @Override
    public void onOtpRequired(final int subId, @Nullable final String hint) {
        // For a manual OTP entry UI; RcsOtpReceiver usually submits the code itself.
        final Intent i = new Intent(RcsConstants.ACTION_OTP_REQUIRED)
                .setPackage(mAppContext.getPackageName())
                .putExtra(RcsConstants.EXTRA_SUB_ID, subId)
                .putExtra(RcsConstants.EXTRA_OTP_HINT, hint);
        mAppContext.sendBroadcast(i);
    }

    @Override
    public void onCarrierTosStateChanged(final int subId, final int tosState,
            @Nullable final RcsTosPrompt prompt) {
        // Cache the state and prompt for the settings row and the re-prompt dialog.
        mRouteSelector.setTosState(subId, tosState, prompt);

        // Only TOS_REQUIRED needs the user; other states surface through the settings row.
        if (tosState != IRcsProviderCallback.TOS_REQUIRED) {
            return;
        }

        // Package-scoped broadcast; RcsCarrierTosReceiver picks dialog or notification.
        final Intent i = new Intent(RcsConstants.ACTION_CARRIER_TOS_REQUIRED)
                .setPackage(mAppContext.getPackageName())
                .putExtra(RcsConstants.EXTRA_TOS_SUB_ID, subId);
        if (prompt != null) {
            i.putExtra(RcsConstants.EXTRA_TOS_TITLE, prompt.title)
             .putExtra(RcsConstants.EXTRA_TOS_BODY, prompt.message)
             .putExtra(RcsConstants.EXTRA_TOS_ACCEPT_LABEL, prompt.acceptLabel)
             .putExtra(RcsConstants.EXTRA_TOS_REJECT_LABEL, prompt.rejectLabel)
             .putExtra(RcsConstants.EXTRA_TOS_KIND, prompt.kind);
        }
        mAppContext.sendBroadcast(i);
    }

    @Override
    public void onTyping(final int subId, final String fromUri, final boolean active) {
        // Advisory, no database write: in-process listeners first, then the broadcast.
        dispatchTyping(subId, fromUri, active);

        final Intent i = new Intent(RcsConstants.ACTION_TYPING)
                .setPackage(mAppContext.getPackageName())
                .putExtra(RcsConstants.EXTRA_SUB_ID, subId)
                .putExtra(RcsConstants.EXTRA_FROM_URI, fromUri)
                .putExtra(RcsConstants.EXTRA_TYPING_ACTIVE, active);
        mAppContext.sendBroadcast(i);
    }

    @Override
    public void onGroupTyping(final int subId, final String groupId,
            final String fromUri, final boolean active) {
        // Advisory, no database write: in-process listeners first, then the broadcast.
        dispatchGroupTyping(subId, groupId, fromUri, active);

        final Intent i = new Intent(RcsConstants.ACTION_GROUP_TYPING)
                .setPackage(mAppContext.getPackageName())
                .putExtra(RcsConstants.EXTRA_SUB_ID, subId)
                .putExtra(RcsConstants.EXTRA_GROUP_ID, groupId)
                .putExtra(RcsConstants.EXTRA_FROM_URI, fromUri)
                .putExtra(RcsConstants.EXTRA_TYPING_ACTIVE, active);
        mAppContext.sendBroadcast(i);
    }

    @Override
    public void onGroupEvent(final int subId, final int op,
            @Nullable final String groupId, @Nullable final String name,
            @Nullable final String conferenceUri, @Nullable final String requester,
            final java.util.List<String> members,
            final java.util.List<String> affectedMembers, @Nullable final String confirmId) {
        new ReceiveRcsGroupEventAction(subId, op, groupId, name, conferenceUri,
                requester,
                members != null ? new java.util.ArrayList<>(members)
                                : new java.util.ArrayList<String>(),
                affectedMembers != null ? new java.util.ArrayList<>(affectedMembers)
                                        : new java.util.ArrayList<String>())
                .confirming(RcsInboundConfirmation.forBinderCall(subId, confirmId))
                .start();
    }

    /**
     * An encrypted group subject arrived (RCC.16 §9.7.1.5). The key came earlier, in the RCC.16
     * §7.8.1 FileInfo of the commit's private message. See docs/mls/metadata.md.
     */
    @Override
    public void onEncryptedGroupSubject(final int subId, @Nullable final String groupId,
            @Nullable final String fromE164, @Nullable final String contentType,
            final byte[] ciphertext) {
        if (groupId == null || ciphertext == null || ciphertext.length == 0) return;
        final Context ctx = Factory.get().getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                // The key may not have arrived yet; the transport then holds the ciphertext, and
                // null means "not yet", not "failed".
                final byte[] plain = MlsProviderTransport.get(ctx, subId)
                        .onEncryptedSubject(groupId, fromE164, ciphertext);
                if (plain == null) return;
                final String subject =
                        new String(plain, java.nio.charset.StandardCharsets.UTF_8);
                LogUtil.i(TAG, "RcsCallbackRouter: DECRYPTED group subject for " + groupId
                        + " (" + plain.length + "B)");
                MlsSubjectApplier.apply(groupId, subject);
            }
        }, "mls-open-subject").start();
    }

    /**
     * An encrypted group icon reference arrived (RCC.16 §9.7.1.4). Logged only; the provider
     * fetches the ciphertext and delivers it through {@link #onEncryptedGroupIconContent}.
     */
    @Override
    public void onEncryptedGroupIcon(final int subId, @Nullable final String groupId,
            @Nullable final String fromE164, @Nullable final String first,
            @Nullable final String second) {
        LogUtil.i(TAG, "RcsCallbackRouter: encrypted group ICON for " + groupId + " from "
                + LogMask.number(fromE164) + " — contentType(field 1)=" + first + " url(field 2)="
                + (second == null ? "absent" : second.length() + "ch")
                + " (the provider fetches the ciphertext; onEncryptedGroupIconContent follows if "
                + "the fetch succeeds)");
    }

    /**
     * The downloaded ciphertext of an encrypted group icon. As {@link #onEncryptedGroupSubject}: a
     * null result means the key has not arrived yet and the transport holds the ciphertext.
     */
    @Override
    public void onEncryptedGroupIconContent(final int subId, @Nullable final String groupId,
            @Nullable final String fromE164, @Nullable final String contentType,
            final byte[] ciphertext) {
        if (groupId == null || ciphertext == null || ciphertext.length == 0) return;
        final Context ctx = Factory.get().getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final byte[] plain = MlsProviderTransport.get(ctx, subId)
                        .onEncryptedIcon(groupId, fromE164, ciphertext);
                if (plain == null) return;
                LogUtil.i(TAG, "RcsCallbackRouter: DECRYPTED group icon for " + groupId
                        + " (" + plain.length + "B)");
                GroupIconApplier.apply(ctx, groupId, plain);
            }
        }, "mls-open-icon").start();
    }

    @Override
    public void onGroupImdnReceipt(final int subId, final String rcsMessageId,
            final String fromUri, final int imdnType, @Nullable final String confirmId) {
        // Per-member group receipt, sent by the provider alongside every 1:1 receipt; the action
        // does nothing unless the message belongs to a group.
        new UpdateRcsMessageStatusAction(rcsMessageId,
                UpdateRcsMessageStatusAction.KIND_GROUP_IMDN, imdnType,
                System.currentTimeMillis(), fromUri)
                .confirming(RcsInboundConfirmation.forBinderCall(subId, confirmId))
                .start();
        // A delivered receipt names the member, so a group message's resend material can be
        // released once every member has confirmed, instead of at the end of the retention window.
        if (imdnType == IRcsProviderCallback.IMDN_DELIVERED) {
            final int s2 = subId;
            final String mid = rcsMessageId;
            final String who = fromUri;
            // Off the callback thread: this reads the roster under the conversation lock.
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        MlsProviderTransport.get(Factory.get().getApplicationContext(), s2)
                                .releaseSealedOnGroupReceipt(mid, who);
                    } catch (final Throwable t) {
                        LogUtil.w(TAG, "onGroupImdnReceipt: attributed release failed", t);
                    }
                }
            }, "mls-group-receipt").start();
        }
    }

    /**
     * Our MLS identity was replaced (re-minted or invalidated). Published KeyPackages embed the old
     * certificate and stay claimable, so republish at once rather than at the next session start.
     */
    @Override
    public void onMlsIdentityChanged(final int subId, final String reason) {
        LogUtil.i(TAG, "onMlsIdentityChanged: sub=" + subId + " reason=" + reason
                + " — republishing the KeyPackage pool");
        final int s = subId;
        final String why = reason;
        // Off the callback thread: this re-reads the identity and uploads.
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    MlsProviderTransport.get(Factory.get().getApplicationContext(), s)
                            .onIdentityChanged(why);
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "onMlsIdentityChanged: republish failed", t);
                }
            }
        }, "mls-identity-changed").start();
    }

    /**
     * Applies {@link MlsHeaderGate} to the inbound RCC.16 headers and logs the verdict. Returns
     * false when the headers fail; only an enforcing caller drops the message.
     */
    private static boolean reportMlsHeaderGate(final String arm, final String messageId,
            final String fromE164, final long eraId, final byte[] epochAuthenticator,
            final String originalMessageId, final boolean enforcing) {
        // The verdict wording differs between the control plane and a content-typed message.
        final boolean control = "onMlsControlBundle".equals(arm);
        try {
            final String eraStr = eraId < 0 ? null : Long.toString(eraId);
            final String authStr = (epochAuthenticator == null || epochAuthenticator.length == 0)
                    ? null
                    : android.util.Base64.encodeToString(epochAuthenticator,
                            android.util.Base64.NO_WRAP);
            final MlsHeaderGate.Verdict v = MlsHeaderGate.check(new MlsHeaderGate.Headers() {
                @Override public String get(final String namespace, final String name) {
                    if (!MlsHeaderGate.MLS_NAMESPACE.equals(namespace)) return null;
                    if (MlsHeaderGate.HDR_ERA_ID.equals(name)) return eraStr;
                    if (MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR.equals(name)) return authStr;
                    if (MlsHeaderGate.HDR_ORIGINAL_MESSAGE_ID.equals(name)) {
                        return originalMessageId;
                    }
                    return null;
                }
            });
            if (v.accepted()) {
                LogUtil.i(TAG, arm + ": MLS headers OK for " + MlsMessageId.forLog(messageId)
                        + " from "
                        + LogMask.number(fromE164)
                        + " era=" + eraId
                        + (originalMessageId == null
                                ? "" : " RESEND of " + MlsMessageId.forLog(originalMessageId)));
                return true;
            }
            // Two log vocabularies: the CPIM splitter's (cannot convert) and the message-level drop
            // line.
            LogUtil.w(TAG, eraId < 0 && authStr == null
                    ? MlsTrace.noMlsHeaders() : MlsTrace.incompleteMlsHeaders());
            LogUtil.w(TAG, eraId < 0 && authStr == null
                    ? MlsTrace.noMlsHeadersNamespaces(MlsHeaderGate.MLS_NAMESPACE)
                    : MlsTrace.incompleteMlsHeadersFound(
                            (eraId < 0 ? "" : MlsHeaderGate.HDR_ERA_ID)
                            + (authStr == null ? "" : " "
                                    + MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR)));
            // The drop marker follows the caller's real behaviour.
            LogUtil.w(TAG, arm + ": " + v.logLine(control)
                    + (enforcing ? " [DROPPED — invariant 51]" : " [REPORT-ONLY — not dropped]")
                    + " msgId=" + MlsMessageId.forLog(messageId) + " from="
                    + LogMask.number(fromE164) + " era="
                    + (eraId < 0 ? "ABSENT" : Long.toString(eraId))
                    + " epochAuth="
                    + (authStr == null ? "ABSENT" : epochAuthenticator.length + "B"));
            return false;
        } catch (final Throwable t) {
            // If the check itself throws, accept: a bug in the gate must not drop a message.
            LogUtil.w(TAG, arm + ": MLS header gate threw — ACCEPTING rather than dropping", t);
            return true;
        }
    }

}
