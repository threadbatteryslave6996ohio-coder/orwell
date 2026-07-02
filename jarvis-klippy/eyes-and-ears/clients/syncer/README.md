# Syncer Client

Drains completed recording segments to the bucket proxy while leaving the live
recording untouched.

For every recording type (`screen`, `microphone`, `system-audio`) the syncer:

1. Lists the completed segments in chronological order.
2. **Leaves the single most recent segment as is** — that is the file the
   recorder is still writing to.
3. Merges every older, completed segment of the same container into one file
   (ffmpeg stream-copy concat — no re-encoding).
4. Uploads the merged file to the proxy via the same `/login` + `/upload` flow
   used by the recorder uploader.

If only one older segment exists it is uploaded directly (nothing to merge). If
the only file present is the current segment, that type is skipped.

Screen `.mp4` (x11) and `.webm` (GNOME Wayland) segments are merged separately,
since only same-container segments can be concatenated by stream copy.

## Usage

```bash
# Edit proxy credentials and recording paths first:
$EDITOR config.sh

./syncer.sh
```

Run it on a timer (e.g. cron or a systemd timer) alongside the recorder. It
takes a single-instance lock, so overlapping runs are safe.

## Configuration

See `config.sh`. Key settings:

| Variable | Meaning |
| --- | --- |
| `PROXY_URL` / `PROXY_USERNAME` / `PROXY_PASSWORD` | Bucket proxy endpoint and credentials |
| `RECORDINGS_DIR` | Base directory holding `screen/`, `mic/`, `system-audio/` |
| `SCREEN_EXTENSIONS` | Screen containers to sync (`mp4 webm`) |
| `MIN_FILE_AGE` / `FILE_STABILITY_WAIT_SECONDS` | Guards that a segment is fully flushed before merging |
| `DELETE_AFTER_UPLOAD` | Remove local source segments after a successful upload (default `true`) |
| `VALIDATE_MEDIA_BEFORE_UPLOAD` | Validate each segment with `ffprobe` before merging |

Merged uploads are named for their time range, e.g.
`screen-2026-07-02-10__thru__screen-2026-07-02-13.mp4`.

## Requirements

- `curl`
- `ffmpeg` (and `ffprobe` when `VALIDATE_MEDIA_BEFORE_UPLOAD=true`)
