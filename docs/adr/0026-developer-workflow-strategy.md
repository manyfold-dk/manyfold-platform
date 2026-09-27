# ADR 0026: Developer Workflow Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
  - [Workflow Classification](#workflow-classification)
  - [Branch and ArgoCD Strategy](#branch-and-argocd-strategy)
  - [Claude Code Integration](#claude-code-integration)
- [Workflow Reference](#workflow-reference)
  - [Workflow A: Code-Only Changes](#workflow-a-code-only-changes)
  - [Workflow B: Application + Manifest Changes](#workflow-b-application--manifest-changes)
  - [Workflow C: Platform Changes](#workflow-c-platform-changes)
- [Rationale](#rationale)
- [Implementation Details](#implementation-details)
  - [cluster.sh set-branch Command](#clustersh-set-branch-command)
  - [Pipeline Branch Awareness](#pipeline-branch-awareness)
  - [dev-workflow Skill](#dev-workflow-skill)
- [Consequences](#consequences)
- [Future Considerations](#future-considerations)
- [References](#references)

## Status

Accepted

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); `cluster.sh set-branch` and the local-cluster steps of workflows B and C went with it, and development and verification run against the cloud cluster through the render harness, the per-application tests and reviewed pull requests that Argo CD deploys.

## Context

The Manyfold Platform has two Kubernetes environments: a local Kind cluster for development and a cloud Hetzner/Talos cluster for production. Both are managed by ArgoCD using GitOps principles.

Currently, both environments track `HEAD` (main branch) with no branch separation. This means any commit to main immediately affects both environments. There is no way to test changes on the local cluster in isolation before they reach production.

Additionally, there is no standardized workflow for different types of changes. Application code changes, Kubernetes manifest changes, and platform infrastructure changes all have different risk profiles and testing requirements, but follow the same undifferentiated process.

### Requirements

1. **Local-first development**: Test changes on the local cluster before they reach cloud
2. **Branch isolation**: Feature branches should be testable on the local cluster without affecting cloud
3. **Workflow clarity**: Different types of changes should follow appropriate workflows
4. **Single cluster**: One local Kind cluster, switchable between feature branches
5. **Standard GitOps**: Cloud deploys automatically when PRs merge to main
6. **Claude Code awareness**: The AI assistant should understand and guide workflow selection

## Decision

We will define three developer workflows based on the type of change being made. The local Kind cluster will dynamically track feature branches via a new `cluster.sh set-branch` command. Cloud continues to track `main` and auto-deploy on merge. A new `dev-workflow` Claude Code skill will auto-detect and guide workflow selection.

### Workflow Classification

Changes are classified into three workflows based on which files and directories are affected:

| Workflow | Scope | When to Use |
|----------|-------|-------------|
| **A: Code-only** | Backend/frontend source code | Bug fixes, new endpoints, UI components, tests |
| **B: App + manifests** | Code + K8s manifests (deployments, PVCs, ingress, env vars) | New services, storage changes, deployment config |
| **C: Platform** | ArgoCD apps, Tekton pipelines, Helm values, observability | New platform components, pipeline changes, infra config |

**Detection heuristic:**
- Only `apps/*/backend/src/`, `apps/*/frontend/src/` touched → **Workflow A**
- `apps/*/overlays/`, `apps/*/base/`, K8s manifests touched → **Workflow B**
- `platform/`, `infrastructure/` touched → **Workflow C**

### Branch and ArgoCD Strategy

**Cloud cluster** remains unchanged: always tracks `main`, auto-deploys on merge via GitHub webhook → Tekton pipeline → ArgoCD Image Updater → ArgoCD sync.

**Local cluster** dynamically tracks feature branches:

1. Developer creates a feature branch (via git worktree)
2. Developer pushes the branch to GitHub
3. Developer runs `cluster.sh set-branch feature/example-change` to point local ArgoCD at the branch
4. ArgoCD syncs all applications from the feature branch's version of the manifests
5. After PR merge to main, developer runs cleanup which resets the cluster to main

Only one local Kind cluster runs at a time. When switching between features, run `cluster.sh set-branch` to point at the new branch.

### Claude Code Integration

A new `dev-workflow` skill serves as the recommended (not mandatory) entry point for feature work:

**Step 1 — Workflow detection:**
- Analyzes the implementation plan and task description to determine affected directories
- Proposes a workflow type (A, B, or C) and asks for confirmation

**Step 2 — Environment setup:**
- Creates the shared git worktree via `worktree-session.sh`
- Adds repo-specific environment symlinks needed by the feature
- For Workflow B/C: checks local cluster status, runs `cluster.sh set-branch`
- For Workflow A: notes that dev mode (`mvn quarkus:dev` / `pnpm dev`) is sufficient

**Step 3 — During implementation:**
- Pushes to remote at key milestones (after worktree setup, after each completed task, before verification)
- For Workflow B/C: async background checks of ArgoCD sync status after each push, surfaces errors if detected
- For Workflow C: reminds to run `cluster.sh verify` and include output in PR

**Step 4 — Completion and cleanup:**
- Uses the `finishing-a-development-branch` skill for PR creation
- After PR merge, performs full auto-cleanup:
  - Runs `cluster.sh set-branch main` to reset local cluster
  - Deletes the remote feature branch
  - Cleans up the worktree via `worktree-session.sh cleanup`

## Workflow Reference

### Workflow A: Code-Only Changes

For changes that only touch application source code (backend Java, frontend TypeScript) without modifying Kubernetes manifests.

**Examples:** bug fixes, new API endpoints, UI components, unit tests, code refactoring.

```
1. Create worktree on feature branch
2. Develop with mvn quarkus:dev / pnpm dev
3. Run tests locally (mvn test / pnpm test:unit)
4. Commit, push, create PR to main
5. Merge → cloud auto-deploys
6. Cleanup worktree
```

**Local cluster:** not required. Dev mode provides fast feedback.

**Verification:** unit tests, lint, format checks.

### Workflow B: Application + Manifest Changes

For changes that touch both application code and Kubernetes manifests (deployments, PVCs, ingress rules, environment variables, services).

**Examples:** adding persistent storage (PVC), new service deployments, ingress configuration, health check changes.

```
1. Create worktree on feature branch
2. Push branch to remote
3. cluster.sh set-branch feature/example-change
4. Develop code and manifests
5. Push at milestones → ArgoCD syncs → verify in local cluster
6. Run pipeline if needed (run-pipeline.sh)
7. Commit, push, create PR to main
8. Merge → cloud auto-deploys
9. Cleanup (reset cluster branch, delete remote branch, remove worktree)
```

**Local cluster:** required to validate manifests, PVCs, ingress, and deployment behavior.

**Verification:** unit tests, lint, ArgoCD sync health, pod status, manual smoke test.

### Workflow C: Platform Changes

For changes to platform infrastructure: ArgoCD applications, Tekton pipelines, Helm chart values, observability configuration, cluster bootstrap scripts.

**Examples:** adding a new Helm component, modifying pipeline tasks, updating Grafana dashboards, changing ingress-nginx values.

```
1. Create worktree on feature branch
2. Push branch to remote
3. cluster.sh set-branch feature/example-change
4. Develop platform changes
5. Push at milestones → ArgoCD syncs → verify in local cluster
6. cluster.sh verify (required — include output in PR)
7. Commit, push, create PR to main
8. Merge → cloud auto-deploys
9. Cleanup (reset cluster branch, delete remote branch, remove worktree)
```

**Local cluster:** required. Platform changes can break the entire cluster.

**Verification:** `cluster.sh verify` must pass. Include verify output in PR description or as a comment.

## Rationale

### Why three workflows instead of one?

Different change types have different risk profiles. Code-only changes (Workflow A) are low risk — they don't affect cluster infrastructure and can be validated with unit tests and dev mode. Manifest changes (Workflow B) need a running cluster to validate. Platform changes (Workflow C) can break the entire cluster and need the most rigorous verification.

A single workflow that always requires the local cluster would slow down simple code changes. A workflow that never requires it would miss manifest and platform issues.

### Why dynamic branch tracking instead of a fixed `local` branch?

A fixed `local` branch adds an extra merge step (feature → local → test → PR to main). Dynamic branch tracking is direct: feature branch → test on cluster → PR to main. It avoids merge conflicts between the `local` integration branch and feature branches.

### Why push to remote instead of local git?

ArgoCD's standard model is pulling from a remote git repository. Using a local git server or file mount inside the cluster would break the GitOps model, add complexity, and create behavior differences between local and cloud. Pushing to remote keeps both environments using the same ArgoCD configuration pattern.

### Why a single local cluster?

Running multiple Kind clusters simultaneously consumes significant resources (each runs the full platform stack: ArgoCD, Tekton, Cilium, observability). A single cluster with branch switching is sufficient for one developer. The `set-branch` command makes switching fast.

### Why recommended but not enforced?

Mandating the `dev-workflow` skill for every change adds friction to quick fixes. Workflow A (code-only) tasks can reasonably skip it. The skill is most valuable for Workflows B and C where environment setup is needed.

## Implementation Details

### cluster.sh set-branch Command

New subcommand for `infrastructure/clusters/local/scripts/cluster.sh`:

```bash
./cluster.sh set-branch feature/example-change
```

**Behavior:**
1. Patches the root app-of-apps Application (`platform`) to use `targetRevision: feature/<name>`
2. ArgoCD auto-syncs, regenerating all child Applications from the branch's manifest content
3. For multi-source Applications (Helm + git values), the git `$values` ref is also patched
4. Reports the current branch and sync status after patching

```bash
./cluster.sh set-branch main   # Reset to main (used during cleanup)
./cluster.sh get-branch        # Show current tracked branch
```

### Pipeline Branch Awareness

`run-pipeline.sh` will default to the branch currently tracked by ArgoCD, with an optional override:

```bash
./run-pipeline.sh website                           # Builds from ArgoCD-tracked branch
./run-pipeline.sh website --branch feature/other    # Explicit override
```

The tracked branch is read from the root ArgoCD Application's `targetRevision` field.

### dev-workflow Skill

A new Claude Code skill at `.claude/skills/dev-workflow/SKILL.md` that orchestrates the full workflow. The skill:

1. Analyzes the task to detect which workflow applies (A, B, or C)
2. Asks for confirmation of the detected workflow
3. Sets up the environment (worktree, cluster branch) accordingly
4. Pushes to remote at milestones during implementation
5. Runs async ArgoCD sync checks for Workflow B/C
6. Handles full cleanup after PR merge

## Consequences

### Positive

- **Safe cloud deployments**: Feature work never reaches cloud until PR merges to main
- **Fast code iteration**: Workflow A skips cluster setup for pure code changes
- **Full integration testing**: Workflows B and C validate against a real cluster
- **Standard GitOps**: Both environments use the same ArgoCD patterns, just different branches
- **Guided workflow**: Claude Code auto-detects and guides the appropriate workflow
- **Clean cleanup**: Automated reset of cluster branch, remote branch, and worktree

### Negative

- **Push-before-test**: ArgoCD needs commits on the remote, so you must push before testing on the cluster (no testing unpushed changes)
- **Single cluster bottleneck**: Only one feature can be tested on the local cluster at a time
- **Branch switching overhead**: Changing features requires `set-branch` and waiting for ArgoCD to sync
- **New tooling**: `set-branch` command and `dev-workflow` skill need to be built and maintained

### Neutral

- Cloud deployment flow is unchanged
- Existing worktree strategy (ADR-0011) is extended, not replaced
- Local/cloud parity (ADR-0012) is maintained — same manifests, different branches

## Future Considerations

- **Test isolation strategy**: A separate ADR will cover mocking, testcontainers, ephemeral S3 buckets, and per-feature resource isolation to prevent shared state between development and production resources
- **Multiple local clusters**: If resource constraints allow, support named clusters per feature for true parallel testing
- **ArgoCD ApplicationSets**: Could auto-generate Applications per branch, reducing manual `set-branch` management
- **Webhook for local**: A lightweight webhook receiver in the local cluster could auto-sync on push, removing the ArgoCD poll delay

## References

- [ADR-0011: Git Worktree Strategy](0011-git-worktree-strategy.md)
- [ADR-0012: Local vs Cloud Environment Parity](0012-local-vs-cloud-environment-parity.md)
- [ADR-0006: CI/CD Tooling (Tekton)](0006-cicd-tooling-selection.md)
- [ADR-0005: ArgoCD and Crossplane Split](0005-argocd-crossplane-responsibility-split.md)
- Cluster management: `infrastructure/clusters/local/scripts/cluster.sh`
- Worktree management: `scripts/worktree-session.sh`
