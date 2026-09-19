/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link MlsPendingBodyAccess}: bodies by message id; every release recorded. */
public final class FakePendingBodies implements MlsPendingBodyAccess {
    public final Map<String, byte[]> bodies = new HashMap<>();
    public final List<String> released = new ArrayList<>();

    @Override public byte[] get(final String id) { return bodies.get(id); }
    @Override public void put(final String id, final byte[] body) { bodies.put(id, body); }
    @Override public void release(final String id) {
        released.add(id);
        bodies.remove(id);
    }
    @Override public List<String> sweepExpired(final long maxAgeMs) { return new ArrayList<>(); }
}
