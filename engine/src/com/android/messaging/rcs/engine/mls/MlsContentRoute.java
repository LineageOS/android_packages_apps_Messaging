/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.Locale;

/**
 * Where an inbound MLS body goes, decided by its outer content type; one router for every
 * transport. {@link #isMlsContentType} decides whether a body is MLS at all; {@link #of} routes it
 * and throws on a type with no arm, so a routing defect is never a silent drop.
 */
public enum MlsContentRoute {

    /** An encrypted application payload for the engine, {@code mls-rcs-file-info} included. */
    RAW,

    SERVER,

    /** A peer-originated control body: Welcome, commit, proposal. */
    CONTROL,

    /** A known type that is not a dispatcher input (the host-internal server kick); dropped. */
    REJECTED;

    public static final String CT_MLS = "message/mls";
    public static final String CT_MLS_RCS_CLIENT = "message/mls-rcs-client";
    public static final String CT_MLS_RCS_SERVER = "message/mls-rcs-server";
    public static final String CT_MLS_FT = "message/mls-ft";
    public static final String CT_MLS_RCS_FILE_INFO = "message/mls-rcs-file-info";
    public static final String CT_MLS_RCS_SERVER_KICK = "message/mls-rcs-server-kick";

    /** {@code false} means an ordinary message for the plaintext chain. */
    public static boolean isMlsContentType(final String contentType) {
        final String c = normalize(contentType);
        return CT_MLS.equals(c)
                || CT_MLS_RCS_CLIENT.equals(c)
                || CT_MLS_RCS_SERVER.equals(c)
                || CT_MLS_FT.equals(c)
                || CT_MLS_RCS_FILE_INFO.equals(c)
                || CT_MLS_RCS_SERVER_KICK.equals(c);
    }

    /** Call only when {@link #isMlsContentType} is true; throws for a type with no arm. */
    public static MlsContentRoute of(final String contentType) {
        final String c = normalize(contentType);
        if (CT_MLS.equals(c) || CT_MLS_RCS_FILE_INFO.equals(c) || CT_MLS_FT.equals(c)) return RAW;
        if (CT_MLS_RCS_SERVER.equals(c)) return SERVER;
        if (CT_MLS_RCS_CLIENT.equals(c)) return CONTROL;
        if (CT_MLS_RCS_SERVER_KICK.equals(c)) return REJECTED;
        throw new IllegalStateException("Invalid content type " + contentType);
    }

    /** Strips parameters, trims and lower-cases; content types are case-insensitive (RFC 2045). */
    public static String normalize(final String contentType) {
        if (contentType == null) return "";
        final int semi = contentType.indexOf(';');
        final String base = semi < 0 ? contentType : contentType.substring(0, semi);
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
