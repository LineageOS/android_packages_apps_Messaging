/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.carrier;

/**
 * The network plane the DR registrar registers over, and so which P-CSCF address is
 * authoritative. Independent of {@link CarrierImsMode}: a DR registrar can run on either plane.
 * The plane is chosen, never fallen into. See docs/rcs/carrier-transport.md.
 */
public enum CarrierSipPlane {

    /**
     * The IMS bearer ({@code TRANSPORT_CELLULAR} + {@code NET_CAPABILITY_IMS}), with the P-CSCF
     * from PCO when the PDN signals one. The default.
     */
    IMS_PDN,

    /**
     * The default bearer, with the P-CSCF from the configuration document only; a PCO address left
     * by another bearer would point the registrar at the wrong proxy.
     */
    CARRIER_DEFAULT_BEARER;

    /** {@code ims} (default) or {@code carrier}. */
    public static final String SYSPROP = "debug.rcs.sip_plane";

    public boolean usesImsPdn() {
        return this == IMS_PDN;
    }

    public boolean pcoPcscfIsAuthoritative() {
        return this == IMS_PDN;
    }

    /**
     * Parses a configured plane name; null and unrecognised values give {@link #IMS_PDN}, so a
     * typo cannot move a device onto a plane nobody chose, and a device still holding the plane's
     * earlier name keeps it. Pure, so the rule is host-testable.
     */
    public static CarrierSipPlane fromString(final String value) {
        return value != null && "carrier".equalsIgnoreCase(value.trim())
                ? CARRIER_DEFAULT_BEARER : IMS_PDN;
    }
}
