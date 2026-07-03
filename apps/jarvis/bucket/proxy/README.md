# Object Storage Upload Proxy Server

This module contains the Java proxy that fronts private AWS S3-compatible or
Azure Blob storage.

## Contents

- `src/main/java/dev/clippy/bucket/proxy/`: Java source
- `src/main/java/dev/clippy/bucket/proxy/client/`: reusable Java client for the proxy HTTP API
- `src/main/resources/application.yml`: configuration
- `pom.xml`: Maven build

## Local Run

```bash
java -version
mvn test
mvn spring-boot:run
```

The service listens on `http://localhost:5000` by default.

## Endpoints

- `GET /health`
- `POST /login`
- `POST /upload`
- `POST /batch-upload`
- `GET /list/{folder}`
- `GET /metadata/{*key}`
- `DELETE /delete/{*key}`

## Java Client Utility

This module now includes a reusable Java client for sending requests to the proxy:

- `dev.clippy.bucket.proxy.client.BucketProxyClient`

It covers:

- `health()`
- `login(username, password)`
- `upload(clientId, bearerToken, file, folder, fileName)`
- `batchUpload(clientId, bearerToken, files, folder)`
- `list(clientId, bearerToken, folder)`
- `metadata(clientId, bearerToken, key)`
- `delete(clientId, bearerToken, key)`

## Configuration

The controller depends only on `storage.BucketStorage`. Set
`PROXY_STORAGE_PROVIDER` to select one of the adapters:

- `aws` (default): `storage.aws.AwsS3StorageAdapter`
- `azure`: `storage.azure.AzureBlobStorageAdapter`

AWS configuration:

```bash
export PROXY_STORAGE_PROVIDER=aws
export AWS_ACCESS_KEY_ID=...
export AWS_SECRET_ACCESS_KEY=...
export PROXY_S3_ENDPOINT=...                 # optional S3-compatible endpoint
export PROXY_S3_PATH_STYLE_ACCESS=false
export PROXY_S3_SSE=AES256
```

Set `proxy.s3.bucket-name` and `proxy.s3.region` in configuration or with
equivalent Spring property overrides.

Azure configuration:

```bash
export PROXY_STORAGE_PROVIDER=azure
export AZURE_STORAGE_ACCOUNT=...
export AZURE_STORAGE_CONTAINER=...
export AZURE_STORAGE_CONNECTION_STRING=...  # optional
```

When `AZURE_STORAGE_CONNECTION_STRING` is blank, the Azure adapter uses
`DefaultAzureCredential`, which supports managed identity, environment
credentials, Azure CLI login, and the other standard credential sources.

## Build

```bash
mvn test
mvn package
```

The deployment script installs the built jar as `/opt/s3-proxy/publish/bucket-proxy.jar` and runs it with `java -jar`.

## Production Notes

- Management sign-in is separate from upload authentication
- The proxy validates tokens against the auth server
- Nginx forwards traffic to port `5000`
