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
package com.android.messaging.rcs.engine.mls;

/**
 * Backend-neutral 1:1 group-creation output: the four RFC 9420 artifacts the server-side
 * {@code CreateMlsConversation} request consumes, plus the engine-chosen {@code groupId} the
 * transport keys its local storage by.
 *
 * <p>The four artifacts ({@code welcome}/{@code commit}/{@code groupInfo}/{@code tag}) are
 * populated identically by both engines. The {@code tag} is the 32-byte epoch_authenticator of the
 * post-Add epoch. {@code groupId} is asymmetric by origin — Google's engine mints it and passes it
 * IN to {@code create_group}; mls-rs mints it internally and hands it OUT — so it rides here so the
 * neutral caller need not know which engine chose it.
 */
public final class MlsGroupArtifacts {
    public final byte[] welcome;
    public final byte[] commit;
    public final byte[] groupInfo;
    public final byte[] tag;       // 32-byte epoch_authenticator / confirmation tag
    public final byte[] groupId;   // the engine's group id (Google's: minted era-id; OpenMLS: mls-rs gid)
    /** Standalone serialized RFC-9420 ratchet tree of the post-Add epoch. OpenMLS only —
     *  {@code null} from Google's engine, whose opaque output we cannot field-read (see
     *  MlsGroupProtos.parseCreateGroupArtifacts). Google's own create response carries GroupInfo and
     *  ratchet_tree as SEPARATE fields, so this is exported independently of {@link #groupInfo}. */
    public final byte[] ratchetTree;

    /**
     * The era the engine ACTUALLY built this at, or {@code -1} when the engine did not report one.
     *
     * <p>An OUTPUT, never an input. The era exists only as GroupContext extension
     * {@code 0xF001} inside {@link #groupInfo}, so the layer that writes that field is the only one
     * that can honestly answer which era these artifacts are for. When the host chose the number and
     * passed it down, "the era we asked for" and "the era the GroupInfo claims" were separate facts
     * that could disagree, and only the second reached the server.
     *
     * <p>{@code -1} on anything built through an era-taking entry point, where the caller already
     * holds the number it supplied. It is deliberately not defaulted to that number: a field that
     * echoes its own input teaches a reader nothing and would make an un-migrated call site
     * indistinguishable from a migrated one.
     */
    public final long era;

    /**
     * What the engine decided this operation IS — Google Messages' {@code welcomeAction}, {@code null} when the
     * engine named nothing (every era-taking entry point).
     *
     * <p>This is the discriminator the host routes an RPC on:
     * {@link MlsWelcomeAction#NEW_MEMBERSHIP_EXISTING_GROUP} means the bundle holds an
     * {@code addMembers} commit and belongs on the add RPC; the join-accepting actions mean a create.
     * A host that computed this from an era it chose itself would have relocated its decision rather
     * than given it up, so it is only ever populated by an entry point that takes no era.
     */
    public final MlsWelcomeAction welcomeAction;

    /**
     * The MSISDNs this commit actually ADMITS — populated only for
     * {@link MlsWelcomeAction#NEW_MEMBERSHIP_EXISTING_GROUP}, empty otherwise. Never null.
     *
     * <p>The RCS half of an add names participants, and it must name the same people the MLS commit
     * added, or the server sees a roster change and a commit that disagree — which is exactly the
     * {@code mismatched-rcs-group-state} shape. The engine filtered the requested packages down to
     * the ones that were not already members, so it is the only layer that knows the answer;
     * recomputing it in the host would put half the decision back in the host.
     */
    public final java.util.List<String> admittedMembers;

    public MlsGroupArtifacts(byte[] welcome, byte[] commit, byte[] groupInfo, byte[] tag,
            byte[] groupId) {
        this(welcome, commit, groupInfo, tag, groupId, null);
    }

    public MlsGroupArtifacts(byte[] welcome, byte[] commit, byte[] groupInfo, byte[] tag,
            byte[] groupId, byte[] ratchetTree) {
        this(welcome, commit, groupInfo, tag, groupId, ratchetTree, -1L, null, null);
    }

    public MlsGroupArtifacts(byte[] welcome, byte[] commit, byte[] groupInfo, byte[] tag,
            byte[] groupId, byte[] ratchetTree, long era, MlsWelcomeAction welcomeAction,
            java.util.List<String> admittedMembers) {
        this.welcome = welcome; this.commit = commit; this.groupInfo = groupInfo;
        this.tag = tag; this.groupId = groupId; this.ratchetTree = ratchetTree;
        this.era = era; this.welcomeAction = welcomeAction;
        this.admittedMembers = (admittedMembers == null)
                ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<String>(admittedMembers));
    }
}
