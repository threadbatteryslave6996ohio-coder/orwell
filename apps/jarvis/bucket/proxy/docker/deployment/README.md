# Proxy Docker deployment

This deployment builds only the bucket proxy and its required Maven modules
from the monorepo root. It does not deploy the external auth server or an
object-storage service.

Initialize the repository submodules before building:

```bash
git submodule update --init --recursive
```

## Configure and run

From this directory:

```bash
cp .env.example .env
# Fill in the storage, auth, and management settings in .env.
docker compose up --build -d
docker compose ps
curl http://localhost:5000/jarvis/health
```

For an auth server running on the Docker host, keep
`AUTH_BASE_URL=http://host.docker.internal:8081`. If the auth server is on a
shared Docker network, use its service name instead, for example
`AUTH_BASE_URL=http://auth-server/auth`.

## Logs

Spring Boot application output is written to the container's stdout/stderr:

```bash
docker compose logs -f proxy
docker inspect --format '{{.LogPath}}' "$(docker compose ps -q proxy)"
```

The host-side application-log location is controlled by Docker's configured
logging driver; the inspect command shows the path when that driver exposes
one.

Auth-server audit events are JSON Lines in `/app/logs/audit.log`. The directory
is persisted in the `jarvis-proxy-audit-logs` Docker volume:

```bash
docker compose exec proxy tail -f /app/logs/audit.log
docker volume inspect jarvis-proxy-audit-logs
```

The audit file currently records calls to the external auth server (login,
token validation, and identity creation). It is not an HTTP access log and
does not record successful upload, list, metadata, or delete operations.

## Stop

```bash
docker compose down
```

Add `--volumes` only when the persisted audit log should also be deleted.
