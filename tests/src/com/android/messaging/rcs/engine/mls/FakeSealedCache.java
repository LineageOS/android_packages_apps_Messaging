/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.LinkedHashMap;
import java.util.Map;

/** An in-memory {@link MlsSealedCacheAccess}: sealed messages by id, and every release recorded. */
public final class FakeSealedCache implements MlsSealedCacheAccess {
    public final Map<String, MlsSealedMessage> byId = new LinkedHashMap<>();
    public final java.util.List<String> released = new java.util.ArrayList<>();

    @Override public MlsSealedMessage get(final String id) { return byId.get(id); }
    @Override public void put(final MlsSealedMessage sealed) { byId.put(sealed.messageId, sealed); }
    @Override public void release(final String id) {
        released.add(id);
        byId.remove(id);
    }
    @Override public int releaseConversation(final String key, final String why) {
        released.add("conversation:" + key);
        return 0;
    }
    @Override public void invalidateForReEncrypt(final String id, final String why) {
        released.add("invalidate:" + id);
        byId.remove(id);
    }
    @Override public int size() { return byId.size(); }
    @Override public int sweepExpired(final long maxAgeMs, final long nowElapsedMs) { return 0; }
}
