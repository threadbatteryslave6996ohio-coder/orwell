output "postgres_host" {
  description = "PostgreSQL server hostname."
  value       = azurerm_postgresql_flexible_server.clippy.fqdn
}

output "spring_datasource_url" {
  description = "JDBC URL for the Clippy Spring Boot server."
  value       = "jdbc:postgresql://${azurerm_postgresql_flexible_server.clippy.fqdn}:5432/${azurerm_postgresql_flexible_server_database.clippy.name}?sslmode=require"
}

output "spring_datasource_username" {
  description = "Username for SPRING_DATASOURCE_USERNAME."
  value       = var.postgres_admin_username
}

output "storage_account_name" {
  description = "Storage account created for the Clippy bucket-equivalent."
  value       = azurerm_storage_account.clippy.name
}

output "storage_container_name" {
  description = "Private blob container created in the storage account."
  value       = azurerm_storage_container.clippy.name
}

output "server_managed_identity_principal_id" {
  description = "Principal ID of the optional server VM's system-assigned managed identity."
  value       = var.create_server_vm ? azurerm_linux_virtual_machine.server[0].identity[0].principal_id : null
}

output "application_credentials" {
  description = "Credentials and storage settings for the applications service principal."
  sensitive   = true
  value = {
    tenant_id              = data.azuread_client_config.current.tenant_id
    client_id              = azuread_application.applications.client_id
    client_secret          = azuread_application_password.applications.value
    storage_account_name   = azurerm_storage_account.clippy.name
    storage_container_name = azurerm_storage_container.clippy.name
  }
}

output "server_vm_public_ip" {
  description = "Public IP for the optional Clippy server VM."
  value       = var.create_server_vm ? azurerm_public_ip.server[0].ip_address : null
}

output "server_url" {
  description = "HTTP URL for the optional Clippy server VM."
  value       = var.create_server_vm ? "http://${azurerm_public_ip.server[0].ip_address}:${var.server_port}" : null
}

output "server_ssh_command" {
  description = "SSH command for the optional Clippy server VM."
  value       = var.create_server_vm ? "ssh ${var.vm_admin_username}@${azurerm_public_ip.server[0].ip_address}" : null
}
