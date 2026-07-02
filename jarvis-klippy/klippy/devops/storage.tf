resource "azurerm_storage_account" "clippy" {
  name = substr("${replace(var.name_prefix, "-", "")}${random_string.suffix.result}", 0, 24)

  resource_group_name      = azurerm_resource_group.clippy.name
  location                 = azurerm_resource_group.clippy.location
  account_tier             = "Standard"
  account_replication_type = "LRS"

  allow_nested_items_to_be_public = false
  min_tls_version                 = "TLS1_2"
}

resource "azurerm_storage_container" "clippy" {
  name                  = var.storage_container_name
  storage_account_id    = azurerm_storage_account.clippy.id
  container_access_type = "private"
}

resource "azurerm_role_assignment" "server_storage_blob_contributor" {
  count = var.create_server_vm ? 1 : 0

  scope                = azurerm_storage_account.clippy.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_linux_virtual_machine.server[0].identity[0].principal_id
}
