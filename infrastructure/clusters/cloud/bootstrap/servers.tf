# -----------------------------------------------------------------------------
# Placement Group (spread nodes across fault domains)
# -----------------------------------------------------------------------------

resource "hcloud_placement_group" "nodes" {
  name = "${var.cluster_name}-nodes"
  type = "spread"

  labels = {
    cluster     = var.cluster_name
    environment = var.environment
    managed_by  = "opentofu"
  }
}

# -----------------------------------------------------------------------------
# Control Plane Nodes
# -----------------------------------------------------------------------------

resource "hcloud_server" "controlplane" {
  count = var.controlplane_count

  name        = "${var.cluster_name}-cp-${count.index + 1}"
  server_type = var.controlplane_node_type
  location    = var.hcloud_location
  image       = data.hcloud_image.talos.id

  # Machine config delivered via Hetzner metadata service on first boot
  user_data = data.talos_machine_configuration.controlplane[count.index].machine_configuration

  placement_group_id = hcloud_placement_group.nodes.id
  firewall_ids       = [hcloud_firewall.talos.id]

  labels = {
    cluster     = var.cluster_name
    environment = var.environment
    managed_by  = "opentofu"
    role        = "controlplane"
    node_index  = tostring(count.index + 1)
  }

  # Enable backups for data protection
  backups = true

  lifecycle {
    ignore_changes = [image, user_data, ssh_keys]
  }
}

# -----------------------------------------------------------------------------
# Worker Nodes
# -----------------------------------------------------------------------------

resource "hcloud_server" "worker" {
  count = var.worker_count

  name        = "${var.cluster_name}-worker-${count.index + 1}"
  server_type = var.worker_node_type
  location    = var.hcloud_location
  image       = data.hcloud_image.talos.id

  # Machine config delivered via Hetzner metadata service on first boot
  user_data = data.talos_machine_configuration.worker[count.index].machine_configuration

  placement_group_id = hcloud_placement_group.nodes.id
  firewall_ids       = [hcloud_firewall.talos.id]

  labels = {
    cluster     = var.cluster_name
    environment = var.environment
    managed_by  = "opentofu"
    role        = "worker"
    node_index  = tostring(count.index + 1)
  }

  # Enable backups for data protection
  backups = true

  lifecycle {
    ignore_changes = [image, user_data, ssh_keys]
  }
}

# -----------------------------------------------------------------------------
# Attach Control Plane Nodes to Private Network
# -----------------------------------------------------------------------------

resource "hcloud_server_network" "controlplane" {
  count = var.controlplane_count

  server_id  = hcloud_server.controlplane[count.index].id
  network_id = hcloud_network.main.id
  ip         = cidrhost(var.subnet_cidr, 10 + count.index) # hosts .10, .11, .12 of the node subnet

  # The attachment takes an address in the node subnet; network_id alone does
  # not order it after the subnet.
  depends_on = [hcloud_network_subnet.nodes]
}

# -----------------------------------------------------------------------------
# Attach Worker Nodes to Private Network
# -----------------------------------------------------------------------------

resource "hcloud_server_network" "worker" {
  count = var.worker_count

  server_id  = hcloud_server.worker[count.index].id
  network_id = hcloud_network.main.id
  ip         = cidrhost(var.subnet_cidr, 20 + count.index) # hosts .20, .21, ... of the node subnet

  # See the control plane attachment above.
  depends_on = [hcloud_network_subnet.nodes]
}
