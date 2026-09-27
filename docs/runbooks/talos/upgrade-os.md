# Upgrade Talos OS

Procedure for upgrading Talos Linux on cluster nodes.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Pre-Upgrade Checklist](#pre-upgrade-checklist)
- [Upgrade Procedure](#upgrade-procedure)
  - [Step 1: Build Snapshot for New Version (If Supported)](#step-1-build-snapshot-for-new-version-if-supported)
  - [Step 2: Upgrade Control Plane Nodes (One at a Time)](#step-2-upgrade-control-plane-nodes-one-at-a-time)
  - [Step 3: Upgrade Worker Nodes](#step-3-upgrade-worker-nodes)
  - [Step 4: Upgrade Kubernetes](#step-4-upgrade-kubernetes)
  - [Step 5: Verify Upgrade](#step-5-verify-upgrade)
  - [Step 6: Update OpenTofu Variables](#step-6-update-opentofu-variables)
- [Version Constraints](#version-constraints)
- [Rollback](#rollback)
- [Finding the Correct Image](#finding-the-correct-image)
- [Troubleshooting](#troubleshooting)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster. |
| `<cp-1-ip>`, `<cp-2-ip>`, `<cp-3-ip>` | The private IP addresses of the three control plane nodes. |
| `<worker-1-ip>`, `<worker-2-ip>` | The private IP addresses of the worker nodes. |
| `<cp-1-name>`, `<cp-2-name>`, `<cp-3-name>` | The Kubernetes node names of the control plane nodes. |
| `<worker-1-name>`, `<worker-2-name>` | The Kubernetes node names of the worker nodes. |
| `<cp-public-ip>` | The public IP address of a control plane node. |
| `<schematic-id>` | The Talos Image Factory schematic ID of the cluster. See [Finding the Correct Image](#finding-the-correct-image). |
| `<TARGET_VERSION>` | The Talos version to upgrade to, without the `v` prefix. |
| `<K8S_VERSION>` | The Kubernetes version to upgrade to. |
| `<node-ip>` | The private IP address of the node that the step names. |

## Prerequisites

- `talosctl` installed and configured
- Kubeconfig access to cluster
- `hcloud-upload-image` installed (included in devcontainer)
- `HCLOUD_TOKEN` available (`source infrastructure/.env.cloud`)
- Current Talos version noted

```bash
# Check current version
talosctl version --nodes <cp-1-ip>

# Check all nodes
kubectl --kubeconfig <cloud-kubeconfig> get nodes -o wide
```

## Pre-Upgrade Checklist

1. **Check cluster health**
   ```bash
   kubectl --kubeconfig <cloud-kubeconfig> get nodes
   talosctl health --nodes <cp-1-ip>,<cp-2-ip>,<cp-3-ip>
   ```

2. **Review release notes** for the target Talos version:
   - https://github.com/siderolabs/talos/releases
   - Check for breaking changes or required actions

3. **Verify etcd health**
   ```bash
   talosctl etcd members --nodes <cp-1-ip>
   ```

4. **Verify Velero backup is recent**
   ```bash
   kubectl --kubeconfig <cloud-kubeconfig> get backups -n velero \
     --sort-by=.metadata.creationTimestamp | tail -3
   ```

5. **Verify ArgoCD apps are synced**
   ```bash
   cd infrastructure/clusters/cloud/scripts
   ./setup-argocd.sh status
   ```

## Upgrade Procedure

### Step 1: Build Snapshot for New Version (If Supported)

Create a Hetzner snapshot for the target Talos version. This ensures new servers (e.g., replacements) use the correct version.

```bash
cd infrastructure/clusters/cloud/scripts
source ../../.env.cloud
./build-talos-snapshot.sh v<TARGET_VERSION>
```

> **Known issue:** A Talos snapshot that `hcloud-upload-image` builds does not always boot on Hetzner Cloud. If the snapshot for the target version does not boot, bootstrap from an older snapshot that boots. Then upgrade the nodes in place. Skip this step if the target version snapshot is known to be incompatible.

### Step 2: Upgrade Control Plane Nodes (One at a Time)

Upgrade control plane nodes sequentially to maintain etcd quorum:

```bash
SCHEMATIC="<schematic-id>"
TARGET="v<TARGET_VERSION>"
IMAGE="factory.talos.dev/installer/${SCHEMATIC}:${TARGET}"

# Node 1
talosctl upgrade --nodes <cp-1-ip> --image "${IMAGE}" --preserve

# Wait for node to come back
kubectl --kubeconfig <cloud-kubeconfig> wait \
  --for=condition=Ready node/<cp-1-name> --timeout=600s

# Verify health before proceeding to next node
talosctl health --nodes <cp-1-ip>
```

Repeat the commands for cp-2 (`<cp-2-ip>`, `<cp-2-name>`). Then repeat the commands for cp-3 (`<cp-3-ip>`, `<cp-3-name>`).

### Step 3: Upgrade Worker Nodes

Worker nodes can be upgraded in parallel if desired:

```bash
# Worker 1
talosctl upgrade --nodes <worker-1-ip> --image "${IMAGE}" --preserve

# Worker 2
talosctl upgrade --nodes <worker-2-ip> --image "${IMAGE}" --preserve

# Wait for workers
kubectl --kubeconfig <cloud-kubeconfig> wait \
  --for=condition=Ready node/<worker-1-name> --timeout=600s
kubectl --kubeconfig <cloud-kubeconfig> wait \
  --for=condition=Ready node/<worker-2-name> --timeout=600s
```

### Step 4: Upgrade Kubernetes

After all nodes run the new Talos version, upgrade Kubernetes:

```bash
talosctl upgrade-k8s --nodes <cp-1-ip> --to <K8S_VERSION>
```

Verify:
```bash
kubectl --kubeconfig <cloud-kubeconfig> version
kubectl --kubeconfig <cloud-kubeconfig> get nodes -o wide
```

### Step 5: Verify Upgrade

```bash
# Check all nodes are running new version
talosctl version --nodes <cp-1-ip>,<cp-2-ip>,<cp-3-ip>,<worker-1-ip>,<worker-2-ip>

# Verify cluster health
kubectl --kubeconfig <cloud-kubeconfig> get nodes
kubectl --kubeconfig <cloud-kubeconfig> get pods -A \
  --field-selector=status.phase!=Running,status.phase!=Succeeded

# Verify ArgoCD apps
cd infrastructure/clusters/cloud/scripts
./setup-argocd.sh status
```

### Step 6: Update OpenTofu Variables

After a successful upgrade, set the new versions in the instance values file. The variables have no defaults.

**`infrastructure/clusters/cloud/bootstrap/instance.auto.tfvars`:**
```hcl
talos_version      = "v<TARGET_VERSION>"
kubernetes_version = "<K8S_VERSION>"
```

**`infrastructure/.working/cloud/bootstrap/terraform.tfvars`** (if versions are overridden there):
```hcl
talos_version      = "v<TARGET_VERSION>"
kubernetes_version = "<K8S_VERSION>"
```

Optionally clean up old snapshots:
```bash
# List all Talos snapshots
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" \
  "https://api.hetzner.cloud/v1/images?type=snapshot&label_selector=os=talos" | \
  jq '.images[] | {id, description, labels}'

# Delete old version snapshots (keep current version)
curl -s -X DELETE -H "Authorization: Bearer $HCLOUD_TOKEN" \
  "https://api.hetzner.cloud/v1/images/<old-snapshot-id>"
```

## Version Constraints

Talos only supports upgrades between adjacent minor versions. Multi-version jumps require stepping through each intermediate version.

| From | To | Kubernetes | Notes |
|------|----|------------|-------|
| v1.10.x | v1.11.x | 1.33 -> 1.34 | |
| v1.11.x | v1.12.x | 1.34 -> 1.35 | |
| v1.12.x | v1.13.x | 1.35 -> 1.36 | Check [support matrix](https://www.talos.dev/latest/introduction/support-matrix/) |

> **Current cluster**: run `talosctl version --nodes <cp-1-ip>` and `kubectl --kubeconfig <cloud-kubeconfig> version` to see the versions that the cluster runs now.

At each step: upgrade Talos first (Steps 2-3), then upgrade Kubernetes (Step 4).

See the [Talos support matrix](https://www.talos.dev/latest/introduction/support-matrix/) for the full compatibility table.

## Rollback

If an upgrade fails, Talos automatically boots into the previous version (A/B partition scheme). To manually rollback:

```bash
talosctl rollback --nodes <node-ip>
```

## Finding the Correct Image

The image URL format is:
```
factory.talos.dev/installer/<schematic-id>:<version>
```

The cluster's schematic ID (`<schematic-id>`) includes:
- qemu-guest-agent (for Hetzner Cloud integration)
- hcloud extensions

The schematic ID is in `infrastructure/clusters/cloud/scripts/build-talos-snapshot.sh` and in `infrastructure/clusters/cloud/bootstrap/talos.tf`.

To find the latest version, check:
- https://github.com/siderolabs/talos/releases

## Troubleshooting

### TLS handshake timeout during upgrade

If `talosctl upgrade` fails with:
```
net/http: TLS handshake timeout on https://127.0.0.1:7445
```

This happens when the node's kubelet version is far behind the cluster's K8s version (for example, a kubelet three minor versions behind the cluster). The internal kubelet proxy can't complete TLS negotiation.

**Fix:** Use the `--stage` flag to bypass the drain phase:
```bash
talosctl upgrade --nodes <node-ip> --image "${IMAGE}" --stage --preserve
```

This stages the upgrade and reboots immediately. After the node comes back, run `talosctl upgrade-k8s` to sync the kubelet version.

### Endpoint connectivity from devcontainer

If talosctl times out on the private node IPs, route through a control plane public IP:
```bash
talosctl -e <cp-public-ip> -n <node-ip> upgrade --image "${IMAGE}" --preserve
```

### Node stuck in upgrading state

```bash
# Check upgrade status
talosctl dmesg --nodes <node-ip> | tail -50

# Force reboot if needed
talosctl reboot --nodes <node-ip>
```

### Etcd issues after upgrade

See [etcd-recovery.md](etcd-recovery.md)

### Node won't come back online

1. Check Hetzner console for server status
2. Try power cycling via Hetzner API:
   ```bash
   hcloud server reset <server-id>
   ```
3. If still failing, see [reset-node.md](reset-node.md)

## Related

- [upgrade-kubernetes.md](upgrade-kubernetes.md) - Kubernetes version upgrades
- [reset-node.md](reset-node.md) - Reset a problematic node
- [etcd-recovery.md](etcd-recovery.md) - etcd cluster recovery
- Cloud Bootstrap README (`infrastructure/clusters/cloud/bootstrap/README.md`) - Snapshot + user_data approach
