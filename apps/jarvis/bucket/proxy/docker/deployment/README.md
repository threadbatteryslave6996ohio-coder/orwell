# Proxy Docker deployment

This directory holds the bucket proxy's `Dockerfile` and its `.env.example`. The
proxy is built and run as part of the whole-stack compose file at the repository
root (service `jarvis-proxy`), which also runs the auth server the proxy depends
on and the shared Nginx entrypoint. There is no longer a standalone compose stack
for this app.

## Configure and run

`.env.example` holds the committed non-secret defaults. Real credentials —
`AWS_SECRET_ACCESS_KEY`, `AZURE_CLIENT_SECRET`, `PROXY_MANAGEMENT_PASSWORD`,
`PROXY_MANAGEMENT_SESSION_SECRET` — belong in a `.env` beside it, which is
gitignored. `docker-compose.all-services.yml` reads both and lets `.env` win, so
copy the example once and edit the copy:

```bash
cp apps/jarvis/bucket/proxy/docker/deployment/.env.example \
   apps/jarvis/bucket/proxy/docker/deployment/.env
```

Never put a real secret in `.env.example` itself: `.gitignore` deliberately
un-ignores it, so anything you write there is committed. The `.env` override is
optional — the stack starts on the example defaults without one. Then, from the
repository root:

```bash
docker compose -f docker-compose.all-services.yml up --build -d jarvis-proxy nginx
docker compose -f docker-compose.all-services.yml ps
curl http://localhost:8080/jarvis/health
```

`nginx` has to be named explicitly. It is what publishes port 8080, and the
dependency runs from `nginx` to `jarvis-proxy` rather than the other way, so
starting the proxy alone leaves nothing listening on 8080 and the `curl` above
fails with connection refused.

The proxy is reachable through the shared Nginx entrypoint (port 8080 by default,
overridable with `ORWELL_HTTP_PORT`) under the `/jarvis` route prefix. The compose
file wires `AUTH_BASE_URL` to the in-stack `auth-server` service, so no host-side
auth configuration is needed.

## Logs

Spring Boot application output is written to the container's stdout/stderr:

```bash
docker compose -f docker-compose.all-services.yml logs -f jarvis-proxy
```

Auth-server audit events are JSON Lines in `/app/logs/audit.log`. The directory
is persisted in the `all-services-proxy-audit-logs` Docker volume:

```bash
docker compose -f docker-compose.all-services.yml exec jarvis-proxy tail -f /app/logs/audit.log
docker volume inspect orwell_all-services-proxy-audit-logs
```

Compose prefixes volume names with the project name, which defaults to the
repository directory (`orwell`); use your own prefix if you run the stack with
`-p`.

The audit file currently records calls to the external auth server (login,
token validation, and identity creation). It is not an HTTP access log and
does not record successful upload, list, metadata, or delete operations.

## Stop

```bash
docker compose -f docker-compose.all-services.yml down
```

Add `--volumes` only when the persisted audit log should also be deleted.
