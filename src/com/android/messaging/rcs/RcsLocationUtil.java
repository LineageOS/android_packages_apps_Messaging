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
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.text.TextUtils;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Helpers for the geopush location card: a {@code geo:} open-in-maps
 * URI, a lat/lon display string, and a reverse-geocode with a hard timeout (falls
 * back to lat/lon when the geocoder is absent — e.g. a Google-free build — or
 * doesn't answer in time).
 *
 * <p>The map thumbnail itself is built by {@link OsmStaticMap} (point-centered
 * OSM-tile composite). We do NOT use a hosted static-map endpoint:
 * {@code staticmap.openstreetmap.de} is gone (NXDOMAIN) and
 * {@code maps.wikimedia.org} restricts tiles to Wikimedia sites (HTTP 403) — both
 * dead ends we learned the hard way; do not resurrect either.
 */
public final class RcsLocationUtil {

    /** Shared single-thread executor for the bounded reverse-geocode call. */
    private static final ExecutorService GEOCODE_EXEC =
            Executors.newSingleThreadExecutor();

    /** {@code geo:} URI that opens the device's default map app on tap. */
    public static String geoUri(final double lat, final double lon, final String label) {
        final String coords = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        String q = coords;
        if (!TextUtils.isEmpty(label)) {
            q += "(" + Uri.encode(label) + ")";
        }
        return "geo:" + coords + "?q=" + q;
    }

    /** Raw "lat, lon" display string (the geocode fallback). */
    public static String latLonText(final double lat, final double lon) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lon);
    }

    /**
     * Reverse-geocode to a one-line address, bounded by {@code timeoutMs}. Returns
     * null when the geocoder backend is absent (Google-free builds), the lookup
     * fails, or it doesn't return within the timeout — the caller shows lat/lon.
     * MUST be called off the main thread.
     */
    public static String reverseGeocode(final Context ctx, final double lat,
            final double lon, final long timeoutMs) {
        if (ctx == null || !Geocoder.isPresent()) {
            return null;
        }
        final Future<String> f = GEOCODE_EXEC.submit(new Callable<String>() {
            @Override
            public String call() {
                try {
                    final Geocoder geocoder = new Geocoder(ctx, Locale.getDefault());
                    final List<Address> addrs = geocoder.getFromLocation(lat, lon, 1);
                    if (addrs == null || addrs.isEmpty()) {
                        return null;
                    }
                    return formatAddress(addrs.get(0));
                } catch (final Throwable t) {
                    return null;
                }
            }
        });
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final Throwable t) {
            f.cancel(true);
            return null;
        }
    }

    /** Compact one-line address: street + locality, falling back to whatever the
     *  geocoder gives. */
    private static String formatAddress(final Address a) {
        if (a == null) {
            return null;
        }
        final String line0 = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
        if (!TextUtils.isEmpty(line0)) {
            return line0;
        }
        final StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(a.getThoroughfare())) {
            if (!TextUtils.isEmpty(a.getSubThoroughfare())) {
                sb.append(a.getSubThoroughfare()).append(' ');
            }
            sb.append(a.getThoroughfare());
        }
        if (!TextUtils.isEmpty(a.getLocality())) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.getLocality());
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private RcsLocationUtil() {}
}
