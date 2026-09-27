# ADR 0041: Tenant Self-Service Egress (Capability #2)

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Consequences](#consequences)

## Status

Accepted

## Context

The tenant self-service track (ADR-0033 Amendment 2026-06-05) delivered capability #1,
`storage.manyfold.dk/ObjectBucket`. ADR-0007 and ADR-0033 named egress/firewall rules as
the next capability. The first tenant is default-deny egress, but the operator baseline
had accreted a set of blanket `toEntities: [world]` allows on 443 -- originally one for the
Cloudflare-fronted identity provider (`auth.<domain>`), then copies for the tenant portal's
external integrations (three third-party APIs, four FQDNs). Each permitted *any* outbound
HTTPS and provided no per-destination control.

## Decision

Introduce `network.manyfold.dk/EgressRule`, a Namespaced Crossplane v2 capability:

- The tenant authors a narrow `EgressRule` (a list of FQDNs, 443/TCP) in its own repo.
- A `ValidatingAdmissionPolicy` (FQDN-only, 443-only, tenant-label-bound) admits it to the
  tenant AppProject allowlist; raw `CiliumNetworkPolicy` stays excluded.
- A Composition (function-go-templating) renders an additive `CiliumNetworkPolicy`
  (`toFQDNs` + 443) via a newly-installed `provider-kubernetes`, scoped by RBAC to only
  `ciliumnetworkpolicies`.
- The baseline enables L7 DNS snooping and converts the operator-owned identity-provider
  egress (`auth.<domain>`) to `toFQDNs`. The three tenant-integration egresses are removed
  from the operator baseline and re-expressed as tenant-authored `EgressRule` claims in the
  tenant's repo, exercising the new capability for real. All blanket
  `world:443` allows are removed.

## Rationale

Egress is an in-cluster resource (no external state/credential), but a narrow claim schema
is safer to guard than admitting a full `CiliumNetworkPolicy`: the tenant literally cannot
express `world`/CIDR/non-443. Reusing the ObjectBucket self-service pattern keeps the track
consistent, and `provider-kubernetes` is a reusable building block for future in-cluster
capabilities. Moving the integration egresses to tenant-authored claims puts the
declaration of *which third parties the portal talks to* where it belongs -- in the tenant
repo, next to the integration code -- while the operator baseline retains only
platform-owned egress (the identity provider, `auth.<domain>`). The change is strictly tighter than the prior
`world:443` posture.

## Consequences

- provider-kubernetes can write `ciliumnetworkpolicies` cluster-wide (scoped RBAC, only the
  operator Composition drives it). Documented and accepted.
- Tenant-pod DNS now flows through Cilium's L7 DNS proxy (small latency; fail-closed if the
  proxy misbehaves). Rolled out dev-first with Hubble verification.
- **Rollout ordering is load-bearing.** The capability machinery (XRD, Composition, VAP,
  AppProject allowlist) must sync first, then the tenant `EgressRule` claims, then -- or
  together with -- the baseline cutover that removes the `world:443` allows. If the cutover
  lands before the tenant claims, the portal's calls to its three integrations are denied.
- The CEL FQDN validation uses a doubled backslash (`\\.`) in the regex: the YAML folded
  scalar passes backslashes through verbatim, but CEL itself processes string escapes, so a
  single `\.` fails to compile.
- "Any valid FQDN" suits a trusted first-party tenant; an operator-curated FQDN allowlist is
  the follow-up before onboarding untrusted tenants.
- Posture is now named-FQDN-only egress with no implicit broad allow.
