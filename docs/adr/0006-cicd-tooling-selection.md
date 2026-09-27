# ADR 0006: CI/CD Tooling Selection

## Table of Contents

- [Status](#status)
- [Amendment 2026-06-14: Blacksmith Replaces Tekton for CI](#amendment-2026-06-14-blacksmith-replaces-tekton-for-ci)
- [Operational Amendment 2026-09-27: Tekton Retired from the Repository](#operational-amendment-2026-09-27-tekton-retired-from-the-repository)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Implementation Details](#implementation-details)
- [Consequences](#consequences)
- [Alternatives Considered](#alternatives-considered)
- [Implementation Phases](#implementation-phases)
- [References](#references)
- [Notes](#notes)

## Status

Accepted -- the **CI-engine selection is superseded by Blacksmith (GitHub Actions on Blacksmith runners)**; see [Amendment 2026-06-14](#amendment-2026-06-14-blacksmith-replaces-tekton-for-ci). The ghcr.io registry choice and the CI/CD separation (build/test in CI, deploy via ArgoCD) are unchanged. The Tekton content left in the repository was retired on 2026-09-27; see the [operational amendment](#operational-amendment-2026-09-27-tekton-retired-from-the-repository).

## Amendment 2026-06-14: Blacksmith Replaces Tekton for CI

**What changed.** All seven platform applications of the time build, test, and deploy via
**GitHub Actions on Blacksmith runners**, not Tekton. The engine is selected per app by the
source-controlled switch files in `infrastructure/ci/*-ci.env` (every file reads
`CI_MODE=blacksmith` / `DEPLOY_ENGINE=blacksmith`; one application uses app-prefixed
variants of the same keys). The GitOps contract is unchanged: CI builds and pushes the image to
ghcr.io, commits the new image tag to Git, and ArgoCD syncs.

**Why.** Blacksmith provides managed, fast runners with sticky-disk caching (multi-GB Maven and
ONNX caches restore in seconds), removes the burden of operating an in-cluster CI control plane
(EventListener webhooks, triggers, dashboard), and frees capacity on the small Hetzner nodes --
the idle Tekton stack was pure cost after the migration. See the 2026-06-10 solution top-down
review and the CI supply-chain hardening plan of 2026-06-12 (both private).

**Tekton's fate: decommissioned.** The cloud Tekton stack (pipelines, tasks, triggers,
dashboard, config) is removed from the cluster (plan steps S12-S13); no cold standby is kept.
Retained deliberately:

- `infrastructure/ci/*-ci.env` and `scripts/ci/read-ci-engine-switch.sh` -- the baseline
  reusable workflow still consumes the switch input; retiring the dual-engine concept itself is
  a concern of the shared baseline, not this decommission.
- The CI-engine switch runbook of one application (private) -- kept as history with a
  decommission-date note, not deleted.

**What still stands from the original decision.** ghcr.io as the container registry; ArgoCD for
CD; the image-tag-commit GitOps flow. Only "Tekton for CI" is reversed. Tekton Chains
(supply-chain signing, listed as a future phase below) is consequently also off the table on
this platform; image signing / SBOM is tracked separately as a roadmap follow-up.

## Operational Amendment 2026-09-27: Tekton Retired from the Repository

Recorded on an owner decision of 2026-09-27 in the cutover plan (private). This amends
operational practice in place; it does not change the Decision above or the 2026-06-14
amendment, which already reversed "Tekton for CI".

**What changed.** After the 2026-06-14 decommission the repository still held Tekton content
that no cluster ran: the shared tasks under `infrastructure/tekton/base`, the pipelines of two
applications, two alert rules, a ServiceMonitor, dashboard panels, two runbooks, the cloud
pipeline runner script and an agent skill. The local cluster, the last one that applied the
tasks and pipelines, was retired on the same day
([ADR-0054 amendment](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead)).
That content is deleted, and the documents and agent instructions no longer describe Tekton as
an engine that can run. GitHub Actions on Blacksmith runners are the only CI engine.

**Why.** The public repository holds what the instance deploys (ADR-0054 amendment). Pipelines
that nothing runs or tests would be published as if they worked.

**Still in place.** The CI-engine switch (`infrastructure/ci/*-ci.env`,
`scripts/ci/read-ci-engine-switch.sh`) still accepts `tekton` and `dual`. Whether it keeps
only `blacksmith` and `none` or is retired is a separate change. The Rationale, Implementation
Details and Implementation Phases below are the January 2026 record; the Tekton components,
tasks and directories they describe no longer exist.

## Context

The Manyfold Platform requires a CI/CD pipeline to build, test, and deploy applications to Kubernetes. ADR-0005 established ArgoCD for application deployment (CD), but the build and test (CI) component was not yet decided.

### Requirements

1. **Kubernetes-Native**: Must run on Kubernetes, supporting both local Kind clusters and production environments
2. **Container Image Building**: Must support building OCI-compliant container images without Docker daemon
3. **GitOps Integration**: Must integrate cleanly with ArgoCD for deployment
4. **Self-Hosted**: Must be fully self-hosted with no external SaaS dependencies
5. **Multi-Language Support**: Must support Java/Maven and Node.js/pnpm build workflows
6. **Production-Grade**: Must be battle-tested and suitable for production workloads

### CI Tools Evaluated

| Tool | K8s Native | Podman/Buildah | Self-Hosted | Maturity |
|------|------------|----------------|-------------|----------|
| **Tekton** | Excellent | Excellent | Yes | High |
| **Dagger** | Good | Excellent | Yes | Medium-High |
| **Woodpecker CI** | Good | Medium | Yes | Medium |
| **Drone CI** | Good | Medium | Yes | Medium-High |
| **Concourse CI** | Good | Medium | Yes | Medium-High |
| **Jenkins X** | Excellent | Good | Yes | Medium |
| **GitHub Actions (ARC)** | Good | Medium | Partial* | High |

*GitHub ARC requires GitHub.com as control plane

### Container Registry Options Evaluated

| Registry | Self-Hosted | Free Tier | Integration |
|----------|-------------|-----------|-------------|
| **GitHub Container Registry (ghcr.io)** | No | Yes (public) | Native GitHub |
| **Harbor** | Yes | N/A | Kubernetes-native |
| **Docker Hub** | No | Limited | Universal |
| **AWS ECR** | No | Pay-per-use | AWS-native |

## Decision

We will use the following CI/CD toolchain:

- **Tekton** for build and test (CI)
- **GitHub Container Registry (ghcr.io)** for container image storage
- **ArgoCD** for deployment (CD) - as established in ADR-0005

### Pipeline Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Git Push / PR                               │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Tekton EventListener                            │
│                   (Webhook from GitHub)                             │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Tekton Pipeline                                │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│  │  Clone  │─▶│  Build  │─▶│  Test   │─▶│ Buildah │─▶│  Push   │   │
│  │  Repo   │  │ (Maven/ │  │ (Unit/  │  │ (Build  │  │ (ghcr.  │   │
│  │         │  │  pnpm)  │  │  Lint)  │  │  Image) │  │   io)   │   │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Update Image Tag in Git                          │
│              (Kustomize overlay or Helm values)                     │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          ArgoCD                                     │
│                (Detects Git change, syncs to cluster)               │
└─────────────────────────────────────────────────────────────────────┘
```

## Rationale

### Why Tekton for CI?

**Kubernetes-Native Architecture**:
- Pipelines, Tasks, and Runs are Kubernetes Custom Resources (CRDs)
- Tasks execute as Pods; steps run as containers within Pods
- Native integration with Kubernetes RBAC, namespaces, and resource limits
- Works identically on local Kind clusters and production Kubernetes

**Buildah/Podman Support**:
- First-class Buildah task in Tekton Catalog (no Docker daemon required)
- Supports rootless builds for enhanced security
- Aligns with our Podman-based local development environment
- OCI-compliant images work with any container runtime

**ArgoCD Integration**:
- Well-established "Tekton for CI, ArgoCD for CD" pattern
- Documented by AWS, Red Hat, and community
- Clean separation: Tekton builds and pushes, ArgoCD deploys
- Tekton updates Git (image tags), ArgoCD syncs from Git

**Production-Grade Maturity**:
- Part of Continuous Delivery Foundation (CDF) alongside Jenkins, Spinnaker
- Backed by Google, Red Hat, IBM, VMware
- ~8,500 GitHub stars, monthly releases, quarterly LTS versions
- Used in production by major enterprises and OpenShift Pipelines

**Ecosystem and Extensibility**:
- Tekton Catalog provides reusable tasks (Maven, npm, Buildah, git-clone, etc.)
- Tekton Chains for supply chain security (image signing with Sigstore)
- Tekton Dashboard for pipeline visualization
- Tekton Triggers for webhook-based pipeline execution

**Resource Efficiency**:
- Ephemeral execution (no persistent runners consuming resources)
- Configurable resource limits per task/step
- Scales to zero when no pipelines are running

### Why GitHub Container Registry (ghcr.io)?

**Integration with GitHub**:
- Native authentication via GitHub tokens (GITHUB_TOKEN)
- Automatic linking to source repositories
- Visibility controls aligned with repository permissions
- No additional credentials management for GitHub-based workflows

**Cost-Effective**:
- Free for public repositories
- Generous free tier for private repositories
- No egress fees for pulls (unlike some cloud registries)

**Simplicity**:
- No infrastructure to manage (unlike self-hosted Harbor)
- Already using GitHub for source control
- Reduces operational burden during initial platform build-out

**Future Flexibility**:
- Can migrate to Harbor later if self-hosting becomes a requirement
- OCI-compliant registry; images are portable
- Tekton pipelines can be updated to push elsewhere with minimal changes

### Why Not Other CI Tools?

**Dagger**:
- Strong contender with excellent Podman support
- "Pipelines as code" model is attractive
- Rejected due to: newer project (less battle-tested), still rapidly evolving
- Could revisit in future if Tekton proves too complex

**Woodpecker CI**:
- Lightweight and fully open-source
- Rejected due to: requires Docker-in-Docker for Kubernetes mode (privileged containers)
- Smaller community and ecosystem

**Drone CI**:
- Simple YAML configuration
- Rejected due to: dual licensing concerns, reduced community activity since Harness acquisition
- Build quotas on community edition

**Jenkins X**:
- Uses Tekton underneath
- Rejected due to: too opinionated, heavy resource requirements, overlaps with ArgoCD
- Adds complexity without proportional benefit

**Concourse CI**:
- Unique resource-based model
- Rejected due to: steep learning curve, less common ArgoCD integration patterns
- Requires privileged containers

**GitHub Actions with ARC (Actions Runner Controller)**:
- Excellent if fully committed to GitHub ecosystem
- Rejected due to: requires GitHub.com as control plane (not fully self-hosted)
- May revisit if GitHub Enterprise Server is adopted

### Why Not Self-Hosted Registry (Harbor)?

**Harbor Considered For**:
- Full control over image storage
- Advanced features: vulnerability scanning, replication, retention policies
- No external dependencies

**Rejected For Initial Implementation**:
- Additional infrastructure to deploy and maintain
- Operational overhead (storage, backups, upgrades)
- ghcr.io provides sufficient functionality for current needs
- Can migrate to Harbor later when platform matures

## Implementation Details

### Tekton Components

```yaml
# Installed via ArgoCD
tekton-pipelines:       # Core pipeline execution engine
tekton-triggers:        # Webhook handling and pipeline triggering
tekton-dashboard:       # Web UI for pipeline visualization
tekton-chains:          # Supply chain security (optional, phase 2)
```

### Tekton Task Examples

**Java/Quarkus Backend**:
```yaml
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: maven-build
spec:
  workspaces:
    - name: source
    - name: maven-settings
  steps:
    - name: build
      image: maven:3.9-eclipse-temurin-25
      workingDir: $(workspaces.source.path)
      script: |
        mvn clean package -DskipTests
    - name: test
      image: maven:3.9-eclipse-temurin-25
      workingDir: $(workspaces.source.path)
      script: |
        mvn test
```

**Vue/Vite Frontend**:
```yaml
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: pnpm-build
spec:
  workspaces:
    - name: source
  steps:
    - name: install
      image: node:22-alpine
      workingDir: $(workspaces.source.path)
      script: |
        corepack enable
        pnpm install --frozen-lockfile
    - name: lint
      image: node:22-alpine
      workingDir: $(workspaces.source.path)
      script: |
        corepack enable
        pnpm lint
    - name: build
      image: node:22-alpine
      workingDir: $(workspaces.source.path)
      script: |
        corepack enable
        pnpm build
```

**Container Image Build (Buildah)**:
```yaml
apiVersion: tekton.dev/v1
kind: Task
metadata:
  name: buildah-build-push
spec:
  params:
    - name: IMAGE
    - name: DOCKERFILE
      default: ./Dockerfile
  workspaces:
    - name: source
  steps:
    - name: build-push
      image: quay.io/buildah/stable
      workingDir: $(workspaces.source.path)
      securityContext:
        capabilities:
          add: ["SETFCAP"]
      script: |
        buildah bud --format=oci \
          -f $(params.DOCKERFILE) \
          -t $(params.IMAGE) .
        buildah push $(params.IMAGE)
```

### Directory Structure

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); its `overlays/local/` Tekton overlay went with it. The rest of this tree was deleted on 2026-09-27 (see the [operational amendment](#operational-amendment-2026-09-27-tekton-retired-from-the-repository)).

```
infrastructure/
├── tekton/
│   ├── base/
│   │   ├── install/              # Tekton operator/components
│   │   ├── tasks/                # Reusable task definitions
│   │   │   ├── git-clone.yaml
│   │   │   ├── maven-build.yaml
│   │   │   ├── pnpm-build.yaml
│   │   │   └── buildah.yaml
│   │   └── pipelines/            # Pipeline definitions
│   │       ├── backend-pipeline.yaml
│   │       └── frontend-pipeline.yaml
│   └── overlays/
│       ├── local/                # Kind cluster configuration
│       └── production/           # Production cluster configuration
└── argocd/
    └── applications/
        └── tekton.yaml           # ArgoCD Application for Tekton
```

### Image Tagging Strategy

Following ADR-0003 (Versioning and Commit Standards):

| Trigger | Tag Format | Example |
|---------|------------|---------|
| PR/Branch | `<branch>-<short-sha>` | `feature-auth-a1b2c3d` |
| Main branch | `main-<short-sha>` | `main-a1b2c3d` |
| Release tag | `<semver>` | `x.y.z` |
| Latest main | `latest` | `latest` |

### ghcr.io Authentication

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: ghcr-credentials
  namespace: tekton-pipelines
type: kubernetes.io/dockerconfigjson
data:
  .dockerconfigjson: <base64-encoded-config>
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: tekton-build
  namespace: tekton-pipelines
secrets:
  - name: ghcr-credentials
imagePullSecrets:
  - name: ghcr-credentials
```

## Consequences

### Positive

- **Kubernetes-Native CI**: Pipelines run as native Kubernetes resources with full RBAC and resource control
- **No Docker Daemon**: Buildah enables secure, rootless container builds
- **Clean Separation**: Tekton handles build/test, ArgoCD handles deployment
- **Scalable**: Ephemeral execution scales to zero; no idle runner costs
- **Auditable**: All pipeline definitions stored in Git
- **Extensible**: Tekton Catalog provides community-maintained tasks
- **Supply Chain Security**: Path to Tekton Chains for image signing (Sigstore)
- **Managed Registry**: ghcr.io reduces operational burden

### Negative

- **Learning Curve**: Tekton's CRD-based model requires Kubernetes expertise
- **YAML Verbosity**: Pipelines require more YAML than simpler CI tools
- **No Built-in UI Authoring**: Dashboard is view-only; pipelines authored as YAML
- **External Registry Dependency**: ghcr.io requires GitHub availability
- **Webhook Configuration**: Requires exposing Tekton Triggers to receive GitHub webhooks

### Mitigations

- Create well-documented pipeline templates for common patterns
- Use Tekton Catalog tasks where possible to reduce custom YAML
- Implement Tekton Dashboard for visibility and debugging
- Document local development workflow for testing pipelines
- Plan Harbor migration path if self-hosted registry becomes required
- Use GitHub App authentication for more robust webhook integration

## Alternatives Considered

### Dagger Instead of Tekton

- **Pros**: Pipelines as code (Go/Python/Java), excellent caching, local/CI parity
- **Cons**: Newer project, rapidly evolving API, less production track record
- **Decision**: Monitor Dagger's maturity; could adopt for specific use cases later

### Harbor Instead of ghcr.io

- **Pros**: Self-hosted, vulnerability scanning, replication, full control
- **Cons**: Additional infrastructure, operational overhead
- **Decision**: Start with ghcr.io; migrate to Harbor if/when self-hosting becomes a requirement

### GitHub Actions with Self-Hosted Runners

- **Pros**: Familiar GitHub Actions syntax, large marketplace
- **Cons**: Control plane on GitHub.com, not fully self-hosted
- **Decision**: Could complement Tekton for specific workflows (e.g., release automation)

### All-in-One Platform (Jenkins X, Argo Workflows + Events)

- **Pros**: Integrated solution, fewer tools to manage
- **Cons**: Opinionated, may conflict with existing ArgoCD setup, heavier
- **Decision**: Prefer composable tools with clear responsibilities

## Implementation Phases

### Phase 1: Foundation
- Install Tekton Pipelines, Triggers, Dashboard via ArgoCD
- Create pipeline for website backend (Java/Quarkus)
- Create pipeline for website frontend (Vue/Vite)
- Configure GitHub webhooks for PR and push events
- Push images to ghcr.io

### Phase 2: Enhancement
- Add Tekton Chains for image signing
- Implement caching (Maven repository, pnpm store)
- Add notification integration (Slack/Discord)
- Create pipeline templates for new applications

### Phase 3: Advanced (Future)
- Evaluate Harbor for self-hosted registry
- Add vulnerability scanning to pipeline
- Implement promotion workflows (dev → staging → prod)
- Consider Dagger for complex build scenarios

## References

- [Tekton Documentation](https://tekton.dev/docs/)
- [Tekton Catalog](https://hub.tekton.dev/)
- [Tekton + ArgoCD Pattern (AWS)](https://aws.amazon.com/blogs/containers/cloud-native-ci-cd-with-tekton-and-argocd-on-aws/)
- [Buildah Task](https://hub.tekton.dev/tekton/task/buildah)
- [GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [ADR-0005: ArgoCD and Crossplane Responsibility Split](0005-argocd-crossplane-responsibility-split.md)
- [ADR-0003: Versioning and Commit Standards](0003-versioning-and-commit-standards.md)

## Notes

This decision was made during initial platform architecture (January 2026). Revisit if:
- Tekton complexity outweighs benefits for team size
- GitHub Container Registry limitations become blocking
- Dagger matures sufficiently to warrant reconsideration
- Self-hosted registry becomes a hard requirement
