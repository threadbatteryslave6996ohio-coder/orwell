# Klippy HTTP Auth API

Shared Java request and response records for the Klippy authentication HTTP API.
This module contains transport contracts only: it has no HTTP client, Spring
controller, persistence, or credential-processing logic.

Both `http-based/client` and `http-based/server` depend on this module so login and token
validation payloads cannot drift between producers and consumers.

Build and test it with its consumers from the repository root:

```bash
mvn -pl apps/auth/http-based/api,apps/auth/http-based/client,apps/auth/http-based/server -am test
```

## Records

- `LoginHttpRequest` / `LoginHttpResponse` — login credentials and token result
- `CheckTokenHttpRequest` / `CheckTokenHttpResponse` — token validation request and result

See `src/main/java/dev/orwell/auth/http/api/` for the full definition of each record.
