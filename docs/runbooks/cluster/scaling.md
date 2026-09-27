# Scaling the Cluster

Procedures for adding or removing nodes from the cluster.

## Table of Contents

- [Placeholders](#placeholders)
- [Current Architecture](#current-architecture)
- [Adding a Worker Node](#adding-a-worker-node)
- [Removing a Worker Node](#removing-a-worker-node)
- [Adding a Control Plane Node](#adding-a-control-plane-node)
- [Removing a Control Plane Node](#removing-a-control-plane-node)
- [Scaling Guidelines](#scaling-guidelines)
- [Troubleshooting](#troubleshooting)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<control-plane-ip>` | The IP address of a healthy control plane node that stays in the cluster |
| `<api-load-balancer>` | The name of the Hetzner load balancer in front of the Kubernetes API |

## Current Architecture

| Node Type | Count | Purpose |
|-----------|-------|---------|
| Control Plane | 3 | API server, etcd, scheduler |
| Worker | 3 | Application workloads |

## Adding a Worker Node

### Step 1: Provision Server in Hetzner

Update OpenTofu configuration to add a new worker:

```bash
cd infrastructure/clusters/cloud/bootstrap

# Edit variables to add worker
# Modify worker count or add new worker definition

# Plan changes
source ../../../.env.cloud
tofu plan

# Apply
tofu apply
```

The OpenTofu configuration will:
1. Create new Hetzner server
2. Boot into rescue mode
3. Install Talos via dd
4. Apply machine configuration

### Step 2: Verify Node Joins Cluster

```bash
# Watch for new node
kubectl get nodes -w

# Verify node is Ready
kubectl get nodes
```

### Step 3: Verify Workloads Schedule

```bash
# Check DaemonSets run on new node
kubectl get pods -A -o wide | grep <new-node-name>
```

## Removing a Worker Node

### Step 1: Drain the Node

```bash
# Cordon to prevent new pods
kubectl cordon <node-name>

# Drain existing pods
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data

# Verify pods moved
kubectl get pods -A -o wide | grep <node-name>
```

### Step 2: Delete from Kubernetes

```bash
kubectl delete node <node-name>
```

### Step 3: Destroy Server

```bash
cd infrastructure/clusters/cloud/bootstrap

# Update configuration to remove worker
# Modify worker count or remove worker definition

source ../../../.env.cloud
tofu plan
tofu apply
```

Or manually via Hetzner Console/CLI:
```bash
hcloud server delete <server-name>
```

## Adding a Control Plane Node

> **Warning:** Control plane changes affect etcd and require careful planning.

### Step 1: Provision Server

Similar to worker, but with control plane machine config:

```bash
cd infrastructure/clusters/cloud/bootstrap

# Update configuration to add control plane node
# Edit terraform.tfvars to increase cp count

source ../../../.env.cloud
tofu plan
tofu apply
```

### Step 2: Verify etcd Membership

```bash
# Check etcd members
talosctl etcd members --nodes <control-plane-ip>

# Should show 4 members now
```

### Step 3: Update Load Balancer

If using dedicated API load balancer, add the new node:

```bash
# Check load balancer targets (Hetzner)
hcloud load-balancer describe <api-load-balancer>
```

## Removing a Control Plane Node

> **Warning:** Never reduce control plane below 3 nodes in production.

### Step 1: Verify Cluster Health

```bash
# Must have at least 3 healthy members before removing one
talosctl etcd members --nodes <control-plane-ip>
```

### Step 2: Remove from etcd

```bash
# Get member ID
talosctl etcd members --nodes <control-plane-ip>

# Remove from etcd cluster
talosctl etcd remove-member --nodes <control-plane-ip> <member-id-to-remove>
```

### Step 3: Drain and Delete

```bash
kubectl cordon <node-name>
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data
kubectl delete node <node-name>
```

### Step 4: Destroy Server

```bash
cd infrastructure/clusters/cloud/bootstrap
source ../../../.env.cloud
tofu plan
tofu apply
```

## Scaling Guidelines

### Worker Nodes

- **Minimum**: 1 (for basic workloads)
- **Recommended**: 2+ (for redundancy)
- **Scale up when**: CPU/memory utilization consistently >70%
- **Scale down when**: Resources underutilized for extended periods

### Control Plane Nodes

- **Minimum**: 1 (development only)
- **Recommended**: 3 (production)
- **Maximum**: 5 (rarely needed)
- **Never**: Even numbers (etcd quorum issues)

### Monitoring for Scaling Decisions

Check Grafana dashboards:
- **Node CPU/Memory**: When to add workers
- **API Server Latency**: When to add control plane capacity
- **Pod Pending Time**: Indicates resource constraints

```bash
# Quick resource check
kubectl top nodes
kubectl top pods -A --sort-by=memory
```

## Troubleshooting

### New node stays NotReady

1. Check Cilium is running:
   ```bash
   kubectl get pods -n kube-system -l k8s-app=cilium -o wide
   ```

2. Check node logs:
   ```bash
   talosctl logs kubelet --nodes <new-node-ip>
   ```

### etcd won't accept new control plane

1. Check existing etcd health:
   ```bash
   talosctl etcd members --nodes <control-plane-ip>
   ```

2. Ensure cluster has quorum before adding

### Pods won't schedule on new node

1. Check taints:
   ```bash
   kubectl describe node <node-name> | grep -A5 Taints
   ```

2. Remove unexpected taints:
   ```bash
   kubectl taint nodes <node-name> <taint-key>-
   ```

## Related

- [drain-node.md](drain-node.md) - Safe node draining
- [../talos/reset-node.md](../talos/reset-node.md) - Resetting nodes
- [../disaster-recovery/full-rebuild.md](../disaster-recovery/full-rebuild.md) - Full cluster rebuild
