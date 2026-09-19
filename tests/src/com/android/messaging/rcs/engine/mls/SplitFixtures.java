/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;

import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Shared fixtures for split tests of engine code behind {@code MlsShellPort}: one conversation
 * {@code g:grp} with MLS group {@link #GID}, identity {@code +1}, peer {@code +2}, and a record
 * store the test controls. See docs/testing.md.
 */
final class SplitFixtures {
    static final byte[] GID = {1, 2};
    static final String KEY = "g:grp";

    private SplitFixtures() {}

    static Group grp() {
        final Group g = new Group();
        g.groupId = GID;
        g.rcsGroupId = "grp";
        g.peerE164 = "+2";
        return g;
    }

    /** A port holding {@code grp()} under every key, identity +1, the store, and working locks. */
    static FakeShellPort port(final FakeRecords store) {
        final Set<String> claimed = new HashSet<>();
        return new FakeShellPort().returns("getGroup", grp()).returns("selfE164", "+1")
                .returns("records", store).returns("pendingOpsClaimedThisProcess", claimed)
                .on("lock", a -> null).on("unlock", a -> null).on("moveHealth", a -> null)
                .returns("ensureSession", true);
    }

    /** A store holding one record for {@code (+1, GID)}, edited from the initial one. */
    static FakeRecords storeWith(final UnaryOperator<MlsConversationRecord.Builder> edit) {
        final FakeRecords s = new FakeRecords();
        s.put(edit.apply(MlsConversationRecord.initial("+1", GID, "grp", "+2").toBuilder())
                .build());
        return s;
    }

    /** The record the store now holds for {@code (+1, GID)}. */
    @SuppressWarnings("unchecked")
    static MlsConversationRecord rec(final FakeRecords store) {
        return ((StoreRead.Ok<MlsConversationRecord>) store.get("+1", GID)).value;
    }

    static MlsPendingOperation op(final MlsPendingOperation.Kind kind) {
        return MlsPendingOperation.start(kind, System.currentTimeMillis(), null, "test");
    }
}
