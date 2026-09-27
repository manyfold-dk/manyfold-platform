# Tenant Repo CI Scaffold

Canonical, copy-into-the-tenant-repo CI recipe that builds a tenant app, publishes
its image to GHCR, and bumps the tenant's GitOps deploy tag for ArgoCD to sync.

This lives in the platform repository (not in any tenant repo) because it is the
**shared, org-level** recipe -- but it is written to depend on *nothing* in the
platform repository, so a tenant (including an external one) can run it in
isolation. See ADR-0037 (private)
for the decision and [ADR-0033](../../../docs/adr/0033-multi-tenancy-model-and-tenant-isolation.md)
for the isolation model.

Placeholders used below: `<org>` is the GitHub organisation that owns the tenant
repositories, `<tenant>` the tenant name token (the same token as in the
[landing-zone template](../_template/README.md)), `<app>` one application in the tenant repo.

## Table of Contents

- [What This Is](#what-this-is)
- [How To Use](#how-to-use)
- [Per-Tenant Variables](#per-tenant-variables)
- [Prerequisites](#prerequisites)
- [How The Deploy Loop Closes](#how-the-deploy-loop-closes)
- [Future: Reusable Workflow](#future-reusable-workflow)
- [Related](#related)

## What This Is

| File | Goes to (tenant repo) | Purpose |
|------|------------------------|---------|
| `ci.yml` | `.github/workflows/ci.yml` | build & test → push image → bump deploy tag |
| `frontend/CONVENTIONS.md` | `apps/<app>/frontend/CONVENTIONS.md` | seed frontend conventions, for a tenant app with a UI |

The recipe is **inline** (no `uses:` of a shared workflow) on purpose: a tenant repo
must not depend on the platform repository (ADR-0033, ADR-0037), and inlining keeps it
fully decoupled from the installation. Shared workflows a tenant may adopt live in the
baseline repositories, which exist for that purpose (ADR-0037); the public one is
described under [Future: Reusable Workflow](#future-reusable-workflow). It uses only public
marketplace actions.

## How To Use

1. Copy `ci.yml` into the tenant repo at `.github/workflows/ci.yml`.
2. Edit the `env:` block and the `paths:` trigger (see [Per-Tenant Variables](#per-tenant-variables)).
3. Confirm the [Prerequisites](#prerequisites).
4. Commit to the tenant's `main`. CI runs on push; the first green run publishes
   the image and rewrites the GitOps `image:` tag.

## Per-Tenant Variables

Only the `env:` block and the `paths:` trigger change between tenants:

| Variable | Meaning | Example |
|----------|---------|---------|
| `APP_DIR` | Maven module + Docker build context | `apps/<app>` |
| `DOCKERFILE` | Self-contained multi-stage Dockerfile | `apps/<app>/Dockerfile` |
| `IMAGE_BASE` | Org GHCR package (lowercase, no tag) | `ghcr.io/<org>/<tenant>/<app>` |
| `DEPLOY_MANIFEST` | GitOps manifest with the inline `image:` line | `gitops/prod/<app>/deployment.yaml` |
| `on.push.paths` | Directories whose changes trigger CI; `APP_DIR` plus the workflow itself | `"apps/<app>/**"` |

The deploy commit's message names the last segment of `IMAGE_BASE` (`<app>`).

The Dockerfile must carry `LABEL org.opencontainers.image.source="https://github.com/<org>/<tenant>"`
so GHCR auto-links the package to the tenant repo on first push.

## Prerequisites

| # | Prerequisite | Owner | Notes |
|---|--------------|-------|-------|
| 1 | Org allows a repo's `GITHUB_TOKEN` to create packages | GitHub org admin | Default-on. If disabled, pre-create the package and grant the tenant repo `write`, or provide a `write:packages` PAT and swap the login password. |
| 2 | `main` is pushable by `GITHUB_TOKEN` | tenant repo | Works when `main` is unprotected (the default for new tenant repos). If protected, add a PAT secret and a bypass for the CI bot. |
| 3 | Cluster can pull the (private) image | platform operator | GHCR packages inherit the private repo's visibility. The pulling namespace needs a `ghcr-credentials` dockerconfigjson secret (the installation provides it per tenant as an encrypted secret; its tenant-onboarding runbook names the file) **and** the workload's pod spec must reference it with `imagePullSecrets: [{name: ghcr-credentials}]`. |
| 4 | (optional) Blacksmith runners | GitHub org admin | Only if converging on the house runner convention -- install the Blacksmith GitHub App on the tenant repo first. |

## How The Deploy Loop Closes

```
push to main → verify (mvnw verify) → image (docker build+push :main-<sha>)
            → deploy (sed image tag in DEPLOY_MANIFEST, commit "[skip ci]" to main)
            → ArgoCD ApplicationSet (selfHeal) syncs the new tag → rollout
```

The tenant CI never calls `argocd`; the operator-owned ApplicationSet auto-syncs.
The deploy commit cannot loop: it carries `[skip ci]`, only touches `gitops/**`
(excluded from the `paths:` trigger), and `GITHUB_TOKEN` pushes do not retrigger
workflows.

## Future: Reusable Workflow

The `verify` + `image` jobs can be replaced by the public baseline's reusable
workflow, pinned by full commit id (its caller contract:
`manyfold-dk/estate-baseline` `.github/workflows/README.md`):

```yaml
jobs:
  build-and-publish:
    uses: manyfold-dk/estate-baseline/.github/workflows/quarkus-image-build-push.yml@<sha>
    with:
      working-directory: apps/<app>
      image-base: ghcr.io/<org>/<tenant>/<app>
      java-version: "<major>"
    permissions:
      contents: read
      packages: write
```

The `deploy` (tag-bump) job stays tenant-side -- it commits to the tenant's own
GitOps manifest, which is repo-specific and not shareable.

## Related

- [`frontend/CONVENTIONS.md`](frontend/CONVENTIONS.md) -- seed Vue/Vite/TS frontend
  conventions to copy into a tenant repo with a UI (distilled from the platform's
  reference apps). Governance for design handoff lives in
  [ADR-0039](../../../docs/adr/0039-frontend-design-handoff.md).
