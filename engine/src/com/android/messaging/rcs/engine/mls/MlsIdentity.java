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

import java.util.List;

/** Backend-neutral MLS identity: the KDS-issued X.509 credential + the certified subject key. */
public final class MlsIdentity {
    public final String e164;
    public final byte[] leafDer;          // the KDS-issued leaf cert (DER)
    public final List<byte[]> chainDer;   // CA chain, leaf-first order excluded (ICA...)
    public final byte[] subjectPriv;      // raw 32-byte P-256 scalar (the certified key)
    public final byte[] subjectPub;       // raw 65-byte SEC1 point
    public final List<byte[]> roots;      // trust anchors (DER)
    /**
     * Revoked certificate SERIAL NUMBERS, host-pushed — {@code CreateClientRequest} field 7
     * ({@code RevokedCertificates}), rework item {@code 0.9}.
     *
     * <p>Google Messages always sends this message PRESENT and EMPTY, and empty is our normal state too.
     * The list existing is the point: RCC.16 leaf certificates are deliberately non-revocable, so
     * an intermediate-CA serial list is the only revocation lever in the design. With nowhere to
     * put a serial, a compromised ICA keeps every certificate it ever minted valid until expiry.
     *
     * <p>Never null — an absent list and an empty list are the same thing to the engine, and
     * letting null mean "empty" here would make a plumbing bug indistinguishable from the normal
     * state.
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
