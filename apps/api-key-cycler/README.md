# API key cycler

Fronts a single upstream and holds several API keys for it, spreading requests across them so an
app behind the proxy sees the pool's combined quota instead of one key's. When the upstream refuses
a key — `429`, or a `401`/`403` from one that has been revoked — that key is rested and the request
is sent again with the next one, so a rate limit costs a retry rather than an error.

```
                    ┌──────── 429/401/403: rest that key, try the next ────────┐
                    ▼                                                          │
client ──▶ ProxyController ──▶ ApiKeyFilter.before ──▶ UpstreamClient ──▶ upstream
                                (writes Authorization)         │
                                                    ApiKeyFilter.after ◀───────┘
```

It is a **standalone app** and shares no code with
[`apps/reverse-proxy`](../reverse-proxy/README.md). The two solve different problems — that one
records and refuses requests, this one rewrites them — so each carries its own forwarding
(`ProxyController`, `ProxyRequest`, `ProxyResponse`, `UpstreamClient`, `ProxyEndpoint`) and neither
constrains the other's shape.

That is a deliberate trade with a real cost, stated here so nobody has to rediscover it: the
hop-by-hop header list (RFC 9110 §7.6.1), `X-Request-Id` propagation across the hop, the two-clock
timing and the body-buffer ceiling exist in both apps, and **a fix to any of them here does not
reach the reverse proxy, or the other way round.** Check the sibling by hand when you touch one.

**They stack.** Point `CYCLER_UPSTREAM_URL` at the audit proxy on 9300 and every rotated request
lands in `request_audit` — one row per attempt, since each rotation is a fresh request as far as
that proxy is concerned — without either app learning anything about the other. This app's own log
line carries `attempts`, which is what ties those rows back to one client request.

## How the pool behaves

Two rules, and they are the whole of `ApiKeyPool`:

- **Round-robin by default**, not drain-the-first. A quota is usually per key per window, so
  holding three keys is meant to be a larger window — which it only is if requests are shared out.
- **A refused key rests** for `CYCLER_COOLDOWN_SECONDS`. Without this, cycling is a bicycle with a
  flat: the rate-limited key comes round again on the very next request and earns the same refusal,
  so throughput collapses to whatever the healthy keys pick up by luck.

**An exhausted pool still hands out a key.** When every key is resting, the one whose rest ends
soonest is used rather than the proxy inventing a `503`. The upstream's own `429` — with its
`Retry-After` and its own explanation — tells the client more than a synthetic error would, and it
does not make the proxy look like the thing that broke.

The pool is lock-free: a cursor and a deadline per key, both atomics. Two requests racing may take
the same key or skip one; neither costs anything, and paying for a lock on every request to prevent
it would.

## Keys are never printed

This is the process that holds every key at once, so it is the one that must never print one.
Everything it says about itself — the log line when a key is refused, the `signature()` that
reaches the log, `/health` — names the **position** in the pool (`key #2`), never the value. `ApiKey.toString()` is overridden for the same reason: the record default would print the
secret into the first stack trace that touched it. `ApiKeyPoolTest` and `ApiKeyFilterTest` both
assert this rather than trusting it.

The same goes for getting the keys in: `CYCLER_API_KEYS` is an ordinary env option, so launching
the jar with the `url` argument loads it — and the whole environment — from the secrets manager at
startup (see `dev.orwell.env.http.EnvLoader`). A deployment that must not have keys in a compose
file already has a way not to, and this app does not grow a secret-fetching path of its own.

## Endpoints

Everything is proxied. `GET /health` is the one exception — the shared endpoint from
`server-bootstrap` — and it reports the pool alongside the upstream, so *"are we out of keys?"* is
a question the health check answers rather than one inferred from a rate of 429s:

```json
{"success":true,"status":"healthy","upstream":"https://api.example.com","maxAttempts":3,
 "keyPool":{"keys":3,"cooldownSeconds":60,"resting":["key #2"]},"engine":"spring"}
```

| Response | When |
|---|---|
| upstream's own status, headers and body | the request was forwarded (possibly after a rotation) |
| the upstream's `429`/`401`/`403` | every attempt in the budget was refused |
| `413 {"success":false,"error":"request body too large"}` | the body exceeded `CYCLER_MAX_BODY_BYTES` |
| `500 {"success":false,"error":"the proxy could not prepare this request"}` | no key could be put on the request; it is not forwarded bare |
| `502 {"success":false,"error":"upstream request failed"}` | the upstream was unreachable or timed out |

There are **no policies** here and nothing is stored: this proxy admits everything and exists to
change the request, not to judge or record it. Put the reverse proxy in front of or behind it if a
blocklist or an audit trail is wanted as well.

## Configuration

| Variable | Default / `.env.example` value | Purpose |
|---|---|---|
| `SERVER_ADDRESS` | **required** — `.env.example` uses `127.0.0.1` | Bind address |
| `SERVER_PORT` | **required** — `.env.example` uses `9310` | Port |
| `CYCLER_UPSTREAM_URL` | **required** | Origin every request is forwarded to |
| `CYCLER_API_KEYS` | **required** | The pool, comma-separated. Blank entries are dropped; an empty pool fails startup |
| `CYCLER_KEY_HEADER` | `Authorization` | Header the key is written to, replacing whatever the client sent |
| `CYCLER_KEY_SCHEME` | `Bearer` | Word before the key. Empty sends the bare value, which is what `X-API-Key` wants |
| `CYCLER_ROTATE_ON_STATUSES` | `429,401,403` | Answers meaning "this key is spent". Empty means spread requests, react to nothing |
| `CYCLER_COOLDOWN_SECONDS` | `60` | How long a refused key rests |
| `CYCLER_MAX_ATTEMPTS` | `3` | Upstream trips one request may cost, **capped at the key count at startup** |
| `CYCLER_UPSTREAM_TIMEOUT_SECONDS` | `30` | Connect and request timeout per attempt |
| `CYCLER_MAX_BODY_BYTES` | `10485760` (10 MiB) | Largest body buffered for replay; over it, `413` |

Every key is `CYCLER_*` rather than `PROXY_*` even where it means what the reverse proxy's version
of it means. Sharing a name across two processes is how a setting gets applied to the one you were
not thinking about — the repo renamed a whole `DETECTION_*` family for this reason and does not
need a second one.

`LOGGER`/`LOKI_URL`/`LOKI_TENANT_ID` come from `AppServerEnv` as with every other server. There is
no `SERVER_ENGINE`: this app relies on servlet streaming to relay arbitrary request bodies, so it
is Spring/Tomcat only.

**`CYCLER_MAX_ATTEMPTS` is capped at the number of keys**, with a warning at startup when it is
configured higher. Beyond that a retry can only reach a key that has already refused this request.

## What it costs, stated plainly

- **A rotation is a second full round trip.** The client waits for both. `durationMs` is what it
  waited, `upstreamMs` is the sum of the upstream calls, and `attempts` says how many there were —
  the three together are what make a rotating proxy's latency readable.
- **The body is buffered in memory**, because it has to be replayed on the next attempt. Hence
  `CYCLER_MAX_BODY_BYTES`.
- **Rotation is per request, not per client.** Two requests from the same caller may use different
  keys. If an upstream's rate limit or its session semantics are per key *and* per user, this is
  the wrong tool.
- **The pool is per process.** Two replicas do not share cooldowns, so each learns which keys are
  rate-limited on its own. That is fine for a handful of replicas and would not be for many.
- **Nothing is persisted.** Cooldowns are in memory and start empty on a restart, which costs one
  refused request per spent key.

## Build and run

```bash
mvn -pl apps/api-key-cycler -am package
java -jar apps/api-key-cycler/target/api-key-cycler-0.1.0-SNAPSHOT-exec.jar
```

In Docker, as part of the local stack:

```bash
docker compose -f docker-compose.storages.yml -f docker-compose.all-services.yml \
  up -d api-key-cycler
curl http://127.0.0.1:9310/health
```

The published port is `ORWELL_CYCLER_PORT` (default `9310`). In the stack it points at `nginx` with
three placeholder keys, so it starts and is exercisable without a real upstream — set
`CYCLER_UPSTREAM_URL` and `CYCLER_API_KEYS` in a gitignored `apps/api-key-cycler/.env` to point it
at something real.
