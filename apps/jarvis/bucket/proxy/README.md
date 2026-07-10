# Jarvis Bucket Proxy

Java upload proxy for AWS S3-compatible or Azure Blob storage. The launcher
loads configuration from `.env` and applies nonblank shell overrides.

## Build And Run

From the repository root:

```bash
mvn -pl apps/jarvis/bucket/proxy -am package
java -jar apps/jarvis/bucket/proxy/target/bucket-proxy-0.1.0-SNAPSHOT-exec.jar
```

The proxy listens on port `5000` by default.

## Configuration

The main runtime settings are:

- `PROXY_STORAGE_PROVIDER` to select `aws` or `azure`
- `PROXY_STORAGE_MAX_FILE_SIZE` for the maximum upload size
- `PROXY_S3_BUCKET_NAME`, `PROXY_S3_REGION`, `PROXY_S3_ENDPOINT`,
  `PROXY_S3_PATH_STYLE_ACCESS`, and `PROXY_S3_SSE` for AWS storage
- `AZURE_STORAGE_ACCOUNT`, `AZURE_STORAGE_CONTAINER`,
  `AZURE_STORAGE_ENDPOINT`, and `AZURE_STORAGE_CONNECTION_STRING` for Azure
- `PROXY_AUTH_SERVER_BASE_URL` for token validation
- `AUTH_IDENTITY_PROVISIONING_KEY` for provisioning identities through the proxy
- `PROXY_MANAGEMENT_USERNAME`, `PROXY_MANAGEMENT_PASSWORD`, and
  `PROXY_MANAGEMENT_SESSION_SECRET` for the management panel
- `PROXY_CORS_ALLOWED_ORIGINS`, `PROXY_LOGGING_AUDIT_FILE`, and
  `PROXY_SERVER_URL` for HTTP/runtime behavior

Default values live in `JarvisProxyEnvs`.

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
- `GET /admin/users`

Upload and management requests require the auth server and the management
session respectively.

## Stream Analysis Worker

This module also contains the stream ingest worker
(`dev.orwell.bucket.streaming.AnalysisWorker`), previously the standalone
`bucket-streaming` module. It reads MJPEG frames from stdin, extracts individual
JPEGs, and POSTs each frame to `STREAM_ANALYSIS_ENDPOINT` (blank drains the
stream without forwarding). Its shell wrappers live in `scripts/`:

- `scripts/record_stream.sh` records segmented MP4 files
- `scripts/analyze_stream.sh` pipes sampled frames from ffmpeg into the worker

Because the worker ships inside the Spring Boot fat jar, `analyze_stream.sh`
launches its main class through the Boot loader:

```bash
java -cp bucket-proxy-0.1.0-SNAPSHOT-exec.jar \
  -Dloader.main=dev.orwell.bucket.streaming.StreamingApplication \
  org.springframework.boot.loader.launch.PropertiesLauncher
```

## Java Client Utility

This module includes `dev.orwell.bucket.proxy.client.BucketProxyClient` for
programmatic access to the proxy API.

## Tests

```bash
mvn -pl apps/jarvis/bucket/proxy -am test
```

## Production

The deployment script installs the runnable jar as
`/opt/s3-proxy/publish/bucket-proxy.jar` and runs it with `java -jar`.
