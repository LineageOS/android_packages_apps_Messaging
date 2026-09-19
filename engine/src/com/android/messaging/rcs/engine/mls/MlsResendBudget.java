/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.DeferredResend;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import java.util.Map;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
/**
 * How many RCC.16 §10.3 resends one peer may draw, as a count within a rolling window, and the
 * send-gate that holds 1:1 resends until the peer converges. The window lets a burst age out; a
 * count with no time bound would disable resends in a conversation for good. See
 * docs/mls/health-and-recovery.md.
 */
public final class MlsResendBudget {

    private MlsResendBudget() {}

    /** Resends allowed to one peer, per conversation, within {@link #WINDOW_MS}. */
    public static final int MAX_PER_WINDOW = 2;

    /** The window; one hour, the app's own choice. */
    public static final long WINDOW_MS = 60L * 60L * 1000L;

    /** The oldest timestamp still inside the window ending at {@code nowMs}. */
    public static long windowStart(final long nowMs) {
        return nowMs - WINDOW_MS;
    }

    /**
     * @param resendsInWindow resends already recorded for this peer and conversation in the window
     */
    public static boolean spent(final int resendsInWindow) {
        return resendsInWindow >= MAX_PER_WINDOW;
    }

    /**
     * Named candidates for the recipient-specific input of the §10.3 resend selector MAC, cheapest
     * first; the one that verifies names itself in the log. A wrong candidate cannot produce a
     * false match.
     */
    public static java.util.Map<String, byte[]> resendMacCandidates(final MlsShellPort shell,
            final Group g) {
        final java.util.LinkedHashMap<String, byte[]> c = new java.util.LinkedHashMap<>();
        final String self = shell.selfE164();
        if (!self.isEmpty()) {
            c.put("msisdn-e164", self.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            final String digits = self.startsWith("+") ? self.substring(1) : self;
            c.put("msisdn-digits", digits.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            c.put("msisdn-tel-uri",
                    ("tel:" + self).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        if (g != null && g.groupId != null) {
            c.put("group-id", g.groupId);
            if (!self.isEmpty()) {
                final byte[] sb = self.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                final byte[] both = new byte[g.groupId.length + sb.length];
                System.arraycopy(g.groupId, 0, both, 0, g.groupId.length);
                System.arraycopy(sb, 0, both, g.groupId.length, sb.length);
                c.put("group-id||msisdn", both);
            }
        }
        // Our leaf index is a natural candidate but the engine does not expose it on this path.
        return c;
    }

    /** Parks a resend until the gate opens. True means the remedy is in progress, not failed. */
    public static boolean deferGatedResend(final MlsShellPort shell, final MlsLogSink log,
            final String key, final DeferredResend d) {
        final int waiting;
        final ConvState s = shell.conv(key);
        synchronized (s) {
            if (MlsRecoveryPolicy.gatedResendQueueFull(s.gatedResends.size())) {
                log.w("MlsResendBudget: "
                        + MlsRecoveryPolicy.gatedResendRefusalLine(key, d.originalMessageId));
                return false;
            }
            s.gatedResends.add(d);
            // Read the depth under the lock; ArrayDeque is not thread-safe.
            waiting = s.gatedResends.size();
        }
        log.i("MlsResendBudget: resend of " + MlsMessageId.forLog(d.originalMessageId)
                + " is HELD at the "
                + "send-gate our own rekey just closed — parked (" + waiting + " waiting) and will "
                + "go out when the peer converges. NOT counted against the escalation ladder: it has "
                + "not been attempted yet.");
        return true;
    }

    /** Peer convergence observed: release the gate and drain it. Takes a canonical key. */
    public static void onPeerConverged(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        final ConvState s = shell.convIfAny(key);
        if (s != null) {
            final boolean wasOpen;
            synchronized (s) {
                wasOpen = s.gateOpenedAt > 0L;
                s.gateOpenedAt = 0L;
            }
            if (wasOpen) {
                log.i("MlsResendBudget: convergence ACK → releasing send-gate for "
                        + MlsConversationKey.forLog(key));
            }
        }
        shell.flushGatedResends(key);
    }

    /** Opens a gate after a commit, only on a transport that can ever close one. */
    public static void openGate(final MlsShellPort shell, final MlsLogSink log, final String key) {
        if (!MlsRecoveryPolicy.sendGateApplies(shell.transportProfile().requiresConvergenceAck)) {
            log.i("MlsResendBudget: NOT opening a send-gate — this transport signals "
                    + "no convergence, so nothing could ever close it");
            return;
        }
        final ConvState s = shell.conv(key);
        synchronized (s) { s.gateOpenedAt = Math.max(1L, shell.elapsedRealtime()); }
    }

    /**
     * Whether a send is held pending peer convergence: only on a transport that signals it, and
     * only until {@link MlsRecoveryPolicy#GATE_DEADLINE_MS}. Takes a canonical key, not the app's
     * conversation id.
     */
    public static boolean sendBlockedByGate(final MlsShellPort shell, final MlsLogSink log,
            final String key) {
        if (!MlsRecoveryPolicy.sendGateApplies(shell.transportProfile().requiresConvergenceAck)) {
            return false;
        }
        final ConvState s = shell.convIfAny(key);
        if (s == null) return false;
        final long opened;
        synchronized (s) { opened = s.gateOpenedAt; }
        if (opened <= 0) return false;
        if (MlsRecoveryPolicy.gateExpired(opened, shell.elapsedRealtime())) {
            log.w("MlsResendBudget: send-gate EXPIRED for " + MlsConversationKey.forLog(key)
                    + " — releasing and sending anyway (a stalled conversation is worse)");
            synchronized (s) { s.gateOpenedAt = 0L; }
            // The expiry is the other release point; drain here too so a lost ACK strands nothing.
            shell.flushGatedResends(key);
            return false;
        }
        return true;
    }
}
