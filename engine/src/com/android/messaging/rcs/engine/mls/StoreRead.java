/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * A store read's result: {@code Ok(v)}, {@code NotFound} or {@code Err(reason)}. {@code NotFound}
 * is a normal answer, and must never be turned into an {@code Ok} from some other row or group.
 * {@link Required} is the two-valued form for reads the host may not answer with "none".
 *
 * @param <T> the value type
 */
public abstract class StoreRead<T> {

    private StoreRead() {}

    /** Present; {@code value} is never null but may be a zero-length array. */
    public static final class Ok<T> extends StoreRead<T> {
        public final T value;
        Ok(final T value) { this.value = value; }
        @Override public String toString() { return "Ok"; }
    }

    /** No such row; the caller decides whether that is a problem. */
    public static final class NotFound<T> extends StoreRead<T> {
        NotFound() {}
        @Override public String toString() { return "NotFound"; }
    }

    /** The read failed. {@code reason} is for logging, never for control flow. */
    public static final class Err<T> extends StoreRead<T> {
        public final String reason;
        Err(final String reason) { this.reason = reason == null ? "" : reason; }
        @Override public String toString() { return "Err(" + reason + ")"; }
    }

    public static <T> StoreRead<T> ok(final T value) {
        if (value == null) {
            // An absent value is NotFound; a null Ok would need a null check the type says is
            // unneeded.
            return new Err<>("Ok(null) — an absent value must be NotFound, not Ok");
        }
        return new Ok<>(value);
    }

    @SuppressWarnings("unchecked")
    public static <T> StoreRead<T> notFound() { return (StoreRead<T>) NOT_FOUND; }

    public static <T> StoreRead<T> err(final String reason) { return new Err<>(reason); }

    private static final NotFound<Object> NOT_FOUND = new NotFound<>();

    public final boolean isOk() { return this instanceof Ok; }
    public final boolean isNotFound() { return this instanceof NotFound; }
    public final boolean isErr() { return this instanceof Err; }

    /** The value, or {@code fallback} for {@code NotFound} AND for {@code Err}. */
    public final T orElse(final T fallback) {
        return (this instanceof Ok) ? ((Ok<T>) this).value : fallback;
    }

    /**
     * The value, or {@code null}, collapsing {@code NotFound} and {@code Err}. Only for callers
     * that have already handled {@code Err}, or diagnostics.
     */
    public final T orNullDiscardingTheDistinction() {
        return (this instanceof Ok) ? ((Ok<T>) this).value : null;
    }

    /**
     * {@code Ok} or {@code Err}, with no {@code NotFound}: for host callbacks such as
     * {@code generateMessageId}, where failing to produce a value is a bug rather than an absence.
     */
    public static abstract class Required<T> {
        private Required() {}

        public static final class Ok<T> extends Required<T> {
            public final T value;
            Ok(final T value) { this.value = value; }
        }

        public static final class Err<T> extends Required<T> {
            public final String reason;
            Err(final String reason) { this.reason = reason == null ? "" : reason; }
        }

        public static <T> Required<T> ok(final T value) {
            return value == null ? new Err<T>("Ok(null)") : new Ok<>(value);
        }

        public static <T> Required<T> err(final String reason) { return new Err<>(reason); }

        public final boolean isOk() { return this instanceof Ok; }

        public final T orNull() { return (this instanceof Ok) ? ((Ok<T>) this).value : null; }
    }
}
