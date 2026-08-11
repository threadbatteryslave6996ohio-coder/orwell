# Jarvis

Surveillance services (detection, retention) and macOS/Linux recorder clients,
the latter under `clients/`.
Alert delivery is no longer part of jarvis; it is a standalone app at `apps/alerting`.
The upload proxy the recorders push to is likewise standalone, at
`apps/object-storage-proxy` — it also bundles the stream analysis worker and its ingest
scripts.

Authentication is supplied by `apps/auth`. Build all services from the repository root:

```bash
mvn -pl apps/jarvis -am package
```

The recorder clients under `clients/` remain standalone shell applications.

The log analyzer is now a standalone app; see `apps/log-analyzer`.

The detection service supports `SERVER_ENGINE=undertow` for the lightweight
runtime and `SERVER_ENGINE=spring` for Spring Boot/Tomcat. Both expose the same
`GET /health`, `POST /detect` and `POST /motion` endpoints.
The Undertow adapter limits detection bodies to 16 MiB and returns a JSON
`413` response with `request body too large` when that limit is exceeded.

#### `POST /motion` — frame-to-frame change detection

Takes the same body as `/detect` (`frameBase64`, optional `frameSha256`, `source`,
`frameIndex`, `timestamp`) and answers whether the frame differs from the previous frame
seen for the same `source`:

```json
{"success":true,"source":"cam1","frameIndex":42,"timestamp":null,
 "totalCells":256,"changed":true,"firstFrame":false,
 "changedCells":64,"changedFraction":0.25}
```

Each frame is reduced to a 16x16 box-averaged luminance grid and only that grid is kept
between requests, so per-source state is a fixed ~1 KB and the comparison is
resolution-independent. A cell counts as changed when its average luminance moves by more
than `DETECTION_MOTION_CELL_THRESHOLD` (0-255, default `12` — above JPEG and sensor noise);
the frame counts as changed once `DETECTION_MOTION_MIN_CHANGED_FRACTION` of the 256 cells
move (default `0.02`, about 5 cells). Raise either to be less twitchy, lower them to catch
smaller movement.

The first frame for a source reports `"changed": false` with `"firstFrame": true` — there is
nothing to compare against yet, and reporting a change would fire on every stream start.
At most 64 sources are tracked, evicting the least recently seen, since `source` comes
straight off the request body. State is in-memory and per-process: a restart makes the next
frame from every source a first frame again.

Unlike `/detect` this endpoint is stateful and fires no alerts — it only reports the
comparison. The stream worker posts to a single `STREAM_ANALYSIS_ENDPOINT`, so point it at
`/detect`, `/motion` or `/frames`, not several.

### Frame hub — push in, stream out, replay what was missed

Detection doubles as the central hub: producers push frames to it, it stores them, and it pushes
them straight on to every client connected at that moment. A client that was away reconnects and
is replayed what it missed before rejoining the live stream. **Requires Postgres and
`SERVER_ENGINE=spring`.**

The hub does three things and no more — **receive, store, redistribute**. It does not decode a
frame, compare it against the last one, or decide that a repeat is not worth sending: every frame
pushed to it is stored and relayed. Change detection lives at `/motion`, and a producer that wants
to relay only interesting frames asks that question itself before pushing. The hub is a pipe, so
the payload does not even have to be an image it could decode.

```
recorders ──POST /frames──> hub ──┬──SSE──> clients connected now
                                  │
                             frame_events ──replay──> a client that reconnects
                                  │
                    FrameRetentionJob trims to the byte/age budget
```

`POST /frames` takes the same body as `/detect` and `/motion`, and answers

```json
{"success":true,"source":"cam1","frameIndex":42,"timestamp":"2026-08-10T12:00:00Z",
 "stored":true,"frameId":91,"recipients":2}
```

— so a camera can tell nobody is watching without polling anything (`frameIndex` and `timestamp`
are echoed back from the request, and are null if it did not send them). A frame with zero
recipients is still stored, so a client connecting later can still replay it. The envelope is
validated (base64, and `frameSha256` against the bytes if sent) but the bytes are not. Ingest
returns as soon as the frame is queued for each client, never after they have received it, so a
producer's frame rate is never coupled to the slowest viewer.

**The frame is broadcast before it is written.** Its id comes from a Postgres sequence allocated in
blocks up front, so the SSE event id exists before the row does and the live stream never waits on
a database write — a Postgres stall slows catch-up, not the video. The write happens behind the
broadcast on a single writer thread, which keeps rows landing in id order so replay stays
contiguous.

That buys speed at a stated cost: if the write queue fills or the process dies, a frame can be
delivered live and never stored. It is then simply missing from the id sequence, which a
reconnecting client skips over; `framesUnstoredTotal` counts them. Set `DETECTION_STORE_MODE=sync`
to write on the ingest thread instead, so anything broadcast is guaranteed replayable — at the
price of putting Postgres back on the live path.

`GET /frames/stream` is a Server-Sent Events stream:

| Parameter | Effect |
|---|---|
| `?source=cam1` | Only that camera; omitted receives every source |
| `?subscription=<name>` | Durable cursor — the hub remembers this name's position across reconnects |
| `?from=<frameId>` | Start after an explicit frame; `0` replays everything still stored |
| `Last-Event-ID:` header | Standard SSE resume, sent automatically by a browser's `EventSource` |

Precedence is `Last-Event-ID` → `from` → the stored cursor for `subscription` → the current head.
So a plain connection sees what happens next rather than the whole retention window, and an
unknown subscription name likewise starts fresh rather than replaying everything.

The SSE event id **is** the `frame_events.id`, which is what makes resuming exact. On connect the
hub emits a `connected` event (which also flushes the response headers, so a viewer knows it is
live rather than still connecting) naming where it will resume from:

```
event: connected
data: {"connected":true,"source":null,"subscription":"viewer-1","resumingAfter":90}

id: 91
event: frame
data: {"frameId":91,"source":"cam1","frameIndex":42,"capturedAt":"2026-08-10T12:00:00Z",
       "sha256":"9f2c…","frameBase64":"…"}
```

**Replay hands over to live without gaps or duplicates.** One virtual thread per connection runs
two phases: it reads stored frames forward from the start cursor, and only when the store is
exhausted does it switch to the live queue. Live frames are queued from the moment the connection
registers — not from when catch-up finishes — so nothing falls into the gap between phases, and the
live phase skips any queued frame whose id it already replayed.

**A slow client cannot slow the producers.** Broadcast only ever enqueues; each connection owns a
bounded queue (`DETECTION_STREAM_QUEUE_DEPTH`) drained by its own thread. When a client falls
behind, the hub drops that client's **oldest** frame — on a live feed the freshest frame is the
useful one, and unbounded buffering would turn a slow viewer into an out-of-memory error. A named
subscription re-fetches dropped frames from the store on its next reconnect; an unnamed one loses
them. Drops are counted and reported on `GET /health`, alongside `connectedClients`,
`framesReceivedTotal`, `framesDistributedTotal`, `framesReplayedTotal`, `framesPendingWrite` and
`framesUnstoredTotal`.

> **Sizing.** `frame_events` holds the frame bytes, because replay means a frame must still exist to
> be re-sent, and the hub stores every frame it is pushed — at 40 KB and 5 fps that is ~200 KB/s
> *per source*. `DETECTION_FRAME_MAX_BYTES` (2 GiB) is what stops that from becoming a disk
> problem, so the question is not how much you can store but how much history that budget buys:
> ~2.8 hours for one such source, ~20 minutes across eight. Raise the budget, or push less —
> sending only frames that differ is a producer's decision, and it can ask `/motion` first or make
> the call locally, which is cheaper than shipping a frame to find out.
>
> It is a **write** rate too, not just disk: eight cameras at 5 fps is 40 inserts/s through one
> writer thread and a 512-frame queue. If Postgres stalls long enough to fill that queue — a
> checkpoint on a busy disk will do it — `submit()` starts abandoning frames after 50 ms and they
> become live-only. `framesPendingWrite` climbing on `GET /health` is the warning; raise
> `DETECTION_STORE_QUEUE_DEPTH` to ride out longer stalls, at the cost of heap.

#### Running it from the stack

`docker compose -f docker-compose.all-services.yml up -d jarvis-detection` builds
[`detection/Dockerfile`](detection/Dockerfile) and brings the hub up on port 9001, behind nginx at
`/detection/`:

```bash
curl http://localhost:8080/detection/health
curl -N http://localhost:8080/detection/frames/stream        # -N: don't buffer the stream
curl -X POST http://localhost:8080/detection/frames \
     -H 'Content-Type: application/json' -d '{"source":"cam1","frameBase64":"..."}'
```

Three things about that route are deliberate, and are the ones to check first if a viewer connects
and then sees nothing:

- **`proxy_buffering off` on `/detection/frames/stream`.** Left on, nginx holds SSE events in its
  own buffer and releases them in batches — the stream looks frozen and then arrives in a burst.
  This is the usual cause of "the hub works with curl inside the network but not through the proxy".
- **`proxy_read_timeout 24h`.** The 60-second default drops a connection that has merely gone
  quiet, which for a camera watching a still scene is the normal case.
- **`client_max_body_size 16m`.** A frame is base64 in a JSON body, so it arrives ~33% larger than
  the JPEG; the 1 MB default would reject a high-resolution one.

The service needs the `jarvis` role and database from `db-init/all-services.sql`, which Postgres
runs **only when its data volume is first initialised**. On a volume created before that entry
existed, create them once by hand:

```bash
docker compose -f docker-compose.all-services.yml exec db psql -U postgres \
  -c "CREATE ROLE jarvis LOGIN PASSWORD 'jarvis'" \
  -c "CREATE DATABASE jarvis OWNER jarvis"
```

#### Upgrading a deployment that ran an earlier build

Two breaking changes landed when frame relaying stopped inspecting frames.

**The schema.** `frame_events.changed` and `changed_fraction` are gone. `ddl-auto=update` never
drops a column and both were `NOT NULL` with no default, so an existing table rejects every insert
until you drop them:

```sql
ALTER TABLE frame_events DROP COLUMN IF EXISTS changed, DROP COLUMN IF EXISTS changed_fraction;
```

Skipping this fails *quietly* in the default `DETECTION_STORE_MODE=async`: the live stream keeps
working perfectly because broadcast happens before the write, and only replay is dead — every
reconnect and `?from=` returns nothing, forever. The symptoms are `framesUnstoredTotal` climbing on
`GET /health` and `Could not store a batch of frames` in the log.

**The wire format.** The SSE `frame` event no longer carries `changed` or `changedFraction`, and
the `POST /frames` ack no longer carries `changed`, `firstFrame`, `changedCells` or `changedFraction`.
A consumer branching on `frame.changed` reads undefined rather than failing, so it goes quiet
instead of erroring — grep your viewers for those field names before upgrading.

**Retention wins over catch-up.** The sweep deletes frames whether or not every client has read
them, so a client away longer than the retained history resumes at the oldest surviving frame and
the ones in between are gone. Bounded storage beats guaranteed catch-up: the alternative is a table
that grows at the ingest rate until the disk fills, on an instance shared with every other app in
the repo.

Two bounds, whichever bites first, swept every `DETECTION_RETENTION_SWEEP_SECONDS` (30):

| | Bounds | Set to 0 to |
|---|---|---|
| `DETECTION_FRAME_MAX_BYTES` (2 GiB) | frame bytes retained | disable, and let age alone bound the table |
| `DETECTION_FRAME_RETENTION_SECONDS` (300) | how stale a retained frame may be | disable, and let the budget alone bound it |

The byte budget is the one that protects the disk on a busy stream, and it makes the **replay
window variable**: a busy hour buys less history than a quiet one, and the footprint is what stays
constant. Prefer that to a fixed duration — it is the disk you are actually defending. The budget
counts frame bytes rather than on-disk table size, so leave headroom for indexes and
not-yet-vacuumed rows.

`GET /health` reports `retainedFrameBytes`, `framesDroppedByAgeTotal`, `framesDroppedByBudgetTotal`
and `lastRetentionSweepError`. The last is null when healthy, and is worth alerting on: a sweep that
stops working is exactly how the table grows without bound. Note `retainedFrameBytes` is measured
*by* the sweep, so it is only as fresh as `DETECTION_RETENTION_SWEEP_SECONDS` — right after startup
it reads 0 until the first sweep completes.

The sweep itself lives in [`jarvis-retention`](retention/README.md), which explains why it owns its
own JDBC transactions rather than being a `@Transactional` Spring bean.

**Cursors are written on a throttle** (at most every couple of seconds, and forced on disconnect),
because a row write per frame would cost more than the streaming does. Delivery is therefore *at
least once* across a crash: key on the event id.

Three current limits worth knowing:

- **Spring engine only.** The hub hands frames to open SSE connections and its retention runs on a
  scheduled bean, neither of which exists in the Undertow runtime. Under `SERVER_ENGINE=undertow`,
  `/frames` answers `501` saying so and `/frames/stream` is not served. `/detect` and `/motion`
  work on both engines.
- **Both frame routes are unauthenticated**, like the rest of detection — anyone who can reach the
  port can push frames and watch the stream. Put it behind a network boundary, or add
  `@RequireAuthentication` to the frame routes and set `AUTH_BASE_URL`.
- **A hung-up client stays registered until the next write to it fails**, so `connectedClients` and
  the `recipients` count lag a disconnect by one frame.

`DETECTION_DATASOURCE_URL`, `DETECTION_DATASOURCE_USERNAME` and `DETECTION_DATASOURCE_PASSWORD`
are **required** — a breaking change for a detection deployment that previously ran without a
database. See [`detection/.env.example`](detection/.env.example) for the full set.


### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the object storage proxy (`apps/object-storage-proxy`). It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash apps/jarvis/clients/syncer/syncer.sh
```

Configure proxy credentials in `apps/jarvis/clients/syncer/config.sh`.
