/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Runs MLS background work off the caller's thread. Tests swap in {@link #DIRECT} to run the body
 * inline, or {@link Recording} to capture what was scheduled.
 */
public interface MlsOffThread {

    /**
     * Run {@code body} off the caller's thread.
     *
     * @param name thread name, for logs and traces
     */
    void run(String name, Runnable body);

    /** Production: a new named thread per call. */
    MlsOffThread REAL_THREADS = new MlsOffThread() {
        @Override public void run(final String name, final Runnable body) {
            if (body == null) return;
            new Thread(body, name).start();
        }
        @Override public String toString() { return "MlsOffThread.REAL_THREADS"; }
    };

    /** Tests: run the body inline before {@code run} returns; a throw propagates to the caller. */
    MlsOffThread DIRECT = new MlsOffThread() {
        @Override public void run(final String name, final Runnable body) {
            if (body == null) return;
            body.run();
        }
        @Override public String toString() { return "MlsOffThread.DIRECT"; }
    };

    /** Tests: record what would have run and run nothing, until {@link #drain}. */
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
