# Offline Clipboard Sync Client

This long-running Java client monitors clipboard records from the Linux client's
`clippy-offline-clipboard.json` file and synchronizes changes to the Clippy
server. It checks immediately at startup and then every 30 minutes.

It queries the server for the file's inclusive timestamp range, compares records
by `clientId`, `content`, and `timestamp`, and posts only records that are not
already stored. Auth audit records (`"type": "auth"`) are ignored because they
are not clipboard data. After every successful pass, the source JSON file is
atomically cleared to `[]`. If another process appends during synchronization,
the clear is skipped and the updated file is handled on the next pass.

Malformed entries, unknown record types, and records rejected by the server
with a record-specific `400`, `413`, `415`, or `422` response are appended to a
sibling dead-letter file before synchronization continues. Known auth audit
records remain intentionally ignored. For the default input, the dead-letter
file is `clippy-offline-clipboard-dead-letter.json`. Each entry records the
processing stage, rejection reason, and original JSON. Dead-letter appends are
idempotent. The source snapshot is not cleared unless every rejected entry was
safely recorded.

Configure the same values used by the clipboard client in the repository `.env`:

```dotenv
REMOTE_SERVER_URL=http://localhost:8080
AUTH_SERVER_URL=http://localhost:8081
CLIENT_ID=ubuntu-gnome
CLIENT_SECRET=change-me-please
OFFLINE_SYNC_INTERVAL_MINUTES=30
```

`CLIENT_TOKEN` can be used instead of `CLIENT_SECRET`. If `CLIENT_ID` is omitted,
the sync client uses the single client id found in the file. If the file is
missing, unreadable, empty, or contains only oversized legacy entries at
startup, it waits and retries every 30 minutes; a usable clipboard entry must
appear before the client id can be derived.
`OFFLINE_SYNC_INTERVAL_MINUTES` is optional, defaults to `30`, and must be at
least `1`.

Retryable file-locker, network, authentication, throttling, and server failures
use delays of 5, 10, 20, 40, and 80 seconds. If the fifth retry fails, the sync
process exits with an error and leaves the offline source file unchanged. The
configured sync interval is used again after a successful pass.

Run it from the repository root:

```bash
./scripts/start-file-locker.sh
```

Keep the file-locker running, then sync in another terminal:

```bash
./scripts/sync-offline-client.sh
```

The default input is `clippy-offline-clipboard.json`. Pass another path as the
first argument when needed:

```bash
./scripts/sync-offline-client.sh /path/to/clippy-offline-clipboard.json
```

The sync client obtains the JSON snapshot through the same Unix-domain socket
used for Linux-client appends. It never reads the file directly, so an append
cannot overlap a snapshot read. It only contacts the server when the JSON
snapshot changes. Failed reads and sync attempts are retried on the next
configured check.

Clipboard values over 1,000,000 characters are not uploaded. Current clients
do not write them to the offline log; oversized entries left by an older client
are excluded before client-id validation, ignored, and removed when the snapshot
is cleared.
