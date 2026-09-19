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
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import com.android.messaging.rcs.engine.mls.MlsOutboundHold;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>TEST FIXTURE.</b> The durable half of the outbound commit hold — what was
 * withheld, the state to put back, and how to give either one up. The decision half is
 * {@link MlsOutboundHold}, in the engine, where it is host-testable.
 *
 * <h2>Two things are stashed, and they are for two different releases</h2>
 *
 * <ul>
 *   <li><b>The SNAPSHOT taken before the FIRST suppressed commit</b>, once. Restoring it undoes every
 *       commit withheld since, because each one was applied on top of the last. There is no reason to
 *       keep the later snapshots and a good reason not to: a group snapshot is engine state, not a
 *       wire payload, and eight of them in a preferences file is a fixture that fails in a way
 *       nothing reports.</li>
 *   <li><b>The ARTIFACTS of each suppressed commit, in order</b> — the bytes {@code applyMlsControl}
 *       would have carried. These are for the OTHER release: publish them late and the server
 *       catches up instead of us rewinding.</li>
 * </ul>
 *
 * <h2>Why persisted rather than static</h2>
 *
 * <p>{@link MlsInboundHoldStore}'s reason, unchanged: this app restarts constantly (provider wakes,
 * installs, low-memory kills) and a fixture has to survive minutes of a conversation living while
 * nobody is watching. A process-static flag would silently disarm itself before most runs. Its own
 * preferences file so clearing the fixture can never touch conversation state.
 *
 * <p>Inert on a non-debuggable build at BOTH ends — the debug arm refuses to set it and this class
 * refuses to act on it. The gate sits on the production SEND path, and the one thing that must be
 * impossible is a restored data directory arming it on a user build.
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
     * eng/userdebug only — {@link MlsInboundHoldStore#buildAllowsFixtures}'s test, and for the reason
     * recorded there: {@code ApplicationInfo.FLAG_DEBUGGABLE} is false for a system app even on a
     * userdebug image, so it is the wrong question.
     */
    public static boolean buildAllowsFixtures() {
        return "eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE);
    }

    private SharedPreferences prefs() {
        return mCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** One withheld commit — everything {@code applyMlsControl} would have been given. */
    public static final class Held {
        public final String ctrlId;
        public final String peerE164;
        public final String rcsGroupId;
        public final byte[] groupInfo;
        public final byte[] commit;
        public final byte[] tag;
        public final byte[] ratchetTree;
        public final byte[] baseEpochAuth;
        /** The epoch we were at when this commit was built, i.e. the one it commits from. */
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

    // ---- ARM STATE ----------------------------------------------------------------------------

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

    /** Arm the lever on one conversation. {@code armEra}/{@code armEpoch} are the measurement base. */
    public synchronized void arm(final String scopeKey, final int armEra, final long armEpoch) {
        prefs().edit()
                .putBoolean(K_ARMED, true)
                .putString(K_SCOPE, scopeKey)
                .putInt(K_ARM_ERA, armEra)
                .putLong(K_ARM_EPOCH, armEpoch)
                .commit();
    }

    /**
     * Stop suppressing. The stash is KEPT — disarming and releasing are separate acts.
     *
     * <p>Separate for {@link MlsInboundHoldStore}'s reason: disarm-and-keep leaves the divergence
     * standing with the undo still in hand, which is what a recovery test wants, and
     * release-and-restore is the check that the lever itself is reversible.
     */
    public synchronized void disarm() {
        prefs().edit().putBoolean(K_ARMED, false).commit();
    }

    // ---- THE GATE -----------------------------------------------------------------------------

    /**
     * Should this commit be withheld?
     *
     * <p>Pure lookup — the caller does the suppressing, because it holds the snapshot and the
     * artifacts and this class must not reach into the engine.
     */
    public synchronized MlsOutboundHold.Verdict verdictFor(final String conversationKey,
            final boolean isRekey) {
        if (!isArmed()) return MlsOutboundHold.Verdict.PUBLISH;
        final String scope = scopeKey();
        final boolean scopeMatches = scope != null && scope.equals(conversationKey);
        return MlsOutboundHold.decide(true, scopeMatches, isRekey, heldCount());
    }

    /**
     * Record a withheld commit, and — on the FIRST one — the snapshot that undoes it.
     *
     * <p>The snapshot is stored ONCE, with the era and epoch read at the same moment. Those two are
     * not decoration: they are what {@code MlsOutboundHold.restoredCleanly} compares against, and
     * without them a release could only report that {@code restoreGroupSnapshot} did not throw.
     *
     * @return true if the commit was recorded; false means it was NOT stashed and the caller must
     *     not treat it as suppressed
     */
    public synchronized boolean suppress(final Held h, final byte[] snapshot, final int preEra,
            final long preEpoch) {
        if (h == null || h.commit == null || h.commit.length == 0) return false;
        final int count = prefs().getInt(K_COUNT, 0);
        final SharedPreferences.Editor e = prefs().edit();
        if (count == 0) {
            if (snapshot == null || snapshot.length == 0 || preEra < 0 || preEpoch < 0L) {
                // REFUSE TO SUPPRESS WITHOUT AN UNDO. This is the one thing the fixture must never
                // do: withhold a commit it cannot take back leaves the device permanently ahead of
                // a group that never saw it, recoverable only by a rebuild or a re-Welcome — the
                // expensive routes the fixture exists so that tests do not have to consume.
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
                + "B, from epoch " + h.fromEpoch + ", ctrlId=" + h.ctrlId + ") for " + h.rcsGroupId
                + "/" + h.peerE164 + ". Now holding " + (count + 1) + "/" + MlsOutboundHold.CAPACITY
                + ". This device is a test fixture right now — it is AHEAD of this conversation BY "
                + "CONSTRUCTION, not by a fault, and the group will never see this commit.");
        return true;
    }

    public synchronized void noteRefused() {
        prefs().edit().putInt(K_REFUSED, prefs().getInt(K_REFUSED, 0) + 1).commit();
    }

    // ---- READ / RELEASE -----------------------------------------------------------------------

    /** The snapshot taken before the first suppressed commit, or null. */
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

    /** The era/epoch the snapshot restores to, or -1 when nothing is stashed. */
    public synchronized int snapshotEra() {
        return prefs().getInt(K_SNAP_ERA, -1);
    }

    public synchronized long snapshotEpoch() {
        return prefs().getLong(K_SNAP_EPOCH, -1L);
    }

    /** Everything withheld, in the order it was withheld. */
    public synchronized List<Held> held() {
        final SharedPreferences p = prefs();
        final int n = p.getInt(K_COUNT, 0);
        final List<Held> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final Held h = decode(p.getString(K_HELD + i, null));
            if (h == null) {
                LogUtil.w(TAG, "MlsOutboundHoldStore: held row " + i + " is unreadable and is being "
                        + "skipped — a late PUBLISH will be INCOMPLETE, and the commits after it "
                        + "will be refused because their base epoch authenticator names a state the "
                        + "server never reached.");
                continue;
            }
            out.add(h);
        }
        return out;
    }

    /** Forget everything stashed. Called by every release once it has finished with the bytes. */
    public synchronized void clearStash() {
        final SharedPreferences.Editor e = prefs().edit();
        final int n = prefs().getInt(K_COUNT, 0);
        for (int i = 0; i < n; i++) e.remove(K_HELD + i);
        e.remove(K_SNAPSHOT).remove(K_SNAP_ERA).remove(K_SNAP_EPOCH).putInt(K_COUNT, 0).commit();
    }

    // ---- STATUS -------------------------------------------------------------------------------

    /**
     * A one-line status. Every number is measured — the engine's era/epoch are read from the engine
     * and the group's is derived from the commits we withheld — so a test can assert the fixture
     * rather than assume it. Costs no server look-up.
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
                .append(" scope=").append(p.getString(K_SCOPE, "<none>"))
                .append(" undo=").append(p.contains(K_SNAPSHOT)
                        ? "snapshot to (era=" + p.getInt(K_SNAP_ERA, -1) + " epoch="
                                + p.getLong(K_SNAP_EPOCH, -1L) + ")"
                        : "NONE STASHED")
                .append(" refused=").append(p.getInt(K_REFUSED, 0))
                .append(" · ").append(gap);
        if (!gap.consistent && gap.suppressed > 0) {
            // The house rule applied to the instrument: this line may not claim a clean N-epoch lead
            // unless our epoch actually moved by N since the lever was armed.
            sb.append(" · NOT CONSISTENT — our epoch has not moved by exactly the ")
                    .append(gap.suppressed).append(" commit(s) we withheld, so something else "
                            + "changed it. The lead is real but this lever did not make all of it, "
                            + "and restoring the stashed snapshot will not land where a release "
                            + "would claim.");
        }
        return sb.toString();
    }

    // ---- helpers ------------------------------------------------------------------------------

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

    /** '|' is the row separator, so it must not survive inside a field. */
    private static String enc(final String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("|", "\\p");
    }

    private static String dec(final String s) {
        if (s == null || s.isEmpty()) return null;
        return s.replace("\\p", "|").replace("\\\\", "\\");
    }
}
