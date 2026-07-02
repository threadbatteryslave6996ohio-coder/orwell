# Offline File-Locker Service

The file-locker is the only Clippy process that reads or writes the offline
clipboard JSON file. Linux clipboard clients send append requests and the
offline sync client requests snapshots over a local Unix-domain socket.

The daemon applies a fair read/write lock per normalized file path. Appends are
written to a temporary file and atomically moved into place, so readers receive
either the complete old JSON array or the complete new array. The socket and
offline files are restricted to the owning user where POSIX permissions are
available.

Repeating an append with an identical JSON object is idempotent, so desktop
clients can safely retry the exact pending entry after an IPC timeout.

Start the service before either client:

```bash
./scripts/start-file-locker.sh
```

Maven packaging keeps the thin `clippy-file-locker` JAR for other modules and
creates the runnable `clippy-file-locker-0.1.0-SNAPSHOT-exec.jar` separately.

The default socket is `/tmp/clippy-offline-file-locker.sock`. Override it for
the service and both clients with the same environment or `.env` value:

```dotenv
OFFLINE_FILE_LOCKER_SOCKET=/run/user/1000/clippy-file-locker.sock
```

The service removes a stale socket at startup, refuses to replace a socket with
an active listener, and removes its own socket during normal shutdown.
