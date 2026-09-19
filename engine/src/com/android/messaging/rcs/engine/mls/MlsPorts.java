/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import java.util.List;

/**
 * The six ports through which the engine may reach the host; none can reach the network. The class
 * and its fields are final, so a new capability means editing this file. The native engine does not
 * yet call the three store ports; storage is engine-owned. See docs/mls/overview.md.
 */
public final class MlsPorts {

    /** Group state and its epoch records; one engine operation is one {@link #write} batch. */
    public interface GroupStateStore {

        /** The serialised group state, or {@link StoreRead#notFound()} if this group has none. */
        StoreRead<byte[]> state(byte[] groupId);

        /** One epoch record. */
        StoreRead<byte[]> epoch(byte[] groupId, long epochId);

        /** The highest epoch id held for this group. */
        StoreRead<Long> maxEpochId(byte[] groupId);

        /**
         * Write the state and a batch of epoch records as one unit. An update that matches zero
         * rows aborts the whole write: the engine's model of storage has diverged from storage.
         *
         * @return {@code null} on success, or the reason the whole write was refused
         */
        String write(byte[] groupId, byte[] state, List<EpochRecord> inserts,
                List<EpochRecord> updates);
    }

    /** One epoch record in a {@link GroupStateStore#write} batch. */
    public static final class EpochRecord {
        public final long id;
        public final byte[] data;

        public EpochRecord(final long id, final byte[] data) {
            this.id = id;
            this.data = data == null ? new byte[0] : data;
        }
    }

    /** The opaque pending-message store: the engine owns the encoding, the host durability. */
    public interface PendingMessageStore {
        StoreRead<byte[]> get(byte[] key);
        String put(byte[] key, byte[] value);      // null on success, else the reason
        String delete(byte[] key);
    }

    /**
     * KeyPackage secrets ({@code init_key}, {@code leaf_node_key}) keyed by KeyPackageRef. Losing
     * them makes a Welcome sealed to that KeyPackage unjoinable.
     */
    public interface KeyPackageStore {
        StoreRead<byte[]> get(byte[] ref);
        String put(byte[] ref, byte[] secrets);
        /** RFC 9420: KeyPackages are single-use, so a successful join consumes one. */
        String delete(byte[] ref);
    }

    /** Read-only access to the message rows the engine needs. */
    public interface MessageAccessor {
        /** The nine-field {@code MessageContent} for a message id. */
        StoreRead<MlsMessageContent> getMessageContent(String messageId);

        /** Per-MSISDN delivery status; see {@link MlsMessageContent.ImdnState}. */
        StoreRead<MlsMessageContent.ImdnState> getDeliveryStatusAsImdn(String messageId,
                String msisdn);

        /** Mint a transport message id; the host may not answer "none". */
        StoreRead.Required<String> generateMessageId();
    }

    /** The one clock source inside the engine. */
    public interface MlsClock {
        /** Milliseconds since the epoch. */
        long nowMs();

        /** True if {@link #nowMs} came from a trusted source rather than the device wall clock. */
        boolean isTrusted();

        /** Which source produced {@link #nowMs}, so a deadline's clock can be logged. */
        default String arm() { return isTrusted() ? ARM_TRUSTED : ARM_SYSTEM; }
    }

    /** {@link MlsClock#arm()}: a trusted time source answered. */
    public static final String ARM_TRUSTED = "TRUSTED";
    /** {@link MlsClock#arm()}: the device wall clock answered. */
    public static final String ARM_SYSTEM = "SYSTEM";

    public final GroupStateStore groupState;
    public final PendingMessageStore pendingMessages;
    public final KeyPackageStore keyPackages;
    public final MessageAccessor messages;
    public final MlsClock clock;
    public final MlsTelemetry telemetry;

    public MlsPorts(final GroupStateStore groupState, final PendingMessageStore pendingMessages,
            final KeyPackageStore keyPackages, final MessageAccessor messages,
            final MlsClock clock, final MlsTelemetry telemetry) {
        this.groupState = groupState;
        this.pendingMessages = pendingMessages;
        this.keyPackages = keyPackages;
        this.messages = messages;
        this.clock = clock;
        // Only telemetry may be absent: a missing metrics plane must never fail an operation.
        this.telemetry = telemetry == null ? MlsTelemetry.NONE : telemetry;
    }

    /**
     * Ports for engine-owned storage: the three store ports refuse at runtime rather than report a
     * write that did not land.
     */
    public static MlsPorts withEngineOwnedStorage(final MessageAccessor messages,
            final MlsClock clock, final MlsTelemetry telemetry) {
        return new MlsPorts(ENGINE_OWNED_GROUP_STATE, ENGINE_OWNED_PENDING,
                ENGINE_OWNED_KEY_PACKAGES, messages, clock, telemetry);
    }

    private static final String ENGINE_OWNED =
            "storage is engine-owned today (see MlsPorts) — this port is declared, not routed; "
            + "reaching it means something started calling through the boundary early";

    private static final GroupStateStore ENGINE_OWNED_GROUP_STATE = new GroupStateStore() {
        @Override public StoreRead<byte[]> state(final byte[] g) { return StoreRead.err(
                ENGINE_OWNED); }
        @Override public StoreRead<byte[]> epoch(final byte[] g, final long e) {
            return StoreRead.err(ENGINE_OWNED);
        }
        @Override public StoreRead<Long> maxEpochId(final byte[] g) { return StoreRead.err(
                ENGINE_OWNED); }
        @Override public String write(final byte[] g, final byte[] s, final List<EpochRecord> i,
                final List<EpochRecord> u) {
            return ENGINE_OWNED;
        }
    };

    private static final PendingMessageStore ENGINE_OWNED_PENDING = new PendingMessageStore() {
        @Override public StoreRead<byte[]> get(final byte[] k) { return StoreRead.err(
                ENGINE_OWNED); }
        @Override public String put(final byte[] k, final byte[] v) { return ENGINE_OWNED; }
        @Override public String delete(final byte[] k) { return ENGINE_OWNED; }
    };

    private static final KeyPackageStore ENGINE_OWNED_KEY_PACKAGES = new KeyPackageStore() {
        @Override public StoreRead<byte[]> get(final byte[] r) { return StoreRead.err(
                ENGINE_OWNED); }
        @Override public String put(final byte[] r, final byte[] s) { return ENGINE_OWNED; }
        @Override public String delete(final byte[] r) { return ENGINE_OWNED; }
    };

    /**
     * The device wall clock, marked untrusted; the only clock source implemented, so every deadline
     * reports {@link #ARM_SYSTEM}.
     */
    public static final MlsClock SYSTEM_CLOCK = new MlsClock() {
        @Override public long nowMs() { return System.currentTimeMillis(); }
        @Override public boolean isTrusted() { return false; }
    };
}
