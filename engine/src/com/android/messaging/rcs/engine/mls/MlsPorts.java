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

import java.util.List;

/**
 * The SIX ports the engine may reach the host through — and there is no seventh.
 *
 * <p>Rework item 1.1. This class is the type-level form of that rule: it is {@code final}, its
 * fields are {@code final}, and adding a capability means editing <em>this file</em>. That is the
 * point. A seventh port cannot be smuggled in as a parameter on an existing one, and the review
 * that would let one in is a review of a diff that says so.
 *
 * <h2>What it replaces, and why the replacement is the important part</h2>
 *
 * <p>{@code MlsEngine.startSession} took an {@code android.content.Context} — unbounded ambient
 * authority, network included, handed to the engine as its <em>first parameter</em>. That is the
 * exact opposite of forbidding a transport-shaped port: it is a transport-shaped port plus
 * everything else, and nothing about the type said so. It also made the engine module
 * un-host-testable at the interface level, which is the argument our whole placement decision rests
 * on.
 *
 * <p>Each port grants exactly one capability, and none of them can reach the network:
 *
 * <ul>
 *   <li>{@link GroupStateStore} — group state and epoch records</li>
 *   <li>{@link PendingMessageStore} — Google Messages' {@code ZinniaStateStore}: the opaque
 *       pending-message KV</li>
 *   <li>{@link KeyPackageStore} — KeyPackage secrets, keyed by ref</li>
 *   <li>{@link MessageAccessor} — read-only access to message rows the engine needs</li>
 *   <li>{@link MlsClock} — the one clock source</li>
 *   <li>{@link MlsTelemetry} — counters, and nothing else</li>
 * </ul>
 *
 * <h2>Storage stays engine-owned for now — deliberately</h2>
 *
 * <p>These interfaces exist; the production Rust engine does <b>not</b> yet call through them, and
 * that is the settled decision rather than an omission. Restructuring mls-rs to reach the host
 * through callbacks would be building an adapter for an engine we do not have, and — decisively —
 * Google Messages does not actually get atomicity from that shape: its engine call runs on a background
 * coroutine context and SQLite transaction scope is per-thread. We get atomicity more cheaply by
 * making one engine call one atomic storage unit, which the storage rewrite already did.
 *
 * <p>So the value here is the <em>boundary</em>, not the indirection: the shape of what the engine
 * is allowed to ask for, fixed and reviewable, before the state machine is built on top of it.
 */
public final class MlsPorts {

    /**
     * Group state and its epoch records.
     *
     * <p>Google Messages' {@code GroupStateStore}. {@link #write} takes a BATCH because one engine
     * operation is one storage unit — see the method doc for the invariant that makes the
     * distinction between inserts and updates load-bearing.
     */
    public interface GroupStateStore {

        /** The serialised group state, or {@link StoreRead#notFound()} if this group has none. */
        StoreRead<byte[]> state(byte[] groupId);

        /** One epoch record. */
        StoreRead<byte[]> epoch(byte[] groupId, long epochId);

        /** The highest epoch id held for this group. */
        StoreRead<Long> maxEpochId(byte[] groupId);

        /**
         * Write the state and a batch of epoch records as ONE unit.
         *
         * <p><b>Invariant 68: an UPDATE that matches zero rows aborts the WHOLE write.</b> An update
         * names a record the engine believes exists; if it does not, the engine's model of storage
         * and storage itself have diverged, and completing the write would persist a state built on
         * that false belief. Silently creating the row instead — which is what a store that treats
         * inserts and updates alike does — hides the divergence at precisely the moment it is
         * cheapest to catch.
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

    /**
     * The opaque pending-message KV — Google Messages' {@code ZinniaStateStore}.
     *
     * <p>Opaque by design: the engine owns the encoding, the host owns durability. The host must
     * never interpret a value here, and nothing in this interface invites it to.
     */
    public interface PendingMessageStore {
        StoreRead<byte[]> get(byte[] key);
        String put(byte[] key, byte[] value);      // null on success, else the reason
        String delete(byte[] key);
    }

    /**
     * KeyPackage secrets, keyed by KeyPackageRef.
     *
     * <p>These are the {@code init_key} and {@code leaf_node_key} of every KeyPackage we publish.
     * Losing them is not a degraded state: a peer's Welcome is HPKE-sealed to a KeyPackage we
     * uploaded, so without its secrets the join simply cannot happen — device-observed as
     * {@code WelcomeKeyPackageNotFound} after an in-memory repo let a session's secrets die with it.
     */
    public interface KeyPackageStore {
        StoreRead<byte[]> get(byte[] ref);
        String put(byte[] ref, byte[] secrets);
        /** RFC 9420: KeyPackages are single-use, so a successful join consumes one. */
        String delete(byte[] ref);
    }

    /**
     * Read-only access to the message rows the engine needs.
     *
     * <p>Google Messages' {@code MessageDelegate}. Read-only is the whole shape: the engine asks what a
     * message was, and never writes one.
     */
    public interface MessageAccessor {
        /** The 9-field {@code MessageContent} for a message id. */
        StoreRead<MlsMessageContent> getMessageContent(String messageId);

        /**
         * Per-MSISDN delivery status as an IMDN state.
         *
         * <p>See {@link MlsMessageContent.ImdnState}.
         */
        StoreRead<MlsMessageContent.ImdnState> getDeliveryStatusAsImdn(String messageId,
                String msisdn);

        /**
         * Mint a transport message id.
         *
         * <p>{@link StoreRead.Required} and not {@link StoreRead}: §3.3 is explicit that this is the
         * one callback where the host may NOT answer "I have none". A host that cannot mint a
         * message id has a bug, and the type refuses to let it report one as an absence.
         */
        StoreRead.Required<String> generateMessageId();
    }

    /**
     * The one clock source.
     *
     * <p>A port and not a parameter, because "what time is it" must have exactly one answer inside
     * the engine. Google Messages prefers a trusted-time source and falls back to the device wall clock;
     * the fallback is worth copying, and logging <em>which arm was taken</em> is worth improving on,
     * because Google Messages does not.
     */
    public interface MlsClock {
        /** Milliseconds since the epoch. */
        long nowMs();

        /** True if {@link #nowMs} came from a trusted source rather than the device wall clock. */
        boolean isTrusted();

        /**
         * Which arm produced {@link #nowMs} — rework item {@code 14.2}.
         *
         * <p>Google Messages prefers a trusted-time source and falls back to the device wall clock, and does
         * NOT record which one answered. Recording it is the whole of {@code 14.2}, and it is worth
         * the line: a deadline computed from an untrusted clock behaves differently from one
         * computed from a trusted one on exactly the devices where it matters — a phone whose wall
         * clock jumped — and after the fact the two are indistinguishable in a log.
         */
        default String arm() { return isTrusted() ? ARM_TRUSTED : ARM_SYSTEM; }
    }

    /** {@link MlsClock#arm()} — a trusted time source answered. */
    public static final String ARM_TRUSTED = "TRUSTED";
    /** {@link MlsClock#arm()} — the device wall clock answered. */
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
        // The only port allowed to be absent, because a missing metrics plane must never be the
        // reason an operation fails. The other five have no safe default: silently discarding a
        // state write is not a degraded mode, it is data loss.
        this.telemetry = telemetry == null ? MlsTelemetry.NONE : telemetry;
    }

    /**
     * The bundle for today's arrangement: storage is engine-owned, the rest is host-provided.
     *
     * <p>The three storage ports are present in the type and refuse at runtime. That is deliberate
     * and is the honest encoding of the settled decision — see the class doc. A silent success
     * would be far worse than a refusal: it would let the engine believe a write landed.
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
        @Override public StoreRead<byte[]> state(final byte[] g) { return StoreRead.err(ENGINE_OWNED); }
        @Override public StoreRead<byte[]> epoch(final byte[] g, final long e) {
            return StoreRead.err(ENGINE_OWNED);
        }
        @Override public StoreRead<Long> maxEpochId(final byte[] g) { return StoreRead.err(ENGINE_OWNED); }
        @Override public String write(final byte[] g, final byte[] s, final List<EpochRecord> i,
                final List<EpochRecord> u) {
            return ENGINE_OWNED;
        }
    };

    private static final PendingMessageStore ENGINE_OWNED_PENDING = new PendingMessageStore() {
        @Override public StoreRead<byte[]> get(final byte[] k) { return StoreRead.err(ENGINE_OWNED); }
        @Override public String put(final byte[] k, final byte[] v) { return ENGINE_OWNED; }
        @Override public String delete(final byte[] k) { return ENGINE_OWNED; }
    };

    private static final KeyPackageStore ENGINE_OWNED_KEY_PACKAGES = new KeyPackageStore() {
        @Override public StoreRead<byte[]> get(final byte[] r) { return StoreRead.err(ENGINE_OWNED); }
        @Override public String put(final byte[] r, final byte[] s) { return ENGINE_OWNED; }
        @Override public String delete(final byte[] r) { return ENGINE_OWNED; }
    };

    /**
     * The device wall clock, marked untrusted — the honest default until a trusted source exists.
     *
     * <h2>Rework {@code 14.2}: we ship exactly ONE arm, and that is a recorded decision</h2>
     *
     * <p>{@code 14.2} asks us to log which clock arm produced a deadline. The attribution is built
     * ({@link MlsClock#arm()}) and every deadline can now say. What we deliberately did <b>not</b>
     * do is invent a second arm.
     *
     * <p><b>Why.</b> The plan itself flags it: <i>"on our fleet (cellular-only, no Google account) a
     * trusted-time source may not exist, which is itself a decision to record."</i> Google Messages'
     * trusted-time fallback is only meaningful once there IS something to fall back FROM. Building a
     * second arm we cannot feed would produce an attribution that always says the same thing while
     * implying a choice was made — which is worse than one arm honestly labelled, because it reads
     * as corroboration.
     *
     * <p>So {@link #ARM_SYSTEM} is what our deadlines report, always, today. If a trusted source
     * ever becomes available, implement {@link MlsClock} over it and the attribution starts
     * discriminating with no other change — which is the point of having built it now.
     */
    public static final MlsClock SYSTEM_CLOCK = new MlsClock() {
        @Override public long nowMs() { return System.currentTimeMillis(); }
        @Override public boolean isTrusted() { return false; }
    };
}
