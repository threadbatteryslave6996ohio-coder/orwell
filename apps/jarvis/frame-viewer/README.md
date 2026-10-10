# jarvis-frame-viewer

A page over the frames the hub has already stored. Pick a device, see its newest frame, press **Back**
to step one frame older, and keep going until the retained window runs out. It also takes whole videos
and backfills them as frames dated across a window you name.

```bash
mvn -pl :jarvis-frame-viewer -am package
FRAME_VIEWER_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/jarvis \
FRAME_VIEWER_DATASOURCE_USERNAME=jarvis FRAME_VIEWER_DATASOURCE_PASSWORD=jarvis \
java -jar target/jarvis-frame-viewer-0.1.0-SNAPSHOT-exec.jar
# http://127.0.0.1:9003
```

Runnable artifact is the shaded `jarvis-frame-viewer-<version>-exec.jar` — a plain program on the
Undertow runtime, not a Spring app. Copy `.env.example` to `.env` to change anything.

> **Set `FRAME_VIEWER_AUTH_BASE_URL` or there is no authentication.** Blank — the default, and what
> the command above runs with — leaves every route open, which the page says in a banner and the log
> says at startup. The compose service sets it, so the stack requires a sign-in. Either way it binds
> `127.0.0.1` and its compose service publishes to `127.0.0.1`: the login is the guard, the bind
> address is the backstop.

## Where it sits

```
                        ┌── reads frame_events ──> THIS APP ──> a browser
recorders ──POST /frames──> hub                        │
                        └── frame_events               │
                                                       └── POST /frames ── an uploaded video,
                                                                            cut into frames
```

**It reads the table and does not watch the stream.** The repo's rule is that anything wanting frames
watches the hub through `jarvis-frame-client` rather than taking an ingest route of its own — but that
rule is about frames *arriving*, and this app is not waiting for frames. It answers "what did camera 1
look like ninety seconds ago", which is a question about history, and history is a table. Subscribing
would mean receiving every frame of every camera in order to display one of them on demand.

**Nor does it call the hub to read.** The hub owns the live path, in a container sized for frames in
flight; serving a browser scrubbing through history from there would put that traffic on the same heap,
and the hub going down stops the recording. `jarvis-retention-worker` already established that a
second process on this table is how jarvis does this — this one is the read-only case of it, with
`setReadOnly(true)` on the connection as well as nothing but `SELECT` in the SQL.

**Uploads are the exception, and they go through the hub.** See [Backfill](#backfill-a-video) below.

### Why the hub cannot just serve this

The hub has no route for "the latest frame from this camera", none for "the one before this one", and
none for the device list at all — a source exists only as a value in a column. Its `GET /frames` pages
*forward* from a time window, which is the wrong shape for a viewer that opens on the newest frame and
walks backwards: nobody holds the id of a frame they have never seen, and displaying one frame would
mean fetching a page of base64 and picking one image out of it.

## Who may watch

Access is the auth server's decision, not this app's. It holds no credentials and stores no users.

```bash
# A browser: sign in and get a cookie.
curl -X POST http://127.0.0.1:9003/login -d 'clientId=watcher&secret=…' -c cookies.txt
curl -b cookies.txt http://127.0.0.1:9003/api/sources

# A script: the same headers every service-to-service call in the repo uses. No session needed.
TOKEN=$(curl -s -X POST http://admin-auth-server/auth/def/login \
  -H 'Content-Type: application/json' -d '{"clientId":"watcher","secret":"…"}' | jq -r .token)
curl -H "X-Client-Id: watcher" -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:9003/api/sources
```

`POST /login` forwards the credentials to the auth server's `POST /{workspace}/login` and keeps the
token it returns in an HttpOnly, `SameSite=Strict` session cookie (`base64url(clientId).token`, the
packing `object-storage-proxy`'s panel uses). Every request after it re-checks that token with
`POST /{workspace}/tokens/check`. **Revoking someone's access to the footage is one call to the auth
server, not a redeploy of this service** — which is the whole reason it is done this way rather than
with a password in the environment.

**Everything but `/health` is guarded, `/api/frames/{id}/image` very much included.** Guarding the
page and leaving the images open would mean the footage was never protected: image URLs are
sequential integers. `/health` stays open because the container health check reads it, and it reports
`authRequired` so you can tell from outside whether anything is guarding the rest.

**Which auth deployment it points at *is* the access-control decision.** Whoever that server accepts
can watch every recording and upload more, so the compose stack points this at `admin-auth-server`
and not at the client-facing `auth-server` that mints identities for cameras and upload clients —
sharing one would make every camera a viewer of every camera. `secrets-manager` and
`object-storage-proxy` split theirs the same way. Provision a viewer with that server's admin
credentials:

```bash
curl -X POST http://admin-auth-server/auth/def/identities \
  -H "X-Client-Id: $ADMIN" -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"clientId":"watcher","secret":"…"}'
```

**An accepted token is cached for 30 seconds** (`FRAME_VIEWER_AUTH_CACHE_SECONDS`). That is not a
micro-optimisation: one step through the history is three requests — the frame, plus the two
neighbouring images the page prefetches — so holding an arrow key down is ten of those a second, and
uncached every one is a blocking POST to the auth server on a request thread. The cost is stated
plainly: **a revoked token keeps working here for up to 30 seconds.** Set it to 0 to check every
time. Only accepted tokens are cached, so a refusal never gets a free pass, and an auth server that
cannot be reached fails *closed* — no footage is served while access cannot be verified.

A session lasts eight hours. When it expires the API routes answer the repo's shared
`401 {"error":"authentication required"}` and the page reloads itself into the sign-in form.

## Routes

| Route | What it does |
|---|---|
| `GET /` | the page, or the sign-in form when nobody is signed in (and `GET /viewer.js`) |
| `POST /login`, `POST /logout` | the session |
| `GET /api/sources` | devices with frames still stored, newest activity first |
| `GET /api/frame` | one frame plus the ids either side — the navigation route |
| `GET /api/frames` | a page of frame metadata, newest first |
| `GET /api/frames/{id}/image` | the frame itself |
| `POST /api/uploads` | a video, cut into frames across a window |
| `GET /api/uploads`, `GET /api/uploads/{id}` | recent backfills, and one backfill's progress |
| `GET /health` | `storedFrames`, `sources`, `uploadsEnabled`, `authRequired` — 503 if the table is unreachable. The one route that is never guarded |

Errors are the same shape the hub gives: `{"success": false, "error": "..."}`.

### Navigating: `GET /api/frame`

Four ways to ask, which are the four things a viewer ever needs:

| Query | Answers |
|---|---|
| `?source=cam1` | the newest frame — where the page opens |
| `?source=cam1&before=<id>` | the frame before that one — **Back** |
| `?source=cam1&after=<id>` | the frame after it — **Forward** |
| `?source=cam1&at=oldest` | the far end of the retained window |
| `?id=<id>` | an exact frame, so a URL reloads onto the same one |

Omit `source` and steps cross every camera in id order. The response names the neighbours so the
buttons know whether there is anywhere to go, and so the page can prefetch the next image:

```json
{"success":true,
 "frame":{"frameId":135,"source":"cam1","frameIndex":5,"capturedAt":"2026-08-17T09:00:50Z",
          "sha256":"9f2c…","bytes":40960,"imageUrl":"api/frames/135/image"},
 "olderFrameId":134,"newerFrameId":null,"position":6,"total":6}
```

Running out of history answers `{"success":true,"frame":null}` — the end of the window is not an error.

**Every step is one indexed seek.** `idx_frame_events_source_id` is `(source, id)`, so
`WHERE source = ? AND id < ? ORDER BY id DESC LIMIT 1` costs the same on the thousandth step as the
first. `position` is counted rather than derived from the ids, because ids come from a sequence shared
by every source and retention deletes from the middle — the gap between two of one camera's ids is not
the number of frames between them.

### Images are a separate route, on purpose

The hub answers with `frameBase64` inline because its caller wants a window in one document. A viewer
wants the opposite. Base64 inflates a 40 KB frame to ~55 KB, it cannot be cached independently of the
metadata around it, and stepping back and forth over the same stretch of history would re-encode and
re-download the same images. So a navigation step is a few hundred bytes of JSON naming an `imageUrl`,
and the image is served `private, max-age=31536000, immutable` — true in the strict sense the header
means it, since a frame id comes from a sequence and its bytes never change. `If-None-Match` is
answered before the query runs, so a revalidating client costs no database read at all.

**Content types are sniffed from the payload's magic bytes, and this is the app's security boundary.**
Nothing records a frame's format: the hub validates the envelope and never looks inside. Anything that
can reach its ingest endpoint can therefore store arbitrary bytes — including HTML with a script in it
— and this app serves those bytes from its own origin, which is the shape of a stored-XSS delivery
route. Three things stop it: only a payload whose magic bytes are a recognised image gets an image
type, every image response carries `X-Content-Type-Options: nosniff`, and anything unrecognised is an
`application/octet-stream` attachment rather than something rendered inline. The page itself is served
under `default-src 'none'; script-src 'self'`, which is why its script is a separate resource rather
than a `<script>` block.

## Backfill a video

```bash
curl -X POST --data-binary @clip.mp4 \
  'http://127.0.0.1:9003/api/uploads?source=back-door&from=2026-08-17T09:00:00Z&to=2026-08-17T09:05:00Z&fps=2'
```

```json
{"success":true,"statusUrl":"api/uploads/1-49ddf1a1",
 "upload":{"uploadId":"1-49ddf1a1","status":"queued","source":"back-door",
           "from":"2026-08-17T09:00:00Z","to":"2026-08-17T09:05:00Z","fps":2.0,
           "videoBytes":50000,"framesTotal":0,"framesPushed":0,"framesFailed":0,
           "firstFrameId":null,"lastFrameId":null,"error":null}}
```

| Parameter | |
|---|---|
| `source` | **required** — the device these frames belong to. Anything; it is created by being used |
| `from` | **required** — ISO-8601 instant, inclusive lower bound on capture time |
| `to` | **required** — ISO-8601 instant, exclusive upper bound |
| `fps` | frames taken per second of *video*, 0–60. Defaults to `FRAME_VIEWER_UPLOAD_FPS` |

**The body is the video, raw.** Not multipart: the metadata is four scalars that belong in a query
string, and multipart would buy a parser and a boundary to carry them alongside bytes that are already
the whole body. It streams to a temp file and never enters the heap.

**There is no default window.** Guessing "ending now" would silently mis-date an archive, and a
mis-dated archive is worse than a refused upload — every query in the repo expressed in capture time
would then read it wrongly. `from` equal to or after `to` is a 400.

**Frames are spread evenly across the window and land inside `[from, to)`.** Six frames over a minute
land at `:00, :10, :20, :30, :40, :50`. The last one lands *inside* the upper bound rather than on it,
because every time window in the repo is half-open — so a `GET /frames?from=…&to=…` against the hub
with the same two bounds returns exactly the frames the upload created, no boundary frame missing and
none from the next window swept in. This deliberately ignores the video's own duration and frame rate:
you said this footage covers 09:00 to 09:05, and that is the answer whether the file is four minutes
or six.

**It answers 202 and a job to poll**, because a ten-minute video at 1 fps is 600 requests to the hub —
minutes of work. Doing it inside the upload request would mean a connection held open for the duration
and a browser or proxy timeout deciding when it stops.

### What it does with the video

`ffmpeg -vf fps=N` writes numbered JPEGs into a scratch directory, and the frames are read back one at
a time as they are pushed. **A subprocess, not a library**: the JDK cannot decode video, JavaCV ships
hundreds of megabytes of platform natives, and a pure-Java decoder reads a fraction of the containers
a phone or screen recorder produces. The repo already treats ffmpeg as the tool for this — the desktop
clients under `clients/` record with it and the syncer concats with it — so this is the same dependency
on the server side of the same pipeline. The runtime image installs it; without it, browsing still
works and `/health` reports `uploadsEnabled` with a warning in the log.

**Frames are pushed to the hub, never written to the table.** The hub is the only thing in the repo
that ingests frames. Writing rows here would allocate ids outside its sequence, skip the live fan-out
so `jarvis-person-detection` would never see a backfilled frame, and make this a second writer to a
table it otherwise opens read-only. Going through `POST /frames` with an explicit `capturedAt` makes a
backfilled frame indistinguishable from a live one.

**One job at a time, in order, and paced.** Each has a reason:

- **In order**, because the hub allocates a frame's id on arrival. Oldest-first keeps id order the same
  as capture order, which is what the Back button and the stream's `Last-Event-ID` resume both walk.
- **One at a time**, because two concurrent backfills would interleave their ids so neither occupies a
  contiguous range — and two ffmpeg processes on a 512 MB container is how this gets killed for memory.
- **Paced** (`FRAME_VIEWER_UPLOAD_PUSHES_PER_SECOND`, 25), because the hub stores frames behind its
  live stream through a 512-deep queue. Overrun it and a frame is delivered live and never stored,
  which for a backfill means it does not exist. If `framesFailed` is non-zero or the hub's
  `framesUnstoredTotal` climbs, set `HUB_STORE_MODE=sync` on the hub so storage is part of the
  response.

A frame the hub refuses increments `framesFailed` and the job carries on: one lost frame is a better
outcome than abandoning the other 599 to report it. The uploaded file and every frame cut from it are
deleted when the job ends, however it ends.

**Sizing before you upload an hour.** `frame_events` holds the bytes and `jarvis-retention-worker` caps
it at 2 GiB by default, so a backfill competes with the live cameras' history — at 40 KB a frame, an
hour at 1 fps is ~140 MB. `fps` is the knob; the default is deliberately low for this reason.

## Configuration

Everything is in [`.env.example`](.env.example). The ones worth knowing:

| Key | Default | |
|---|---|---|
| `SERVER_ADDRESS` / `SERVER_PORT` | `127.0.0.1` / `9003` | loopback by default, deliberately |
| `FRAME_VIEWER_DATASOURCE_*` | — | **required**; the `jarvis` database the hub writes |
| `FRAME_VIEWER_PAGE_SIZE` | `200` | the frame list's default *and* ceiling; over it is a 400 |
| `FRAME_VIEWER_HUB_URL` | *(blank)* | blank turns uploads off, and the route 503s saying so |
| `FRAME_VIEWER_UPLOAD_MAX_BYTES` | 512 MiB | streamed to disk, so this bounds disk not heap |
| `FRAME_VIEWER_UPLOAD_FPS` | `1.0` | frames per second of video when the caller does not say |
| `FRAME_VIEWER_UPLOAD_PUSHES_PER_SECOND` | `25` | see the pacing note above |
| `FRAME_VIEWER_FFMPEG` | `ffmpeg` | a bare name resolves on `PATH` |
| `FRAME_VIEWER_AUTH_BASE_URL` | *(blank)* | blank leaves every route open; see [Who may watch](#who-may-watch) |
| `FRAME_VIEWER_AUTH_WORKSPACE` | `def` | the workspace within that auth server |
| `FRAME_VIEWER_AUTH_CACHE_SECONDS` | `30` | how long an accepted token is trusted; 0 checks every time |
| `FRAME_VIEWER_SECURE_COOKIES` | `false` | `Secure` on the session cookie — on wherever this is reached over TLS |

It refuses to start if the datasource is unreachable: everything it does is read that table, and a
viewer that starts anyway and 503s every request looks healthy to whoever launched it.

## What it deliberately does not do

- **No live stream.** A "follow latest" checkbox polls `GET /api/frame` every two seconds — two index
  seeks. Subscribing to the hub's SSE would mean receiving every frame of every camera to notice one
  changed, and this app is a reader of stored frames.
- **No thumbnails.** The frame list is text: ids and times. Thumbnailing would mean decoding every
  frame in a page, and there is no thumbnail column to read instead.
- **No video out.** Frames are stills, served one at a time. Assembling them back into a video is
  ffmpeg's job on whatever machine wants the file.
- **No deleting.** Retention is `jarvis-retention-worker`'s business, and a viewer that can delete
  evidence is a different kind of app.
- **No users, roles or per-camera permissions.** It asks the auth server whether a credential is good
  and stops there: anyone who can sign in can see every camera and upload to any source name. Scoping
  a viewer to one camera would need a notion of authorisation that the auth server does not have —
  it answers "is this token this client's", not "may this client watch the back door".
