# Drain Node for Maintenance

Procedure for safely draining workloads from a node before maintenance.

## Table of Contents

- [When to Use](#when-to-use)
- [Procedure](#procedure)
  - [Step 1: Cordon the Node](#step-1-cordon-the-node)
  - [Step 2: Identify Running Workloads](#step-2-identify-running-workloads)
  - [Step 3: Drain the Node](#step-3-drain-the-node)
  - [Step 4: Verify Pods Moved](#step-4-verify-pods-moved)
  - [Step 5: Perform Maintenance](#step-5-perform-maintenance)
  - [Step 6: Uncordon (After Maintenance)](#step-6-uncordon-after-maintenance)
- [Handling Problematic Pods](#handling-problematic-pods)
- [Draining Specific Node Types](#draining-specific-node-types)
- [Batch Operations](#batch-operations)
- [Monitoring During Drain](#monitoring-during-drain)
- [Related](#related)

## When to Use

- Before OS upgrades
- Before node maintenance
- Before removing a node
- Before rebooting a node

## Procedure

### Step 1: Cordon the Node

Prevent new pods from scheduling:

```bash
kubectl cordon <node-name>

# Verify node shows SchedulingDisabled
kubectl get nodes
```

### Step 2: Identify Running Workloads

Check what's running on the node:

```bash
kubectl get pods -A -o wide | grep <node-name>
```

Pay special attention to:
- **StatefulSets**: May need special handling
- **PersistentVolumes**: Data locality considerations
- **DaemonSets**: Will remain (can't be drained)

### Step 3: Drain the Node

```bash
# Standard drain
kubectl drain <node-name> \
  --ignore-daemonsets \
  --delete-emptydir-data

# If pods are stuck, force eviction
kubectl drain <node-name> \
  --ignore-daemonsets \
  --delete-emptydir-data \
  --force
```

### Step 4: Verify Pods Moved

```bash
# Check no pods remain (except DaemonSets)
kubectl get pods -A -o wide | grep <node-name>

# Verify pods rescheduled elsewhere
kubectl get pods -A | grep -v Running | grep -v Completed
```

### Step 5: Perform Maintenance

Now safe to:
- Upgrade Talos: `talosctl upgrade --nodes <node-ip> --image ...`
- Reset node: `talosctl reset --nodes <node-ip> ...`
- Reboot: `talosctl reboot --nodes <node-ip>`
- Remove node: Continue to node deletion

### Step 6: Uncordon (After Maintenance)

When maintenance is complete:

```bash
kubectl uncordon <node-name>

# Verify node accepts scheduling
kubectl get nodes
```

## Handling Problematic Pods

### Pod with Local Storage

```bash
# Error: pod has local storage
kubectl drain <node-name> --delete-emptydir-data
```

### Pod Disruption Budget Blocking Drain

```bash
# Check PDBs
kubectl get pdb -A

# If PDB blocks drain and maintenance is critical
kubectl drain <node-name> --disable-eviction
```

### Stuck Terminating Pods

```bash
# Find stuck pods
kubectl get pods -A | grep Terminating

# Force delete
kubectl delete pod <pod-name> -n <namespace> --force --grace-period=0
```

### Pods with Finalizers

```bash
# Check for finalizers
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.metadata.finalizers}'

# Remove finalizers (be careful!)
kubectl patch pod <pod-name> -n <namespace> -p '{"metadata":{"finalizers":null}}'
```

## Draining Specific Node Types

### Control Plane Node

Control plane pods (API server, etc.) are static and don't need draining:

```bash
# Only drain workload pods
kubectl drain <cp-node-name> --ignore-daemonsets --delete-emptydir-data
```

### Worker Node with StatefulSets

StatefulSets should handle drain gracefully, but verify:

```bash
# Check StatefulSet pods
kubectl get pods -l app=<statefulset-app> -o wide

# Ensure replicas are healthy before draining
kubectl get sts -A
```

## Batch Operations

### Drain Multiple Nodes Sequentially

```bash
for node in worker-1 worker-2; do
  echo "Draining $node..."
  kubectl cordon $node
  kubectl drain $node --ignore-daemonsets --delete-emptydir-data
  echo "Performing maintenance on $node..."
  # ... maintenance ...
  kubectl uncordon $node
  echo "Completed $node"
  sleep 60  # Allow pods to settle
done
```

### Emergency Drain All Workers

```bash
# Cordon all workers first
kubectl cordon -l node-role.kubernetes.io/worker=

# Then drain (pods will pile up on control plane if allowed)
kubectl get nodes -l node-role.kubernetes.io/worker= -o name | \
  xargs -I {} kubectl drain {} --ignore-daemonsets --delete-emptydir-data
```

## Monitoring During Drain

```bash
# Watch pod status
kubectl get pods -A -w

# Check events for issues
kubectl get events -A --sort-by='.lastTimestamp' | tail -20
```

## Related

- [scaling.md](scaling.md) - Adding/removing nodes
- [../talos/upgrade-os.md](../talos/upgrade-os.md) - Talos upgrades
- [../talos/reset-node.md](../talos/reset-node.md) - Node reset procedures
