# Local Testing with a Local Bucket

Run the full upload path — client → proxy → bucket — entirely on your machine,
with no AWS account and no remote auth server. A local [MinIO](https://min.io)
container stands in for S3, and the auth server runs against a local PostgreSQL
container.

Requires: Docker, JDK 17+, Maven, `curl`.

## Quick start

```bash
cd apps/jarvis/bucket/proxy

# 1. Start MinIO (S3) + PostgreSQL and create the bucket.
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

Tear down the containers (named volumes are kept) with:

```bash
scripts/local-stack.sh down
```

## How the proxy is pointed at the local bucket

The proxy's S3 client now supports an endpoint override. `local-stack.sh proxy`
starts it with:

| System property / env | Local value | Purpose |
| --- | --- | --- |
| `proxy.s3.endpoint` | `http://localhost:9000` | Point the S3 client at MinIO instead of AWS |
| `proxy.s3.path-style-access` | `true` | MinIO needs path-style bucket addressing |
| `proxy.s3.server-side-encryption` | *(empty)* | Disable SSE-S3 (a bare MinIO has no KMS) |
| `proxy.s3.bucket-name` | `keeboarder-recordings` | Local bucket name |
| `orwell.auth.base-url` | `http://localhost:8081` | Local auth server |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | `minioadmin` | MinIO root credentials |

All of these default to production-safe values when unset: no endpoint override
(real AWS), and `server-side-encryption: AES256`. See `application.yml`.

## Pointing the syncer / recorder clients at it

In a client `config.sh`:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="http://localhost:5000"
PROXY_USERNAME="tester"
PROXY_PASSWORD="testsecret"
```
