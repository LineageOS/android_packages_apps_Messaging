/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import org.junit.Test;

public final class MlsResendReceiveSplitTest {


    @Test
    public void noResentSelectorIsReadYet() {
        assertNull(MlsResendReceive.resentSelectorField(new byte[] {0, 1, 0}));
    }


    @Test
    public void noResentInnerUnwrapIsAvailableYet() {
        assertNull(MlsResendReceive.resentInnerUnwrap());
    }
}
