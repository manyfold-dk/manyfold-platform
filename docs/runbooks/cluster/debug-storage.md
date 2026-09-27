# Debug Storage Issues

Procedures for diagnosing and resolving storage problems in the cluster.

## Table of Contents

- [Storage Architecture](#storage-architecture)
- [Diagnostic Commands](#diagnostic-commands)
- [Common Issues](#common-issues)
  - [Issue: PVC Stuck in Pending](#issue-pvc-stuck-in-pending)
  - [Issue: Volume Won't Attach](#issue-volume-wont-attach)
  - [Issue: Volume Stuck in Terminating](#issue-volume-stuck-in-terminating)
  - [Issue: Disk Full](#issue-disk-full)
  - [Issue: Volume Performance Issues](#issue-volume-performance-issues)
- [Volume Operations](#volume-operations)
- [Monitoring Storage](#monitoring-storage)
- [Hetzner-Specific Notes](#hetzner-specific-notes)
- [Related](#related)

## Storage Architecture

| Component | Provider | Purpose |
|-----------|----------|---------|
| CSI Driver | Hetzner CSI | Persistent volumes (block storage) |
| StorageClass | hcloud-volumes | Default storage class |
| Volume Type | SSD | All Hetzner volumes are SSD |

## Diagnostic Commands

### Check Storage Components

```bash
# CSI driver pods
kubectl get pods -n kube-system -l app=hcloud-csi

# Storage classes
kubectl get storageclass

# Persistent volumes
kubectl get pv

# Persistent volume claims
kubectl get pvc -A
```

### Check CSI Driver Logs

```bash
# Controller logs
kubectl logs -n kube-system -l app=hcloud-csi,app.kubernetes.io/component=controller

# Node driver logs
kubectl logs -n kube-system -l app=hcloud-csi,app.kubernetes.io/component=node
```

## Common Issues

### Issue: PVC Stuck in Pending

**Symptoms:**
- PVC shows `Pending` status
- Pod waiting for volume

**Diagnosis:**
```bash
# Check PVC status
kubectl describe pvc <pvc-name> -n <namespace>

# Check events
kubectl get events -n <namespace> --sort-by='.lastTimestamp' | grep <pvc-name>
```

**Common Causes and Resolutions:**

1. **No matching StorageClass:**
   ```bash
   # Check if StorageClass exists
   kubectl get storageclass

   # Fix: Specify correct storageClassName in PVC
   ```

2. **CSI driver not running:**
   ```bash
   kubectl get pods -n kube-system -l app=hcloud-csi

   # Fix: Check CSI driver deployment
   kubectl describe deployment -n kube-system hcloud-csi-controller
   ```

3. **Hetzner API issues:**
   ```bash
   # Check controller logs
   kubectl logs -n kube-system -l app=hcloud-csi,app.kubernetes.io/component=controller | tail -50

   # Fix: Verify HCLOUD_TOKEN is valid
   ```

### Issue: Volume Won't Attach

**Symptoms:**
- Pod stuck in `ContainerCreating`
- "volume attachment" errors in events

**Diagnosis:**
```bash
# Check pod events
kubectl describe pod <pod-name> -n <namespace>

# Check VolumeAttachment
kubectl get volumeattachment

# Check node CSI registration
kubectl get csinodes
```

**Resolution:**
1. Check if volume is attached elsewhere:
   ```bash
   kubectl get pv <pv-name> -o yaml | grep -A5 nodeAffinity
   ```

2. Force detach if needed (use carefully):
   ```bash
   kubectl delete volumeattachment <attachment-name>
   ```

3. Check Hetzner Console for volume status

### Issue: Volume Stuck in Terminating

**Symptoms:**
- PV or PVC stuck in `Terminating`
- Can't delete volume

**Diagnosis:**
```bash
# Check for finalizers
kubectl get pvc <pvc-name> -n <namespace> -o jsonpath='{.metadata.finalizers}'
kubectl get pv <pv-name> -o jsonpath='{.metadata.finalizers}'
```

**Resolution:**
1. Ensure no pods are using the volume:
   ```bash
   kubectl get pods -A -o json | jq '.items[] | select(.spec.volumes[]?.persistentVolumeClaim.claimName=="<pvc-name>")'
   ```

2. Remove finalizers if safe:
   ```bash
   kubectl patch pvc <pvc-name> -n <namespace> -p '{"metadata":{"finalizers":null}}'
   kubectl patch pv <pv-name> -p '{"metadata":{"finalizers":null}}'
   ```

### Issue: Disk Full

**Symptoms:**
- Application errors about disk space
- Volume shows high utilization

**Diagnosis:**
```bash
# Check volume usage from pod
kubectl exec -n <namespace> <pod-name> -- df -h

# Check PV capacity
kubectl get pv
```

**Resolution:**
1. **Expand volume** (if StorageClass allows):
   ```bash
   # Edit PVC to request more space
   kubectl edit pvc <pvc-name> -n <namespace>
   # Change spec.resources.requests.storage
   ```

2. **Clean up data** inside the pod:
   ```bash
   kubectl exec -n <namespace> <pod-name> -- du -sh /*
   ```

3. **Add more volumes** for the workload

### Issue: Volume Performance Issues

**Symptoms:**
- Slow read/write operations
- High latency in application

**Diagnosis:**
```bash
# Check IOps from inside pod
kubectl exec -n <namespace> <pod-name> -- sh -c "
  dd if=/dev/zero of=/data/testfile bs=1M count=100 oflag=direct
  rm /data/testfile
"

# Check Hetzner volume metrics
# (In Hetzner Console or via API)
```

**Resolution:**
1. Hetzner SSD volumes have fixed IOps based on size
2. Larger volumes = more IOps
3. Consider using multiple smaller volumes for parallelism

## Volume Operations

### Resize a Volume

```bash
# 1. Edit PVC (StorageClass must allow expansion)
kubectl edit pvc <pvc-name> -n <namespace>
# Increase spec.resources.requests.storage

# 2. Check resize status
kubectl describe pvc <pvc-name> -n <namespace>

# 3. Pod may need restart for filesystem resize
kubectl rollout restart deployment <deployment-name> -n <namespace>
```

### Migrate Data Between Volumes

```bash
# 1. Create new PVC
kubectl apply -f new-pvc.yaml

# 2. Create migration pod
kubectl run migrate --image=alpine --restart=Never -- sleep 3600
kubectl exec migrate -- sh -c "apk add rsync && rsync -av /old/ /new/"

# 3. Update application to use new PVC
# 4. Delete old PVC when confirmed
```

### Snapshot a Volume

> Note: Hetzner CSI doesn't support native snapshots. Use application-level backups.

```bash
# For database volumes, use database dump
kubectl exec -n <namespace> <db-pod> -- pg_dump > backup.sql

# For file volumes, use tar
kubectl exec -n <namespace> <pod> -- tar czf - /data > backup.tar.gz
```

## Monitoring Storage

### Prometheus Metrics

Key storage metrics in Grafana:
- `kubelet_volume_stats_used_bytes`
- `kubelet_volume_stats_capacity_bytes`
- `kubelet_volume_stats_inodes_used`

### Storage Alerts

Example alert (add to observability-alerts):
```yaml
- alert: PersistentVolumeFillingUp
  expr: kubelet_volume_stats_used_bytes / kubelet_volume_stats_capacity_bytes > 0.85
  for: 5m
  labels:
    severity: warning
```

## Hetzner-Specific Notes

### Volume Limits

- Max 16 volumes per server (Hetzner limit)
- Volumes must be in same datacenter as server
- Volumes are zonal (single availability zone)

### Detaching Volumes

If a node dies with attached volumes:

1. Check Hetzner Console for volume status
2. Force detach via Hetzner API:
   ```bash
   hcloud volume detach <volume-id>
   ```
3. Volume can then attach to different node

## Related

- [debug-networking.md](debug-networking.md) - Network troubleshooting
- [drain-node.md](drain-node.md) - Node maintenance (affects volume placement)
- [../disaster-recovery/restore-backup.md](../disaster-recovery/restore-backup.md) - Data recovery
