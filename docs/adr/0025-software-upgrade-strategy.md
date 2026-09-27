# ADR 0025: Software Upgrade Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
  - [Upgrade Layers](#upgrade-layers)
  - [Automation Strategy per Layer](#automation-strategy-per-layer)
  - [Upgrade Cadence](#upgrade-cadence)
- [Rationale](#rationale)
  - [Why Renovate over Dependabot?](#why-renovate-over-dependabot)
  - [Why Not Fully Automate Talos/Kubernetes?](#why-not-fully-automate-taloskubernetes)
  - [Why ArgoCD Image Updater for Container Images?](#why-argocd-image-updater-for-container-images)
- [Implementation](#implementation)
  - [Current State](#current-state)
  - [Renovate Configuration](#renovate-configuration)
  - [ArgoCD Image Updater (Planned)](#argocd-image-updater-planned)
  - [Talos Upgrades](#talos-upgrades)
  - [Upgrade Workflow](#upgrade-workflow)
- [Consequences](#consequences)
  - [Positive](#positive)
  - [Negative](#negative)
- [References](#references)

## Status

Accepted

## Context

The Manyfold Platform has dependencies across multiple layers: operating system (Talos Linux), Kubernetes, platform components (Helm charts managed by ArgoCD), application dependencies (Maven, npm), infrastructure-as-code providers (OpenTofu), and developer tooling (devcontainer). Keeping these current is essential for security, stability, and access to new features.

Experience from the Q1 2026 dependency upgrade (PR #78, private tracker) revealed several pain points:

1. **Talos only supports upgrades between adjacent minor versions** -- a direct jump from v1.9.5 to v1.12.1 failed and auto-rolled back. This requires careful incremental upgrade planning.
2. **Manual Helm chart version bumps** across both local and cloud ArgoCD application manifests are tedious and error-prone.
3. **No automation existed** for detecting or proposing dependency updates.
4. **Bootstrap infrastructure** (rescue mode + dd) was fragile and non-idempotent, making OS-level upgrades riskier.

This ADR establishes a unified strategy for software upgrades across all layers, maximizing automation where safe and enforcing manual review where necessary.

## Decision

We adopt a **layered upgrade strategy** with automation appropriate to each layer's risk profile. The guiding principle is: **automate detection and proposal everywhere, automate execution only where rollback is trivial**.

### Upgrade Layers

| Layer | Components | Risk | Automation Level |
|-------|-----------|------|-----------------|
| **L1: OS / Node** | Talos Linux | High | Manual execution, automated detection |
| **L2: Kubernetes** | kube-apiserver, kubelet, etc. | High | Manual execution, automated detection |
| **L3: Platform** | Helm charts (ArgoCD, cert-manager, ingress-nginx, observability stack, Tekton) | Medium | Automated PRs via Renovate, manual merge |
| **L4: Application** | Maven dependencies, npm packages | Low-Medium | Automated PRs via Renovate, auto-merge for low-risk |
| **L5: Container Images** | Application container tags | Low | Automated via ArgoCD Image Updater (planned) |
| **L6: IaC Providers** | OpenTofu providers (hcloud, cloudflare, talos) | Medium | Automated PRs via Renovate, manual merge |
| **L7: Dev Tooling** | Devcontainer, Kind, CLI tools | Low | Automated PRs via Renovate, manual merge |

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); Kind is no longer part of L7.

### Automation Strategy per Layer

**L1-L2: Talos + Kubernetes (manual execution)**
- Renovate monitors Talos GitHub releases and opens an issue/PR when a new minor version is available.
- Upgrades are performed manually using `talosctl upgrade` following the incremental path (adjacent minor versions only).
- New Talos versions are provisioned as Hetzner snapshots via `hcloud-upload-image` for future node replacements. See the Talos snapshot migration plan of 2026-01-30 (private).
- Kubernetes version upgrades follow Talos upgrades via `talosctl upgrade-k8s`.

**L3: Platform components (automated PRs, manual merge)**
- Renovate detects Helm chart version updates in `platform/argocd/*/applications/*.yaml` via custom regex manager.
- Minor/patch updates are grouped into a single weekly PR.
- Major updates get separate PRs with `major-update` label for careful review.
- After merge, ArgoCD auto-syncs the new versions to both local and cloud clusters.

**L4: Application dependencies (automated PRs, selective auto-merge)**
- Renovate creates grouped PRs for Maven and npm dependencies weekly.
- npm `devDependencies` minor/patch updates auto-merge.
- Maven test dependency patch updates auto-merge.
- All other dependency updates require manual review.

**L5: Container images (automated, planned)**
- ArgoCD Image Updater will monitor container registries for new tags matching a policy (e.g., semver).
- It writes updated image tags back to Git, which ArgoCD syncs.
- This closes the loop for application deployments without manual intervention.

**L6: IaC providers (automated PRs, manual merge)**
- Renovate detects OpenTofu provider version updates in `.tf` files.
- All IaC updates require manual review due to potential breaking changes.

**L7: Dev tooling (automated PRs, manual merge)**
- Renovate detects version updates in Dockerfile, `dev-shell.sh`, and `package.json` tooling.
- Updates are reviewed and merged manually.

### Upgrade Cadence

| Schedule | What |
|----------|------|
| **Weekly** (weekends) | Renovate scans and opens PRs for L3-L7 |
| **Monthly** | Review and merge accumulated Renovate PRs |
| **Quarterly** | Evaluate Talos/Kubernetes minor version upgrades (L1-L2) |
| **Immediately** | Security patches at any layer (CVEs) |

## Rationale

### Why Renovate over Dependabot?

| Aspect | Renovate | Dependabot |
|--------|----------|------------|
| **Custom file formats** | Regex managers for any file | Limited to supported ecosystems |
| **Helm chart detection** | Custom regex in ArgoCD YAML | Not supported natively |
| **PR grouping** | Flexible grouping rules | Limited grouping |
| **Auto-merge** | Configurable per dependency type | Basic auto-merge |
| **Scheduling** | Cron-like, timezone-aware | Limited scheduling |
| **Self-hosted option** | Yes (CronJob in-cluster) | GitHub-only |
| **OpenTofu support** | Via Terraform manager | Via Terraform manager |

Renovate's custom regex managers are essential for detecting Helm chart versions embedded in ArgoCD Application YAML files -- a format Dependabot cannot parse.

### Why Not Fully Automate Talos/Kubernetes?

1. **Adjacent-version constraint**: Talos requires stepping through every minor version. Automation must enforce this ordering, which is complex to implement safely.
2. **Node-by-node rolling**: Each node must be upgraded individually with health verification between nodes. A failure partway through requires human judgment.
3. **Kubernetes version coupling**: Kubernetes upgrades must follow Talos upgrades and respect the Talos support matrix.
4. **Low frequency**: Talos releases a new minor ~every 3 months. The manual overhead is manageable.
5. **High blast radius**: A botched OS upgrade can take down the cluster. Human oversight is warranted.

Future improvement: A Claude Code skill or script could automate the rolling upgrade sequence with health checks between nodes, reducing manual effort while keeping human approval gates.

### Why ArgoCD Image Updater for Container Images?

ArgoCD Image Updater fits naturally into the GitOps workflow:
- It watches container registries for new image tags.
- It writes updated tags to Git (not directly to the cluster).
- ArgoCD syncs from Git, maintaining the single source of truth.
- It supports semver policies, regex filters, and digest pinning.

Alternative considered: Renovate can also detect container image updates in Kubernetes manifests, but ArgoCD Image Updater is purpose-built for this use case and integrates tighter with the ArgoCD ecosystem.

## Implementation

### Current State

| Layer | Automation | Status |
|-------|-----------|--------|
| Talos/Kubernetes | Renovate notification on new release | Active (self-hosted CronJob) |
| Platform (Helm) | Renovate regex manager (with repoURL capture) | Active (self-hosted CronJob) |
| Application (Maven) | Renovate | Active (self-hosted CronJob) |
| Application (npm) | Renovate | Active (self-hosted CronJob) |
| Container images | ArgoCD Image Updater | Deployed |
| IaC providers | Renovate terraform manager | Active (self-hosted CronJob) |
| Dev tooling | Renovate Dockerfile regex managers | Active (self-hosted CronJob) |

### Renovate Configuration

Renovate runs as a **self-hosted CronJob** in the cloud cluster's `tekton-builds` namespace (`infrastructure/tekton/base/renovate/`). This eliminates GitHub App rate limiting and gives full control over scheduling.

The CronJob:
- Runs every 4 hours (`0 */4 * * *`)
- Uses the existing `git-credentials` secret (PAT with `repo` scope)
- Reads configuration from `.github/renovate.json` in the repository
- Runs on control-plane nodes to avoid consuming worker capacity

The Renovate configuration at `.github/renovate.json` defines:

- **Grouped PRs**: Helm charts, npm, Maven dependencies grouped separately
- **Auto-merge**: npm devDependencies (minor/patch), Maven test deps (patch)
- **Stability window**: 3-day minimum release age before proposing updates
- **Schedule**: Weekends only, Europe/Copenhagen timezone
- **Custom manager**: Regex extraction of Helm `targetRevision` from ArgoCD YAML
- **Exclusions**: Vendored Tekton release.yaml, infrastructure tfvars

### ArgoCD Image Updater (Planned)

Deploy ArgoCD Image Updater as a platform component:

```yaml
# platform/argocd/cloud/applications/argocd-image-updater.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: argocd-image-updater
  namespace: argocd
spec:
  source:
    repoURL: https://argoproj.github.io/argo-helm
    chart: argocd-image-updater
    targetRevision: 0.x.x
  destination:
    namespace: argocd
```

Annotate Application resources to enable image tracking:

```yaml
metadata:
  annotations:
    argocd-image-updater.argoproj.io/image-list: backend=ghcr.io/<owner>/website-backend
    argocd-image-updater.argoproj.io/backend.update-strategy: semver
    argocd-image-updater.argoproj.io/write-back-method: git
```

### Talos Upgrades

Talos upgrades follow a two-part approach:

1. **In-place rolling upgrade** for existing clusters using `talosctl upgrade` (one node at a time, adjacent minor versions only).
2. **Snapshot-based provisioning** for new nodes using `hcloud-upload-image` snapshots with `user_data` config delivery.

The snapshot approach replaces the fragile rescue-mode + dd bootstrap. The Talos snapshot migration plan of 2026-01-30 (private) has the details.

**Upgrade path enforcement**: Always upgrade through each minor version:
```
v1.9.x -> v1.10.x -> v1.11.x -> v1.12.x
```

At each step: upgrade Talos first (all nodes), then upgrade Kubernetes.

### Upgrade Workflow

```
Renovate detects update
  |
  +--> Low risk (L4 dev deps, L5 images)
  |      |
  |      +--> Auto-merge PR / ArgoCD Image Updater commits
  |      +--> ArgoCD syncs to cluster
  |
  +--> Medium risk (L3 Helm, L4 prod deps, L6 IaC, L7 tooling)
  |      |
  |      +--> Renovate opens PR
  |      +--> Human reviews changelog + breaking changes
  |      +--> Squash merge
  |      +--> Cloud pipeline triggers, ArgoCD syncs
  |
  +--> High risk (L1 Talos, L2 Kubernetes)
         |
         +--> Renovate opens issue/notification
         +--> Human plans upgrade window
         +--> Build snapshot: ./build-talos-snapshot.sh vX.Y.Z
         +--> Rolling upgrade: talosctl upgrade (node by node)
         +--> Verify health between each node
         +--> Upgrade Kubernetes: talosctl upgrade-k8s
         +--> Update tfvars + commit
```

## Consequences

### Positive

- **Reduced manual toil**: Renovate handles detection and PR creation for most layers automatically
- **Consistent cadence**: Weekly scans prevent dependencies from falling dangerously behind
- **Risk-appropriate automation**: Auto-merge only where rollback is trivial (dev deps, test deps)
- **Full GitOps compliance**: All changes flow through Git, maintaining audit trail
- **Security**: Faster awareness of CVEs through automated scanning
- **Reproducible infrastructure**: Snapshot-based Talos provisioning eliminates bootstrap fragility

### Negative

- **Renovate noise**: Weekly PRs require attention even when everything is fine
- **Custom manager maintenance**: Regex-based Helm chart detection may break if ArgoCD YAML format changes
- **Multiple tools**: Renovate + ArgoCD Image Updater + manual talosctl is three different upgrade mechanisms
- **Delayed Talos updates**: Quarterly review cadence means Talos may lag 1-2 minor versions behind latest

**Amendment (2026-06-14) -- npm devDependency auto-merge requires a write-token-free job.**
The 2026-06-10 solution review showed that auto-merged npm `devDependencies` (the rule above)
execute lifecycle scripts inside CI. This is acceptable **only where the CI job running npm
grants no write credential** -- no `contents: write` / `packages: write` token, no PAT in env.
The shared baseline's reusable Node CI workflow was job-scoped accordingly (see the private
baseline repository's reusable-CI supply-chain hardening plan of 2026-06-12); the shared Renovate preset
additionally carries `minimumReleaseAge: 7 days` on the rule. Any future workflow that runs
npm with a write token must first disable this auto-merge for itself. This amends, and does
not supersede, the auto-merge decision recorded above.

**Amendment (2026-06-14) -- automerge requires PR-time CI in the consuming repo.**
The same 2026-06-10 review found that Renovate automerges dependency PRs (the Maven test-dep
patch and npm `devDependency` minor/patch rules above) into repos that may run **no PR-time
checks** -- so a broken or malicious update can land and deploy with neither a human nor a
machine in the loop. Automerge is therefore conditional on the consuming repo running PR-time
CI that gates the merge: Renovate keeps the preset default `ignoreTests: false`, so an
automerge-eligible PR merges only after its required checks report green. This works without
branch protection (which is unavailable on GitHub Free, per ADR-0005). **A repo with no
PR-time checks must not enable these automerge rules** -- doing so reproduces the original
finding. manyfold-platform adds the missing PR-time CI for exactly this reason (see the platform
CI supply-chain hardening plan of 2026-06-12 (private), step S5); enrolling a new repo
in the shared Renovate executor before it has PR-time CI is the same hazard. This amends, and
does not supersede, the auto-merge decision recorded above.

## References

- [Renovate Documentation](https://docs.renovatebot.com/)
- [ArgoCD Image Updater](https://argocd-image-updater.readthedocs.io/)
- [Talos Upgrade Documentation](https://docs.siderolabs.com/talos/v1.12/talos-guides/upgrading-talos/)
- [hcloud-upload-image](https://github.com/apricote/hcloud-upload-image)
- Dependency Management Guide (private)
- Talos Snapshot Migration Plan of 2026-01-30 (private)
- Q1 2026 Dependency Upgrade (PR #78, private tracker)
- [ADR-0010: Build System and Dependency Management](0010-build-system-and-dependency-management.md)
- [ADR-0015: Kubernetes Distribution (Talos)](0015-kubernetes-distribution.md)
- [ADR-0016: Infrastructure as Code](0016-infrastructure-as-code-tool.md)
- Renovate config: `.github/renovate.json`
