# ADR 0018: Identity Management Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
  - [Requirements](#requirements)
  - [Identity Types](#identity-types)
  - [Current State](#current-state)
  - [Tools Evaluated](#tools-evaluated)
  - [Detailed User Identity Provider Comparison](#detailed-user-identity-provider-comparison)
  - [Summary Ranking for This Platform](#summary-ranking-for-this-platform)
  - [Selection Rationale](#selection-rationale)
- [Decision](#decision)
  - [Tier 1: User Identity - Keycloak](#tier-1-user-identity---keycloak)
  - [Tier 2: Workload Identity - SPIRE](#tier-2-workload-identity---spire)
  - [Architecture](#architecture)
  - [Integration Points](#integration-points)
  - [RBAC Strategy](#rbac-strategy)
- [Rationale](#rationale)
  - [Why Keycloak for User Identity?](#why-keycloak-for-user-identity)
  - [Why Not Other User Identity Solutions?](#why-not-other-user-identity-solutions)
  - [Why SPIRE for Workload Identity?](#why-spire-for-workload-identity)
  - [Why Not Other Workload Identity Solutions?](#why-not-other-workload-identity-solutions)
  - [Why Two-Tier Approach?](#why-two-tier-approach)
- [Consequences](#consequences)
  - [Positive](#positive)
  - [Negative](#negative)
  - [Risks and Mitigations](#risks-and-mitigations)
- [Implementation](#implementation)
  - [Prerequisites](#prerequisites)
  - [Phase 1: Keycloak Deployment](#phase-1-keycloak-deployment)
  - [Phase 2: ArgoCD OIDC Integration](#phase-2-argocd-oidc-integration)
  - [Phase 3: Grafana OIDC Integration](#phase-3-grafana-oidc-integration)
  - [Phase 4: SPIRE Deployment](#phase-4-spire-deployment)
  - [Phase 5: Workload Registration](#phase-5-workload-registration)
  - [Directory Structure](#directory-structure)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)
- [Notes](#notes)

## Status

Accepted

**Note (2026-06-12):** Tier 1 (Keycloak) is implemented and live in the cloud cluster
(see `platform/components/keycloak/`). Tier 2 (SPIRE) is decided but not implemented; no
workload-identity deployment exists yet.

## Context

The Manyfold Platform requires identity management for both human users and system workloads. As the platform matures, secure authentication, authorization, and service-to-service identity become critical for security, compliance, and operational efficiency.

### Requirements

1. **User Authentication**: Centralized authentication for platform services (dashboards, APIs)
2. **Single Sign-On (SSO)**: Unified login experience across platform components
3. **Standard Protocols**: Support for OAuth 2.0, OpenID Connect (OIDC), SAML
4. **Workload Identity**: Cryptographic identity for services (pods, jobs)
5. **Zero-Trust Foundation**: Enable workload-to-workload authentication without shared secrets
6. **Service Mesh Ready**: Compatible with future service mesh adoption (mTLS)
7. **Open Source**: Align with project philosophy
8. **Kubernetes Native**: Deep integration with Kubernetes primitives
9. **Scalable**: Support growth from personal platform to potential multi-tenant use

### Identity Types

| Identity Type | Description | Example Use Cases |
|---------------|-------------|-------------------|
| **User Identity** | Human operators, developers | ArgoCD login, Grafana dashboards, API access |
| **Workload Identity** | Services, pods, jobs | Service-to-service auth, database access, cloud API access |
| **Machine Identity** | External systems, CI/CD | Tekton pipelines, external integrations |

### Current State

- **ArgoCD**: Uses local admin account
- **Grafana**: Uses static admin credentials
- **Services**: No authentication (internal cluster network)
- **Workloads**: Kubernetes ServiceAccount tokens only (not suitable for external systems)

### Tools Evaluated

#### User Identity Management

| Tool | Type | Pros | Cons |
|------|------|------|------|
| **Keycloak** | Self-hosted | Feature-rich, mature, extensive protocol support | Resource-heavy, complex |
| **Authentik** | Self-hosted | Modern UI, lighter weight | Younger project, smaller community |
| **Dex** | Self-hosted | Lightweight OIDC connector | Limited features, no user management |
| **Zitadel** | Self-hosted/SaaS | Modern, cloud-native | Newer, less ecosystem integration |
| **Auth0** | SaaS | Zero ops, extensive features | Cost at scale, vendor lock-in |

#### Workload Identity

| Tool | Type | Pros | Cons |
|------|------|------|------|
| **SPIRE/SPIFFE** | Self-hosted | Industry standard (CNCF), cryptographic identity | Learning curve, operational overhead |
| **Istio Identity** | Service Mesh | Built-in mTLS, integrated | Requires full Istio adoption |
| **Kubernetes ServiceAccount** | Native | Simple, built-in | Not suitable for external systems |
| **cert-manager + CSI** | K8s-native | Certificate-based identity | Manual rotation, less automated |

### Detailed User Identity Provider Comparison

#### 1. Keycloak (Selected)

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Excellent | Native OIDC, extensively documented |
| Grafana Integration | Excellent | Built-in support, role mapping |
| Kubernetes Deployment | Excellent | Official Helm chart, operators available |
| Resource Usage | ~1GB RAM | Heavier, but acceptable for features |
| UI/UX | Good | Functional, enterprise-focused |
| Community | Excellent | Largest OSS identity community |
| Protocol Support | Excellent | OIDC, OAuth2, SAML, LDAP, Kerberos |
| Maturity | Excellent | 10+ years, Red Hat backing |

**Strengths**: Industry standard, extensive documentation, every integration scenario documented.

**Weaknesses**: Resource-heavy, Java-based (slower startup), complex admin UI.

#### 2. Authentik (Strong Alternative)

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Excellent | Native OIDC, documented |
| Grafana Integration | Excellent | Built-in OAuth provider |
| Kubernetes Deployment | Excellent | Helm chart, lightweight |
| Resource Usage | ~512MB RAM | Much lighter than Keycloak |
| UI/UX | Excellent | Modern, intuitive interface |
| Community | Good | Smaller but active, growing fast |
| Protocol Support | Good | OIDC, SAML, LDAP, proxy auth |
| Maturity | Good | Since 2020, production-ready |

**Strengths**: Modern UX, lower resource footprint, Python-based (easier to extend).

**Weaknesses**: Smaller ecosystem, fewer enterprise case studies, younger project.

**Best for**: Users who prioritize modern UX and lower resource usage over ecosystem size.

#### 3. Ory Stack (Kratos + Hydra)

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Good | OIDC via Hydra |
| Grafana Integration | Good | Standard OAuth |
| Kubernetes Deployment | Excellent | Cloud-native, microservices |
| Resource Usage | Very Light | Scale components independently |
| UI/UX | Limited | Headless - build your own UI |
| Community | Strong | CNCF-adjacent, enterprise backing |
| Protocol Support | Good | OAuth2, OIDC (Hydra is certified) |
| Maturity | Good | Production-ready, used by major companies |

**Components**:
- **Kratos**: Identity management (users, passwords, MFA)
- **Hydra**: OAuth2/OIDC server (certified implementation)
- **Oathkeeper**: API gateway/proxy (optional)
- **Keto**: Authorization/permissions (optional)

**Strengths**: Maximum flexibility, true cloud-native architecture, certified OIDC.

**Weaknesses**: Requires assembling multiple components, no built-in admin UI.

**Best for**: Teams wanting cloud-native architecture and willing to build custom UI.

#### 4. Zitadel

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Good | OIDC compliant |
| Grafana Integration | Good | Generic OAuth works |
| Kubernetes Deployment | Good | Helm chart available |
| Resource Usage | ~512MB RAM | Efficient Go binary |
| UI/UX | Excellent | Very modern, polished |
| Community | Growing | Swiss company backing |
| Protocol Support | Good | OIDC, SAML |
| Maturity | Moderate | Since 2019, evolving API |

**Strengths**: Excellent developer experience, modern architecture, good documentation.

**Weaknesses**: Smaller community, fewer pre-built integrations, API still evolving.

**Best for**: Users prioritizing developer experience and willing to adopt a newer project.

#### 5. Authelia

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Limited | Forward-auth primary, OIDC in beta |
| Grafana Integration | Limited | Via forward-auth or OIDC (beta) |
| Kubernetes Deployment | Excellent | Very lightweight, Helm chart |
| Resource Usage | ~50MB RAM | Extremely light |
| UI/UX | Good | Clean, simple, focused |
| Community | Good | Active, good documentation |
| Protocol Support | Limited | Forward-auth, OIDC (beta) |
| Maturity | Good | Stable for its scope |

**Strengths**: Extremely lightweight, great for simple auth gateway scenarios.

**Weaknesses**: OIDC support still maturing, primarily a forward-auth solution.

**Best for**: Simple authentication gateway needs, complement to another IdP.

#### 6. Dex

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Excellent | ArgoCD bundles Dex by default |
| Grafana Integration | Good | OIDC provider |
| Kubernetes Deployment | Excellent | Very lightweight |
| Resource Usage | ~30MB RAM | Minimal footprint |
| UI/UX | None | No user management UI |
| Community | Strong | CNCF, part of Kubernetes ecosystem |
| Protocol Support | Good | OIDC connector to upstream IdPs |
| Maturity | Excellent | Battle-tested |

**Strengths**: Lightweight OIDC federation, great for connecting to existing IdPs.

**Weaknesses**: Not a standalone IdP - requires upstream identity source.

**Best for**: Federating to existing identity providers (GitHub, LDAP, Google).

#### 7. Casdoor

| Aspect | Rating | Notes |
|--------|--------|-------|
| ArgoCD Integration | Good | OIDC/OAuth2 |
| Grafana Integration | Good | Standard OAuth |
| Kubernetes Deployment | Manual | No official Helm chart |
| Resource Usage | Light | Go-based |
| UI/UX | Good | Modern React UI |
| Community | Growing | Chinese origin, expanding globally |
| Protocol Support | Good | OIDC, SAML, CAS, OAuth2 |
| Maturity | Moderate | Less ecosystem integration |

**Strengths**: Feature-rich, lighter than Keycloak, good protocol support.

**Weaknesses**: Less Kubernetes ecosystem integration, smaller Western community.

**Best for**: Users wanting Keycloak-like features with lighter footprint.

### Summary Ranking for This Platform

| Rank | Tool | Match | Recommendation |
|------|------|-------|----------------|
| 1 | **Keycloak** | 95% | Best overall - ecosystem, maturity, documentation |
| 2 | **Authentik** | 88% | Best alternative - modern, lighter, good integrations |
| 3 | **Ory Stack** | 85% | Best for cloud-native purists, more assembly required |
| 4 | **Zitadel** | 80% | Good modern option, smaller ecosystem |
| 5 | **Dex** | 50% | Only if federating to external IdP |
| 6 | **Authelia** | 60% | Best as complement, not standalone |
| 7 | **Casdoor** | 55% | Less K8s ecosystem integration |

### Selection Rationale

For this **personal platform engineering lab** with learning goals:

**Primary choice: Keycloak** - The ecosystem integration (ArgoCD, Grafana, Tekton all have documented Keycloak examples), community size, and feature completeness make it the most practical choice. Resource overhead (~1GB) is acceptable for a learning platform.

**If resources become constrained: Authentik** - Best balance of features, modern UX, and lighter footprint. Growing community and good Kubernetes support make it a viable fallback.

**If adopting cloud-native patterns: Ory Stack** - More work to set up but teaches microservices identity patterns and provides maximum flexibility.

## Decision

We will implement a **two-tier identity strategy**:

### Tier 1: User Identity - Keycloak

**Keycloak** will serve as the central identity provider for human users and external systems.

### Tier 2: Workload Identity - SPIRE

**SPIRE** (the SPIFFE Runtime Environment) will provide cryptographic identity for workloads, enabling zero-trust service-to-service authentication.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         User Identity (Keycloak)                         │
│                                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │   ArgoCD     │  │   Grafana    │  │   Tekton     │  │  Custom Apps │ │
│  │   (OIDC)     │  │   (OIDC)     │  │   (OIDC)     │  │   (OIDC)     │ │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘ │
│           │                │                │                │          │
│           └────────────────┴────────────────┴────────────────┘          │
│                                     │                                    │
│                                     ▼                                    │
│                          ┌──────────────────┐                           │
│                          │     Keycloak     │                           │
│                          │  (Identity Hub)  │                           │
│                          └──────────────────┘                           │
└─────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────┐
│                       Workload Identity (SPIRE)                          │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                        SPIRE Server                               │   │
│  │  - Issues SVIDs (SPIFFE Verifiable Identity Documents)           │   │
│  │  - Manages workload registration                                  │   │
│  │  - Integrates with Kubernetes for attestation                    │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                     │                                    │
│              ┌──────────────────────┼──────────────────────┐            │
│              ▼                      ▼                      ▼            │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐      │
│  │   SPIRE Agent    │  │   SPIRE Agent    │  │   SPIRE Agent    │      │
│  │    (Node 1)      │  │    (Node 2)      │  │    (Node N)      │      │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘      │
│           │                      │                      │               │
│           ▼                      ▼                      ▼               │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐      │
│  │   Workload Pod   │  │   Workload Pod   │  │   Workload Pod   │      │
│  │  (SVID mounted)  │  │  (SVID mounted)  │  │  (SVID mounted)  │      │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘      │
└─────────────────────────────────────────────────────────────────────────┘
```

### Integration Points

| Component | Keycloak Integration | SPIRE Integration |
|-----------|---------------------|-------------------|
| ArgoCD | OIDC authentication, RBAC groups | - |
| Grafana | OIDC authentication, org mapping | - |
| Tekton Dashboard | OIDC authentication | - |
| Backend Services | JWT validation | mTLS, SVID-based auth |
| Database Access | - | SVID for dynamic credentials |
| External APIs | - | Workload identity federation |

### RBAC Strategy

Role-Based Access Control will be implemented through Keycloak's group and role system, with claims propagated via OIDC tokens to downstream applications.

#### Keycloak RBAC Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Keycloak Realm                          │
│                                                                 │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐        │
│  │ Realm Roles │    │Client Roles │    │   Groups    │        │
│  │ - admin     │    │ argocd:     │    │ - Platform  │        │
│  │ - user      │    │   - admin   │    │   Admins    │        │
│  │ - readonly  │    │   - readonly│    │ - Developers│        │
│  └─────────────┘    │ grafana:    │    │ - Viewers   │        │
│         │           │   - editor  │    └──────┬──────┘        │
│         │           │   - viewer  │           │               │
│         │           └─────────────┘           │               │
│         └─────────────────┬───────────────────┘               │
│                           ▼                                    │
│                    ┌─────────────┐                            │
│                    │    Users    │                            │
│                    └─────────────┘                            │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼ OIDC Token with Claims
┌─────────────────────────────────────────────────────────────────┐
│ {                                                               │
│   "sub": "user-uuid",                                          │
│   "groups": ["Platform Admins", "Developers"],                 │
│   "realm_access": { "roles": ["admin", "user"] },              │
│   "resource_access": {                                         │
│     "argocd": { "roles": ["admin"] },                          │
│     "grafana": { "roles": ["editor"] }                         │
│   }                                                            │
│ }                                                              │
└─────────────────────────────────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
┌───────────────┐  ┌───────────────┐  ┌───────────────┐
│    ArgoCD     │  │    Grafana    │  │    Tekton     │
│ groups claim  │  │ role_attribute│  │ groups claim  │
│ → RBAC policy │  │ → Org roles   │  │ → Dashboard   │
└───────────────┘  └───────────────┘  └───────────────┘
```

#### Platform Roles

| Role | Description | ArgoCD | Grafana | Tekton |
|------|-------------|--------|---------|--------|
| `platform-admin` | Full platform access | Admin (all projects) | Admin | Full access |
| `developer` | Application development | Write (assigned projects) | Editor | Run pipelines |
| `viewer` | Read-only access | Read-only | Viewer | View only |

#### Group-to-Role Mapping

| Keycloak Group | Realm Roles | Client Roles | Use Case |
|----------------|-------------|--------------|----------|
| `Platform Admins` | `admin` | `argocd:admin`, `grafana:admin` | Platform operators |
| `Developers` | `user` | `argocd:developer`, `grafana:editor` | Application teams |
| `Viewers` | `readonly` | `argocd:readonly`, `grafana:viewer` | Stakeholders, auditors |

#### ArgoCD RBAC Integration

The example below is illustrative. Each developer team gets its own role, scoped to the
team's ArgoCD AppProject, and its own Keycloak group (a subgroup of `Developers`).
`<team>` stands for the team, `<team-group>` for its Keycloak group and `<team-project>`
for its AppProject; repeat the block per team. Do not add several projects to one shared
role: every member of a group mapped to that role would get every project. The example
has no `policy.default`, so a user without a group mapping gets no access; viewers get
read access through the explicit `Viewers` mapping. This is the target design, not the
deployed policy. The deployed policy (`platform/argocd/overlays/cloud/argocd-rbac-cm.yaml`)
differs: for example, it sets `policy.default: role:readonly`, so every authenticated
user can read every application there.

```yaml
# argocd-rbac-cm ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: argocd-rbac-cm
  namespace: argocd
data:
  policy.csv: |
    # Platform admins get full access
    g, Platform Admins, role:admin

    # One role per developer team: view, sync and update apps in the team's project only
    p, role:<team>-developer, applications, get, <team-project>/*, allow
    p, role:<team>-developer, applications, sync, <team-project>/*, allow
    p, role:<team>-developer, applications, update, <team-project>/*, allow
    p, role:<team>-developer, projects, get, <team-project>, allow
    p, role:<team>-developer, logs, get, <team-project>/*, allow
    p, role:<team>-developer, exec, create, */*, deny
    g, <team-group>, role:<team>-developer

    # Viewers get read-only access
    p, role:viewer, applications, get, */*, allow
    p, role:viewer, logs, get, */*, allow
    g, Viewers, role:viewer
  scopes: '[groups]'
```

#### Grafana Role Mapping

```yaml
# Grafana OAuth configuration
grafana:
  grafana.ini:
    auth.generic_oauth:
      role_attribute_path: |
        contains(groups[*], 'Platform Admins') && 'Admin' ||
        contains(groups[*], 'Developers') && 'Editor' ||
        'Viewer'
      org_mapping: |
        Platform Admins:1:Admin
        Developers:1:Editor
        Viewers:1:Viewer
```

#### RBAC Comparison: Keycloak vs Authentik

| Feature | Keycloak | Authentik |
|---------|----------|-----------|
| Realm/Client Roles | Yes - hierarchical | Groups only |
| Composite Roles | Yes | No |
| Fine-Grained Authorization | Yes (UMA, policies) | Limited |
| Group Hierarchy | Yes | Yes |
| Attribute-Based (ABAC) | Yes | Partial (expressions) |
| Custom Token Claims | Protocol mappers | Property mappings (Python) |
| Learning Curve | Steep | Gentle |

**Keycloak advantages**: Fine-grained authorization services, composite roles, UMA delegation.

**Authentik advantages**: Simpler model, Python-based property mappings, easier to understand.

For this platform, Keycloak's RBAC is chosen because:
1. ArgoCD and Grafana have well-documented Keycloak integration examples
2. Fine-grained authorization enables future per-project access control
3. Client roles allow scoped permissions per application

## Rationale

### Why Keycloak for User Identity?

**Industry Standard:**
- Most widely deployed open-source identity solution
- Extensive documentation and community resources
- Battle-tested at massive scale (Red Hat SSO)
- CNCF ecosystem integrations

**Protocol Support:**
- Full OAuth 2.0 and OpenID Connect support
- SAML 2.0 for legacy integrations
- LDAP/Active Directory federation
- Social login providers (GitHub, Google)

**Feature Set:**
- User management and self-service
- Fine-grained authorization services
- Multi-factor authentication (MFA/2FA)
- Identity brokering
- Customizable login flows

**Kubernetes Integration:**
- Official Helm chart and operators
- Well-documented ArgoCD/Grafana integrations
- JWT tokens work natively with Kubernetes RBAC

### Why Not Other User Identity Solutions?

**Authentik:**
- Modern and lighter weight
- Smaller community and ecosystem
- Less mature integrations with enterprise tools
- Rejected: Keycloak's maturity and ecosystem outweigh resource savings

**Dex:**
- Lightweight OIDC connector
- No user management (connector only)
- Requires external user directory
- Rejected: Need full identity management, not just OIDC proxy

**Zitadel:**
- Modern, cloud-native design
- Newer project with evolving API
- Fewer pre-built integrations
- Rejected: Less proven, smaller ecosystem

**Auth0/Okta (SaaS):**
- Zero operational overhead
- Excellent developer experience
- Cost scales with users
- Vendor lock-in concerns
- Rejected: Self-hosted preference, learning goals

### Why SPIRE for Workload Identity?

**SPIFFE Standard:**
- SPIFFE (Secure Production Identity Framework for Everyone) is a CNCF project
- Defines universal identity for workloads
- Vendor-neutral, widely adopted
- SPIRE is the reference implementation

**Cryptographic Identity:**
- X.509 SVIDs (SPIFFE Verifiable Identity Documents)
- Short-lived, automatically rotated certificates
- No shared secrets or static credentials
- Enables true zero-trust architecture

**Kubernetes Native:**
- Automatic workload attestation via node agents
- Pod identity based on ServiceAccount, namespace, labels
- No application code changes for basic identity
- CSI driver for automatic SVID mounting

**Federation Capabilities:**
- Workload identity federation with cloud providers
- SVID-to-cloud-IAM mapping (AWS IRSA, GCP Workload Identity)
- Cross-cluster trust without network exposure
- OIDC token exchange support

**Service Mesh Foundation:**
- Works standalone or with service mesh
- Cilium supports SPIFFE for mTLS
- Future Istio/Linkerd integration possible
- Identity layer independent of network layer

### Why Not Other Workload Identity Solutions?

**Kubernetes ServiceAccount Only:**
- JWT tokens not accepted by external systems
- No automatic rotation
- Cluster-bound identity only
- Rejected: Insufficient for zero-trust or external integration

**Istio Identity:**
- Requires full Istio service mesh
- Heavy resource overhead
- Coupled to network proxy
- Rejected: SPIRE provides identity without mesh commitment

**cert-manager + CSI Driver:**
- Certificate-based approach
- Manual registration required
- No automatic workload attestation
- Rejected: SPIRE automates what cert-manager requires manually

### Why Two-Tier Approach?

**Separation of Concerns:**
- User identity and workload identity have different requirements
- Users need UX (login pages, MFA), workloads need automation
- Different security models and lifecycle management
- Cleaner architecture than forcing one tool to do both

**Best of Breed:**
- Keycloak excels at user identity
- SPIRE excels at workload identity
- Combining them is common in production environments
- OIDC federation bridges the two when needed

**Gradual Adoption:**
- Can deploy Keycloak first for immediate SSO benefits
- Add SPIRE later as workload identity needs grow
- Independent scaling and lifecycle
- Risk isolation

## Consequences

### Positive

- **Unified SSO**: Single login for all platform dashboards
- **Zero-Trust Ready**: Cryptographic workload identity enables service mesh and external integration
- **Standards-Based**: OIDC and SPIFFE are industry standards with wide tooling support
- **Cloud Portable**: Workload identity federation works with AWS, GCP, Azure
- **Audit Trail**: Both tools provide detailed authentication/authorization logs
- **Future-Proof**: Foundation for service mesh, multi-cluster, and enterprise patterns
- **Open Source**: Full control, no vendor lock-in

### Negative

- **Resource Overhead**: Keycloak requires ~1GB RAM, SPIRE agents on each node
- **Operational Complexity**: Two identity systems to manage
- **Learning Curve**: SPIFFE/SPIRE concepts require study
- **Bootstrap Complexity**: Identity must be bootstrapped before apps relying on it
- **Database Dependency**: Keycloak requires PostgreSQL (can share with platform DB)

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Keycloak resource usage | Start with single replica, scale as needed |
| SPIRE learning curve | Incremental adoption, start with simple workloads |
| Database dependency | Use existing PostgreSQL, implement backups |
| Bootstrap ordering | Document dependency chain, automate in cluster.sh |
| Token/certificate expiry | Configure appropriate lifetimes, test rotation |

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead), and `cluster.sh` with it.

## Implementation

### Prerequisites

1. PostgreSQL database (from existing platform or dedicated)
2. DNS/ingress for Keycloak UI
3. Persistent storage for Keycloak and SPIRE Server

### Phase 1: Keycloak Deployment

```yaml
# ArgoCD Application for Keycloak
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: keycloak
  namespace: argocd
spec:
  project: platform
  source:
    repoURL: https://codecentric.github.io/helm-charts
    chart: keycloakx
    targetRevision: 2.4.x
    helm:
      values: |
        replicas: 1
        database:
          vendor: postgres
          hostname: postgresql.database.svc
          database: keycloak
        http:
          relativePath: /auth
        ingress:
          enabled: true
          hostname: keycloak.localhost
  destination:
    server: https://kubernetes.default.svc
    namespace: keycloak
```

### Phase 2: ArgoCD OIDC Integration

```yaml
# ArgoCD ConfigMap patch for Keycloak OIDC
apiVersion: v1
kind: ConfigMap
metadata:
  name: argocd-cm
  namespace: argocd
data:
  url: https://argocd.localhost
  oidc.config: |
    name: Keycloak
    issuer: https://keycloak.localhost/auth/realms/manyfold
    clientID: argocd
    clientSecret: $oidc.keycloak.clientSecret
    requestedScopes: ["openid", "profile", "email", "groups"]
```

### Phase 3: Grafana OIDC Integration

```yaml
# Grafana values for OIDC
grafana:
  grafana.ini:
    auth.generic_oauth:
      enabled: true
      name: Keycloak
      client_id: grafana
      client_secret: ${GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET}
      scopes: openid profile email groups
      auth_url: https://keycloak.localhost/auth/realms/manyfold/protocol/openid-connect/auth
      token_url: https://keycloak.localhost/auth/realms/manyfold/protocol/openid-connect/token
      api_url: https://keycloak.localhost/auth/realms/manyfold/protocol/openid-connect/userinfo
      role_attribute_path: contains(groups[*], 'admin') && 'Admin' || 'Viewer'
```

### Phase 4: SPIRE Deployment

```yaml
# SPIRE Server Deployment (simplified)
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: spire
  namespace: argocd
spec:
  project: platform
  source:
    repoURL: https://spiffe.github.io/helm-charts-hardened
    chart: spire
    targetRevision: 0.22.x
    helm:
      values: |
        global:
          spire:
            trustDomain: <domain>
        spire-server:
          controllerManager:
            enabled: true
          nodeAttestor:
            k8sPsat:
              enabled: true
        spire-agent:
          workloadAttestors:
            k8s:
              enabled: true
  destination:
    server: https://kubernetes.default.svc
    namespace: spire-system
```

### Phase 5: Workload Registration

```yaml
# SPIRE ClusterSPIFFEID for automatic workload registration
apiVersion: spire.spiffe.io/v1alpha1
kind: ClusterSPIFFEID
metadata:
  name: website-backend
spec:
  spiffeIDTemplate: "spiffe://<domain>/ns/{{ .PodMeta.Namespace }}/sa/{{ .PodSpec.ServiceAccountName }}"
  podSelector:
    matchLabels:
      app: website-backend
  namespaceSelector:
    matchLabels:
      spire-workload: "true"
```

### Directory Structure

```
platform/
├── identity/
│   ├── keycloak/
│   │   ├── kustomization.yaml
│   │   ├── namespace.yaml
│   │   ├── application.yaml
│   │   └── realm-export.json      # Manyfold realm configuration
│   └── spire/
│       ├── kustomization.yaml
│       ├── namespace.yaml
│       ├── application.yaml
│       └── cluster-spiffe-ids/    # Workload registration templates
│           ├── website-backend.yaml
│           └── tekton-pipelines.yaml
└── argocd/
    └── applications/
        ├── keycloak.yaml
        └── spire.yaml
```

## Alternatives Considered

### Single Identity System

**Option**: Use only Keycloak with service accounts for workloads
- **Pros**: Simpler, single system to manage
- **Cons**: No cryptographic workload identity, manual credential management
- **Rejected**: Doesn't enable zero-trust or cloud federation

### Service Mesh Identity Only

**Option**: Deploy Istio or Linkerd for all identity needs
- **Pros**: Integrated mTLS, traffic management included
- **Cons**: Heavy, all-or-nothing adoption, no user identity
- **Rejected**: Overkill for current needs, still need user IdP

### Cloud Provider Identity

**Option**: Use AWS Cognito / GCP Identity Platform
- **Pros**: Managed, integrated with cloud
- **Cons**: Vendor lock-in, doesn't work locally
- **Rejected**: Need consistent identity across local and cloud

### No Workload Identity (Deferred)

**Option**: Deploy Keycloak only, add SPIRE later
- **Status**: Valid phased approach
- **Note**: Phase 4-5 can be deferred if workload identity not immediately needed

## Related Decisions

- [ADR-0007: Cilium CNI and Network Policy Strategy](0007-cilium-cni-and-network-policy-strategy.md) - Cilium supports SPIFFE for mTLS
- [ADR-0013: Observability Stack](0013-observability-stack.md) - Grafana OIDC integration
- [ADR-0017: Secrets Management Strategy](0017-secrets-management-strategy.md) - Credentials for Keycloak, SPIRE integration

## References

- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [Keycloak Kubernetes Guide](https://www.keycloak.org/getting-started/getting-started-kube)
- [SPIFFE Specification](https://spiffe.io/docs/latest/spiffe-about/overview/)
- [SPIRE Documentation](https://spiffe.io/docs/latest/spire-about/)
- [SPIRE on Kubernetes](https://spiffe.io/docs/latest/deploying/spire_helm_charts_hardened/)
- [ArgoCD OIDC Configuration](https://argo-cd.readthedocs.io/en/stable/operator-manual/user-management/#existing-oidc-provider)
- [Grafana Generic OAuth](https://grafana.com/docs/grafana/latest/setup-grafana/configure-security/configure-authentication/generic-oauth/)
- [Cilium SPIFFE Integration](https://docs.cilium.io/en/stable/network/servicemesh/mutual-authentication/spiffe/)
- [SPIFFE Federation](https://spiffe.io/docs/latest/architecture/federation/)

## Notes

This decision was made during Phase 1 of platform development (January 2026). The two-tier approach positions the platform for enterprise-grade identity management while allowing phased adoption. Keycloak provides immediate SSO benefits, while SPIRE enables future zero-trust and cloud federation capabilities.

Revisit if:
- Resource constraints make running both systems problematic
- A service mesh is adopted that provides equivalent workload identity
- Simpler alternatives emerge that meet all requirements
