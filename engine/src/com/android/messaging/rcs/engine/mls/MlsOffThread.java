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
 * The off-thread seam for MLS background work.
 *
 * <p>Every recovery behaviour in {@code MlsProviderTransport} is verified by reading logcat on a
 * device, because the paths that matter run off-thread and a host test cannot join a raw
 * {@code new Thread(...)}. This is the one swap that makes such a path run INLINE. It is a
 * TESTABILITY seam, explicitly not a re-architecture: the serialisation property a
 * {@code ConversationActor} would have delivered is already delivered by the per-conversation locks,
 * which is why none was added.
 *
 * <h2>Why it lives here and not nested in the caller</h2>
 *
 * <p>It began as a private interface inside {@code MlsProviderTransport}, and there it could never
 * pay off: that class needs a {@code Context}, {@code SharedPreferences} and a bound provider, there
 * is no Robolectric in this tree, and every MLS host test links {@code messaging-mls-policy-host}
 * — a deliberately pure subset. A seam that can only be reached through an un-loadable class is a
 * seam with no tests, which is what it had. Pulled out here it is plain Java, host-testable on its
 * own terms, and available to the other {@code new Thread(...)} sites across {@code rcs/e2ee}
 * instead of being one class's private arrangement.
 *
 * <p><b>The seam alone does not make the ladder host-testable</b>, and it is worth saying so plainly
 * so nobody reads this class as finishing that job: the blocker for driving {@code selfHeal} on a
 * host is its Android dependencies, not its threading. Note also that {@code selfHeal} is already
 * synchronous — it returns an {@code int} to its caller and spawns nothing — so the half of
 * the request that it "become drivable" was already satisfied by its shape.
 */
public interface MlsOffThread {

    /**
     * Run {@code body} off the caller's thread.
     *
     * @param name thread name, for logs and traces — a real name is worth having when a stall has
     *             to be diagnosed from a bug report
     */
    void run(String name, Runnable body);

    /**
     * PRODUCTION: exactly the {@code new Thread(body, name).start()} this replaced.
     *
     * <p>The default is a real thread so production behaviour is byte-identical to what stood here
     * before the seam existed. A seam whose default changes behaviour is a refactor pretending to be
     * a test affordance.
     */
    MlsOffThread REAL_THREADS = new MlsOffThread() {
        @Override public void run(final String name, final Runnable body) {
            if (body == null) return;
            new Thread(body, name).start();
        }
        @Override public String toString() { return "MlsOffThread.REAL_THREADS"; }
    };

    /**
     * TESTS: run the body inline, before {@code run} returns.
     *
     * <p>This is the whole point — an assertion written after the call sees the work already done,
     * with no sleep, no latch and no flake. A body that throws propagates to the caller rather than
     * dying on a background thread where a test would never see it: a swallowed exception here would
     * turn a failing test green, which is worse than no test.
     */
    MlsOffThread DIRECT = new MlsOffThread() {
        @Override public void run(final String name, final Runnable body) {
            if (body == null) return;
            body.run();
        }
        @Override public String toString() { return "MlsOffThread.DIRECT"; }
    };

    /**
     * TESTS: record what would have run, and run nothing.
     *
     * <p>For asserting that a path <em>scheduled</em> work without letting the work happen — the
     * distinction that matters when the question is "did the trigger fire", not "what did it do".
     */
    final class Recording implements MlsOffThread {
        private final java.util.List<String> mNames =
                java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        private final java.util.List<Runnable> mBodies =
                java.util.Collections.synchronizedList(new java.util.ArrayList<Runnable>());

        @Override public void run(final String name, final Runnable body) {
            mNames.add(name);
            mBodies.add(body);
        }

        /** Names, in the order they were scheduled. */
        public java.util.List<String> names() {
            return new java.util.ArrayList<String>(mNames);
        }

        public int count() { return mNames.size(); }

        /** Run everything recorded so far, in order, then forget it. */
        public void drain() {
            final java.util.List<Runnable> todo;
            synchronized (mBodies) {
                todo = new java.util.ArrayList<Runnable>(mBodies);
                mBodies.clear();
                mNames.clear();
            }
            for (final Runnable r : todo) {
                if (r != null) r.run();
            }
        }
    }
}
