# Starting the Bucket Proxy

This guide covers the current env-driven startup flow for the bucket proxy.

## Local Development

### 1. Install prerequisites

```bash
java -version
mvn -version
```

You need:

- JDK 25+
- Maven 3.9+
- AWS or Azure credentials if you are not using local test storage
- A running auth server, usually on `http://localhost:8081`

### 2. Configure the server

Create a `.env` file in `apps/jarvis/bucket/proxy/` or set shell variables.
The important settings are:

- `PROXY_STORAGE_PROVIDER=aws` or `azure`
- AWS settings: `PROXY_S3_BUCKET_NAME`, `PROXY_S3_REGION`,
  `PROXY_S3_ENDPOINT`, `PROXY_S3_PATH_STYLE_ACCESS`, `PROXY_S3_SSE`
- Azure settings: `AZURE_STORAGE_ACCOUNT`, `AZURE_STORAGE_CONTAINER`,
  `AZURE_STORAGE_ENDPOINT`, `AZURE_STORAGE_CONNECTION_STRING`
- Auth settings: `PROXY_AUTH_SERVER_BASE_URL`, `AUTH_IDENTITY_PROVISIONING_KEY`
- Management settings: `PROXY_MANAGEMENT_USERNAME`,
  `PROXY_MANAGEMENT_PASSWORD`, `PROXY_MANAGEMENT_SESSION_SECRET`

Example:

```dotenv
PROXY_STORAGE_PROVIDER=aws
PROXY_S3_BUCKET_NAME=your-bucket-name
PROXY_S3_REGION=us-east-1
PROXY_AUTH_SERVER_BASE_URL=http://localhost:8081
PROXY_MANAGEMENT_USERNAME=admin
PROXY_MANAGEMENT_PASSWORD=replace-with-a-long-admin-password
PROXY_MANAGEMENT_SESSION_SECRET=replace-with-a-long-random-session-secret
```

### 3. Start the server

From the repository root:

```bash
mvn -pl apps/jarvis/bucket/proxy -am package
java -jar apps/jarvis/bucket/proxy/target/jarvis-bucket-proxy-0.1.0-SNAPSHOT-exec.jar
```

The proxy listens on port `5000` by default.

### 4. Check health

```bash
curl http://localhost:5000/health
```

### 5. Create upload identities

Open:

```text
http://localhost:5000/admin
```

Sign in with `PROXY_MANAGEMENT_USERNAME` and `PROXY_MANAGEMENT_PASSWORD`, then
create auth-server identities for uploaders.

## Client Login Test

After creating an upload identity:

```bash
curl -H "Content-Type: application/json" \
  -d '{"username":"uploader","password":"upload-user-password"}' \
  http://localhost:5000/login
```

The response includes a bearer token that upload clients send with each request.

## Production Systemd Service

On the deployed EC2 host, the Terraform setup installs a systemd service named
`s3-proxy`.

```bash
sudo systemctl status s3-proxy
sudo systemctl restart s3-proxy
sudo journalctl -u s3-proxy -f
```

Production runs from `/opt/s3-proxy/publish/jarvis-bucket-proxy.jar`.

The audit log is written to `/var/log/s3-proxy/audit.log`.

## Client Settings

Linux and macOS recorder clients should use credentials issued from `/admin`
through the auth server:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="http://your-proxy-host"
PROXY_USERNAME="created-upload-user"
PROXY_PASSWORD="created-upload-user-password"
```

## Notes

- `/health` and `/login` do not require a bearer token.
- Upload, list, metadata, and delete endpoints require
  `Authorization: Bearer TOKEN` and `X-Client-Id`.
- The proxy validates tokens by calling the auth server `/tokens/check`
  endpoint.
- `/admin` uses the separate management credential from server config.
