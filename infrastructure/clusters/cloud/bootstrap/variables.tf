# -----------------------------------------------------------------------------
# Inputs
#
# Every installation value is a required input without a default; gateway_ip
# defaults to null because its value exists only after the first apply. The
# installation supplies them in instance.auto.tfvars (loaded automatically) or
# in a -var-file; see README.md "Inputs". The variables of the redirect and
# brand DNS zones are declared in dns.tf, beside the only resources that use
# them.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# General
# -----------------------------------------------------------------------------

variable "cluster_name" {
  description = "Name of the Kubernetes cluster"
  type        = string
}

variable "environment" {
  description = "Environment name (e.g., production, staging)"
  type        = string
}

# -----------------------------------------------------------------------------
# Hetzner Cloud
# -----------------------------------------------------------------------------

variable "hcloud_location" {
  description = "Hetzner Cloud location (fsn1, nbg1, hel1)"
  type        = string
}

variable "hcloud_network_zone" {
  description = "Hetzner Cloud network zone of the node subnet; it must contain hcloud_location (e.g., eu-central)"
  type        = string
}

variable "controlplane_count" {
  description = "Number of control plane nodes"
  type        = number
}

variable "worker_count" {
  description = "Number of worker nodes"
  type        = number
}

variable "controlplane_node_type" {
  description = "Hetzner server type for control plane nodes"
  type        = string
}

variable "worker_node_type" {
  description = "Hetzner server type for worker nodes"
  type        = string
}

variable "api_load_balancer_type" {
  description = "Hetzner load balancer type for the Kubernetes API load balancer"
  type        = string
}

variable "talos_version" {
  description = "Talos Linux version"
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes version"
  type        = string
}

# -----------------------------------------------------------------------------
# Network
# -----------------------------------------------------------------------------

variable "network_cidr" {
  description = "CIDR for the private network"
  type        = string
}

variable "subnet_cidr" {
  description = "CIDR for the node subnet"
  type        = string
}

variable "pod_cidr" {
  description = "CIDR for Kubernetes pods"
  type        = string
}

variable "service_cidr" {
  description = "CIDR for Kubernetes services"
  type        = string
}

# -----------------------------------------------------------------------------
# DNS (Cloudflare)
# -----------------------------------------------------------------------------

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID of the base domain (var.domain)"
  type        = string
  sensitive   = true
}

variable "cloudflare_account_id" {
  description = "Cloudflare Account ID for R2"
  type        = string
  sensitive   = true
}

variable "domain" {
  description = "Base domain for the cluster"
  type        = string
}

variable "api_subdomain" {
  description = "Subdomain for Kubernetes API endpoint"
  type        = string
}

variable "internal_subdomain" {
  description = "Subdomain under var.domain for internal-only hosts such as the registry mirrors (resolved on the nodes, not in public DNS)"
  type        = string
}

# -----------------------------------------------------------------------------
# Gateway / Registry Mirrors
# -----------------------------------------------------------------------------

variable "gateway_ip" {
  description = "External IP of the Cilium Gateway (Hetzner CCM LoadBalancer) for internal registry mirror DNS. The address exists only after the cluster runs; leave it null on the first apply (see README.md \"Registry Mirrors\")"
  type        = string
  default     = null

  validation {
    condition     = var.gateway_ip == null || can(cidrhost("${var.gateway_ip}/32", 0))
    error_message = "gateway_ip must be null or an IP address."
  }
}

# -----------------------------------------------------------------------------
# Object storage (Cloudflare R2)
# -----------------------------------------------------------------------------

variable "velero_backups_bucket_name" {
  description = "R2 bucket for Velero backups (EU jurisdiction)"
  type        = string
}

variable "openbao_snapshots_bucket_name" {
  description = "R2 bucket for OpenBao Raft snapshots (EU jurisdiction)"
  type        = string
}

variable "velero_backups_replica_bucket_name" {
  description = "R2 bucket for the write-once replica of the Velero backups (EU jurisdiction)"
  type        = string
}

variable "tenant_carveouts_bucket_name" {
  description = "R2 bucket for tenant carve-out exports (EU jurisdiction)"
  type        = string
}

# -----------------------------------------------------------------------------
# Security / Lockdown
# -----------------------------------------------------------------------------

variable "lockdown_mode" {
  description = "Enable lockdown mode: restrict the firewall to admin IPs only and disable the public interface of the API load balancer"
  type        = bool
}

variable "lockdown_closes_api_load_balancer" {
  description = "In lockdown mode, also disable the public interface of the API load balancer. Switch it on only after every node carries the API host entry (README.md \"Lockdown Mode\"), because the nodes then reach the API over the private network only."
  type        = bool
}

variable "admin_ips" {
  description = "List of admin IP addresses allowed during lockdown (CIDR format, e.g., '<admin-address>/32')"
  type        = list(string)
}
