# test-support

Test helpers with **no dependencies at all** — not Spring, not Testcontainers, not even JUnit.

That is the whole reason this module is separate from
[`server-test-support`](../server-test-support/README.md). Its consumers include desktop clients
whose test classpath is `junit-jupiter` and nothing else; anything declared here would land on
every one of them. A module takes whichever of the two it actually needs, and several take both.

## JsonStubServer

A real HTTP server on a loopback port, for a test whose subject is what a client puts on the wire:
the URL it builds, the headers it sets, the body it serializes, and what it makes of the answer. A
mocked `HttpClient` tests none of those.

```java
try (JsonStubServer auth = JsonStubServer.start()) {
    AtomicReference<String> sent = auth.stub("/def/login", 200, """
            {"clientId":"client-a","token":"token-456"}
            """);

    String token = new ClientAuthSession(auth.baseUrl(), "client-a", "secret", null).token();

    assertEquals("token-456", token);
    assertTrue(sent.get().contains("\"clientId\":\"client-a\""));
}
```

- `start()` binds port 0 on loopback, so nothing here can collide with the repo's local stack or
  with a parallel test.
- `on(path, handler)` for a handler that needs the method, the headers or the URI. Contexts may be
  registered after the server is running, which is what lets a test read arrange-then-act.
- `stub(path, status, body)` for the common case — answer with a fixed body, capture what was sent.
- `respond` and `readBody` are public statics, for use inside an `on(...)` handler.
- `baseUrl()` / `port()` to point the client under test at it.

`com.sun.net.httpserver` is in the JDK, which is what lets this carry no dependencies.

It replaced eighteen hand-rolled copies, in `auth-http-client`, `object-storage-proxy`,
`gmail-general`, `insta`, `klippy-offline-sync-client`, `klippy-client-core`, `jarvis-frame-client`,
`jarvis-person-detection`, `reverse-proxy`, `http-client`, `logger` and `server-bootstrap`. They had
already drifted — `.set` versus `.add` on the response header, `close()` versus try-with-resources,
and one that omitted `Content-Type` entirely, so a client that parses by content type behaved
differently depending on which stub it met. `respond` now always sets it.

Not every one wants `respond`: `FrameStreamClientTest` streams SSE and writes its own chunked body,
using only `start()`, `on(...)`, `baseUrl()` and `close()`. That is the intended way to use this for
a protocol it does not model.

A stub with real protocol semantics builds *on* this rather than replacing it: insta's
`ApifyStubServer` keeps its run/dataset/`OUTPUT` routing and delegates the socket, the base URL and
the JSON reply.
