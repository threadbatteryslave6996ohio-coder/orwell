resource "azurerm_postgresql_flexible_server" "clippy" {
  name                = "${var.name_prefix}-pg-${random_string.suffix.result}"
  resource_group_name = azurerm_resource_group.clippy.name
  location            = azurerm_resource_group.clippy.location

  version                = "16"
  administrator_login    = var.postgres_admin_username
  administrator_password = var.postgres_admin_password

  sku_name   = "B_Standard_B1ms"
  storage_mb = 32768

  backup_retention_days        = 7
  geo_redundant_backup_enabled = false

  public_network_access_enabled = true
}

resource "azurerm_postgresql_flexible_server_database" "clippy" {
  name      = var.postgres_database_name
  server_id = azurerm_postgresql_flexible_server.clippy.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "allowed_ips" {
  for_each = var.allowed_ip_addresses

  name             = "allow-${replace(each.key, ".", "-")}"
  server_id        = azurerm_postgresql_flexible_server.clippy.id
  start_ip_address = each.value
  end_ip_address   = each.value
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "allowed_ip_ranges" {
  for_each = var.allowed_ip_ranges

  name             = "allow-${each.key}"
  server_id        = azurerm_postgresql_flexible_server.clippy.id
  start_ip_address = each.value.start_ip_address
  end_ip_address   = each.value.end_ip_address
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "server_vm" {
  count = var.create_server_vm ? 1 : 0

  name             = "allow-server-vm"
  server_id        = azurerm_postgresql_flexible_server.clippy.id
  start_ip_address = azurerm_public_ip.server[0].ip_address
  end_ip_address   = azurerm_public_ip.server[0].ip_address
}
