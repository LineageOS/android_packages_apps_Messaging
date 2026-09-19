/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import com.android.messaging.rcs.RcsContentDisposition;
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

import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.action.ReceiveRcsBotMessageAction;
import com.android.messaging.datamodel.action.ReceiveRcsGroupEventAction;
import com.android.messaging.datamodel.action.ReceiveRcsMediaAction;
import com.android.messaging.datamodel.action.ReceiveRcsMessageAction;
import com.android.messaging.datamodel.action.UpdateRcsMessageStatusAction;
import com.android.messaging.datamodel.action.UpdateRcsReactionAction;
import com.android.messaging.rcs.e2ee.E2eeObservation;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;

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
public final class RcsCallbackRouter extends IRcsProviderCallback.Stub {
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

    /** Best-effort file extension for a MIME (MediaScratchFileProvider keys files by it). */
    private static String extensionFor(final String mime) {
        final String ext = (mime == null) ? null
                : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return TextUtils.isEmpty(ext) ? "dat" : ext;
    }

    // ---- IRcsProviderCallback ----

    /**
     * The single path from any inbound transport into the message store. {@code msg.contentType}
     * and {@code msg.body} are final: the producer has already removed any framing, and
     * nothing downstream parses them again. See docs/rcs/architecture.md.
     */
    @Override
    public void onIncomingMessage(final RcsIncomingMessage msg) {
        if (msg == null) {
            return;
        }
        new ReceiveRcsMessageAction(msg).start();
    }

    @Override
    public void onIncomingMedia(final RcsIncomingFile file) {
        // The provider has already downloaded the file (and the thumbnail) and resolved it to a URI
        // or a descriptor; this only hands it to the receive action.
        if (file == null) {
            return;
        }
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
        new ReceiveRcsMediaAction(file, durableFileUri, durableThumbUri).start();
    }

    @Override
    public void onIncomingReaction(final int subId, final String targetMessageId,
            final String fromUri, final String emoji, final boolean add,
            @Nullable final String groupId) {
        // Only the reaction's effect is stored, in rcs_reactions keyed by the target's wire id;
        // group reactions aggregate per emoji.
        if (TextUtils.isEmpty(targetMessageId) || TextUtils.isEmpty(emoji)) {
            return;
        }
        new UpdateRcsReactionAction(targetMessageId, fromUri, emoji, add,
                System.currentTimeMillis()).start();
    }

    @Override
    public void onIncomingBotMessage(final RcsIncomingBotMessage msg) {
        // The raw GSMA JSON goes to the receive action; no receipt is sent to a bot.
        if (msg == null) {
            return;
        }
        new ReceiveRcsBotMessageAction(msg).start();
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
        UpdateRcsMessageStatusAction.forStatus(messageId, status, e2eeSchemeId, source).start();
    }

    @Override
    public void onImdnReceipt(final int subId, final String messageId, final int imdnType) {
        new UpdateRcsMessageStatusAction(
                messageId, UpdateRcsMessageStatusAction.KIND_IMDN, imdnType,
                System.currentTimeMillis())
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
            final java.util.List<String> affectedMembers) {
        new ReceiveRcsGroupEventAction(subId, op, groupId, name, conferenceUri,
                requester,
                members != null ? new java.util.ArrayList<>(members)
                                : new java.util.ArrayList<String>(),
                affectedMembers != null ? new java.util.ArrayList<>(affectedMembers)
                                        : new java.util.ArrayList<String>())
                .start();
    }

    @Override
    public void onGroupImdnReceipt(final int subId, final String rcsMessageId,
            final String fromUri, final int imdnType) {
        // Per-member group receipt, sent by the provider alongside every 1:1 receipt; the action
        // does nothing unless the message belongs to a group.
        new UpdateRcsMessageStatusAction(rcsMessageId,
                UpdateRcsMessageStatusAction.KIND_GROUP_IMDN, imdnType,
                System.currentTimeMillis(), fromUri)
                .start();
    }

}
