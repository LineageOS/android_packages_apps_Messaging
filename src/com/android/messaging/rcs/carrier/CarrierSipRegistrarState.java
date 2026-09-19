/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

/**
 * Lifecycle state of {@link CarrierSipRegistrar}, top-level so the host-tested
 * {@link CarrierTransportBridge} can use it without the registrar's Android dependencies.
 * Transitions: see docs/rcs/carrier-transport.md.
 */
public enum CarrierSipRegistrarState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    FAILED
}
