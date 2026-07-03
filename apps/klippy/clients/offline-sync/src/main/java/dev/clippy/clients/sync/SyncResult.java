package dev.clippy.clients.sync;

/** Outcome of synchronizing one batch of offline clipboard records. */
record SyncResult(int alreadyPresent, int sent, int rejected) {
    SyncResult(int alreadyPresent, int sent) {
        this(alreadyPresent, sent, 0);
    }
}
