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
package com.android.messaging.rcs.carrier;

/**
 * WHICH NETWORK PLANE the carrier SIP registrar targets, and therefore where its P-CSCF comes from
 *.
 *
 * <p>This is a DIFFERENT AXIS from {@link CarrierImsMode}. That enum answers "which IMS stack do we
 * drive" — SR (a framework {@code SipDelegate}) or DR (our own JAIN-SIP). This one answers "which
 * bearer do we register over, and who is authoritative about the P-CSCF". A DR registrar can
 * legitimately run on either plane, so folding the two together would make one unrepresentable.
 *
 * <p><b>Why a profile and not a fix.</b> {@link #LAB_IMS_PDN} is today's behaviour and is plausibly
 * CORRECT for the carrier / open5gs lab, where there really is an IMS PDN and PCO really does signal
 * a P-CSCF. That lab is the target the RCC.16 completion plan says is the only place full RCC.16 is
 * reachable, so replacing this path to make the other plane work would break it. Both planes
 * are real; the
 * defect was having only one.
 */
public enum CarrierSipPlane {

    /**
     * The IMS/VoLTE bearer: {@code TRANSPORT_CELLULAR} + {@code NET_CAPABILITY_IMS}, with the
     * P-CSCF taken from PCO ({@code LinkProperties.getPcscfServers}) when the PDN signals one.
     *
     * <p>The Google Messages IMS discovery mechanism, and the right plane for the open5gs lab and for a
     * carrier that actually provisions an IMS APN. <b>The DEFAULT, because it is what shipped.</b>
     */
    LAB_IMS_PDN,

    /**
     * The DEFAULT bearer (or Wi-Fi), with the P-CSCF taken from the ACS document.
     *
     * <p><b>A HYPOTHESIS, NOT A MEASUREMENT — do not read the name as "known correct".</b> What the
     * iPhone capture supports is a USERSPACE DIRECT SOCKET (app-layer SIP to a Google public IP)
     * rather than a modem-baseband IMS registration. It does <b>not</b> establish which bearer that
     * socket must egress. An earlier reading here — that {@code P-Access-Network-Info: IEEE-802.11}
     * proves no IMS PDN is involved — is <b>WRONG</b>: VoWiFi/VoWLAN registers IMS over Wi-Fi through
     * an ePDG-tunnelled IMS PDN and carries the identical PANI. {@code pdp_ip0} and
     * {@code telephony.rcs.private} hint the other way, at a dedicated bearer, and our June
     * direct-socket 403s never isolated the bearer from the missing PrivateToken. So the bearer is
     * an OPEN VARIABLE, and a third plane — a default-bearer-over-IMS-PDN variant — may yet be
     * what is needed.
     *
     * <p>What IS settled, and is the half of this that is a correctness fix rather than a
     * capability: <b>under this plane a PCO-signalled address is the WRONG address</b> and must not
     * be consulted, because the ACS document is authoritative here and a stray PCO entry from some
     * other bearer would silently point the registrar at the wrong proxy.
     *
     * <p><b>Chosen, never fallen into.</b> Previously a device with no IMS PDN reached the
     * default bearer anyway — through {@code acquireImsNetwork}'s catch block — while the P-CSCF
     * accessor still returned whatever PCO had left behind. That is a path reached by FAILURE that
     * reports like a path reached by DESIGN, and an attempt on this plane could appear to
     * half-work through an
     * error handler. This enum exists so the default bearer is a decision.
     */
    CARRIER_DEFAULT_BEARER;

    /** {@code debug.rcs.sip_plane}: {@code lab} (default) | {@code carrier}. */
    public static final String SYSPROP = "debug.rcs.sip_plane";

    /** True when this plane binds the cellular IMS PDN. */
    public boolean usesImsPdn() {
        return this == LAB_IMS_PDN;
    }

    /**
     * True when a PCO-signalled P-CSCF is authoritative. On {@link #CARRIER_DEFAULT_BEARER} it is not:
     * the ACS document is, and a stray PCO address would silently point the registrar at the wrong
     * proxy.
     */
    public boolean pcoPcscfIsAuthoritative() {
        return this == LAB_IMS_PDN;
    }

    /**
     * Parse a configured plane name. Defaults to {@link #LAB_IMS_PDN} — the shipped behaviour —
     * because changing what a device does by default is out of scope here; <b>an
     * unrecognised value also falls back to it rather than to the newer path</b>, so a typo cannot
     * silently move a device onto a plane nobody chose.
     *
     * <p>Deliberately PURE: no {@code SystemProperties}, no {@code Context}. The sysprop read lives
     * at the one call site that already owns Android, so the DECISION this method makes is
     * host-testable while the plumbing that feeds it is not. Putting the read in here would have
     * made the default-on-typo rule an assertion in a comment rather than a test.
     */
    public static CarrierSipPlane fromString(final String value) {
        return value != null && "carrier".equalsIgnoreCase(value.trim())
                ? CARRIER_DEFAULT_BEARER : LAB_IMS_PDN;
    }
}
