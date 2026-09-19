/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MlsAppMessageMomentTest {


    @Test
    public void inboundMomentRefusesAnUnreadableEpochRatherThanGuessingOne() {
        assertNull(MlsAppMessage.inboundMoment(new byte[] {1, 2}, new MlsAppMessage.Moment(3, 4)));
        assertNull(MlsAppMessage.inboundMoment(null, null));
    }
}
