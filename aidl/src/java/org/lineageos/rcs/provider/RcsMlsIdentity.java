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
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * The provider's MLS enrolment identity, for one-time adoption by the app at cutover
 *.
 *
 * <p><b>This carries a PRIVATE KEY.</b> {@link #subjectPriv} is the raw P-256 scalar of the
 * <i>certified</i> leaf key. Crossing a process boundary with it is acceptable only because binding
 * {@code RcsProviderService} requires {@code org.lineageos.rcs.permission.BIND_RCS_PROVIDER} at
 * {@code signature|privileged} — a same-signed privileged app. The provider's {@code clientToken} is
 * NOT validated, so that manifest permission is the only control; do not weaken it.
 *
 * <p><b>Why it can be exported at all.</b> The provider stores this key as Base64 PKCS#8 in
 * SharedPreferences — software-backed. Had it been in the AndroidKeyStore it would be non-exportable
 * by design, and "migrate the state" would have been impossible: every live conversation would have
 * had to be re-established, burning an era each. If key storage is ever hardened, this migration path
 * dies with it — re-read this note first.
 *
 * <p><b>Subject key, not participant key.</b> {@code subjectPriv}/{@code subjectPub} MUST be the key
 * the KDS leaf certificate binds ({@code MlsKeyStore.getOrCreateSubject}), NOT the ACS-bound
 * participant key. Mixing them makes every LeafNode signature fail to verify against the credential —
 * a failure that looks like generic MLS breakage rather than a key mix-up.
 *
 * <p>{@link #chainDer} and {@link #roots} are length-prefixed concatenations (4-byte big-endian length
 * per record), the same framing the engine already uses internally, so no new codec is introduced.
 */
public final class RcsMlsIdentity implements Parcelable {

    /** Our MSISDN in E.164 — also the per-identity storage directory key. */
    @Nullable public final String e164;
    /** The KDS-issued leaf certificate (DER). */
    @Nullable public final byte[] leafDer;
    /** CA chain (DER), length-prefixed concatenation, leaf excluded. */
    @Nullable public final byte[] chainDer;
    /** Raw 32-byte P-256 scalar of the CERTIFIED subject key. Private key material. */
    @Nullable public final byte[] subjectPriv;
    /** Raw 65-byte SEC1 point for the same key. */
    @Nullable public final byte[] subjectPub;
    /** Trust anchors (DER), length-prefixed concatenation. */
    @Nullable public final byte[] roots;
    /**
     * Revoked certificate SERIAL NUMBERS, length-prefixed concatenation — the host-pushed
     * {@code RevokedCertificates} list (engine {@code CreateClientRequest} field 7), contract v50,
     * rework item {@code 0.9}.
     *
     * <p><b>Empty is the normal state and is NOT a bug.</b> Google Messages always sends this message
     * present and empty; so do we. What matters is that the slot exists: RCC.16 leaf certificates
     * are deliberately non-revocable, so an intermediate-CA serial list is the ONLY revocation
     * lever in the entire design. Without somewhere to put a serial there is nothing to pull when
     * an ICA is compromised — every certificate it ever minted, for every MSISDN, stays valid
     * until it expires.
     *
     * <p>Shipping it permanently empty is a deployment CHOICE, and it is now a conscious one
     * rather than an inherited absence.
     *
     * <p>A null here means "an older provider that predates v50", which the app treats as empty.
     */
    @Nullable public final byte[] revokedSerials;

    public RcsMlsIdentity(@Nullable String e164, @Nullable byte[] leafDer, @Nullable byte[] chainDer,
            @Nullable byte[] subjectPriv, @Nullable byte[] subjectPub, @Nullable byte[] roots) {
        this(e164, leafDer, chainDer, subjectPriv, subjectPub, roots, null);
    }

    public RcsMlsIdentity(@Nullable String e164, @Nullable byte[] leafDer, @Nullable byte[] chainDer,
            @Nullable byte[] subjectPriv, @Nullable byte[] subjectPub, @Nullable byte[] roots,
            @Nullable byte[] revokedSerials) {
        this.e164 = e164;
        this.leafDer = leafDer;
        this.chainDer = chainDer;
        this.subjectPriv = subjectPriv;
        this.subjectPub = subjectPub;
        this.roots = roots;
        this.revokedSerials = revokedSerials;
    }

    /** True when every field needed to start an engine session is present. */
    public boolean isComplete() {
        return e164 != null && !e164.isEmpty()
                && leafDer != null && leafDer.length > 0
                && subjectPriv != null && subjectPriv.length > 0
                && subjectPub != null && subjectPub.length > 0
                && roots != null && roots.length > 0;
    }

    protected RcsMlsIdentity(Parcel in) {
        e164 = in.readString();
        leafDer = in.createByteArray();
        chainDer = in.createByteArray();
        subjectPriv = in.createByteArray();
        subjectPub = in.createByteArray();
        roots = in.createByteArray();
        // v50. A parcel written by a v49 provider ends here; createByteArray on an exhausted
        // parcel yields null, which isComplete() and the engine both read as "empty list" — the
        // correct degradation, since empty is the normal state anyway.
        revokedSerials = in.createByteArray();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(e164);
        dest.writeByteArray(leafDer);
        dest.writeByteArray(chainDer);
        dest.writeByteArray(subjectPriv);
        dest.writeByteArray(subjectPub);
        dest.writeByteArray(roots);
        dest.writeByteArray(revokedSerials);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RcsMlsIdentity> CREATOR = new Creator<RcsMlsIdentity>() {
        @Override public RcsMlsIdentity createFromParcel(Parcel in) { return new RcsMlsIdentity(in); }
        @Override public RcsMlsIdentity[] newArray(int size) { return new RcsMlsIdentity[size]; }
    };

    /** Never prints key material — only shapes. */
    @Override public String toString() {
        return "RcsMlsIdentity{e164=" + e164
                + " leaf=" + (leafDer == null ? 0 : leafDer.length) + "B"
                + " chain=" + (chainDer == null ? 0 : chainDer.length) + "B"
                + " priv=" + (subjectPriv == null ? 0 : subjectPriv.length) + "B"
                + " pub=" + (subjectPub == null ? 0 : subjectPub.length) + "B"
                + " roots=" + (roots == null ? 0 : roots.length) + "B"
                + " revoked=" + (revokedSerials == null ? 0 : revokedSerials.length) + "B"
                + " complete=" + isComplete() + "}";
    }
}
