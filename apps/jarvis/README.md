# Jarvis

Surveillance services (bucket proxy, alerting) and macOS/Linux recorder
clients. Bucket services are under `bucket/`, recorder clients under `clients/`.
The bucket proxy also bundles the stream analysis worker and its ingest scripts
(see `bucket/proxy/`).

Authentication is supplied by `apps/auth`. Build all services from the repository root:

```bash
mvn -pl apps/jarvis -am package
```

The recorder clients under `clients/` remain standalone shell applications.

The log analyzer is now a standalone app; see `apps/log-analyzer`.

### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the bucket proxy. It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash apps/jarvis/clients/syncer/syncer.sh
```

Configure proxy credentials in `apps/jarvis/clients/syncer/config.sh`.
