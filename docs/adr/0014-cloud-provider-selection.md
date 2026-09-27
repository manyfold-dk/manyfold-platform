# ADR 0014: Cloud Provider Selection

## Table of Contents

- [Status](#status)
- [Context](#context)
  - [Requirements](#requirements)
  - [Providers Evaluated](#providers-evaluated)
  - [Cost Comparison (3-Node Talos Cluster)](#cost-comparison-3-node-talos-cluster)
- [Decision](#decision)
  - [Target Architecture](#target-architecture)
  - [Services Used](#services-used)
  - [DNS Strategy](#dns-strategy)
  - [Resource Allocation](#resource-allocation)
- [Rationale](#rationale)
  - [Why Hetzner Cloud?](#why-hetzner-cloud)
  - [Why Not Hyperscalers?](#why-not-hyperscalers)
  - [Why Not Managed Kubernetes?](#why-not-managed-kubernetes)
- [Consequences](#consequences)
  - [Positive](#positive)
  - [Negative](#negative)
  - [Risks and Mitigations](#risks-and-mitigations)
- [Implementation](#implementation)
  - [Prerequisites](#prerequisites)
  - [Phase 1: Infrastructure Bootstrap](#phase-1-infrastructure-bootstrap)
  - [Phase 2: Talos Cluster](#phase-2-talos-cluster)
  - [Phase 3: Platform Bootstrap](#phase-3-platform-bootstrap)
  - [Phase 4: DNS and TLS](#phase-4-dns-and-tls)
  - [Directory Structure](#directory-structure)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)

## Status

Accepted

## Context

The Manyfold Platform requires a cloud environment for production deployment (Phase 3). The platform has been developed locally using Kind with a focus on GitOps patterns, Kubernetes-native tooling, and infrastructure automation. The cloud provider selection affects cost, available services, networking options, and operational complexity.

### Requirements

1. **Cost-Effective**: Budget target of ~€50/month for a minimal HA Kubernetes cluster
2. **Kubernetes Support**: Ability to run self-managed Kubernetes (Talos Linux)
3. **API-Driven**: Full API for infrastructure automation with OpenTofu
4. **Block Storage**: Persistent volume support via CSI driver
5. **Load Balancing**: L4 load balancer for Kubernetes API and ingress
6. **DNS Integration**: API-driven DNS for External DNS integration (or use separate provider)
7. **European Data Centers**: GDPR compliance and low latency for European users
8. **Generous Bandwidth**: Avoid high egress costs that dominate cloud bills

### Providers Evaluated

| Provider | Pros | Cons |
|----------|------|------|
| **Hetzner Cloud** | 70-80% cheaper, excellent bandwidth (20TB included), European DCs, Talos ISO available | Smaller ecosystem, fewer managed services, limited regions |
| **AWS** | Most mature, EKS managed K8s, extensive services, global | Expensive (~10x Hetzner), complex pricing, high egress costs |
| **GCP** | Strong K8s heritage (GKE), good free tier, excellent DX | Expensive for sustained workloads, complex pricing |
| **Azure** | Enterprise integration, AKS, Microsoft ecosystem | Expensive, complex, overkill for personal platform |
| **DigitalOcean** | Simple, developer-friendly, DOKS managed K8s | More expensive than Hetzner, less mature K8s support |

### Cost Comparison (3-Node Talos Cluster)

| Configuration | Hetzner | AWS | Azure | GCP |
|--------------|---------|-----|-------|-----|
| 3× 2 vCPU, 4GB | ~€11/mo | ~€200/mo | ~€240/mo | ~€180/mo |
| 3× 4 vCPU, 8GB | ~€35/mo | ~€400/mo | ~€480/mo | ~€350/mo |
| Load Balancer | €5/mo | ~€20/mo | ~€20/mo | ~€20/mo |
| 1TB Egress | Included | ~€80 | ~€80 | ~€80 |
| **Total (small)** | **~€50/mo** | ~€300/mo | ~€340/mo | ~€280/mo |

## Decision

We will use **Hetzner Cloud** as the cloud provider for the Manyfold Platform.

### Target Architecture

```
Hetzner Cloud (~€50/month budget)
├── 3× <control-plane-type> (2 vCPU, 4GB RAM) - Talos control plane nodes
├── 2× <worker-type> (2 vCPU, 8GB RAM) - Talos worker nodes
├── 1× Load Balancer - Kubernetes API + Ingress
├── 50GB Block Storage - Persistent volumes
├── 1× Floating IP - Stable ingress endpoint
└── Private Network - Inter-node communication
```

**Note:** Control plane and worker nodes use different server types. Workers have 8GB RAM to handle application workloads (observability stack, applications), while control plane nodes with 4GB handle Kubernetes API, etcd, and controller components.

### Services Used

| Hetzner Service | Purpose |
|-----------------|---------|
| Cloud Servers (control plane type) | Control plane nodes (2 vCPU, 4GB RAM) |
| Cloud Servers (worker type) | Worker nodes (2 vCPU, 8GB RAM) |
| Load Balancers | Kubernetes API server, Ingress traffic |
| Volumes | Persistent storage via Hetzner CSI |
| Private Networks | Secure inter-node communication |
| Floating IPs | Stable public endpoint for ingress |

### DNS Strategy

Hetzner DNS is basic and lacks some features. We will use **Cloudflare** for DNS:
- Free tier with excellent API
- DDoS protection included
- External DNS controller has mature Cloudflare support
- Proxy capability for additional security

### Resource Allocation

For ~€50/month budget:

| Resource | Spec | Monthly Cost |
|----------|------|--------------|
| 3× control plane servers | Control plane: 2 vCPU, 4GB, 40GB SSD | ~€12 |
| 2× worker servers | Workers: 2 vCPU, 8GB, 80GB SSD | ~€14 |
| 1× Load Balancer | Smallest type | €5.39 |
| 50GB Volume | For PVCs | €2.60 |
| Floating IP | 1× IPv4 | €4.00 |
| Private Network | 1× network | Free |
| Server Backups | 5 servers | ~€5 |
| **Total** | | **~€43/mo** |

This leaves ~€7/month buffer for:
- Additional storage as needed
- Egress beyond 20TB included

## Rationale

### Why Hetzner Cloud?

**Cost Efficiency:**
- 70-80% cheaper than hyperscalers for equivalent compute
- 20TB included egress (vs ~€80/TB on AWS/Azure/GCP)
- No surprise bills from bandwidth overages
- Budget allows for proper 3-node HA setup instead of single-node compromise

**Talos Linux Support:**
- Talos ISO directly available in Hetzner Cloud (since v1.9.5)
- No need for custom image uploads or workarounds
- Active community using Talos on Hetzner
- Excellent guides and documentation available

**Kubernetes Ecosystem:**
- Hetzner Cloud Controller Manager (load balancers, node metadata)
- Hetzner CSI Driver (block storage as PVCs)
- External DNS support for Hetzner DNS
- Cluster API provider for advanced automation

**European Focus:**
- Data centers in Germany (Falkenstein, Nuremberg) and Finland (Helsinki)
- GDPR-compliant by default
- Low latency for European users
- Recently added US location (Ashburn) if needed

**Simplicity:**
- Straightforward pricing (no reserved instances complexity)
- Clean API and Terraform/OpenTofu provider
- Good documentation and responsive support
- Focus on core compute services without sprawling service catalog

### Why Not Hyperscalers?

**Cost:**
- 10x more expensive for equivalent compute
- Egress costs can dominate bills for bandwidth-heavy workloads
- Reserved instances add complexity and lock-in
- Budget would force single-node or spot instances (less learning value)

**Complexity:**
- Vast service catalogs with overlapping options
- Complex IAM and networking models
- More operational overhead for self-managed K8s
- Overkill for personal platform learning goals

**For This Project:**
- Goal is learning platform engineering patterns, not specific cloud services
- Same Kubernetes patterns work on any provider
- Can migrate to hyperscaler later if needed (Talos is provider-agnostic)
- Hetzner constraints encourage good architecture decisions

### Why Not Managed Kubernetes?

While Hetzner doesn't offer managed Kubernetes, this aligns with project goals:
- **Learning**: Operating Talos provides deeper Kubernetes understanding
- **Portability**: Talos clusters are identical across any infrastructure
- **Cost**: No managed K8s premium (~€70-100/mo on other providers)
- **Control**: Full control over cluster configuration, upgrades, and security

## Consequences

### Positive

- **Budget Friendly**: Full HA cluster within €50/month target
- **Learning Value**: Self-managed K8s provides deep operational experience
- **Generous Bandwidth**: 20TB included eliminates egress cost concerns
- **Simple Pricing**: Predictable monthly costs, no surprise bills
- **European Compliance**: GDPR-friendly by default
- **Talos Native**: First-class Talos support with official ISO

### Negative

- **Limited Regions**: Only EU (Germany, Finland) and US (Ashburn)
- **No Managed Services**: Must self-host databases, caches, etc.
- **Smaller Ecosystem**: Fewer integrations than hyperscalers
- **Less Enterprise Features**: No advanced compliance certifications
- **Operational Burden**: Self-managed everything requires more effort

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Hetzner service issues | Regular backups, documented recovery procedures |
| Limited regions | Cloudflare CDN for global edge caching |
| No managed databases | PostgreSQL on Kubernetes with proper backups (Phase 4) |
| Provider lock-in | Talos is provider-agnostic, easy to migrate |
| Scaling limits | Can add servers easily, consider dedicated servers for growth |

## Implementation

### Prerequisites

1. Hetzner Cloud account with API token
2. Cloudflare account with API token and zone
3. Domain configured in Cloudflare (`<domain>`)
4. OpenTofu installed locally

### Phase 1: Infrastructure Bootstrap

1. Create Hetzner Cloud project via console
2. Generate API token with read/write permissions
3. Create OpenTofu configuration for:
   - Private network
   - Firewall rules
   - 3× cloud servers with Talos image
   - Load balancer
   - Floating IP

### Phase 2: Talos Cluster

1. Generate Talos machine configuration
2. Apply configuration to nodes via `talosctl`
3. Bootstrap Kubernetes cluster
4. Install Hetzner Cloud Controller Manager
5. Install Hetzner CSI Driver
6. Verify cluster health

### Phase 3: Platform Bootstrap

1. Install Cilium CNI
2. Install ArgoCD
3. Configure Git credentials
4. Deploy App-of-Apps
5. Verify all platform components sync

### Phase 4: DNS and TLS

1. Install External DNS with Cloudflare provider
2. Install cert-manager
3. Configure Let's Encrypt ClusterIssuer
4. Verify automatic DNS records and certificates

### Directory Structure

```
infrastructure/
├── hetzner/
│   ├── bootstrap/
│   │   ├── main.tf           # OpenTofu entrypoint
│   │   ├── variables.tf      # Input variables
│   │   ├── outputs.tf        # Output values
│   │   ├── providers.tf      # Provider configuration
│   │   ├── network.tf        # Private network, firewall
│   │   ├── servers.tf        # Talos nodes
│   │   ├── loadbalancer.tf   # Load balancer config
│   │   └── dns.tf            # Cloudflare DNS records
│   └── talos/
│       ├── controlplane.yaml # Talos control plane config
│       ├── worker.yaml       # Talos worker config (if separate)
│       └── talosconfig       # Talos client config
```

## Alternatives Considered

### AWS with EKS

- **Pros**: Managed control plane, extensive services, most job-relevant
- **Cons**: ~€300+/month minimum, complex pricing, high egress
- **Rejected**: Budget constraint, learning goals better served by self-managed

### DigitalOcean with DOKS

- **Pros**: Simple, managed K8s included, developer-friendly
- **Cons**: More expensive than Hetzner, less mature CSI/CCM
- **Rejected**: Hetzner offers better value for self-managed approach

### Hetzner Robot (Dedicated Servers)

- **Pros**: Better price/performance for sustained workloads
- **Cons**: Slower provisioning, more complex networking, minimum commits
- **Deferred**: Can migrate from Cloud to Robot later for cost optimization

### Hybrid (Local + Cloud)

- **Pros**: Keep heavy workloads local, use cloud for public-facing only
- **Cons**: Complexity of multi-cluster, networking challenges
- **Rejected**: Single cloud cluster simpler for learning

## Related Decisions

- [ADR-0012: Local vs Cloud Environment Parity](0012-local-vs-cloud-environment-parity.md) - Same tools locally and cloud
- [ADR-0015: Kubernetes Distribution](0015-kubernetes-distribution.md) - Talos Linux selection
- [ADR-0016: Infrastructure as Code Tool](0016-infrastructure-as-code-tool.md) - OpenTofu for provisioning

## References

- [Hetzner Cloud Pricing](https://www.hetzner.com/cloud)
- [Hetzner Cloud API Documentation](https://docs.hetzner.cloud/)
- [Talos Linux on Hetzner](https://www.siderolabs.com/blog/iso-talos-linux-1-9-5-now-available-on-hetzner/)
- [Hetzner Cloud Controller Manager](https://github.com/hetznercloud/hcloud-cloud-controller-manager)
- [Hetzner CSI Driver](https://github.com/hetznercloud/csi-driver)
- [Cloudflare External DNS](https://github.com/kubernetes-sigs/external-dns/blob/master/docs/tutorials/cloudflare.md)
- [Bare-metal Kubernetes with Talos on Hetzner](https://datavirke.dk/posts/bare-metal-kubernetes-part-1-talos-on-hetzner/)
