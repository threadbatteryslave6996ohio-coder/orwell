# Jarvis

Surveillance services (bucket proxy, detection) and macOS/Linux recorder
clients. Bucket services are under `bucket/`, recorder clients under `clients/`.
Alert delivery is no longer part of jarvis; it is a standalone app at `apps/alerting`.
The bucket proxy also bundles the stream analysis worker and its ingest scripts
(see `bucket/proxy/`).

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

```
recorders ──POST /frames──> hub ──┬──SSE──> clients connected now
                                  │
                             frame_events ──replay──> a client that reconnects
                                  │
                          FrameRetentionJob drops aged rows
```

`POST /frames` takes the same body as `/detect` and `/motion`, and returns the change verdict plus
`{"stored":true,"frameId":91,"recipients":2}` — so a camera can tell nobody is watching without
polling anything. A frame with zero recipients is still stored, so a client connecting later can
still replay it. Ingest returns as soon as the frame is queued for each client, never after they
have received it, so a producer's frame rate is never coupled to the slowest viewer.

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
       "sha256":"9f2c…","changed":true,"changedFraction":0.25,"frameBase64":"…"}
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
`framesReceivedTotal`, `framesStoredTotal`, `framesDistributedTotal`, `framesReplayedTotal`,
`framesPendingWrite` and `framesUnstoredTotal`.

> **Sizing.** `frame_events` holds JPEG bytes, because replay means a frame must still exist to be
> re-sent. At 40 KB and 5 fps that is ~200 KB/s *per source*, so two settings bound it:
> `DETECTION_RELAY_MODE=changed` (the default) stores only frames that differ from the previous one
> for their source plus each source's first frame, and `DETECTION_FRAME_RETENTION_SECONDS`
> (default 300) caps how long any frame survives. `DETECTION_RELAY_MODE=all` disables the first
> lever and the table grows at the full ingest rate.

**Retention wins over catch-up.** The sweep deletes aged frames whether or not every client has
read them, so a client away longer than the retention window resumes at the oldest surviving frame
and the ones in between are gone. Widen the window to trade disk for tolerance.

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

The syncer drains completed recording segments to the bucket proxy. It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash apps/jarvis/clients/syncer/syncer.sh
```

Configure proxy credentials in `apps/jarvis/clients/syncer/config.sh`.
