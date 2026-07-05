# AWS S3 Private Bucket

This project deploys a private AWS S3 bucket plus a Java-based EC2 proxy and Java stream services. The only shell-based pieces are the stream helper scripts and the deployment bootstrap.

## Components

- `deployment/`: Terraform and EC2 bootstrap
- `proxy/`: Java S3 upload proxy
- `alerting/`: Java alert server (includes person detection)
- `streaming/`: Java stream worker plus shell scripts

## Runtime

- `proxy`: Spring Boot service packaged as a jar
- `alerting`: executable jar (includes detection)
- `streaming`: executable jar invoked by `analyze_stream.sh`
- `record_stream.sh` and `analyze_stream.sh` remain shell scripts

## Deployment

1. Copy `deployment/terraform.tfvars.example` to `deployment/terraform.tfvars`
2. Set `source_repo_url` and the other deployment variables
3. Run `terraform init`, `terraform plan`, and `terraform apply` from `deployment/`

The EC2 user-data script installs Java 25, Maven, Nginx, Docker, and ffmpeg, then builds `bucket/pom.xml` and installs the generated jars.

## Proxy API

The proxy exposes the upload and management endpoints on port 80 through Nginx.
See [proxy/README.md](proxy/README.md) for the endpoint list and local run instructions.

## Stream Flow

MediaMTX receives RTMP input, the recorder writes segmented MP4 files, and the analyzer forwards sampled frames to the Java detection and alert services.

## Documentation

- [deployment/README.md](deployment/README.md)
- [deployment/README.md](deployment/README.md)
- [proxy/README.md](proxy/README.md)
- [alerting/README.md](alerting/README.md)
- [streaming/README.md](streaming/README.md)
