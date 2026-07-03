package dev.clippy.clients.sync;

import java.io.IOException;

/** Persists clipboard entries that could not be synchronized (typically to a dead-letter log). */
@FunctionalInterface
interface RejectionSink {
    void reject(RejectedRecord rejection) throws IOException;
}
