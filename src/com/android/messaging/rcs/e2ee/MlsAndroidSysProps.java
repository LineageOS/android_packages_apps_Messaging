/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.e2ee;

import android.os.SystemProperties;

import com.android.messaging.rcs.RcsDebug;
import com.android.messaging.rcs.engine.mls.MlsSysProps;

/** {@link MlsSysProps} over {@code android.os.SystemProperties}: every call forwards unchanged. */
final class MlsAndroidSysProps implements MlsSysProps {
    static final MlsSysProps INSTANCE = new MlsAndroidSysProps();

    private MlsAndroidSysProps() {}

    @Override public String get(final String key, final String def) {
        return SystemProperties.get(key, def);
    }
    @Override public boolean getBoolean(final String key, final boolean def) {
        return SystemProperties.getBoolean(key, def);
    }
    @Override public int getInt(final String key, final int def) {
        return SystemProperties.getInt(key, def);
    }
    @Override public long getLong(final String key, final long def) {
        return SystemProperties.getLong(key, def);
    }
    /** The app's one reading of it, so the device has a single implementation. */
    @Override
    public boolean debuggableBuild() {
        return RcsDebug.isDebugBuild();
    }
}
