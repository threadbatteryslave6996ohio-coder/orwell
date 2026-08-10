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
`/detect` or `/motion`, not both.

### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the bucket proxy. It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash apps/jarvis/clients/syncer/syncer.sh
```

Configure proxy credentials in `apps/jarvis/clients/syncer/config.sh`.
