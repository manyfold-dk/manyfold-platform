---
number: "0036"
series: Platform
title: Synthetic monitoring and integrity verification
status: Accepted
date: 2026-05-30
summary: Passive observability says how the system behaves when users hit it. This adds outside-in availability checks, real login journeys and tamper detection, with the external probe holding no login credentials.
changes: The list of front ends, tenant and application names, the synthetic user naming, how the operator fronts are reached, endpoint and route details and links to private documents are removed.
---

## Status

Accepted 2026-05-30

## Context

The platform has mature passive observability (ADR 0013): Prometheus metrics,
Loki logs, Tempo traces, Alloy collection, and RUM Web Vitals. These tell us how
the system behaves *when real users hit it*. They do not tell us, proactively
and from the outside, that:

1. A front end is **reachable and responsive** right now (before a user
   complains).
2. A **login journey still works** end to end against the live Keycloak and
   oauth2-proxy stack (no functional regression after a deploy).
3. A front end **has not been tampered with** -- defaced, had a malicious script
   injected, served from a hijacked DNS record, or presented with an unexpected
   TLS certificate.

We now run several public and operator-facing front ends, and the first tenant
(ADR 0033) is onboarding its own production fronts. The blast radius of an
undetected outage or compromise grows with every front and every tenant.

### Requirements

1. **Active availability checks** on every front, from inside *and* outside the
   cluster, so edge, DNS, TLS and ingress failures a user would see are caught
   even when in-cluster health looks green.
2. **Authenticated functional journeys** -- real Keycloak login plus
   critical-path assertions -- to catch regressions that liveness probes and the
   deep-health API cannot see.
3. **Integrity and tamper detection** covering TLS certificate, content and
   asset integrity, security headers and CSP, and DNS records, plus visual-diff
   for defacement.
4. **Self-hosted and GitOps-native** -- consistent with the platform's
   infrastructure-as-code posture (ADR 0016) and ADR 0013's "learn and control
   the stack" stance. Credentials must not leave the platform.
5. **Reuse the existing LGTM stack** -- results land in Prometheus, Grafana and
   Alertmanager, not a separate system.
6. **Feed self-healing** -- integrity and availability signals should be
   routable to the automated remediation loop (ADR 0023).
7. **Per-tenant extensibility** -- tenants declare their own journeys without
   operator code changes, consistent with the multi-tenancy model (ADR 0033).

## Decision

We will implement a **three-layer synthetic monitoring and integrity
verification system** that reuses the existing LGTM stack and our existing
Playwright investment, with a hybrid external and in-cluster vantage split.

### Layered checks

| Layer | Purpose | Tool | Vantage | Cadence |
|-------|---------|------|---------|---------|
| **A -- Availability and edge** | HTTP 2xx, latency, redirect to login present, security headers, TLS expiry, DNS resolution | blackbox-exporter (in-cluster) + Cloudflare Worker (external) | both | 1-2 min |
| **B -- Functional journeys** | Real Keycloak login + critical-path regression assertions | Playwright | in-cluster | 5-15 min |
| **C -- Tamper and integrity** | Content and asset hash vs baseline, visual-diff, header and CSP drift, cert fingerprint, DNS drift | Playwright (authenticated) + Worker (public) + blackbox `tls` | both | 5-15 min |

### Vantage split

- **External = Cloudflare Worker, probe-only.** Cron-triggered, runs Layer A
  checks and public-page content hashing from outside the platform. **Holds no
  login credentials** -- only a push token. Pushes results to an authenticated
  ingestion endpoint in the cluster. Chosen because Cloudflare already fronts
  our DNS, so it is zero new vendor and effectively free.
- **In-cluster = full Playwright.** Runs Layer B authenticated journeys and
  Layer C authenticated-page tamper checks as Kubernetes CronJobs. Reaches both
  the public fronts and the operator fronts that are not exposed to the
  internet. Credentials stay in the cluster.

### Plumbing

- A **Prometheus Pushgateway** (new) receives metrics from short-lived
  Playwright CronJobs and the external Worker. blackbox-exporter is scraped
  directly via ServiceMonitor and needs no Pushgateway.
- Playwright jobs emit a standard metric set (`synthetic_check_up`,
  `synthetic_login_success`, `synthetic_content_hash_match`,
  `synthetic_visual_diff_ratio`, `synthetic_ssl_expiry_seconds`, ...) labelled
  by `front`, `journey`, `vantage`, `tenant`.
- Failure **artifacts** (screenshots, HARs, Playwright traces) go to object
  storage and Loki. **Baselines** (expected hashes, reference screenshots,
  expected DNS records) are committed to git so every intended change is
  reviewed via PR.

### Identities and secrets

- Dedicated **least-privilege synthetic test users** in Keycloak per realm and
  app, never real or admin accounts. Operator-portal journeys assert
  *reachability and post-login landing only* -- they perform no mutating
  actions.
- Credentials are handled like the platform's other secrets (ADR 0017). Dynamic
  credentials from OpenBao are a later upgrade.

### Alerting and self-healing

- New **PrometheusRule** alerts (front down, login failure, content-hash
  mismatch, visual-diff over threshold, cert or DNS drift, header or CSP drift)
  route through the existing Alertmanager. Integrity alerts are high-severity
  and routable to the ops agent's self-healing loop (ADR 0023). Alerting
  accounts for deploy windows to avoid noise.

### Repository placement

- Framework, blackbox config, Pushgateway, the Worker, the shared Playwright
  runner, and **platform-front journeys** live in the platform repository.
- **Tenant journeys** are declared in the tenant's repository and discovered and
  run by the framework, preserving tenant isolation (ADR 0033).

## Rationale

### Why blackbox-exporter + Playwright (and not k6)

blackbox-exporter already covers Layer A declaratively (HTTP, TLS, DNS and
header-regex probes, cert expiry) and is the LGTM-native choice -- it is scraped
like any other Prometheus target. Playwright is **already in the codebase** (the
website e2e suite) and is stronger than k6-browser for real Keycloak login,
regression assertions, and visual-diff. Adding k6 would introduce a *second*
browser-scripting model for no coverage we cannot already get. One scripting
tool, one probe tool.

### Why a hybrid vantage, with external probe-only

In-cluster checks are blind to DNS hijack, edge TLS problems, and ingress or
edge outages -- exactly the failures a user sees first. An external vantage
closes that gap. Making the external runner **probe-only** keeps login
credentials inside the platform (requirement 4) and keeps the external surface
trivial enough to run on a free Cloudflare Worker. The heavy, credential-bearing
work stays in-cluster where it belongs.

### Why Cloudflare Worker over AWS Lambda for phase 1

Cloudflare already manages our DNS and edge, so a Worker is zero new vendor,
free at our volume, and has built-in cron. Its one weakness -- no socket-level
TLS introspection for raw certificate fingerprinting -- is largely moot because
Cloudflare manages the edge certificate anyway; in-cluster blackbox `tls` checks
cover the origin. AWS Lambda (container + headless Chromium) is held in reserve
for a later phase if we want full *external* browser journeys or raw external
cert fingerprinting.

### Why baselines in git

Tamper detection is only as trustworthy as its baseline. Storing expected
hashes, reference screenshots, and expected DNS records in git means every
legitimate change to a front is an explicit, reviewed PR -- and anything that
changes without such a PR is, by definition, suspicious.

## Consequences

### Positive

- **Outside-in coverage**: outages and edge, DNS and TLS problems are detected
  before users report them.
- **Regression safety net**: login and critical paths are continuously exercised
  against the live stack.
- **Tamper visibility**: defacement, script injection, cert and DNS anomalies
  surface as alerts, not incidents.
- **Stack reuse**: no new observability backend; results live in Grafana and
  Alertmanager alongside everything else.
- **Self-hosted, credential-safe**: no synthetic credentials leave the platform;
  no SaaS dependency for the functional path.
- **Tenant-extensible**: tenants add journeys via their own repo.

### Negative and tradeoffs

- **New moving parts**: a Pushgateway, blackbox-exporter, a Playwright runner
  image + CronJobs, and a Worker to operate and patch.
- **Browser cost**: Layer B and C browser runs are heavier than probes;
  mitigated by frequent cheap Layer A and less-frequent browser journeys.
- **Visual-diff flakiness**: dynamic content causes false positives; mitigated
  with region masking and ratio thresholds (phase 2).
- **Synthetic-user management**: dedicated Keycloak users and their secrets must
  be created, scoped, and rotated per app and tenant.

### Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Synthetic-user blast radius | Least-privilege users; operator journeys are read-only (assert landing, no mutations) |
| Alert noise during deploys | Alerting accounts for deploy windows |
| Pushgateway as a stale-metric trap | Jobs push with a grouping key and delete or expire on completion; alert on push freshness |
| Push endpoint abuse | Authenticated endpoint with a rotating push token, scoped by network policy |
| External cert fingerprinting gap (Worker) | Accepted for phase 1 (Cloudflare manages the edge cert); revisit with AWS Lambda in a later phase |

## Implementation

High-level phasing:

- **Phase 1 (functional)**: Pushgateway + blackbox-exporter (Layer A
  in-cluster), Cloudflare Worker (Layer A external), Playwright runner and
  journeys for the first three fronts (Layer B), Grafana dashboard, core alerts.
- **Phase 2 (tamper)**: content-hash + visual-diff baselines, header, CSP, cert
  and DNS drift alerts, ops-agent triage hook, optional CSP report collector.
- **Phase 3 (optional)**: AWS Lambda external browser journeys + raw external
  cert fingerprinting, OpenBao dynamic credentials, tenant-journey
  auto-onboarding.

## Alternatives considered

### Grafana k6 and k6-browser for journeys

- **Pros**: purpose-built for synthetic + load, native Prometheus metrics,
  declarative CRDs via k6-operator.
- **Cons**: k6-browser is weaker than Playwright for complex Keycloak login and
  regression; introduces a second browser-scripting model alongside our existing
  Playwright suite; visual-diff and content-hash still custom.
- **Rejected**: no coverage gain over Playwright; more tools to maintain.

### Third-party SaaS synthetics (Grafana Cloud Synthetic Monitoring, Checkly)

- **Pros**: real multi-geo vantage points, near-zero infra, fastest to value.
- **Cons**: recurring cost and external dependency; **login and operator
  credentials would leave the platform**; less GitOps-native.
- **Rejected** for the functional path (conflicts with requirement 4). May be
  revisited *only* for credential-free external availability if the Worker
  proves insufficient.

### In-cluster only (no external vantage)

- **Pros**: simplest; nothing outside the cluster to operate.
- **Cons**: blind to DNS hijack, edge TLS, and ingress or edge outages -- the
  failures users see first.
- **Rejected**: fails requirement 1.

### AWS Lambda external runner for phase 1

- **Pros**: can run full headless-Chromium journeys and raw TLS cert
  fingerprinting externally.
- **Cons**: more setup; Playwright-on-Lambda needs 1.5 to 2 GB memory so
  frequent runs exceed pure free tier; new vendor surface for phase 1.
- **Deferred**: held in reserve for phase 3 if external browser journeys or raw
  external cert fingerprinting are wanted.

## Related decisions

None of these are published yet.

- ADR 0013: Observability stack -- the LGTM stack these checks feed into
- ADR 0023: Self-healing platform architecture -- consumer of integrity and
  availability signals
- ADR 0017: Secrets management strategy
- ADR 0018: Identity management strategy -- Keycloak synthetic test users
- ADR 0033: Multi-tenancy model and tenant isolation -- tenant-declared journeys

## References

- [Prometheus blackbox_exporter](https://github.com/prometheus/blackbox_exporter)
- [Prometheus Pushgateway](https://github.com/prometheus/pushgateway)
- [Playwright](https://playwright.dev/)
- [Cloudflare Workers Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/)
