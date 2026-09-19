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

import org.lineageos.rcs.provider.RcsMlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsIdentity;
import com.android.messaging.rcs.engine.mls.MlsSession;
import com.android.messaging.rcs.engine.mls.OpenMlsEngine;
import com.android.messaging.rcs.engine.mls.OpenMlsSession;
import com.android.messaging.rcs.ProviderTransport;
import com.android.messaging.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * The app's MLS identity on disk.
 *
 * <p>Started life as the one-shot cutover copier; the copying is gone now that
 * the migration is complete and the app is the owner. What remains is the permanent thing it left
 * behind: the adopted identity (leaf cert, chain, private key) that {@code MlsProviderTransport}
 * opens every session against. The file name is kept for continuity with devices already cut over.
 *
 * <p>The cutover procedure itself is recoverable from git history if another device ever needs it.
 */
public final class MlsIdentityStore {

    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String PREFS = "mls_state_migration";
    private static final String KEY_DONE_COUNT = "migrated_entry_count";
    /** Where the adopted identity is persisted, alongside the migrated engine state. */
    private static final String IDENTITY_FILE = "mls_migrated_identity.bin";

    private MlsIdentityStore() {}

    /** Outcome of a migration attempt. */
    public static final class Result {
        public final boolean ran;
        public final int total;
        public final int copied;
        public final int failed;
        public final String detail;

        Result(boolean ran, int total, int copied, int failed, String detail) {
            this.ran = ran;
            this.total = total;
            this.copied = copied;
            this.failed = failed;
            this.detail = detail;
        }

        public boolean ok() { return ran && failed == 0; }

        @Override public String toString() {
            return "MlsStateMigration{ran=" + ran + " total=" + total + " copied=" + copied
                    + " failed=" + failed + (detail == null ? "" : " detail=" + detail) + "}";
        }
    }




    /** The adopted identity as the engine's own type, or null if absent/unreadable. */
    /**
     * Pull the current enrolment identity from the provider and store it (contract v31).
     *
     * <p>The KDS leaf is minted and refreshed provider-side on a ~75-day window. Holding the snapshot
     * this class was originally seeded with would mean MLS silently stops working when that leaf
     * expires — so the identity is re-fetched, not just loaded.
     *
     * @return true if a fresh identity was stored
     */
    public static boolean refreshFromProvider(final Context ctx, final int subId) {
        try {
            final org.lineageos.rcs.provider.RcsMlsIdentity id =
                    ProviderTransport.getInstance(ctx).exportMlsIdentity(subId);
            if (id == null || id.leafDer == null || id.subjectPriv == null) return false;
            final File f = new File(ctx.getApplicationContext().getFilesDir(), IDENTITY_FILE);
            final byte[] blob = packIdentity(id);
            if (blob == null || blob.length == 0) return false;
            final File tmp = new File(f.getAbsolutePath() + ".new");
            final FileOutputStream os = new FileOutputStream(tmp);
            os.write(blob);
            os.close();
            if (!tmp.renameTo(f)) { tmp.delete(); return false; }
            LogUtil.i(TAG, "MlsIdentityStore: refreshed enrolment identity from the provider ("
                    + blob.length + "B)");
            return true;
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsIdentityStore: identity refresh failed", t);
            return false;
        }
    }


    public static MlsIdentity loadIdentity(final Context ctx) {
        final File f = new File(ctx.getApplicationContext().getFilesDir(), IDENTITY_FILE);
        if (!f.isFile()) return null;
        FileInputStream in = null;
        try {
            final byte[] buf = new byte[(int) f.length()];
            in = new FileInputStream(f);
            int off = 0;
            while (off < buf.length) {
                final int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off != buf.length) return null;
            final List<byte[]> p = OpenMlsSession.splitLenPrefixed(buf);
            if (p.size() < 6) return null;
            final String e164 = new String(p.get(0), "UTF-8");
            // Element 6 (revoked serials, contract v50) is absent in a blob written before 0.9.
            // Size-checked rather than assumed: a stored identity outlives the build that wrote
            // it, and reading a missing element would turn every pre-v50 device into a null
            // identity — MLS off, with nothing pointing at the format change as the cause.
            final List<byte[]> revoked = p.size() > 6
                    ? OpenMlsSession.splitLenPrefixed(p.get(6))
                    : java.util.Collections.<byte[]>emptyList();
            return new MlsIdentity(e164, p.get(1),
                    OpenMlsSession.splitLenPrefixed(p.get(2)),
                    p.get(3), p.get(4),
                    OpenMlsSession.splitLenPrefixed(p.get(5)),
                    revoked);
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsIdentityStore: loadIdentity failed", t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (final Throwable ignore) { }
        }
    }


    /** {e164, leaf, chain, priv, pub, roots, revokedSerials} as one length-prefixed blob. */
    private static byte[] packIdentity(final RcsMlsIdentity id) {
        final java.util.ArrayList<byte[]> parts = new java.util.ArrayList<>(7);
        try {
            parts.add(id.e164.getBytes("UTF-8"));
        } catch (final Throwable t) {
            parts.add(new byte[0]);
        }
        parts.add(id.leafDer == null ? new byte[0] : id.leafDer);
        parts.add(id.chainDer == null ? new byte[0] : id.chainDer);
        parts.add(id.subjectPriv == null ? new byte[0] : id.subjectPriv);
        parts.add(id.subjectPub == null ? new byte[0] : id.subjectPub);
        parts.add(id.roots == null ? new byte[0] : id.roots);
        // v50. Always written, even when empty — an empty revocation list is Google Messages' normal
        // state and a meaningful value, not an omission.
        parts.add(id.revokedSerials == null ? new byte[0] : id.revokedSerials);
        return OpenMlsSession.joinLenPrefixed(parts);
    }

    /** Write via temp + rename so an interrupted copy never leaves a truncated entry behind. */
    private static boolean writeAtomic(final File base, final String rel, final byte[] data) {
        final File dest = new File(base, rel);
        final File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            LogUtil.w(TAG, "MlsIdentityStore: cannot create " + parent);
            return false;
        }
        final File tmp = new File(dest.getAbsolutePath() + ".migrating");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (final Throwable t) {
            LogUtil.w(TAG, "MlsIdentityStore: write failed for " + rel, t);
            deleteQuietly(tmp);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (final Throwable ignore) { }
        }
        if (!tmp.renameTo(dest)) {
            // rename() will not clobber on some filesystems; retry after removing the old entry.
            deleteQuietly(dest);
            if (!tmp.renameTo(dest)) {
                LogUtil.w(TAG, "MlsIdentityStore: rename failed for " + rel);
                deleteQuietly(tmp);
                return false;
            }
        }
        return true;
    }

    private static void deleteQuietly(final File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (final Throwable ignore) { }
    }
}
