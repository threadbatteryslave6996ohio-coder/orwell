# Jarvis

Surveillance services (bucket proxy, streaming, alerting) and macOS/Linux recorder
clients. Bucket services are under `apps/jarvis/bucket/`, recorder clients under
`apps/jarvis/clients/`.

Authentication is supplied by `apps/auth`. Build all services from the repository root:

```bash
mvn -pl apps/jarvis -am package
```

The recorder clients under `clients/` remain standalone shell applications.

### Syncer (`clients/syncer`)

The syncer drains completed recording segments to the bucket proxy. It merges older completed segments (via ffmpeg concat) and uploads the result, leaving the current in-progress segment untouched. Run it on a timer:

```bash
bash clients/syncer/syncer.sh
```

Configure proxy credentials in `clients/syncer/config.sh`.
