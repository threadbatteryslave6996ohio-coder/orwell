# Local Testing with a Local Bucket

Run the full upload path — client → proxy → bucket — entirely on your machine,
with no AWS account and no remote auth server. A local [MinIO](https://min.io)
container stands in for S3, and the auth server runs against the monorepo's single
shared PostgreSQL — the `db` service in `docker-compose.all-services.yml`, not a
container private to this app.

Requires: Docker, JDK 25+, Maven, `curl`.

## Quick start

```bash
cd apps/jarvis/bucket/proxy

# 1. Start MinIO (S3), bring up the shared `db` service, and create the bucket.
#    Note: `up` touches the monorepo-wide stack — it runs `docker compose -f
#    docker-compose.all-services.yml up -d db`, the same Postgres every other app uses.
scripts/local-stack.sh up

# 2. In one terminal, run the auth server (builds the jar on first run).
scripts/local-stack.sh auth

# 3. In another terminal, run the proxy pointed at MinIO.
scripts/local-stack.sh proxy

# 4. Create an upload identity (secret must be >= 8 chars).
scripts/local-stack.sh identity tester testsecret

# 5. Check everything is up.
scripts/local-stack.sh status
```

Then exercise the proxy:

```bash
# Login through the proxy (proxy validates against the auth server).
curl -s -H 'Content-Type: application/json' \
  -d '{"username":"tester","password":"testsecret"}' \
  http://localhost:5000/login
# -> {"clientId":"tester","token":"...","success":true,"tokenType":"Bearer"}

# Upload a file with the returned token.
TOKEN=... # from the login response
curl -s -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: tester" \
  -F "file=@/path/to/some.mp4" -F "folder=recordings/test/screen" \
  http://localhost:5000/upload
```

Inspect stored objects in MinIO:

```bash
docker run --rm --network=host --entrypoint sh minio/mc -c \
  "mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null; \
   mc ls -r local/keeboarder-recordings"
```

The MinIO web console is at http://localhost:9001 (minioadmin / minioadmin).

Tear down with:

```bash
scripts/local-stack.sh down
```

`down` removes only the containers this script started (MinIO; named volumes are kept). It
deliberately leaves the shared Postgres running, since other apps depend on it — stop that with
`docker compose -f docker-compose.all-services.yml stop db` from the repo root if you really
want it down.

## How the proxy is pointed at the local bucket

The proxy's S3 client supports an endpoint override. `local-stack.sh proxy` starts it pointed at
MinIO on `http://localhost:9000` with path-style addressing, the `keeboarder-recordings` bucket,
`minioadmin`/`minioadmin` as the S3 credentials, and `AUTH_BASE_URL=http://localhost:8081` for
the local auth server.

Point `PROXY_S3_ENDPOINT` at whichever S3-compatible bucket service you run; there is no default
remote endpoint. See `application.yml`.

## Pointing the syncer / recorder clients at it

In a client `config.sh`:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="http://localhost:5000"
PROXY_USERNAME="tester"
PROXY_PASSWORD="testsecret"
```
