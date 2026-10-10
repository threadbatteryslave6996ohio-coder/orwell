# http-client

Two decorators over `java.net.http.HttpClient`: one says who is calling, one records the calls.
Neither changes a call site — both are a one-line change where the client is built, and they
compose. Both extend `DelegatingHttpClient`, which carries the pass-through surface so a decorator
only has to write the part it exists for.

```java
private final HttpClient http = LoggingHttpClient.wrap(
        ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "secrets-manager-client"),
        logger);
```

> **Adoption status.** That is the intended shape, not the current one. `LoggingHttpClient` is used
> by every outbound client in the repo; `ServiceHeaderHttpClient` is used by **none** — every call
> site wraps with the logging decorator alone. Two things therefore do not work today:
> `request_audit.service` is NULL for every request an orwell service makes, and a service does not
> forward the `X-Request-Id` that `RequestIdFilter` bound, because this wrapper is what would attach
> it. (The reverse proxy is the exception on the id: `UpstreamClient` sets that header explicitly,
> so a proxied hop correlates. Service-to-service calls do not.) Tracked as `ISSUES.md` §2.
> Everything described below is built and tested — it is wiring that is missing, one line per call
> site.

## ServiceHeaderHttpClient

Says who is calling — once something wraps with it. Nothing does yet; see the note above.

A server that logs `remoteAddress=172.18.0.7` knows a container made the request. It does not know
which service that was, and after the next restart the address means something else.
`ServiceHeaderHttpClient` wraps an existing `HttpClient` so every request it sends carries a
`Service` header naming the process, and the receiving end logs that name.

```java
// before
private final HttpClient http = HttpClient.newHttpClient();

// after
private final HttpClient http = ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "log-analyzer");
```

That is the whole adoption cost. The wrapper **is** an `HttpClient`, so nothing downstream of the
field changes: the same `send`, the same `sendAsync`, the same builders, the same responses.

### What it guarantees

- **A header set at the call site wins.** A request that already carries `Service` goes out
  untouched, so one call can be attributed to something more specific than the process — and a
  relay passes the original sender's name through instead of overwriting it with its own.
- **Attribution never breaks a request.** In the (near-unreachable) case where the JDK refuses to
  copy a request, the original is sent unstamped. A missing log field is a smaller failure than a
  call that stopped working because someone wanted better logs.
- **A bad name fails at startup.** Blank, or carrying a character illegal in a header value — a
  newline being the interesting one, since that is header injection rather than a name — throws
  from `wrap`, not from the first request.
- **Nothing else changes.** Every other method delegates, `close()` and the rest of the shutdown
  family included, so the wrapper owns nothing and closing it closes the client it wraps.
- **Wrapping twice is harmless.** The outermost name is the one applied.

The name should be the same string the process already calls itself — what it passes to
`AppServer` or to `LoggerSetup.fromConfiguration` — so the sender's own logs and the receiver's
agree on what to call it.

### Reading it on the other end

Anything that can see request headers can log the name. The reverse proxy does it in one line and
puts `service` in the metadata of every record it writes:

```java
String service = request.header(ServiceHeaderHttpClient.SERVICE_HEADER);
```

```
[reverse-proxy] [INFO] Proxied request. method=GET target=/frames service=jarvis-person-detection status=200
[reverse-proxy] [WARN] Request blocked by policy. method=GET target=/admin/panel service=insta policy=pattern-policy
```

The key is left out rather than logged as null when the header is absent, so "which callers are
still unattributed" is a query rather than a value to squint at. (Which, until something wraps with
this class, is all of them — the sample lines above show what the reading end *will* print, not what
it prints today.)

### What it is not

- **Not authentication.** A client sets this header itself and anything can claim any name. It is
  for attribution in logs; `AUTH_BASE_URL` and the auth service are what decide whether a caller
  is allowed in.
- **Not a tracing library.** One header, one name, no span ids, no propagation, no context
  plumbing. If this repo ever wants distributed tracing, that is a different thing and should
  arrive as one.
- **Not applied to WebSocket handshakes.** `newWebSocketBuilder()` delegates untouched — the
  handshake is built by the returned builder, which this class never sees.

## LoggingHttpClient

Records the calls.

An outbound call is the one part of a service's work that leaves no trace in its own logs unless
someone writes the line by hand — which means each call site invents its own message, or skips it.
`LoggingHttpClient` wraps an existing `HttpClient` and writes one record per request to the
`dev.orwell.logging.Logger` the component already has, so a service's outbound calls land in the
same stream as the rest of its work.

```java
// before
private final HttpClient http = HttpClient.newHttpClient();

// after
private final HttpClient http = LoggingHttpClient.wrap(HttpClient.newHttpClient(), logger);
```

```
[secrets-manager-client] [INFO] Sent HTTP request. method=GET uri=http://secrets:8080/bundles/12 status=200 durationMs=34
[secrets-manager-client] [WARN] HTTP request failed. method=GET uri=http://secrets:8080/bundles/12 durationMs=5001 error=java.net.http.HttpConnectTimeoutException: HTTP connect timed out
```

### What it guarantees

- **One record, written when the request finishes**, carrying `method`, `uri`, `status` and
  `durationMs` — structured metadata, not a concatenated message. A line without a status or a
  duration is barely worth the volume, and the repo's sinks do not filter by level, so it does not
  also write one when the request starts. The cost is that a call which never returns leaves no
  record — so **a client built for this repo sets both a connect timeout and a per-request
  `.timeout(...)`**, and then it does return, as a failure. A connect timeout alone is not enough:
  it bounds reaching the host, not a host that accepts the request and then stops answering, which
  is the case that produces an unbounded wait. Three clients were missing one or both, and the
  worst of them posts to subscriber-supplied URLs — see `WebhookSender`, whose javadoc records what
  that cost. Two exemptions are deliberate: `FrameStreamClient` reads an SSE stream that is
  *supposed* to stay open, and `ObjectStorageProxyClient` sets only the connect bound because its
  uploads can legitimately outlast any read timeout worth having.
- **A response is a response.** A 503 is the server answering and is recorded like any other
  status. Only a request that produced no response at all — connect timeout, refused connection,
  a body handler that threw — is a `WARN` with an `error` field and no `status`.
- **Logging never breaks a request.** A sink that throws is swallowed, and a failed request is
  recorded and then rethrown unchanged. Wrap the logger in `FailSafeLogger` if you want the sink's
  own failure reported somewhere.
- **No headers, no bodies, no query strings.** `Authorization` is on nearly every request in this
  repo, and tokens travel in query strings too (Apify's do), so the target is logged up to the
  first `?`. What is left identifies the endpoint, which is what you would aggregate on anyway.
- **A quieter level is available.** `wrap(client, logger, LogLevel.DEBUG)` for a poller that would
  otherwise write a line a second. A *failed* request stays `WARN` either way — choosing a quieter
  level for the ordinary case is not asking to lose the interesting one.
- **Nothing else changes.** `sendAsync` hands back the delegate's own future rather than one
  derived from `whenComplete`, so cancelling it still cancels the request. The consequence is that
  on the async path the record races the caller's continuation and can land just after it. That is
  the right way round: a log line is not worth delaying a response for.

### Who uses it

Every outbound client in the repo, which is the point — there is no second way to build one. The
JDK-client call sites wrap where the client is built; the two Spring `RestClient` ones
(`HttpAuthenticationStrategy`, `ObjectStorageProxyClient`) name `JdkClientHttpRequestFactory`
explicitly so the client underneath is the wrapped one, which is the same transport Spring would
have chosen for them anyway.

Three deliberate exceptions:

| Not wrapped | Why |
|---|---|
| `reverse-proxy`'s `UpstreamClient` | The endpoint already writes a record per proxied request, carrying whatever `Service` name the caller sent (in practice none today — see the adoption note at the top). Wrapping would log every hop twice. |
| `LokiLogger`'s own client | A sink logging its own pushes through itself is a loop that feeds itself. |
| `env-http`'s `EnvLoader` | It fetches configuration before a logger can exist — the one place `System.out` is still the reporting channel. |

Client libraries reached from CLIs (`SecretsManagerClient`, `HttpAuthenticationStrategy`,
`ClientAuthSession`, `ObjectStorageProxyClient`) keep a constructor that takes no `Logger` and
defaults to a `ConsoleLogger`, so a caller that has said nothing about logging still sees its
calls. Pass the process's own `Logger` wherever there is one.

### What it is not

- **Not a sink.** It writes through whatever `Logger` you hand it, so `LOGGER` still decides where
  the records go, exactly as for every other record the process writes.
- **Not for a logging sink's own transport.** `LokiLogger` pushes over HTTP; wrapping *its* client
  with a logger that leads back to it is a loop that feeds itself.
- **Not applied to WebSocket traffic.** `newWebSocketBuilder()` delegates untouched — a handshake
  and then frames is a different thing to log than a request.

## DelegatingHttpClient

Both wrappers extend it, and it is why each of them is short.

`HttpClient` is an abstract class with about fifteen methods, of which a decorator cares about
three — `send` and the two `sendAsync` overloads. The other dozen (`connectTimeout`,
`sslParameters`, `version`, the shutdown family, …) are pure pass-throughs that say nothing about
what the wrapper is for, and writing them out per wrapper is how this package came to hold ninety
duplicated lines. `DelegatingHttpClient` holds the delegate and implements all of them once.

```java
public final class RetryingHttpClient extends DelegatingHttpClient {
    private RetryingHttpClient(HttpClient delegate) {
        super(delegate);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        // ... the one behaviour this wrapper adds
    }
}
```

Two properties come with the base and should not be undone by a subclass: the wrapper owns
nothing, so `close()` closes the client it wraps and nothing else; and `newWebSocketBuilder()`
delegates untouched, so no wrapper's behaviour reaches a WebSocket — the handshake is built by the
returned builder, which no wrapper in the chain sees, and what follows it is frames rather than
requests.

It is also the right base for a test double that stands in for a real client, which is what both
of this package's `RecordingClient` stubs now use.

## Why not an interceptor

`java.net.http` has no interception point: no filter chain, no request customiser, nothing between
`send` and the wire. The only seam is the client itself, which is why this is a decorator over
`HttpClient` rather than configuration on one. It also means adoption is a one-line change at the
place the client is built, and no change at all where requests are made — which is where the
repo's ~15 `HttpClient` call sites live.

## Dependencies

The JDK, plus `dev.orwell:logger` for `LoggingHttpClient`. Nothing else, on purpose: desktop
clients (klippy, keeboarder) depend on this as readily as servers do, so it must not drag in
Spring — and `logger` does not, being the same hand-written interface those clients already build
a `ConsoleLogger` from in `main`.
