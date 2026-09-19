/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The on-device half of the contract-skew check: derives this build's transaction layouts from its
 * generated stubs, exchanges them on {@link #PROBE_TRANSACTION} and refuses the pairing when they
 * disagree; {@link RcsContractLayout} makes the decision. Both sides link this class. The
 * derivation validates itself because R8 can strip {@code TRANSACTION_*} fields.
 * See docs/rcs/provider-contract.md.
 */
public final class RcsContractProbe {

    private static final String TAG = "RcsContract";

    /**
     * The layout exchange's transaction code. Not an AIDL method, because a method's ordinal can
     * move with the change being detected. Never renumber it: a peer that does not know it answers
     * {@code false}, which reads as "cannot verify".
     */
    public static final int PROBE_TRANSACTION = IBinder.LAST_CALL_TRANSACTION;

    /**
     * Payload revision; bump only when the parcel shape changes. A different revision is
     * unverifiable and refused.
     */
    public static final int PROBE_REV = 1;

    @Nullable private static volatile String[] sProviderLayout;
    @Nullable private static volatile String[] sCallbackLayout;
    private static volatile boolean sDerived;

    private RcsContractProbe() {}

    /**
     * This build's {@code IRcsProvider} layout: index 0 is ordinal {@code FIRST_CALL_TRANSACTION}.
     * Cached.
     *
     * @return the layout, or {@code null} if it could not be established, which means refuse
     */
    @Nullable
    public static String[] deriveLocalLayout() {
        derive();
        return sProviderLayout;
    }

    /**
     * This build's {@code IRcsProviderCallback} layout, numbered independently of
     * {@link IRcsProvider}.
     *
     * @return the layout, or {@code null} if it could not be established, which means refuse
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
            // getDeclaredFields() order is unspecified: collect by ordinal, then size and fill.
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
        // Catches R8 removing only some fields, which passes the gap and anchor checks.
        final int declared = ifaceClass.getDeclaredMethods().length;
        if (declared != byOrdinal.length) {
            Log.e(TAG, ifaceName + ": derived " + byOrdinal.length + " transactions but the "
                    + "interface declares " + declared + " methods — the derivation is "
                    + "incomplete; refusing");
            return null;
        }
        return byOrdinal;
    }

    /** {@code <IRcsProvider digest>/<IRcsProviderCallback digest>}, for logs. */
    public static String localDigest() {
        return RcsContractLayout.digest(deriveLocalLayout()) + "/"
                + RcsContractLayout.digest(deriveCallbackLayout());
    }

    /**
     * Answers a contract probe. Call from the provider stub's {@code onTransact} when
     * {@code code == }{@link #PROBE_TRANSACTION}, before delegating to {@code super}. Also logs the
     * provider's own verdict, so a skew shows in both processes' logs.
     *
     * @return always true; the reply carries the outcome
     */
    public static boolean handleProbe(final Binder self, final Parcel data, final Parcel reply,
            final int contractVersion) {
        return handleProbe(self, data, reply, contractVersion, 0);
    }

    /**
     * As above; when {@code injectSkewAtOrdinal} is in range the reported layout gains a synthetic
     * method at that ordinal, so the refusal path can be exercised on a device. It can only cause
     * a refusal, never make skewed builds look paired.
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
            // A bad token or a short parcel; the caller still gets our layout and decides.
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
            // Each interface in its own direction: the app dials one, we dial the other.
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

    /**
     * Asks a bound provider for its layouts and decides whether the pairing is safe. A provider
     * that cannot answer is refused like a skewed one.
     *
     * @param localVersion    our contract version, for the message only
     * @param providerVersion the provider's, already read through {@code getContractVersion()}
     */
    public static RcsContractLayout.Verdict check(final IBinder binder, final int localVersion,
            final int providerVersion) {
        final String[][] theirs = queryRemote(binder, localVersion);

        final RcsContractLayout.Verdict out = RcsContractLayout.compare(
                "IRcsProvider", RcsContractLayout.ANCHOR_METHOD,
                "app", deriveLocalLayout(), localVersion,
                "provider", theirs == null ? null : theirs[0], providerVersion);
        if (!out.compatible) {
            return out;
        }
        // The provider dials the callback, so the roles are reversed.
        return RcsContractLayout.compare(
                "IRcsProviderCallback", RcsContractLayout.CALLBACK_ANCHOR_METHOD,
                "provider", theirs == null ? null : theirs[1], providerVersion,
                "app", deriveCallbackLayout(), localVersion);
    }

    /**
     * @param localVersion our contract version, sent so the provider can log the verdict too
     * @return {@code [IRcsProvider layout, IRcsProviderCallback layout]}, or {@code null} if they
     *         could not be obtained; either element may be null if the provider could not derive it
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
