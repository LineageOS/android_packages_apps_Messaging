/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

/**
 * Whether a send may conclude that RCS is up, that it is down, or that the state is still
 * arriving. A process started by the send itself (a share, a notification reply) knows nothing
 * until the selected transport reports provisioning and registration, which takes a few hundred
 * milliseconds; routing SMS in that window sends a message the line could have carried over RCS.
 * A send therefore waits, bounded, while the state is settling, and never when RCS is known to be
 * unavailable. Pure Java, so the host tests drive it.
 */
public final class RcsSendReadiness {

    /** Registered and provisioned: send over RCS. */
    public static final int AVAILABLE = 0;
    /** Known not to be up: send SMS now. */
    public static final int UNAVAILABLE = 1;
    /** Not reported yet, or provisioning or registration in progress: wait. */
    public static final int SETTLING = 2;

    /** The longest a send waits for a settling state before it routes SMS. */
    public static final long MAX_WAIT_MS = 3_000L;

    /** How often the wait re-reads the state when no change wakes it. */
    static final long POLL_MS = 50L;

    private RcsSendReadiness() {}

    /**
     * @param rcsEnabled the master toggle
     * @param selecting  a transport is selected for the subscription, a higher-priority one is
     *                   still binding, or selection has not run yet in this process
     * @param up         registered and provisioned
     * @param settling   provisioning not reported yet or in progress, registering, or provisioned
     *                   with registration not reported yet
     */
    public static int classify(final boolean rcsEnabled, final boolean selecting,
            final boolean up, final boolean settling) {
        if (!rcsEnabled) return UNAVAILABLE;
        if (up) return AVAILABLE;
        return selecting && settling ? SETTLING : UNAVAILABLE;
    }

    /** The current readiness of one subscription. */
    public interface Probe {
        int readiness();
    }

    /**
     * Waits up to {@code maxWaitMs} while {@code probe} says {@link #SETTLING}. Returns true once
     * it says {@link #AVAILABLE}, false at once on {@link #UNAVAILABLE} and false when the wait
     * runs out. A state change should {@code notifyAll} on {@code monitor}; without one the probe
     * is re-read every {@link #POLL_MS}.
     */
    public static boolean await(final Probe probe, final Object monitor, final long maxWaitMs) {
        final long deadline = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (true) {
            final int r = probe.readiness();
            if (r == AVAILABLE) return true;
            if (r != SETTLING) return false;
            final long leftMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (leftMs <= 0) return false;
            synchronized (monitor) {
                try {
                    monitor.wait(Math.min(POLL_MS, leftMs));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return probe.readiness() == AVAILABLE;
                }
            }
        }
    }
}
