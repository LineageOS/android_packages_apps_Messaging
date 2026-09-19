/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.messaging.rcs.engine.mls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.android.messaging.rcs.engine.mls.MlsFetchLedger.Caller;

import org.junit.Test;

/**
 * {@link MlsFetchLedgerRecord}: the counters survive a restart and cannot be refilled by moving the
 * clock. See docs/mls/budgets.md.
 */
public final class MlsFetchLedgerRecordTest {

    private static final long W = MlsFetchLedger.WINDOW_MS;

    @Test
    public void nothingStoredIsEmptyNotNull() {
        assertSame(MlsFetchLedgerRecord.EMPTY, MlsFetchLedgerRecord.decode(null));
        assertSame(MlsFetchLedgerRecord.EMPTY, MlsFetchLedgerRecord.decode(""));
        assertTrue(MlsFetchLedgerRecord.decode(null).isEmpty());
    }

    @Test
    public void somethingStoredAndUnreadableIsNull() {
        // An unreadable record read as "no charges" would hand back a full allowance on a parse
        // failure.
        assertNull("no version prefix", MlsFetchLedgerRecord.decode("garbage"));
        assertNull("wrong version", MlsFetchLedgerRecord.decode("2|100:0"));
        assertNull("no caller", MlsFetchLedgerRecord.decode("1|100"));
        assertNull("empty caller", MlsFetchLedgerRecord.decode("1|100:"));
        assertNull("empty stamp", MlsFetchLedgerRecord.decode("1|:0"));
        assertNull("non-numeric stamp", MlsFetchLedgerRecord.decode("1|abc:0"));
        assertNull("non-numeric caller", MlsFetchLedgerRecord.decode("1|100:x"));
        assertNull("negative stamp", MlsFetchLedgerRecord.decode("1|-1:0"));
        assertNull("negative caller", MlsFetchLedgerRecord.decode("1|100:-1"));
    }

    @Test
    public void anOversizedRecordIsRefusedRatherThanParsedUnbounded() {
        final StringBuilder b = new StringBuilder("1|");
        for (int i = 0; i < 400; i++) {
            if (i > 0) b.append(',');
            b.append(i).append(":0");
        }
        assertNull("a corrupt or hostile record must not turn one budget lookup into an unbounded "
                + "parse", MlsFetchLedgerRecord.decode(b.toString()));
    }

    /** A stale ordinal is dropped; refusing the whole record would deny every other caller. */
    @Test
    public void aChargeForAVanishedCallerIsDroppedNotRefused() {
        final int beyond = Caller.values().length + 7;
        final MlsFetchLedgerRecord r = MlsFetchLedgerRecord.decode("1|100:0,100:" + beyond);
        assertNotNull("a stale ordinal must not read as corruption", r);
        assertEquals("the stale entry is dropped and the readable one kept", 1, r.size());
    }

    @Test
    public void chargesRoundTrip() {
        MlsFetchLedgerRecord r = MlsFetchLedgerRecord.EMPTY;
        r = r.charged(Caller.SELF_HEAL, 1_000L);
        r = r.charged(Caller.RECONCILE_DRIVE, 2_000L);
        r = r.charged(Caller.SELF_HEAL, 3_000L);
        final MlsFetchLedgerRecord back = MlsFetchLedgerRecord.decode(r.encode());
        assertNotNull(back);
        assertEquals(3, back.size());
        assertEquals(2, back.spentBy(Caller.SELF_HEAL, 3_000L));
        assertEquals(1, back.spentBy(Caller.RECONCILE_DRIVE, 3_000L));
        assertEquals(3, back.spentAgainstCeiling(3_000L));
    }

    @Test
    public void aNullCallerChargesNothing() {
        final MlsFetchLedgerRecord r = MlsFetchLedgerRecord.EMPTY.charged(null, 1_000L);
        assertTrue("a charge with no declared caller must not be recorded under some default",
                r.isEmpty());
        assertEquals(0, r.spentBy(null, 1_000L));
    }

    @Test
    public void chargesAgeOutOfTheWindow() {
        final MlsFetchLedgerRecord r = MlsFetchLedgerRecord.EMPTY.charged(Caller.SELF_HEAL, 1_000L);
        assertEquals("inside the window", 1, r.spentBy(Caller.SELF_HEAL, 1_000L + W));
        assertEquals("one millisecond past it", 0, r.spentBy(Caller.SELF_HEAL, 1_000L + W + 1));
    }

    @Test
    public void pruningDropsOnlyWhatHasAgedOut() {
        MlsFetchLedgerRecord r = MlsFetchLedgerRecord.EMPTY
                .charged(Caller.SELF_HEAL, 1_000L)
                .charged(Caller.SELF_HEAL, 1_000L + W);
        assertSame("nothing to drop yet, and the caller must be able to skip a write",
                r, r.pruned(1_000L + W));
        r = r.pruned(1_000L + W + 1);
        assertEquals(1, r.size());
        assertEquals(1, r.spentBy(Caller.SELF_HEAL, 1_000L + W + 1));
    }

    /**
     * A backwards reading means a reboot, and at most the current uptime can be shown to have
     * passed, so the entry is retained longer, never dropped early.
     */
    @Test
    public void aBackwardsClockDoesNotRefillTheLedger() {
        final MlsFetchLedgerRecord r =
                MlsFetchLedgerRecord.EMPTY.charged(Caller.SELF_HEAL, 5_000_000L);
        // A reboot: elapsedRealtime restarts near zero, far short of the window.
        assertEquals("a reboot must not hand the allowance back — the SERVER's throttle did not "
                + "restart with us", 1, r.spentBy(Caller.SELF_HEAL, 1_000L));
        // Once the window has elapsed in uptime it refills: a rate, never a permanent charge.
        assertEquals(0, r.spentBy(Caller.SELF_HEAL, W + 1));
    }

    @Test
    public void aRestartKeepsTheCharges() {
        final String stored = MlsFetchLedgerRecord.EMPTY
                .charged(Caller.SELF_HEAL, 1_000L)
                .charged(Caller.REBUILD, 1_500L)
                .encode();
        final MlsFetchLedgerRecord afterRestart = MlsFetchLedgerRecord.decode(stored);
        assertNotNull(afterRestart);
        assertEquals("the per-drive counter could forget on restart because a drive does not "
                + "outlive the process. This window does.",
                2, afterRestart.spentAgainstCeiling(2_000L));
    }

    /** Callers held outside the ceiling are excluded from the sum as well as from the test. */
    @Test
    public void callersHeldOutsideTheCeilingDoNotInflateTheSharedTotal() {
        MlsFetchLedgerRecord r = MlsFetchLedgerRecord.EMPTY;
        for (final Caller c : Caller.values()) {
            if (!c.chargesTheSharedCeiling) r = r.charged(c, 1_000L);
        }
        assertTrue("there is at least one caller held outside the ceiling, or this test asserts "
                + "nothing", r.size() > 0);
        assertEquals("none of them may count toward the shared total", 0,
                r.spentAgainstCeiling(1_000L));
        r = r.charged(Caller.SELF_HEAL, 1_000L);
        assertEquals("but a ceiling-charging caller does", 1, r.spentAgainstCeiling(1_000L));
    }

    @Test
    public void aRecordCannotGrowWithoutBound() {
        MlsFetchLedgerRecord r = MlsFetchLedgerRecord.EMPTY;
        // All at one instant, so nothing ages out and only the trim can bound it.
        for (int i = 0; i < 500; i++) r = r.charged(Caller.SELF_HEAL, 1_000L);
        assertTrue("the record grew to " + r.size() + " entries; a ledger that grows without bound "
                + "is a preference file that eventually cannot be parsed", r.size() <= 256);
        assertNotNull("and it must still round-trip at its cap",
                MlsFetchLedgerRecord.decode(r.encode()));
    }
}
