# Reverse proxy

Fronts a single upstream service. Every request is run past a chain of **policies** first: if one
refuses, the proxy answers `403` and the upstream never sees the request. Every request — allowed,
blocked or rejected — produces exactly one structured log record through the shared
`dev.orwell.logging.Logger`, so the log is a complete audit of what the proxy was asked to do,
not only of what it stopped.

```
client ──▶ ProxyController ──▶ PolicyChain ──▶ UpstreamClient ──▶ upstream
                                    │
                                    └── refused ──▶ 403 {"success":false,"error":"policies do not allow you to send this request"}
```

## Policies

`Policy` is the extension point:

```java
@FunctionalInterface
public interface Policy {
    boolean pass(ProxyRequest request);

    default String name() { return getClass().getSimpleName(); }
}
```

`ProxyRequest` is framework-neutral — method, percent-encoded path, query, case-insensitive
headers, remote address — so a policy can be unit-tested without a servlet container. The body is
deliberately not part of it: a policy decides from the request line and headers, which the proxy
has in full before reading a byte of the body, so a blocked request costs nothing to refuse.

`PolicyChain` runs the policies in order and stops at the first refusal, naming it in the log
line. Rules that hold by construction:

- **An empty chain allows everything.** A proxy with no policies configured is a pass-through,
  not a closed door.
- **A policy that throws blocks the request.** The chain fails closed, and the exception is logged
  at ERROR under `policy-error` — a silent deny would look exactly like a working blocklist.

To add a policy, declare another `Policy` bean; nothing else changes:

```java
@Bean
Policy requireApiKey() {
    return request -> request.header("X-Api-Key") != null;
}
```

**A policy cannot change the request**, and that is on purpose: it answers a boolean and never
touches what goes upstream, which is what lets `policies` in the audit table fingerprint the whole
chain and mean something. Anything that needs to *rewrite* a request — inject a credential, pick an
identity — does not belong here. [`apps/api-key-cycler`](../api-key-cycler/README.md) is a separate
proxy that does exactly that, with its own copy of the forwarding code; it shares nothing with this
one.

### The pattern policy

`PatternPolicy` ships by default and is configured from `PROXY_BLOCKED_PATTERNS`: a
comma-separated list of regexes, **full-matched** against the request target (`/path` or
`/path?query`), each optionally prefixed with `METHOD:` to scope it to one HTTP method.

| Pattern | Blocks |
|---|---|
| `/admin.*` | every method on every path starting with `/admin` |
| `POST:/orders` | `POST /orders` only |
| `.*[?&]debug=true` | anything carrying `?debug=true` |

Two things to know:

- Full match, not substring. `/admin` blocks `/admin` alone, not `/admin/users` — write
  `/admin.*` for the subtree.
- A comma always separates two patterns and is never part of one, so a regex needing a literal
  comma (a `{2,4}` quantifier) has to be written without it: `\d\d\d?\d?`.

A pattern that does not compile fails startup rather than being skipped, so a typo can't leave you
with a blocklist that blocks nothing.

## Endpoints

Everything is proxied. `GET /health` is the one exception: it is the shared health endpoint from
`server-bootstrap` (an exact mapping, which Spring prefers over the catch-all), and it reports the
configured upstream and the active policy names.

| Response | When |
|---|---|
| upstream's own status, headers and body | the request passed every policy |
| `403 {"success":false,"error":"policies do not allow you to send this request"}` | a policy refused it |
| `413 {"success":false,"error":"request body too large"}` | the body exceeded `PROXY_MAX_BODY_BYTES` |
| `502 {"success":false,"error":"upstream request failed"}` | the upstream was unreachable or timed out |

Hop-by-hop headers (`Connection`, `Transfer-Encoding`, `Host`, `Content-Length`, …) are stripped in
both directions per RFC 9110 §7.6.1; everything else is relayed untouched. Redirects are **not**
followed — the `Location` goes back to the client, which is whose decision it is.

Two things Tomcat decides before the proxy is reached, both verified rather than assumed:

- **`TRACE` gets a `405`** from Spring MVC and is never proxied. Every other method is
  (GET/POST/PUT/PATCH/DELETE/OPTIONS/HEAD).
- **A path containing an encoded slash (`%2F`) gets a `400`.** That is Tomcat's default and it
  never reaches the controller, so the "encoding is preserved" rule below covers encoded spaces
  and `?` but not `%2F`. Relaxing it is a Tomcat setting, deliberately not set here.

## Configuration

| Variable | Default / `.env.example` value | Purpose |
|---|---|---|
| `SERVER_ADDRESS` | **required** — `.env.example` uses `127.0.0.1` | Proxy bind address |
| `SERVER_PORT` | **required** — `.env.example` uses `9300` | Proxy port |
| `PROXY_UPSTREAM_URL` | **required** — `.env.example` uses `http://127.0.0.1:8080` | Origin every request is forwarded to |
| `PROXY_BLOCKED_PATTERNS` | `` (block nothing) | Comma-separated patterns for `PatternPolicy` |
| `PROXY_UPSTREAM_TIMEOUT_SECONDS` | `30` | Connect and request timeout for the upstream call |
| `PROXY_MAX_BODY_BYTES` | `10485760` (10 MiB) | Largest body buffered for replay; over it, `413` |
| `PROXY_AUDIT_DATASOURCE_URL` | `` (log only, no table) | Postgres holding `request_audit` |
| `PROXY_AUDIT_DATASOURCE_USERNAME` | `` | Role for that database |
| `PROXY_AUDIT_DATASOURCE_PASSWORD` | `` | Its password |
| `PROXY_AUDIT_QUEUE_DEPTH` | `10000` | Records buffered ahead of the writer; over it, rows drop |
| `PROXY_AUDIT_RETENTION_DAYS` | `30` | Days of trail kept, swept hourly. `0` keeps everything |

`SERVER_ADDRESS`, `SERVER_PORT` and `PROXY_UPSTREAM_URL` are `required`: unset, the app exits at
startup with a validation error. `LOGGER`/`LOKI_URL`/`LOKI_TENANT_ID` come from `AppServerEnv` as
with every other server — `LOGGER` chooses the sinks (`console`, `disk`, `loki`,
`loki-with-fallback`, `both`), which matters more here than elsewhere: the audit trail this proxy
produces is its whole point, so a deployment that cares about it wants `loki-with-fallback` rather
than records dropped during a Loki outage. There is no `SERVER_ENGINE` here — this app is Spring/Tomcat only, because it relies
on servlet streaming for arbitrary request bodies.

## The audit record

Every request writes one record through `dev.orwell.logging.Logger` — allowed at INFO, refused at
WARN, oversized or failed at WARN/ERROR — with `requestId`, `method`, `target`, `remoteAddress`,
`status` and, for a forwarded request, `requestBytes`, `responseBytes`, `durationMs` and
`upstreamMs`.

```
[reverse-proxy] [INFO] Proxied request. requestId=0f3c… method=GET target=/frames service=jarvis-person-detection status=200 durationMs=12 upstreamMs=11
[reverse-proxy] [WARN] Request blocked by policy. requestId=91ab… method=GET target=/admin/panel service=insta policy=pattern-policy status=403
```

**Four fields answer "who", and they answer different questions.** `remoteAddress` is the socket,
which behind another hop is that hop; `forwardedFor` is what that hop claims the original address
was; `service` is the calling *process*; `clientId` is the authenticated *identity*, and the only
one of the four that means a credential rather than a machine.

`service` comes from the `Service` request header. A remote address only identifies a container,
and identifies a different one after the next restart; the header says which service it was.
Callers are meant to set it by building their `HttpClient` through
`dev.orwell.http.ServiceHeaderHttpClient` (see `packages/http-client`) — a one-line change where
the client is built. Any key is left out rather than logged as null when the header is absent, so
"which callers are still unattributed" is a query rather than a value to squint at.

> **Today every caller is unattributed.** No client in the repo wraps with
> `ServiceHeaderHttpClient`, so `service` is `NULL` on every row an orwell service produces, and the
> "who is calling" query below returns a single `NULL` group. The same gap empties `request_id` of
> its point across a hop: that wrapper is the only thing that forwards the id
> `RequestIdFilter` bound, so a caller and its callee mint different ids. Nothing in this service is
> wrong — the columns, the index and the queries all work the moment a caller sends the header.
> Tracked as [`ISSUES.md`](../../ISSUES.md) §2.

The proxy relays that header upstream untouched and **does not** stamp its own name over it: it is
a relay, so the name the upstream sees is the original caller's, not `reverse-proxy`.

`requestId` is the inbound `X-Request-Id` when the caller sent a usable one, otherwise the id
`RequestIdFilter` already bound to the request — the same one it puts on the response — and only
failing both a freshly minted one. So the id a caller reads off its own response is the id in
`request_audit`, and a log line always joins to its row.

The proxy also **forwards** that id upstream, which is the one header this relay adds. Without it a
caller that sent no id would have the proxy resolve one and the upstream mint a third, and the hop
the column exists to span would be the one hop it could not follow. It is not stamped with a
`Service` name — that stays the original caller's across the hop.

`policies` is a fingerprint, not a list of names: each policy's name plus a short digest of its
configuration. Names alone would read `pattern-policy` both before and after someone edited
`PROXY_BLOCKED_PATTERNS`, so *"was this allowed under today's rules or yesterday's?"* — the
question an audit is usually opened to answer — could not be asked of the table.

**Two timings, because one cannot answer the question that gets asked.** `durationMs` is what the
client waited; `upstreamMs` is what the origin took. The difference between them is this proxy's
own cost, which is the only way to tell a slow upstream from a slow policy chain. A *failed*
request keeps `upstreamMs` too — a connection refused in a millisecond and one that sat until the
timeout are the same 502 to the client and very different problems.

The upstream URL and the policy list are **not** in the log metadata: they are identical on every
line one process writes, and the startup record names them once. They are columns in the table,
where rows from more than one proxy can meet.

## The audit table

The same record is also a row in `request_audit`, which is what makes the trail queryable instead
of greppable. One record, two destinations, built from one `ProxyAuditRecord` so the log and the
table can never describe a request differently.

| Column | Notes |
|---|---|
| `id` | `BIGSERIAL` |
| `received_at` | When the proxy took the request, **not** when the row was written — the writer runs behind the request path, so insertion order is not arrival order |
| `outcome` | `PROXIED` \| `BLOCKED` \| `REJECTED` \| `FAILED` |
| `request_id` | The caller's `X-Request-Id` or one minted here; what joins this row to the same request's log lines |
| `service` | The caller's name from the `Service` header; `NULL` when it sent none |
| `client_id` | The authenticated identity from `X-Client-Id`; the only "who" column that means a credential rather than a machine |
| `method`, `path`, `query` | Split on purpose: a path is what you group by, a query is what you search. `query` is `NULL` when the request line carried none |
| `remote_address`, `forwarded_for` | The socket, and what the hop in front claims the original was. Both kept, because neither can be trusted to be the other |
| `user_agent` | |
| `upstream`, `policies` | Which proxy wrote the row and what it was enforcing. Constant per process, repeated per row on purpose — two proxies sharing this table have nothing else to tell them apart. `policies` is `name:digest` per policy, comma-separated (`pattern-policy:1a2b3c4d`), and `''` means an open pass-through |
| `status` | What the client was answered |
| `policy` | The policy that refused it; `NULL` unless `outcome = 'BLOCKED'` |
| `request_bytes`, `response_bytes`, `duration_ms` | Only for a request that reached the upstream; `NULL` otherwise |
| `upstream_ms` | What the origin took, against `duration_ms`'s what-the-client-waited. Kept for a `FAILED` row too |
| `detail` | The rejection reason or the upstream error, when there is one |

What does not apply to an outcome is `NULL`, never `0`, so `WHERE policy IS NOT NULL` means what
it says. Five indexes cover the questions the table exists for: recency, per-service, per-identity,
by request id, and non-`PROXIED` outcomes — all but the first partial, because a row with no
service, no client id, no request id, or a `PROXIED` row are exactly the ones those indexes would
never serve. The DDL is `src/main/resources/audit-schema.sql`, applied at startup and idempotent;
it is plain SQL rather than a Hibernate mapping because this table's whole purpose is to be read by
things that are not this application.

**The `proxy` role and database are not created for you on an existing deployment.** They are in
`db-init/all-services.sql`, which Postgres runs *only* when the data volume is first initialised —
so a stack whose volume predates this service has no such role, `JdbcAuditStore` fails at startup,
and `restart: unless-stopped` turns that into a crash loop. `scripts/redeploy-on-update.sh` runs
unattended, so nobody is watching when it happens. `depends_on: db: service_healthy` does not help:
the database is healthy, the role simply is not there. Create it once, as
`docker-compose.all-services.yml` notes beside the service:

```bash
docker compose exec -T db psql -U postgres \
  -c "CREATE ROLE proxy LOGIN PASSWORD 'proxy'" -c "CREATE DATABASE proxy OWNER proxy"
```

The same is true of `jarvis`, for the same reason. A fresh volume needs neither.

A table created by an earlier build is upgraded in place: the file's `ALTER TABLE … ADD COLUMN IF
NOT EXISTS` statements add the newer columns as nullable, which is both safe on a populated table
and honest — every one of them is a fact the proxy did not record at the time, and `NULL` says
exactly that.

```sql
-- who is calling, and how much
-- (one NULL group until a caller wraps with ServiceHeaderHttpClient — see the note above)
SELECT service, count(*), round(avg(duration_ms)) AS avg_ms
FROM request_audit WHERE received_at > now() - interval '1 day'
GROUP BY service ORDER BY 2 DESC;

-- everything that was refused, newest first
SELECT received_at, service, method, path, policy
FROM request_audit WHERE outcome = 'BLOCKED' ORDER BY received_at DESC LIMIT 50;

-- callers that are still unattributed
SELECT remote_address, count(*) FROM request_audit WHERE service IS NULL GROUP BY 1 ORDER BY 2 DESC;

-- the slowest paths, and how much of it was the upstream rather than this proxy
SELECT path, count(*), max(duration_ms), max(duration_ms - upstream_ms) AS max_proxy_ms
FROM request_audit WHERE outcome = 'PROXIED' GROUP BY path ORDER BY 3 DESC LIMIT 20;

-- everything one identity did
SELECT received_at, outcome, method, path, status
FROM request_audit WHERE client_id = 'insta-worker' ORDER BY received_at DESC LIMIT 100;

-- one request end to end, starting from an id seen in a log line
SELECT * FROM request_audit WHERE request_id = '...';
```

In the local stack: `docker compose exec db psql -U proxy -d proxy`.

### What it costs, stated plainly

- **The database is never on the request path.** A request hands its record to a bounded queue and
  returns; one virtual thread drains that queue in batches. This is not a `Logger` sink —
  `DatabaseLogger` was deleted for putting a round trip on every request, and nothing here brings
  that back.
- **A row can therefore be lost** — but only when the queue fills. `PROXY_AUDIT_QUEUE_DEPTH`
  records absorb a stall, and a database that comes back gets the whole backlog: an outage of the
  local stack's Postgres was ridden out with `droppedTotal` still `0` and every row landing on
  recovery. Past that depth, `submit` drops rather than waits, counting it in `droppedTotal` and
  saying so in the log. The *log* record is never lost, so what degrades under real pressure is
  the queryability of the trail, not the trail.
- **Startup and steady state fail differently.** A store that cannot connect or cannot apply its
  schema aborts the boot — you asked for an audit trail and it cannot be kept. Once serving, a
  database that goes away costs only queue depth, because killing the proxy would turn a Postgres
  outage into a total one.
- **The table is bounded.** `PROXY_AUDIT_RETENTION_DAYS` (default 30) is swept hourly by the same
  writer thread. Rows are small, so unlike jarvis's frame bytes this needs no worker of its own.
  `0` disables the sweep, which is a deliberate choice on the Postgres the whole repo shares.
- **Unset `PROXY_AUDIT_DATASOURCE_URL` means log-only**, announced with a warning at startup so an
  empty table is never a surprise. That is what lets the jar run without a Postgres.

`/health` reports the store alongside the upstream and the policies:

```json
{"success":true,"status":"healthy","upstream":"http://nginx","policies":["pattern-policy"],
 "audit":{"enabled":true,"pending":0,"writtenTotal":128,"droppedTotal":0},"engine":"spring"}
```

`engine` is stamped by the shared `SharedHealthController` on every Spring app, last and after this
service's own fields, so it names the runtime that answered rather than anything this service
claims about itself.

## Build and run

```bash
mvn -pl apps/reverse-proxy -am package
java -jar apps/reverse-proxy/target/reverse-proxy-0.1.0-SNAPSHOT-exec.jar
```

In Docker, as part of the local stack:

```bash
docker compose -f docker-compose.storages.yml -f docker-compose.all-services.yml \
  up -d reverse-proxy
curl http://127.0.0.1:9300/health
```

There it fronts `nginx` — the stack's own edge — so everything it admits reaches the same routes
as port 8080 does. It is a second way in rather than a replacement: the point is that this one
logs every request, and can refuse some, before nginx dispatches it. `PROXY_BLOCKED_PATTERNS` is
empty in that service on purpose, because a local stack that silently refused a path would be
debugged as a broken service; set it in a gitignored `apps/reverse-proxy/.env` to try a blocklist.
The published port is `ORWELL_PROXY_PORT` (default `9300`).

## Notes

The request body is buffered in memory because it has to be replayed on a second connection —
hence the `PROXY_MAX_BODY_BYTES` ceiling, which is what keeps one upload from taking the proxy
down. Spring's form-content filter is disabled in `application.properties` for the same reason:
it would consume the body of a `PUT`/`PATCH`/`DELETE` form request before the proxy could relay it.
