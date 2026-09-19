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
 * Builds a keyless static-map thumbnail for the geopush location card
 * by fetching raw OpenStreetMap raster tiles and compositing a window
 * <b>centered exactly on the shared point</b>.
 *
 * <p>Why we composite instead of hitting a hosted static-map endpoint:
 * <ul>
 *   <li>{@code staticmap.openstreetmap.de} was decommissioned (NXDOMAIN).</li>
 *   <li>{@code maps.wikimedia.org} returns "Map tiles are restricted to Wikimedia
 *       and affiliated sites only" (HTTP 403) for any non-cached coordinate — it
 *       is not usable by third-party apps.</li>
 *   <li>A single OSM tile is keyless and reliable but places the point at an
 *       arbitrary pixel offset (often a tile edge/corner), so it can't be used
 *       as a centered thumbnail on its own.</li>
 * </ul>
 * So we fetch the (typically 2x2..3x2) tiles covering a point-centered window and
 * crop it. The OSM tile usage policy permits this kind of light, cached client
 * use as long as a descriptive {@code User-Agent} is sent (it is) — we cache the
 * composited PNG per (lat,lon,zoom,size) so each location is fetched at most once.
 *
 * <p>The card layout overlays the pin at the frame center; because the composite
 * is centered on the point, that pin lands exactly on the location.
 */
public final class OsmStaticMap {

    private static final String TAG = LogUtil.BUGLE_TAG;

    /** OSM tile policy requires a descriptive, identifying User-Agent. */
    private static final String USER_AGENT =
            "LineageOS-RCS/1.0 (RCS location card; +https://lineageos.org)";

    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final int TILE = 256;
    /** Street-level zoom (house footprints + street names visible), matching the
     *  tightness of the native Apple/Google Maps location card. z15 was too wide. */
    private static final int DEFAULT_ZOOM = 17;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    /** Run {@code job} on the shared map-builder thread. */
    public static void submit(final Runnable job) {
        EXEC.execute(job);
    }

    /**
     * Return a cached point-centered map PNG for the location, building it (tile
     * fetch + composite) on first use. Returns null on any failure (no network,
     * tile 4xx, decode error). <b>MUST be called off the main thread.</b>
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
                return null; // nothing loaded — let the card fall back to pin+address
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
            LogUtil.w(TAG, "OsmStaticMap build failed: " + t);
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
                LogUtil.w(TAG, "OSM tile " + z + "/" + x + "/" + y + " HTTP " + code);
                return null;
            }
            try (InputStream is = con.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (final Throwable t) {
            LogUtil.w(TAG, "OSM tile fetch failed " + z + "/" + x + "/" + y + ": " + t);
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    private OsmStaticMap() {}
}
