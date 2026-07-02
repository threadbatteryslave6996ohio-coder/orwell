package dev.clippy.clients.core;

/**
 * Linux desktop behavior: empty content is skipped, queued offline entries are retried
 * before the next read, read errors name the backend, and read recovery is not logged
 * (Linux backends fail routinely, so recovery noise is suppressed).
 */
public final class LinuxClipboardPolicy implements DesktopClipboardPolicy {
    @Override
    public boolean ignoreEmptyContent() {
        return true;
    }

    @Override
    public boolean flushPendingBeforeRead() {
        return true;
    }

    @Override
    public boolean includeBackendInReadErrors() {
        return true;
    }

    @Override
    public boolean logReadRecovery() {
        return false;
    }
}
