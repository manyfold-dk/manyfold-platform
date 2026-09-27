# ArgoCD App Not Converging

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [When This Fires](#when-this-fires)
- [Why Synced and Healthy Are Not Enough](#why-synced-and-healthy-are-not-enough)
- [Git Access Lost](#git-access-lost)
- [Procedure](#procedure)
  - [Step 1: Run the Audit](#step-1-run-the-audit)
  - [Step 2: Identify Which Failure Shape You Have](#step-2-identify-which-failure-shape-you-have)
  - [Step 3: Clear a Stranded Operation](#step-3-clear-a-stranded-operation)
  - [Step 4: Fix the Underlying Cause](#step-4-fix-the-underlying-cause)
- [Verification](#verification)
- [Rollback](#rollback)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<cloud-kubeconfig>` | The absolute path of the kubeconfig file for the cloud cluster |
| `<org>` | The GitHub organisation that owns the repositories that ArgoCD reads |
| `<repo-creds-file>` | The SOPS file in `platform/resources/cloud/secrets/` that holds the `repo-creds-github-<org>` secret |
| `<app-name>` | The name of the ArgoCD application that you examine |
| `<app-namespace>` | The namespace of the workloads of that application |

## Prerequisites

- `kubectl` with the cloud kubeconfig (`<cloud-kubeconfig>`)
- `python3` for the audit script

## When This Fires

| Alert | Meaning |
|-------|---------|
| `ArgoCDAppNotConverging` | OutOfSync for >15m with automated sync on -- a committed change is not reaching the cluster |
| `ArgoCDAppSyncFailing` | At least one failed sync in the last 30m |
| `ArgoCDAppDegraded` | Deployed workload unhealthy for >30m (about the workload, not the sync machinery) |
| `ArgoCDAppTargetStateUnknown` | One or more apps have sync status `Unknown` for >15m -- ArgoCD cannot render the desired state (ComparisonError) |
| `ArgoCDGitAccessLost` | More than half of all apps are `Unknown` for >10m -- ArgoCD cannot read Git. Go to [Git Access Lost](#git-access-lost) |

The first is the important one. It means ArgoCD wants to apply something and
cannot.

## Why Synced and Healthy Are Not Enough

`sync_status` and `health_status` describe desired-vs-live state. Neither says
the sync machinery still works.

In one incident, an application reported `Synced` and `Healthy` continuously
while no sync completed for four months. Every change to that app silently failed to
reach the cluster. It was only noticed because a Gateway label
edit never appeared -- at which point the app went `OutOfSync` and stayed there,
which is exactly what `ArgoCDAppNotConverging` now detects.

A wedged app with nothing pending does no harm, so firing only once a change is
waiting is the correct trigger.

## Git Access Lost

Use this section when `ArgoCDGitAccessLost` fires, or when many apps show
`ComparisonError: ... authentication required`.

The workloads continue to run. No commit reaches the cluster until you repair
the credential.

1. Get the error from one app:

   ```bash
   kubectl --kubeconfig <cloud-kubeconfig> -n argocd get application website \
     -o jsonpath='{.status.conditions[0].message}{"\n"}'
   ```

   If the message is not `authentication required`, the cause is not the
   credential. Go to [Procedure](#procedure).

2. Make sure that only one repo credential exists. A `repository` secret with
   its own password overrides the org-wide `repo-creds` template:

   ```bash
   kubectl --kubeconfig <cloud-kubeconfig> -n argocd get secret \
     -l argocd.argoproj.io/secret-type -o custom-columns=NAME:.metadata.name,TYPE:.metadata.labels.argocd\.argoproj\.io/secret-type
   ```

   Expect only `repo-creds-github-<org>`.

3. Mint a new fine-grained PAT. Set the resource owner to `<org>`. Give
   it Contents read-only on every repo that ArgoCD reads.

4. Put the PAT in the SOPS file. Do this step from the secrets directory:

   ```bash
   cd platform/resources/cloud/secrets
   export SOPS_AGE_KEY_FILE=~/.config/sops/age/keys.txt
   printf %s "$NEW_PAT" | jq -Rs . | sops set --value-stdin \
     <repo-creds-file> '["stringData"]["password"]'
   ```

5. Commit and push the file **before** you apply it to the cluster.

   > **CAUTION:** `cloud-secrets` has `selfHeal`. If you apply first, the
   > next sync writes the old token back from the previous Git HEAD. This
   > occurred on 2026-09-22.

6. Apply the secret, restart the repo server and refresh all apps:

   ```bash
   sops -d <repo-creds-file> | kubectl --kubeconfig <cloud-kubeconfig> apply -f -
   kubectl --kubeconfig <cloud-kubeconfig> -n argocd rollout restart deploy/argocd-repo-server
   for a in $(kubectl --kubeconfig <cloud-kubeconfig> -n argocd get applications -o name); do
     kubectl --kubeconfig <cloud-kubeconfig> -n argocd annotate "$a" argocd.argoproj.io/refresh=hard --overwrite
   done
   ```

7. After 5 minutes, examine the secret again. Make sure that it still holds the
   new token. Then make sure that no app has sync status `Unknown`.

## Procedure

### Step 1: Run the Audit

```bash
./scripts/audit-argocd-sync.py --kubeconfig <cloud-kubeconfig>
```

Exit code 0 means every app has a completed, successful last operation, is
converged, and is not Degraded. Exit code 1 lists the problems by shape.

The script catches two things the alerts cannot see, because ArgoCD does not
export them as metrics:

- `never-finished` -- `operationState.phase` stuck at `Running` with no
  `finishedAt`
- `no-history` -- an app that has never recorded a deployment

> Do **not** build an alert on `argocd_app_info`'s `operation` label. Over the
> seven days spanning the real incident it produced two samples, both after
> manual intervention.

### Step 2: Identify Which Failure Shape You Have

```bash
APP=<app-name>
kubectl --kubeconfig <cloud-kubeconfig> -n argocd get application "$APP" \
  -o jsonpath='sync={.status.sync.status} health={.status.health.status}
phase={.status.operationState.phase} started={.status.operationState.startedAt} finished={.status.operationState.finishedAt}
msg={.status.operationState.message}
'
```

Then check whether syncs are actually completing:

```bash
kubectl --kubeconfig <cloud-kubeconfig> -n argocd get application "$APP" \
  -o json | python3 -c 'import json,sys; d=json.load(sys.stdin); [print(e.get("id"), e.get("deployedAt")) for e in (d.get("status",{}).get("history") or [])]'
```

A single entry with an old date, or a `phase=Running` that started hours ago, is
a stranded operation.

### Step 3: Clear a Stranded Operation

A non-terminal operation blocks every later sync, and a new sync request just
queues behind it. **Deleting `.operation` is not sufficient** -- the controller
leaves `status.operationState` non-terminal.

Force it terminal, which is what `argocd app terminate-op` does:

```bash
kubectl --kubeconfig <cloud-kubeconfig> -n argocd patch application "$APP" \
  --type=merge -p '{"status":{"operationState":{"phase":"Terminating"}}}'
```

The ArgoCD `Application` CRD has no `status` subresource, so this patch writes
`status` directly and the API server keeps the change. Do not add
`--subresource=status`: this CRD has no `/status` endpoint. If you use the
`argocd` CLI with a login, `argocd app terminate-op "$APP"` does the same.

It settles to `Failed` within seconds. Then request a sync:

```bash
kubectl --kubeconfig <cloud-kubeconfig> -n argocd patch application "$APP" \
  --type=merge -p '{"operation":{"initiatedBy":{"username":"runbook"},"sync":{"syncStrategy":{"hook":{}}}}}'
```

### Step 4: Fix the Underlying Cause

Clearing the operation unblocks syncing but does not fix what wedged it. Read
the operation message.

**`waiting for completion of hook ...`** -- the chart ships helm hooks that are
not Jobs (ServiceAccount, RBAC, webhook configs). Combined with
`Replace=true` these never reach a terminal phase. Remove `Replace=true` from the
Application's `syncOptions` unless the app genuinely needs it for oversized CRDs
that exceed the 262144-byte annotation limit. Note that `Replace=true` also
silently overrides `ServerSideApply=true` when both are set.

**`one or more objects failed to apply, reason: namespaces "..." not found`** --
a manifest references a namespace that no longer exists. Usually a stale record
from a decommission; confirm git no longer references it, then sync to clear.

**`already exists`** -- resources are being created rather than applied. Almost
always the same `Replace=true` cause.

## Verification

Sync twice. The second run proves the fix is durable rather than a one-off,
because the first run may have created resources that the second must reconcile
idempotently:

```bash
./scripts/audit-argocd-sync.py --kubeconfig <cloud-kubeconfig>     # expect exit 0
kubectl --kubeconfig <cloud-kubeconfig> -n argocd get application "$APP" \
  -o jsonpath='{.status.operationState.phase} {.status.operationState.message}{"\n"}'
```

Expect `Succeeded successfully synced`. Confirm the workloads did not restart:

```bash
kubectl --kubeconfig <cloud-kubeconfig> get pods -n <app-namespace>
```

Pod ages and restart counts should be unchanged for a no-op sync.

## Rollback

Clearing a stranded operation is not reversible, but it is safe: the operation
being cleared is one that never completed and never will. If a sync then applies
something unwanted, revert the offending commit and let ArgoCD converge -- do not
re-wedge the app deliberately.

If removing `Replace=true` causes a genuine apply failure (oversized CRDs), put
it back and instead set `skipCrds: true` with the CRDs managed by a separate
Application.

## Related

- `BEST-PRACTICES.md`, section "`Replace=true` Wedges Any Chart Whose Hooks Are Not Jobs"
- `scripts/audit-argocd-sync.py` (private, in the instance repository)
- [ADR 0012: Local vs Cloud Environment Parity](../../adr/0012-local-vs-cloud-environment-parity.md)
