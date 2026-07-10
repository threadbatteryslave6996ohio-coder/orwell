# S3 Bucket & Proxy Server Deployment

This folder contains the Terraform infrastructure code to deploy:
- A private AWS S3 bucket
- An EC2 instance running the proxy server
- An optional live stream ingest stack that records, detects people, and analyzes incoming video separately
- VPC, networking, security groups, and IAM roles

## Files

- **main.tf**: Terraform configuration for S3 bucket and EC2 instance
- **setup.sh**: EC2 user data script for initial server setup
- **terraform.tfvars.example**: Example variable values
- **.gitignore**: Git ignore for Terraform state files

## Quick Start

```bash
# Copy and customize the variables
cp terraform.tfvars.example terraform.tfvars
nano terraform.tfvars

# Initialize Terraform
terraform init

# Plan and apply
terraform plan
terraform apply
```

## Configuration

Edit `terraform.tfvars`:

```hcl
aws_region                      = "us-east-1"
bucket_name                     = "my-bucket-name"
proxy_instance_type             = "t3.micro"
environment                     = "production"
enable_versioning               = true
enable_encryption               = true
auth_server_base_url            = "http://localhost:8081"
proxy_management_username       = "admin"
proxy_management_password       = "replace-with-a-long-random-admin-password"
proxy_management_session_secret = "replace-with-a-different-long-random-session-secret"
allowed_origins                 = []
ssh_cidr_blocks                 = []
stream_cidr_blocks              = ["0.0.0.0/0"]
stream_analysis_endpoint        = ""
```

Admins sign in at `/admin` with `proxy_management_username` and `proxy_management_password` to create upload identities in the external auth server. Uploaders call `/login` with those issued credentials, receive a Bearer token from the auth server, and send that token plus `X-Client-Id` to protected endpoints. `ssh_cidr_blocks` defaults to an empty list, which disables SSH ingress.

The same EC2 instance also runs a MediaMTX ingest container on port `1935`. Clients publish to `rtmp://PROXY_IP:1935/live`, the recorder stores segmented files locally under `/var/lib/streaming/recordings`, and the analyzer samples frames and posts them to the configured webhook.

Person detection happens in the Python detection service. When a person is detected, it forwards the alert to the alert service, and the alert service can optionally send email through SMTP.

After deployment, open `http://$PROXY_IP/admin`, sign in as the management user, and create the upload identity credentials that Linux clients should place in `PROXY_USERNAME` and `PROXY_PASSWORD`.

## Deploying the Proxy Application

Terraform deploys and starts the proxy application through EC2 user data. After `terraform apply`, verify it from the instance or through the proxy IP:

```bash
PROXY_IP=$(terraform output -raw proxy_server_public_ip)
curl http://$PROXY_IP/health
TOKEN=$(curl -s -H "Content-Type: application/json" \
  -d "{\"username\":\"$PROXY_USERNAME\",\"password\":\"$PROXY_PASSWORD\"}" \
  http://$PROXY_IP/login | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
curl -H "Authorization: Bearer $TOKEN" -H "X-Client-Id: $PROXY_USERNAME" http://$PROXY_IP/list/uploads
```

## Outputs

After `terraform apply`, you'll get:

- `bucket_name`: S3 bucket name
- `bucket_arn`: S3 bucket ARN
- `proxy_server_id`: EC2 instance ID
- `proxy_server_public_ip`: IP to access the proxy
- `proxy_server_private_ip`: Private IP within VPC
- `vpc_id`: VPC ID

## Cleanup

```bash
terraform destroy
```

## See Also

- [../proxy/README.md](../proxy/README.md) - Proxy application documentation
- [../streaming/README.md](../streaming/README.md) - Stream ingest, recording, and analysis docs
- [../../../alerting/README.md](../../../alerting/README.md) - Alert delivery service docs (now a standalone app)
- [../README.md](../README.md) - Main project README
