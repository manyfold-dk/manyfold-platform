# Velero Operations

Procedures for managing Velero backups and restores with Cloudflare R2 storage.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Architecture Overview](#architecture-overview)
- [Backup Operations](#backup-operations)
  - [List Backups](#list-backups)
  - [Describe Backup](#describe-backup)
  - [Verify Backup](#verify-backup)
  - [On-Demand Backup](#on-demand-backup)
- [Restore Procedures](#restore-procedures)
  - [Full Cluster Restore](#full-cluster-restore)
  - [Namespace Restore](#namespace-restore)
  - [Resource-Specific Restore](#resource-specific-restore)
  - [Restore to Different Namespace](#restore-to-different-namespace)
- [Schedule Management](#schedule-management)
- [Troubleshooting](#troubleshooting)
- [Quarterly DR Drill](#quarterly-dr-drill)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster. |
| `<primary-bucket>` | The R2 bucket that holds the Velero backups. |
| `<replica-bucket>` | The R2 bucket that holds the nightly replica of the primary bucket. |
| `<replica-credentials-secret>` | The Secret in the `velero` namespace that holds the replica R2 token for rclone. |
| `<velero-credentials-secret>` | The Secret in the `velero` namespace that holds the R2 credentials of the primary backup location. |
| `<backup-location>` | The name of the primary `BackupStorageLocation`. The Velero values define the name. |

## Prerequisites

- `velero` CLI installed (same version as server)
- Kubeconfig configured: `export KUBECONFIG=<cloud-kubeconfig>`
- Access to the cluster with admin permissions

Install Velero CLI:
```bash
# macOS
brew install velero

# Verify version matches server
velero version
```

## Architecture Overview

Velero is deployed with the following configuration:

| Component | Details |
|-----------|---------|
| Velero Server | Runs on worker nodes (v1.18.0) -- moved off control plane April 2026 |
| Node Agent | DaemonSet on worker nodes for file-level (Kopia) PVC backups |
| Backup Storage | Cloudflare R2 (`<primary-bucket>` bucket, jurisdiction=eu) |
| Volume Snapshots | **Not functional.** External-snapshotter v8.4.0 and a `hcloud-snapshots` VolumeSnapshotClass are installed, but Hetzner CSI lacks `ControllerCreateSnapshot`. All PVC backups use file-level (Kopia) backup via the node-agent instead -- see [ADR-0020](../../adr/0020-backup-strategy.md#implementation). |

**Note:** The `velero` namespace uses privileged pod security because node-agent requires hostPath volumes for Kopia file-level backups.

## Backup Operations

### List Backups

```bash
# List all backups
velero backup get

# List with schedule filter
velero backup get --selector velero.io/schedule-name=daily-backup

# List recent backups
velero backup get --output wide
```

### Describe Backup

```bash
# Get backup details
velero backup describe <backup-name>

# Include resource details
velero backup describe <backup-name> --details

# Check backup logs
velero backup logs <backup-name>
```

### Verify Backup

Check backup contents and completeness:

```bash
# List included namespaces
velero backup describe <backup-name> | grep -A 10 "Included Namespaces"

# Check for errors
velero backup describe <backup-name> | grep -E "(Errors|Warnings)"

# Verify volume backups
velero backup describe <backup-name> --details | grep -A 20 "PVCs"
```

### On-Demand Backup

Create a backup before major operations (upgrades, migrations):

```bash
# Full backup (all namespaces except kube-system)
velero backup create pre-upgrade-$(date +%Y%m%d) \
  --exclude-namespaces kube-system \
  --ttl 168h \
  --wait

# Specific namespace backup
velero backup create observability-backup-$(date +%Y%m%d) \
  --include-namespaces observability \
  --ttl 168h \
  --wait

# Check backup status
velero backup describe pre-upgrade-$(date +%Y%m%d)
```

## Restore Procedures

> **Restore-from-replica:** if the primary `<primary-bucket>` bucket is lost
> or tampered with, the nightly replica holds a second copy: create a read-only
> backup location against `<replica-bucket>/current` --
>
> ```bash
> # Velero needs AWS-INI-format credentials; build them from the replica token
> # (<replica-credentials-secret> holds the same token in per-key format for rclone)
> kubectl -n velero create secret generic velero-replica-bsl-credentials \
>   --from-literal=cloud="[default]
> aws_access_key_id=<replica access-key>
> aws_secret_access_key=<replica secret-key>"
>
> velero backup-location create replica \
>   --provider aws \
>   --bucket <replica-bucket> \
>   --prefix current \
>   --config region=auto,s3ForcePathStyle=true,s3Url=<replica-endpoint> \
>   --credential=velero-replica-bsl-credentials=cloud \
>   --access-mode ReadOnly
> velero backup get   # now lists the replicated backups too
> ```
>
> The `history/<run-id>/` prefixes are the WORM (30-day locked) fallback for
> ransomware / operator error: objects the nightly sync overwrote or deleted
> survive there and can be copied back into `current/` layout with rclone.
> See the 2026-07-19 amendment in [ADR-0020](../../adr/0020-backup-strategy.md).

> **Kopia repository password required:** file-level (fs-backup) restores need
> the `velero-repo-credentials` secret (key `repository-password`) present in
> the `velero` namespace *before* the restore runs. It is SOPS-managed in the
> installation's secrets directory (`platform/resources/cloud/secrets/`) and
> synced by the cloud-secrets ArgoCD app; on a rebuilt cluster make sure the
> secrets app has synced first. The value is recoverable via the break-glass
> coverage model (escrowed age key + the SOPS-encrypted file in Git); there is
> deliberately no separate plaintext escrow. Without it, every fs-backup is
> unrestorable. Resource-only restores (no PVC data) do not need it.

### Full Cluster Restore

> **Warning:** Full restore should only be used after complete cluster rebuild.
> See [full-rebuild.md](full-rebuild.md) for cluster provisioning steps first.

After cluster is ready with ArgoCD:

```bash
# List available backups
velero backup get

# Restore entire backup
velero restore create full-restore-$(date +%Y%m%d) \
  --from-backup <backup-name> \
  --wait

# Check restore status
velero restore describe full-restore-$(date +%Y%m%d)
```

### Namespace Restore

Restore a specific namespace (e.g., after accidental deletion):

```bash
# Restore single namespace
velero restore create observability-restore \
  --from-backup <backup-name> \
  --include-namespaces observability \
  --wait

# Restore multiple namespaces
velero restore create multi-ns-restore \
  --from-backup <backup-name> \
  --include-namespaces observability,argocd \
  --wait

# Check restore status
velero restore describe observability-restore
velero restore logs observability-restore
```

### Resource-Specific Restore

Restore specific resource types:

```bash
# Restore only PVCs and PVs
velero restore create pvc-restore \
  --from-backup <backup-name> \
  --include-resources pvc,pv \
  --wait

# Restore only ConfigMaps and Secrets
velero restore create config-restore \
  --from-backup <backup-name> \
  --include-resources configmap,secret \
  --include-namespaces <namespace> \
  --wait
```

### Restore to Different Namespace

Test restore without affecting production:

```bash
# Restore to a test namespace
velero restore create test-restore \
  --from-backup <backup-name> \
  --include-namespaces observability \
  --namespace-mappings observability:restore-test \
  --wait

# Verify restored resources
kubectl get pods -n restore-test
kubectl get pvc -n restore-test

# Clean up test namespace
kubectl delete ns restore-test
```

## Schedule Management

View and manage backup schedules:

```bash
# List schedules
velero schedule get

# Describe schedule
velero schedule describe daily-backup

# Pause schedule (e.g., during maintenance)
velero schedule pause daily-backup

# Resume schedule
velero schedule unpause daily-backup

# Manually trigger scheduled backup
velero backup create manual-daily-$(date +%Y%m%d) \
  --from-schedule daily-backup
```

## Troubleshooting

### Check Velero Components

```bash
# Check Velero pods
kubectl get pods -n velero

# Check Velero logs
kubectl logs -n velero deployment/velero -f

# Check node-agent (for volume backups)
kubectl logs -n velero daemonset/node-agent -f
```

### Backup Storage Location Issues

```bash
# Check BSL status
velero backup-location get

# Describe BSL
velero backup-location describe <backup-location>

# Test R2 connectivity (from velero pod)
kubectl exec -n velero deployment/velero -- \
  velero backup-location check <backup-location>
```

### Common Issues

**Backup stuck in "InProgress":**
```bash
# Check for stuck backups
velero backup get | grep InProgress

# Delete stuck backup
velero backup delete <stuck-backup-name>
```

**Volume backup failing:**
```bash
# Check node-agent pods
kubectl get pods -n velero -l name=node-agent

# Check node-agent logs
kubectl logs -n velero -l name=node-agent --tail=100
```

**BSL shows "Unavailable":**
```bash
# Check credentials secret
kubectl get secret <velero-credentials-secret> -n velero -o yaml

# Verify R2 endpoint in values
# Check platform/components/velero/values-cloud.yaml for correct s3Url
```

**"Why isn't my PVC backup using CSI snapshots?"**

It never will on this cluster -- CSI volume snapshots are not a working backup path
here. The `external-snapshotter` controller and `hcloud-snapshots`
VolumeSnapshotClass are installed (they were evaluated during initial Velero setup),
but the Hetzner CSI driver does not implement `ControllerCreateSnapshot`, so any
attempt to use them fails. Every PVC backup in this cluster goes through Velero's
file-level (Kopia) backup instead, driven by the `backup.velero.io/backup-volumes`
pod annotation -- see [ADR-0020](../../adr/0020-backup-strategy.md#implementation).
If a PVC isn't being backed up, check the pod annotation, not the CSI/snapshot
stack:

```bash
# Confirm the pod carries the fs-backup opt-in annotation
kubectl get pod <pod-name> -n <namespace> -o jsonpath='{.metadata.annotations.backup\.velero\.io/backup-volumes}'

# Check node-agent (Kopia) logs for the volume
kubectl logs -n velero daemonset/node-agent -f
```

**Node-agent not starting (PodSecurity violation):**
```bash
# Verify velero namespace has privileged PSS
kubectl get ns velero --show-labels | grep pod-security

# Should show: pod-security.kubernetes.io/enforce=privileged
# If not, check platform/resources/cloud/velero/namespace.yaml
```

### View Velero Metrics

```bash
# Port-forward to Prometheus
kubectl port-forward -n observability svc/prometheus-operated 9090:9090

# Query Velero metrics
# velero_backup_total
# velero_backup_success_total
# velero_backup_failure_total
# velero_backup_last_successful_timestamp
```

## Quarterly DR Drill

Perform quarterly to validate backup and restore procedures:

### Pre-Drill Checklist

- [ ] Schedule maintenance window
- [ ] Notify stakeholders
- [ ] Create fresh on-demand backup
- [ ] Document current cluster state

### Drill Procedure

1. **Create test backup:**
   ```bash
   velero backup create dr-drill-$(date +%Y%m%d) \
     --include-namespaces observability,website \
     --ttl 72h \
     --wait
   ```

2. **Restore to test namespace:**
   ```bash
   velero restore create dr-drill-restore \
     --from-backup dr-drill-$(date +%Y%m%d) \
     --include-namespaces website \
     --namespace-mappings website:dr-drill-website \
     --wait
   ```

3. **Verify restored resources:**
   ```bash
   kubectl get pods -n dr-drill-website
   kubectl get pvc -n dr-drill-website
   kubectl get services -n dr-drill-website
   ```

4. **Test application functionality:**
   ```bash
   # Port-forward and test (adjust based on application)
   kubectl port-forward -n dr-drill-website svc/website-frontend 8080:80
   curl localhost:8080
   ```

5. **Clean up:**
   ```bash
   kubectl delete ns dr-drill-website
   velero backup delete dr-drill-$(date +%Y%m%d)
   ```

### Post-Drill Documentation

Document:
- [ ] Time to complete backup
- [ ] Time to complete restore
- [ ] Any issues encountered
- [ ] Recommendations for improvement

## Related

- [restore-backup.md](restore-backup.md) - General restore procedures
- [full-rebuild.md](full-rebuild.md) - Complete cluster rebuild
- [ADR-0020: Backup Strategy](../../../docs/adr/0020-backup-strategy.md) - Architecture decision
- [Velero Documentation](https://velero.io/docs/)
