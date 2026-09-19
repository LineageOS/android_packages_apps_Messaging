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
package com.android.messaging.rcs.e2ee;

import android.os.Build;
import android.os.SystemProperties;
import android.text.TextUtils;

import org.lineageos.rcs.provider.RcsE2eeInfo;
import com.android.messaging.Factory;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.util.LogUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The send-path front door to {@link E2eeSchemeGate}: a process
 * singleton that wires the gate to its three live seams and resolves the outbound
 * E2EE scheme for a 1-1 conversation. The send path ({@code InsertNewMessageAction})
 * calls {@link #resolveForSend} on the DataModel action thread, then routes:
 *
 * <ul>
 *   <li>{@link RcsE2eeScheme#MLS} → {@code transport.sendMlsMessage(...)} (the app
 *       carries the MLS ciphertext over its own CPM/MSRP transport);</li>
 *   <li>{@link RcsE2eeScheme#ETOUFFEE} → the plaintext {@code sendMessage} path (the
 *       <i>provider</i> encrypts transparently), tagging the row so the padlock lights;</li>
 *   <li>{@code null} → plaintext RCS.</li>
 * </ul>
 *
 * <p>Seams:
 * <ul>
 *   <li><b>Etouffee availability</b> — the provider's cached {@link RcsE2eeInfo}
 *       ({@code available && schemeId == google.etouffee}).</li>
 *   <li><b>MLS provisioning</b> — the active transport's
 *       {@link RcsTransport#isMlsReady(int)} (carrier-IMS attached ⇒ enrolled + KPs
 *       published). Group-id is bound lazily to the conversation (the carrier
 *       transport establishes/reuses the real 32-byte group at send time).</li>
 *   <li><b>Bits store</b> — {@link ConversationBitsStore} (durable, latching).</li>
 * </ul>
 */
public final class E2eeSendGate {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;

    /**
     * eng/userdebug lab override: synthesize an MLS-capable peer when the RCS
     * capability exchange doesn't advertise the {@code +g.gsma.rcs.mls.*} tags. The
     * open5gs lab runs no capability negotiation, so without this the gate can never
     * select MLS. Off by default; production requires real advertised caps.
     */
    private static final String PROP_ASSUME_PEER_MLS = "debug.rcs.mls.assume_peer_capable";

    private static volatile E2eeSendGate sInstance;

    private final E2eeSchemeGate mGate;

    public static E2eeSendGate get() {
        if (sInstance == null) {
            synchronized (E2eeSendGate.class) {
                if (sInstance == null) {
                    sInstance = new E2eeSendGate();
                }
            }
        }
        return sInstance;
    }

    private E2eeSendGate() {
        mGate = new E2eeSchemeGate(
                new ProviderEtouffeeAvailability(),
                new CarrierMlsProvisioning(),
                new ConversationBitsStore());
    }

    /**
     * Resolve + persist the outbound E2EE scheme for a 1-1 send. Returns
     * {@link RcsE2eeScheme#MLS} / {@link RcsE2eeScheme#ETOUFFEE} / {@code null}
     * (plaintext). Never throws — any failure degrades to plaintext.
     */
    public String resolveForSend(final String conversationId, final int subId,
            final String recipientE164, final boolean isGroup) {
        try {
            final List<MlsCapabilities.PeerCaps> peers =
                    Collections.singletonList(peerCapsFor(subId, recipientE164));
            // Surface WHY an MLS-provisioned peer was refused. MlsCapabilities and
            // E2eeSchemeGate are both deliberately pure (host-testable with synthetic cap-sets), so
            // the logging lives here — the Android-coupled layer — rather than inside the tree.
            // Without this, wave drift downgrades us to Etouffee silently and permanently.
            final String refusal = MlsCapabilities.refusalReason(
                    peers.get(0), RcsE2eeScheme.ourLaunchIteration(), isGroup);
            if (refusal != null) {
                LogUtil.w(TAG, "MLS eligibility refused an mls-kds peer: " + refusal);
            }
            return mGate.selectScheme(conversationId, subId, peers, isGroup);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "E2eeSendGate.resolveForSend failed; sending plaintext", t);
            return RcsE2eeScheme.NONE;
        }
    }

    /** Deliberate MLS downgrade for a conversation (provisioning lost / IMDN reason). */
    public void downgradeMls(final String conversationId) {
        mGate.downgradeMls(conversationId);
    }

    private MlsCapabilities.PeerCaps peerCapsFor(final int subId, final String recipientE164) {
        // Lab override first: on open5gs there is no capability exchange carrying MLS tags at all,
        // so this synthesises a capable peer to let the carrier path be driven end-to-end.
        if (labAssumePeerMlsCapable()) {
            final Map<String, String> tags = new HashMap<>();
            tags.put(RcsE2eeScheme.TAG_MLS_KDS, "2");
            tags.put(RcsE2eeScheme.TAG_MLS_VERSION, RcsE2eeScheme.MLS_VERSION_V1);
            return new MlsCapabilities.PeerCaps(tags);
        }
        // Contract v18: ask the provider for the peer's REAL advertised MLS tags.
        //
        // This replaced a stub that returned parseCaps(null) — i.e. EMPTY — outside the
        // lab override. The effect was that this app had NO peer-capability source whatsoever: the
        // whole standard-first eligibility tree below was fed nothing, every peer failed the mls-kds
        // hard gate, and MLS could never be selected on a real device. It presented as a silent
        // plaintext downgrade with no error, and it masked a SECOND bug (the tree also refuses
        // Google Messages peers in 1:1) that could not even be reached while the caps were empty.
        //
        // The provider has had this data all along via Tachyon LookupRegistered; it simply never
        // crossed the binder.
        try {
            final ProviderTransport pt =
                    ProviderTransport.getInstance(Factory.get().getApplicationContext());
            final Map<String, String> tags = pt.lookupPeerMlsCaps(subId, recipientE164);
            if (tags != null && !tags.isEmpty()) {
                return MlsCapabilities.parseCaps(tags);
            }
            // Empty = NOTHING KNOWN, not "not capable" (transient miss, unbound provider, peer not
            // looked up yet). We still return empty caps — the tree will decline — but the caller
            // logs the distinction so a lookup outage is not mistaken for a capability verdict.
            LogUtil.i(TAG, "peerCapsFor(" + recipientE164 + "): provider returned NO MLS tags "
                    + "(nothing known — not a negative capability assertion)");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "peerCapsFor(" + recipientE164 + "): provider lookup failed", t);
        }
        return MlsCapabilities.parseCaps(null);
    }

    private static boolean labAssumePeerMlsCapable() {
        if (!"eng".equals(Build.TYPE) && !"userdebug".equals(Build.TYPE)) {
            return false;
        }
        try {
            return SystemProperties.getBoolean(PROP_ASSUME_PEER_MLS, false);
        } catch (final Throwable t) {
            return false;
        }
    }

    // ---- seams ----

    /** Etouffee availability from the provider's cached {@link RcsE2eeInfo}. */
    private static final class ProviderEtouffeeAvailability
            implements E2eeSchemeGate.EtouffeeAvailability {
        @Override
        public boolean isEtouffeeAvailable(final int subId) {
            try {
                final ProviderTransport pt =
                        ProviderTransport.getInstance(Factory.get().getApplicationContext());
                final RcsE2eeInfo info = pt.peekE2eeInfo();
                return info != null && info.available
                        && RcsE2eeScheme.ETOUFFEE.equals(info.schemeId);
            } catch (final Throwable t) {
                return false;
            }
        }
    }

    /** MLS provisioning from the active carrier transport + lazy per-conversation group. */
    private static final class CarrierMlsProvisioning
            implements E2eeSchemeGate.MlsProvisioning {
        @Override
        public boolean isMlsProvisioned(final int subId) {
            try {
                final ProviderRegistry registry = ProviderRegistry.peek();
                final RcsTransport t = (registry != null)
                        ? registry.getActiveTransport(subId) : null;
                return t != null && t.isMlsReady(subId);
            } catch (final Throwable th) {
                return false;
            }
        }

        @Override
        public String mlsGroupId(final String conversationId) {
            // App-provided-carrier-MLS is LAZY: the carrier transport establishes or
            // reuses the real 32-byte MLS group keyed by this conversation at send
            // time (MlsCarrierTransport.ensureReady). The invariant ("a
            // message tagged MLS must have a group") is satisfied by the send path
            // itself, so a non-empty conversationId is a valid stable group key here.
            // (Google Messages pre-establishes the Tachyon group; our carrier path defers it.)
            return TextUtils.isEmpty(conversationId) ? null : conversationId;
        }

        @Override
        public String ourLaunchIteration() {
            // This used to return null on the belief that "the GSMA-standard path
            // (mls-version=v1) is our primary, so the same-build fallback is unused" — which is
            // FALSE against real Google peers and disabled MLS with them entirely in 1:1:
            //
            //   Google Messages advertises: mls-kds=2, mls-launch-iteration=1,
            //                                mls-supports-groups=true, and NO mls-version
            //
            // mls-version is gated by a server-side rollout flag on the ADVERTISING side, so
            // un-flagged Google lines never send it, and a newer Google Messages build does
            // not help. With null here the launch-iteration path could never fire, and
            // supports-groups is group-only, so every Google peer failed the 1:1 gate.
            //
            // We DO advertise mls-launch-iteration=1, so claiming it here is self-consistent and is
            // the same path Google Messages uses peer-to-peer. mls-version still covers other peers
            // (Apple advertises it).
            return RcsE2eeScheme.ourLaunchIteration();
        }
    }
}
