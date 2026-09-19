/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.rcs.provider;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Nullable;

/**
 * The line's MLS identity as the provider holds it, exported to the app's engine. It carries the
 * private key of the certified subject key (not the configuration-bound participant key; mixing
 * them breaks every LeafNode signature), so the bind permission gating the provider is the only
 * control on it. {@link #chainDer}, {@link #roots} and {@link #revokedSerials} are
 * {@code [u32 BE length][bytes]} records.
 */
public final class RcsMlsIdentity implements Parcelable {

    /** Our number in E.164; also keys the identity's storage. */
    @Nullable public final String e164;
    /** The leaf certificate, DER. */
    @Nullable public final byte[] leafDer;
    /** CA chain, DER, leaf excluded. */
    @Nullable public final byte[] chainDer;
    /** Raw 32-byte P-256 scalar of the certified subject key. */
    @Nullable public final byte[] subjectPriv;
    /** Raw 65-byte SEC1 point for the same key. */
    @Nullable public final byte[] subjectPub;
    /** Trust anchors, DER. */
    @Nullable public final byte[] roots;
    /**
     * Revoked intermediate-CA serial numbers. Empty is the normal state; the slot is the only
     * revocation lever, since leaf certificates are not revocable. Null reads as empty.
     */
    @Nullable public final byte[] revokedSerials;

    public RcsMlsIdentity(@Nullable String e164, @Nullable byte[] leafDer,
            @Nullable byte[] chainDer,
            @Nullable byte[] subjectPriv, @Nullable byte[] subjectPub, @Nullable byte[] roots) {
        this(e164, leafDer, chainDer, subjectPriv, subjectPub, roots, null);
    }

    public RcsMlsIdentity(@Nullable String e164, @Nullable byte[] leafDer,
            @Nullable byte[] chainDer,
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
        // Null from a parcel that ends here, which reads as an empty list.
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
        @Override public RcsMlsIdentity createFromParcel(Parcel in) { return new RcsMlsIdentity(
                in); }
        @Override public RcsMlsIdentity[] newArray(int size) { return new RcsMlsIdentity[size]; }
    };

    /** Prints sizes only, never key material. */
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
