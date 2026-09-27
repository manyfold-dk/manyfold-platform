# Manyfold platform

The generic half of a small Kubernetes hosting platform: the components and the application
sources. A private instance repository holds what is specific to one installation -- hostnames,
secrets, image tags, the Argo CD Applications -- and reads this repository at a pinned commit.

This repository is being populated: content arrives one directory group at a time, each through
a publication gate that refuses instance values, private names and credentials.

## Contents

| Path | What it is |
|---|---|
| `build/parent/` | The Maven parent of the Java applications: Java 25, the Quarkus platform, shared test configuration |
| `build/lint/` | Checkstyle, PMD, SpotBugs and formatter rules shared by the Java applications; every check runs in `mvn verify` and none rewrites a file |
| `platform/components/` | Helm values for the platform's upstream components -- Alloy, cert-manager, Crossplane, Descheduler, Envoy Gateway, Komoplane, Loki, OpenBao, the Pushgateway, the Tailscale operator and Tempo -- as `values-cloud.yaml` for a production cluster. The installation's Argo CD Applications pin each chart version and point at these files |
| `platform/observability/alerts/`, `platform/observability/servicemonitors/` | Prometheus alert rules (API server, Argo CD, the website backend, node memory, backups, Velero, synthetic checks, Web Vitals) and the ServiceMonitors for the platform's own services |
| `platform/observability/grafana/` | Grafana dashboards as ConfigMaps for the Grafana sidecar: an entry dashboard, platform and infrastructure health, the website backend's RED metrics and SLO, Web Vitals, synthetic checks and Velero backups |
| `platform/resources/` | Plain manifests the platform installs beside its charts. `cloud/`: the Hetzner cloud controller and CSI driver, the operations Redis, the in-cluster registry with pull-through mirrors (`registry/base`), the Velero namespace |
| `infrastructure/crossplane/` | The platform's own Kubernetes APIs, `ObjectBucket` and `EgressRule`, with Compositions for a cloud cluster (Cloudflare R2, Hetzner Object Storage, Cilium egress policies) ([README](infrastructure/crossplane/README.md)) |
| `infrastructure/modules/r2-backup-bucket/` | An OpenTofu module for an EU-jurisdiction Cloudflare R2 backup bucket with an optional Bucket Lock and expiry, tested with a mocked provider ([README](infrastructure/modules/r2-backup-bucket/README.md)) |
| `infrastructure/clusters/` | The OpenTofu root that bootstraps a production cluster on Hetzner Cloud, with every installation value an input and no default: `cloud/bootstrap/` provisions the Talos control plane and worker servers, the private network and firewall, the API load balancer and its DNS record, and the R2 backup buckets ([README](infrastructure/clusters/cloud/bootstrap/README.md)) |
| `infrastructure/images/` | Container images the platform's own jobs run: `object-backup-validate/` packages the DuckDB CLI with its `httpfs` and `iceberg` extensions, which the object-storage backup job uses to check the copied tables, as a non-root image |
| `.devcontainer/` | A development container (Ubuntu, ARM64) with the Java, Node.js, Kubernetes, Argo CD, SOPS, Talos and OpenTofu command-line tools and zsh dotfiles; its start-up scripts configure Git credentials from an environment file and make host worktree paths resolve inside the container ([README](.devcontainer/README.md)) |
| `scripts/` | Developer tooling: `dev-shell.sh` runs the development container without an editor, `worktree-session.sh` manages one Git worktree per agent session, `consolidate-claude-settings.sh` merges local Claude Code permissions into the shared settings, `drawio-to-png.{js,sh}` export a draw.io diagram to PNG, and `ralph/` runs Claude Code in a loop with a fresh context per task |
| `platform/tenants/_template/` | The tenant landing zone as a template: namespaces, quotas, a default-deny Cilium baseline, an Argo CD AppProject and ApplicationSet for the tenant's repository, and admission policies for self-service buckets and egress ([README](platform/tenants/_template/README.md)) |
| `apps/accounting-mcp/` | An MCP connector and REST passthrough for an accounting API, with an idempotent, audited write ledger ([README](apps/accounting-mcp/README.md)) |
| `apps/slack-bot/` | The operator channel in Slack: alerts, deployments, a health digest, and an approval flow for restarts ([README](apps/slack-bot/README.md)) |
| `apps/synthetic/` | Synthetic monitoring from two vantage points: Playwright journeys in a CronJob and an edge probe Worker, pushing shared metrics to a Pushgateway ([README](apps/synthetic/README.md)) |
| `apps/website/` | The public site and the operator console in one image: a Quarkus backend serving a Vue 3 application, with health aggregation, alert and pipeline webhooks, and restarts ([README](apps/website/README.md)) |
| `platform/argocd/base/` | The generic Argo CD configuration: command parameters, the generic keys of `argocd-cm`, the path-based server route and the KSOPS repo-server patch; an installation layers its URL, identity provider and RBAC in an overlay of its own |
| `platform/observability/synthetic/` | The synthetic-monitoring ingest: a Caddy proxy in front of the Pushgateway and the Pushgateway's ServiceMonitor; an installation adds its probe targets and its ingest route in an overlay |
| `platform/tenants/_tenant-repo-scaffold/` | The scaffold of a tenant's own repository: a CI workflow that builds, publishes and deploys the tenant's image, and the frontend conventions ([README](platform/tenants/_tenant-repo-scaffold/README.md)) |
| `docs/adr/` | Architecture decisions, published one by one with installation values removed |
| `docs/architecture/` | The platform's architecture as C4 diagrams: system context, containers, the GitOps deployment flow, network flow, namespaces, and the networking, observability and cloud stacks ([overview](docs/architecture/platform-overview.md)) |
| `docs/claude-code/` | A developer guide to working on this repository with Claude Code: getting started, features and plugins, keyboard shortcuts and parallel sessions in Git worktrees, plus two archived recommendation notes ([guide](docs/claude-code/README.md)) |
| `docs/iphone-widget/` | A Scriptable widget for iOS that shows the platform's status as a traffic light from the website's status API, with its installation guide ([README](docs/iphone-widget/README.md)) |
| `docs/setup/` | Setup guides: the macOS development setup (the tools and their tested versions, verification, access to the cluster, troubleshooting) and secrets management with SOPS and age (key setup, encrypting secrets, rotation cadence, troubleshooting) ([macOS setup](docs/setup/macos-setup.md), [secrets](docs/setup/secrets-management.md)) |
| `docs/templates/` | Templates for an ADR, a component README and a runbook |
| `docs/runbooks/` | Operational runbooks for Talos, the cluster, disaster recovery and the applications, written with placeholders an operator substitutes ([index](docs/runbooks/README.md)) |
| `docs/guides/` | How-to guides; today dependency management: Renovate, image updates, infrastructure and tooling updates, manual updates, the version policy and rollback ([guide](docs/guides/dependency-management.md)) |
| `docs/standards/` | Standards the platform's applications implement; today the deep health API that health aggregation and self-healing read ([standard](docs/standards/deep-health-api.md)) |
| `CLAUDE.md`, `AGENTS.md`, `.claude/` | Instructions for coding agents: what this repository holds and how to verify a change, then the shared policy block, rules, skills and agent definitions vendored from the public `estate-baseline` payload; the two skill overlays (`.claude/skills/*/environment.md`) are this repository's own ([CLAUDE.md](CLAUDE.md)) |
| `BEST-PRACTICES.md` | Lessons learned operating the platform -- supply chain, Kubernetes and Argo CD, external-dns, Renovate, Helm charts, API and schema design, vendor integrations and known platform limitations -- each with the problem and its solution or workaround ([document](BEST-PRACTICES.md)) |

## Checks

Every pull request runs the publication gate (shapes and both secret scanners), a secret scan of
the commits, a render of every Kustomize directory, and a check of the relative links.

## Licence

Apache-2.0. See [`LICENSE`](LICENSE).
