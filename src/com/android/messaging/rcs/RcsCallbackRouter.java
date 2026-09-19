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
import com.android.messaging.util.LogUtil;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * The <b>process-wide</b> inbound sink for every RCS transport (design §6.5):
 * an {@link IRcsProviderCallback} that marshals each provider-&gt;app callback
 * onto a DataModel Action (so DB writes leave the binder/callback thread and run
 * on the action-service thread the SMS path already uses).
 *
 * <p>This was formerly a private nested class of {@link ProviderTransport}. It is
 * extracted to a top-level, injectable singleton so <b>all</b> transports feed one
 * router:
 * <ul>
 *   <li>the legacy {@link ProviderTransport} singleton passes it to
 *       {@code IRcsProvider.attach} (unchanged provider path);</li>
 *   <li>each {@link BoundProviderTransport} (external providers) is constructed
 *       with it as its sink via {@link ProviderRegistry#setCallbackSink};</li>
 *   <li>the in-process {@link com.android.messaging.rcs.carrier.CarrierImsTransport}
 *       feeds it <b>directly</b> (no binder round-trip) for carrier-IMS
 *       inbound/registration/provisioning events.</li>
 * </ul>
 * Because it is in-process for the carrier transport, a carrier
 * {@code EVT_INCOMING_MESSAGE} / {@code EVT_REG_STATE} lands on exactly the same
 * DataModel Actions as a provider inbound.
 *
 * <p>It owns the shared cross-transport state that used to live on
 * {@link ProviderTransport}: the {@link RouteSelector} (per-sub reg/prov gate +
 * per-recipient capability cache), the last-known generic E2EE snapshot, and the
 * in-process typing / group-typing listener fan-out. {@link ProviderTransport}
 * now delegates its typing/E2EE/route-selector accessors here so the app's UI call
 * sites are unchanged.
 *
 * <p>Threading: callbacks arrive on a binder thread (external providers) or the
 * carrier events Handler thread (in-process). Every one immediately hands off to a
 * DataModel Action or posts listener fan-out to the main thread, so no heavy work
 * runs on the caller's thread and the router never calls back into a transport
 * while holding a lock.
 */
public final class RcsCallbackRouter extends IRcsProviderCallback.Stub {
    private static final String TAG = LogUtil.BUGLE_TAG;

    private static volatile RcsCallbackRouter sInstance;

    private final Context mAppContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final RouteSelector mRouteSelector = new RouteSelector();

    /** Last-known generic E2EE state (pushed via onE2eeStateChanged); the settings
     *  row reads {@link ProviderTransport#getE2eeInfo} which prefers a fresh binder
     *  call and falls back to this. */
    @Nullable private volatile org.lineageos.rcs.provider.RcsE2eeInfo mE2eeInfo;

    // In-process inbound-typing fan-out. The conversation UI registers a
    // TypingListener; onTyping(...) posts to each registered listener on the main
    // thread. This is the preferred seam over the ACTION_TYPING broadcast (which we
    // keep firing for any out-of-process or legacy listeners). CopyOnWriteArraySet
    // so register/unregister never races the fan-out.
    private final java.util.Set<ProviderTransport.TypingListener> mTypingListeners =
            new java.util.concurrent.CopyOnWriteArraySet<>();

    // WAVE-D: in-process inbound GROUP-typing fan-out (parallel to
    // mTypingListeners). onGroupTyping(...) posts to each.
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

    // ---- generic E2EE cache (contract-v16) ----

    /** Update the cached generic-E2EE snapshot (set from a fresh binder read). */
    void cacheE2eeInfo(@Nullable final org.lineageos.rcs.provider.RcsE2eeInfo info) {
        mE2eeInfo = info;
    }

    /** Last-known generic-E2EE snapshot (cache only), or null until first push/read. */
    @Nullable
    org.lineageos.rcs.provider.RcsE2eeInfo peekE2eeInfo() {
        return mE2eeInfo;
    }

    // ---- typing seam (registration lives here; ProviderTransport delegates) ----

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
        // Typing-indicator master toggle: when off, don't show
        // inbound typing either (the toggle governs both directions).
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
        // Typing-indicator master toggle: suppress inbound group
        // typing display too when the user has turned the indicator off.
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
     * Copy an inbound file blob FD (handed across the binder by the provider) into
     * our own {@link MediaScratchFileProvider} storage and return a durable
     * content URI string the receive Action can persist. The FD is consumed +
     * closed. Returns {@code null} on failure (the Action then falls back to the
     * provider's contentUri, if any). Runs on the binder callback thread — the
     * copy is bounded by {@code MaxSizeFileTransfer} (provider-clamped).
     */
    private static String ingestFdToScratch(final Context ctx,
            final ParcelFileDescriptor pfd, final String mime) {
        final android.net.Uri scratch =
                MediaScratchFileProvider.buildMediaScratchSpaceUri(extensionFor(mime));
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                OutputStream out = ctx.getContentResolver().openOutputStream(scratch)) {
            if (out == null) {
                LogUtil.w(TAG, "onIncomingMedia: could not open scratch output");
                return null;
            }
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return scratch.toString();
        } catch (final Exception e) {
            LogUtil.w(TAG, "onIncomingMedia: FD->scratch ingest failed", e);
            return null;
        }
    }

    /** Best-effort file extension for a MIME (MediaScratch keys files by it). */
    private static String extensionFor(final String mime) {
        final String ext = (mime == null) ? null
                : android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        return TextUtils.isEmpty(ext) ? "dat" : ext;
    }

    // ================================================================
    // IRcsProviderCallback — every provider->app callback marshalled onto a
    // DataModel Action (DB writes off the binder/callback thread).
    // ================================================================

    /**
     * The single funnel from any inbound transport into the message store.
     *
     * <p><b>Contract: {@code msg.contentType} and {@code msg.body} are FINAL.</b>
     * Whoever built this {@link RcsIncomingMessage} owns the unframing, so the type and bytes
     * here are the real inner content and nothing downstream re-derives them.
     * {@link ReceiveRcsMessageAction} used to unframe a second time, which is a no-op for text
     * and destroys anything else: an unframed image has no frame either, so it came back as
     * {@code text/plain} over raw bytes.
     *
     * <p>The carrier leg unframes in {@code CarrierImsService.emitIncoming}, at the point it
     * builds the message.
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
        // Contract-v6 inbound media. The provider has ALREADY downloaded the
        // blob (and EAGERLY the thumbnail, per FtThumbnailSupported) and
        // resolved it to a content:// URI/FD with a READ grant to our package.
        // ZERO transport branching here: we do NOT inspect the MIME, look at
        // any URL, or parse any XML — we hand the resolved descriptor straight
        // to the receive Action, exactly as onIncomingMessage hands text to
        // ReceiveRcsMessageAction. (DB writes happen off the binder thread on
        // the action-service thread.)
        if (file == null) {
            return;
        }
        // The provider hands the file blob as a one-shot FD when it has no
        // grantable content URI. An FD cannot survive the Action queue (the
        // Action is itself parceled/persisted), so ingest it into our OWN
        // MediaScratch storage now and give the Action a durable URI.
        //
        // CRITICAL: this callback runs on a binder thread inside the provider's
        // transaction, so the calling identity is the PROVIDER's uid. Writing
        // our own (non-exported) MediaScratchFileProvider under that identity is
        // denied (SecurityException). Clear the calling identity so the ingest
        // runs as our OWN uid.
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
        // The eager thumbnail likewise crosses as a one-shot FD (the provider
        // has no grantable FileProvider URI); ingest it into MediaScratch too.
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
        // Contract-v9 inbound emoji reaction (tapback). The reaction MESSAGE is
        // hidden (the provider never delivers it via onIncomingMessage); only its
        // effect lands in rcs_reactions, keyed by the TARGET message's rcs
        // message-id (the IMDN id space). 1:1 and group are identical here --
        // group just carries groupId (advisory; the target id alone locates the
        // row) and N members each fire their own reaction, which aggregate by
        // GROUP BY emoji in RcsMessageStore.readReactions.
        if (TextUtils.isEmpty(targetMessageId) || TextUtils.isEmpty(emoji)) {
            return;
        }
        new UpdateRcsReactionAction(targetMessageId, fromUri, emoji, add,
                System.currentTimeMillis()).start();
    }

    @Override
    public void onIncomingBotMessage(final RcsIncomingBotMessage msg) {
        // Contract-v10 inbound RBM (RCS Business Messaging) agent message. The
        // provider carries the raw GSMA JSON UNPARSED (Phase 1); the parser is
        // Phase 2, the rich-card renderer Phase 4. We hand it straight to the
        // receive Action, which lands it in a thread keyed by the bot id and
        // fires NO IMDN (a bot address is not an MSISDN). DB writes happen off
        // the binder thread on the action-service thread.
        if (msg == null) {
            return;
        }
        new ReceiveRcsBotMessageAction(msg).start();
    }

    @Override
    public void onMessageStatus(final int subId, final String messageId, final int status,
            @Nullable final String errorReason) {
        new UpdateRcsMessageStatusAction(
                messageId, UpdateRcsMessageStatusAction.KIND_STATUS, status, 0L)
                .start();
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
        // TODO(P1b follow-up): thread-wide proto-IMDN promotion — mark all
        // outstanding outgoing RCS messages to fromUri delivered/displayed.
        // The per-messageId onImdnReceipt() above covers the common 1-1 case.
        LogUtil.i(LogUtil.BUGLE_TAG, "onImdnReceiptForPeer sub=" + subId
                + " peer=" + fromUri + " type=" + imdnType + " (thread-wide; TODO)");
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
        // Generic E2EE state push (contract-v16). Cache it so the settings row reads
        // it without re-crossing the binder, and broadcast so an open settings screen
        // updates its toggle live. Scheme-blind: we only carry the info through.
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
        // Broadcast to the (provider-side-of-the-main-app) OTP UI. The main
        // app holds the default-SMS role; OtpCatcher will usually feed the
        // OTP back automatically, but a manual-entry UI can also listen.
        final Intent i = new Intent(RcsConstants.ACTION_OTP_REQUIRED)
                .setPackage(mAppContext.getPackageName())
                .putExtra(RcsConstants.EXTRA_SUB_ID, subId)
                .putExtra(RcsConstants.EXTRA_OTP_HINT, hint);
        mAppContext.sendBroadcast(i);
    }

    @Override
    public void onCarrierTosStateChanged(final int subId, final int tosState,
            @Nullable final RcsTosPrompt prompt) {
        // Cache the state + prompt so the settings row and the (re)prompt
        // dialog can read it without re-crossing the binder.
        mRouteSelector.setTosState(subId, tosState, prompt);

        // Only TOS_REQUIRED is user-actionable (a gating ServerMessage that
        // the provider is blocked on). The other states are informational
        // (settings row only) -- the broadcast for them would have nothing
        // to prompt, so we skip it. A live RcsSettingsFragment also listens
        // to ACTION_CARRIER_TOS_REQUIRED for instant refresh, but it polls
        // RouteSelector anyway, so informational states still surface there.
        if (tosState != IRcsProviderCallback.TOS_REQUIRED) {
            return;
        }

        // Mirror the OTP path: package-scoped broadcast carrying the prompt
        // fields. RcsCarrierTosReceiver decides dialog-vs-notification.
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
        // v1: typing indicator is advisory; no DB write. Preferred path is
        // the in-process TypingListener fan-out (main-thread); the legacy
        // package-scoped broadcast is kept for any out-of-process listener.
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
        // WAVE-D: advisory group typing; no DB write. Preferred path is the
        // in-process GroupTypingListener fan-out (the conversation UI drives a
        // per-sender model + multi-name label); the legacy package-scoped
        // broadcast is kept for any out-of-process listener.
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
        // FLOW4b SCAFFOLD ONLY. The full group-conversation UI/DB plumbing
        // (participants-table sync, group thread creation, member-change
        // system messages) is NOT built out -- flagged for review. We hand
        // the event to a stub Action that logs + records the route so the
        // wire path is exercised end-to-end without touching messaging.db.
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
        // WAVE-E: per-member group read receipt. The provider fires this
        // ALONGSIDE the 1:1 onImdnReceipt for every inbound IMDN (the group
        // IMDN is routed to us as a PHONE_NUMBER, so it is indistinguishable
        // from a 1:1 IMDN on the wire). We upsert a per-member row into
        // rcs_group_receipts; the action itself no-ops when rcsMessageId does
        // NOT map to a group conversation (rcs_group_id == null), so the 1:1
        // path (driven by onImdnReceipt) stays byte-unchanged.
        new UpdateRcsMessageStatusAction(rcsMessageId,
                UpdateRcsMessageStatusAction.KIND_GROUP_IMDN, imdnType,
                System.currentTimeMillis(), fromUri)
                .start();
    }

}
