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
import com.android.messaging.datamodel.action.ReceiveRcsBotMessageAction;
import com.android.messaging.datamodel.action.ReceiveRcsGroupEventAction;
import com.android.messaging.datamodel.action.ReceiveRcsMediaAction;
import com.android.messaging.datamodel.action.ReceiveRcsMessageAction;
import com.android.messaging.datamodel.action.UpdateRcsMessageStatusAction;
import com.android.messaging.datamodel.action.UpdateRcsReactionAction;
import com.android.messaging.Factory;
import com.android.messaging.rcs.GroupIconApplier;
import com.android.messaging.rcs.e2ee.MlsProviderTransport;
import com.android.messaging.rcs.e2ee.MlsSubjectApplier;
import com.android.messaging.rcs.e2ee.RccMlsBody;
import com.android.messaging.rcs.engine.mls.RccContentDisposition;
import com.android.messaging.rcs.engine.mls.RccCpimReaction;
import com.android.messaging.rcs.engine.mls.RccGroupMetadataKeys;
import com.android.messaging.rcs.engine.mls.RccFileInfo;
import com.android.messaging.rcs.e2ee.RcsE2eeScheme;
import com.android.messaging.rcs.engine.mls.MlsInboundRefusal;
import com.android.messaging.rcs.engine.mls.MlsHeaderGate;
import com.android.messaging.rcs.engine.mls.MlsTrace;
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
public final class RcsCallbackRouter extends IRcsProviderCallback.Stub
        implements MlsProviderTransport.PendingAppReplay {
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
        // Wire the §10.8 application-plane replay door as part of BECOMING the sink, not from some
        // later init step. A parked message that reaches its moment with no door installed is lost
        // with nothing to fall back to, so the window in which that can happen has to be zero.
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

    /** How long one peer report suppresses an identical repeat. See {@link #claimNegativeDelivery}. */
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
     * Claim one peer failure report, so a duplicate dispatch runs the remedy once.
     *
     * <p>Time-boxed rather than permanent: a peer that reports the same id again much later is
     * telling us something new (its state regressed), and that deserves a fresh remedy. Within
     * {@link #NEGATIVE_DELIVERY_DEDUPE_MS} it is the same event arriving twice.
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
     * Message ids currently being inserted, or inserted recently — the duplicate-insert claim.
     *
     * <p>Bounded and self-trimming: a duplicate dispatch arrives milliseconds after the first
     * (measured: 4 ms), so the window that matters is tiny, and an unbounded set keyed by every
     * message id we ever received would be a slow leak fed by strangers.
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
     * Claim {@code mid} for insertion. Returns false if another dispatch already holds it.
     *
     * <p>Atomic, because the DB existence check alone is a TOCTOU: two concurrent dispatches of one
     * message both look, both see nothing, and both insert. That is exactly what produced two rows
     * for one Google Messages message on 2026-08-15.
     *
     * <p>Deliberately never released. A claim is "this id has been handled", not "this id is in
     * flight" — releasing it on completion would reopen the window for a redelivery arriving just
     * after the first insert finished. The LRU bound is what keeps that safe.
     */
    private boolean claimInboundInsert(final String mid) {
        if (mid == null || mid.isEmpty()) return true;   // nothing to key on; do not block delivery
        synchronized (mInsertClaims) {
            return mInsertClaims.add(mid);
        }
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
     * Whoever built this {@link RcsIncomingMessage} owns RCC.16 unframing, so the type and bytes
     * here are the real inner content — never a still-framed MLS application payload — and nothing
     * downstream re-derives them. {@link ReceiveRcsMessageAction} used to unframe a second time,
     * which is a no-op for text (an unframed text body has no frame, and {@code RccMlsBody.parse}
     * returns a frameless payload verbatim as {@code text/plain}) and destroys anything else: an
     * unframed image has no frame either, so it came back as {@code text/plain} over raw bytes.
     *
     * <p>Both producers comply. This class's MLS leg gets an already-parsed
     * {@code RccMlsBody.Parsed} from {@code MlsProviderTransport.decryptInbound} — it has to, since
     * it routes receipts, keys and reactions on the inner type before it can decide there is a
     * message at all. The carrier leg unframes in {@code CarrierImsService.emitIncoming}, at the
     * point it builds the message.
     */
    @Override
    public void onIncomingMessage(final RcsIncomingMessage msg) {
        if (msg == null) {
            return;
        }
        new ReceiveRcsMessageAction(msg).start();
    }

    /**
     * Inbound MLS control traffic (contract v21).
     *
     * <p>The payload is opaque transport bytes; the app's MLS engine unwraps and applies it. Landing
     * here at all is the inbound half of ownership moving: the provider recognises that an item is
     * control rather than an application message and routes it up, without parsing MLS.
     *
     * <p><b>Not yet wired to the engine.</b> Applying an inbound commit is the recovery-state-machine
     * work that still lives provider-side; until that moves, logging here would be the
     * only effect and silently dropping it would be worse — so this records the arrival explicitly so
     * a control message that reaches the app before the engine does is visible rather than lost.
     */
    /**
     * Inbound MLS control messages applied IN ORDER, on ONE thread (contract v42).
     *
     * <p>This is the ordering guarantee. {@link #onMlsControl} spawns a thread per callback — fine
     * for one message, wrong for a pair that must be applied in sequence. A metadata commit ships
     * the commit AND a key delivery encrypted at the POST-commit epoch, so when the key won the race
     * both failed "future epoch", nothing drained, and the receiver's own next commit was then built
     * at a stale epoch and refused by its engine. Applying the bundle sequentially on a single
     * thread makes order a property of the payload instead of thread scheduling.
     */
    @Override
    public void onMlsControlBundle(final int subId, final String fromE164, final String messageId,
            final byte[] packedMessages, final boolean convergenceAck, final String groupId,
            final long eraId, final byte[] epochAuthenticator, final String originalMessageId) {
        if (packedMessages == null || packedMessages.length == 0) return;
        // STILL REPORT-ONLY ON THIS ARM, DELIBERATELY — read this before "finishing the job".
        //
        // The ciphertext arm below now enforces, because we have measured a Google Messages-SENT application
        // message carrying BOTH required headers. We have NOT measured a Google Messages-sent inbound CONTROL
        // bundle, and the evidence we do have points the other way: Google Messages' receipts carry
        // Era-ID and NO Epoch-Authenticator, which is §12.11 ("Epoch-Authenticator is never emitted
        // on a receipt") and which our own sender honours. So an Era-ID-only message is a legitimate
        // shape on some paths, and MlsHeaderGate rejects it as MISSING_EPOCH_AUTHENTICATOR.
        //
        // Enforcing here on the ciphertext arm's evidence would be borrowing proof from a different
        // message type — the same reasoning error the report-only mode was built to avoid, one arm
        // over.
        //
        // Measured 2026-08-04, AND THE ANSWER IS "NEVER ENFORCE THIS ARM". A rekey against a
        // Google Messages peer drew its convergence ACK back as a control bundle carrying NEITHER
        // header:
        //     onMlsControlBundle: … msgId=mls-rekey-+1571…  era=ABSENT epochAuth=ABSENT
        //     onMlsControlBundle: [1] 78B applied=true
        // and that ACK is the message that RELEASES OUR SEND-GATE. Enforcing here would have dropped
        // it and wedged the conversation — the exact silent, invisible failure report-only mode
        // exists to prevent, caught by one experiment instead of by a field report.
        //
        // CORRECTED 2026-08-07 — THE CLAIM BELOW WAS WRONG, AND STATING IT AS SETTLED PROTECTED IT.
        //
        // What this used to say: "The observation exists and it is settled: Google Messages'
        // control bundles do not carry the RCC.16 headers at all ... Do not flip it."
        //
        // They carry both. A kind=47 bundle is not a CPIM message, and our inbound parser reads
        // Era-ID / Epoch-Authenticator as CPIM HEADER NAMES — so it found nothing on a transport
        // that has no CPIM headers, and we recorded its blindness as Google Messages' omission. Decoded off
        // the wire, the fan-back is an envelope: field 2 = the 32-byte epoch authenticator, field 3
        // = the era. Both present on every message we had called ABSENT. The provider now derives
        // them (MlsMessageTransport.routeOpen[control]).
        //
        // THE MARKER IS THE LESSON. "Do not flip it" survived three days and several passes over
        // this file, and it did not merely record the wrong conclusion — it discouraged the check
        // that would have overturned it. A hardening annotation is only safe on a claim whose
        // FALSIFIER is written next to it.
        //
        // FALSIFIER FOR THE CLAIM THAT REPLACES IT: this arm stays report-only as long as a control
        // bundle can legitimately arrive carrying NO era and NO epoch authenticator. Find one that
        // does — after the envelope derivation above, not before it — and enforcement becomes
        // arguable. The 2026-08-04 rekey ACK is NOT such a case; it was measured
        // through the blind parser.
        //
        // The DECISION is unchanged and its reason is untouched: the send-gate-releasing convergence
        // ACK travels this path, and dropping it wedges the conversation. Only the evidence was
        // wrong.
        //
        // ────────────────────────────────────────────────────────────────────────────────────────
        // RE-ASKED 2026-09-08, AFTER THE DERIVATION WAS FIXED. STILL NO.
        //
        // Fixing the derivation removed the reason report-only was ARGUED for and did not remove
        // the reason it is RIGHT. The derivation was reading the era and the authenticator one
        // protobuf level too high on every kind=5 control — an ApplyMlsControlMessage sits at field
        // 2 of the GroupEvent there, not at the top — so it reported era=ABSENT and a 275B/9480B
        // "authenticator". Seven
        // real kind=5 envelopes now say both values were on the wire the whole time, 32B and era>0
        // on 7 of 7. On THAT shape there is nothing left to tolerate.
        //
        // But this arm does not see one shape, it sees THREE, and they do not reach it equally:
        //
        //   kind=5  GROUP                    -> ApplyMlsControlMessage at GroupEvent.f2   7/7 decorated
        //   kind=47 SERVER_MLS_CONTROL       -> pm.rawInner IS the ApplyMlsControlMessage  ONE sample (2026-08-07, 104B)
        //   message/mls-rcs-server body      -> a CPIM envelope on kind=11/24/36           ZERO captured bytes
        //
        // THE THIRD PLANE IS THE ANSWER, and it is not a hypothetical. That envelope is
        // {f1 = the CPIM message, f2 = REPEATED header entries} with NO varint at f3, so
        // MlsControlEnvelope.locate() declines to identify a control envelope in it BY CONSTRUCTION
        // and the era/authenticator can only come from CPIM headers — which nobody has ever
        // observed Google Messages stamp on a CONTROL body. We know it omits Epoch-Authenticator on a
        // RECEIPT by spec (§12.11), and MlsHeaderGate rejects Era-ID-only as
        // MISSING_EPOCH_AUTHENTICATOR. Enforcing here would drop every control message on a plane
        // we have not one byte of evidence about, on the strength of measurements taken from a
        // different plane. That is the same borrowed-proof error the paragraph above this one
        // already names, one plane over instead of one arm over.
        //
        // AND THE SMALL-FRAME CASE IS UNRESOLVED. The gate release runs on
        // `ack = wire.length <= 96` in the provider, and a wire that small CANNOT be an
        // ApplyMlsControlMessage carrying any of the ACK payloads we have measured (66B/103B/106B
        // + ~38B of f2/f3 framing = 104B/141B/144B). So the frame that releases the send gate is
        // undecorated by arithmetic, and locate() returns NOT FOUND on it — which is precisely "a
        // control bundle legitimately carrying NO era and NO epoch authenticator". That is log
        // archaeology, not a capture, and the asymmetry is exactly the wrong side to guess on.
        //
        // WHAT A CLOSER READ OF GOOGLE MESSAGES MOVES — TWO THINGS, AND NOT THE DECISION.
        //
        // Moved (1): ENFORCEMENT IS GOOGLE MESSAGES' BEHAVIOUR, on both planes, so this is the
        // right end state rather than an optional hardening. Its single-part incoming chat-message
        // processor drops on a missing Era-ID and then on a missing Epoch-Authenticator, and its
        // CPIM header holder makes the pair jointly mandatory BY CONSTRUCTION — it throws
        // IllegalStateException("CPIM headers should contain all required MLS headers.") if either
        // is absent. On the outbound side it sets both from ONE call on the engine's own outgoing
        // result, with only Original-Message-ID conditional. There is no path through Google
        // Messages' builder that produces one without the other.
        //
        // Moved (2): THEIR ENFORCEMENT IS SCOPED AND OURS IS NOT, which is the shape of what
        // still has to be built. That processor runs ONLY for message/mls and
        // message/mls-rcs-server; anything else takes "Received a message with unknown content
        // type %s. Drops it." Notably message/mls-rcs-server-kick is NOT in that set — a
        // kick takes a different processor and is not subject to the pair requirement at all. So a
        // content-type-BLIND guard is not a faithful port of Google Messages', it is a stricter one, and
        // this arm has no content type on two of its three planes.
        //
        // Checked, because it decides whether the self-leave is a header bug or a content-type bug:
        // IT IS NEITHER. Our self-leave goes out as a Groups/KickGroupUsers gRPC call with the MLS
        // half in KickGroupUsersRequest.mls_control_message (field 9) — no CPIM envelope, no
        // content type, in either direction. Neither Google Messages' CPIM builder nor its
        // header-checking processor applies to it; the server synthesises the decoration into the
        // kind=5 fan-out, where it was measured present 7/7. And message/mls-rcs-server-kick is
        // carrier-leg-only for us and already REJECTED as a
        // dispatcher input (MlsContentRoute), so Google Messages' sharp edge does not touch this path.
        //
        // NOT moved: THE PLANE-A RELAY QUESTION, which is not the same as the builder question.
        // The builder proves Google Messages PRODUCES both headers. It does not prove the server
        // RELAYS them back
        // to us — the distinction TachyonRegistrar's own inbound-header logging was added to
        // settle ("Outbound stamping proves we can PRODUCE these headers; it does not prove the
        // server relays them back"). Nor does it move the small-frame case above at all.
        //
        // WHAT WOULD SETTLE IT, and it is now cheap because the provider says it per message.
        // routeOpen[control] prints `kind=`, `ct=` and the located envelope on every control, so a
        // single clean run answers all of it. Enforce when ALL FOUR hold:
        //   1. every control on kind=5 and kind=47 logs a located envelope with era>0 and a 32B
        //      authenticator — zero `control envelope: NOT FOUND` on those two kinds;
        //   2. zero `REFUSED a NNNB epoch authenticator` warnings (that WARN is the tripwire for an
        //      envelope shape we still do not decode);
        //   3. a message/mls-rcs-server inbound control has actually been CAPTURED and RELAYED to
        //      us with both CPIM headers — or, if that plane turns out to be dead, that it is dead.
        //      the builder evidence lowers the risk here a lot but does not answer it: the
        //      question is relay, not production;
        //   4. a convergence ACK and a peer-observed self-leave are both in the run, because those
        //      two are the messages whose loss is silent and unrecoverable.
        // Short of all four, the safe intermediate is to enforce ONLY where the provider positively
        // identified an ApplyMlsControlMessage and stay report-only otherwise — which needs the
        // provider to tell us which it was, i.e. one more field across IRcsProviderCallback. Worth
        // doing when (3) forces the issue; not worth an interface change before then, because the
        // only thing enforcement buys today is dropping a message the engine would reject anyway,
        // and the thing it costs is a member who announced departure staying in the group for ever
        // with nothing logged.
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
                    // The unit the server holds is the MESSAGE, so confirm once for the whole
                    // bundle. In a finally because MlsProviderTransport.get() can throw before the
                    // loop is ever entered, and an unconfirmed message is re-offered every window
                    // until the provider gives up on it.
                    confirmApplied(subId, realId);
                }
            }
        }, "mls-inbound-bundle").start();
    }

    /**
     * Tell the provider an inbound message has been durably applied, so the server may stop
     * redelivering it (contract-v44).
     *
     * <p>Every handler for an MLS-bearing kind (group event, ciphertext, control) MUST reach this
     * on every path, including the failure paths — "we tried and it did not apply" is still a
     * final answer, and the alternative is the message being re-offered every re-offer window
     * until the provider gives up and drops it. Non-MLS kinds are acked by the provider on
     * hand-off and must not call this.
     */
    private void confirmApplied(final int subId, final String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        // TEST HOOK: suppress the apply-confirmation to reproduce "the app received it
        // and never confirmed" on demand.
        //
        // That state is the whole premise of the provider's off-pull re-offer sweep, and it turns
        // out to be HARD TO PRODUCE DELIBERATELY: freezing the app does not do it (verified
        // 2026-08-22 — cgroup.freeze=1, yet the FCM tickle thawed it and it confirmed in 300ms).
        // Without this hook the sweep can only be exercised by catching a real stall, which is
        // exactly the "wait and hope" testing that let the bug live this long.
        //
        // Read live rather than cached: the point is to toggle it between messages.
        if (android.os.SystemProperties.getBoolean("debug.rcs.mls_skip_apply_ack", false)) {
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

    @Override
    public void onMlsControl(final int subId, final String fromE164, final String messageId,
            final byte[] payload, final String groupId) {
        // The provider marks the peer-converged signal by prefixing the id — a transport-level fact,
        // kept out of the MLS payload so the app never has to recognise a backend-shaped frame.
        final boolean ack = messageId != null && messageId.startsWith("ack:");
        final String realId = ack ? messageId.substring(4) : messageId;
        LogUtil.i(TAG, "onMlsControl: sub=" + subId + " from=" + fromE164 + " msgId=" + realId
                + " payload=" + (payload == null ? 0 : payload.length) + "B ack=" + ack);
        // Apply OFF this callback thread — the engine call can block and the provider is waiting.
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
     * How long a failed decrypt waits for an in-flight join before it becomes an FTD.
     *
     * <p>The observed race was 19 ms; this is two orders of magnitude of headroom, and the cost of
     * being generous is nil — the message has ALREADY failed, so the only thing the wait delays is a
     * report we would rather not send. The cost of being too tight is a spurious FTD plus a resend
     * on the first message of a new conversation. Asymmetric, so err long.
     */
    private static final long JOIN_RACE_WINDOW_MS = 3000L;

    /**
     * Inbound MLS APPLICATION ciphertext (contract v25).
     *
     * <p>The provider hands this over undecrypted because the app owns the engine. We decrypt, unframe
     * and insert it through the SAME {@link ReceiveRcsMessageAction} path every other inbound message
     * uses, tagged with the MLS scheme id so the UI shows the encrypted indicator.
     *
     * <p>If decryption fails we insert nothing — the peer must not be told we delivered a message we
     * could not read.
     */
    @Override
    public void onMlsCiphertext(final int subId, final String fromE164, final String messageId,
            final byte[] ciphertext, final String groupId,
            final long eraId, final byte[] epochAuthenticator, final String originalMessageId) {
        LogUtil.i(TAG, "onMlsCiphertext: sub=" + subId + " from=" + fromE164 + " msgId=" + messageId
                + " ct=" + (ciphertext == null ? 0 : ciphertext.length) + "B");
        // Invariant 51: an application message missing the required RCC.16 headers is dropped,
        // SILENTLY from the user's point of view. Dropped before the decrypt is attempted, so a
        // header-less message cannot consume a ratchet step. Enforcing here and NOT on the control
        // arm above is deliberate — see the comment there for what evidence each rests on.
        if (!reportMlsHeaderGate("onMlsCiphertext", messageId, fromE164, eraId, epochAuthenticator,
                originalMessageId, /*enforcing=*/ true)) {
            return;
        }
        // Decrypt OFF this callback thread — the engine call can block and the provider is waiting.
        // The transport's Era-ID header (contract v47) goes with it. Needed on the failure path to
        // tell an era-crossing message from one of our own past: a new era restarts the epoch at 0,
        // so without the era, era N+1 epoch 0 is indistinguishable from a stale message.
        decryptClassifyInsert(subId, fromE164, messageId, ciphertext, groupId, eraId,
                /*replayOfParked=*/ false, originalMessageId);
    }

    /**
     * The application-plane replay door, called by the transport when a PARKED message reaches its
     * exact moment (§10.8 drain).
     *
     * <p>Same pipeline as a punctual message, one step deliberately skipped — see
     * {@code replayOfParked} on the worker below. The RCC.16 header gate is NOT re-run: it passed
     * at admission (the park is only reachable from inside {@link #onMlsCiphertext}, downstream of
     * it), and the headers it inspects were never persisted, so re-running it would fail a message
     * that had already satisfied it.
     */
    @Override
    public void replayParkedMlsCiphertext(final int subId, final String fromE164,
            final String messageId, final byte[] ciphertext, final String groupId,
            final long eraId) {
        LogUtil.i(TAG, "replayParkedMlsCiphertext: " + messageId + " from " + fromE164 + " ("
                + (ciphertext == null ? 0 : ciphertext.length) + "B era=" + eraId
                + ") — its moment arrived; running the ordinary inbound pipeline");
        // NO Original-Message-ID on a replay, and it cannot be recovered: the park stores the
        // ciphertext and the moment, not the CPIM headers. Harmless — it is a CROSS-CHECK on the
        // AAD component, never the decision, so its absence costs one log line's precision and
        // changes no disposition. (The AAD travels WITH the ciphertext, so the authenticated half
        // of the resend statement survives the park intact.)
        decryptClassifyInsert(subId, fromE164, messageId, ciphertext, groupId, eraId,
                /*replayOfParked=*/ true, /*originalMessageId=*/ null);
    }

    /**
     * Decrypt, classify and insert one inbound MLS application ciphertext, off the caller's thread.
     *
     * @param replayOfParked true when this blob was parked by §10.8 and is being replayed at its
     *        moment. It suppresses exactly one thing — the {@code confirmApplied} in the finally —
     *        because the provider was already told this message was handled when it was PARKED, and
     *        a second confirmation for one message id is a claim about a message the provider has
     *        already released.
     * @param originalMessageId the transport's {@code Original-Message-ID} header — Google Messages sets it
     *        ONLY on a §10.3 resend, so it is the PRE-DECRYPT resend recogniser.
     *        It arrived at {@link #onMlsCiphertext} from the very first contract version and was
     *        then DISCARDED here, which is why nothing downstream could tell a resend from an
     *        ordinary message except by parsing the AAD after the fact.
     */
    private void decryptClassifyInsert(final int subId, final String fromE164,
            final String messageId, final byte[] ciphertext, final String groupId,
            final long eraId, final boolean replayOfParked, final String originalMessageId) {
        final int s = subId;
        final String from = fromE164;
        final String mid = messageId;
        final byte[] ct = ciphertext;
        final String gid = groupId;
        final long era = eraId;
        final String origMid = originalMessageId;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    RccMlsBody.Parsed parsed = MlsProviderTransport
                            .get(Factory.get().getApplicationContext(), s)
                            .decryptInbound(from, mid, ct, gid, origMid);
                    if (parsed == null || parsed.body == null) {
                        // THE JOIN RACE, retried before anything irreversible happens.
                        //
                        // A Welcome and the first application message travel together, but reach us
                        // on separate callbacks and decrypt on this thread — so this decrypt can beat
                        // the join that provides its keys. Device-observed by 19 ms on a clean 1:1
                        // establish, i.e. on the FIRST message of a new conversation.
                        //
                        // Everything below this point is a one-way door: the §7.7.2.2 FTD tells the
                        // sender we could not read a message we are about to be able to read, and
                        // provokes a resend. So the cheap check goes FIRST, and it only engages when
                        // we hold no group at all — the one case that cannot be parked, because the
                        // pending queue is keyed by group id.
                        parsed = MlsProviderTransport
                                .get(Factory.get().getApplicationContext(), s)
                                .awaitJoinAndRetryDecrypt(from, mid, ct, gid, JOIN_RACE_WINDOW_MS,
                                        origMid);
                    }
                    if (parsed == null || parsed.body == null) {
                        // RCC.16 §10: a failed decrypt is not a dead end, it is the ENTRY POINT to
                        // recovery. This used to log and return, so the message was lost, the sender
                        // was never told, and nothing tried to repair the group state.
                        //
                        // The ordering is the spec's, not ours: self-heal FIRST (§10.1), and only
                        // report the failure if it PERSISTS (§10.2 sends after self-heal completes,
                        // for the messages that still could not be decrypted).
                        // The CIPHERTEXT and the Era-ID go with it now (rework 6.7, §10.8
                        // conflict 8). A ciphertext that is strictly from the FUTURE must be
                        // PARKED, not reported: §10 recovery plus an FTD for a message we are
                        // about to be able to decrypt provokes a resend of something already in
                        // flight. onDecryptFailure makes that call — it is the only place that can,
                        // because only the moment comparison distinguishes "cannot read yet" from
                        // "cannot read". Everything that is not from the future takes the §10 path
                        // exactly as before.
                        LogUtil.w(TAG, "onMlsCiphertext: " + mid + " not decrypted — entering "
                                + "§10 recovery (self-heal, then report if it persists), unless it "
                                + "is strictly from the future, in which case it is parked");
                        MlsProviderTransport.get(Factory.get().getApplicationContext(), s)
                                .onDecryptFailure(gid, from, mid, ct, era);
                        return;
                    }
                    // A REACTION, WHICH IS NOT A MESSAGE.
                    //
                    // Captured from Google Messages 2026-08-20 and pinned in RccCpimReactionTest: an MLS
                    // reaction is an ORDINARY application message — outer message/mls, inner
                    // text/plain, body = the emoji — and what makes it a reaction rides in the
                    // encrypted CPIM headers:
                    //     NS: n1 <http://www.gsma.com>
                    //     NS: n2 <urn:rcs:message:reactions:>
                    //     n1.Reference-ID: <the message being reacted to>
                    //     n1.Reference-Type: +Reaction        ('+' add, '-' remove)
                    // Nothing OUTSIDE the ciphertext marks it, which is why the transport cannot
                    // route it and why this check has to live here, after the decrypt.
                    //
                    // Before this, RccContentDisposition saw text/plain and the emoji became a chat
                    // bubble instead of attaching to its target — degraded but not garbage, which is
                    // exactly why it survived so long.
                    //
                    // Deliberately checked BEFORE the content-type switch: a reaction IS text/plain,
                    // so the switch cannot tell them apart and would render every one of them.
                    final RccCpimReaction cpim = RccCpimReaction.parse(parsed.body);
                    if (cpim != null && cpim.isReaction()) {
                        final String target = cpim.reactedMessageId();
                        final String emoji = cpim.emoji();
                        if (target == null || target.isEmpty() || emoji.isEmpty()) {
                            // Name it rather than silently dropping: a reaction we cannot attach is
                            // a reaction the user never sees, and it would otherwise look identical
                            // to one that arrived correctly.
                            LogUtil.w(TAG, "onMlsCiphertext: " + mid + " is a REACTION but is "
                                    + "unusable (target=" + target + " emojiLen=" + emoji.length()
                                    + ") — dropping it rather than rendering it as a message");
                            return;
                        }
                        LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is a REACTION "
                                + (cpim.isAdd() ? "ADD" : "REMOVE") + " on " + target
                                + " from " + from + " — routing to the reaction store, not the "
                                + "thread");
                        onIncomingReaction(s, target, from, emoji, cpim.isAdd(), gid);
                        // AND ACK IT. The reaction does NOT go through onIncomingMessage, which is
                        // what normally carries wantsDeliveredImdn=true — so returning here without
                        // emitting the receipt silently stops acknowledging every reaction.
                        //
                        // Caught on the FIRST device run of this change: the three reactions that
                        // took the old (wrong) bubble path were ACKed in 6-9s; the first one routed
                        // here got no receipt at all and the Google Messages sender was left showing it
                        // undelivered. We DID receive and process it, so reporting nothing is a lie
                        // of omission — and a sender that never hears back has no way to
                        // tell "not delivered" from "delivered and unacknowledged".
                        //
                        // IMDN_DELIVERED only: "displayed" is a claim about the user having seen
                        // something, and a reaction is applied to a row rather than shown as one.
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

                    // ONE classifier for the whole inbound path. The transport can no
                    // longer see inside an MLS message, so every type in here has only ever been
                    // routed by this switch — and anything it misses becomes a chat bubble
                    // containing whatever the bytes were.
                    final int disp = RccContentDisposition.classify(parsed.contentType);
                    if (disp == RccContentDisposition.DROP_CONTROL) {
                        // §7.6.2: an MLS-carried IMDN is a RECEIPT, not a message. Validate its
                        // signature and apply it rather than inserting it — and an UNVERIFIED one is
                        // worse than none. Typing indicators land here too and are simply not shown.
                        if (parsed.contentType != null
                                && parsed.contentType.startsWith("message/imdn")) {
                            final MlsProviderTransport t = MlsProviderTransport
                                    .get(Factory.get().getApplicationContext(), s);
                            final MlsProviderTransport.ImdnCheck chk =
                                    t.verifyDecryptedImdn(gid, from, parsed.body);
                            if (chk != null && !chk.ok()) {
                                LogUtil.w(TAG, "onMlsCiphertext: DISCARDING a signed IMDN that failed "
                                        + "validation (" + chk + ") — it is not evidence of delivery");
                                return;
                            }
                            LogUtil.i(TAG, "onMlsCiphertext: MLS IMDN " + mid + " → "
                                    + (chk == null ? "unsigned (applied unverified)"
                                                   : "VERIFIED " + chk));
                            // Fall through: the provider's own IMDN path applies the status.
                        } else if (MlsInboundRefusal.isRefusal(parsed.contentType)) {
                            // A REFUSAL, not signalling and not a failure. The decrypt
                            // path could read this message and decided not to accept it; it comes
                            // back as an internal marker precisely so it lands HERE rather than on
                            // the null branch above, which would self-heal and tell the sender we
                            // could not decrypt something we could. Named separately from ordinary
                            // signalling because "is signalling — not inserted" is the wrong story
                            // for a message that was refused, and the two would otherwise be
                            // indistinguishable in a log.
                            LogUtil.w(TAG, "onMlsCiphertext: " + mid + " from " + from + " was "
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
                        // A KEY, not a message. Its body is binary protobuf, so rendering it shows
                        // raw bytes — and we SEND these ourselves, so the damage would be
                        // self-inflicted.
                        //
                        // BRANCH ON THE CONTENT TYPE. This was a single call to onFileInfo, and a
                        // device test caught what that costs: a §7.13.4 GroupMetadataKeys was handed
                        // to the FileInfo parser, failed, and was dropped as "unparseable FileInfo".
                        // The user-visible outcome was right — nothing rendered — which is precisely
                        // why nobody would notice that the keys inside were being thrown away.
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
                        // An unrouted non-text type. Dropping is deliberate: rendering it is how
                        // binary reaches the conversation. The log line is the only way this
                        // surfaces, so it names the type.
                        LogUtil.w(TAG, "onMlsCiphertext: " + mid + " has UNROUTED contentType="
                                + parsed.contentType + " (" + RccContentDisposition.name(disp)
                                + ", " + parsed.body.length + "B) — dropped, NOT shown. If a peer "
                                + "legitimately sends this, it needs a handler.");
                        return;
                    }
                    // ONE ROW PER MESSAGE ID — the replay guard protects the RATCHET, not the row.
                    //
                    // Observed on a device with a Google Messages sender: the same ciphertext was
                    // dispatched to us TWICE, 4 ms apart. MlsRendezvous correctly stopped the second
                    // decrypt ("was already decrypted — REPLAYING the stored result instead of
                    // advancing the ratchet a second time"), and then BOTH paths inserted, so the
                    // user saw Google Messages' message twice — same sender, same rcs_message_id.
                    //
                    // Two guards because they cover different windows:
                    //  1. an atomic in-memory CLAIM, which is the only thing that closes the race
                    //     between two concurrent dispatches (the DB check alone is a TOCTOU: both
                    //     threads look, both see nothing, both insert);
                    //  2. a DURABLE check, because the claim set does not survive process death and
                    //     a redelivery after a restart would otherwise duplicate.
                    // Same shape as the provider's inFlight claim set, for the same
                    // reason and at the layer that actually owns the row.
                    if (!claimInboundInsert(mid)) {
                        LogUtil.i(TAG, "onMlsCiphertext: " + mid + " is already being inserted by "
                                + "another dispatch — dropping this duplicate rather than writing a "
                                + "second row for one message");
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
                        // A store read must never block delivery. Inserting a possible duplicate is
                        // strictly better than dropping a message we cannot prove we already have.
                        LogUtil.w(TAG, "onMlsCiphertext: duplicate check failed for " + mid
                                + " — inserting anyway", dupCheck);
                    }
                    // parsed.contentType / parsed.body — the UNFRAMED content, which is what every
                    // branch above has already routed on. This is the producer, so it hands down
                    // final content and nothing below unframes again (see the contract
                    // on onIncomingMessage). Handing the raw framed payload down instead would
                    // make image/jpeg something the Action re-derives from bytes this method has
                    // already decoded.
                    onIncomingMessage(new RcsIncomingMessage(s, mid, from, parsed.contentType,
                            parsed.body, /*serverTimestampUsec=*/ 0L, /*wantsDeliveredImdn=*/ true,
                            /*wantsDisplayedImdn=*/ true, gid, RcsE2eeScheme.MLS));
                    // DELETE ON CHAT-ROW INSERT (rework 6.4, invariant 52). The message is durable
                    // now, so there is nothing left to replay and the rendezvous rows are dead
                    // weight. Deliberately BROADER than the read — it drops every stage and every
                    // sender-attribution of this id, because a narrower delete looks correct, passes
                    // every duplicate test, and leaks a row per message forever.
                    final int dropped = MlsProviderTransport
                            .get(Factory.get().getApplicationContext(), s)
                            .forgetRendezvous(mid);
                    LogUtil.i(TAG, "onMlsCiphertext: inserted " + mid + " ("
                            + parsed.body.length + "B " + parsed.contentType + ")"
                            + (dropped > 0 ? " — released " + dropped + " rendezvous row(s)" : ""));
                } catch (final Throwable t) {
                    LogUtil.w(TAG, "onMlsCiphertext: decrypt/insert threw", t);
                } finally {
                    // FINALLY, not at the end of the happy path. This body returns early for an
                    // undecryptable message (which enters §10 recovery), for a signed IMDN, and
                    // for an unrouted content type — all of them FINAL answers for this message.
                    // Re-offering it would not change any of those outcomes, so confirm and let
                    // the server release it. Confirming only on success would have left every
                    // recovery-path message looping until the provider dropped it.
                    //
                    // ONCE PER MESSAGE ID, though: a parked message was already confirmed when it
                    // was parked (the park returns through this same finally), so the replay must
                    // not confirm it a second time.
                    if (!replayOfParked) {
                        confirmApplied(s, mid);
                    }
                }
            }
        }, "mls-inbound-message").start();
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
        // The OTHER terminal state (rework 7.1) — the counterpart to the positive receipt below.
        //
        // STATUS_FAILED is permanent: the provider has stopped trying, so nothing will ever resend
        // these bytes and holding them only consumes one of the cache's 256 slots. Releasing on
        // DELIVERED alone left every permanently-failed message pinned forever, which is the slow
        // path to the cap — and at the cap the cache stops STORING, so retries quietly resume
        // burning a generation each, exactly the failure invariant 62 exists to prevent.
        //
        // Deliberately NOT released on SENT or DELIVERED-as-status: a sent message can still draw a
        // failed-to-decrypt report, and that resend must replay these exact bytes rather than
        // re-encrypt at a new generation (invariant 62).
        if (status == IRcsProviderCallback.STATUS_FAILED) {
            // NAME THE RELEASE. A resend's own body was observed disappearing ~1.4s after it was
            // stored, and the only candidate was this line — but "the only
            // candidate" is exactly the reasoning that sent the last release bug the long way round,
            // so the status now says so itself rather than being inferred from adjacency.
            //
            // AND IT NO LONGER ENDS THE CHAIN. This called releaseSealed, which is the
            // DELIVERED terminal: it drops the whole chain's material and deletes its ledger rows.
            // Correct for a message that arrived, backwards for one that did not — the rows are the
            // only thing that resolves a resend's bare-UUID id back to the root holding the body, so
            // dropping them here orphaned the chain and the next report died on "no stored text for
            // <uuid>" with the body one link away. A failure now releases the failed attempt alone.
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
        new UpdateRcsMessageStatusAction(
                messageId, UpdateRcsMessageStatusAction.KIND_STATUS, status, 0L)
                .start();
    }

    @Override
    public void onImdnReceipt(final int subId, final String messageId, final int imdnType) {
        // RELEASE THE CACHED CIPHERTEXT (rework 7.1) — BUT ONLY FOR A 1:1.
        //
        // A positive delivery receipt is terminal when there is exactly one recipient: the peer has
        // it, nothing is left to resend, and the cached bytes are dead weight. It is NOT terminal
        // for a GROUP, where the receipt names no sender and OTHER members may still fail. (Not
        // "N-1" — that count was a withdrawn inference; what matters here is only that
        // one receipt does not account for the rest of the roster.) Releasing
        // on it destroyed the material a later member's failure report needed, and did so about a
        // second after the send (device-observed on the resendlab group). The transport
        // makes that distinction; do not shortcut back to releaseSealed here.
        //
        // Sending is NOT terminal either, in either shape — a sent message can still draw a
        // failed-to-decrypt report, and that resend has to replay these exact bytes rather than
        // re-encrypt at a new generation.
        //
        // Without a release path the cache only grows: at its cap it starts DECLINING to store, and
        // retries silently resume burning generations, which is the failure the cap's log line warns
        // about. For groups that bound is now the retention window rather than a receipt.
        // NAME THE TERMINAL. This path had no log at all, so "did a receipt arrive for this id?"
        // was unanswerable from a device — and that is the exact question left open when ~20% of one
        // device's sends were found still holding their send material after the messages had
        // demonstrably been delivered. Absence of evidence was
        // being read as evidence, in both directions.
        if (imdnType == IRcsProviderCallback.IMDN_DELIVERED) {
            // SAY WHAT WE ARE ABOUT TO ASK FOR, NOT WHAT WILL HAPPEN. This line used to read
            // "terminal for a 1:1, releasing its send material" — announcing an outcome it does
            // not decide. The 1:1-vs-group judgement is releaseSealedOnPositiveReceipt's, made
            // from three lookups this method does not have, and for a GROUP it KEEPS the material
            // and says so on its own line. So the old wording asserted a release that the very
            // next log line contradicted, and it cost a device session on 2026-09-08: a group
            // send whose id resolved to no group printed "terminal for a 1:1, releasing" and then
            // an orphaned-chain warning, which reads as a live group-resend bug and was not one.
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
    public void onMlsNegativeDelivery(final int subId, final String messageId,
            @Nullable final String fromUri, @Nullable final String groupId,
            final int failureReason) {
        // RCC.16 §7.7.2.2 (contract v43): a peer reports OUR message failed on ITS side. This is the
        // signal that resolves the divergence deadlock — we cannot query peer state, but a diverged
        // peer can tell us, and it just did.
        LogUtil.w(LogUtil.BUGLE_TAG, "onMlsNegativeDelivery sub=" + subId + " mid=" + messageId
                + " from=" + fromUri + " group=" + groupId + " reason=" + failureReason);
        if (fromUri == null || messageId == null) return;
        // ONE REMEDY PER REPORT. The provider can dispatch the SAME peer report twice —
        // measured 3 ms apart on two threads — and each dispatch spawns its own remedy thread, so a
        // single decrypt failure produced TWO §10.3 resends of one message.
        //
        // That is worse than a wasted send. Two resends of one message is exactly what the
        // repeat-resend detector counts, so a duplicate makes a WORKING resend look like a failing
        // one: on 2026-08-15 it escalated to an era advance, which the server refused as
        // INCORRECT_ERA, which
        // tore down a healthy conversation. A duplicate dispatch should cost nothing at all.
        //
        // Keyed on (reporter, reported id, reason) and time-boxed. A LEGITIMATE second report from
        // the same peer for the same original names the RESEND's id, not the original's — resends
        // are minted with new ids — so it does not collide with this key. Only a real duplicate
        // dispatch does.
        if (!claimNegativeDelivery(fromUri, messageId, failureReason)) {
            LogUtil.i(LogUtil.BUGLE_TAG, "onMlsNegativeDelivery: duplicate report of " + messageId
                    + " from " + fromUri + " reason=" + failureReason + " within the dedupe window "
                    + "— ignoring. One report, one remedy.");
            return;
        }
        final int s = subId;
        final String from = fromUri;
        final String mid = messageId;
        final String gid = groupId;
        final int reason = failureReason;
        // Off the callback thread: the remedy performs engine work and server round-trips, and the
        // provider is blocked waiting for this call to return.
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

    /**
     * An ENCRYPTED group subject arrived (RCC.16 §9.7.1.5, contract v39).
     *
     * <p>The provider hands us the ciphertext because it holds no key; the key came earlier, in the
     * §7.8.1 FileInfo carried by the commit's private message, and was stored by
     * {@code onFileInfo}. This is where those two halves finally meet — before it existed the key
     * was stored and never used, and {@code openStoredIconSubject} had no callers at all.
     */
    @Override
    public void onEncryptedGroupSubject(final int subId, @Nullable final String groupId,
            @Nullable final String fromE164, @Nullable final String contentType,
            final byte[] ciphertext) {
        if (groupId == null || ciphertext == null || ciphertext.length == 0) return;
        final Context ctx = Factory.get().getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                // onEncryptedSubject, not openStoredIconSubject: the key may not have arrived yet
                // (the push routinely beats it), in which case it holds the ciphertext and opens it
                // when the FileInfo lands. A null here means "not yet", not "failed".
                final byte[] plain = MlsProviderTransport.get(ctx, subId)
                        .onEncryptedSubject(groupId, fromE164, ciphertext);
                if (plain == null) return;
                final String subject =
                        new String(plain, java.nio.charset.StandardCharsets.UTF_8);
                LogUtil.i(TAG, "RcsCallbackRouter: DECRYPTED group subject for " + groupId
                        + " (" + plain.length + "B) = \"" + subject + "\"");
                // APPLY IT. This line used to be missing, and that absence was the whole
                // defect: the subject decrypted correctly here and was then written to logcat and
                // dropped, so no conversation was ever renamed and no subject ever appeared.
                MlsSubjectApplier.apply(groupId, subject);
            }
        }, "mls-open-subject").start();
    }

    /**
     * An ENCRYPTED group ICON reference arrived (RCC.16 §9.7.1.4, contract v40).
     *
     * <p>Deliberately does NOT yet fetch or decrypt, and now for ONE reason rather than two. The
     * field order is no longer a guess — field 1 is the content type and field 2 the URL, pinned
     * against Google Messages (see the provider's {@code EncryptedProfileFields}) — so what remains is a
     * DOWNLOAD path for the ciphertext, which unlike the subject is not inline. The SEND half went
     * in with the pin; this receive half is the follow-up, and until it lands a peer's icon reaches
     * us as a reference we log and store nothing for.
     */
    @Override
    public void onEncryptedGroupIcon(final int subId, @Nullable final String groupId,
            @Nullable final String fromE164, @Nullable final String first,
            @Nullable final String second) {
        LogUtil.i(TAG, "RcsCallbackRouter: encrypted group ICON for " + groupId + " from "
                + fromE164 + " — contentType(field 1)=" + first + " url(field 2)=" + second
                + " (the provider fetches the ciphertext; onEncryptedGroupIconContent follows if "
                + "the fetch succeeds)");
    }

    /**
     * The DOWNLOADED ciphertext behind that reference (contract v64).
     *
     * <p>The icon twin of {@link #onEncryptedGroupSubject}, and the same shape on purpose: the
     * provider hands us bytes because it holds no key, and the key came earlier in the §7.8.1
     * FileInfo carried by the commit's private message. The only structural difference is upstream
     * of here — a subject is carried inline, an icon behind a URL, so the provider fetched it
     * first.
     *
     * <p>{@code onEncryptedIcon}, not {@code openStoredIconSubject}: the key may not have arrived
     * yet, in which case the transport holds the ciphertext and opens it when the FileInfo lands or
     * the commit applies. A null here means "not yet", not "failed".
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
                // APPLY IT — the line whose absence was the subject's defect. See
                // GroupIconApplier for the two limitations it carries.
                GroupIconApplier.apply(ctx, groupId, plain);
            }
        }, "mls-open-icon").start();
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
        // ATTRIBUTED RELEASE. This callback names the member, so a group message's
        // resend material can be released the moment EVERY member has confirmed instead of sitting
        // out the full 24h retention window. Only a DELIVERED receipt counts: a DISPLAYED one says
        // the user read it, which is not the question — the question is whether anyone can still
        // report a failure-to-decrypt and need those bytes back.
        if (imdnType == IRcsProviderCallback.IMDN_DELIVERED) {
            final int s2 = subId;
            final String mid = rcsMessageId;
            final String who = fromUri;
            // Off the callback thread: this reads the roster under the conversation lock and may
            // touch storage. The callback is oneway and must not block the provider's dispatch.
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
     * Our KDS MLS identity was replaced (contract v35) — re-minted, or a
     * registration-id rotation invalidated it.
     *
     * <p>The KeyPackages we have already published embed the OLD leaf certificate and stay claimable,
     * so until we republish, peers keep adding us on a credential we no longer hold. Nothing surfaces
     * that: the packages upload fine, the claims succeed, and the group is simply rejected later by a
     * peer that checks the leaf. So we republish immediately rather than waiting for the next session
     * start to notice.
     */
    @Override
    public void onMlsIdentityChanged(final int subId, final String reason) {
        LogUtil.i(TAG, "onMlsIdentityChanged: sub=" + subId + " reason=" + reason
                + " — republishing the KeyPackage pool");
        final int s = subId;
        final String why = reason;
        // Off the callback thread: this re-reads the identity over the AIDL and does a KDS upload.
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
     * Rework 6.2 — apply {@link MlsHeaderGate} to the inbound RCC.16 headers. NOW ENFORCING.
     *
     * <h2>What unblocked enforcement, and why it was gated on exactly this</h2>
     *
     * <p>Invariant 51 says a message missing the required headers is dropped SILENTLY, and the
     * carrier CPM/MSRP leg does exactly that. This leg reported instead of dropping for one
     * evidential reason: every inbound MLS message we had ever seen on the Tachygram transport was
     * sent by OUR OWN implementation ({@code ns=true Era-ID=1 Epoch-Authenticator=ABSENT}), so
     * requiring a header Google Messages might omit would have silently dropped every message Google Messages sends
     * — and the drop is, by design, invisible.
     *
     * <p><b>Settled 2026-08-04 on the first working Google Messages-peer conversation</b> (era 2).
     * A Google Messages-SENT inbound MLS message carries both:
     * <pre>
     *   inbound MLS headers from +1571…: ns=true Era-ID=2 Epoch-Authenticator=32B
     *                                    Original-Message-ID=ABSENT
     * </pre>
     * So both required headers are present on Google Messages' own traffic, and
     * {@code Original-Message-ID} is correctly absent on a first send rather than a resend. The
     * premise the report-only mode was waiting on is now measured rather than assumed.
     *
     * <p>The log line is deliberately Google Messages' own ({@link MlsHeaderGate.Verdict#logLine}) so a
     * §20.3 trace diff matches it as a literal — a paraphrase breaks the comparison exactly when a
     * header regression is what you are looking for.
     */
    private static boolean reportMlsHeaderGate(final String arm, final String messageId,
            final String fromE164, final long eraId, final byte[] epochAuthenticator,
            final String originalMessageId, final boolean enforcing) {
        // The plane is the arm. Google Messages emits two DIFFERENT strings for this verdict —
        // "an MLS CONTROL MESSAGE" on the inbox control plane and "an MLS message" on a
        // content-typed body — and we shipped only the second, on both arms. See
        // MlsHeaderGate.Verdict.logLine(boolean).
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
                LogUtil.i(TAG, arm + ": MLS headers OK for " + messageId + " from " + fromE164
                        + " era=" + eraId
                        + (originalMessageId == null ? "" : " RESEND of " + originalMessageId));
                return true;
            }
            // §20.4 #18, verbatim (rework 13.2). TWO vocabularies, and they are not
            // interchangeable: MlsHeaderGate.logLine() is the MESSAGE-level drop (§10.2, "Received
            // an MLS message without Era-ID header." — or the CONTROL-plane wording, see below),
            // while these are the CPIM SPLITTER's, which
            // reports it cannot CONVERT and says nothing about dropping. Google Messages emits both, at
            // different layers, so both are emitted here — with the splitter line chosen by whether
            // the namespace was present at all.
            LogUtil.w(TAG, eraId < 0 && authStr == null
                    ? MlsTrace.noMlsHeaders() : MlsTrace.incompleteMlsHeaders());
            LogUtil.w(TAG, eraId < 0 && authStr == null
                    ? MlsTrace.noMlsHeadersNamespaces(MlsHeaderGate.MLS_NAMESPACE)
                    : MlsTrace.incompleteMlsHeadersFound(
                            (eraId < 0 ? "" : MlsHeaderGate.HDR_ERA_ID)
                            + (authStr == null ? "" : " " + MlsHeaderGate.HDR_EPOCH_AUTHENTICATOR)));
            // SAY WHAT ACTUALLY HAPPENED. This line briefly read "[DROPPED — invariant 51]" on BOTH
            // arms while only one of them acted on the verdict, so the report-only arm logged a drop
            // that never occurred — the precise failure mode this codebase has already paid for once
            // ("a log line asserting damage that did not happen costs exactly as much as one that
            // hides damage that did"). The flag is the caller's real behaviour, not a constant.
            LogUtil.w(TAG, arm + ": " + v.logLine(control)
                    + (enforcing ? " [DROPPED — invariant 51]" : " [REPORT-ONLY — not dropped]")
                    + " msgId=" + messageId + " from=" + fromE164 + " era="
                    + (eraId < 0 ? "ABSENT" : Long.toString(eraId))
                    + " epochAuth=" + (authStr == null ? "ABSENT" : epochAuthenticator.length + "B"));
            return false;
        } catch (final Throwable t) {
            // A GATE MUST NEVER BE THE THING THAT LOSES A MESSAGE. If the check itself throws we
            // ACCEPT: dropping on our own bug would be indistinguishable, to every observer, from
            // the silent drop invariant 51 asks for — and that is precisely the failure this
            // codebase keeps finding the hard way.
            LogUtil.w(TAG, arm + ": MLS header gate threw — ACCEPTING rather than dropping", t);
            return true;
        }
    }

}
