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
package com.android.messaging.rcs.engine.mls;

import java.util.Locale;

/**
 * Where an inbound MLS body goes, decided by its OUTER content type (rework item 6.1, §3.13).
 *
 * <p>One router, because we currently have two and they disagree. The Tachyon leg routes two types
 * and falls THROUGH to the plaintext/IMDN/RBM chain for anything else; the carrier leg accepts a
 * six-type set. So "which content types are MLS?" has two answers depending on which transport the
 * message arrived on, which is the one-contract-two-implementations shape that keeps costing us.
 *
 * <h2>An unknown type THROWS, and that is the point of the item</h2>
 *
 * <p>Today an unrecognised type is a silent ignore at the MLS-dispatcher layer. That collapses two
 * different things into one behaviour — a layer-1 drop ("not for MLS") and a layer-2 routing bug
 * ("for MLS, and we do not know where it goes") — so a routing regression is invisible. Layer 1 is
 * {@link #isMlsContentType}: ask that first, and only bodies that answer yes reach {@link #of},
 * where not knowing the answer is a defect and says so.
 *
 * <h2>Two deliberate divergences, recorded rather than silent</h2>
 *
 * <ul>
 *   <li>{@code message/mls-rcs-server-kick} is <b>host-internal</b> and per the spec never a
 *       dispatcher input, yet it sits in our inbound accept-set. It routes to {@link #REJECTED} here
 *       rather than being quietly accepted, so the accept-set can be narrowed with evidence instead
 *       of by guess.</li>
 *   <li>{@code message/mls-rcs-client} has no arm in the spec's three-way split but is real on our
 *       wire — the peer-originated control leg. It gets {@link #CONTROL}.</li>
 * </ul>
 */
public enum MlsContentRoute {

    /**
     * Hand the bytes to the engine as an encrypted application payload.
     *
     * <p>{@code message/mls-rcs-file-info} joins {@code message/mls} here because the file-info
     * proto arrives ENCRYPTED like any other application body. Note we currently only recognise it
     * as the INNER type of an already-decrypted body, so the outer-type leg is new.
     */
    RAW,

    /** A server-originated control body. */
    SERVER,

    /** A peer-originated control body — Welcome, commit, proposal. */
    CONTROL,

    /**
     * Recognised, but not legitimate as a dispatcher input. Drop it and say why.
     *
     * <p>Distinct from an unknown type: this one we know, and we know it should not have arrived.
     */
    REJECTED;

    public static final String CT_MLS = "message/mls";
    public static final String CT_MLS_RCS_CLIENT = "message/mls-rcs-client";
    public static final String CT_MLS_RCS_SERVER = "message/mls-rcs-server";
    public static final String CT_MLS_FT = "message/mls-ft";
    public static final String CT_MLS_RCS_FILE_INFO = "message/mls-rcs-file-info";
    public static final String CT_MLS_RCS_SERVER_KICK = "message/mls-rcs-server-kick";

    /**
     * LAYER 1 — is this body on the MLS plane at all?
     *
     * <p>A {@code false} here is an ordinary non-MLS message and must fall through to the plaintext
     * chain silently. Keep this question separate from {@link #of}: merging them is what turned a
     * routing bug into a silent drop.
     */
    public static boolean isMlsContentType(final String contentType) {
        final String c = normalize(contentType);
        return CT_MLS.equals(c)
                || CT_MLS_RCS_CLIENT.equals(c)
                || CT_MLS_RCS_SERVER.equals(c)
                || CT_MLS_FT.equals(c)
                || CT_MLS_RCS_FILE_INFO.equals(c)
                || CT_MLS_RCS_SERVER_KICK.equals(c);
    }

    /**
     * LAYER 2 — where does this MLS body go?
     *
     * <p>Call only when {@link #isMlsContentType} is true.
     *
     * @throws IllegalStateException for anything else — a body that reached the MLS dispatcher with
     *         a type the dispatcher has no arm for is a routing defect, not a message to ignore
     */
    public static MlsContentRoute of(final String contentType) {
        final String c = normalize(contentType);
        if (CT_MLS.equals(c) || CT_MLS_RCS_FILE_INFO.equals(c) || CT_MLS_FT.equals(c)) return RAW;
        if (CT_MLS_RCS_SERVER.equals(c)) return SERVER;
        if (CT_MLS_RCS_CLIENT.equals(c)) return CONTROL;
        if (CT_MLS_RCS_SERVER_KICK.equals(c)) return REJECTED;
        throw new IllegalStateException("Invalid content type " + contentType);
    }

    /**
     * Strip parameters, trim, lower-case.
     *
     * <p>{@code "message/mls; charset=utf-8"} is {@code message/mls}: a routing decision that turned
     * on a charset parameter would drop a perfectly good body, and content types are
     * case-insensitive by RFC 2045.
     */
    public static String normalize(final String contentType) {
        if (contentType == null) return "";
        final int semi = contentType.indexOf(';');
        final String base = semi < 0 ? contentType : contentType.substring(0, semi);
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
