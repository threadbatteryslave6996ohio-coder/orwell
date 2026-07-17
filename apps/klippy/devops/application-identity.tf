data "azuread_client_config" "current" {}

resource "azuread_application" "applications" {
  display_name            = "applications"
  description             = "Shared application identity for Klippy storage access."
  prevent_duplicate_names = true
}

resource "azuread_service_principal" "applications" {
  client_id = azuread_application.applications.client_id
}

resource "azuread_application_password" "applications" {
  application_id = azuread_application.applications.id
  display_name   = "terraform-managed"
}

resource "azurerm_role_assignment" "applications_storage_blob_contributor" {
  scope                = azurerm_storage_account.klippy.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azuread_service_principal.applications.object_id
}
