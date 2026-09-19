/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;

import org.lineageos.rcs.provider.RcsSendResult;

import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;
import java.util.UUID;

/**
 * Debug-only driver for an outbound file send through {@link ProviderTransport#sendFile}. By
 * default it generates a unique random-noise JPEG, large enough to exceed the inline limit and
 * unique so the upload is never de-duplicated, writes it to {@link MediaScratchFileProvider} and
 * sends it; the provider sees only a file descriptor. Ignored unless the build is debuggable.
 * <pre>
 *   adb shell am broadcast -a com.android.messaging.debug.SEND_FT \
 *     -n com.android.messaging/.rcs.RcsDebugFtSendReceiver \
 *     --es to &lt;e164&gt; [--es caption "hi"] [--ei px 640]
 * </pre>
 */
public final class RcsDebugFtSendReceiver extends BroadcastReceiver {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final String ACTION_SEND_FT = "com.android.messaging.debug.SEND_FT";
    private static final String EXTRA_TO = "to";
    /** 32-hex group id; when present the file goes to the group and {@code to} is ignored. */
    private static final String EXTRA_GID = "gid";
    private static final String EXTRA_CAPTION = "caption";
    private static final String EXTRA_PX = "px";
    /**
     * Override the MIME type (default image/jpeg). A non-image type sends a random blob instead of
     * a JPEG, exercising the file-only path with no thumbnail.
     */
    private static final String EXTRA_MIME = "mime";
    /**
     * Size of the random blob for a non-image send (default 65536). A large value exercises the
     * multi-pass upload and the resuming download.
     */
    private static final String EXTRA_BYTES = "bytes";
    /**
     * Path of a real file to send verbatim with {@code mime}, overriding the generators.
     */
    private static final String EXTRA_FILE = "file";
    private static final String MIME = "image/jpeg";

    /**
     * A 1.7 KB valid H.264 MP4 (160x120, about 0.5 s), so a {@code video/mp4} send is
     * self-contained: random bytes do not decode, and the provider's thumbnail extraction needs a
     * real clip.
     */
    private static final String SAMPLE_MP4_B64 =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAANibW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAA"
          + "AAAD6AAAAfQAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAA"
          + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAo10cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAAB"
          + "AAAAAAAAAfQAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAA"
          + "AKAAAAB4AAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAH0AAAQAAABAAAAAAIFbWRpYQAAACBtZGhk"
          + "AAAAAAAAAAAAAAAAAABAAAAAIABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRl"
          + "b0hhbmRsZXIAAAABsG1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAA"
          + "AQAAAAx1cmwgAAAAAQAAAXBzdGJsAAAAwHN0c2QAAAAAAAAAAQAAALBhdmMxAAAAAAAAAAEAAAAAAAAA"
          + "AAAAAAAAAAAAAKAAeABIAAAASAAAAAAAAAABFUxhdmM2MS4xOS4xMDEgbGlieDI2NAAAAAAAAAAAAAAA"
          + "GP//AAAANmF2Y0MBZAAK/+EAGWdkAAqs2UKEflwEQAAAAwBAAAAEA8SJZYABAAZo6+PLIsD9+PgAAAAA"
          + "EHBhc3AAAAABAAAAAQAAABRidHJ0AAAAAAAAMVAAAAAAAAAAGHN0dHMAAAAAAAAAAQAAAAQAAAgAAAAA"
          + "FHN0c3MAAAAAAAAAAQAAAAEAAAAoY3R0cwAAAAAAAAADAAAAAQAAEAAAAAABAAAgAAAAAAIAAAgAAAAA"
          + "HHN0c2MAAAAAAAAAAQAAAAEAAAAEAAAAAQAAACRzdHN6AAAAAAAAAAAAAAAEAAAC6wAAABAAAAANAAAA"
          + "DQAAABRzdGNvAAAAAAAAAAEAAAOSAAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRp"
          + "cmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSpdG9vAAAAHGRhdGEAAAABAAAAAExhdmY2MS43LjEwMAAA"
          + "AAhmcmVlAAADHW1kYXQAAAKtBgX//6ncRem95tlIt5Ys2CDZI+7veDI2NCAtIGNvcmUgMTY0IHIzMTA4"
          + "IDMxZTE5ZjkgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDIzIC0gaHR0"
          + "cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MSByZWY9MyBkZWJs"
          + "b2NrPTE6MDowIGFuYWx5c2U9MHgzOjB4MTEzIG1lPWhleCBzdWJtZT03IHBzeT0xIHBzeV9yZD0xLjAw"
          + "OjAuMDAgbWl4ZWRfcmVmPTEgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0xIDh4OGRjdD0x"
          + "IGNxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PS0yIHRocmVh"
          + "ZHM9NCBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0xIGlu"
          + "dGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTMgYl9w"
          + "eXJhbWlkPTIgYl9hZGFwdD0xIGJfYmlhcz0wIGRpcmVjdD0xIHdlaWdodGI9MSBvcGVuX2dvcD0wIHdl"
          + "aWdodHA9MiBrZXlpbnQ9MjUwIGtleWludF9taW49OCBzY2VuZWN1dD00MCBpbnRyYV9yZWZyZXNoPTAg"
          + "cmNfbG9va2FoZWFkPTQwIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxY29tcD0wLjYwIHFwbWluPTAg"
          + "cXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0xOjEuMDAAgAAAADZliIQAEP/+5sD5lloh"
          + "44BQh2uGTrSLlJeKTDK3g+nEAz9MaDD5s++ko5CpSMgKIAA5QRRoFgkAAAAMQZojbEO//qmWAHrAAAA"
          + "ACUGeQXiHfwA9YQAAAAkBnmJqQ78APWA=";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (intent == null || !ACTION_SEND_FT.equals(intent.getAction())) {
            return;
        }
        final boolean debuggable = RcsDebug.isDebugBuild();
        if (!debuggable) {
            LogUtil.w(TAG, "DEBUG SEND_FT ignored: build is not debuggable");
            return;
        }

        final String to = intent.getStringExtra(EXTRA_TO);
        final String gid = intent.getStringExtra(EXTRA_GID);
        if (TextUtils.isEmpty(to) && TextUtils.isEmpty(gid)) {
            LogUtil.w(TAG, "DEBUG SEND_FT missing 'to' (or 'gid') extra; ignoring");
            return;
        }
        final String caption = intent.getStringExtra(EXTRA_CAPTION);
        final int px = clamp(intent.getIntExtra(EXTRA_PX, 640), 64, 2048);
        final String mimeArg = intent.getStringExtra(EXTRA_MIME);
        final String mime = TextUtils.isEmpty(mimeArg) ? MIME : mimeArg;
        final boolean isImage = mime.toLowerCase().startsWith("image/");
        final boolean isVideo = mime.toLowerCase().startsWith("video/");
        final int synthBytes = intent.getIntExtra(EXTRA_BYTES, 65536);
        final String filePath = intent.getStringExtra(EXTRA_FILE);

        int subId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            subId = SubscriptionManager.getDefaultDataSubscriptionId();
        }
        final int sub = subId;
        final Context appCtx = context.getApplicationContext();
        final PendingResult pr = goAsync();

        new Thread(() -> {
            try {
                final String messageId = UUID.randomUUID().toString();
                final String ext = extFor(mime, isImage);
                final Uri uri;
                if (!TextUtils.isEmpty(filePath)) {
                    uri = copyFileToScratch(appCtx, filePath, ext);
                } else if (isImage) {
                    uri = makeUniqueJpeg(appCtx, px, messageId);
                } else if (isVideo) {
                    uri = makeSampleVideo(appCtx);
                } else {
                    uri = makeSyntheticBlob(appCtx, synthBytes, ext);
                }
                if (uri == null) {
                    LogUtil.w(TAG, "DEBUG SEND_FT could not create test payload");
                    return;
                }
                final long size = sizeOf(uri);
                final String fileName = "rcs-ft-" + System.currentTimeMillis() + "." + ext;
                final boolean toGroup = !TextUtils.isEmpty(gid);
                LogUtil.i(TAG, "DEBUG SEND_FT " + (toGroup ? "gid=" + gid : "to=" + to)
                        + " subId=" + sub
                        + " messageId=" + messageId + " uri=" + uri + " mime=" + mime
                        + " size=" + size + (isImage ? " px=" + px : " synthBytes=" + synthBytes)
                        + " -> ProviderTransport.sendFile");

                final ProviderTransport transport = ProviderTransport.getInstance(appCtx);
                final RcsSendResult r = transport.sendFile(sub, messageId,
                        toGroup ? null : to, uri, mime, fileName, size, caption,
                        toGroup ? gid : null);
                LogUtil.i(TAG, "DEBUG SEND_FT result accepted=" + (r != null && r.accepted)
                        + " reasonCode=" + (r == null ? -1 : r.reasonCode)
                        + " reason=" + (r == null ? "null" : r.reason)
                        + " messageId=" + messageId);
            } catch (final Throwable t) {
                LogUtil.w(TAG, "DEBUG SEND_FT failed", t);
            } finally {
                pr.finish();
            }
        }, "rcs-debug-ft-send").start();
    }

    /**
     * A unique random-noise JPEG in a MediaScratchFileProvider URI. Noise compresses poorly, so it
     * reliably exceeds the inline limit, and uniqueness defeats upload de-duplication.
     */
    private static Uri makeUniqueJpeg(final Context ctx, final int px,
            final String tag) throws Exception {
        final Uri uri = MediaScratchFileProvider.buildMediaScratchSpaceUri("jpg");
        final File f = MediaScratchFileProvider.getFileFromUri(uri);
        if (f == null) {
            return null;
        }
        final Bitmap bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        final Random rnd = new Random();
        final int[] row = new int[px];
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                row[x] = 0xFF000000 | (rnd.nextInt() & 0x00FFFFFF);
            }
            bmp.setPixels(row, 0, px, 0, y, px, 1);
        }
        // Stamp a label so each rendered image can be matched to its send.
        final Canvas c = new Canvas(bmp);
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(px / 12f);
        p.setShadowLayer(4f, 0, 0, Color.BLACK);
        c.drawText("RCS FT", px / 16f, px / 2f, p);
        c.drawText(tag.substring(0, 8), px / 16f, px / 2f + px / 10f, p);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        bmp.recycle();
        return uri;
    }

    /** A random {@code n}-byte blob in a MediaScratchFileProvider URI, for the file-only path. */
    private static Uri makeSyntheticBlob(final Context ctx, final int n,
            final String ext) throws Exception {
        final int bytes = n < 1 ? 1 : n;
        final Uri uri = MediaScratchFileProvider.buildMediaScratchSpaceUri(ext);
        final File f = MediaScratchFileProvider.getFileFromUri(uri);
        if (f == null) {
            return null;
        }
        final Random rnd = new Random();
        final byte[] buf = new byte[64 * 1024];
        int remaining = bytes;
        try (FileOutputStream out = new FileOutputStream(f)) {
            while (remaining > 0) {
                rnd.nextBytes(buf);
                final int w = Math.min(remaining, buf.length);
                out.write(buf, 0, w);
                remaining -= w;
            }
        }
        return uri;
    }

    /**
     * Copy a real on-device file into a MediaScratchFileProvider URI; the provider sees only the
     * descriptor.
     */
    private static Uri copyFileToScratch(final Context ctx, final String path,
            final String ext) throws Exception {
        final File src = new File(path);
        if (!src.exists() || !src.canRead()) {
            LogUtil.w(TAG, "DEBUG SEND_FT file not readable: " + path);
            return null;
        }
        final Uri uri = MediaScratchFileProvider.buildMediaScratchSpaceUri(ext);
        final File dst = MediaScratchFileProvider.getFileFromUri(uri);
        if (dst == null) {
            return null;
        }
        final byte[] buf = new byte[64 * 1024];
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return uri;
    }

    /** Decode the embedded sample MP4 into a MediaScratchFileProvider URI. */
    private static Uri makeSampleVideo(final Context ctx) throws Exception {
        final byte[] mp4 = android.util.Base64.decode(SAMPLE_MP4_B64,
                android.util.Base64.DEFAULT);
        final Uri uri = MediaScratchFileProvider.buildMediaScratchSpaceUri("mp4");
        final File f = MediaScratchFileProvider.getFileFromUri(uri);
        if (f == null) {
            return null;
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(mp4);
        }
        return uri;
    }

    /** A sensible file extension for the MIME / scratch URI. */
    private static String extFor(final String mime, final boolean isImage) {
        if (isImage) {
            return "jpg";
        }
        final String m = mime.toLowerCase();
        if (m.equals("application/pdf")) {
            return "pdf";
        }
        if (m.startsWith("video/")) {
            return m.contains("mp4") ? "mp4" : "vid";
        }
        if (m.startsWith("audio/")) {
            return "aud";
        }
        return "bin";
    }

    private static long sizeOf(final Uri uri) {
        final File f = MediaScratchFileProvider.getFileFromUri(uri);
        return (f != null && f.exists()) ? f.length() : -1;
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
