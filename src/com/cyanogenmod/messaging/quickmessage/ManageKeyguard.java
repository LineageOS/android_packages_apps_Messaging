/*
 * Copyright (C) 2012 Adam K
 * Modifications Copyright (C) 2012 The CyanogenMod Project
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

package com.cyanogenmod.messaging.quickmessage;

import android.app.KeyguardManager;
import android.app.KeyguardManager.KeyguardLock;
import android.app.KeyguardManager.OnKeyguardExitResult;
import android.content.Context;

public class ManageKeyguard {
    private static String LOGTAG = "ManageKeyguard";
    private static KeyguardManager mKeyguardManager = null;
    private static KeyguardLock mKeyguardLock = null;

    public static synchronized void initialize(Context context) {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        }
    }

    public static synchronized void disableKeyguard(Context context) {
        initialize(context);

        if (mKeyguardManager.inKeyguardRestrictedInputMode()) {
            mKeyguardLock = mKeyguardManager.newKeyguardLock(LOGTAG);
            mKeyguardLock.disableKeyguard();
        } else {
            mKeyguardLock = null;
        }
    }

    public static synchronized boolean inKeyguardRestrictedInputMode() {
        if (mKeyguardManager != null) {
            return mKeyguardManager.inKeyguardRestrictedInputMode();
        }
        return false;
    }

    public static synchronized void reenableKeyguard() {
        if (mKeyguardManager != null) {
            if (mKeyguardLock != null) {
                mKeyguardLock.reenableKeyguard();
                mKeyguardLock = null;
            }
        }
    }

    public static synchronized void exitKeyguardSecurely(final LaunchOnKeyguardExit callback) {
        if (inKeyguardRestrictedInputMode()) {
            mKeyguardManager.exitKeyguardSecurely(new OnKeyguardExitResult() {
                @Override
                public void onKeyguardExitResult(boolean success) {
                    reenableKeyguard();
                    if (success) {
                        callback.LaunchOnKeyguardExitSuccess();
                    }
                }
            });
        } else {
            callback.LaunchOnKeyguardExitSuccess();
        }
    }

    public interface LaunchOnKeyguardExit {
        public void LaunchOnKeyguardExitSuccess();
    }
}
