/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.os.SystemProperties;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.rcs.ProviderRegistry;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.RcsTransport;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The send path's entry to {@link E2eeSchemeGate}: a process singleton that wires the gate to the
 * active transport's {@link RcsTransport#isMlsReady(int)} and {@link ConversationBitsStore}.
 * Called on the DataModel action thread. {@link RcsE2eeScheme#MLS} sends through the MLS
 * transport; the provider-plane scheme (the conversation has carried provider-encrypted traffic)
 * and {@code null} send plaintext for the provider to encrypt when it can.
 */
public final class E2eeSendGate {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;

    /**
     * Debug builds only: treat every peer as MLS-capable when no capability exchange carries the
     * MLS tags (as on a test network without one). Off by default.
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
                new CarrierMlsProvisioning(),
                new ConversationBitsStore());
    }

    /**
     * Resolve and persist the outbound scheme. Never throws; any failure resolves to plaintext.
     */
    public String resolveForSend(final String conversationId, final int subId,
            final String recipientE164, final boolean isGroup) {
        try {
            final List<MlsCapabilities.PeerCaps> peers =
                    Collections.singletonList(peerCapsFor(subId, recipientE164));
            // The capability tree is pure, so the refusal is logged here.
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

    /** Deliberate MLS downgrade (provisioning lost, or an IMDN downgrade reason). */
    public void downgradeMls(final String conversationId) {
        mGate.downgradeMls(conversationId);
    }

    private MlsCapabilities.PeerCaps peerCapsFor(final int subId, final String recipientE164) {
        if (debugAssumePeerMlsCapable()) {
            final Map<String, String> tags = new HashMap<>();
            tags.put(RcsE2eeScheme.TAG_MLS_KDS, "2");
            tags.put(RcsE2eeScheme.TAG_MLS_VERSION, RcsE2eeScheme.MLS_VERSION_V1);
            return new MlsCapabilities.PeerCaps(tags);
        }
        // The peer's advertised MLS tags, from the provider.
        try {
            final ProviderTransport pt =
                    ProviderTransport.getInstance(Factory.get().getApplicationContext());
            final Map<String, String> tags = pt.lookupPeerMlsCaps(subId, recipientE164);
            if (tags != null && !tags.isEmpty()) {
                return MlsCapabilities.parseCaps(tags);
            }
            // Empty means nothing is known (a transient miss, an unbound provider, a peer not
            // looked up yet), not "not capable". The tree still declines, but the log keeps the two
            // apart.
            LogUtil.i(TAG, "peerCapsFor(" + LogMask.number(recipientE164)
                    + "): provider returned NO MLS tags "
                    + "(nothing known — not a negative capability assertion)");
        } catch (final Throwable t) {
            LogUtil.w(TAG, "peerCapsFor(" + LogMask.number(recipientE164)
                    + "): provider lookup failed", t);
        }
        return MlsCapabilities.parseCaps(null);
    }

    private static boolean debugAssumePeerMlsCapable() {
        if (!RcsDebug.isDebugBuild()) {
            return false;
        }
        try {
            return SystemProperties.getBoolean(PROP_ASSUME_PEER_MLS, false);
        } catch (final Throwable t) {
            return false;
        }
    }

    /** MLS provisioning from the active transport; the group is bound lazily. */
    private static final class CarrierMlsProvisioning
            implements E2eeSchemeGate.MlsProvisioning {
        private final MlsReupgradeStore mReupgrade = new MlsReupgradeStore(/*config=*/ null);

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
            // The transport establishes or reuses the conversation's MLS group at send time
            // (ensureReady), so the send path itself keeps "a message tagged MLS has a group"; the
            // conversation id stands in as the stable group key here.
            return TextUtils.isEmpty(conversationId) ? null : conversationId;
        }

        @Override
        public boolean mlsDowngraded(final String conversationId) {
            // Set by the downgrade flow together with clearing the bit, and cleared when a
            // re-upgrade brings the conversation back. An unreadable row reads as not downgraded.
            return !TextUtils.isEmpty(conversationId)
                    && mReupgrade.load(conversationId).eagerlyDowngraded;
        }

        @Override
        public String ourLaunchIteration() {
            // Claimed so peers that advertise mls-launch-iteration and not mls-version pass in a
            // 1:1; the provider advertises the same value.
            return RcsE2eeScheme.ourLaunchIteration();
        }
    }
}
