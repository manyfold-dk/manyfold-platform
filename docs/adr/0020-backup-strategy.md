# ADR 0020: Backup Strategy with Cloudflare R2

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Implementation](#implementation)
- [References](#references)

## Status

Accepted - Implemented

Amended 2026-07-19: the primary Velero bucket stays prunable (unchanged), and a
locked replica bucket now provides the delete-resistant copy -- see
[Velero Bucket Immutable Replica](#velero-bucket-immutable-replica-amendment-2026-07-19).

## Context

The cloud Kubernetes cluster running on Hetzner Cloud contains persistent data that needs protection against:

1. **Accidental deletion** - Human error deleting resources or namespaces
2. **Data corruption** - Application bugs or failed upgrades corrupting data
3. **Cluster failure** - Complete loss of the Kubernetes cluster
4. **Provider outage** - Hetzner Cloud becoming unavailable (regional or complete)

Current persistent volumes in the cluster (~53GB total critical data):

| Namespace | PVC | Size | Type | Backup Status |
|-----------|-----|------|------|---------------|
| observability | prometheus-db | 10Gi | Critical | Daily + weekly |
| observability | alertmanager-db | 2Gi | Critical | Daily + weekly |
| observability | loki-* | 10Gi | Critical | Daily + weekly |
| observability | tempo-data | 10Gi | Critical | Daily + weekly |
| argocd | argocd-redis | 8Gi | Critical | Daily + weekly |
| keycloak | data-keycloak-postgresql-0 | 2Gi | Critical | Daily (fs-backup via annotation) |
| `<tenant-app>` | `data-<tenant-app>-postgresql-0` | 2Gi | Critical | Daily (fs-backup via annotation) |
| platform-ops | data-openbao-0 | 1Gi | Important | Daily (fs-backup via annotation) + R2 Raft snapshot CronJob |
| registry-system | mirror-* | ~2Gi each | Cache (excluded) | Excluded |

> **Backup strategy (April 2026):** Database PVCs use Velero's opt-in fs-backup via `backup.velero.io/backup-volumes` pod annotations. Only database pods (Keycloak PostgreSQL, a tenant application's PostgreSQL, OpenBao Raft) are annotated — the global `defaultVolumesToFsBackup` remains `false` to avoid backing up 30+ GB of regeneratable observability PVCs (Prometheus, Loki, Tempo) daily. PostgreSQL pods also use Velero pre-backup hooks to run `pg_dump -Fc` before the fs-backup, producing a consistent compressed dump alongside the raw data files.

For true disaster recovery, backups must be stored outside Hetzner infrastructure to survive a complete provider outage.

## Decision

Use **Velero** with **Cloudflare R2** as the backup storage backend for Kubernetes disaster recovery.

### Backup Scope

**Include (daily — critical stateful namespaces):**
- observability (Prometheus, Alertmanager, Loki, Tempo) — resource backup only
- argocd (Redis state) — resource backup only
- website — resource backup only
- keycloak (PostgreSQL) — resource backup + fs-backup with pre-backup `pg_dump` hook
- a tenant application's namespace (PostgreSQL) — resource backup + fs-backup with pre-backup `pg_dump` hook
- platform-ops (OpenBao Raft) — resource backup + fs-backup

**Include (weekly — all namespaces):**
- All namespaces except kube-system — resource backup only

**Exclude (regeneratable/cache):**
- Registry mirror caches (`mirror-docker-io`, `mirror-ghcr-io`, etc.) — excluded via annotation
- Build caches and temporary workspaces
- Observability PVC data (Prometheus metrics, Loki logs, Tempo traces) — regeneratable, not worth daily fs-backup cost

### Object Storage Bucket Backups

Persistent object-storage buckets are backed up separately from Kubernetes PVC/resource backups. Each source bucket gets a tenant-specific backup destination in Cloudflare R2 with `jurisdiction = "eu"`.

The first implementation is a tenant application's storage bucket:

- Source: the application's Hetzner Object Storage bucket in the cluster's Hetzner location.
- Destination: a Cloudflare R2 bucket `<tenant-app>-object-backups` with EU jurisdiction, provisioned by the cloud `ObjectBucket` Crossplane composition.
- Schedule: daily at 03:30 UTC.
- Layout: `current/` mirrors the live source; `history/<run-id>/` stores overwritten or deleted objects.

R2 does not support S3 bucket versioning. Delete protection is implemented with rclone's `--backup-dir history/<run-id>/`, R2 Bucket Lock on the `history/` prefix for 30 days, and a lifecycle rule that expires `history/` objects after 31 days.

The application's Iceberg data is copied in two phases: data files first, then Iceberg metadata pointers. A DuckDB validation container scans the copied Iceberg table after sync so broken metadata references fail the Job.

### Velero Bucket Immutable Replica (amendment 2026-07-19)

The original decision left the primary Velero bucket (`<velero-bucket>`) without R2 lock or lifecycle
because Velero owns pruning through backup TTL. That decision stands -- the
primary must remain prunable. The gap it left (finding F3 of the 2026-06-10
solution review (private): the in-cluster Velero R2 credentials can delete the only DR
copy) is closed by a **WORM second copy**, not by locking the primary:

- Replica bucket `<velero-bucket>-replica` (same `r2-backup-bucket` module,
  tenant-application destination shape): `current/` live mirror plus a `history/`
  prefix under 30-day R2 Bucket Lock with 31-day lifecycle expiry.
- Nightly `object-backup` CronJob overlay in the `velero` namespace (05:00,
  after the nightly backups) syncs primary -> replica using a **read-only**
  token on the primary, so the replica job cannot delete primaries, and a
  replica-scoped write token for the destination.
- Restore-from-replica: `velero backup-location create` pointing at
  `<velero-bucket>-replica/current` (read-only); `history/` is the
  ransomware / operator-error fallback.

The provider-independent second copy question (solution review section 7.6,
Cloudflare concentration) is deliberately **deferred to the umbrella-repo
decision** -- this in-account replica does not preempt it.

### Backup Schedule

- **Daily incremental backups** with 7-day retention
- **Weekly full backups** with 30-day retention
- **On-demand backups** before major cluster operations

## Rationale

### Why Cloudflare R2

| Criteria | Cloudflare R2 | Hetzner Object Storage | AWS S3 |
|----------|---------------|------------------------|--------|
| Geographic independence | Yes (separate provider) | No (same provider) | Yes |
| S3 compatibility | Full | Full | Native |
| Egress fees | None | Standard | High |
| Cost (current volumes) | $0.00 (within R2 free tier; egress free) | ~EUR 5.99/month flat floor (incl. 1 TB storage + 1 TB egress; no sub-TB tier) | ~$1.15/month |
| Infrequent access tier | Yes ($0.01/GB) | No | Yes |

Key advantages of Cloudflare R2:

1. **Provider independence** - Survives complete Hetzner outage
2. **No egress fees** - Free data retrieval during disaster recovery
3. **S3-compatible** - Works with Velero's AWS plugin
4. **Cost-effective** - Infrequent access tier ideal for backups

R2 `jurisdiction = "eu"` has no price premium.

### Why Velero

- Kubernetes-native backup solution
- Supports volume snapshots and resource backups
- Well-maintained with active community
- Flexible scheduling and retention policies
- Supports backup hooks for application consistency

## Consequences

### Positive

- **Disaster recovery** capability for complete provider failure
- **Low cost** - Estimated $1-2/month for current data volumes
- **No egress fees** during restore operations
- **Automated** daily backups reduce operational burden
- **Granular restore** - Can restore specific namespaces or resources

### Negative

- **Additional complexity** - New component to maintain (Velero)
- **Backup latency** - Cross-provider backups slower than local
- **Credential management** - R2 API keys need secure storage
- **Restore testing** - Requires periodic DR drills to validate

### Neutral

- **RTO/RPO tradeoffs** - Daily backups mean up to 24h data loss (acceptable for this workload)

## Implementation

### Deployed Components

| Component | Version | Location |
|-----------|---------|----------|
| Velero | 1.17.1 (Helm chart 11.3.2) | `velero` namespace |
| AWS Plugin | v1.13.1 | Init container |
| External Snapshotter | v8.4.0 | `kube-system` namespace |
| Snapshot Controller | v8.4.0 | `kube-system` namespace |

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Velero Server                            │
│                (runs on worker node)                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────────┐    ┌─────────────────────────────────┐│
│  │  AWS Plugin     │───►│  Cloudflare R2 (<velero-bucket>)    ││
│  │  (S3 compat)    │    │  - Resource backups              ││
│  └─────────────────┘    │  - File-level PVC backups (Kopia)││
│                         └─────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                Node Agent (DaemonSet)                       │
│              (runs on all worker nodes)                     │
├─────────────────────────────────────────────────────────────┤
│  - File-level PVC backups via Kopia (dedup + compression)  │
│  - Opt-in per pod: backup.velero.io/backup-volumes         │
│  - Pre-backup hooks run pg_dump for database consistency   │
│  - Requires privileged pod security (hostPath volumes)     │
└─────────────────────────────────────────────────────────────┘

Note: CSI volume snapshots are NOT supported (Hetzner CSI lacks
ControllerCreateSnapshot). All PVC backups use file-level (Kopia).
```

### Key Configuration

**EU Backup Storage Location (live since 2026-05-30):**
- Name: `r2-backups`
- Provider: AWS (S3-compatible)
- Bucket: `<velero-bucket>`
- Region: `auto` (Cloudflare R2)
- Endpoint: `https://<account-id>.eu.r2.cloudflarestorage.com`

**Schedules:**
- `daily-backup`: Runs at 02:00 UTC, 7-day retention, 6 critical namespaces, fs-backup for annotated database pods
- `weekly-backup`: Runs Sundays at 03:00 UTC, 30-day retention, all namespaces (resource-only)

### Deployment Notes

1. **Velero runs on worker nodes** - Moved off control plane (April 2026) to reduce CP memory pressure.

2. **Privileged namespace required** - The `velero` namespace uses `pod-security.kubernetes.io/enforce: privileged` because node-agent requires hostPath volumes for file-level backups.

3. **CSI snapshots installed but non-functional** - External-snapshotter CRDs and controller are deployed, but Hetzner CSI lacks `ControllerCreateSnapshot`. All PVC backups use Kopia file-level backup instead.

4. **R2 credentials** - Stored as a SOPS-encrypted secret. The EU backup destination uses credentials scoped only to the Velero backup bucket.

4b. **Kopia repository password** - A dedicated repository-password secret (SOPS-managed with the other cloud secrets) replaces Velero's well-known default password for Kopia repositories. The password applies at repository creation only; repositories initialised before the secret existed must be re-initialised to pick it up. The value is recoverable through the break-glass coverage model (the escrowed SOPS age key plus the Git-managed encrypted file); no separate plaintext escrow is kept (decision 2026-07-19, per the umbrella repository's ADR-0002 (private) root-material-only principle). Restore on a fresh cluster requires it before any fs-backup restore works.

5. **EU jurisdiction** - New R2 backup destinations use `jurisdiction = "eu"` and the EU endpoint. See [ADR 0035](0035-eu-data-residency-for-storage.md).

### Object Storage Backup Implementation

Object-storage backups are defined as Kubernetes CronJobs built from the shared
`platform/resources/cloud/object-backup/base/` overlay pattern. Two instances exist:

- **A tenant application** (its own namespace, schedule 03:30): mirrors the
  application's Hetzner bucket, with an Iceberg DuckDB validation step.
- **A tenant's portal photos** (`platform-ops` namespace, schedule 03:45): mirrors the
  tenant's Hetzner photo bucket. No Iceberg layout, so the validate
  container self-skips. Deliberately in `platform-ops`, not the tenant namespace:
  the R2 composition's connection secret carries account-level R2 keys, which must
  never land in a tenant-mountable namespace. The tenant's source-bucket
  credentials are SOPS-mirrored into `platform-ops`
  (a bucket-mirror secret, keep-in-sync note in its template),
  and `platform-ops` has its own namespaced `cloudflare-r2` OpenTofu
  ProviderConfig for the destination claim.

Both follow the same flow:

1. Crossplane provisions the EU-jurisdiction R2 destination bucket and writes the
   connection secret (one per instance).
2. `rclone-sync` initContainer mirrors the Hetzner source bucket to
   `s3://<destination-bucket>/current`.
3. rclone moves destination overwrites/deletes into `history/<run-id>/`.
4. R2 Bucket Lock makes `history/` immutable for 30 days.
5. R2 lifecycle expires `history/` objects after the lock period.
6. (Tenant application only) `iceberg-validate` scans
   the application's Iceberg table under `current/warehouse/` with DuckDB.

Velero's EU replacement bucket has no R2 lock or lifecycle because Velero prunes backups through its TTL; its delete-resistant copy is the locked `<velero-bucket>-replica` bucket (see the 2026-07-19 amendment above). OpenBao's EU snapshot bucket and the tenant carve-out bucket use whole-bucket 30-day lock and 31-day lifecycle expiry because their contents are timestamped and externally retained.

### Files

| Path | Purpose |
|------|---------|
| `platform/argocd/cloud/applications/velero.yaml` | Velero Helm deployment |
| `platform/argocd/cloud/applications/velero-namespace.yaml` | Namespace with privileged PSS |
| `platform/argocd/cloud/applications/velero-schedules.yaml` | Backup schedules |
| `platform/argocd/cloud/applications/snapshot-crds.yaml` | VolumeSnapshot CRDs |
| `platform/argocd/cloud/applications/snapshot-controller.yaml` | Snapshot controller |
| `platform/components/velero/values-cloud.yaml` | Helm values |
| `platform/resources/cloud/secrets/` | R2 credentials and Kopia repository password (SOPS-encrypted); replica destination write token, primary read-only source token and tenant source-bucket credentials mirror (templates) |
| `platform/resources/cloud/object-backup/velero-replica/` | Velero primary-to-replica sync overlay |
| `platform/resources/cloud/openbao/tenant-carveout-backup-cronjob.yaml` | Tenant carve-out export CronJob |
| `infrastructure/crossplane/claims/cloud/` | R2 backup bucket claims, one per backed-up bucket (the portal-photos claim in platform-ops) |
| `infrastructure/crossplane/providerconfigs/cloud/cloudflare-r2-providerconfig-platform-ops.yaml` | platform-ops R2 ProviderConfig |
| `platform/resources/cloud/object-backup/` | Object-storage backup CronJob manifests (base + per-bucket overlays) |
| `platform/argocd/cloud/applications/` | Portal-photos backup ArgoCD app |
| `platform/observability/alerts/object-backup-alerts.yaml` | Object-storage backup alerts (both instances) |
| The object-storage backup runbook (private) | Object-storage backup runbook |
| `platform/resources/cloud/hetzner-csi/volumesnapshotclass.yaml` | Hetzner snapshot class |

### Estimated Costs

Actual PVC data is small: Keycloak PostgreSQL ~70MB, the tenant application's PostgreSQL ~323MB, OpenBao Raft ~4MB. With Kopia dedup/compression and 7-day retention, total R2 storage is well under 1GB.

| Component | Size | Monthly Cost |
|-----------|------|--------------|
| Daily resource backups (7 × ~5MB) | ~35MB | — |
| Daily fs-backups (Kopia dedup, 7-day) | ~500MB | — |
| Weekly resource backups (4 × ~10MB) | ~40MB | — |
| OpenBao Raft snapshots (CronJob) | ~50MB | — |
| **Total R2 storage** | **~600MB-1GB** | **$0.00 (within R2 free tier)** |

R2 free tier includes 10GB storage, 10M Class A ops, and 10M Class B ops per month. Current usage is well within these limits. Egress is always free on R2.

## References

- [Velero Documentation](https://velero.io/docs/)
- [Cloudflare R2 Documentation](https://developers.cloudflare.com/r2/)
- [Velero AWS Plugin](https://github.com/vmware-tanzu/velero-plugin-for-aws)
- Issue #46: Backup Strategy (private repository)
- [ADR 0014: Cloud Provider Selection](0014-cloud-provider-selection.md)
