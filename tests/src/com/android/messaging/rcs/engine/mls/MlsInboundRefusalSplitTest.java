/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static com.android.messaging.rcs.engine.mls.SplitFixtures.storeWith;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.grp;
import static com.android.messaging.rcs.engine.mls.SplitFixtures.GID;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.function.Function;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Group;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.ConvState;
import com.android.messaging.rcs.engine.mls.MlsTransportTypes.Look;
import org.junit.Test;

public final class MlsInboundRefusalSplitTest {


    @Test
    public void aRefusalIsParsedAsItsMarkerAndOnlyAReplayableOneIsStoredForTheResend() {
        for (final MlsInboundRefusal.Reason reason : MlsInboundRefusal.Reason.values()) {
            final FakeShellPort f = new FakeShellPort();
            f.returns("rendezvous", f.stub(MlsRendezvousAccess.class, "put", null));
            final RccMlsBody.Parsed p =
                    MlsInboundRefusal.refuse(f.port(), "+1", "+2", "m1", reason);
            assertEquals(MlsInboundRefusal.marker(reason), p.contentType);
            assertEquals(reason.toString(), MlsInboundRefusal.replayable(reason),
                    f.calls.contains("MlsRendezvousAccess.put"));
        }
    }
}
