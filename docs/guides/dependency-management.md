# Dependency Management

Process for keeping dependencies up to date in the manyfold-platform.

## Table of Contents

- [Overview](#overview)
- [Automated Updates (Renovate)](#automated-updates-renovate)
- [Container Image Updates (ArgoCD Image Updater)](#container-image-updates-argocd-image-updater)
- [Infrastructure and Tooling Updates](#infrastructure-and-tooling-updates)
  - [OpenTofu Providers (L6)](#opentofu-providers-l6)
  - [Devcontainer Tools (L7)](#devcontainer-tools-l7)
- [Manual Updates](#manual-updates)
  - [Application Dependencies](#application-dependencies)
  - [Helm Charts](#helm-charts)
  - [Infrastructure Components](#infrastructure-components)
  - [Talos and Kubernetes](#talos-and-kubernetes)
- [Version Policy](#version-policy)
- [Testing Updates](#testing-updates)
- [Rollback Procedures](#rollback-procedures)

## Overview

Dependencies are categorized by how they are updated:

| Category | Tool | Automation | ADR-0025 Layer |
|----------|------|------------|----------------|
| npm packages | pnpm | Renovate (weekly PRs, auto-merge devDeps) | L4 |
| Maven dependencies | Maven | Renovate (weekly PRs, auto-merge test deps) | L4 |
| Helm charts | ArgoCD | Renovate (weekly PRs via custom regex manager) | L3 |
| Container images | CI deploy jobs | Image tag bump in Git by the deploy job | L5 |
| OpenTofu providers | OpenTofu | Renovate (weekly PRs via terraform manager) | L6 |
| Devcontainer tools | Dockerfile | Renovate (weekly PRs via regex manager) | L7 |
| Talos Linux | talosctl | Renovate notification + manual upgrade | L1 |
| Kubernetes | talosctl | Follows Talos upgrade | L2 |

## Automated Updates (Renovate)

Renovate runs as a **self-hosted CronJob** in the cloud cluster's `platform-renovate` namespace, eliminating GitHub App rate limiting.

- Runs every 4 hours (`0 */4 * * *`)
- Schedule/grouping rules defined in `.github/renovate.json` (weekends, Europe/Copenhagen)
- Groups related dependencies together
- Auto-merges minor/patch updates for dev dependencies
- Creates separate PRs for major updates
- Waits 3 days before proposing updates (stability period)

### Configuration

- **CronJob manifest:** `platform/resources/cloud/renovate/cronjob.yaml`
- **Renovate config:** `.github/renovate.json`
- **Credentials:** The SOPS-managed `git-credentials` secret in the `platform-renovate` namespace

### This Repository

This repository's [`.github/renovate.json`](../../.github/renovate.json) enables only the
`kubernetes` manager, for the plain manifests that pin an upstream image by tag and digest:
the operations Redis, the registry and its mirrors, the synthetic-monitoring Caddy and the
KSOPS component's init container. An installation runs these pins as they are and does not
override them (ADR-0055), so a Renovate pull request here is the only place such an image
changes.

- Renovate runs here only when the installation's executor lists this repository; onboarding
  is off, so this file must exist first.
- A merged bump reaches a cluster when the installation moves its pin to a commit that
  contains it. The installation's render harness shows the image change in that move.
- A bump needs no new `.publish-allow.tsv` row. The Caddy manifest allows any version, and
  the other tags (`7-alpine`, `2`, `3.21`) carry no three-part version for the gate to match;
  Renovate keeps a tag's precision.

### Manual Trigger

`<cloud-kubeconfig>` is the path of the kubeconfig file for the cloud cluster.

```bash
# Trigger a one-off Renovate run
kubectl --kubeconfig <cloud-kubeconfig> create job renovate-manual \
  --from=cronjob/renovate -n platform-renovate

# Watch logs
kubectl --kubeconfig <cloud-kubeconfig> logs -f job/renovate-manual -n platform-renovate
```

### Checking Status

```bash
# List recent Renovate jobs
kubectl --kubeconfig <cloud-kubeconfig> get jobs -n platform-renovate --sort-by=.metadata.creationTimestamp

# Check CronJob schedule
kubectl --kubeconfig <cloud-kubeconfig> get cronjob renovate -n platform-renovate
```

### Reviewing Renovate PRs

1. Check the changelog/release notes linked in the PR
2. Review for breaking changes
3. For major updates: render the change and run the affected application's tests before merging (see [Testing Updates](#testing-updates))
4. Merge via squash merge

## Container Image Updates (ArgoCD Image Updater)

> **2026-09-23:** ArgoCD Image Updater is not deployed; its unlisted Application file and the
> annotations on four Applications were removed. CI deploy jobs bump every image tag. The
> section below describes the earlier design.

ArgoCD Image Updater monitors container registries for new tags and writes updates to Git:

- **Watched registries:** ghcr.io (website-backend, website-frontend)
- **Update strategy:** Latest tag matching `main-<sha>` pattern
- **Write-back method:** Git commit to `main` branch
- **ArgoCD sync:** Automatic after git commit

In that design it complemented the CI deploy workflow: the CI engine built and pushed images, and ArgoCD Image Updater detected matching `main-<sha>` tags and wrote them back for annotated applications.

## Infrastructure and Tooling Updates

### OpenTofu Providers (L6)

Renovate detects version constraints in `infrastructure/clusters/cloud/bootstrap/versions.tf`:

Updates appear as grouped PRs labeled `infrastructure`. Always review changelogs before merging -- provider updates can change API behavior.

### Devcontainer Tools (L7)

Renovate detects version ARGs in `.devcontainer/Dockerfile` for tools with GitHub releases:
- ArgoCD CLI
- SOPS
- age

Updates appear as grouped PRs labeled `dev-tooling`. After merging, rebuild the devcontainer.

## Manual Updates

### Application Dependencies

#### Backend (Maven)

```bash
cd apps/website/backend

# Check for updates
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates

# Update specific dependency
# Edit pom.xml manually

# Test
mvn clean verify
```

#### Frontend (npm)

```bash
cd apps/website/frontend

# Check for updates
pnpm outdated

# Update all
pnpm update

# Update specific package
pnpm update <package-name>

# Test
pnpm lint && pnpm test:unit
```

### Helm Charts

Each Helm-based component has one ArgoCD Application on the cloud cluster. The Application pins the chart version: `targetRevision` on the source that names the `chart` and its `repoURL`. It also lists the values files, which live in the component's directory under `platform/components/<component>/` (`values-cloud.yaml` and any overlay files). Renovate raises these bumps itself through the same pin, so a manual update is for a chart Renovate does not track or a bump you need before its schedule.

To update:

1. Find the component's Application and read the chart source's current `targetRevision`
2. Read the chart's changelog and the diff of its default values (`helm show values <chart> --repo <repoURL> --version <version>`) between the pinned and the target version. For an OCI chart source (a `repoURL` without a scheme, such as a registry path), use `helm show values oci://<repoURL>/<chart> --version <version>` instead
3. Update `targetRevision` in the Application for that chart
4. Adjust the component's values files under `platform/components/<component>/` where the chart renamed or moved a key
5. Commit and push; ArgoCD syncs the Applications that have automated sync
6. Monitor ArgoCD for sync status and the component's health

### Infrastructure Components

#### ArgoCD

Version is set in the bootstrap script `infrastructure/clusters/cloud/scripts/setup-argocd.sh`.

To upgrade:

```bash
# Update ARGOCD_VERSION in the script
# Then run:
./setup-argocd.sh install
```

### Talos and Kubernetes

**IMPORTANT**: Talos only supports upgrades between adjacent minor versions.

Check the versions the cluster runs before you choose the next minor version: `talosctl version --nodes <cp-1-ip>` and `kubectl --kubeconfig <cloud-kubeconfig> version`. The configured versions are the `talos_version` and `kubernetes_version` values in the cloud OpenTofu root's `instance.auto.tfvars`; the upgrade procedure updates them after a successful upgrade.

Example upgrade path: from v1.N.x, upgrade to v1.(N+1).x before any later minor version.

See [docs/runbooks/talos/upgrade-os.md](../runbooks/talos/upgrade-os.md) for the full procedure.

## Version Policy

| Component | Policy |
|-----------|--------|
| npm devDependencies | Auto-merge minor/patch |
| npm dependencies | Manual review |
| Maven test deps | Auto-merge patch |
| Maven deps | Manual review |
| Helm charts | Manual review |
| Infrastructure | Manual review, staged rollout |
| Talos/K8s | Manual, incremental upgrades only |

## Testing Updates

The Applications on the cloud cluster track the repository's default branch: their Git sources use `targetRevision: HEAD`. A pushed feature branch therefore does not reach the cluster; ArgoCD applies a change only after it is merged. There is no local cluster to try a change on first.

### Before Merge

1. Create a feature branch and apply the update
2. Render the change:
   - Chart bump: `helm template <release> <chart> --repo <repoURL> --version <version> --namespace <destination namespace> -f <values file> [-f <values file> ...]`, with one `-f` for every entry of the Application's `valueFiles`, in the listed order, so the render matches ArgoCD's. An entry such as `$values/<path>` refers to the Git source with `ref: values`; pass it as `<path>` in the repository checkout. For an OCI source, use `oci://<repoURL>/<chart>` in place of `<chart> --repo <repoURL>`. An Application that carries its values inline (`helm.valuesObject` or `helm.values`) instead of `valueFiles` needs those values written to a temporary file and passed with `-f` as well; without them the render shows the chart's defaults, not what ArgoCD deploys
   - Manifest change: `kubectl kustomize <directory>`
   - Secrets directory: it runs the KSOPS generator, which needs `kustomize build --enable-alpha-plugins --enable-exec <directory> > /dev/null` with `ksops` on the `PATH` and the age key available. Discard the output, because it holds the decrypted secrets
3. For a change to an Application or to the files it renders, run the installation's render harness before and after the change and compare the two renders (a render into a fresh directory each time, then its `--compare`)
4. Run the affected application's own checks (see [Application Dependencies](#application-dependencies))

### After Merge

1. Monitor ArgoCD sync status
2. Check pod health: `kubectl --kubeconfig <cloud-kubeconfig> get pods -A`
3. Verify endpoints respond
4. Check observability stack for alerts

## Rollback Procedures

### ArgoCD-managed Components

Almost every Application has automated sync with self-heal, so Git is the only durable way back. `argocd app rollback` refuses to run while automated sync is enabled, and a manual change to the live resources is reverted by self-heal at the next reconciliation. Roll back in Git instead:

1. Revert the commit that introduced the change (a chart bump, a values change, a manifest change): `git revert <commit-hash>`
2. Push the revert, through a pull request where the branch is protected
3. ArgoCD syncs the reverted state; check the Application's sync status and health

When the revert cannot land fast enough, disabling automated sync is a conscious, temporary step. The Applications are themselves managed by an app-of-apps Application with automated sync and self-heal, which restores a child's sync policy from Git. Disable automated sync on that parent first, then on the child: `argocd app set <parent-app> --sync-policy none` and `argocd app set <app-name> --sync-policy none`. Then bring the child back to its last good state. For a single-source Application, use `argocd app history <app-name>` and `argocd app rollback <app-name> <history-id>`. For a multi-source Application (a chart plus a values source), pin each source's last good revision in a sync instead: `argocd app sync <app-name> --revisions <good-chart-version> --source-positions 1 --revisions <good-commit> --source-positions 2`, with positions counted from 1 in the order of `spec.sources`. Add one `--revisions`/`--source-positions` pair for every source the bad change touched; some Applications have a third source with plain manifests. When the bad revision added resources, neither command removes them by itself: compare the live state with the good revision (`argocd app diff <app-name> --revision <good-commit>` for a single source; for a multi-source Application the same `--revisions`/`--source-positions` pairs as the sync above; a diff against the current, still-bad revision hides them) and add `--prune` to the sync or rollback command, or the leftovers keep running. The diff does not list Secrets, so when the bad revision added one, prune without waiting for the diff to show it. Land the Git revert next. Once Git matches the state you want, re-enable automated sync on the parent (`argocd app set <parent-app> --sync-policy automated --self-heal --auto-prune`); its next sync restores the child's policy from Git. Until then, neither Application follows Git.

### Talos Linux

Talos automatically boots into previous version if upgrade fails. Manual rollback:

```bash
talosctl rollback --nodes <node-ip>
```

### Git-based Rollback

For any other Git-tracked change, the same flow applies: revert the commit, push the revert (through a pull request where the branch is protected), and let ArgoCD sync the Applications that have automated sync.
