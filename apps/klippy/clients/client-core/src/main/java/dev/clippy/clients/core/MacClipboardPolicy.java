package dev.clippy.clients.core;

/**
 * macOS desktop behavior: empty content is still sent, queued offline entries are retried
 * after the next read (interleaved with content comparison), read errors omit the single
 * AWT backend name, and read recovery is logged.
 */
public final class MacClipboardPolicy implements DesktopClipboardPolicy {
    @Override
    public boolean ignoreEmptyContent() {
        return false;
    }

    @Override
    public boolean flushPendingBeforeRead() {
        return false;
    }

    @Override
    public boolean includeBackendInReadErrors() {
        return false;
    }

    @Override
    public boolean logReadRecovery() {
        return true;
    }
}
