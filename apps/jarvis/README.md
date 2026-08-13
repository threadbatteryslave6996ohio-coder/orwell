# Jarvis

Surveillance services and macOS/Linux recorder clients, the latter under `clients/`.
Alert delivery is no longer part of jarvis; it is a standalone app at `apps/alerting`.
The upload proxy the recorders push to is likewise standalone, at
`apps/object-storage-proxy` — it also bundles the stream analysis worker and its ingest
scripts.

Authentication is supplied by `apps/auth`. Build everything from the repository root:

```bash
mvn -pl apps/jarvis -am package
```

The recorder clients under `clients/` remain standalone shell applications.
The log analyzer is now a standalone app; see `apps/log-analyzer`.

## The three processes

Jarvis used to be one service, `jarvis-detection`, serving `/detect`, `/motion` and `/frames` from
a single jar. They were always unrelated jobs sharing a port, and they did not cost the same to
run: sizing one container meant sizing it for the most expensive of them, an out-of-memory in the
hub took person detection down with it, and the retention sweep could only run while the service
that served video was up.

**Only the hub takes frames over HTTP.** Everything else that wants frames *watches* the hub
through [`jarvis-frame-client`](frame-client), so a producer pushes each frame exactly once and
fan-out is the hub's job. Adding a watcher costs the producers nothing and requires no change to
them at all.

```
recorders ──POST /frames──> hub ──SSE──┬──> person-detection ──> apps/alerting
                                       ├──> any other watcher
                                       │
                                  frame_events ──replay──> a watcher that reconnects
                                       │
                        jarvis-retention-worker (no port) trims it to budget
```

| Directory | artifactId | Port | Serves | Needs Postgres |
|---|---|---|---|---|
| `hub` | `jarvis-hub` | 9001 | `POST /frames`, `GET /frames/stream`, `GET /frames` | **yes** |
| `person-detection` | `jarvis-person-detection` | 9002 | `GET /health` only — it *watches* | no |
| `retention-worker` | `jarvis-retention-worker` | **none** | — | **yes** |

And three libraries, which are not deployable:

| Directory | artifactId | What it is |
|---|---|---|
| `frame-core` | `jarvis-frame-core` | The frame envelope (`FramePayload`) the hub accepts on ingest |
| `frame-client` | `jarvis-frame-client` | The stream **consumer**: SSE framing, resume, backoff, decode |
| `retention` | `jarvis-retention` | The generic table sweeper the worker applies to `frame_events` |

The hub is Spring-only (it serves SSE). Person detection and the retention worker are plain Java
programs — no Spring, no `SERVER_ENGINE` — because neither serves request routes.

### Watching the stream: `jarvis-frame-client`

Any service, CLI or test that wants frames uses this rather than writing its own SSE reader:

```java
FrameStreamOptions options = FrameStreamOptions.of("http://jarvis-hub:9001", "my-watcher");

try (FrameStreamClient frames = new FrameStreamClient(options, frame -> {
        // frame.bytes() is already base64-decoded and hash-checked
        process(frame.source(), frame.bytes());
    }, logger)) {
    frames.start();          // returns immediately; reads on its own thread
    ...
}
```

It exists because four things are individually easy to get wrong, and getting any of them wrong
looks the same from outside — "the stream works, but we occasionally lose frames":

- **SSE framing.** `data:` may repeat and is joined with newlines. A reader that JSON-parses each
  `data:` line works until a payload wraps, then fails on exactly the large frames you care about.
- **Resume.** It reconnects with `Last-Event-ID` set to the last frame it *delivered to the
  listener*, not the last it received — so a frame that arrived but was never handled comes back
  rather than being skipped. Delivery is therefore **at least once**: key on `frame.frameId()`.
- **Backoff.** Reconnects double from 1s to 30s and reset on success. Without the ceiling, a hub
  restart turns every watcher into a retry storm against a server trying to come up.
- **Verification.** Base64 is undone and the `sha256` checked before the listener sees anything.

Two behaviours worth knowing before you write a listener. It **never gives up** — a dead hub is
treated as temporary, because the alternative is a watcher that exits at 3am when the hub restarts
and which nothing restarts because it exited cleanly. And your listener runs **on the reader
thread**, one frame at a time in id order, so a listener slower than the frame rate makes the hub
start dropping that connection's oldest frames. A durable `subscription` name re-fetches them on
the next reconnect; without one they are gone. `healthDetails()` gives you `connected`,
`lastFrameId`, `framesReceivedTotal`, `reconnectsTotal` and `lastStreamError` to fold into your own
`/health`.

### Migrating from `jarvis-detection`

Every environment variable was renamed to the service that now reads it. The old names are not
read by anything: a leftover `DETECTION_*` is silently ignored, so a deployment that keeps its old
env file starts with defaults rather than failing.

| Was | Is | Now belongs to |
|---|---|---|
| `DETECTION_ALERT_URL` | `PERSON_DETECTION_ALERT_URL` | person-detection |
| `DETECTION_ALERT_COOLDOWN_SECONDS` | `PERSON_DETECTION_ALERT_COOLDOWN_SECONDS` | person-detection |
| `DETECTION_MIN_CONFIDENCE` | `PERSON_DETECTION_MIN_CONFIDENCE` | person-detection |
| — | `PERSON_DETECTION_HUB_URL` (**required**) | person-detection (new: it watches the hub) |
| — | `PERSON_DETECTION_SUBSCRIPTION` | person-detection (new: its durable cursor) |
| `DETECTION_DATASOURCE_*` | `HUB_DATASOURCE_*` | hub |
| `DETECTION_JPA_HIBERNATE_DDL_AUTO` | `HUB_JPA_HIBERNATE_DDL_AUTO` | hub |
| `DETECTION_STREAM_QUEUE_DEPTH` | `HUB_STREAM_QUEUE_DEPTH` | hub |
| `DETECTION_STORE_MODE` | `HUB_STORE_MODE` | hub |
| `DETECTION_STORE_QUEUE_DEPTH` | `HUB_STORE_QUEUE_DEPTH` | hub |
| `DETECTION_FRAME_MAX_BYTES` | `RETENTION_FRAME_MAX_BYTES` | retention-worker |
| `DETECTION_FRAME_RETENTION_SECONDS` | `RETENTION_FRAME_MAX_AGE_SECONDS` | retention-worker |
| `DETECTION_RETENTION_SWEEP_SECONDS` | `RETENTION_SWEEP_SECONDS` | retention-worker |
| — | `RETENTION_DATASOURCE_*` | retention-worker (new: it connects itself) |
| `DETECTION_MOTION_*` | — | **removed with `/motion`** |

**Two endpoints are gone.**

- **`POST /detect` no longer exists.** Person detection reads the hub's stream instead. Point your
  producers at `POST /hub/frames` — one push, and every watcher sees it. The consequence to plan
  for: detection is now **asynchronous**, so nothing gets a verdict back in a response. A detection
  becomes an alert to `apps/alerting`, or a counter on `/health`.
- **`POST /motion` and the whole motion service are deleted.** Frame-to-frame change detection is
  no longer part of this repo — `FrameChangeDetector`, `MotionService` and their 13 tests are gone,
  not moved. If you were calling it to decide whether a frame was worth pushing, that decision now
  has to live in the producer. `git log` has the implementation if you want it back.

`STREAM_ANALYSIS_ENDPOINT` posts to one place, so it should now be `.../hub/frames`.

`GET /health` split up. The hub keeps the frame counters. Person detection reports both halves of
its job — `connected`, `lastFrameId`, `reconnectsTotal` and `lastStreamError` from the stream
client, plus `framesExaminedTotal`, `detectionsTotal`, `alertsSentTotal` and
`undecodableFramesTotal` from the detector. A consumer that is up but not connected is the failure
that matters most there, and it is the one thing a process-liveness check cannot see. The retention
counters left the API entirely — see the retention worker section below.

### Frame hub — push in, stream out, replay what was missed

Detection doubles as the central hub: producers push frames to it, it stores them, and it pushes
them straight on to every client connected at that moment. A client that was away reconnects and
is replayed what it missed before rejoining the live stream. **Requires Postgres and
`SERVER_ENGINE=spring`.**

The hub does three things and no more — **receive, store, redistribute**. It does not decode a
frame, compare it against the last one, or decide that a repeat is not worth sending: every frame
pushed to it is stored and relayed. A producer that wants to relay only interesting frames makes
that decision itself before pushing — there is no change-detection endpoint to ask any more. The
hub is a pipe, so the payload does not even have to be an image it could decode.

```
recorders ──POST /frames──> hub ──┬──SSE──> clients connected now
                                  │
                             frame_events ──┬──replay──> a client that reconnects
                                  │         └──GET /frames──> a window that has already passed
                                  │
              jarvis-retention-worker (separate process) trims it to budget
```

`POST /frames` takes the frame envelope — `frameBase64`, optional `frameSha256`,
`source`, `frameIndex`, `timestamp` — and answers

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
reconnecting client skips over; `framesUnstoredTotal` counts them. Set `HUB_STORE_MODE=sync`
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
bounded queue (`HUB_STREAM_QUEUE_DEPTH`) drained by its own thread. When a client falls
behind, the hub drops that client's **oldest** frame — on a live feed the freshest frame is the
useful one, and unbounded buffering would turn a slow viewer into an out-of-memory error. A named
subscription re-fetches dropped frames from the store on its next reconnect; an unnamed one loses
them. Drops are counted and reported on `GET /health`, alongside `connectedClients`,
`framesReceivedTotal`, `framesDistributedTotal`, `framesReplayedTotal`, `framesPendingWrite` and
`framesUnstoredTotal`.

#### Reading back a window: `GET /frames`

The stream answers "what happens next"; this answers "what happened between 10:00 and 10:05". They
read the same `frame_events` table and return the same frame objects, but they are asked different
questions and so take different coordinates. The stream resumes by **frame id**, which works only
for a caller that remembers where it got to — nobody holds the id of a frame they have never seen.
So the window here is in **capture time**, which is what an operator actually has.

| Parameter | Effect |
|---|---|
| `?from=<instant>` | Inclusive lower bound on `capturedAt`, ISO-8601 (`2026-08-12T10:00:00Z`). Omitted: the oldest frame still kept |
| `?to=<instant>` | **Exclusive** upper bound. Omitted: open-ended |
| `?source=cam1` | Only that camera; omitted spans every source |
| `?limit=<n>` | Frames in this page, 1–500. Default 100 |
| `?after=<frameId>` | Exclusive cursor — the previous page's `nextAfter` |

```bash
curl 'http://localhost:9001/frames?from=2026-08-12T10:00:00Z&to=2026-08-12T10:05:00Z&source=cam1'
```

```json
{"success":true,"source":"cam1","from":"2026-08-12T10:00:00Z","to":"2026-08-12T10:05:00Z",
 "after":null,"limit":100,"returned":100,"hasMore":true,"nextAfter":1300,
 "frames":[{"frameId":1201,"source":"cam1","frameIndex":42,"capturedAt":"2026-08-12T10:00:00Z",
            "sha256":"9f2c…","frameBase64":"…"}]}
```

A frame here is byte-identical to the same frame over SSE — same fields, same base64 — so anything
that can decode the stream can decode a page.

**Paging is the caller's job.** Follow `nextAfter` while `hasMore` is true, and stop when it is
false. The upper bound is exclusive so adjacent windows neither overlap nor skip the frame on the
boundary, and the cursor is exclusive for the same reason between pages.

**Filtered by time, paged by id**, which are not quite the same order: an id is allocated before
`capturedAt` is stamped, so concurrent ingest can invert the two by a frame. It costs nothing here —
the time bounds decide *which* frames are in the window and the id cursor decides the order they
come back in, so nothing in the window is skipped or repeated. A timestamp cursor could not promise
that, because frames sharing a `capturedAt` would fall on both sides of a page boundary.

**A limit above 500 is a 400, not a clamp.** A caller that asks for 5000 and silently receives 500
reads the short page as the end of the window and stops early — losing frames with no error
anywhere. The ceiling itself is a memory limit rather than a courtesy: a page is materialised whole,
rows and then base64, so 500 frames of 40 KB is ~20 MB of rows plus ~27 MB of base64 per concurrent
request against a container running with `mem_limit: 512m`. Raise `MAX_LIMIT` and that `mem_limit`
together or not at all — the hub dying takes the recording down with it.

Retention is not consulted: this reads what is in the table now, and
`jarvis-retention-worker` may delete rows out from under a caller that is paging. Because pages are
read forward by id, that shows up as a short page, never as a repeat.

> **Sizing.** `frame_events` holds the frame bytes, because replay means a frame must still exist to
> be re-sent, and the hub stores every frame it is pushed — at 40 KB and 5 fps that is ~200 KB/s
> *per source*. `RETENTION_FRAME_MAX_BYTES` (2 GiB) is what stops that from becoming a disk
> problem, so the question is not how much you can store but how much history that budget buys:
> ~2.8 hours for one such source, ~20 minutes across eight. Raise the budget, or push less —
> sending only frames that differ is a producer's decision, and making it locally is cheaper than
> shipping a frame to find out.
>
> It is a **write** rate too, not just disk: eight cameras at 5 fps is 40 inserts/s through one
> writer thread and a 512-frame queue. If Postgres stalls long enough to fill that queue — a
> checkpoint on a busy disk will do it — `submit()` starts abandoning frames after 50 ms and they
> become live-only. `framesPendingWrite` climbing on `GET /health` is the warning; raise
> `HUB_STORE_QUEUE_DEPTH` to ride out longer stalls, at the cost of heap.

#### Running it from the stack

```bash
docker compose -f docker-compose.all-services.yml up -d \
    jarvis-hub jarvis-retention-worker jarvis-person-detection
```

Three containers where there was one. Only the hub takes traffic: `/hub/` accepts frames and serves
the stream, `/person-detection/health` is read-only observability, and the retention worker gets no
route at all because it has no port.

```bash
curl http://localhost:8080/hub/health
# Is detection actually watching? `connected` and `lastFrameId` are the fields that matter.
curl http://localhost:8080/person-detection/health
docker compose -f docker-compose.all-services.yml logs -f jarvis-retention-worker   # no /health

curl -N http://localhost:8080/hub/frames/stream              # -N: don't buffer the stream

# What was on cam1 five minutes ago. Page with ?after=<nextAfter> while hasMore is true.
curl 'http://localhost:8080/hub/frames?source=cam1&from=2026-08-12T10:00:00Z&to=2026-08-12T10:05:00Z'

# One push. The hub stores it, relays it to every watcher, and person detection examines it.
curl -X POST http://localhost:8080/hub/frames \
     -H 'Content-Type: application/json' -d '{"source":"cam1","frameBase64":"..."}'
```

Three things about the hub's route are deliberate, and are the ones to check first if a viewer
connects and then sees nothing:

- **`proxy_buffering off` on `/hub/frames/stream`.** Left on, nginx holds SSE events in its
  own buffer and releases them in batches — the stream looks frozen and then arrives in a burst.
  This is the usual cause of "the hub works with curl inside the network but not through the proxy".
- **`proxy_read_timeout 24h`.** The 60-second default drops a connection that has merely gone
  quiet, which for a camera watching a still scene is the normal case.
- **`client_max_body_size 16m`.** A frame is base64 in a JSON body, so it arrives ~33% larger than
  the JPEG; the 1 MB default would reject a high-resolution one. Only the hub's route needs it now
  — it is the only one that takes a frame.

The hub and the retention worker need the `jarvis` role and database from `db-init/all-services.sql`, which Postgres
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

Skipping this fails *quietly* in the default `HUB_STORE_MODE=async`: the live stream keeps
working perfectly because broadcast happens before the write, and only replay is dead — every
reconnect and `?from=` returns nothing, forever. The symptoms are `framesUnstoredTotal` climbing on
`GET /health` and `Could not store a batch of frames` in the log.

**The wire format.** The SSE `frame` event no longer carries `changed` or `changedFraction`, and
the `POST /frames` ack no longer carries `changed`, `firstFrame`, `changedCells` or `changedFraction`.
A consumer branching on `frame.changed` reads undefined rather than failing, so it goes quiet
instead of erroring — grep your viewers for those field names before upgrading.

### Retention — `jarvis-retention-worker`

**The sweep is its own process, and holds no port.** It connects to the same `jarvis` database the
hub writes to, trims `frame_events` on a timer, and serves nothing. Being separate is the point:
the table must stay bounded while the hub is restarting, being scaled, or down, and a delete
sweeping half a table should not be competing for heap with the frames being relayed.

**Retention wins over catch-up.** The sweep deletes frames whether or not every client has read
them, so a client away longer than the retained history resumes at the oldest surviving frame and
the ones in between are gone. Bounded storage beats guaranteed catch-up: the alternative is a table
that grows at the ingest rate until the disk fills, on an instance shared with every other app in
the repo.

Two bounds, whichever bites first, swept every `RETENTION_SWEEP_SECONDS` (30):

| | Bounds | Set to 0 to |
|---|---|---|
| `RETENTION_FRAME_MAX_BYTES` (2 GiB) | frame bytes retained | disable, and let age alone bound the table |
| `RETENTION_FRAME_MAX_AGE_SECONDS` (300) | how stale a retained frame may be | disable, and let the budget alone bound it |

The byte budget is the one that protects the disk on a busy stream, and it makes the **replay
window variable**: a busy hour buys less history than a quiet one, and the footprint is what stays
constant. Prefer that to a fixed duration — it is the disk you are actually defending. The budget
counts frame bytes rather than on-disk table size, so leave headroom for indexes and
not-yet-vacuumed rows.

> **Watching it is now a log question, not an endpoint.** `retainedFrameBytes`,
> `framesDroppedByAgeTotal`, `framesDroppedByBudgetTotal` and `lastRetentionSweepError` used to be
> fields on the detection service's `GET /health`. A process with no port cannot serve them, so
> they go to the logger instead: set `LOKI_URL` and alert on `Frame retention sweep failed`. A
> sweep that stops working is exactly how the table grows without bound, so this is the one thing
> here worth paging on. The container healthcheck only proves the JVM is alive — it cannot tell
> you the sweep is working.

The sweep itself lives in [`jarvis-retention`](retention/README.md), a generic library that trims
any append-only table; the worker is just the schedule plus the `frame_events` policy. That README
explains why the sweeper owns its own JDBC transactions rather than being a `@Transactional`
Spring bean.

### Hub limits worth knowing

**Cursors are written on a throttle** (at most every couple of seconds, and forced on disconnect),
because a row write per frame would cost more than the streaming does. Delivery is therefore *at
least once* across a crash: key on the event id.

- **Spring engine only.** The hub hands frames to open SSE connections, which the Undertow runtime
  does not set up. It has no `SERVER_ENGINE` to choose — the old `501` from `/frames` on the wrong
  engine is gone with the service that could be started that way. Its siblings are plain Java
  programs with no engine choice at all.
- **The frame routes are unauthenticated** — anyone who can reach the port can push frames and
  watch the stream. Put it behind a network boundary, or add `@RequireAuthentication` to the routes
  and set `AUTH_BASE_URL`. This matters more now that watching is the supported way to consume
  frames: an unauthenticated `?subscription=` can also advance someone else's cursor.
- **A hung-up client stays registered until the next write to it fails**, so `connectedClients` and
  the `recipients` count lag a disconnect by one frame.
- **A durable subscription can lose exactly that frame.** The hub advances a subscription's stored
  cursor when it *writes* a frame to the connection, and a TCP write into a socket whose peer has
  died still succeeds until the RST arrives. So the frame in flight when a watcher is killed is
  recorded as delivered, and the watcher does not get it when it comes back. Verified end to end:
  push 65, kill the watcher, push 66 and 67, restart — it resumes after 66 and examines only 67.
  Delivery is therefore **at least once within a connection, but at most once for the single frame
  in flight across an unclean disconnect.** This is inherent to a server-side cursor over TCP, not
  a bug with a small fix. If a watcher cannot afford to miss that frame, have it record its own
  last-processed id and reconnect with `?from=` instead of relying on `?subscription=`.
- **`HogPersonDetector` itself is still untested.** `PersonDetectionServiceTest` covers the
  listener contract — undecodable frames counted not thrown, cooldown gating, alert failure not
  fatal — but the brightness heuristic underneath has no test of its own.

`HUB_DATASOURCE_URL`, `HUB_DATASOURCE_USERNAME` and `HUB_DATASOURCE_PASSWORD` are **required** for
the hub, and `PERSON_DETECTION_HUB_URL` is required for the watcher. Person detection needs no
database at all. See each service's `.env.example`: [hub](hub/.env.example),
[person-detection](person-detection/.env.example),
[retention-worker](retention-worker/.env.example).


### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the object storage proxy (`apps/object-storage-proxy`). It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash apps/jarvis/clients/syncer/syncer.sh
```

Configure proxy credentials in `apps/jarvis/clients/syncer/config.sh`.
