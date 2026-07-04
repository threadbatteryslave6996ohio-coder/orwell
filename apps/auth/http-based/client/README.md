# Clippy HTTP Auth Client

HTTP implementation of the shared `AuthenticationStrategy`, plus login support.

Use `HttpAuthenticationStrategy` when another Java module needs auth behavior
through the auth server without knowing endpoint paths or wire DTO details.

```java
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;

HttpAuthenticationStrategy auth = new HttpAuthenticationStrategy("http://localhost:8081");
LoginHttpResponse login = auth.login("dummy", "change-me-please");
boolean valid = auth.isTokenValidForClient("dummy", login.token());
```

The shared request and response records are provided by the transitive
`auth-http-api` dependency.

## Build

```bash
mvn -pl apps/auth/http-based/client -am package
```

## Install For Local Consumers

If another module depends on `dev.orwell:auth-http-client:0.1.0-SNAPSHOT`
and is built outside the reactor, install the HTTP client first:

```bash
mvn -pl apps/auth/http-based/client -am install
```

After that, direct module builds can resolve the HTTP client snapshot locally.
