/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

/**
 * The opaque E2EE scheme ids carried across the provider AIDL ({@code RcsE2eeInfo.schemeId},
 * {@code RcsIncomingMessage.e2eeSchemeId}). {@link #ETOUFFEE} is a plane the provider owns end to
 * end; the app only reads its availability and per-message tag. See docs/rcs/provider-contract.md.
 */
public final class RcsE2eeScheme {
    private RcsE2eeScheme() {}

    /** The provider-owned plane. A {@code null} scheme id means plaintext. */
    public static final String ETOUFFEE = "google.etouffee";
}
