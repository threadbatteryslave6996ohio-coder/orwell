package dev.clippy.clients.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopClipboardPolicyTest {
    @Test
    void linuxPolicySkipsEmptyFlushesEarlyNamesBackendAndSuppressesRecovery() {
        DesktopClipboardPolicy policy = new LinuxClipboardPolicy();

        assertTrue(policy.ignoreEmptyContent());
        assertTrue(policy.flushPendingBeforeRead());
        assertTrue(policy.includeBackendInReadErrors());
        assertFalse(policy.logReadRecovery());
    }

    @Test
    void macPolicySendsEmptyFlushesLateOmitsBackendAndLogsRecovery() {
        DesktopClipboardPolicy policy = new MacClipboardPolicy();

        assertFalse(policy.ignoreEmptyContent());
        assertFalse(policy.flushPendingBeforeRead());
        assertFalse(policy.includeBackendInReadErrors());
        assertTrue(policy.logReadRecovery());
    }
}
