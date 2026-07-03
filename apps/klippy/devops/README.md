# Clippy Azure Infrastructure

Terraform for Azure infrastructure sized for Azure's 12-month free-account allowances where eligible:

- Azure Database for PostgreSQL Flexible Server: Burstable B1MS with 32 GB storage.
- Optional Azure Linux VM: `Standard_B2pts_v2` with Ubuntu 22.04 LTS Arm64.

Azure free eligibility depends on your subscription offer, region, capacity, usage, and current Microsoft terms. Check the Azure portal cost estimate before applying. VM disks, static public IPs, network egress, and usage beyond included allowances may still create charges.

The same Terraform can optionally create an Azure Linux VM as an EC2-style server host. The VM clones this repo, builds `server`, runs it with systemd, opens the configured server port, and persists logs under `/var/log/clippy`.

## Layout

- `main.tf`: provider setup, random suffix, and shared resource group.
- `application-identity.tf`: shared service principal and storage role assignment.
- `database.tf`: Azure PostgreSQL server, database, and firewall rules.
- `storage.tf`: Azure storage account and private blob container.
- `compute.tf`: optional server VM, networking, security group, and VM bootstrap wiring.
- `variables.tf`: module inputs.
- `outputs.tf`: module outputs.

## Deploy

```bash
cd ~/Desktop/clippy/devops
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` and set:

- `postgres_admin_password`
- `allowed_ip_addresses`

To also run the server on an Azure VM, set:

- `create_server_vm = true`
- `server_repo_url`
- `server_repo_ref`
- `vm_admin_ssh_public_key`
- `server_allowed_cidrs`
- `vm_ssh_allowed_cidrs`

Then deploy:

```bash
az login
terraform init
terraform plan -parallelism=1
terraform apply -parallelism=1
```

The serialized `-parallelism=1` run avoids Azure read-after-create/provider
consistency issues that can leave partially-created resources outside
Terraform state.

Use the outputs to configure the Spring Boot server:

```bash
export SPRING_DATASOURCE_URL="$(terraform output -raw spring_datasource_url)"
export SPRING_DATASOURCE_USERNAME="$(terraform output -raw spring_datasource_username)"
export SPRING_DATASOURCE_PASSWORD="<postgres_admin_password>"
```

If you need the storage resources, use:

```bash
terraform output -raw storage_account_name
terraform output -raw storage_container_name
```

For applications running outside the Azure VM, retrieve the shared service-principal
credentials as JSON:

```bash
terraform output -json application_credentials
```

This sensitive output contains `tenant_id`, `client_id`, `client_secret`,
`storage_account_name`, and `storage_container_name`. The secret is stored in Terraform
state; keep the state and command output private.

If `create_server_vm` is enabled, use:

```bash
terraform output -raw server_url
terraform output -raw server_ssh_command
```

The server VM has a system-assigned managed identity with `Storage Blob Data Contributor`
access to the storage account. After installing Azure CLI on the VM, authenticate without
storing credentials:

```bash
az login --identity
az storage blob list \
  --account-name clippyw70lug \
  --container-name clippy \
  --auth-mode login \
  --output table
```

The VM runs these services:

- `clippy-server.service`: pulls the repo during first boot, builds the Spring Boot server, and runs it.
- `clippy-log-keeper.service`: tails `/var/log/clippy/server.log` and writes a second persisted copy to `/var/log/clippy/server-kept.log`.

Server logs are written to `/var/log/clippy/server.log` on the VM. Logrotate keeps 14 daily compressed rotations for `/var/log/clippy/*.log`.

## Destroy

```bash
cd ~/Desktop/clippy/devops
terraform destroy
```
