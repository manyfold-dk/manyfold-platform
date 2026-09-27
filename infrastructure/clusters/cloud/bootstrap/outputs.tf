# -----------------------------------------------------------------------------
# Network Outputs
# -----------------------------------------------------------------------------

output "network_id" {
  description = "Hetzner network ID"
  value       = hcloud_network.main.id
}

output "network_name" {
  description = "Hetzner network name"
  value       = hcloud_network.main.name
}

# -----------------------------------------------------------------------------
# Control Plane Server Outputs
# -----------------------------------------------------------------------------

output "controlplane_ids" {
  description = "List of control plane server IDs"
  value       = hcloud_server.controlplane[*].id
}

output "controlplane_names" {
  description = "List of control plane server names"
  value       = hcloud_server.controlplane[*].name
}

output "controlplane_public_ips" {
  description = "List of control plane public IPv4 addresses"
  value       = hcloud_server.controlplane[*].ipv4_address
}

output "controlplane_private_ips" {
  description = "List of control plane private IPv4 addresses"
  value       = hcloud_server_network.controlplane[*].ip
}

# -----------------------------------------------------------------------------
# Worker Server Outputs
# -----------------------------------------------------------------------------

output "worker_ids" {
  description = "List of worker server IDs"
  value       = hcloud_server.worker[*].id
}

output "worker_names" {
  description = "List of worker server names"
  value       = hcloud_server.worker[*].name
}

output "worker_public_ips" {
  description = "List of worker public IPv4 addresses"
  value       = hcloud_server.worker[*].ipv4_address
}

output "worker_private_ips" {
  description = "List of worker private IPv4 addresses"
  value       = hcloud_server_network.worker[*].ip
}

# -----------------------------------------------------------------------------
# Load Balancer Outputs
# -----------------------------------------------------------------------------

output "api_lb_id" {
  description = "API load balancer ID"
  value       = hcloud_load_balancer.api.id
}

output "api_lb_ip" {
  description = "API load balancer public IP"
  value       = hcloud_load_balancer.api.ipv4
}

output "api_lb_private_ip" {
  description = "API load balancer private IP"
  value       = hcloud_load_balancer_network.api.ip
}

# -----------------------------------------------------------------------------
# DNS Outputs
# -----------------------------------------------------------------------------

output "kubernetes_api_endpoint" {
  description = "Kubernetes API endpoint URL"
  value       = "https://${var.api_subdomain}.${var.domain}:6443"
}

output "wildcard_domain" {
  description = "Wildcard domain for ingress"
  value       = "*.${var.domain}"
}

# -----------------------------------------------------------------------------
# Talos Configuration Inputs
# -----------------------------------------------------------------------------

output "talos_config_inputs" {
  description = "Inputs needed for Talos machine configuration"
  value = {
    cluster_name             = var.cluster_name
    cluster_endpoint         = "https://${var.api_subdomain}.${var.domain}:6443"
    controlplane_public_ips  = hcloud_server.controlplane[*].ipv4_address
    controlplane_private_ips = hcloud_server_network.controlplane[*].ip
    worker_public_ips        = hcloud_server.worker[*].ipv4_address
    worker_private_ips       = hcloud_server_network.worker[*].ip
    pod_cidr                 = var.pod_cidr
    service_cidr             = var.service_cidr
  }
}

# -----------------------------------------------------------------------------
# Talos Outputs (available after cluster bootstrap)
# -----------------------------------------------------------------------------

output "talosconfig" {
  description = "Talos client configuration (save to ~/.talos/config)"
  value       = try(data.talos_client_configuration.this.talos_config, null)
  sensitive   = true
}

output "kubeconfig" {
  description = "Kubernetes kubeconfig (save to ~/.kube/config)"
  value       = try(talos_cluster_kubeconfig.this.kubeconfig_raw, null)
  sensitive   = true
}
