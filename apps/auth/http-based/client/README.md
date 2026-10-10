# Klippy HTTP Auth Client

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

## Workspaces

A strategy talks to one workspace, named at construction and used as the first path segment of
every call. The constructors that omit it use `def`, which is where identities created before
workspaces existed live — so existing call sites need no change.

```java
HttpAuthenticationStrategy team = new HttpAuthenticationStrategy("http://localhost:8081", "team-a");
HttpAuthenticationStrategy admins = new HttpAuthenticationStrategy("http://localhost:8081", "admin");

// Provisioning needs an admin's credential; the identity lands in `team`'s workspace.
String adminToken = admins.login("ops-admin", "change-me-please").token();
team.createIdentity("ops-admin", adminToken, "dummy", "change-me-please");
```

`ClientAuthSession` takes a workspace the same way, and holds the token for a caller that needs
one repeatedly — it logs in again when the server stops accepting it.

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
