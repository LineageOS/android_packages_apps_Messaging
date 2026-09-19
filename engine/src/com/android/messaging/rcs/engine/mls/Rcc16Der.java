/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.messaging.rcs.engine.mls;

import java.nio.charset.StandardCharsets;

/**
 * Public access to the native RCC.16 credential DER encoder, the same one the native validator
 * checks against, so a layout error fails both ways. Private-key operations stay in Java: callers
 * sign {@link #tbsParticipantInfo} themselves. A native refusal throws
 * {@link IllegalStateException} rather than returning an empty value a server might accept.
 */
public final class Rcc16Der {

    private Rcc16Der() {}

    private static byte[] require(final byte[] v, final String what) {
        if (v == null || v.length == 0) {
            throw new IllegalStateException("RCC.16 encoder refused to build " + what
                    + " (native returned " + (v == null ? "null" : "empty") + ")");
        }
        return v;
    }

    private static void requireNative() {
        if (!OpenMlsNative.available()) {
            throw new IllegalStateException(
                    "libmlsopenmlsbridge is not loaded; cannot build RCC.16 credential DER");
        }
    }

    /** {@code Name} with one {@code CN=<cn>} as UTF8String; some KDSs reject PrintableString. */
    public static byte[] subject(final String cn) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16SubjectDer(cn.getBytes(StandardCharsets.UTF_8)),
                "subject");
    }

    /** {@code GeneralNames} with one URI, e.g. {@code tel:+15551234567}. */
    public static byte[] san(final String uri) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16SanDer(uri.getBytes(StandardCharsets.UTF_8)),
                "san");
    }

    /**
     * The {@code Validity} of {@code notBefore}, {@code notAfter} in unix seconds: UTCTime through
     * 2049, GeneralizedTime from 2050 (RFC 5280 §4.1.2.5).
     */
    public static byte[] validity(final long notBeforeUnixSec, final long notAfterUnixSec) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16ValidityDer(notBeforeUnixSec, notAfterUnixSec),
                "validity");
    }

    /**
     * The {@code tbsParticipantInfo} bytes to sign with the participant key. {@code subject},
     * {@code leafSpki} and {@code san} come from the leaf, {@code vendorId} and {@code validity}
     * from the extension; a wrong split still encodes, and only a signature check catches it.
     */
    public static byte[] tbsParticipantInfo(final byte[] subject, final long vendorId,
            final byte[] validity, final byte[] leafSpki, final byte[] san) {
        requireNative();
        return require(
                OpenMlsNative.nativeRcc16TbsDer(subject, vendorId, validity, leafSpki, san), "tbs");
    }

    /**
     * The {@code .4 ParticipantInformation} extension around {@code popSig}, a DER-encoded EC
     * signature over {@link #tbsParticipantInfo}. {@code participantKeyRolls} is never emitted: it
     * is {@code SIZE(1..5)}, and the validator refuses any roll because verifying one (RCC.16
     * A.4.1.1) is not implemented.
     */
    public static byte[] participantInfoExtension(final long vendorId, final byte[] validity,
            final byte[] popSig, final byte[] participantSpki) {
        requireNative();
        return require(
                OpenMlsNative.nativeRcc16Ext4Der(vendorId, validity, popSig, participantSpki),
                "ext4");
    }

    // ---- Self-test PKI: throwaway chains for driving the engine without a KDS. Not a CA; a real
    // leaf comes from the KDS.

    /** A CA certificate's TBS; for a root pass {@code issuer == subject} and {@code aki == ski}. */
    public static byte[] caTbs(final byte[] issuer, final byte[] subject, final byte[] spki,
            final byte[] serial, final long notBeforeUnixSec, final long notAfterUnixSec,
            final byte[] ski, final byte[] aki, final long vendorId) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16TbsCa(issuer, subject, spki, serial,
                notBeforeUnixSec, notAfterUnixSec, ski, aki, vendorId), "CA TBS");
    }

    /**
     * A client leaf's TBS per the RCC.16 A.3.8 profile. {@code san} and {@code ext4} are copied as
     * given, since the {@code .4} proof-of-possession covers the client's own encoding of them.
     */
    public static byte[] leafTbs(final byte[] issuer, final byte[] subject, final byte[] spki,
            final byte[] serial, final long notBeforeUnixSec, final long notAfterUnixSec,
            final byte[] ski, final byte[] aki, final byte[] san, final byte[] ext4,
            final long vendorId) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16TbsLeaf(issuer, subject, spki, serial,
                notBeforeUnixSec, notAfterUnixSec, ski, aki, san, ext4, vendorId), "leaf TBS");
    }

    /** Assemble a certificate from its TBS and a signature over exactly those TBS bytes. */
    public static byte[] certificate(final byte[] tbs, final byte[] signature) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16Certificate(tbs, signature), "certificate");
    }

    /**
     * CA {@code Name} {@code O=<org>, CN=<cn>}, both PrintableString, matching the existing fixture
     * chain (unlike {@link #subject}).
     */
    public static byte[] caName(final String org, final String cn) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16CaNameDer(
                org.getBytes(StandardCharsets.UTF_8), cn.getBytes(StandardCharsets.UTF_8)),
                "CA name");
    }

    /**
     * Flips one bit inside {@code s} of a {@code .4} proof-of-possession signature, so a test
     * fixture still parses and is rejected by the signature check itself.
     */
    public static byte[] corruptPopSignature(final byte[] ext4) {
        requireNative();
        return require(OpenMlsNative.nativeRcc16CorruptPop(ext4), "corrupted .4");
    }
}
