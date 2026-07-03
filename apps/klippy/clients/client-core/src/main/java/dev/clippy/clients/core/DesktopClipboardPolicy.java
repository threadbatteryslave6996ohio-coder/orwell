package dev.clippy.clients.core;

/**
 * Platform behavior for {@link DesktopClipboardMonitor}. Only the two named
 * implementations ({@link LinuxClipboardPolicy}, {@link MacClipboardPolicy}) exist, so an
 * invalid combination of the individual decisions cannot be constructed by accident.
 */
public sealed interface DesktopClipboardPolicy
        permits LinuxClipboardPolicy, MacClipboardPolicy {

    /** Whether an empty clipboard string is treated as "nothing to send". */
    boolean ignoreEmptyContent();

    /** Whether a queued offline entry is retried before each clipboard read (true) or after (false). */
    boolean flushPendingBeforeRead();

    /** Whether read-failure logs include the clipboard backend name. */
    boolean includeBackendInReadErrors();

    /** Whether recovery from a prior read failure is logged. */
    boolean logReadRecovery();
}
