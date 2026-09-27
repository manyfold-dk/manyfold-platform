# -----------------------------------------------------------------------------
# Host entries on each node (extraHostEntries, not public DNS)
#
# The API name resolves to the API load balancer's private address, so the
# nodes reach the API over the private network, also when lockdown mode has
# disabled the load balancer's public interface.
#
# The internal registry mirror names resolve to the Cilium Gateway, whose
# address exists only after the cluster runs. Until gateway_ip is set, the
# nodes get no mirror entries and containerd falls back to the upstream
# registries.
#
# The servers ignore later user_data changes, so nodes created before an entry
# existed get it by talosctl patch (README.md "Lockdown Mode" and "Registry
# Mirrors").
# -----------------------------------------------------------------------------

locals {
  internal_domain = "${var.internal_subdomain}.${var.domain}"

  internal_registry_hostnames = [
    "mirror-docker-io.${local.internal_domain}",
    "mirror-ghcr-io.${local.internal_domain}",
    "mirror-quay-io.${local.internal_domain}",
    "mirror-registry-k8s-io.${local.internal_domain}",
    "local-registry.${local.internal_domain}",
  ]

  api_host_entries = [
    {
      ip      = local.api_lb_private_ip
      aliases = ["${var.api_subdomain}.${var.domain}"]
    }
  ]

  registry_host_entries = var.gateway_ip == null ? [] : [
    {
      ip      = var.gateway_ip
      aliases = local.internal_registry_hostnames
    }
  ]

  node_host_entries = concat(local.api_host_entries, local.registry_host_entries)
}

# -----------------------------------------------------------------------------
# Talos Snapshot (built by hcloud-upload-image)
# Create it before the first tofu apply (README.md "Setup", step 3)
# -----------------------------------------------------------------------------

data "hcloud_image" "talos" {
  with_selector     = "os=talos,version=${var.talos_version}"
  most_recent       = true
  with_architecture = "x86"
}

# -----------------------------------------------------------------------------
# Talos Machine Secrets
# -----------------------------------------------------------------------------

resource "talos_machine_secrets" "this" {}

# -----------------------------------------------------------------------------
# Talos Machine Configuration - Control Plane (per-node)
# Generates one config per CP node with per-node certSANs (the hostname is set
# by the hcloud platform from the server name, not statically in the config).
# Config is delivered via Hetzner user_data (metadata service) on first boot.
# -----------------------------------------------------------------------------

data "talos_machine_configuration" "controlplane" {
  count = var.controlplane_count

  cluster_name     = var.cluster_name
  cluster_endpoint = "https://${var.api_subdomain}.${var.domain}:6443"
  machine_type     = "controlplane"
  machine_secrets  = talos_machine_secrets.this.machine_secrets

  talos_version      = var.talos_version
  kubernetes_version = var.kubernetes_version

  config_patches = [
    yamlencode({
      machine = {
        install = {
          disk  = "/dev/sda"
          image = "factory.talos.dev/installer/ce4c980550dd2ab1b17bbf2b08801c7eb59418eafe8f279833297925d67c7515:${var.talos_version}"
        }
        registries = {
          mirrors = {
            "docker.io" = {
              endpoints = ["https://mirror-docker-io.${local.internal_domain}"]
            }
            "quay.io" = {
              endpoints = ["https://mirror-quay-io.${local.internal_domain}"]
            }
            "ghcr.io" = {
              endpoints = ["https://mirror-ghcr-io.${local.internal_domain}"]
            }
            "registry.k8s.io" = {
              endpoints = ["https://mirror-registry-k8s-io.${local.internal_domain}"]
            }
          }
        }
        network = {
          # Do NOT set machine.network.hostname here. The Talos hcloud platform
          # injects the hostname from the Hetzner server name (<cluster_name>-cp-N) and
          # rejects the config ("static hostname is already set in v1alpha1 config"),
          # boot-looping fresh nodes. Existing nodes keep their hostname via
          # ignore_changes=[user_data]; only newly created/replaced nodes are affected.
          interfaces = [
            {
              interface = "eth0"
              dhcp      = true
            },
            {
              interface = "eth1"
              dhcp      = true
            }
          ]
          extraHostEntries = local.node_host_entries
        }
        certSANs = [
          "${var.api_subdomain}.${var.domain}",
          hcloud_load_balancer.api.ipv4,
        ]
        # Enable external cloud provider for Hetzner CCM
        kubelet = {
          extraArgs = {
            "cloud-provider" = "external"
          }
        }
      }
      cluster = {
        network = {
          cni = {
            name = "none"
          }
          podSubnets     = [var.pod_cidr]
          serviceSubnets = [var.service_cidr]
        }
        # Disable scheduling on control plane - workers handle workloads
        allowSchedulingOnControlPlanes = false
        # Tune kube-apiserver for small (4GB) control plane nodes.
        # default-watch-cache-size halved from upstream default (100→50) to
        # contain watch-cache memory growth that OOM-restarted the apiservers
        # every few days on such nodes.
        apiServer = {
          extraArgs = {
            "default-watch-cache-size" = "50"
          }
        }
        # Expose metrics for Prometheus scraping
        # By default, Talos binds these to the loopback address, unreachable from pods
        controllerManager = {
          extraArgs = {
            "bind-address" = "0.0.0.0"
          }
        }
        scheduler = {
          extraArgs = {
            "bind-address" = "0.0.0.0"
          }
        }
      }
    })
  ]
}

# -----------------------------------------------------------------------------
# Talos Machine Configuration - Worker (per-node)
# -----------------------------------------------------------------------------

data "talos_machine_configuration" "worker" {
  count = var.worker_count

  cluster_name     = var.cluster_name
  cluster_endpoint = "https://${var.api_subdomain}.${var.domain}:6443"
  machine_type     = "worker"
  machine_secrets  = talos_machine_secrets.this.machine_secrets

  talos_version      = var.talos_version
  kubernetes_version = var.kubernetes_version

  config_patches = [
    yamlencode({
      machine = {
        install = {
          disk  = "/dev/sda"
          image = "factory.talos.dev/installer/ce4c980550dd2ab1b17bbf2b08801c7eb59418eafe8f279833297925d67c7515:${var.talos_version}"
        }
        registries = {
          mirrors = {
            "docker.io" = {
              endpoints = ["https://mirror-docker-io.${local.internal_domain}"]
            }
            "quay.io" = {
              endpoints = ["https://mirror-quay-io.${local.internal_domain}"]
            }
            "ghcr.io" = {
              endpoints = ["https://mirror-ghcr-io.${local.internal_domain}"]
            }
            "registry.k8s.io" = {
              endpoints = ["https://mirror-registry-k8s-io.${local.internal_domain}"]
            }
          }
        }
        network = {
          # Do NOT set machine.network.hostname here -- see the control-plane
          # config note above. The Talos hcloud platform sets the hostname from
          # the Hetzner server name (<cluster_name>-worker-N); a static hostname also
          # being present fails validation ("static hostname is already set in
          # v1alpha1 config") and boot-loops fresh nodes.
          interfaces = [
            {
              interface = "eth0"
              dhcp      = true
            },
            {
              interface = "eth1"
              dhcp      = true
            }
          ]
          extraHostEntries = local.node_host_entries
        }
        # Enable external cloud provider for Hetzner CCM
        kubelet = {
          extraArgs = {
            "cloud-provider" = "external"
          }
        }
      }
    })
  ]
}

# -----------------------------------------------------------------------------
# Talos Client Configuration
# -----------------------------------------------------------------------------

data "talos_client_configuration" "this" {
  cluster_name         = var.cluster_name
  client_configuration = talos_machine_secrets.this.client_configuration
  # Use control plane nodes as endpoints for talosctl
  endpoints = hcloud_server.controlplane[*].ipv4_address
  # Include all nodes (both control plane and workers)
  nodes = concat(
    hcloud_server.controlplane[*].ipv4_address,
    hcloud_server.worker[*].ipv4_address
  )
}

# -----------------------------------------------------------------------------
# Bootstrap Cluster (only on first control plane node)
# -----------------------------------------------------------------------------

resource "talos_machine_bootstrap" "this" {
  client_configuration = talos_machine_secrets.this.client_configuration
  node                 = hcloud_server.controlplane[0].ipv4_address

  depends_on = [
    hcloud_server.controlplane,
    hcloud_server.worker,
    hcloud_server_network.controlplane,
    hcloud_server_network.worker,
  ]
}

# -----------------------------------------------------------------------------
# Get Kubeconfig
# -----------------------------------------------------------------------------

resource "talos_cluster_kubeconfig" "this" {
  client_configuration = talos_machine_secrets.this.client_configuration
  node                 = hcloud_server.controlplane[0].ipv4_address

  depends_on = [talos_machine_bootstrap.this]
}
