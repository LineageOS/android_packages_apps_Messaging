/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/** An MLS identity: the KDS-issued X.509 credential and the certified subject key. */
public final class MlsIdentity {
    public final String e164;
    public final byte[] leafDer;          // the KDS-issued leaf cert (DER)
    public final List<byte[]> chainDer;   // CA chain, leaf-first order excluded (ICA...)
    public final byte[] subjectPriv;      // raw 32-byte P-256 scalar (the certified key)
    public final byte[] subjectPub;       // raw 65-byte SEC1 point
    public final List<byte[]> roots;      // trust anchors (DER)
    /**
     * Revoked intermediate-CA certificate serial numbers, pushed by the host; normally empty, never
     * null. RCC.16 leaf certificates are non-revocable, so this list is the only revocation lever.
     */
    public final List<byte[]> revokedSerials;

    public MlsIdentity(String e164, byte[] leafDer, List<byte[]> chainDer,
            byte[] subjectPriv, byte[] subjectPub, List<byte[]> roots) {
        this(e164, leafDer, chainDer, subjectPriv, subjectPub, roots, null);
    }

    public MlsIdentity(String e164, byte[] leafDer, List<byte[]> chainDer,
            byte[] subjectPriv, byte[] subjectPub, List<byte[]> roots,
            List<byte[]> revokedSerials) {
        this.e164 = e164;
        this.leafDer = leafDer;
        this.chainDer = chainDer;
        this.subjectPriv = subjectPriv;
        this.subjectPub = subjectPub;
        this.roots = roots;
        this.revokedSerials = revokedSerials == null
                ? java.util.Collections.emptyList() : revokedSerials;
    }
}
