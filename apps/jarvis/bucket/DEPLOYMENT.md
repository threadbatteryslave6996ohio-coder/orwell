# Deployment Guide

This guide covers the Terraform deployment in `bucket/deployment/`.

## Files

- `main.tf`: infrastructure and instance wiring
- `setup.sh`: EC2 bootstrap
- `terraform.tfvars.example`: sample inputs

## Deploy

```bash
cd bucket/deployment
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform plan
terraform apply
```

The bootstrap script:

- installs Java 17, Maven, Nginx, Docker, and ffmpeg
- clones the repository from `source_repo_url`
- builds `bucket/pom.xml`
- installs the proxy, alerting, detection, and streaming jars
- writes systemd units for the Java services
- leaves only `record_stream.sh` and `analyze_stream.sh` as shell helpers

## Runtime Layout

- `s3-proxy.service`: `java -jar /opt/s3-proxy/publish/bucket-proxy.jar`
- `stream-alert.service`: `java -jar /opt/streaming/publish/alerting.jar`
- `stream-detection.service`: `java -jar /opt/streaming/publish/bucket-detection.jar`
- `stream-analyzer.service`: shell script wrapper for the streaming jar
- `stream-recorder.service`: shell script wrapper for ffmpeg recording
- `mediamtx.service`: Docker container

## Inputs

Set these in `terraform.tfvars`:

- `source_repo_url`
- `auth_server_base_url`
- `auth_identity_provisioning_key` (must match `AUTH_IDENTITY_PROVISIONING_KEY` on the auth server)
- `proxy_management_username`
- `proxy_management_password`
- `proxy_management_session_secret`
- `allowed_origins`
- `stream_analysis_endpoint`

## Verify

After `terraform apply`, check the proxy health endpoint and service logs:

```bash
curl http://$PROXY_IP/health
sudo journalctl -u s3-proxy -n 50
```

## Cleanup

```bash
terraform destroy
```
