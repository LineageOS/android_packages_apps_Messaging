/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;

import com.android.messaging.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keyless static-map thumbnail for the location card, composited from OpenStreetMap raster tiles
 * into a window centred on the shared point, so the card's centred pin lands on the location. A
 * single tile would place the point at an arbitrary offset. Results are cached per (lat, lon, zoom,
 * size), so each location is fetched once, as the OSM tile usage policy expects.
 */
public final class OsmStaticMap {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** The OSM tile policy requires a descriptive, identifying User-Agent. */
    private static final String USER_AGENT =
            "LineageOS-RCS/1.0 (RCS location card; +https://lineageos.org)";

    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final int TILE = 256;
    /** Street-level zoom: building footprints and street names visible. */
    private static final int DEFAULT_ZOOM = 17;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    /** Run {@code job} on the shared map-builder thread. */
    public static void submit(final Runnable job) {
        EXEC.execute(job);
    }

    /**
     * A cached point-centred map PNG, built on first use; null on any failure. Must be called off
     * the main thread.
     */
    public static File buildCenteredMap(final Context ctx, final double lat,
            final double lon, final int widthPx, final int heightPx) {
        return buildCenteredMap(ctx, lat, lon, DEFAULT_ZOOM, widthPx, heightPx);
    }

    public static File buildCenteredMap(final Context ctx, final double lat,
            final double lon, final int zoom, final int widthPx, final int heightPx) {
        final int w = Math.max(1, Math.min(widthPx, 1024));
        final int h = Math.max(1, Math.min(heightPx, 1024));
        final File dir = new File(ctx.getCacheDir(), "osmmap");
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            return null;
        }
        final File out = new File(dir, String.format(Locale.US,
                "%.5f_%.5f_z%d_%dx%d.png", lat, lon, zoom, w, h));
        if (out.exists() && out.length() > 0) {
            return out;
        }

        final double n = Math.pow(2, zoom);
        // Fractional tile coords of the point, then its global pixel position.
        final double xTileF = (lon + 180.0) / 360.0 * n;
        final double latR = Math.toRadians(lat);
        final double yTileF =
                (1.0 - Math.log(Math.tan(latR) + 1.0 / Math.cos(latR)) / Math.PI) / 2.0 * n;
        final double cxPx = xTileF * TILE;
        final double cyPx = yTileF * TILE;
        final double left = cxPx - w / 2.0;
        final double top = cyPx - h / 2.0;

        final int xMin = (int) Math.floor(left / TILE);
        final int xMax = (int) Math.floor((left + w - 1) / TILE);
        final int yMin = (int) Math.floor(top / TILE);
        final int yMax = (int) Math.floor((top + h - 1) / TILE);
        final int maxTile = (int) n;

        Bitmap canvas = null;
        try {
            canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(canvas);
            int drawn = 0;
            for (int tx = xMin; tx <= xMax; tx++) {
                final int wrappedX = ((tx % maxTile) + maxTile) % maxTile; // longitude wrap
                for (int ty = yMin; ty <= yMax; ty++) {
                    if (ty < 0 || ty >= maxTile) {
                        continue; // off the top/bottom of the world
                    }
                    final Bitmap tile = fetchTile(zoom, wrappedX, ty);
                    if (tile == null) {
                        continue;
                    }
                    final float dx = (float) (tx * (double) TILE - left);
                    final float dy = (float) (ty * (double) TILE - top);
                    c.drawBitmap(tile, dx, dy, null);
                    tile.recycle();
                    drawn++;
                }
            }
            if (drawn == 0) {
                return null; // nothing loaded; the card falls back to pin and address
            }
            final File tmp = new File(dir, out.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                canvas.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            if (!tmp.renameTo(out)) {
                tmp.delete();
                return out.exists() ? out : null;
            }
            return out;
        } catch (final Throwable t) {
            // Class only: an I/O message names the cache file, which encodes the coordinates.
            LogUtil.w(TAG, "OsmStaticMap build failed: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (canvas != null) {
                canvas.recycle();
            }
        }
    }

    private static Bitmap fetchTile(final int z, final int x, final int y) {
        HttpURLConnection con = null;
        try {
            final URL url = new URL(String.format(Locale.US, TILE_URL, z, x, y));
            con = (HttpURLConnection) url.openConnection();
            con.setRequestProperty("User-Agent", USER_AGENT);
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setInstanceFollowRedirects(true);
            final int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                LogUtil.w(TAG, "OSM tile HTTP " + code);
                return null;
            }
            try (InputStream is = con.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (final Throwable t) {
            // Class only: a network exception's message carries the tile URL.
            LogUtil.w(TAG, "OSM tile fetch failed: " + t.getClass().getSimpleName());
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private OsmStaticMap() {}
}
