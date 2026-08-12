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

## Configuration

| Variable | Default / `.env.example` value | Purpose |
|---|---|---|
| `SERVER_ADDRESS` | **required** — `.env.example` uses `127.0.0.1` | Proxy bind address |
| `SERVER_PORT` | **required** — `.env.example` uses `9300` | Proxy port |
| `PROXY_UPSTREAM_URL` | **required** — `.env.example` uses `http://127.0.0.1:8080` | Origin every request is forwarded to |
| `PROXY_BLOCKED_PATTERNS` | `` (block nothing) | Comma-separated patterns for `PatternPolicy` |
| `PROXY_UPSTREAM_TIMEOUT_SECONDS` | `30` | Connect and request timeout for the upstream call |
| `PROXY_MAX_BODY_BYTES` | `10485760` (10 MiB) | Largest body buffered for replay; over it, `413` |

`SERVER_ADDRESS`, `SERVER_PORT` and `PROXY_UPSTREAM_URL` are `required`: unset, the app exits at
startup with a validation error. `LOKI_URL`/`LOKI_TENANT_ID` come from `AppServerEnv` as with every
other server. There is no `SERVER_ENGINE` here — this app is Spring/Tomcat only, because it relies
on servlet streaming for arbitrary request bodies.

## Build and run

```bash
mvn -pl apps/reverse-proxy -am package
java -jar apps/reverse-proxy/target/reverse-proxy-0.1.0-SNAPSHOT-exec.jar
```

## Notes

The request body is buffered in memory because it has to be replayed on a second connection —
hence the `PROXY_MAX_BODY_BYTES` ceiling, which is what keeps one upload from taking the proxy
down. Spring's form-content filter is disabled in `application.properties` for the same reason:
it would consume the body of a `PUT`/`PATCH`/`DELETE` form request before the proxy could relay it.
