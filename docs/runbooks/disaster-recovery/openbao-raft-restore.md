# OpenBao Raft Snapshot Restore

Restore the platform OpenBao instance (`openbao-0` in `platform-ops`) from a
nightly Raft snapshot in the `<snapshot-bucket>` R2 bucket.

> **Scope caveat:** a Raft snapshot restore replaces the *whole* OpenBao state
> (all KV mounts, Transit keys, auth mounts, policies, tokens) with the state at
> snapshot time. For tenant-granular recovery (a single deleted `<tenant>/` key,
> for example) use the tenant carve-out exports instead -- see
> Part 2 of the restore runbook of the tenant. Restore the full snapshot
> only when OpenBao itself is lost or corrupted.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Pre-Action Checklist](#pre-action-checklist)
- [Procedure](#procedure)
  - [Step 1: Fetch the Latest Snapshot](#step-1-fetch-the-latest-snapshot)
  - [Step 2: Copy the Snapshot into the Pod](#step-2-copy-the-snapshot-into-the-pod)
  - [Step 3: Restore](#step-3-restore)
  - [Step 4: Unseal](#step-4-unseal)
- [Verification](#verification)
- [Rollback](#rollback)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<cloud-kubeconfig>` | The absolute path of the kubeconfig file for the cloud cluster |
| `<snapshot-bucket>` | The R2 bucket that holds the OpenBao Raft snapshots |
| `<tenant>` | The name of a tenant, which is also the path of its KV mount |
| `<TIMESTAMP>` | The timestamp in the name of the snapshot that you restore |
| `<privileged-token>` | An OpenBao token with the privileges that the prerequisites name |

## Prerequisites

- `kubectl` with the cloud kubeconfig (`<cloud-kubeconfig>`).
- R2 read credentials for `<snapshot-bucket>`. In-cluster they live
  in the `openbao-backup-credentials` secret (`platform-ops`, keys
  `aws-access-key-id` / `aws-secret-access-key` / `endpoint-url`); `aws` CLI or
  `rclone` locally.
- An OpenBao token privileged for `sys/storage/raft/snapshot-force` (restore is
  a sudo operation). Until the OpenBao root-token and credential-rotation plan
  retires it, this is the root token (SOPS: `openbao-unseal-keys` secret /
  break-glass escrow).
- The **Shamir unseal keys that were current at snapshot time**. A restore
  brings back the seal configuration from the snapshot -- if the keys were
  rekeyed *after* the snapshot was taken, the post-restore instance needs the
  OLD keys, not the current ones. After any planned rekey, keep the superseded
  keys escrowed until every older snapshot inside the retention window has aged
  out.

## Pre-Action Checklist

- [ ] Confirm the failure really requires a full restore (not a tenant-granular
  carve-out restore, not a sealed-but-healthy instance the auto-unseal sidecar
  can recover).
- [ ] Note the current pod state for rollback:
  `kubectl --kubeconfig <cloud-kubeconfig> -n platform-ops get pods -l app.kubernetes.io/name=openbao`
- [ ] Identify which snapshot to restore (usually the newest; older ones are
  retained ~30 days under the bucket's WORM lock).
- [ ] Announce the maintenance window: during the restore, workloads that use
  OpenBao (tenant applications that use Transit, the document-signing service,
  the carve-out job) fail closed.

## Procedure

### Step 1: Fetch the Latest Snapshot

```bash
# Credentials from the in-cluster secret (or from local escrow)
NS_ARGS="--kubeconfig <cloud-kubeconfig> -n platform-ops"
export AWS_ACCESS_KEY_ID=$(kubectl $NS_ARGS get secret openbao-backup-credentials -o jsonpath='{.data.aws-access-key-id}' | base64 -d)
export AWS_SECRET_ACCESS_KEY=$(kubectl $NS_ARGS get secret openbao-backup-credentials -o jsonpath='{.data.aws-secret-access-key}' | base64 -d)
ENDPOINT=$(kubectl $NS_ARGS get secret openbao-backup-credentials -o jsonpath='{.data.endpoint-url}' | base64 -d)

# List and pick the newest snapshot (names are <YYYYMMDD-HHMMSS>.snap)
aws s3 ls s3://<snapshot-bucket>/ --endpoint-url "$ENDPOINT" | sort | tail -5
aws s3 cp s3://<snapshot-bucket>/<TIMESTAMP>.snap ./restore.snap --endpoint-url "$ENDPOINT"
```

### Step 2: Copy the Snapshot into the Pod

If `openbao-0` is not running (total loss), first let ArgoCD recreate the
StatefulSet (`openbao` application) with an empty Raft volume, initialise it if
prompted, and then restore over it -- `-force` overwrites the fresh state.

```bash
kubectl $NS_ARGS cp ./restore.snap openbao-0:/tmp/restore.snap -c openbao
```

### Step 3: Restore

```bash
kubectl $NS_ARGS exec -it openbao-0 -c openbao -- /bin/sh -c '
  export BAO_ADDR=http://127.0.0.1:8200
  export BAO_TOKEN=<privileged-token>
  bao operator raft snapshot restore -force /tmp/restore.snap'
```

`-force` is required because the restoring instance's cluster ID differs from
the snapshot's.

### Step 4: Unseal

The restore seals OpenBao with the seal configuration **from the snapshot**.
The `auto-unseal` sidecar retries with the keys in the `openbao-unseal-keys`
secret; if those are the same generation as the snapshot it recovers on its
own within its poll interval. If the keys were rekeyed after the snapshot,
unseal manually with the escrowed old keys:

```bash
kubectl $NS_ARGS exec -it openbao-0 -c openbao -- bao operator unseal   # x threshold
```

Then update the `openbao-unseal-keys` SOPS secret to the generation that now
matches the running instance, and re-run a rekey if the old generation should
not stay live.

## Verification

```bash
# Seal status and Raft health
kubectl $NS_ARGS exec openbao-0 -c openbao -- bao status

# Tenant KV is back
kubectl $NS_ARGS exec openbao-0 -c openbao -- /bin/sh -c \
  'BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN=<privileged-token> bao kv list <tenant>/'

# Kubernetes auth works end to end: run the nightly backup job once --
# its take-snapshot init container performs a scoped k8s-auth login
kubectl $NS_ARGS create job --from=cronjob/openbao-raft-backup restore-verify
kubectl $NS_ARGS logs job/restore-verify -c take-snapshot -f
kubectl $NS_ARGS delete job restore-verify
```

Also confirm that dependent workloads recover: the Transit encrypt/decrypt of
each tenant application (ADR-0040) and the document-signing service (ADR-0034).

## Rollback

A restore overwrites Raft state, so "rollback" means restoring a different
snapshot: repeat the procedure with an earlier `<TIMESTAMP>.snap` from the
bucket (the WORM `history` retention guarantees ~30 days of choices). The
snapshot you restored over is gone -- which is why Step 1 of the checklist
requires confirming the restore is necessary. Anything written to OpenBao
between snapshot time and the incident must be re-entered (e.g. via the tenant
carve-out exports or re-onboarding runbooks).
