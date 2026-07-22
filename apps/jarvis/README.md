# Jarvis

Surveillance services (bucket proxy, detection) and macOS/Linux recorder
clients. Bucket services are under `bucket/`, recorder clients under `clients/`.
Alert delivery is no longer part of jarvis; it is a standalone app at `apps/alerting`.
The bucket proxy also bundles the stream analysis worker and its ingest scripts
(see `bucket/proxy/`).

Authentication is supplied by `apps/auth`. Build all services from the repository root:

```bash
mvn -pl apps/jarvis -am package
```

The recorder clients under `clients/` remain standalone shell applications.

The log analyzer is now a standalone app; see `apps/log-analyzer`.

The detection service supports `SERVER_ENGINE=undertow` for the lightweight
runtime and `SERVER_ENGINE=spring` for Spring Boot/Tomcat. Both expose the same
`GET /health` and `POST /detect` endpoints.
The Undertow adapter limits detection bodies to 16 MiB and returns a JSON
`413` response with `request body too large` when that limit is exceeded.

### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the bucket proxy. It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash apps/jarvis/clients/syncer/syncer.sh
```

Configure proxy credentials in `apps/jarvis/clients/syncer/config.sh`.
