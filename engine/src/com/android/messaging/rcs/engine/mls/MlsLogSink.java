/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

/**
 * Where engine code writes its log lines, always under {@link MlsLog#TAG}. A port, because the
 * engine cannot depend on the app's Android logger; host tests supply a recording sink.
 */
public interface MlsLogSink {
    /** Debug level; dropped on user builds. */
    void d(String msg);

    void i(String msg);

    void w(String msg);

    void w(String msg, Throwable t);

    void e(String msg);

    void e(String msg, Throwable t);

    /** Discards everything. For callers that have nowhere to report; never the app's default. */
    MlsLogSink NONE = new MlsLogSink() {
        @Override public void d(final String msg) {}
        @Override public void i(final String msg) {}
        @Override public void w(final String msg) {}
        @Override public void w(final String msg, final Throwable t) {}
        @Override public void e(final String msg) {}
        @Override public void e(final String msg, final Throwable t) {}
    };
}
