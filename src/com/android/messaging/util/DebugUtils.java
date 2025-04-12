/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024-2025 The LineageOS Project
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

package com.android.messaging.util;

public class DebugUtils {
    /**
     * Returns info about the calling method. The {@code depth} parameter controls how far back to
     * go. For example, if foo() calls bar(), and bar() calls getCaller(0), it returns info about
     * bar(). If bar() instead called getCaller(1), it would return info about foo(). And so on.
     * <p>
     * NOTE: This method retrieves the current thread's stack trace, which adds runtime overhead.
     * It should only be used in production where necessary to gather context about an error or
     * unexpected event (e.g. the {@link Assert} class uses it).
     *
     * @return stack frame information for the caller (if found); otherwise {@code null}.
     */
    public static StackTraceElement getCaller(int depth) {
        // If the signature of this method is changed, proguard.flags must be updated!
        if (depth < 0) {
            throw new IllegalArgumentException("depth cannot be negative");
        }
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        if (trace == null || trace.length < (depth + 2)) {
            return null;
        }
        // The stack trace includes some methods we don't care about (e.g. this method).
        // Walk down until we find this method, and then back up to the caller we're looking for.
        for (int i = 0; i < trace.length - 1; i++) {
            String methodName = trace[i].getMethodName();
            if ("getCaller".equals(methodName)) {
                return trace[i + depth + 1];
            }
        }
        // Never found ourself in the stack?!
        return null;
    }
}
