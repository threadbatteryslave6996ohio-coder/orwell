# health-monitor

Polls other services' `/health` endpoints and says when one stops answering. One alert when a service
goes down, one when it comes back, silence in between.

```bash
mvn -pl :health-monitor -am package
HEALTH_MONITOR_TARGETS="hub=http://127.0.0.1:9001/health,auth=http://127.0.0.1:8080/auth/health" \
java -jar target/health-monitor-0.1.0-SNAPSHOT-exec.jar
# http://127.0.0.1:9005/targets
```

Runnable artifact is the shaded `health-monitor-<version>-exec.jar` — a plain program on the Undertow
runtime, no Spring and no database.

## What this adds, given two things already exist

**Not the compose health checks.** Most services in `docker-compose.all-services.yml` already have a
`healthcheck` block, and they do something different: Docker restarts a container that fails one, and
**tells nobody**. This tells somebody, gives one place to see the whole stack at once, and works for
anything with a URL — including dependencies that are not in the compose file. It does not replace
them; restart-on-failure is still Docker's job, and `restart: unless-stopped` will happily crash-loop
a broken service forever without either of them noticing.

**Not `apps/liveness-analyzer`.** That is the other half of the same question and neither subsumes the
other:

| | Watches | Detects | Because |
|---|---|---|---|
| `liveness-analyzer` | Loki, for heartbeat lines from desktop **clients** | *absence* of heartbeats | nothing can reach a client behind a home router to poll it |
| `health-monitor` | HTTP, actively probing **servers** | a bad or missing answer | a server can be reached, and can be up while broken |

## Routes

| Route | |
|---|---|
| `GET /health` | this app's own health, plus a one-line summary per target |
| `GET /targets` | per target: state, last detail, HTTP status, latency, counters |

```bash
curl -s http://127.0.0.1:9005/targets | jq '.targets[] | {name, state, httpStatus, durationMillis}'
```

## A 200 is necessary and not sufficient

The repo's endpoints answer `{"success":true,"status":"healthy",...}`, and some mean it
conditionally — `jarvis-frame-viewer` answers **503 with `"status":"unhealthy"`** when it cannot reach
the frame store. A checker that reads only the status code calls a service healthy the moment it
starts lying politely. So this reads the body:

| Answer | Verdict |
|---|---|
| 2xx, `status: healthy` | up |
| 2xx, `status` anything else | **down** — "reported status 'degraded'" |
| 2xx, `success: false` | **down** |
| 2xx, not JSON, or JSON without those fields | up — taken at its word |
| any non-2xx | **down** |
| 3xx | **down**. Redirects are not followed |
| connection refused / timeout | **down**, with which one it was |

The fourth row is deliberate: demanding this repo's envelope from a third-party endpoint would make
the tool useless against exactly the dependencies most worth watching. The redirect row is too — a
health endpoint that redirects is a misconfiguration, and following it would report the health of
whatever answered instead.

> **`/health` is at the ROOT of every service, never under its route prefix.** `auth-server` runs with
> `AUTH_ROUTE_PREFIX=/auth` and its health endpoint is still `/health`; nginx proves it by mapping
> `/auth/health` → `auth-server:80/health`. `nginx/all-services.conf` is the authoritative list of
> where each one lives, and the compose target list is taken from it.

## Restraint is the feature

A monitor that pages on the first refused connection gets muted within a week, and a muted monitor
protects nothing. Three things stop that:

- **Two consecutive failures** before a target is called down (`HEALTH_MONITOR_FAILURES_BEFORE_DOWN`).
  A container being recreated by `docker compose up`, a restart, a GC pause — each produces exactly
  one refused connection. Failures must be **consecutive**: one good check resets the count.
- **One success** to call it up again (`HEALTH_MONITOR_SUCCESSES_BEFORE_UP`). The two mistakes are not
  equally bad — crying wolf costs trust, while calling a flapping service recovered costs one extra
  message. Believe good news quickly, bad news twice.
- **Transitions only.** A target that stays down produces one alert, not one per round.

**A target seen healthy on its first check is logged and not alerted.** Every target's first confirmed
check is a transition out of `unknown`, so without this every restart of this process would fire one
"is healthy again" per target — a fourteen-message burst for the stack below, on a channel whose whole
value is that a message from it means something. `unknown -> down` *is* alerted: starting up to find
something already broken is worth telling somebody.

Detection latency is therefore about `interval x failuresBeforeDown` — a minute on the defaults.

## What it reports

```
[ERROR] A health endpoint stopped answering. target=jarvis-hub url=http://jarvis-hub:9001/health
        state=down previousState=up detail="Connection refused" httpStatus=0
[INFO]  A health endpoint is answering again. target=jarvis-hub state=up downForSeconds=21
```

With `HEALTH_MONITOR_ALERT_URL` set — and it **is** set by default in the compose stack, pointing at
`apps/alerting` — each transition is also POSTed there, following the same conventional keys
`liveness-analyzer` uses:

```json
{"event":"health_check_failed","source":"health-monitor","severity":"critical",
 "title":"jarvis-hub is not answering its health endpoint",
 "message":"jarvis-hub (http://jarvis-hub:9001/health) failed its health check: Connection refused",
 "target":"jarvis-hub","state":"down","previousState":"up","httpStatus":0,"at":"..."}
```

The recovery carries `downForSeconds`, which is computed as the transition is made — by the time
anyone could read it back off the state, the timestamp it comes from has been overwritten by the very
transition being reported.

**The log line is written before the alert is attempted.** A failed alert is logged and not retried,
so if alerting is down at the same moment a service goes down, the log still has it. That ordering is
the only thing making that case survivable.

## Getting an actual email

`apps/alerting` is the email service, and it turns that POST into mail:

```
To:      ops@example.com
Subject: [Alert] health_check_failed from health-monitor

{"event":"health_check_failed","source":"health-monitor","severity":"critical",
 "title":"payments-api is not answering its health endpoint",
 "message":"payments-api (http://…/health) failed its health check: Connection refused", …}
```

The subject is `[Alert] {event} from {source}`; the body is the whole alert JSON. A recovery arrives
the same way as `health_check_recovered`, because being told it is over matters as much as being told
it broke.

**Three settings on `apps/alerting` decide whether any mail leaves**, none of them here:

| On `apps/alerting` | |
|---|---|
| `ALERT_EMAIL_ENABLED=true` | off by default |
| `ALERT_EMAIL_TO=you@example.com` | **the recipient** — alerting's setting, not this app's |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` / `SMTP_USE_TLS` | your mail server |

> **A 200 from alerting does not mean an email was sent.** It accepts and records every alert, and
> mails it only when all three of the above are in place — otherwise it answers 200 with
> `emailSent: false` and carries on. Treating that as success is how a monitor reports cover it does
> not have, which is worse than no alerting at all because it looks like protection.
>
> So this app reads alerting's answer. `/health` carries **`emailsSentTotal` separately from
> `alertsSentTotal`** — the two diverging is the signal that the mail path is broken — and
> **`lastAlertProblem`** names which setting is missing. It also logs a WARN per transition that
> produced no mail:
>
> ```
> [WARN] A health transition reached alerting but produced no email. target=payments-api state=down
>        reason="alerting accepted the alert but email is off there: set ALERT_EMAIL_ENABLED=true and
>        ALERT_EMAIL_TO"
> ```

**The recipient is alerting's, and there is deliberately no per-alert override.** `POST /alerts` is
proxied by nginx on `0.0.0.0:8080` (`/alerting/alerts`), so a `to` field in the request body would let
anything that can reach that port send mail to any address through your SMTP credentials — an open
relay wearing a monitoring badge. If health alerts need to go somewhere other than the rest, the clean
answer is a second alerting deployment with its own `ALERT_EMAIL_TO`, the way the repo already runs two
auth servers.

To turn emails off again and keep the log lines, set `ORWELL_HEALTH_ALERT_URL=` (empty) in the root
`.env`. Worth doing if you run a partial stack: a service you never started looks exactly like one
that went down.

## In the compose stack

The service is already wired up with all fourteen of the stack's health endpoints and points at the
alerting service, so transitions become emails as soon as `apps/alerting` has its three email settings:

```bash
./scripts/stack.sh up -d health-monitor
curl -s http://127.0.0.1:9005/targets | jq '{targetsDown, targets: [.targets[] | {name, state}]}'

# Then, in apps/alerting/.env, so the alerts become mail:
#   ALERT_EMAIL_ENABLED=true
#   ALERT_EMAIL_TO=you@example.com
#   SMTP_HOST=... SMTP_PORT=587 SMTP_USERNAME=... SMTP_PASSWORD=...
curl -s http://127.0.0.1:9005/health | jq '{alertsSentTotal, emailsSentTotal, lastAlertProblem}'
```

It has **no `depends_on`**, deliberately: a monitor that waits for the things it monitors to be healthy
cannot report that they are not. It starts first and says what it finds. It publishes `127.0.0.1:9005`
and is not behind nginx — `/targets` is a map of the internal topology, which is not secret but is not
something to put on the LAN for free.

## Configuration

| Key | Default | |
|---|---|---|
| `HEALTH_MONITOR_TARGETS` | *(blank)* | comma-separated `name=url`. Blank checks nothing, and says so at startup |
| `HEALTH_MONITOR_INTERVAL_SECONDS` | `30` | seconds between rounds |
| `HEALTH_MONITOR_TIMEOUT_SECONDS` | `5` | per request. **Must stay below the interval**; it warns if not |
| `HEALTH_MONITOR_FAILURES_BEFORE_DOWN` | `2` | consecutive failures before an outage |
| `HEALTH_MONITOR_SUCCESSES_BEFORE_UP` | `1` | consecutive successes before recovery |
| `HEALTH_MONITOR_ALERT_URL` | *(blank; `http://alerting/alerts` in compose)* | where a transition is sent to become an email. Blank logs only |

Names should be the service's name rather than its address: `172.20.0.7 is down` at three in the
morning is a puzzle, not a message. An unparseable target list is **fatal at startup**, because a
monitor that silently drops one entry watches less than it was told to and no alerts is what a healthy
stack looks like.

Targets are checked **in parallel**. Sequentially, one target that has stopped answering delays every
target after it by the full timeout, so one dead service can push the round past its own interval and
make the whole stack get checked late.

## Who watches the watchman

This app's failure is the only one that announces nothing: every other component's outage is reported
*by* it, and silence is its normal output. Running it on its own host removes the worst version of that
— dying alongside the things it watches — but not the likelier one: **up, listening, and not checking.**
The port answers, `/health` says healthy, no rounds run, and the absence of alerts reads as a healthy
stack. Two things here cover it.

### It publishes a heartbeat, on its own Loki stream

After every completed round — never before it, so the beat is evidence that checking happened rather
than that a timer fired — it pushes one line to `stream_type="heartbeat"`:

```json
{"message":"Health monitor heartbeat.","clientId":"health-monitor",
 "targets":14,"targetsDown":0,"intervalSeconds":30}
```

**A separate stream, not the app log.** A beat every 30s is ~2,900 lines a day saying nothing happened,
and `{stream_type="app"}` is what `apps/log-analyzer` triages for errors. On its own label it costs that
reader nothing and a consumer can select it directly. It needs `LOKI_URL`; without one the app warns at
startup that nothing can detect it dying.

**`clientId` is that field on purpose**, even though this is a service and not a client: it is what
[`apps/liveness-analyzer`](../liveness-analyzer/README.md) groups heartbeats by, reading it out of the
line body with `| json`. So that app can watch this one with configuration and no code:

```bash
LIVENESS_LOKI_QUERY='{stream_type="heartbeat"} | json | message="Health monitor heartbeat."'
LIVENESS_EXPECTED_CLIENTS=health-monitor
LIVENESS_THRESHOLD_SECONDS=180          # a few missed beats, not one
```

The two apps then cover each other: this one probes the servers, that one watches this one. What
remains is "Loki and the liveness analyzer are down too", which is a far smaller target than "the
monitor is down".

### Its own `/health` admits when its rounds have stopped

`GET /health` answers **503 with `status: unhealthy`** when no round has completed in three intervals
(minimum 30s), and carries `checksCurrent`, `lastRoundAt` and `roundsTotal` either way. Before the
first round it is judged against startup instead, so a scheduler that never started at all is caught
too rather than sitting at "no rounds yet" forever.

It stays **healthy while targets are down** — that is a fact about the stack, and reporting it here
would have an orchestrator restart the monitor at the exact moment it is doing its job. The split is
what makes an external check on this endpoint worth anything: a port-open check cannot tell a working
monitor from a wedged one, and this can.

**Still: do not treat "no alerts" as evidence on its own.**

## Other limits worth knowing
- **Latency is reported, never alerted on.** `durationMillis` and `slowestMillis` are on `/targets`, but
  "slow" needs a per-target baseline this app does not keep, and a threshold guessed for one service is
  wrong for the next. A service that is up and crawling will not wake you.
- **It only knows what an endpoint admits.** The Spring `SharedHealthController` returns `healthy`
  unconditionally — a service that has lost its database still says healthy unless it contributes a
  `HealthDetailsProvider` that says otherwise. This tool is only as good as the endpoints it asks.
- **No history.** Counters since start, and one minute of nothing else. It answers "is it up now" and
  "how many outages since I started", not "what was our uptime last month".
- **One URL per target.** No auth headers, no POST bodies, no expected-content matching.
