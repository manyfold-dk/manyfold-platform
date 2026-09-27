# ADR 0022: Remote Access Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
- [Decision](#decision)
- [Rationale](#rationale)
- [Alternatives Considered](#alternatives-considered)
- [Implementation](#implementation)
- [Consequences](#consequences)
- [References](#references)

## Status

Implemented (January 2026), amended April 2026

## Context

The cloud Kubernetes cluster hosts several internal tools and APIs that require secure access:

- **Web UIs**: ArgoCD, Grafana, Tekton Dashboard, Prometheus, Alertmanager, Hubble
- **APIs**: Kubernetes API (kubectl), Talos API (talosctl)
- **Webhooks**: GitHub webhooks for ArgoCD and Tekton Triggers

Currently, these services are either:
1. Exposed publicly via Ingress (security risk)
2. Locked down entirely (no remote access)
3. Protected only by mTLS (Talos API on port 50000)

We need a solution that:
- Allows secure remote access from any network location
- Does not require a fixed IP address
- Provides defense-in-depth beyond application-level authentication
- Is simple to set up and maintain for a single-user platform

The initial January 2026 rollout established Tailscale operator access successfully for simple internal web tools using the tailnet's MagicDNS hostnames (`<host>.<tailnet>`). Two later browser-facing admin surfaces exposed an important limitation in that model:

- **ArgoCD UI** expects its canonical `argocd.<domain>` host and `server.rootpath: /argocd` behavior, which causes redirect loops on the alternate tailnet hostname. See issue #73 (private tracker).
- **Keycloak admin** did not reliably bootstrap on a separate tailnet `hostname-admin` in Keycloak 26.3.3, even though simple curl checks succeeded.

These cases require a second remote-access pattern for host-sensitive browser UIs.

## Decision

We will use **Tailscale** with the **Kubernetes Operator** deployment model as the primary private-access layer, with two exposure patterns:

1. **Raw tailnet-hostname Tailscale ingress** for simple internal tools that do not depend on a canonical public hostname.
2. **Private custom domains via split DNS and a private Gateway** for browser admin surfaces that are sensitive to hostname, cookies, redirects, or root paths.

### Key choices:

| Aspect | Decision |
|--------|----------|
| VPN Solution | Tailscale (managed coordination, peer-to-peer data) |
| Deployment | Kubernetes Operator |
| Scope | Single user (personal tailnet) |
| Services exposed | Internal tools and APIs, using the most appropriate private-access pattern |
| Public Ingress | Remove for internal tools after Tailscale verified |
| Auth key storage | SOPS-encrypted secret in Git |

### Access patterns

| Service class | Pattern | Why |
|---------------|---------|-----|
| Simple internal web UIs | `*.<tailnet>` via Tailscale Ingress | Lowest operational overhead |
| Host-sensitive browser admin UIs | `*.<domain>` private custom domains via split DNS | Preserves canonical hostnames and browser behavior |
| Public apps and webhooks | Public `*.<domain>` via Cloudflare + public Gateway | Internet-reachable by design |

### Services exposed via Tailscale:

| Service | Private URL | Pattern | Status |
|---------|-------------|---------|--------|
| Grafana | `https://grafana-cluster.<tailnet>` | Raw tailnet-hostname ingress | ✅ Working |
| Prometheus | `https://prometheus-cluster.<tailnet>` | Raw tailnet-hostname ingress | ✅ Working |
| Alertmanager | `https://alertmanager-cluster.<tailnet>` | Raw tailnet-hostname ingress | ✅ Working |
| Tekton Dashboard | `https://tekton-cluster.<tailnet>` | Raw tailnet-hostname ingress | ✅ Working |
| Hubble UI | `https://hubble-cluster.<tailnet>` | Raw tailnet-hostname ingress | ✅ Working |
| ArgoCD UI | `https://argocd.<domain>` | Split-DNS private custom domain | ✅ Working |
| Keycloak admin | `https://auth-admin.<domain>` | Split-DNS private custom domain | ✅ Working |
| Kubernetes API | Via existing mTLS | N/A | Not needed via Tailscale |
| Talos API | Via existing mTLS | N/A | Not needed via Tailscale |

### Services that remain public:

| Service | Reason |
|---------|--------|
| Website (`<domain>`) | Public-facing application |
| Public OIDC issuer (`auth.<domain>`) | Browser login for public applications |
| GitHub webhook endpoints | Must be reachable by GitHub |

## Rationale

### Why Tailscale?

1. **Zero-config VPN**: Works immediately without complex network configuration
2. **Peer-to-peer**: Traffic flows directly between devices, not through a central server
3. **WireGuard-based**: Modern, fast, audited cryptography
4. **Works anywhere**: No fixed IP required, works behind NAT, on mobile networks
5. **Free tier sufficient**: 100 devices, 3 users - more than enough for personal use

### Why Kubernetes Operator over alternatives?

| Option | Pros | Cons | Verdict |
|--------|------|------|---------|
| **Kubernetes Operator** | Service-level exposure, DNS names, native K8s integration | More components | **Chosen** - best UX |
| Subnet Router | Simple, single pod | Access via IPs only, no DNS | Good fallback |
| Talos Extension | Node-level access | Requires Talos reconfiguration | Overkill for this use case |

### Why remove public Ingress?

- **Reduced attack surface**: Internal tools have no public exposure
- **Defense in depth**: Even if application auth is bypassed, Tailscale blocks access
- **Simpler firewall rules**: Only allow webhook endpoints publicly
- **No cert management**: Internal services don't need public TLS certificates

### Why private custom domains for some browser UIs?

- **Canonical hostname compatibility**: ArgoCD and Keycloak admin both behave better when the browser sees the hostname they are configured around.
- **Same-site cookies and redirects**: Browser auth flows are less fragile when private admin hosts stay under the platform's own domain.
- **Safer public narrowing**: Split DNS lets internal clients keep the canonical admin hostname while public routes can be restricted to the minimal callback or webhook paths that must remain reachable.
- **Incremental migration**: Existing public hostnames can be kept during rollout and narrowed only after private access is verified.

## Alternatives Considered

### Cloudflare Tunnel + Zero Trust

| Aspect | Tailscale | Cloudflare Tunnel |
|--------|-----------|-------------------|
| Protocol | Any (WireGuard) | HTTP/HTTPS primarily |
| kubectl support | Native | Requires workarounds |
| Talos API support | Native | Not supported |
| Traffic routing | Direct P2P | Through Cloudflare edge |
| Already using | No | Yes (DNS) |

**Verdict**: Cloudflare Tunnel works well for web UIs but doesn't support kubectl or Talos API natively. Could be added later for web UIs if SSO integration is desired.

### Headscale (self-hosted Tailscale)

| Aspect | Tailscale | Headscale |
|--------|-----------|-----------|
| Control plane | Tailscale cloud | Self-hosted |
| Setup | Minutes | Hours |
| Maintenance | None | Server upkeep |
| Mobile support | Full | Works but less polished |

**Verdict**: Tailscale's cloud dependency is minimal (coordination only). Self-hosting adds complexity without significant benefit for a personal platform.

### WireGuard (manual)

**Verdict**: Tailscale is WireGuard with automatic key management, NAT traversal, and coordination. Manual WireGuard would require significant configuration effort.

## Implementation

### Phase 1: Setup (Prerequisites) ✅

1. ✅ Created Tailscale account at tailscale.com
2. ✅ Installed Tailscale on local machine
3. ✅ Generated OAuth client credentials (expires April 25, 2026)
4. ✅ Stored credentials as SOPS-encrypted secret

### Phase 2: Operator Deployment ✅

1. ✅ Deployed Tailscale Kubernetes Operator v1.92.5 via ArgoCD
2. ✅ Configured operator with proper ACL tags (`tag:k8s-operator`, `tag:k8s`)
3. ✅ Enabled HTTPS certificates via Tailscale

### Phase 3: Service Exposure ✅

1. ✅ Created Tailscale Ingress resources for internal services
2. ✅ Verified access from local machine via Tailscale
3. ⚠️ ArgoCD excluded due to rootpath configuration conflict (issue #73, private tracker)

### Phase 4: Lockdown (Pending)

1. [ ] Remove public Ingress for internal tools (after verification period)
2. [ ] Update Hetzner firewall to restrict Talos API to Tailscale
3. ✅ Documentation updated

### Phase 5: Private Custom-Domain Admin UIs ✅

1. ✅ Added Envoy Gateway exposed through Tailscale LoadBalancer
2. ✅ Added split DNS for `<domain>` via private CoreDNS resolver (on a pinned ClusterIP)
3. ✅ Moved ArgoCD UI to private `https://argocd.<domain>`, public route narrowed to `/api/webhook`
4. ✅ Moved Keycloak admin to private `https://auth-admin.<domain>` via `hostname-admin`
5. ✅ Narrowed remaining public admin paths after private validation

### Actual Directory Structure

```
platform/
├── argocd/cloud/applications/
│   ├── tailscale.yaml                # ArgoCD Application (Helm + Git sources)
│   ├── private-admin-gateway.yaml    # Envoy Gateway stack (sync wave 1)
│   ├── private-admin-dns.yaml        # Private CoreDNS resolver (sync wave 1)
│   └── private-admin-routes.yaml     # Private admin HTTPRoutes (sync wave 2)
├── components/
│   ├── tailscale/
│   │   └── values-cloud.yaml         # Helm values for operator
│   └── envoy-gateway/
│       └── values-cloud.yaml         # Helm values for private Gateway controller
└── resources/cloud/
    ├── secrets/
    │   ├── <SOPS-encrypted OAuth credentials>
    │   └── ksops-generator.yaml      # KSOPS configuration
    ├── tailscale/
    │   ├── kustomization.yaml
    │   └── ingresses.yaml            # Tailscale Ingress resources (tailnet-hostname tools)
    ├── private-admin-gateway/        # Envoy Gateway + GatewayClass + listeners
    │   ├── kustomization.yaml
    │   ├── namespace.yaml
    │   ├── envoyproxy.yaml           # Tailscale LoadBalancer exposure
    │   ├── gatewayclass.yaml
    │   └── gateway.yaml              # Listeners: auth-admin, argocd
    ├── private-admin-dns/            # CoreDNS split-DNS resolver
    │   ├── kustomization.yaml
    │   ├── namespace.yaml
    │   ├── coredns-configmap.yaml    # Authoritative overrides + forwarding
    │   ├── deployment.yaml           # 2 replicas
    │   ├── service.yaml              # Pinned ClusterIP
    │   ├── servicemonitor.yaml
    │   ├── prometheusrule.yaml
    │   └── connector.yaml            # Tailscale Connector for resolver /32
    └── private-admin-routes/         # Private admin HTTPRoutes
        ├── kustomization.yaml
        ├── keycloak-admin-httproute.yaml
        └── argocd-httproute.yaml
```

### Operational Notes

- **Token Rotation**: OAuth credentials expire April 25, 2026. See [Tailscale Runbook](../runbooks/applications/tailscale.md).
- **Tailnet Suffix**: the tailnet's MagicDNS suffix (`<tailnet>`), an instance value
- **Tailscale Admin Console**: https://login.tailscale.com/admin/machines

## Consequences

### Positive

- Secure access to all internal services from any location
- No public exposure of internal tools
- Simple setup and maintenance
- Works on any network (home, mobile, travel)
- Defense in depth - multiple layers of security
- Leaves room for canonical private admin hostnames without abandoning the Tailscale model

### Negative

- Dependency on Tailscale's coordination service (minimal - data is P2P)
- Requires Tailscale client on all accessing devices
- Additional component to monitor and maintain
- Private custom domains add an internal DNS resolver and a second Gateway path for host-sensitive tools

### Neutral

- Webhook endpoints (ArgoCD, Tekton) remain public by necessity
- May add Cloudflare Tunnel later for SSO integration on web UIs

## References

- [Tailscale](https://tailscale.com/)
- [Tailscale Kubernetes Operator](https://tailscale.com/kb/1236/kubernetes-operator)
- [Use custom domains with Kubernetes Gateway API and Tailscale](https://tailscale.com/docs/solutions/kubernetes-operator-byod-gateway-api)
- [DNS in Tailscale](https://tailscale.com/kb/1054/dns/)
- [Tailscale on Hetzner](https://tailscale.com/kb/1234/hetzner)
- GitHub issues #52 and #73 (private tracker)
- [Argo CD Ingress Configuration](https://argo-cd.readthedocs.io/en/stable/operator-manual/ingress/)
- [Headscale](https://github.com/juanfont/headscale) (self-hosted alternative)
- [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) (alternative considered)
