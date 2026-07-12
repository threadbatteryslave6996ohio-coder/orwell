package dev.orwell.loganalyzer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertCooldownTrackerTest {
    @Test
    void rolledBackDeliveryDoesNotStartCooldown() {
        AlertCooldownTracker tracker = new AlertCooldownTracker(60);

        assertTrue(tracker.tryAcquire("disk-pressure", 100));
        tracker.rollback("disk-pressure", 100);

        assertTrue(tracker.tryAcquire("disk-pressure", 101));
    }

    @Test
    void successfulDeliveryStartsCooldown() {
        AlertCooldownTracker tracker = new AlertCooldownTracker(60);

        assertTrue(tracker.tryAcquire("disk-pressure", 100));

        assertFalse(tracker.tryAcquire("disk-pressure", 101));
        assertTrue(tracker.tryAcquire("disk-pressure", 160));
    }

    @Test
    void concurrentAcquireReservesTheSlotAtomically() {
        AlertCooldownTracker tracker = new AlertCooldownTracker(60);

        assertTrue(tracker.tryAcquire("disk-pressure", 100));
        // A second poll for the same fingerprint at the same instant must lose the
        // slot even though the first has not finished delivering its alert yet.
        assertFalse(tracker.tryAcquire("disk-pressure", 100));
    }

    @Test
    void rollbackDoesNotClobberANewerReservation() {
        AlertCooldownTracker tracker = new AlertCooldownTracker(60);

        assertTrue(tracker.tryAcquire("disk-pressure", 100));
        // A newer reservation replaces the first one after the cooldown elapses.
        assertTrue(tracker.tryAcquire("disk-pressure", 200));
        // A late rollback of the stale reservation must not free the newer slot.
        tracker.rollback("disk-pressure", 100);

        assertFalse(tracker.tryAcquire("disk-pressure", 201));
    }
}
