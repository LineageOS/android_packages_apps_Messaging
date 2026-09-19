/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.engine.mls.MlsConversationKey;
import com.android.messaging.rcs.engine.mls.MlsMessageId;
import com.android.messaging.rcs.engine.mls.MlsOutboundHold;
import com.android.messaging.rcs.log.LogMask;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture, debug builds only: the durable half of the outbound commit hold, which withholds
 * rekey commits so a test can put this device ahead of its group on purpose. The decision half is
 * {@link MlsOutboundHold} in the engine.
 *
 * <p>Two things are stashed: the group snapshot taken before the first withheld commit (restoring
 * it undoes all of them), and each withheld commit's artifacts in order (publishing them late lets
 * the server catch up instead). Persisted in its own preferences file so it survives the app's
 * frequent restarts and clearing it cannot touch conversation state. Inert on a user build at both
 * ends: the debug arm refuses to set it and this class refuses to act on it.
 */
public final class MlsOutboundHoldStore {

    private static final String TAG = LogUtil.BUGLE_TAG;

    private static final String PREFS = "mls_outbound_hold";

    private static final String K_ARMED = "armed";
    private static final String K_SCOPE = "scope";
    private static final String K_ARM_ERA = "arm_era";
    private static final String K_ARM_EPOCH = "arm_epoch";
    private static final String K_SNAPSHOT = "snapshot";
    private static final String K_SNAP_ERA = "snap_era";
    private static final String K_SNAP_EPOCH = "snap_epoch";
    private static final String K_COUNT = "held_count";
    private static final String K_HELD = "held_";
    private static final String K_REFUSED = "refused_total";

    private static volatile MlsOutboundHoldStore sInstance;

    private final Context mCtx;

    private MlsOutboundHoldStore(final Context ctx) {
        mCtx = ctx.getApplicationContext();
    }

    public static MlsOutboundHoldStore get(final Context ctx) {
        MlsOutboundHoldStore s = sInstance;
        if (s == null) {
            synchronized (MlsOutboundHoldStore.class) {
                s = sInstance;
                if (s == null) {
                    s = new MlsOutboundHoldStore(ctx);
                    sInstance = s;
                }
            }
        }
        return s;
    }

    /**
     * eng or userdebug only. {@code ApplicationInfo.FLAG_DEBUGGABLE} is false for a system app even
     * on userdebug, so it is the wrong test.
     */
    public static boolean buildAllowsFixtures() {
        return RcsDebug.isDebugBuild();
    }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** One withheld commit: everything {@code applyMlsControl} would have been given. */
    public static final class Held {
        public final String ctrlId;
        public final String peerE164;
        public final String rcsGroupId;
        public final byte[] groupInfo;
        public final byte[] commit;
        public final byte[] tag;
        public final byte[] ratchetTree;
        public final byte[] baseEpochAuth;
        /** The epoch this commit was built from. */
        public final long fromEpoch;

        Held(final String ctrlId, final String peerE164, final String rcsGroupId,
                final byte[] groupInfo, final byte[] commit, final byte[] tag,
                final byte[] ratchetTree, final byte[] baseEpochAuth, final long fromEpoch) {
            this.ctrlId = ctrlId;
            this.peerE164 = peerE164;
            this.rcsGroupId = rcsGroupId;
            this.groupInfo = groupInfo;
            this.commit = commit;
            this.tag = tag;
            this.ratchetTree = ratchetTree;
            this.baseEpochAuth = baseEpochAuth;
            this.fromEpoch = fromEpoch;
        }
    }

    public boolean isArmed() {
        return buildAllowsFixtures() && prefs().getBoolean(K_ARMED, false);
    }

    /** The canonical conversation key the lever names ({@code g:<gid>} or {@code p:<e164>}). */
    public String scopeKey() {
        return prefs().getString(K_SCOPE, null);
    }

    public int armEra() {
        return prefs().getInt(K_ARM_ERA, -1);
    }

    public long armEpoch() {
        return prefs().getLong(K_ARM_EPOCH, -1L);
    }

    public int heldCount() {
        return prefs().getInt(K_COUNT, 0);
    }

    /** Arm the lever on one conversation; {@code armEra} and {@code armEpoch} are the baseline. */
    public synchronized void arm(final String scopeKey, final int armEra, final long armEpoch) {
        prefs().edit()
                .putBoolean(K_ARMED, true)
                .putString(K_SCOPE, scopeKey)
                .putInt(K_ARM_ERA, armEra)
                .putLong(K_ARM_EPOCH, armEpoch)
                .commit();
    }

    /**
     * Stop withholding. The stash is kept: disarming and releasing are separate acts, so a
     * divergence can be left standing with the undo still in hand.
     */
    public synchronized void disarm() {
        prefs().edit().putBoolean(K_ARMED, false).commit();
    }

    /** Should this commit be withheld? A lookup only; the caller does the withholding. */
    public synchronized MlsOutboundHold.Verdict verdictFor(final String conversationKey,
            final boolean isRekey) {
        if (!isArmed()) return MlsOutboundHold.Verdict.PUBLISH;
        final String scope = scopeKey();
        final boolean scopeMatches = scope != null && scope.equals(conversationKey);
        return MlsOutboundHold.decide(true, scopeMatches, isRekey, heldCount());
    }

    /**
     * Record a withheld commit and, on the first one, the snapshot that undoes it, with the era and
     * epoch read at the same moment so a release can check it restored cleanly.
     *
     * @return true if recorded; false means it was not stashed and must not be treated as withheld
     */
    public synchronized boolean suppress(final Held h, final byte[] snapshot, final int preEra,
            final long preEpoch) {
        if (h == null || h.commit == null || h.commit.length == 0) return false;
        final int count = prefs().getInt(K_COUNT, 0);
        final SharedPreferences.Editor e = prefs().edit();
        if (count == 0) {
            if (snapshot == null || snapshot.length == 0 || preEra < 0 || preEpoch < 0L) {
                // Never withhold without an undo: a commit that cannot be taken back leaves the
                // device ahead for good, repairable only by a rebuild or a re-Welcome.
                LogUtil.e(TAG, "MlsOutboundHoldStore: NOT suppressing — no usable snapshot ("
                        + (snapshot == null ? "null" : snapshot.length + "B") + ", era=" + preEra
                        + " epoch=" + preEpoch + "). A hold with no undo is exactly the "
                        + "unrecoverable state this lever exists to produce ON PURPOSE and never "
                        + "by accident.");
                return false;
            }
            e.putString(K_SNAPSHOT, Base64.encodeToString(snapshot, Base64.NO_WRAP))
                    .putInt(K_SNAP_ERA, preEra)
                    .putLong(K_SNAP_EPOCH, preEpoch);
        }
        e.putString(K_HELD + count, encode(h)).putInt(K_COUNT, count + 1).commit();
        LogUtil.w(TAG, "MlsOutboundHoldStore: SUPPRESSED a rekey commit (" + h.commit.length
                + "B, from epoch " + h.fromEpoch + ", ctrlId=" + MlsMessageId.forLog(h.ctrlId)
                + ") for " + h.rcsGroupId
                + "/" + LogMask.number(h.peerE164) + ". Now holding " + (count + 1) + "/"
                + MlsOutboundHold.CAPACITY
                + ". This device is a test fixture right now — it is AHEAD of this conversation BY "
                + "CONSTRUCTION, not by a fault, and the group will never see this commit.");
        return true;
    }

    public synchronized void noteRefused() {
        prefs().edit().putInt(K_REFUSED, prefs().getInt(K_REFUSED, 0) + 1).commit();
    }

    /** The snapshot taken before the first withheld commit, or null. */
    public synchronized byte[] snapshot() {
        final String s = prefs().getString(K_SNAPSHOT, null);
        if (s == null || s.isEmpty()) return null;
        try {
            return Base64.decode(s, Base64.NO_WRAP);
        } catch (final RuntimeException bad) {
            LogUtil.e(TAG, "MlsOutboundHoldStore: the stashed snapshot is UNREADABLE — the undo is "
                    + "gone and a release cannot report success: " + bad);
            return null;
        }
    }

    /** The era the snapshot restores to, or -1 when nothing is stashed. */
    public synchronized int snapshotEra() {
        return prefs().getInt(K_SNAP_ERA, -1);
    }

    public synchronized long snapshotEpoch() {
        return prefs().getLong(K_SNAP_EPOCH, -1L);
    }

    /** Everything withheld, in order. */
    public synchronized List<Held> held() {
        final SharedPreferences p = prefs();
        final int n = p.getInt(K_COUNT, 0);
        final List<Held> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final Held h = decode(p.getString(K_HELD + i, null));
            if (h == null) {
                LogUtil.w(TAG, "MlsOutboundHoldStore: held row " + i
                        + " is unreadable and is being "
                        + "skipped — a late PUBLISH will be INCOMPLETE, and the commits after it "
                        + "will be refused because their base epoch authenticator names a state the "
                        + "server never reached.");
                continue;
            }
            out.add(h);
        }
        return out;
    }

    /** Forget everything stashed; every release calls this when done with the bytes. */
    public synchronized void clearStash() {
        final SharedPreferences.Editor e = prefs().edit();
        final int n = prefs().getInt(K_COUNT, 0);
        for (int i = 0; i < n; i++) e.remove(K_HELD + i);
        e.remove(K_SNAPSHOT).remove(K_SNAP_ERA).remove(K_SNAP_EPOCH).putInt(K_COUNT, 0).commit();
    }

    /**
     * A one-line status. The era and epoch are read from the engine and the group's position is
     * derived from the withheld commits, so a test can assert the fixture. No server look-up.
     */
    public synchronized String describe(final int ourEra, final long ourEpoch) {
        final SharedPreferences p = prefs();
        if (!buildAllowsFixtures()) {
            return "outbound-hold UNAVAILABLE (Build.TYPE=" + Build.TYPE + ", not eng/userdebug)";
        }
        final MlsOutboundHold.Gap gap = MlsOutboundHold.measure(
                p.getInt(K_ARM_ERA, -1), p.getLong(K_ARM_EPOCH, -1L), ourEra, ourEpoch,
                p.getInt(K_COUNT, 0));
        final StringBuilder sb = new StringBuilder();
        sb.append(p.getBoolean(K_ARMED, false) ? "ARMED" : "disarmed")
                .append(" scope=")
                .append(MlsConversationKey.forLog(p.getString(K_SCOPE, "<none>")))
                .append(" undo=").append(p.contains(K_SNAPSHOT)
                        ? "snapshot to (era=" + p.getInt(K_SNAP_ERA, -1) + " epoch="
                                + p.getLong(K_SNAP_EPOCH, -1L) + ")"
                        : "NONE STASHED")
                .append(" refused=").append(p.getInt(K_REFUSED, 0))
                .append(" · ").append(gap);
        if (!gap.consistent && gap.suppressed > 0) {
            // Claim a clean N-epoch lead only if our epoch moved by exactly N since arming.
            sb.append(" · NOT CONSISTENT — our epoch has not moved by exactly the ")
                    .append(gap.suppressed).append(" commit(s) we withheld, so something else "
                            + "changed it. The lead is real but this lever did not make all of it, "
                            + "and restoring the stashed snapshot will not land where a release "
                            + "would claim.");
        }
        return sb.toString();
    }

    private static String encode(final Held h) {
        return enc(h.ctrlId) + '|' + enc(h.peerE164) + '|' + enc(h.rcsGroupId) + '|'
                + b64(h.groupInfo) + '|' + b64(h.commit) + '|' + b64(h.tag) + '|'
                + b64(h.ratchetTree) + '|' + b64(h.baseEpochAuth) + '|' + h.fromEpoch;
    }

    private static Held decode(final String row) {
        if (row == null) return null;
        final String[] f = row.split("\\|", 9);
        if (f.length < 9) return null;
        try {
            return new Held(dec(f[0]), dec(f[1]), dec(f[2]), unb64(f[3]), unb64(f[4]), unb64(f[5]),
                    unb64(f[6]), unb64(f[7]), Long.parseLong(f[8]));
        } catch (final RuntimeException bad) {
            return null;
        }
    }

    private static String b64(final byte[] b) {
        return (b == null || b.length == 0) ? "" : Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] unb64(final String s) {
        return (s == null || s.isEmpty()) ? null : Base64.decode(s, Base64.NO_WRAP);
    }

    /** '|' separates fields, so it is escaped inside one. */
    private static String enc(final String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\p");
    }

    private static String dec(final String s) {
        if (s == null || s.isEmpty()) return null;
        return s.replace("\\p", "|").replace("\\\\", "\\");
    }
}
