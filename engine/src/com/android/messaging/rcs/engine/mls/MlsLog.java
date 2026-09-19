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
 * The one logcat tag every MLS line carries — host, JNI bridge and Rust engine alike.
 *
 * <p>Rework item 14.3. Before this there were five: {@code MessagingApp} (host, via
 * {@code LogUtil.BUGLE_TAG}), {@code MlsOpenMlsBridge} (JNI), {@code RcsMlsFfi} (Rust),
 * {@code OpenMlsEngine} and {@code OpenMlsNative} — plus {@code MlsCarrierXport} and
 * {@code MlsSelfTest} on the app side. A single MLS operation therefore scattered its lines across
 * seven {@code logcat -s} filters, and reading one operation end to end meant knowing all seven and
 * interleaving them by timestamp. That is not a cosmetic complaint: it is why our capture scripts
 * carried a hand-maintained tag list, and it would have propagated into every state-machine line
 * the rework is about to add.
 *
 * <p>This constant is the canonical copy. Three others must move with it, and none of them can
 * import this class:
 * <ul>
 *   <li>{@code TAG} in {@code jni/mls_openmls_bridge.c}</li>
 *   <li>{@code LOG_TAG} in {@code rust/rcs_mls_ffi/src/ffi.rs}</li>
 *   <li>the tag list in {@code tools/capture/start-capture.sh} and {@code setup-device.sh}</li>
 * </ul>
 *
 * <p>The engine module owns it rather than {@code LogUtil} because the engine cannot depend on the
 * app, only the other way round — and the engine is where the rework is moving the state machine.
 */
public final class MlsLog {
    /** The MLS logcat tag. Keep in lockstep with the three copies named in the class doc. */
    public static final String TAG = "RcsMls";

    private MlsLog() {}
}
