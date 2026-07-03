# Clippy Auth Client

Java wrapper for the Clippy auth server HTTP API.

Use `ClippyAuthClient` when another Java module needs auth behavior without knowing endpoint paths or request/response DTO details.

```java
import dev.clippy.auth.api.LoginResponse;
import dev.clippy.auth.client.ClippyAuthClient;

ClippyAuthClient authClient = new ClippyAuthClient("http://localhost:8081");
LoginResponse login = authClient.login("dummy", "change-me-please");
boolean valid = authClient.isTokenValidForClient("dummy", login.token());
```

The shared request and response records are provided by the transitive
`auth-api` dependency.

## Build

```bash
mvn -pl auth/client package
```

## Install For Local Consumers

If another module in this repo depends on `dev.clippy:auth-client:0.1.0-SNAPSHOT` and you are building that module outside the auth reactor, install the auth client into your local Maven repository first:

```bash
mvn -pl auth/client install
```

After that, direct builds such as `mvn package` in `server/` or `clients/mac/` can resolve the auth client snapshot locally.
