# ADR 0036: Synthetic Monitoring and Integrity Verification

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)
- [Implementation](#implementation)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)

## Status

Accepted - 2026-05-30

## Context

The platform has mature passive observability (ADR-0013): Prometheus metrics, Loki logs, Tempo traces, Alloy collection, and RUM Web Vitals. These tell us how the system behaves *when real users hit it*. They do not tell us, proactively and from the outside, that:

1. A front end is **reachable and responsive** right now (before a user complains).
2. A **login journey still works** end to end against the live Keycloak/oauth2-proxy stack (no functional regression after a deploy).
3. A front end **has not been tampered with** -- defaced, had a malicious script injected, served from a hijacked DNS record, or presented with an unexpected TLS certificate.

We now run multiple public and admin-facing front ends (the public website, an internal application, the document-signing service (ADR-0034) and a tenant instance of it, plus the Argo CD, Keycloak user and admin, Tekton triggers, object-backup, and admin portals), and the first tenant (ADR-0033) is onboarding its own production fronts. The blast radius of an undetected outage or compromise grows with every front and every tenant.

### Requirements

1. **Active availability checks** on every front, from inside *and* outside the cluster, so edge/DNS/TLS/ingress failures a user would see are caught even when in-cluster health looks green.
2. **Authenticated functional journeys** -- real Keycloak login plus critical-path assertions -- to catch regressions that liveness probes and the deep-health API (`/api/v1/health/deep`) cannot see.
3. **Integrity/tamper detection** covering TLS certificate, content/asset integrity, security headers/CSP, and DNS records, plus visual-diff for defacement.
4. **Self-hosted and GitOps-native** -- consistent with the platform's IaC posture (ADR-0016) and ADR-0013's "learn and control the stack" stance. Credentials must not leave the platform.
5. **Reuse the existing LGTM stack** -- results land in Prometheus/Grafana/Alertmanager, not a separate system.
6. **Feed self-healing** -- integrity and availability signals should be routable to the automated remediation loop (ADR-0023).
7. **Per-tenant extensibility** -- tenants declare their own journeys without operator code changes, consistent with the multi-tenancy model (ADR-0033).

## Decision

We will implement a **three-layer synthetic monitoring and integrity verification system** that reuses the existing LGTM stack and our existing Playwright investment, with a hybrid external/in-cluster vantage split.

### Layered checks

| Layer | Purpose | Tool | Vantage | Cadence |
|-------|---------|------|---------|---------|
| **A -- Availability/edge** | HTTP 2xx, latency, redirect-to-Keycloak present, security headers, TLS expiry, DNS resolution | blackbox-exporter (in-cluster) + Cloudflare Worker (external) | both | 1-2 min |
| **B -- Functional journeys** | Real Keycloak login + critical-path regression assertions | Playwright | in-cluster | 5-15 min |
| **C -- Tamper/integrity** | Content/asset hash vs baseline, visual-diff, header/CSP drift, cert fingerprint, DNS drift | Playwright (authed) + Worker (public) + blackbox `tls` | both | 5-15 min |

### Vantage split

- **External = Cloudflare Worker, probe-only.** Cron-triggered, runs Layer A checks and public-page content hashing from outside the platform. **Holds no login credentials** -- only a push token. Pushes results to an authenticated in-cluster Prometheus Pushgateway over a dedicated HTTPRoute. Chosen because Cloudflare already fronts our DNS (ADR-0019), so it is zero new vendor and effectively free.
- **In-cluster = full Playwright.** Runs Layer B authenticated journeys and Layer C authenticated-page tamper checks as Kubernetes CronJobs. Reaches both the public ingress and the Tailscale-internal admin fronts (ADR-0022). Credentials stay in the cluster.

### Plumbing

- A **Prometheus Pushgateway** (new) receives metrics from short-lived Playwright CronJobs and the external Worker. blackbox-exporter is scraped directly via ServiceMonitor and needs no Pushgateway.
- Playwright jobs emit a standard metric set (`synthetic_check_up`, `synthetic_login_success`, `synthetic_content_hash_match`, `synthetic_visual_diff_ratio`, `synthetic_ssl_expiry_seconds`, ...) labelled by `front`, `journey`, `vantage`, `tenant`.
- Failure **artifacts** (screenshots, HARs, Playwright traces) go to object storage/Loki. **Baselines** (expected hashes, reference screenshots, expected DNS records) are committed to git so every intended change is reviewed via PR.

### Identities and secrets

- Dedicated **least-privilege synthetic test users** in Keycloak per realm/app (e.g. `synthetic-monitor@...`), never real or admin accounts. Admin-portal journeys assert *reachability and post-login landing only* -- they perform no mutating admin actions.
- Credentials stored as **SOPS-encrypted Secrets** (ADR-0017), matching the existing SOPS-encrypted secret-file pattern. OpenBao dynamic credentials are a later upgrade.

### Alerting and self-healing

- New **PrometheusRule** alerts (front down, login failure, content-hash mismatch, visual-diff over threshold, cert/DNS drift, header/CSP drift) route through the existing Alertmanager to Slack. Integrity alerts are high-severity and routable to the automated remediation loop (ADR-0023). Alerts are silenced during ArgoCD syncs to avoid deploy-window noise.

### Repository placement

- Framework, blackbox config, Pushgateway, the Worker, the shared Playwright runner, and **platform-front journeys** live in `manyfold-platform`.
- **Tenant journeys** (e.g. the first tenant's) are declared in the tenant repo and discovered/run by the framework, preserving tenant isolation (ADR-0033).

## Rationale

### Why blackbox-exporter + Playwright (and not k6)

blackbox-exporter already covers Layer A declaratively (HTTP/TLS/DNS/header-regex probes, cert expiry) and is the LGTM-native choice -- it is scraped like any other Prometheus target. Playwright is **already in the codebase** (the website e2e suite) and is stronger than k6-browser for real Keycloak login, regression assertions, and visual-diff. Adding k6 would introduce a *second* browser-scripting model for no coverage we cannot already get. One scripting tool, one probe tool.

### Why a hybrid vantage, with external probe-only

In-cluster checks are blind to DNS hijack, edge TLS problems, and ingress/edge outages -- exactly the failures a user sees first. An external vantage closes that gap. Making the external runner **probe-only** keeps login credentials inside the platform (requirement 4) and keeps the external surface trivial enough to run on a free Cloudflare Worker. The heavy, credential-bearing work stays in-cluster where it belongs.

### Why Cloudflare Worker over AWS Lambda for phase 1

Cloudflare already manages our DNS and edge, so a Worker is zero new vendor, free at our volume, and has built-in cron. Its one weakness -- no socket-level TLS introspection for raw certificate fingerprinting -- is largely moot because Cloudflare manages the edge certificate anyway; in-cluster blackbox `tls` checks cover the origin. AWS Lambda (container + headless Chromium) is held in reserve for a later phase if we want full *external* browser journeys or raw external cert fingerprinting.

### Why baselines in git

Tamper detection is only as trustworthy as its baseline. Storing expected hashes, reference screenshots, and expected DNS records in git means every legitimate change to a front is an explicit, reviewed PR -- and anything that changes without such a PR is, by definition, suspicious.

## Consequences

### Positive

- **Outside-in coverage**: outages and edge/DNS/TLS problems are detected before users report them.
- **Regression safety net**: login and critical paths are continuously exercised against the live stack.
- **Tamper visibility**: defacement, script injection, cert/DNS anomalies surface as alerts, not incidents.
- **Stack reuse**: no new observability backend; results live in Grafana/Alertmanager alongside everything else.
- **Self-hosted, credential-safe**: no synthetic credentials leave the platform; no SaaS dependency for the functional path.
- **Tenant-extensible**: tenants add journeys via their own repo.

### Negative / tradeoffs

- **New moving parts**: a Pushgateway, blackbox-exporter, a Playwright runner image + CronJobs, and a Worker to operate and patch.
- **Browser cost**: Layer B/C browser runs are heavier than probes; mitigated by frequent cheap Layer A and less-frequent browser journeys.
- **Visual-diff flakiness**: dynamic content causes false positives; mitigated with region masking and ratio thresholds (phase 2).
- **Synthetic-user management**: dedicated Keycloak users and their secrets must be created, scoped, and rotated per app/tenant.

### Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Synthetic-user blast radius | Least-privilege users; admin journeys are read-only (assert landing, no mutations) |
| Alert noise during deploys | Silence synthetic alerts tied to ArgoCD sync windows |
| Pushgateway as a stale-metric trap | Jobs push with a grouping key and delete/expire on completion; alert on push freshness |
| Worker push endpoint abuse | Dedicated authenticated HTTPRoute + rotating push token; network-policy scoped |
| External cert fingerprinting gap (Worker) | Accepted for phase 1 (CF manages edge cert); revisit with AWS Lambda in a later phase |

## Implementation

Detailed task breakdown lives in the implementation plan of 2026-05-30 (private).

High-level phasing:

- **Phase 1 (functional)**: Pushgateway + blackbox-exporter (Layer A in-cluster), Cloudflare Worker (Layer A external), Playwright runner + journeys for the internal application, the document-signing service and the website (Layer B), Grafana dashboard, core alerts.
- **Phase 2 (tamper)**: content-hash + visual-diff baselines, header/CSP + cert/DNS drift alerts, automated-remediation triage hook, optional CSP report collector.
- **Phase 3 (optional)**: AWS Lambda external browser journeys + raw external cert fingerprinting, OpenBao dynamic credentials, tenant-journey auto-onboarding.

## Alternatives Considered

### Grafana k6 / k6-browser for journeys

- **Pros**: purpose-built for synthetic + load, native Prometheus metrics, declarative CRDs via k6-operator.
- **Cons**: k6-browser is weaker than Playwright for complex Keycloak login and regression; introduces a second browser-scripting model alongside our existing Playwright suite; visual-diff/content-hash still custom.
- **Rejected**: no coverage gain over Playwright; more tools to maintain.

### Third-party SaaS synthetics (Grafana Cloud Synthetic Monitoring, Checkly)

- **Pros**: real multi-geo vantage points, near-zero infra, fastest to value.
- **Cons**: recurring cost and external dependency; **login/admin credentials would leave the platform**; less GitOps-native.
- **Rejected** for the functional path (conflicts with requirement 4). May be revisited *only* for credential-free external availability if the Worker proves insufficient.

### In-cluster only (no external vantage)

- **Pros**: simplest; nothing outside the cluster to operate.
- **Cons**: blind to DNS hijack, edge TLS, and ingress/edge outages -- the failures users see first.
- **Rejected**: fails requirement 1.

### AWS Lambda external runner for phase 1

- **Pros**: can run full headless-Chromium journeys and raw TLS cert fingerprinting externally.
- **Cons**: more setup; Playwright-on-Lambda needs ~1.5-2GB memory so frequent runs exceed pure free tier; new vendor surface for phase 1.
- **Deferred**: held in reserve for phase 3 if external browser journeys or raw external cert fingerprinting are wanted.

## Related Decisions

- [ADR-0013: Observability Stack](0013-observability-stack.md) - the LGTM stack these checks feed into
- [ADR-0023: Self-Healing Platform Architecture](0023-self-healing-platform.md) - consumer of integrity/availability signals
- [ADR-0017: Secrets Management Strategy](0017-secrets-management-strategy.md) - SOPS-encrypted synthetic credentials
- [ADR-0018: Identity Management Strategy](0018-identity-management-strategy.md) - Keycloak synthetic test users
- ADR-0019: the platform's domain setup (private) - Cloudflare DNS/edge the Worker rides on
- [ADR-0022: Remote Access Strategy](0022-remote-access-strategy.md) - Tailscale-internal admin fronts reached in-cluster
- [ADR-0033: Multi-Tenancy Model and Tenant Isolation](0033-multi-tenancy-model-and-tenant-isolation.md) - tenant-declared journeys
- Deep Health API Standard (private) - complementary in-cluster health signal

## References

- [Prometheus blackbox_exporter](https://github.com/prometheus/blackbox_exporter)
- [Prometheus Pushgateway](https://github.com/prometheus/pushgateway)
- [Playwright](https://playwright.dev/)
- [Cloudflare Workers Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/)
