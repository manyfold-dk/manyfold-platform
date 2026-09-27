# Upgrade Kubernetes Version

Procedure for upgrading the Kubernetes version on a Talos cluster.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Pre-Upgrade Checklist](#pre-upgrade-checklist)
- [Upgrade Procedure](#upgrade-procedure)
  - [Step 1: Upgrade Control Plane](#step-1-upgrade-control-plane)
  - [Step 2: Verify Control Plane](#step-2-verify-control-plane)
  - [Step 3: Upgrade Worker Nodes](#step-3-upgrade-worker-nodes)
  - [Step 4: Verify Complete Upgrade](#step-4-verify-complete-upgrade)
- [Troubleshooting](#troubleshooting)
- [Rollback](#rollback)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<cp-1-ip>` | The private IP address of the first control plane node. |
| `<worker-1-ip>`, `<worker-2-ip>` | The private IP addresses of the worker nodes. |
| `<target-version>` | The Kubernetes version to upgrade to, without the `v` prefix. The Talos support matrix gives the versions that Talos supports. |
| `<node-ip>` | The private IP address of the node that the step names. |

## Prerequisites

- Talos version that supports the target Kubernetes version
- Check compatibility at https://www.talos.dev/latest/introduction/support-matrix/

```bash
# Check current versions
kubectl version
talosctl version --nodes <cp-1-ip>
```

## Pre-Upgrade Checklist

1. **Verify Talos supports target K8s version**
   - Each Talos version supports a range of Kubernetes versions
   - Check the [support matrix](https://www.talos.dev/latest/introduction/support-matrix/)

2. **Review Kubernetes release notes**
   - https://kubernetes.io/releases/
   - Check for deprecated APIs and breaking changes

3. **Verify cluster health**
   ```bash
   kubectl get nodes
   kubectl get pods -A | grep -v Running | grep -v Completed
   ```

4. **Check for deprecated API usage**
   ```bash
   # Install kubent if needed
   brew install kubent

   # Check for deprecated APIs
   kubent
   ```

## Upgrade Procedure

### Step 1: Upgrade Control Plane

Upgrade control plane nodes first (one at a time for safety):

```bash
# Upgrade first control plane node
talosctl upgrade-k8s --nodes <cp-1-ip> --to <target-version>

# The command upgrades all control plane components on that node
# Wait for it to complete before proceeding
```

Talos handles the following automatically:
- kubeadm upgrade
- kubelet upgrade
- Control plane components (API server, controller-manager, scheduler)

### Step 2: Verify Control Plane

```bash
# Check control plane version
kubectl get nodes
kubectl version

# Verify all control plane pods are running
kubectl get pods -n kube-system
```

### Step 3: Upgrade Worker Nodes

Worker kubelet is upgraded automatically when control plane upgrade is applied:

```bash
# Verify worker nodes are also upgraded
kubectl get nodes -o wide

# If workers need manual upgrade
talosctl upgrade-k8s --nodes <worker-1-ip> --to <target-version>
talosctl upgrade-k8s --nodes <worker-2-ip> --to <target-version>
```

### Step 4: Verify Complete Upgrade

```bash
# All nodes should show same version
kubectl get nodes

# Check all system pods
kubectl get pods -n kube-system
kubectl get pods -n argocd
kubectl get pods -n observability
```

## Troubleshooting

### API version deprecation warnings

If pods fail after upgrade due to deprecated APIs:

1. Check which resources are affected:
   ```bash
   kubectl get events -A | grep -i deprecated
   ```

2. Update manifests to use new API versions

3. Force ArgoCD resync:
   ```bash
   argocd app sync <app-name> --force
   ```

### Kubelet won't start after upgrade

```bash
# Check kubelet logs
talosctl logs kubelet --nodes <node-ip>

# Check dmesg for errors
talosctl dmesg --nodes <node-ip> | tail -100
```

### etcd issues during upgrade

See [etcd-recovery.md](etcd-recovery.md)

## Rollback

Kubernetes version rollback is not straightforward. If issues occur:

1. **Restore from etcd backup** (if available)
2. **Rebuild the cluster** using [disaster-recovery/full-rebuild.md](../disaster-recovery/full-rebuild.md)

Prevention is key - always test upgrades in a staging environment first.

## Related

- [upgrade-os.md](upgrade-os.md) - Talos OS upgrades
- [etcd-recovery.md](etcd-recovery.md) - etcd cluster recovery
