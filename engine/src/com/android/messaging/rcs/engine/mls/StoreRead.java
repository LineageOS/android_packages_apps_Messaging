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
 * The three-valued read contract: {@code Ok(v)} | {@code NotFound} | {@code Err(reason)}.
 *
 * <p>Rework item 1.2. <b>{@code NotFound} is a NORMAL ANSWER</b> — not a failure, not an empty
 * success. Every store read in the engine boundary returns one of these, and the type makes it
 * impossible to handle two of the three by accident.
 *
 * <h2>Why this is not fussiness</h2>
 *
 * <p>The production path was two-valued by null: every verb returned {@code byte[]}, so "absent"
 * and "error" were the same value. Inside Rust the trait already <em>is</em> three-valued
 * ({@code Result<Option<..>>}), so the information existed and was discarded at the boundary.
 *
 * <p>And where the union did exist — the bridge to Google's MLS engine — it was used in both wrong directions, which
 * is what makes this worth a type rather than a convention:
 *
 * <ul>
 *   <li>A keyed group-state MISS fabricated an {@code Ok} from a process-wide blob belonging to
 *       <em>some other group</em>. That is {@code NotFound} laundered into a wrong {@code Ok} — the
 *       exact failure the union exists to prevent, and the worst possible one, because the engine
 *       then operates on another conversation's state believing it is this one's.</li>
 *   <li>{@code generateMessageId} returned {@code NotFound} when it could not mint an id. §3.3 is
 *       explicit that this is the one callback where the host may NOT say "I have none" — hence
 *       {@link Required}, a deliberately two-valued sibling with no {@code NotFound} constructor.
 *       A host that cannot mint a message id has a bug, not an absence.</li>
 * </ul>
 *
 * @param <T> the value type
 */
public abstract class StoreRead<T> {

    private StoreRead() {}

    /** The value is present. {@code value} may legitimately be a zero-length array; it is not null. */
    public static final class Ok<T> extends StoreRead<T> {
        public final T value;
        Ok(final T value) { this.value = value; }
        @Override public String toString() { return "Ok"; }
    }

    /** No such row. A normal answer — the caller decides whether it is a problem. */
    public static final class NotFound<T> extends StoreRead<T> {
        NotFound() {}
        @Override public String toString() { return "NotFound"; }
    }

    /** The read itself failed. {@code reason} is for a log line, never for control flow. */
    public static final class Err<T> extends StoreRead<T> {
        public final String reason;
        Err(final String reason) { this.reason = reason == null ? "" : reason; }
        @Override public String toString() { return "Err(" + reason + ")"; }
    }

    public static <T> StoreRead<T> ok(final T value) {
        if (value == null) {
            // A null Ok is the two-valued world sneaking back in: the caller would have to null-check
            // a value the type says is present. Say so loudly rather than propagate it.
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
     * The value, or {@code null}.
     *
     * <p>Named to be conspicuous at the call site: it collapses the three values back to two and
     * throws away the distinction this type exists to carry. Legitimate only where the caller has
     * already handled {@code Err} — or is a diagnostic that genuinely does not care.
     */
    public final T orNullDiscardingTheDistinction() {
        return (this instanceof Ok) ? ((Ok<T>) this).value : null;
    }

    /**
     * The two-valued sibling: {@code Ok} | {@code Err}, no {@code NotFound}.
     *
     * <p>For the callbacks §3.3 says the host is not permitted to answer "I have none" —
     * {@code generateMessageId} is the named one. Separate type rather than a runtime rule, so the
     * absence is not expressible.
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
