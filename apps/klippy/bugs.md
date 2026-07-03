# Code review findings

1. `clients/client-core/src/main/java/dev/clippy/clients/core/DesktopClipboardMonitor.java:41-66,180-181`

   mac mode sets `flushPendingBeforeRead=false`, so `poll()` always tries `clipboardReader.readText()` before retrying a previously failed offline append. If the client already has a `pendingOfflineEntry` and clipboard reads keep failing, the method returns from the `catch` block at lines 54-56 and never reaches `appendPendingOffline()`. Result: once a send has fallen back to the offline queue, recovery of the file-locker alone is not enough to drain it on macOS; the clipboard must also start reading successfully again.

2. `clients/offline-sync/src/main/java/dev/clippy/sync/RejectedRecord.java:12-18`

   `RejectedRecord.forRecord(...)` claims to preserve the rejected record’s “raw JSON”, but it reconstructs a new object containing only `clientId`, `content`, and `timestamp`. Any original `type` field or future metadata is dropped before the dead-letter entry is written. That makes the dead-letter log lossy and can break the README guarantee that it stores the original JSON for rejected entries.

3. `clients/dummy/src/main/java/dev/clippy/dummy/DummyClientApp.java:57-75` together with `clients/client-core/src/main/java/dev/clippy/clients/core/ClipboardApiClient.java:62-77`

   `ClipboardApiClient` rethrows runtime failures from `authSession.refresh()` during a 401 retry. `DummyClientApp.sendCommand()` only catches `IOException` and `InterruptedException`, so an auth refresh failure after token expiry will escape as an uncaught runtime exception and terminate the interactive dummy client instead of reporting a normal send failure. The Linux/mac clients handle this path explicitly; the dummy client does not.
