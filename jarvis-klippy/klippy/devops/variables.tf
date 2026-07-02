variable "name_prefix" {
  description = "Prefix for Azure resource names. Keep it short and lowercase."
  type        = string
  default     = "clippy"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}[a-z0-9]$", var.name_prefix))
    error_message = "name_prefix must be 3-22 lowercase letters, numbers, or hyphens, start with a letter, and end with a letter or number."
  }
}

variable "location" {
  description = "Azure region for the PostgreSQL Flexible Server."
  type        = string
  default     = "centralus"
}

variable "postgres_admin_username" {
  description = "Administrator username for PostgreSQL."
  type        = string
  default     = "clippyadmin"
}

variable "postgres_admin_password" {
  description = "Administrator password for PostgreSQL."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.postgres_admin_password) >= 12
    error_message = "postgres_admin_password must be at least 12 characters."
  }
}

variable "postgres_database_name" {
  description = "Database name for the Clippy server."
  type        = string
  default     = "clippy"
}

variable "storage_container_name" {
  description = "Blob container name for the Clippy storage account."
  type        = string
  default     = "clippy"

  validation {
    condition = (
      length(var.storage_container_name) >= 3 &&
      length(var.storage_container_name) <= 63 &&
      can(regex("^[a-z0-9][a-z0-9-]*[a-z0-9]$", var.storage_container_name)) &&
      !strcontains(var.storage_container_name, "--")
    )

    error_message = "storage_container_name must be 3-63 characters of lowercase letters, numbers, or hyphens, start and end with a letter or number, and not contain consecutive hyphens."
  }
}

variable "allowed_ip_addresses" {
  description = "Public IPv4 addresses allowed to connect to PostgreSQL."
  type        = set(string)
  default     = []
}

variable "allowed_ip_ranges" {
  description = "Named public IPv4 ranges allowed to connect to PostgreSQL."
  type = map(object({
    start_ip_address = string
    end_ip_address   = string
  }))
  default = {}
}

variable "create_server_vm" {
  description = "Create an Azure Linux VM that clones this repo, builds the server, exposes SERVER_PORT, and persists server logs."
  type        = bool
  default     = false
}

variable "server_repo_url" {
  description = "Git URL the server VM clones."
  type        = string
  default     = "https://github.com/replace-me/clippy.git"
}

variable "server_repo_ref" {
  description = "Git branch, tag, or commit checked out by the server VM."
  type        = string
  default     = "main"
}

variable "server_port" {
  description = "Port exposed by the Spring Boot server on the VM."
  type        = number
  default     = 8080
}

variable "server_allowed_cidrs" {
  description = "CIDR ranges allowed to connect to the Spring Boot server port."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "vm_ssh_allowed_cidrs" {
  description = "CIDR ranges allowed to SSH into the server VM."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "vm_admin_username" {
  description = "Admin username for the Azure Linux server VM."
  type        = string
  default     = "azureuser"
}

variable "vm_admin_ssh_public_key" {
  description = "SSH public key for the Azure Linux server VM. Required when create_server_vm is true."
  type        = string
  default     = ""
}

variable "vm_size" {
  description = "Azure VM size for the Clippy server. Standard_B2pts_v2 matches Azure's current 12-month free Linux VM allowance where eligible."
  type        = string
  default     = "Standard_B2pts_v2"
}

variable "vm_vnet_address_space" {
  description = "Address space for the optional server VM virtual network."
  type        = string
  default     = "10.40.0.0/16"
}

variable "vm_subnet_address_prefix" {
  description = "Subnet CIDR for the optional server VM."
  type        = string
  default     = "10.40.1.0/24"
}
