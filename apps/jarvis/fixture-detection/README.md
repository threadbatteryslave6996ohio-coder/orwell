# jarvis-fixture-detection

Is the lamp on. Is the burner lit. Is the furnace running.

A watcher on the hub's frame stream that measures a rectangle of each frame and reports when it
crosses a threshold. One log line when a fixture goes on, one when it goes off, silence in between.

```bash
mvn -pl :jarvis-fixture-detection -am package
cp fixtures.example.json fixtures.json      # then edit it — see Calibrating below
FIXTURE_DETECTION_HUB_URL=http://127.0.0.1:9001 \
FIXTURE_DETECTION_DEFINITIONS_FILE=$PWD/fixtures.json \
java -jar target/jarvis-fixture-detection-0.1.0-SNAPSHOT-exec.jar
# http://127.0.0.1:9004/fixtures
```

## Why this and not a vision model

For a camera that does not move, "is this fixture emitting light" is a **measurement**, not a
judgement. The pixels where the lamp is get brighter; a threshold answers it. That is deterministic,
unit-testable against synthetic frames, costs microseconds per frame, needs no GPU, and cannot
hallucinate.

A vision model asked the same question is four orders of magnitude slower, needs calibration you
cannot inspect, and will occasionally say the lamp is on because there is a window in shot. Keep the
model for the open question — *is anything unusual happening here* — and measure the closed one.

What this app does **not** know is what a lamp *is*. It knows that a rectangle you drew got brighter
than a number you chose. Point it at the wrong rectangle and it will report the state of a wall with
total confidence. That is what the preview route is for.

## Routes

| Route | |
|---|---|
| `GET /health` | frame-stream state, counters, and every fixture's current state |
| `GET /fixtures` | per fixture: live value, thresholds, observed range, last minute of readings |
| `GET /fixtures/{name}/preview` | the latest frame with the regions drawn on it |

It takes no frames over HTTP and nothing calls it. Frames arrive over the hub's SSE stream through
`jarvis-frame-client`, with its own durable subscription — so adding it cost the cameras nothing.

## Configuring a fixture

JSON, from `FIXTURE_DETECTION_DEFINITIONS_FILE` (mount it; editable without a rebuild) or inline in
`FIXTURE_DETECTION_DEFINITIONS`. Start from [`fixtures.example.json`](fixtures.example.json).

```json
{
  "name": "living-room-lamp",
  "source": "cam1",
  "region":    {"x": 0.62, "y": 0.18, "width": 0.10, "height": 0.12},
  "reference": {"x": 0.20, "y": 0.18, "width": 0.10, "height": 0.12},
  "metric": "MEAN_LUMINANCE",
  "onAbove": 22, "offBelow": 10, "confirmFrames": 3
}
```

| Field | |
|---|---|
| `name` | **required**, unique — the key in `/fixtures`, the preview URL, the log line and the alert |
| `source` | the camera it is visible from. Omit to watch whichever source a frame came from |
| `region` | **required** — where the fixture is, in **fractions of the frame**, not pixels |
| `reference` | an optional second region whose value is subtracted. Read the next section |
| `metric` | `MEAN_LUMINANCE` (default), `BRIGHT_FRACTION`, `SATURATED_BRIGHT_FRACTION` |
| `brightCutoff` | luma 0–255 at which a pixel counts as bright, for the fraction metrics. Default 200 |
| `saturationCutoff` | saturation 0–1 at which a pixel counts as coloured. Default 0.30 |
| `onAbove` / `offBelow` | **required** — the two thresholds. The gap between them is the hysteresis |
| `confirmFrames` | consecutive agreeing frames before a change is reported. Default 3 |

Regions are fractions so they survive the camera being reconfigured from 1080p to 720p, or a producer
starting to downscale. They do not survive the camera being **moved** — nothing could.

**Anything unparseable is fatal at startup.** A detector watching nothing looks exactly like a
working one, because silence is what a correct lamp detector produces almost all of the time.

### Pick the metric by shape, not by fixture

| | Use | Because |
|---|---|---|
| Lamp lighting a wall | `MEAN_LUMINANCE` | the change fills the region |
| Bulb, indicator LED, hot element | `BRIGHT_FRACTION` | a small emitter barely moves a mean |
| Gas flame, glowing coil | `SATURATED_BRIGHT_FRACTION` | it is *coloured* light |

The middle row is the one people get wrong. A 20×20 flame inside a 200×200 region is 1% of the
pixels, so even saturating its own pixels it lifts the mean by two or three levels — inside the noise
of JPEG compression and auto-exposure. Counting bright pixels instead moves from 0% to 1%.

The third row is why the metric tests saturation rather than warmth: a gas flame is **blue**, an
electric coil is red, an LED is whatever the manufacturer chose. What they share is not a hue, it is
that they are coloured light, where a lamp, daylight and a ceiling fixture are all approximately
white. That is what lets it tell *the burner is lit* from *somebody switched the kitchen light on*,
which brightness over the hob cannot do at all. Lower `brightCutoff` to about 110 for a flame — it is
not as bright as a bulb.

### The reference region is the important knob

Absolute brightness cannot tell a lamp from the sun. A region reading 40 at night with the lamp on
reads 180 at noon with it off, so **any fixed threshold is wrong for half the day**. A reference patch
— a stretch of the same wall the lamp does not light — moves with daylight and not with the lamp, so
the difference between them stays put:

```
              lamp on, night   lamp off, noon
region             90               210
reference          20               140
difference         70                70   <- one threshold works for both
```

It also absorbs the camera fighting you. Auto-exposure reacts to a lamp switching on by pulling gain
*down*, which flattens the region's own rise; the reference falls at the same time, so the gap widens
instead of narrowing. Put the reference in the same exposure zone as the region and out of the
fixture's light.

If `observedMax - observedMin` on `/fixtures` is far wider than flipping the switch explains, the
region is picking up ambient light and wants a reference.

### Hysteresis and confirmation

Two thresholds, not one, and `confirmFrames` agreeing samples. Both exist because of specific
failures:

- **One threshold** means a fixture reading right at it reports a change on *every frame* — at 1 fps
  that is 3,600 state changes an hour from a lamp nobody touched. With two, crossing up needs
  `onAbove`, crossing down needs `offBelow`, and inside the gap the current state simply holds.
- **One frame** is enough for a passing shadow, an auto-exposure hunt or a badly compressed frame to
  land the wrong side of a threshold. `confirmFrames` must be **consecutive** — one contrary frame
  restarts the count rather than pausing it — so at 1 fps a change costs a couple of seconds of
  latency and buys immunity to every one-frame artefact.

## Calibrating

You cannot guess thresholds. Read them off the fixture:

```bash
# 1. Put the region roughly where the fixture is and give it any thresholds.
# 2. Check the rectangle is actually on the fixture. Green is the region, blue the reference:
open http://127.0.0.1:9004/fixtures/living-room-lamp/preview

# 3. Turn the fixture off, wait, turn it on, wait. Then read the swing:
curl -s http://127.0.0.1:9004/fixtures | jq '.fixtures[0] | {value, observedMin, observedMax,
  recent: [.recent[].value]}'
# { "value": 130, "observedMin": 0, "observedMax": 130,
#   "recent": [0, 0, 0, 130, 130, 130] }

# 4. Put onAbove and offBelow either side of the gap — here 40 and 15 — and restart.
```

`recent` is the last minute of readings at 1 fps, which is what makes step 3 one operation rather
than a polling loop: flip the switch, then look at the history.

Leave a wide margin. A lamp reading 130 against a threshold of 40 keeps working when the bulb dims
with age or somebody moves a chair; one set at 128 does not.

## What it reports

A transition, never a state. Nothing is emitted while a lamp stays on, which is what makes this cheap
to alert on:

```
[INFO] A fixture changed state. fixture=living-room-lamp source=cam1 state=on
       previousState=off value=130.0 onAbove=40.0 offBelow=15.0 frameId=262
```

Set `FIXTURE_DETECTION_ALERT_URL` (e.g. `http://alerting/alerts`) and each transition is also POSTed:

```json
{"event":"fixture_state_changed","fixture":"living-room-lamp","source":"cam1",
 "previousState":"off","state":"on","value":130.0,"at":"2026-08-17T21:13:32Z","frameId":262}
```

**No cooldown**, which is the deliberate difference from `jarvis-person-detection`. A person is an
event, so repeated detections of the same person need suppressing by a timer. A lamp has a *state*:
the interesting moment is when it changes, and hysteresis plus confirmation already make a change mean
something. A timer on top would only delay real transitions.

A failed alert is logged, not retried — the transition is already in the log, and re-delivering a
stale "the stove came on" into a recovering alerting service is worse than a warning saying it was
not delivered.

The first confirmed reading after a restart **is** reported, as `unknown -> on`. On a restart you want
to be told the stove is currently on, not only when it next changes.

Transitions are dated with the frame's own `capturedAt`, so a recording backfilled through
`jarvis-frame-viewer` reports when the stove was on rather than when the video was uploaded.

## In the compose stack

The app's own directory is mounted read-only at `/app/config`, so switching the detector on is two
steps and no rebuild:

```bash
cp apps/jarvis/fixture-detection/fixtures.{example.,}json    # then edit it
echo 'ORWELL_FIXTURE_DEFINITIONS_FILE=/app/config/fixtures.json' >> .env
./scripts/stack.sh up -d jarvis-fixture-detection
open http://127.0.0.1:9004/fixtures
```

`fixtures.json` is gitignored — it describes where things are in your home, which is yours rather than
the repo's. The `.example` is committed. It is not behind nginx and publishes to `127.0.0.1` only:
`/health` and `/fixtures` carry no imagery, but the preview route returns a frame of your house and it
is on the same port.

## Configuration

Everything is in [`.env.example`](.env.example). `FIXTURE_DETECTION_HUB_URL` is the only required key.
`FIXTURE_DETECTION_SUBSCRIPTION` matters more here than for other watchers: a missed stretch of frames
is a missed transition, and the reported state is then wrong until somebody next touches the switch.

## Limits worth knowing before you trust it

- **A moved camera invalidates every region**, silently. The fixture keeps reporting; it is now
  measuring a different part of the room. Nothing here can detect that.
- **It cannot see what it cannot see.** A lamp behind a chair, a burner under a pan, a furnace LED
  out of frame: no threshold fixes an occlusion.
- **Night-vision IR flips the assumptions.** A camera switching to infrared makes the whole frame
  monochrome, so `SATURATED_BRIGHT_FRACTION` reads ~0 and a flame becomes undetectable. If your camera
  does that, a burner needs `BRIGHT_FRACTION` and separate day/night thresholds — which this app does
  not have.
- **One fixture per rectangle.** Four hob burners are four fixtures, and if they overlap in frame
  their regions overlap and so do their readings.
- **The state is not persisted.** A restart begins at `unknown` and re-derives the state from the next
  frames, which is why the durable subscription matters.
