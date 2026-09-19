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

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * The on-device half of the contract-skew guard: derive THIS build's binder
 * transaction layout from its own generated stub, exchange it across the bind, and refuse the
 * pairing when the two disagree. {@link RcsContractLayout} holds the decision; this holds the
 * plumbing.
 *
 * <p><b>Both sides link this file</b> — it lives in {@code messaging-rcs-contract-aidl}, which
 * this app and any provider app both {@code static_libs}. Each copy derives over ITS OWN
 * {@code IRcsProvider.Stub}, so the two answers differ exactly when the builds differ.
 *
 * <h3>Why a reserved transaction code rather than a new AIDL method</h3>
 * A new AIDL method is itself positional, so it can be renumbered by the very class of change this
 * guard exists to catch — and a probe that can move is a probe that can be mis-dispatched. {@link
 * #PROBE_TRANSACTION} is {@link IBinder#LAST_CALL_TRANSACTION} (0x00ffffff), the highest legal call
 * code. aidl numbers methods UP from {@code FIRST_CALL_TRANSACTION} (1), so reaching it would take
 * 16,777,215 declared methods. It is a constant in shared code, not a position, and therefore the
 * one channel immune to the failure being guarded against.
 *
 * <h3>Why derived, not declared</h3>
 * The layout is read back out of the {@code TRANSACTION_*} fields aidl generated, so it describes
 * what the binder will ACTUALLY do. A guard written as a list of expected method names would encode
 * a spelling rather than the property, and would silently report green the day somebody adds a
 * method and does not update the list — which is the same failure mode as the stale
 * {@code CONTRACT_VERSION} that let this incident through in the first place.
 *
 * <h3>R8, and why the derivation self-checks</h3>
 * {@code messaging2} runs R8 with obfuscation. R8 inlines {@code static final int} and renames
 * classes, and it was measured doing exactly that to this stub: in {@code messaging2.apk}
 * (2026-09-11) {@code IRcsProvider$Stub$Proxy} had been renamed to {@code org.lineageos.rcs.provider.a}
 * with every {@code TRANSACTION_*} field gone, while {@code IRcsProviderCallback$Stub} (where the
 * app is the SERVER, so the switch reads the fields) kept all 23. {@code proguard.flags} therefore
 * keeps {@code org.lineageos.rcs.provider.**}. That keep rule is a thing that can be deleted, so
 * {@link #deriveLocalLayout()} does not trust it: it validates the derivation against an anchor and
 * against an independent count, and returns {@code null} — which {@link RcsContractLayout#compare}
 * turns into a REFUSAL — if either fails. The provider APK builds with R8 off entirely.
 */
public final class RcsContractProbe {

    private static final String TAG = "RcsContract";

    /**
     * Reserved transaction code carrying the layout exchange.
     *
     * <p>{@link IBinder#LAST_CALL_TRANSACTION}. See the class doc for why the top of the range and
     * not a generated ordinal. Never renumber this: an older peer that does not know the code
     * answers {@code false}, which is a clean, fail-closed "cannot verify".
     */
    public static final int PROBE_TRANSACTION = IBinder.LAST_CALL_TRANSACTION;

    /**
     * Payload revision. Bump only for a change to the parcel shape below; a peer that answers with
     * a different revision is treated as unverifiable and REFUSED, not best-effort parsed.
     */
    public static final int PROBE_REV = 1;

    /** Cached derivations — reflection over ~60 and ~23 fields; no reason to repeat either. */
    @Nullable private static volatile String[] sProviderLayout;
    @Nullable private static volatile String[] sCallbackLayout;
    private static volatile boolean sDerived;

    private RcsContractProbe() {}

    // ---------------------------------------------------------------------
    // Derivation
    // ---------------------------------------------------------------------

    /**
     * This build's {@code IRcsProvider} transaction layout: index 0 is ordinal
     * {@code FIRST_CALL_TRANSACTION}, and each entry is the method aidl numbered there.
     *
     * @return the layout, or {@code null} if it could not be established — which every caller must
     *         treat as "refuse", never as "assume fine".
     */
    @Nullable
    public static String[] deriveLocalLayout() {
        derive();
        return sProviderLayout;
    }

    /**
     * This build's {@code IRcsProviderCallback} layout — the INBOUND direction, where the provider
     * dials and the app dispatches. Numbered independently of {@link IRcsProvider}, and skewed by
     * its own history: four mid-interface insertions to date.
     *
     * @return the layout, or {@code null} if it could not be established (treat as "refuse").
     */
    @Nullable
    public static String[] deriveCallbackLayout() {
        derive();
        return sCallbackLayout;
    }

    private static void derive() {
        if (sDerived) {
            return;
        }
        synchronized (RcsContractProbe.class) {
            if (!sDerived) {
                sProviderLayout = deriveUncached(IRcsProvider.Stub.class, IRcsProvider.class,
                        "IRcsProvider", RcsContractLayout.ANCHOR_METHOD);
                sCallbackLayout = deriveUncached(IRcsProviderCallback.Stub.class,
                        IRcsProviderCallback.class, "IRcsProviderCallback",
                        RcsContractLayout.CALLBACK_ANCHOR_METHOD);
                sDerived = true;
            }
        }
    }

    @Nullable
    private static String[] deriveUncached(final Class<?> stubClass, final Class<?> ifaceClass,
            final String ifaceName, final String anchor) {
        // The duplicated constant in the JDK-only half must match the platform's.
        if (RcsContractLayout.FIRST_ORDINAL != IBinder.FIRST_CALL_TRANSACTION) {
            Log.e(TAG, "FIRST_ORDINAL " + RcsContractLayout.FIRST_ORDINAL + " != platform "
                    + IBinder.FIRST_CALL_TRANSACTION + " — refusing to derive a layout");
            return null;
        }

        final String[] byOrdinal;
        int highest = -1;
        try {
            final Field[] fields = stubClass.getDeclaredFields();
            // Two passes: size the array from the highest ordinal seen, then fill it. Field order
            // from getDeclaredFields() is not specified, so nothing here may depend on it.
            final java.util.HashMap<Integer, String> map = new java.util.HashMap<>();
            for (final Field f : fields) {
                final String n = f.getName();
                if (!n.startsWith("TRANSACTION_") || f.getType() != int.class
                        || !Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                final int ordinal = f.getInt(null);
                final int idx = ordinal - IBinder.FIRST_CALL_TRANSACTION;
                if (idx < 0 || idx > 0xFFFF) {
                        Log.e(TAG, ifaceName + ": transaction " + n + " has out-of-range ordinal "
                            + ordinal);
                    return null;
                }
                final String method = n.substring("TRANSACTION_".length());
                final String prev = map.put(idx, method);
                if (prev != null) {
                    Log.e(TAG, ifaceName + ": two methods share ordinal " + ordinal + ": "
                            + prev + " and " + method);
                    return null;
                }
                if (idx > highest) {
                    highest = idx;
                }
            }
            if (highest < 0) {
                Log.e(TAG, "no TRANSACTION_* fields on " + ifaceName + ".Stub — the generated "
                        + "stub was stripped or renamed (check the -keep for "
                        + "org.lineageos.rcs.provider.** in proguard.flags). Cannot verify the "
                        + "contract; callers must refuse.");
                return null;
            }
            byOrdinal = new String[highest + 1];
            for (final java.util.Map.Entry<Integer, String> e : map.entrySet()) {
                byOrdinal[e.getKey()] = e.getValue();
            }
        } catch (final IllegalAccessException | RuntimeException e) {
            Log.e(TAG, ifaceName + ": could not read the generated transaction map", e);
            return null;
        }

        // --- Validate. Each of these is reachable, and each means "we are reading the wrong
        // thing", so each returns null rather than a layout we would then compare. ---
        for (int i = 0; i < byOrdinal.length; i++) {
            if (byOrdinal[i] == null) {
                Log.e(TAG, ifaceName + ": gap at ordinal " + (i + IBinder.FIRST_CALL_TRANSACTION)
                        + " — the derived map is incomplete; refusing to use it");
                return null;
            }
        }
        if (!anchor.equals(byOrdinal[0])) {
            Log.e(TAG, ifaceName + ": ordinal " + IBinder.FIRST_CALL_TRANSACTION + " is '"
                    + byOrdinal[0] + "', expected '" + anchor + "' — refusing");
            return null;
        }
        // Independent cross-check against a different artifact (the interface's own method list).
        // This is what catches R8 having removed SOME of the fields: a partial map still passes the
        // gap and anchor checks, but cannot match the declared method count.
        final int declared = ifaceClass.getDeclaredMethods().length;
        if (declared != byOrdinal.length) {
            Log.e(TAG, ifaceName + ": derived " + byOrdinal.length + " transactions but the "
                    + "interface declares " + declared + " methods — the derivation is "
                    + "incomplete; refusing");
            return null;
        }
        return byOrdinal;
    }

    /**
     * Short fingerprint of this build's layouts, for logs and the install-time pairing check.
     * Covers BOTH interfaces, because a pairing is only safe if both agree.
     */
    public static String localDigest() {
        return RcsContractLayout.digest(deriveLocalLayout()) + "/"
                + RcsContractLayout.digest(deriveCallbackLayout());
    }

    // ---------------------------------------------------------------------
    // Provider side
    // ---------------------------------------------------------------------

    /**
     * Answer a contract probe. Call from the {@code IRcsProvider.Stub}'s {@code onTransact} when
     * {@code code == }{@link #PROBE_TRANSACTION}, BEFORE delegating to {@code super}.
     *
     * <p>Also logs the provider's own verdict on the caller's layout, so a skew is visible in the
     * provider's log and not only the app's — the incident's symptom (a stray
     * {@code mlsForgetGroupConversation}) surfaced on this side.
     *
     * @return true (the transaction was handled), always — the reply carries the outcome.
     */
    public static boolean handleProbe(final Binder self, final Parcel data, final Parcel reply,
            final int contractVersion) {
        return handleProbe(self, data, reply, contractVersion, 0);
    }

    /**
     * As above, with a TEST AFFORDANCE: when {@code injectSkewAtOrdinal} is in range, the layout
     * this provider REPORTS has a synthetic method inserted at that ordinal, exactly as an aidl
     * insertion would — everything below shifts down by one.
     *
     * <p>Why this exists. The host tests pin the decision against layouts recovered from real
     * builds, but they cannot show that the probe crosses the binder, that the refusal actually
     * prevents the attach, or that the sentence reaches logcat. Demonstrating the ORDINAL SKEW
     * branch on a device otherwise means building and installing a deliberately-broken provider,
     * because every provider that answers the probe at all comes from the same tree as the app.
     * This makes the guard re-verifiable on any device, at any time, with a sysprop.
     *
     * <p><b>It can only ever cause a REFUSAL, never a false pass.</b> It perturbs what the provider
     * reports about itself, so the app's comparison fails; there is no value of it that makes two
     * genuinely skewed builds look paired. That asymmetry is what makes it safe to ship.
     */
    public static boolean handleProbe(final Binder self, final Parcel data, final Parcel reply,
            final int contractVersion, final int injectSkewAtOrdinal) {
        String[] callerProvider = null;
        String[] callerCallback = null;
        int callerVersion = 0;
        try {
            data.enforceInterface(self.getInterfaceDescriptor());
            if (data.readInt() == PROBE_REV) {
                callerVersion = data.readInt();
                callerProvider = data.createStringArray();
                callerCallback = data.createStringArray();
            }
        } catch (final RuntimeException e) {
            // Binder.getInterfaceDescriptor() (the concrete class, not the IBinder interface) does
            // not throw RemoteException, so RuntimeException is the whole surface here: a bad
            // token is a SecurityException and a short parcel is a BadParcelableException. Either
            // way the caller still gets our layout back and makes the decision.
            Log.w(TAG, "probe request unreadable", e);
        }

        final String[] mineCallback = deriveCallbackLayout();
        String[] mineProvider = deriveLocalLayout();
        if (injectSkewAtOrdinal > 0 && mineProvider != null
                && injectSkewAtOrdinal <= mineProvider.length) {
            final int at = injectSkewAtOrdinal - IBinder.FIRST_CALL_TRANSACTION;
            final String[] skewed = new String[mineProvider.length + 1];
            System.arraycopy(mineProvider, 0, skewed, 0, at);
            skewed[at] = "injectedSkewProbe";
            System.arraycopy(mineProvider, at, skewed, at + 1, mineProvider.length - at);
            Log.w(TAG, "TEST INJECTION: reporting a synthetic insertion at ordinal "
                    + injectSkewAtOrdinal + " — this provider is pretending to be a different "
                    + "build. Clear debug.rcs.contract_skew_at to stop.");
            mineProvider = skewed;
        }
        if (reply != null) {
            reply.writeNoException();
            reply.writeInt(PROBE_REV);
            reply.writeInt(contractVersion);
            reply.writeStringArray(mineProvider);
            reply.writeStringArray(mineCallback);
        }

        if (callerProvider != null) {
            // Both directions, each compared the way it is actually dialled: the app calls
            // IRcsProvider, and WE call IRcsProviderCallback.
            log(RcsContractLayout.compare("IRcsProvider", RcsContractLayout.ANCHOR_METHOD,
                    "app", callerProvider, callerVersion,
                    "provider", mineProvider, contractVersion));
            log(RcsContractLayout.compare("IRcsProviderCallback",
                    RcsContractLayout.CALLBACK_ANCHOR_METHOD,
                    "provider", mineCallback, contractVersion,
                    "app", callerCallback, callerVersion));
        } else {
            Log.i(TAG, "contract probe answered: provider v" + contractVersion + " layout="
                    + localDigest());
        }
        return true;
    }

    private static void log(final RcsContractLayout.Verdict v) {
        if (v.compatible) {
            Log.i(TAG, "contract probe OK — " + v.reason);
        } else {
            Log.e(TAG, "CONTRACT SKEW (provider side) — " + v.reason);
        }
    }

    // ---------------------------------------------------------------------
    // App side
    // ---------------------------------------------------------------------

    /**
     * Ask a bound provider for its layout and decide whether it is safe to call.
     *
     * <p><b>Fail closed.</b> A provider that does not answer the probe — one built before this
     * guard, or one whose derivation failed — is REFUSED. That is deliberate: "too old to tell us"
     * and "skewed" are the same risk, and the operational rule either way is to rebuild and install
     * both sides from one tree.
     *
     * @param binder          the provider's binder
     * @param localVersion    our own declared contract version (a label for the message)
     * @param providerVersion the provider's declared contract version, already read via
     *                        {@code getContractVersion()} (ordinal 1, the one that cannot move)
     */
    public static RcsContractLayout.Verdict check(final IBinder binder, final int localVersion,
            final int providerVersion) {
        final String[][] theirs = queryRemote(binder, localVersion);

        // OUTBOUND: we dial IRcsProvider, the provider dispatches it.
        final RcsContractLayout.Verdict out = RcsContractLayout.compare(
                "IRcsProvider", RcsContractLayout.ANCHOR_METHOD,
                "app", deriveLocalLayout(), localVersion,
                "provider", theirs == null ? null : theirs[0], providerVersion);
        if (!out.compatible) {
            return out;
        }
        // INBOUND: the PROVIDER dials IRcsProviderCallback and WE dispatch it, so the caller/callee
        // roles are the other way round. A skew here mis-delivers inbound messages, receipts and
        // MLS control frames onto the wrong handler, which is no less destructive for arriving
        // from the other side — that interface has taken four mid-interface insertions of its own.
        return RcsContractLayout.compare(
                "IRcsProviderCallback", RcsContractLayout.CALLBACK_ANCHOR_METHOD,
                "provider", theirs == null ? null : theirs[1], providerVersion,
                "app", deriveCallbackLayout(), localVersion);
    }

    /**
     * @param localVersion our declared contract version, forwarded so the provider can log the
     *                     skew from its own side as well.
     * @return {@code [IRcsProvider layout, IRcsProviderCallback layout]}, or {@code null} if it
     *         could not be obtained for ANY reason (old provider, dead binder, unexpected payload
     *         revision). Either element may itself be null if that side could not derive it.
     */
    @Nullable
    public static String[][] queryRemote(final IBinder binder, final int localVersion) {
        if (binder == null) {
            return null;
        }
        Parcel data = null;
        Parcel reply = null;
        try {
            data = Parcel.obtain();
            reply = Parcel.obtain();
            data.writeInterfaceToken(binder.getInterfaceDescriptor());
            data.writeInt(PROBE_REV);
            // Our declared version travels in the parcel so the PROVIDER's log names both sides
            // too; a skew must be diagnosable from either process's logcat.
            data.writeInt(localVersion);
            data.writeStringArray(deriveLocalLayout());
            data.writeStringArray(deriveCallbackLayout());
            if (!binder.transact(PROBE_TRANSACTION, data, reply, 0)) {
                Log.w(TAG, "provider does not answer the contract probe (older build)");
                return null;
            }
            reply.readException();
            final int rev = reply.readInt();
            if (rev != PROBE_REV) {
                Log.w(TAG, "contract probe rev " + rev + ", we speak " + PROBE_REV
                        + " — treating as unverifiable");
                return null;
            }
            reply.readInt(); // provider version; the caller already has it from getContractVersion
            return new String[][] { reply.createStringArray(), reply.createStringArray() };
        } catch (final RemoteException | RuntimeException e) {
            Log.w(TAG, "contract probe failed", e);
            return null;
        } finally {
            if (reply != null) {
                reply.recycle();
            }
            if (data != null) {
                data.recycle();
            }
        }
    }
}
