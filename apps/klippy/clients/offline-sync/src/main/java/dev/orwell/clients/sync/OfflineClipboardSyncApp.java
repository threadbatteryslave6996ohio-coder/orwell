package dev.orwell.clients.sync;

import dev.orwell.logging.ConsoleLogger;

/**
 * Entry point for the offline clipboard sync client. Parsing, wiring, and the sync loop live
 * in {@link OfflineSyncConfig} and {@link OfflineSyncBootstrap}; this class only starts them.
 */
public final class OfflineClipboardSyncApp {
    private OfflineClipboardSyncApp() {
    }

    public static void main(String[] args) throws Exception {
        OfflineSyncBootstrap.fromArgs(args, new ConsoleLogger("klippy-offline-sync-client")).run();
    }
}
