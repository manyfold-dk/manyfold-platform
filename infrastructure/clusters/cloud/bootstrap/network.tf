# -----------------------------------------------------------------------------
# Private Network
# -----------------------------------------------------------------------------

resource "hcloud_network" "main" {
  name     = "${var.cluster_name}-network"
  ip_range = var.network_cidr

  labels = {
    cluster     = var.cluster_name
    environment = var.environment
    managed_by  = "opentofu"
  }
}

resource "hcloud_network_subnet" "nodes" {
  network_id   = hcloud_network.main.id
  type         = "cloud"
  network_zone = var.hcloud_network_zone
  ip_range     = var.subnet_cidr
}

# -----------------------------------------------------------------------------
# Firewall
# -----------------------------------------------------------------------------

# Determine source IPs based on lockdown mode
locals {
  # In lockdown mode, only admin IPs can access management ports
  # In normal mode, allow all IPs
  # The firewall filters only the public interfaces of the servers, not the
  # private network. The API load balancer reaches the control plane over the
  # private network and so bypasses these rules; lockdown mode closes the load
  # balancer's public interface instead (loadbalancer.tf).
  admin_source_ips   = var.lockdown_mode ? var.admin_ips : ["0.0.0.0/0", "::/0"]
  ingress_source_ips = var.lockdown_mode ? [] : ["0.0.0.0/0", "::/0"]
}

resource "hcloud_firewall" "talos" {
  name = "${var.cluster_name}-firewall"

  labels = {
    cluster     = var.cluster_name
    environment = var.environment
    managed_by  = "opentofu"
    lockdown    = var.lockdown_mode ? "enabled" : "disabled"
  }

  # SSH (for initial rescue mode installation only)
  # After Talos is installed, SSH is not available
  # In lockdown: admin IPs only
  dynamic "rule" {
    for_each = length(local.admin_source_ips) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "22"
      source_ips = local.admin_source_ips
    }
  }

  # Kubernetes API on the servers' public addresses (the load balancer reaches
  # the control plane over the private network, not through this rule)
  # In lockdown: admin IPs only
  dynamic "rule" {
    for_each = length(local.admin_source_ips) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "6443"
      source_ips = local.admin_source_ips
    }
  }

  # HTTP ingress
  # In lockdown: closed entirely (no services running)
  dynamic "rule" {
    for_each = length(local.ingress_source_ips) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "80"
      source_ips = local.ingress_source_ips
    }
  }

  # HTTPS ingress
  # In lockdown: closed entirely (no services running)
  dynamic "rule" {
    for_each = length(local.ingress_source_ips) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "443"
      source_ips = local.ingress_source_ips
    }
  }

  # Talos API - Maintenance mode (protected by mTLS - certificate required)
  # Port 50000: Used during initial setup before config is applied
  # In lockdown: admin IPs only (defense in depth)
  dynamic "rule" {
    for_each = length(local.admin_source_ips) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "50000"
      source_ips = local.admin_source_ips
    }
  }

  # Talos API - apid (protected by mTLS - certificate required)
  # Port 50001: Used after machine config is applied
  # In lockdown: admin IPs only (defense in depth)
  dynamic "rule" {
    for_each = length(local.admin_source_ips) > 0 ? [1] : []
    content {
      direction  = "in"
      protocol   = "tcp"
      port       = "50001"
      source_ips = local.admin_source_ips
    }
  }

  # Allow all traffic from private network (inter-node communication)
  # Always enabled - required for cluster operation
  rule {
    direction  = "in"
    protocol   = "tcp"
    port       = "any"
    source_ips = [var.network_cidr]
  }

  rule {
    direction  = "in"
    protocol   = "udp"
    port       = "any"
    source_ips = [var.network_cidr]
  }

  # ICMP (ping) from private network
  rule {
    direction  = "in"
    protocol   = "icmp"
    source_ips = [var.network_cidr]
  }
}
