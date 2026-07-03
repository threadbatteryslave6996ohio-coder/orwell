# Starting the S3 Proxy Server

This guide covers starting the proxy server and issuing upload identities through the external auth server.

## Local Development

### 1. Install prerequisites

```bash
java -version
mvn -version
aws sts get-caller-identity
```

You need:

- Java 17
- Maven
- AWS credentials with access to the target S3 bucket
- A running auth server, usually on `http://localhost:8081`

### 2. Configure the server

Edit `appsettings.json`:

```json
{
  "Logging": {
    "AuditFile": "logs/audit.log"
  },
  "AuthServer": {
    "BaseUrl": "http://localhost:8081"
  },
  "Management": {
    "Username": "admin",
    "Password": "replace-with-a-long-admin-password",
    "SessionSecret": "replace-with-a-long-random-session-secret"
  },
  "S3": {
    "BucketName": "your-bucket-name",
    "Region": "us-east-1"
  }
}
```

The management username/password are only for the proxy management panel. Upload clients must use identities created in the external auth server.

Start the auth server before using `/login` or `/admin` identity creation. Its default local URL is:

```text
http://localhost:8081
```

### 3. Start the server

From `bucket/proxy`:

```bash
mvn spring-boot:run
```

The server listens on `http://localhost:5000`.

### 4. Check health

```bash
curl http://localhost:5000/health
```

### 5. Create upload identities

Open:

```text
http://localhost:5000/admin
```

Sign in with `Management:Username` and `Management:Password`, then create auth-server client identities for uploaders.

## Client Login Test

After creating an upload identity:

```bash
curl -H "Content-Type: application/json" \
  -d '{"username":"uploader","password":"upload-user-password"}' \
  http://localhost:5000/login
```

The response includes a Bearer token that upload clients send with each request.

## Production systemd Service

On the deployed EC2 host, the Terraform setup installs a systemd service named `s3-proxy`.

```bash
sudo systemctl status s3-proxy
sudo systemctl restart s3-proxy
sudo journalctl -u s3-proxy -f
```

Production runs from `/opt/s3-proxy/publish/bucket-proxy.jar`.

The audit log is written to:

```text
/var/log/s3-proxy/audit.log
```

## Required Client Settings

Linux clients should use credentials issued from `/admin` through the auth server:

```bash
UPLOAD_MODE="proxy"
PROXY_URL="http://your-proxy-host"
PROXY_USERNAME="created-upload-user"
PROXY_PASSWORD="created-upload-user-password"
```

## Notes

- `/health` and `/login` do not require a Bearer token.
- Upload, list, metadata, and delete endpoints require `Authorization: Bearer TOKEN` and `X-Client-Id`.
- The proxy validates tokens by calling the auth server `/tokens/check` endpoint.
- `/admin` uses the separate management credential from server config.
