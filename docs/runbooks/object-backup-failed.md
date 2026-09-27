# Object Backup Failed

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [What The Alert Means](#what-the-alert-means)
- [Procedure](#procedure)
- [Verification](#verification)
- [Rollback](#rollback)

## Placeholders

Replace these placeholders with the values of the failed backup job. The alert labels give most of the values.

| Placeholder | Value |
|---|---|
| `<namespace>` | The namespace of the backup CronJob. The `namespace` label of the alert gives the namespace. |
| `<backup-cronjob>` | The name of the backup CronJob, `object-backup-<instance>`. The `cronjob` label of the alert gives the name. For `ObjectBackupJobFailed`, remove the Job suffix from the `job_name` label. |
| `<backup-app>` | The ArgoCD Application that deploys the backup CronJob. |
| `<backup-bucket-claim>` | The Crossplane `ObjectBucket` claim of the destination bucket. |
| `<backup-credentials-secret>` | The connection Secret of the destination bucket claim. |
| `<r2-provider-credentials-secret>` | The SOPS-managed Secret with the Cloudflare R2 credentials of the Crossplane provider. |
| `<failed-job>` | The name of the failed Job. |

## Prerequisites

- `kubectl` access to the cloud cluster.
- Access to the `<namespace>` namespace.
- Access to the Crossplane `ObjectBucket` claim and Cloudflare R2 provider credentials when provisioning or rotation is required.

## What The Alert Means

`ObjectBackupJobFailed` means that the most recent Job of an object-storage backup CronJob failed. `ObjectBackupStale` means that no run completed successfully in more than 30 hours. `ObjectBackupSuspended` means that the CronJob stays suspended for more than 24 hours after rollout.

The backup Job syncs a source bucket into an EU-jurisdiction R2 destination bucket under `current/`. rclone keeps deleted or overwritten destination objects under `history/<run-id>/`. R2 Bucket Lock protects the objects under `history/`.

The backup Job has two containers. The `rclone-sync` container copies the objects. The `iceberg-validate` container checks the Iceberg metadata of the copy. The `iceberg-validate` container skips the check when the job has no Iceberg table.

## Procedure

### Step 1: Inspect The Latest Jobs

```bash
kubectl -n <namespace> get cronjob <backup-cronjob>
kubectl -n <namespace> get jobs -l app.kubernetes.io/component=object-backup --sort-by=.metadata.creationTimestamp
```

If the CronJob is still suspended after the destination claim is ready, do these steps:

1. Make a Git change that sets `spec.suspend: false` for the CronJob. The base manifest is `platform/resources/cloud/object-backup/base/cronjob.yaml`. The overlay of the job can also patch `spec.suspend`.
2. Merge the change.
3. Sync the `<backup-app>` ArgoCD application.

> **Warning:** The application has `selfHeal: true`. ArgoCD reverts a live patch. Use the live patch only as a short break-glass action.

```bash
kubectl -n <namespace> patch cronjob <backup-cronjob> --type merge -p '{"spec":{"suspend":false}}'
```

### Step 2: Check The Failed Logs

```bash
kubectl -n <namespace> logs job/<failed-job> -c rclone-sync
kubectl -n <namespace> logs job/<failed-job> -c iceberg-validate
```

Common causes:

- Source or destination credentials expired.
- **`403 SignatureDoesNotMatch` on the destination (R2) bucket** -- an operator rolled the R2 S3 token
  secret but did not re-sync the `<r2-provider-credentials-secret>` SOPS secret. The composition
  still emits the old `secret-key`. A `SignatureDoesNotMatch` (not `InvalidAccessKeyId`) means that
  the access-key id is still valid and only the secret is stale. To fix:
  1. Update `secret-key` in **every** SOPS copy of `<r2-provider-credentials-secret>` in
     `platform/resources/cloud/secrets/`. The installation can keep one copy per namespace.
  2. Let the `cloud-secrets` application sync.
  3. Force-reconcile the R2 `Workspace`s.
  4. Re-run the backup (Step 4).

  The R2-secret-rotation checklist is in the backup and restore hardening plan of the installation.
- The Crossplane destination bucket claim or connection secret is not ready.
- The source object storage or the Cloudflare R2 endpoint is unreachable.
- The Iceberg metadata points to a missing data file.

### Step 3: Check The Destination Bucket Claim

Do this step if the job uses a Crossplane `ObjectBucket` claim for the destination bucket.

```bash
kubectl -n <namespace> get objectbucket <backup-bucket-claim>
kubectl -n <namespace> describe objectbucket <backup-bucket-claim>
kubectl -n <namespace> get secret <backup-credentials-secret>
```

If the claim is not ready, check the managed OpenTofu workspace and the Cloudflare R2 provider credentials before you re-run the backup.

### Step 4: Re-Run Manually

```bash
kubectl -n <namespace> create job --from=cronjob/<backup-cronjob> object-backup-manual
kubectl -n <namespace> wait --for=condition=complete job/object-backup-manual --timeout=600s
```

## Verification

```bash
kubectl -n <namespace> logs job/object-backup-manual -c rclone-sync | tail
kubectl -n <namespace> logs job/object-backup-manual -c iceberg-validate | tail
```

Expect `[backup] done` from `rclone-sync`. Expect `[validate] ok` from `iceberg-validate`, or `[validate] no ICEBERG_TABLE configured; skipping` for a job without an Iceberg table.

## Rollback

Keep the CronJob suspended while you investigate repeated failures:

```bash
kubectl -n <namespace> patch cronjob <backup-cronjob> --type merge -p '{"spec":{"suspend":true}}'
```

The suspended CronJob starts no scheduled backups. The suspension does not delete `current/` or `history/` data from R2.
