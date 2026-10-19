# Architecture decision records

The platform's architecture decisions, one MADR record per decision, published with installation values removed.

<!-- adr-index:begin -->
| ADR | Title | Status |
|---|---|---|
| 0001 | [Monorepo Structure](0001-monorepo-structure.md) | Accepted |
| 0002 | [Resource Naming Conventions](0002-resource-naming-conventions.md) | Accepted |
| 0003 | [Versioning and Commit Standards](0003-versioning-and-commit-standards.md) | Accepted |
| 0004 | [Application Stack Tool Selection](0004-application-stack-tool-selection.md) | Accepted |
| 0005 | [ArgoCD and Crossplane Responsibility Split](0005-argocd-crossplane-responsibility-split.md) | Accepted |
| 0006 | [CI/CD Tooling Selection](0006-cicd-tooling-selection.md) | Accepted -- the **CI-engine selection is superseded by Blacksmith (GitHub Actions on Blacksmith runners)**; see [Amendment 2026-06-14](#amendment-2026-06-14-blacksmith-replaces-tekton-for-ci). The ghcr.io registry choice and the CI/CD separation (build/test in CI, deploy via ArgoCD) are unchanged. The Tekton content left in the repository was retired on 2026-09-27; see the [operational amendment](#operational-amendment-2026-09-27-tekton-retired-from-the-repository). |
| 0007 | [Cilium CNI and Network Policy Strategy](0007-cilium-cni-and-network-policy-strategy.md) | Accepted |
| 0008 | [Container Registry Strategy](0008-container-registry-strategy.md) | Accepted |
| 0009 | [Development Environment Strategy](0009-development-environment-strategy.md) | Accepted |
| 0010 | [Build System and Dependency Management](0010-build-system-and-dependency-management.md) | Accepted |
| 0011 | [Git Worktree Strategy for Parallel Development](0011-git-worktree-strategy.md) | Accepted |
| 0012 | [Local vs Cloud Environment Parity](0012-local-vs-cloud-environment-parity.md) | Accepted |
| 0013 | [Observability Stack](0013-observability-stack.md) | Accepted |
| 0014 | [Cloud Provider Selection](0014-cloud-provider-selection.md) | Accepted |
| 0015 | [Kubernetes Distribution](0015-kubernetes-distribution.md) | Accepted |
| 0016 | [Infrastructure as Code Tool](0016-infrastructure-as-code-tool.md) | Accepted |
| 0017 | [Secrets Management Strategy](0017-secrets-management-strategy.md) | Accepted — OpenBao deployed to cloud cluster (2026-02-21) |
| 0018 | [Identity Management Strategy](0018-identity-management-strategy.md) | Accepted |
| 0020 | [Backup Strategy with Cloudflare R2](0020-backup-strategy.md) | Accepted - Implemented |
| 0021 | [Feature Flags Service](0021-feature-flags-service.md) | Proposed |
| 0022 | [Remote Access Strategy](0022-remote-access-strategy.md) | Implemented (January 2026), amended April 2026 |
| 0023 | [Self-Healing Platform Architecture](0023-self-healing-platform.md) | Accepted |
| 0024 | [Jump Server and Devcontainer in Cluster](0024-jump-server-and-devcontainer-in-cluster.md) | Implemented (Jump Server with Tailscale SSH + Mosh) |
| 0025 | [Software Upgrade Strategy](0025-software-upgrade-strategy.md) | Accepted |
| 0026 | [Developer Workflow Strategy](0026-developer-workflow-strategy.md) | Accepted |
| 0028 | [Slack Bot Microservice](0028-slack-bot-microservice.md) | Accepted |
| 0031 | [Pod Rebalancing Strategy](0031-pod-rebalancing-strategy.md) | Accepted |
| 0033 | [Multi-Tenancy Model, Tenant Naming & Isolation Guardrails](0033-multi-tenancy-model-and-tenant-isolation.md) | (unknown) |
| 0035 | [EU Data Residency for Storage](0035-eu-data-residency-for-storage.md) | Accepted - 2026-05-24. Migration completed 2026-05-30 (all backup buckets now jurisdiction=eu; old WEUR buckets decommissioned). |
| 0036 | [Synthetic Monitoring and Integrity Verification](0036-synthetic-monitoring-and-integrity-verification.md) | Accepted - 2026-05-30 |
| 0039 | [Frontend Design Handoff — Claude Design ↔ Claude Code](0039-frontend-design-handoff.md) | Proposed (2026-05-31). Triggered by the first frontend that will be designed with |
| 0040 | [Tenant Application-Managed Secrets via OpenBao Transit](0040-tenant-app-managed-secrets-via-openbao-transit.md) | (unknown) |
| 0041 | [Tenant Self-Service Egress (Capability #2)](0041-tenant-self-service-egress.md) | Accepted |
| 0050 | [Agent Instruction Baseline & Session Altitudes](0050-agent-instruction-baseline-and-session-altitudes.md) | Accepted (2026-08-23). Records two decisions that were taken and implemented before this ADR |
| 0054 | [A Public Platform Repository and a Private Instance Repository](0054-public-platform-and-private-instance-repositories.md) | **Accepted** (2026-09-23), on the owner's decision. Proposed the same day during an unattended |
| 0055 | [Upstream Versions in the Public Platform Repository](0055-upstream-versions-in-the-public-platform-repository.md) | Accepted (2026-09-23), on the owner's decision. A recorded deviation from the baseline |
| 0058 | [Untagged Images in Application Bases](0058-untagged-images-in-application-bases.md) | accepted |
| 0059 | [Registry Volumes Outside Velero File-System Backup](0059-registry-volumes-outside-velero-file-system-backup.md) | accepted |
| 0060 | [A Backstage Developer Portal as a Tenant of the Platform](0060-backstage-developer-portal-as-a-tenant-of-the-platform.md) | Accepted (2026-10-19) |
<!-- adr-index:end -->
