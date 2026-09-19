/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.util.Base64;
import android.util.Log;

import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;

import java.util.Collections;
import java.util.List;

/**
 * Trust anchors for MLS on the carrier path, decided by {@link MlsCarrierTrust}: the provider's
 * fetched, signature-verified anchor list ({@code IRcsProvider.getMlsTrustAnchors}) when it
 * supplies one; else, on a debug build only, the compiled-in root; else none, and carrier-path MLS
 * is off.
 *
 * <p>Everything compiled in here belongs to a test network: the root, the intermediate (its AIA and
 * CRL point at {@code kds.rcs.mnc001.mcc001.pub.3gppnetwork.org}) and that network's KDS. A user
 * build must never trust or dial them, so every read below sits behind
 * {@link RcsDebug#isDebugBuild()}; {@code TestNetworkTrustGuardTest} fails when one does not, or
 * when any of them appears in another source file.
 *
 * <p>The compiled-in hierarchy is a self-signed P-384 root (SHA-384 signatures, vendor-id
 * {@code 2.23.146.2.1.6 = 2}) and one intermediate that signs P-256 leaves.
 * <ul>
 *   <li>root sha256 = {@code 65b55992b88c5668d5310a21e0812df86e105762adb812b681efbfa4b5e42df4}</li>
 *   <li>ica  sha256 = {@code ac8d3ef3550e9de4dbe58cfea51793b99407f2c73b4905d738f09cfac86b1559}</li>
 * </ul>
 */
public final class MlsTrustAnchors {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;

    private MlsTrustAnchors() {}

    /** The compiled-in root: P-384, self-signed. */
    private static final String ROOT_DER_B64 =
            "MIICBTCCAYygAwIBAgIUTdwsz0lXB1LtfXoaMkoP6dYaQmQwCgYIKoZIzj0EAwMwOzELMAkGA1UEBhMC"
            + "VVMxEDAOBgNVBAoMB09wZW5SQ1MxGjAYBgNVBAMMEU9wZW5SQ1MgRTJFRSBSb290MB4XDTI2MDcyMjAy"
            + "MTUwMFoXDTM2MDcyMTAyMTUwMFowOzELMAkGA1UEBhMCVVMxEDAOBgNVBAoMB09wZW5SQ1MxGjAYBgNV"
            + "BAMMEU9wZW5SQ1MgRTJFRSBSb290MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE/XkzD1x/9nwFQNdG//fu"
            + "sDygryYMEanyGgnYPf4kY/zB0VDxhSXyDakwiJOssMyGi6oqsWWKbVTufHgZhgOedPry7eG+V3jk6GgQ"
            + "3QU446fY14TGC6ahF8T2msIMDIoso1EwTzAdBgNVHQ4EFgQUxPCSCQ8vq/kQdckbOaBRRz3vF7QwDgYD"
            + "VR0PAQH/BAQDAgEGMA8GA1UdEwEB/wQFMAMBAf8wDQYGZ4ESAgEGBAMCAQIwCgYIKoZIzj0EAwMDZwAw"
            + "ZAIwWdRglc2GAOqyHE+RtZbk6GBQmMDx4Be6rBf91lrnfJ5+RZ+C2hu6DxeT7sJvZU65AjAz1RrnRnO"
            + "BCTT2ZWCaKpSp1v3JEVCy02dSvLZFoWn+FqKky9gIUUr50KAd0lYnEWc=";

    /** The compiled-in intermediate: P-384, issued by the root; signs leaves. */
    private static final String ICA_DER_B64 =
            "MIIC+jCCAn+gAwIBAgIUU9gajdSqP+m7H+1yu4yNeoJ5HcAwCgYIKoZIzj0EAwMwOzELMAkGA1UEBhMC"
            + "VVMxEDAOBgNVBAoMB09wZW5SQ1MxGjAYBgNVBAMMEU9wZW5SQ1MgRTJFRSBSb290MB4XDTI2MDcyMjAy"
            + "MTUwMFoXDTI5MDcyMTAyMTUwMFowRTELMAkGA1UEBhMCVVMxEDAOBgNVBAoMB09wZW5SQ1MxJDAiBgNV"
            + "BAMMG09wZW5SQ1MgRTJFRSBJbnRlcm1lZGlhdGUgMTB2MBAGByqGSM49AgEGBSuBBAAiA2IABBVcQSDj"
            + "cFtmOL+JXORMen9yMXVFFG6wHIm8wTypJ+EAwdmUNY4ZiAmkVT7Vr8YRperg8kGky5ZNdtdxHhIMUT2"
            + "5c/o9QhZv+LTDCz+A95VH+Ahia4Qz4iyNFbGGzyoBdaOCATgwggE0MB8GA1UdIwQYMBaAFMTwkgkPL6v5"
            + "EHXJGzmgUUc97xe0MB0GA1UdDgQWBBQhk2QwZ+5Tw6Py6UkML6qgVnAhEzAOBgNVHQ8BAf8EBAMCAQYw"
            + "DwYDVR0TAQH/BAUwAwEB/zATBgNVHSAEDDAKMAgGBmeBEgIBAjBMBgNVHR8ERTBDMEGgP6A9hjtodHRw"
            + "Oi8va2RzLnJjcy5tbmMwMDEubWNjMDAxLnB1Yi4zZ3BwbmV0d29yay5vcmcvbWxzL3YxL2NybDBbBggr"
            + "BgEFBQcBAQRPME0wSwYIKwYBBQUHMAKGP2h0dHA6Ly9rZHMucmNzLm1uYzAwMS5tY2MwMDEucHViLjNn"
            + "cHBuZXR3b3JrLm9yZy9tbHMvdjEvaWNhLmRlcjARBgNVHSUECjAIBgZngRICAQMwCgYIKoZIzj0EAwMD"
            + "aQAwZgIxAJZ6ujutlcvDdHs4V8lM0NtWb8gY8VmhVAcMQzJnEFLjiFr61z50j43P7mGCH2q8ugIxAPVZ"
            + "CXitl5TLllzbcEIyy8qn2+BiB6WmcOixSi7V0Fg88URYJFdO1Lg/kaouYhSOZw==";

    /** The test network's KDS, the carrier path's default when the configuration names none. */
    private static final String TEST_NETWORK_KDS_URL =
            "https://kds.rcs.mnc001.mcc001.pub.3gppnetwork.org";

    /** The compiled-in root as a one-element anchor list; empty on a user build. */
    private static List<byte[]> compiledInRoots() {
        return RcsDebug.isDebugBuild()
                ? Collections.singletonList(Base64.decode(ROOT_DER_B64, Base64.DEFAULT))
                : Collections.<byte[]>emptyList();
    }

    /** The compiled-in intermediate DER; {@code null} on a user build. */
    public static byte[] compiledInIca() {
        return RcsDebug.isDebugBuild() ? Base64.decode(ICA_DER_B64, Base64.DEFAULT) : null;
    }

    /** The test network's KDS; {@code null} on a user build. */
    public static String compiledInKdsUrl() {
        return RcsDebug.isDebugBuild() ? TEST_NETWORK_KDS_URL : null;
    }

    /**
     * The anchor list for {@code MlsIdentity.roots}: the provider's verified list when it supplies
     * one, else the compiled-in root on a debug build, else an empty list, which the caller must
     * treat as "carrier-path MLS is off". The provider's list is memoised for the process after
     * the first success.
     *
     * <p>The provider's list replaces the compiled-in one, never merges with it, so a withdrawn
     * anchor stays withdrawn. It is also never merged with the provider-transport anchor set, which
     * is a different PKI.
     */
    public static List<byte[]> roots(final Context context) {
        return roots(context, null, 0L, null);
    }

    /**
     * As {@link #roots(Context)}, passing the ACS configuration's {@code openrcs-trust-anchors-*}
     * pointers, which only this app receives. Null or zero is the normal unprovisioned state; the
     * provider then uses what it holds.
     */
    public static List<byte[]> roots(final Context context, final String uri,
            final long generation, final String signerSpkiB64) {
        final List<byte[]> supplied = providerRoots(context, uri, generation, signerSpkiB64);
        final List<byte[]> out = MlsCarrierTrust.anchors(supplied, RcsDebug.isDebugBuild(),
                MlsTrustAnchors::compiledInRoots);
        if (out.isEmpty()) {
            Log.w(TAG, "MlsTrustAnchors: no trust anchors — carrier-path MLS is off. A user build "
                    + "trusts only the provider's verified anchors, and it supplied none.");
        } else if (out != supplied) {
            Log.i(TAG, "MlsTrustAnchors: debug build — using the COMPILED-IN test-network root. "
                    + "It cannot rotate, and a user build never uses it.");
        }
        return out;
    }

    /** The provider's verified list, or an empty one when it supplies none. */
    private static List<byte[]> providerRoots(final Context context, final String uri,
            final long generation, final String signerSpkiB64) {
        final List<byte[]> memo = sSuppliedRoots;
        if (memo != null) {
            return memo;
        }
        if (context == null) {
            return Collections.emptyList();
        }
        try {
            final ProviderTransport pt = ProviderTransport.peekInstance();
            if (pt == null) {
                Log.i(TAG, "MlsTrustAnchors: no bound provider in this process — no provider "
                        + "anchors");
                return Collections.emptyList();
            }
            final byte[] packed = pt.getMlsTrustAnchors(
                    SubscriptionManager.getDefaultSmsSubscriptionId(),
                    uri, generation, signerSpkiB64);
            if (packed == null || packed.length == 0) {
                Log.w(TAG,
                        "MlsTrustAnchors: the provider supplied no anchors. NOT a rejection: a "
                        + "device that has never seen an ACS config document, or a provider older "
                        + "than contract v63, looks exactly like this.");
                return Collections.emptyList();
            }
            final List<byte[]> supplied = OpenMlsSession.splitLenPrefixed(packed);
            if (supplied == null || supplied.isEmpty()) {
                Log.w(TAG, "MlsTrustAnchors: the provider's anchor blob did not decode");
                return Collections.emptyList();
            }
            Log.i(TAG, "MlsTrustAnchors: using " + supplied.size() + " PROVIDER-supplied trust "
                    + "anchor(s), signature-verified provider-side");
            sSuppliedRoots = Collections.unmodifiableList(supplied);
            return sSuppliedRoots;
        } catch (final Throwable t) {
            Log.w(TAG, "MlsTrustAnchors: could not reach the provider for trust anchors", t);
            return Collections.emptyList();
        }
    }

    /** Forget the memoised provider anchors (after a re-provision). */
    public static void invalidate() {
        sSuppliedRoots = null;
    }

    /** The provider's verified list once seen, for the life of the process. */
    private static volatile List<byte[]> sSuppliedRoots;
}
