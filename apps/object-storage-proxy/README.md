# Object Storage Proxy

Java upload proxy in front of an object-storage bucket. It speaks the S3 protocol against any
S3-compatible store (e.g. a self-hosted [MinIO](https://min.io)) or Azure Blob, so uploads never
need cloud-vendor credentials on the clients. The launcher loads configuration from `.env` and
applies nonblank shell overrides.

It is a standalone app: the jarvis recorder clients (`apps/jarvis/clients/`) and insta's HTTP
picture store upload through it, and it bundles the stream analysis worker described below.
Person detection lives in `apps/jarvis/person-detection` and alert delivery in `apps/alerting`.

## Build And Run

From the repository root:

```bash
mvn -pl apps/object-storage-proxy -am package
java -jar apps/object-storage-proxy/target/object-storage-proxy-0.1.0-SNAPSHOT-exec.jar
```

The proxy listens on port `5000` by default.

## Configuration

The main runtime settings are:

- `OBJECT_STORAGE_PROVIDER` to select `aws` (the S3-compatible adapter) or `azure`
- `OBJECT_STORAGE_MAX_FILE_SIZE` for the maximum upload size
- `OBJECT_STORAGE_S3_BUCKET_NAME`, `OBJECT_STORAGE_S3_REGION`, `OBJECT_STORAGE_S3_ENDPOINT`, and
  `OBJECT_STORAGE_S3_PATH_STYLE_ACCESS` for S3-compatible storage (point
  `OBJECT_STORAGE_S3_ENDPOINT` at your bucket service; the SDK reads the bucket
  credentials from `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`)
- `AZURE_STORAGE_ACCOUNT`, `AZURE_STORAGE_CONTAINER`,
  `AZURE_STORAGE_ENDPOINT`, and `AZURE_STORAGE_CONNECTION_STRING` for Azure
- `AUTH_BASE_URL` for token validation (the shared key from `AppServerEnv`; the proxy maps it
  to `object-storage.auth-server.base-url`)
- `AUTH_IDENTITY_PROVISIONING_KEY` for provisioning identities through the proxy
- `OBJECT_STORAGE_MANAGEMENT_USERNAME`, `OBJECT_STORAGE_MANAGEMENT_PASSWORD`, and
  `OBJECT_STORAGE_MANAGEMENT_SESSION_SECRET` for the management panel
- `OBJECT_STORAGE_CORS_ALLOWED_ORIGINS`, `OBJECT_STORAGE_LOGGING_AUDIT_FILE`, and
  `OBJECT_STORAGE_SERVER_URL` for HTTP/runtime behavior
- `STREAM_ANALYSIS_ENDPOINT` for the stream worker's frame destination (see below)
- `JARVIS_SERVER_ROUTE_PREFIX` to root the proxy routes under a prefix

Default values live in `ObjectStorageProxyEnvs`. The env keys are mapped onto Spring properties
under the `object-storage.` prefix.

## Endpoints

The proxy routes are rooted at `${jarvis.server.route-prefix:}`. By default the
routes are at the server root and include:

- `GET /health`
- `POST /login`
- `POST /upload`
- `POST /batch-upload`
- `GET /list/{folder}`
- `GET /metadata/{*key}`
- `DELETE /delete/{*key}`
- `GET /admin`
- `POST /admin/login`
- `POST /admin/logout`
- `POST /admin/users` (create an upload identity)

Upload and management requests require the auth server and the management
session respectively.

## Stream Analysis Worker

The stream ingest worker (`dev.orwell.objectstorage.proxy.streaming.AnalysisWorker`) is
a second mode of this same app rather than a separate application. It reads MJPEG
frames from stdin, extracts individual JPEGs, and POSTs each frame to
`STREAM_ANALYSIS_ENDPOINT` (blank drains the stream without forwarding). Its
shell wrappers live in `scripts/`:

It posts to **one** endpoint, so point it at exactly one of the jarvis services —
`/person-detection/detect`, `/motion/motion`, or `/hub/frames` — not several. Those
were all one service (`/detection/*`) before jarvis split into four; see
`apps/jarvis/README.md` if you are updating an existing value.

- `scripts/record_stream.sh` records segmented MP4 files
- `scripts/analyze_stream.sh` pipes sampled frames from ffmpeg into the worker

The worker shares the proxy's jar, entry point
(`dev.orwell.objectstorage.proxy.ObjectStorageProxyApplication`), and
env schema (`ObjectStorageProxyEnvs`). Run it by passing `--mode=stream-worker` to the
jar instead of booting the web server:

```bash
java -jar object-storage-proxy-0.1.0-SNAPSHOT-exec.jar --mode=stream-worker
```

## Java Client Utility

This module includes `dev.orwell.objectstorage.proxy.client.ObjectStorageProxyClient` for
programmatic access to the proxy API.

## Deployment

The proxy ships as a container built from [`docker/deployment/`](docker/deployment/) and is run
as the `object-storage-proxy` service in the repository-root
`docker-compose.all-services.yml`, behind the shared Nginx entrypoint under the `/jarvis` route
prefix. Point `OBJECT_STORAGE_S3_ENDPOINT` at your bucket service and set the storage
credentials. For local development against MinIO, use
[`scripts/local-stack.sh`](scripts/local-stack.sh) (see [LOCAL_TESTING.md](LOCAL_TESTING.md)).

## Tests

```bash
mvn -pl apps/object-storage-proxy -am test
```

## Production Systemd Service

The deployment script installs the runnable jar as
`/opt/s3-proxy/publish/object-storage-proxy.jar` and runs it with `java -jar` under a systemd
service named `s3-proxy`.

```bash
sudo systemctl status s3-proxy
sudo systemctl restart s3-proxy
sudo journalctl -u s3-proxy -f
```

By default it listens on `0.0.0.0:5000`; any public reverse proxy or TLS setup is managed
outside this repo. The audit log is written to `/var/log/s3-proxy/audit.log`.

The Linux recorder client and the syncer authenticate with credentials issued from `/admin`
through the auth server. In their `config.sh`:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="http://your-proxy-host"
PROXY_USERNAME="created-upload-user"
PROXY_PASSWORD="created-upload-user-password"
```

The mac/linux recorders are record-only; the `syncer` client
(`apps/jarvis/clients/syncer/`) drains their recordings through this proxy using
the `/login` + `/upload` flow above.

## Notes

- `/health` and `/login` do not require a bearer token.
- Upload, list, metadata, and delete endpoints require
  `Authorization: Bearer TOKEN` and `X-Client-Id`.
- The proxy validates tokens by calling the auth server `/tokens/check` endpoint.
- `/admin` uses the separate management credential from server config.
