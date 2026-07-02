# Clippy Auth API

Shared Java request and response records for the Clippy authentication HTTP API.
This module contains transport contracts only: it has no HTTP client, Spring
controller, persistence, or credential-processing logic.

Both `auth/client` and `auth/server` depend on this module so login and token
validation payloads cannot drift between producers and consumers.

Build and test it with its consumers from the repository root:

```bash
mvn -pl auth/api,auth/client,auth/server -am test
```
