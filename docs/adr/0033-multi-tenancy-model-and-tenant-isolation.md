# ADR-0033: Multi-Tenancy Model, Tenant Naming & Isolation Guardrails

* Status: accepted
* Date: 2026-05-24
* Decider: Thomas
* Supersedes: none
* Amends: ADR-0002 (Resource naming)
* Cross-ref: the solution umbrella repository's ADR-0001 and ADR-0005 (private); the
  first tenant's platform onboarding design spec of 2026-05-22 (private);
  ADR-0034 (document signing service — first shared cross-tenant tool, in scope of the
  realm-brokering gate below)

## Table of Contents

- [Context](#context)
- [Decision](#decision)
- [Blueprint Definition of Done](#blueprint-definition-of-done-gates-before-an-untrusted-external-tenant)
- [Consequences](#consequences)
- [Amendment 2026-06-05: tenant self-service infra claims (ObjectBucket capability #1)](#amendment-2026-06-05-tenant-self-service-infra-claims-objectbucket-capability-1)
  - [Blueprint DoD -- additional gate](#blueprint-dod----additional-gate-this-list-is-the-canonical-tracker-for-outstanding-hardening)
- [Rollback](#rollback)

## Context

The first tenant is the platform's first co-tenant and the blueprint for hosting
external companies. The platform was single-tenant (app-of-apps on
`project: default`, no AppProjects, no quotas, no restricted PSA, no
default-deny network policy). This ADR introduces the multi-tenancy primitives.

## Decision

- **Logical multi-tenant**: shared cluster + platform services, per-tenant
  namespace(s), AppProject, Keycloak realm, default-deny network, scoped secrets.
- **Naming**: tenant namespaces are `<tenant>-<env>` (e.g. `<tenant>-prod`),
  tenant-first (extends ADR-0002's `<env>-<domain>`); mandatory label
  `manyfold.dk/tenant`.
- **Boundary**: an ArgoCD AppProject with empty `clusterResourceWhitelist` and an
  explicit `namespaceResourceWhitelist` allowlist that EXCLUDES ResourceQuota,
  LimitRange, NetworkPolicy, CiliumNetworkPolicy, ServiceMonitor, RBAC kinds, and
  (interim) HTTPRoute -- the last because the shared Gateway accepts routes from all
  namespaces, so HTTPRoute is withheld until hostname admission exists (see DoD).
- **Cost/exposure**: the per-namespace ResourceQuota denies LoadBalancer and NodePort
  Services (`services.loadbalancers`/`services.nodeports: "0"`) and caps
  `requests.storage` -- interim enforcement of the NodePort/LoadBalancer DoD gate.
- **GitOps**: an operator-owned ApplicationSet forces `spec.project` and the
  destination namespace; tenant repos hold plain manifests only.
- **Pod security**: tenant namespaces enforce PSA `restricted`.
- **Network**: tenant namespaces use Cilium default-deny (`enableDefaultDeny` plus
  empty `ingress`/`egress` rule fields, which the CRD schema requires) with an
  operator-owned baseline allow (DNS, intra-tenant, gateway, Prometheus scrape).
- **Admission engine (committed, deferred impl)**: built-in
  `ValidatingAdmissionPolicy` (Kyverno fallback only if CEL falls short).
- **Restore**: a tenant restore bundle (Git config + realm/OpenBao carve-outs +
  namespace/PV backup), not a namespace backup alone.

## Blueprint Definition of Done (gates before an UNTRUSTED external tenant)

- [ ] `ValidatingAdmissionPolicy`: deny NodePort/LoadBalancer Services; restrict
      HTTPRoute/Gateway hostnames to tenant domains; require `manyfold.dk/tenant`
      label; if ServiceMonitor is re-added to the tenant allowlist, constrain its
      `namespaceSelector` to the tenant's own namespaces + cap scrape volume.
      INTERIM (already in place pending the VAP): NodePort/LoadBalancer Services are
      denied via ResourceQuota, and HTTPRoute is withheld from the tenant allowlist.
      The VAP will replace these with per-hostname/label admission and allow HTTPRoute
      back in.
- [ ] Tenant-user observability: per-tenant datasources (Loki `X-Scope-OrgID` +
      Prometheus/Mimir tenancy) OR a dedicated per-tenant Grafana instance.
- [x] Keycloak realm brokering wired (when a tenant user needs a shared tool).
      SCOPE: the **document-signing service (ADR-0034)** is the first such
      shared cross-tenant tool and the concrete driver for this gate — its multi-tenant
      web form is blocked on realm brokering (its CLI/programmatic paths are not, as those
      validate JWTs from a multi-issuer allowlist directly).
      DONE (2026-05-28): the first tenant onboarded as a signing-service tenant — own
      archive bucket (per tenant, Object-Lock), own oauth2-proxy client + CLI
      device-flow client + operators group in the tenant's realm, a second
      oauth2-proxy instance on a tailnet hostname, and the tenant's issuer mapped
      to `tenant=<tenant>` via `OIDC_ISSUERS`. The CLI's token cache is now scoped
      per `(issuer, clientId)` so a Manyfold token can't silently be reused for
      the tenant. See the tenant onboarding plan of 2026-05-27 (private) and
      the Tenants section of the document-signing service runbook.
      NOTE: the project-scoped `<tenant>-admin` ArgoCD RBAC role (added in this
      landing zone) is still INERT — ArgoCD authenticates against the single
      `manyfold` realm, so a tenant-realm role does not reach ArgoCD's
      `groups` claim. Activation requires either brokering the tenant realm
      into the ArgoCD OIDC client (with a realm-roles-to-groups mapper) or
      assigning the `<tenant>-admin` role within the `manyfold` realm. That is
      a separate piece of work; this checkbox covers the signing service, the
      concrete driver named in the SCOPE line.

(Crossplane `TenantClaim` is an ergonomics evolution, NOT a security gate.)

## Amendment (2026-06-07): first per-app/per-ServiceAccount OpenBao scoping

The day-one tenant OpenBao role binds all ServiceAccounts (`*`) in the tenant namespaces
to a tenant-wide policy, with per-app/per-SA scoping deferred to "when apps with differing
secret sensitivity exist" (see `setup-openbao-k8s-auth.sh` and the first tenant's own
ADR-0005, private). That condition is now met: the first tenant's portal keeps a
shared-credential vault beside onboarding PII. **ADR-0040** introduces the first per-app scoping -- a Kubernetes auth role
(`<tenant>-<app>`) bound to a single ServiceAccount, carrying a policy scoped to one
OpenBao Transit key. This neither relaxes nor replaces the tenant-wide `<tenant>-reader`
role; it adds a narrower, app-specific role alongside it. The reusable primitive is
`setup-openbao-k8s-auth.sh create-app-vault <tenant> <app> <sa>`.

## Consequences

- New primitives to maintain; tenant #2 is copy-paste from `platform/tenants/_template/`.
- ADR-0001's revisit trigger ("strict repo-level access control") is now met.

## Amendment 2026-06-05: tenant self-service infra claims (ObjectBucket capability #1)

The note "(Crossplane `TenantClaim` is an ergonomics evolution, NOT a security gate.)"
is now implemented for the first capability, following the same arc HTTPRoute took:
excluded from the tenant allowlist until a `ValidatingAdmissionPolicy` made it safe,
then admitted.

**Pattern (capability-agnostic) -- tenant self-service infrastructure via guard-railed claims:**
1. Operator owns the XRD/Composition/provider credentials/OpenTofu state -- always.
2. The tenant AppProject `namespaceResourceWhitelist` admits the specific claim kind.
3. A per-capability `ValidatingAdmissionPolicy` (bound by `manyfold.dk/tenant`) enforces
   tenant-safe constraints.
4. The tenant authors the claim in its repo `gitops/`; the operator ApplicationSet syncs
   it into the tenant namespace; results land as a `Secret` in that namespace.

**Capability #1 -- `storage.manyfold.dk/ObjectBucket`:** added to the first tenant's AppProject
allowlist behind `platform/tenants/<tenant>/objectbucket-policy.yaml` (name prefix
`<tenant>-`, provider `hetzner` -- the only provider with a seeded tenant ProviderConfig;
`cloudflare-r2` is admitted only once a tenant R2 ProviderConfig is seeded, same pattern --
EU region set explicitly, no Object Lock, `manyfold.dk/tenant` label). Each tenant namespace is seeded with an operator-owned,
**object-storage-only** `hetzner` ProviderConfig (no hcloud token) plus a distinct,
revocable Hetzner S3 credential set. Generalized into `platform/tenants/_template/`.

**Future capabilities (same pattern, NOT yet built):** synthetic checks (ADR-0036) and
egress/firewall rules (ADR-0007). The latter needs its own guardrail design + ADR
amendment because it touches the default-deny network posture directly.

### Blueprint DoD -- additional gate (this list is the canonical tracker for outstanding hardening)

- [ ] **Cross-tenant object-storage isolation:** tenant-facing S3 credentials must not be
      able to reach other tenants' buckets. Hetzner Object Storage keys are project-wide
      and per-bucket IAM is unavailable, so a hard boundary requires a per-tenant Hetzner
      project (or migration to a provider with per-bucket IAM, e.g. Cloudflare R2).
      INTERIM (the trusted first co-tenant): each tenant gets a distinct, revocable
      Hetzner S3 credential via its own object-storage-only ProviderConfig; the hcloud
      token is never placed in a tenant namespace. Mandatory before an UNTRUSTED tenant.

## Rollback

Delete `platform/tenants/<tenant>/`, the landing-zone Application, the realm
config key, the OpenBao block, and the tenant's observability/backup resources.
