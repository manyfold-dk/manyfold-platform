# -----------------------------------------------------------------------------
# Load Balancer for Kubernetes API
# -----------------------------------------------------------------------------

locals {
  # The load balancer's address in the node subnet. The nodes resolve the API
  # name to it (talos.tf), so they reach the API over the private network in
  # every mode, lockdown included.
  api_lb_private_ip = cidrhost(var.subnet_cidr, 2) # host .2 of the node subnet
}

resource "hcloud_load_balancer" "api" {
  name               = "${var.cluster_name}-api"
  load_balancer_type = var.api_load_balancer_type
  location           = var.hcloud_location

  labels = {
    cluster     = var.cluster_name
    environment = var.environment
    managed_by  = "opentofu"
    purpose     = "kubernetes-api"
  }
}

resource "hcloud_load_balancer_network" "api" {
  load_balancer_id = hcloud_load_balancer.api.id
  network_id       = hcloud_network.main.id
  ip               = local.api_lb_private_ip

  # Lockdown mode disables the public interface of the load balancer. The load
  # balancer reaches its targets over the private network (use_private_ip),
  # which the firewall in network.tf does not filter, so the firewall alone
  # cannot close the public path to the API through the load balancer. In
  # lockdown mode the API name stops answering from the internet; admins reach
  # the API on a control plane node's public address, which the firewall limits
  # to admin_ips. The health checks use the private network and keep passing,
  # and the nodes resolve the API name to the private address above. The
  # switch is separate from lockdown_mode so that an installation already in
  # lockdown can patch its nodes with the API host entry first and close the
  # load balancer's public path as a deliberate second step.
  enable_public_interface = !(var.lockdown_mode && var.lockdown_closes_api_load_balancer)

  # The attachment takes an address in the node subnet; network_id alone does
  # not order it after the subnet.
  depends_on = [hcloud_network_subnet.nodes]
}

# Kubernetes API service
resource "hcloud_load_balancer_service" "api" {
  load_balancer_id = hcloud_load_balancer.api.id
  protocol         = "tcp"
  listen_port      = 6443
  destination_port = 6443

  health_check {
    protocol = "tcp"
    port     = 6443
    interval = 10
    timeout  = 5
    retries  = 3
  }
}

# Attach only control plane nodes as targets (API server runs on CP nodes only)
resource "hcloud_load_balancer_target" "api" {
  count = var.controlplane_count

  type             = "server"
  load_balancer_id = hcloud_load_balancer.api.id
  server_id        = hcloud_server.controlplane[count.index].id
  use_private_ip   = true

  depends_on = [hcloud_load_balancer_network.api, hcloud_server_network.controlplane]
}

# -----------------------------------------------------------------------------
# DNS Record for Kubernetes API
# -----------------------------------------------------------------------------

resource "cloudflare_dns_record" "api" {
  zone_id = var.cloudflare_zone_id
  name    = var.api_subdomain
  content = hcloud_load_balancer.api.ipv4
  type    = "A"
  ttl     = 300
  proxied = false # Don't proxy K8s API traffic
  comment = "Kubernetes API endpoint - managed by OpenTofu"
}

# -----------------------------------------------------------------------------
# DNS Records for Ingress
# -----------------------------------------------------------------------------
# NOTE: Wildcard and root DNS records are now managed by External-DNS in the cluster.
# External-DNS creates explicit A records for each Ingress resource, pointing to
# the Hetzner Cloud load balancer (managed by CCM via ingress-nginx service).
#
# This approach:
# - Prevents internal hostnames (under var.internal_subdomain) from being exposed
# - Allows fine-grained control over which services are publicly accessible
# - Uses the load balancer IP (not worker node IPs) for proper HA
#
# See: platform/components/external-dns/values-cloud.yaml for configuration
# See: ADR-0019 for architecture decision on DNS management
