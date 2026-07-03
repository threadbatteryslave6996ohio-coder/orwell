resource "azurerm_virtual_network" "clippy" {
  count = var.create_server_vm ? 1 : 0

  name                = "${var.name_prefix}-vnet"
  address_space       = [var.vm_vnet_address_space]
  location            = azurerm_resource_group.clippy.location
  resource_group_name = azurerm_resource_group.clippy.name
}

resource "azurerm_subnet" "server" {
  count = var.create_server_vm ? 1 : 0

  name                 = "${var.name_prefix}-server-subnet"
  resource_group_name  = azurerm_resource_group.clippy.name
  virtual_network_name = azurerm_virtual_network.clippy[0].name
  address_prefixes     = [var.vm_subnet_address_prefix]
}

resource "azurerm_public_ip" "server" {
  count = var.create_server_vm ? 1 : 0

  name                = "${var.name_prefix}-server-pip"
  location            = azurerm_resource_group.clippy.location
  resource_group_name = azurerm_resource_group.clippy.name
  allocation_method   = "Static"
  sku                 = "Standard"
}

resource "azurerm_network_security_group" "server" {
  count = var.create_server_vm ? 1 : 0

  name                = "${var.name_prefix}-server-nsg"
  location            = azurerm_resource_group.clippy.location
  resource_group_name = azurerm_resource_group.clippy.name

  security_rule {
    name                       = "ssh"
    priority                   = 100
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefixes    = var.vm_ssh_allowed_cidrs
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "clippy-server"
    priority                   = 110
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = tostring(var.server_port)
    source_address_prefixes    = var.server_allowed_cidrs
    destination_address_prefix = "*"
  }
}

resource "azurerm_network_interface" "server" {
  count = var.create_server_vm ? 1 : 0

  name                = "${var.name_prefix}-server-nic"
  location            = azurerm_resource_group.clippy.location
  resource_group_name = azurerm_resource_group.clippy.name

  ip_configuration {
    name                          = "primary"
    subnet_id                     = azurerm_subnet.server[0].id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.server[0].id
  }
}

resource "azurerm_network_interface_security_group_association" "server" {
  count = var.create_server_vm ? 1 : 0

  network_interface_id      = azurerm_network_interface.server[0].id
  network_security_group_id = azurerm_network_security_group.server[0].id
}

resource "azurerm_linux_virtual_machine" "server" {
  count = var.create_server_vm ? 1 : 0

  name                  = "${var.name_prefix}-server"
  location              = azurerm_resource_group.clippy.location
  resource_group_name   = azurerm_resource_group.clippy.name
  network_interface_ids = [azurerm_network_interface.server[0].id]
  size                  = var.vm_size
  admin_username        = var.vm_admin_username
  custom_data = base64encode(templatefile("${path.module}/cloud-init-clippy-server.yml.tftpl", {
    repo_url_b64               = base64encode(var.server_repo_url)
    repo_ref_b64               = base64encode(var.server_repo_ref)
    server_port                = var.server_port
    spring_datasource_url      = jsonencode("jdbc:postgresql://${azurerm_postgresql_flexible_server.clippy.fqdn}:5432/${azurerm_postgresql_flexible_server_database.clippy.name}?sslmode=require")
    spring_datasource_username = jsonencode(var.postgres_admin_username)
    spring_datasource_password = jsonencode(var.postgres_admin_password)
    logging_file_name          = jsonencode("/var/log/clippy/server.log")
  }))

  admin_ssh_key {
    username   = var.vm_admin_username
    public_key = var.vm_admin_ssh_public_key
  }

  identity {
    type = "SystemAssigned"
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "Standard_LRS"
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-arm64"
    version   = "latest"
  }

  depends_on = [
    azurerm_postgresql_flexible_server_firewall_rule.server_vm,
    azurerm_network_interface_security_group_association.server
  ]

  lifecycle {
    precondition {
      condition     = trimspace(var.vm_admin_ssh_public_key) != ""
      error_message = "vm_admin_ssh_public_key must be set when create_server_vm is true."
    }

    precondition {
      condition     = !strcontains(var.server_repo_url, "replace-me")
      error_message = "server_repo_url must be set to the real Clippy repository URL when create_server_vm is true."
    }
  }
}
