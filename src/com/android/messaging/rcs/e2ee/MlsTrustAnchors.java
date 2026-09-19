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
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.telephony.SubscriptionManager;
import android.util.Base64;
import android.util.Log;

import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The open5gs lab MLS trust anchor — the {@code openrcs-pki} E2EE hierarchy that issues RCC.16 client
 * certificates for the carrier lab (00101). Baked in as a compiled-in constant (a public CA cert, no
 * secret) so both the shared engine's consumers get it without a resource lookup.
 *
 * <p>Both DERs are sha256-verified:
 *
 * <ul>
 *   <li>root sha256 = {@code 65b55992b88c5668d5310a21e0812df86e105762adb812b681efbfa4b5e42df4}</li>
 *   <li>ica  sha256 = {@code ac8d3ef3550e9de4dbe58cfea51793b99407f2c73b4905d738f09cfac86b1559}</li>
 * </ul>
 *
 * <p>Profile: root = P-384 secp384r1 / ecdsa-with-SHA384, self-signed {@code CN=OpenRCS E2EE Root},
 * BasicConstraints CA:TRUE critical, KeyUsage critical, SKI, vendor-id {@code 2.23.146.2.1.6 = INTEGER 2}.
 * ICA = {@code CN=OpenRCS E2EE Intermediate 1}, issued by the root. Leaves are P-256 identity keys
 * signed by the P-384 ICA (SHA-384) — our {@code X509Validator} (mls-rs-crypto-rustcrypto, pulls the
 * {@code p384} crate) verifies the cross-curve chain.
 *
 * <h2>DEMOTED 2026-09-13 — this constant is now the FLOOR, not the anchor set</h2>
 *
 * <p>{@link #root()} used to be the SOLE trust anchor, and that is the defect: a baked anchor
 * works perfectly right up to the point where it should have changed. A device pinned to it
 * cannot learn about an added anchor and cannot drop a withdrawn one, and the only lever is a
 * rebuild.
 *
 * <p>{@link #roots(android.content.Context)} is the entry point now. It asks the PROVIDER for a
 * fetched, generation-addressed, signature-verified list ({@code IRcsProvider
 * .getMlsTrustAnchors}, contract v63) and falls back to the constant below only when the
 * provider cannot supply one. Fetching lives in the provider by the operator's rule — the engine
 * does not reach the network.
 *
 * <p>{@link #roots()} without a Context still exists and still returns the constant. It is the
 * OFFLINE FLOOR, not a shortcut: reaching for it where a Context is available silently reinstates
 * the defect, which is why it is deprecated rather than deleted.
 */
public final class MlsTrustAnchors {
    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;

    private MlsTrustAnchors() {}

    /** {@code CN=OpenRCS E2EE Root} — P-384, self-signed. The sole trust anchor for lab MLS. */
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

    /** {@code CN=OpenRCS E2EE Intermediate 1} — P-384, issued by the root. The CA that signs leaves. */
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

    /** The lab MLS root cert DER (the trust anchor). */
    public static byte[] root() { return Base64.decode(ROOT_DER_B64, Base64.DEFAULT); }

    /** The lab MLS intermediate cert DER (part of a leaf's chain; the CA that signs leaves). */
    public static byte[] ica() { return Base64.decode(ICA_DER_B64, Base64.DEFAULT); }

    /**
     * The trust-anchor list for {@code MlsIdentity.roots}: the PROVIDER's verified list when it
     * can supply one, else the baked floor.
     *
     * <p>Memoised for the process after the first success, like the Tachyon side's
     * {@code MlsTrustedRoots}: this costs one binder call per cold start rather than one per
     * enrolment, and the provider does its own caching underneath.
     *
     * <p><b>REPLACE, never merge.</b> A verified list is the complete anchor set for the lab
     * domain; unioning it with the floor would make a withdrawn anchor un-withdrawable, which
     * defeats the point of fetching. And it is never merged with the TACHYON set either — that is
     * a different PKI, and a lab-minted leaf must not validate on the Tachyon path.
     *
     * <p>Falls back rather than failing closed: an unreachable provider must not leave the lab
     * path with no anchors at all.
     */
    public static List<byte[]> roots(final Context context) {
        return roots(context, null, 0L, null);
    }

    /**
     * As {@link #roots(Context)}, with the ACS's {@code openrcs-trust-anchors-*} pointers.
     *
     * <p>They come from the config document the MODEM fetched, which arrives in THIS app, so this
     * app is the only one that has them; the provider may never have seen a document. Null/zero is
     * the normal unprovisioned state and not a refusal — the provider then falls through to
     * whatever it holds, and below that to the floor.
     */
    public static List<byte[]> roots(final Context context, final String uri,
            final long generation, final String signerSpkiB64) {
        final List<byte[]> memo = sSuppliedRoots;
        if (memo != null) {
            return memo;
        }
        if (context == null) {
            return roots();
        }
        try {
            final ProviderTransport pt = ProviderTransport.peekInstance();
            if (pt == null) {
                Log.i(TAG, "MlsTrustAnchors: no bound provider yet — using the BAKED lab root. It "
                        + "cannot rotate; this is the floor, not the anchor set.");
                return roots();
            }
            final byte[] packed = pt.getMlsTrustAnchors(
                    SubscriptionManager.getDefaultSmsSubscriptionId(),
                    uri, generation, signerSpkiB64);
            if (packed == null || packed.length == 0) {
                Log.w(TAG, "MlsTrustAnchors: the provider supplied no anchors — using the BAKED lab "
                        + "root. NOT a rejection: a device that has never seen an ACS config "
                        + "document, or a provider older than contract v63, looks exactly like "
                        + "this.");
                return roots();
            }
            final List<byte[]> supplied = OpenMlsSession.splitLenPrefixed(packed);
            if (supplied == null || supplied.isEmpty()) {
                Log.w(TAG, "MlsTrustAnchors: the provider's anchor blob did not decode — using the "
                        + "BAKED lab root");
                return roots();
            }
            Log.i(TAG, "MlsTrustAnchors: using " + supplied.size() + " PROVIDER-supplied lab trust "
                    + "anchor(s), signature-verified provider-side");
            sSuppliedRoots = Collections.unmodifiableList(supplied);
            return sSuppliedRoots;
        } catch (final Throwable t) {
            Log.w(TAG, "MlsTrustAnchors: could not reach the provider for lab trust anchors — "
                    + "using the BAKED lab root", t);
            return roots();
        }
    }

    /** Forget the memoised provider-supplied anchors (a re-provision, or a bench probe). */
    public static void invalidate() {
        sSuppliedRoots = null;
    }

    /** The provider's verified list once seen, for the life of the process. */
    private static volatile List<byte[]> sSuppliedRoots;

    /**
     * The BAKED anchor list — the offline floor.
     *
     * @deprecated prefer {@link #roots(Context)}. This returns a compiled-in root that cannot
     *     rotate; it is correct only as the fallback the
     *     Context-taking overload already applies for you.
     */
    @Deprecated
    public static List<byte[]> roots() { return Collections.singletonList(root()); }

    /** The chain (ICA...) for a lab-issued leaf's {@code MlsIdentity.chainDer}: just the ICA. */
    public static List<byte[]> chain() {
        final List<byte[]> c = new ArrayList<>(1);
        c.add(ica());
        return c;
    }
}
