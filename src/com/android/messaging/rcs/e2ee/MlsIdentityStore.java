/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
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
 * The app's MLS identity on disk: the leaf certificate, chain and private key that
 * {@code MlsProviderTransport} opens every session against, refreshed from the provider. The file
 * and preference names are kept for compatibility with identities already stored.
 */
public final class MlsIdentityStore {

    private static final String TAG = com.android.messaging.rcs.engine.mls.MlsLog.TAG;
    private static final String PREFS = "mls_state_migration";
    private static final String KEY_DONE_COUNT = "migrated_entry_count";
    /** Where the identity is persisted. */
    private static final String IDENTITY_FILE = "mls_migrated_identity.bin";

    private MlsIdentityStore() {}

    /** Outcome of a migration attempt. Unused. */
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




    /**
     * Fetch the provider's current enrolment identity and store it. The provider renews the leaf,
     * so the identity must be re-fetched, not only loaded, or MLS stops when the stored leaf
     * expires.
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


    /** The stored identity as the engine type, or null if absent or unreadable. */
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
            // Element 6 (revoked serials) is absent from blobs written by older builds; a stored
            // identity outlives the build that wrote it, so read it only when present.
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
        // Always written: an empty revocation list is the normal state, not an omission.
        parts.add(id.revokedSerials == null ? new byte[0] : id.revokedSerials);
        return OpenMlsSession.joinLenPrefixed(parts);
    }

    /**
     * Write via temp and rename, so an interrupted write never leaves a truncated entry. Unused.
     */
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
            // rename() does not replace an existing file on some filesystems.
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
