# Reset a Talos Node

Procedure for resetting a problematic node to a clean state.

## Table of Contents

- [When to Use](#when-to-use)
- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Procedure](#procedure)
  - [Option 1: Soft Reset (Preserves Machine Config)](#option-1-soft-reset-preserves-machine-config)
  - [Option 2: Full Reset (Wipes Everything)](#option-2-full-reset-wipes-everything)
  - [Option 3: Hetzner Rescue Mode (Last Resort)](#option-3-hetzner-rescue-mode-last-resort)
- [After Reset](#after-reset)
- [Troubleshooting](#troubleshooting)
- [Related](#related)

## When to Use

- Node is unresponsive or in a bad state
- Node needs to rejoin the cluster after issues
- Cleaning up a node before decommissioning

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<node-name>` | The Kubernetes name of the node to reset. |
| `<node-ip>` | The private IP address of the node to reset. |
| `<any-healthy-cp-ip>` | The private IP address of a healthy control plane node. |
| `<server-name>`, `<public-ip>` | The Hetzner Cloud server name and the public IP address of the node. |
| `<schematic-id>` | The Talos Image Factory schematic ID of the cluster. See [Finding the Correct Image](upgrade-os.md#finding-the-correct-image). |
| `<talos-version>` | The Talos version that the cluster runs now. `talosctl version --nodes <any-healthy-cp-ip>` shows the version. |
| `<config>` | The machine configuration file for the role of the node (control plane or worker). |

## Prerequisites

```bash
# Ensure node is removed from workloads first
kubectl cordon <node-name>
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data
```

## Procedure

### Option 1: Soft Reset (Preserves Machine Config)

This wipes only the EPHEMERAL partition (`/var`: etcd data, kubelet data and container data). The STATE partition keeps the machine configuration and the node identity, so the node joins the cluster again after the reboot.

> **Warning:** Always set `--system-labels-to-wipe`. Without it, `talosctl reset` wipes the full system disk (`--wipe-mode all` is the default). A Hetzner Cloud server cannot boot after a full disk wipe. You must then reinstall Talos with [Option 3](#option-3-hetzner-rescue-mode-last-resort).

With `--graceful=true`, a control plane node leaves etcd before the reset. If the etcd member of the node is broken, the graceful reset fails. Then use the procedure in [etcd-recovery.md](etcd-recovery.md#scenario-1-single-member-failure).

```bash
# Reset the node
talosctl reset --nodes <node-ip> --graceful=true --reboot=true \
  --system-labels-to-wipe EPHEMERAL

# Wait for node to rejoin
kubectl get nodes -w
```

### Option 2: Full Reset (Wipes Everything)

This wipes the STATE and EPHEMERAL partitions. The node loses its machine configuration and its node identity. The Talos installation on the other partitions stays:

```bash
# Full wipe
talosctl reset --nodes <node-ip> --graceful=true --reboot=true \
  --system-labels-to-wipe STATE --system-labels-to-wipe EPHEMERAL
```

After the reboot, Talos gets its machine configuration again from the Hetzner metadata service (`user_data`). OpenTofu sets the `user_data` when it creates the server (`infrastructure/clusters/cloud/bootstrap/servers.tf`) and ignores later changes to it. Thus the node starts with the configuration that it had when OpenTofu created it:

1. Make sure that the node is back:
   ```bash
   talosctl version --nodes <node-ip>
   ```

2. If you changed the machine configuration of the node after its creation (for example with `talosctl patch machineconfig`), apply these changes again.

3. If the server has no `user_data`, Talos starts in maintenance mode. Then apply the machine configuration through the maintenance service:
   ```bash
   talosctl apply-config --nodes <node-ip> --file <config>.yaml --insecure
   ```

### Option 3: Hetzner Rescue Mode (Last Resort)

If talosctl cannot reach the node:

1. **Enable rescue mode via Hetzner Console**
   ```bash
   hcloud server enable-rescue <server-name> --type linux64
   hcloud server reset <server-name>
   ```

2. **SSH into rescue mode**
   ```bash
   ssh root@<public-ip>
   ```

3. **Reinstall Talos** (see bootstrap README for full process)
   ```bash
   # Download Talos image (use current cluster version)
   curl -LO https://factory.talos.dev/image/<schematic-id>/<talos-version>/hcloud-amd64.raw.xz

   # Write to disk
   xz -d -c hcloud-amd64.raw.xz | dd of=/dev/sda bs=4M status=progress

   # Reboot
   reboot
   ```

4. **Apply machine configuration** if the node starts in maintenance mode. A server with `user_data` gets its configuration from the Hetzner metadata service (see [Option 2](#option-2-full-reset-wipes-everything)).
   ```bash
   talosctl apply-config --nodes <node-ip> --file <config>.yaml --insecure
   ```

## After Reset

### For Control Plane Nodes

1. Wait for node to join etcd cluster:
   ```bash
   talosctl etcd members --nodes <any-healthy-cp-ip>
   ```

2. Verify Kubernetes membership:
   ```bash
   kubectl get nodes
   ```

3. Uncordon the node:
   ```bash
   kubectl uncordon <node-name>
   ```

### For Worker Nodes

1. Verify node joins cluster:
   ```bash
   kubectl get nodes -w
   ```

2. Uncordon the node:
   ```bash
   kubectl uncordon <node-name>
   ```

3. Verify workloads schedule:
   ```bash
   kubectl get pods -A -o wide | grep <node-name>
   ```

## Troubleshooting

### Node won't join cluster after reset

1. Check network connectivity:
   ```bash
   talosctl dmesg --nodes <node-ip> | grep -i network
   ```

2. Verify machine config is applied:
   ```bash
   talosctl get machineconfig --nodes <node-ip>
   ```

3. Check if kubelet is running:
   ```bash
   talosctl service kubelet --nodes <node-ip>
   ```

### etcd won't accept rejoining control plane

See [etcd-recovery.md](etcd-recovery.md) for etcd-specific issues.

### Node shows NotReady

1. Check Cilium status:
   ```bash
   kubectl get pods -n kube-system -l k8s-app=cilium -o wide
   ```

2. Check kubelet logs:
   ```bash
   talosctl logs kubelet --nodes <node-ip> | tail -50
   ```

## Related

- [etcd-recovery.md](etcd-recovery.md) - etcd cluster issues
- [view-logs.md](view-logs.md) - Viewing node logs
- [../cluster/drain-node.md](../cluster/drain-node.md) - Draining nodes for maintenance
