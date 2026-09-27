---
number: "0014"
series: Platform
title: Cloud provider selection
status: Accepted
date: 2026-01-20
summary: Why the platform runs on Hetzner. Cost and learning value decided it in January 2026; EU data residency became the reason to stay. The record says so.
changes: Server types and counts, network and load balancer details, the bootstrap procedure and the repository layout are removed. A dated note is added at the top.
---

> **Note, September 2026.** This record is from January 2026, when the platform
> was a personal project with a budget of about EUR 50 a month. Cost and learning
> value decided the provider. European data centres were one requirement among
> eight. Since then the platform has taken on its first tenant, and EU data
> residency has become a hard requirement
> ([ADR 0035](/decisions/0035-eu-data-residency-for-storage)). Sovereignty is
> the reason to stay on Hetzner. It was not the reason to start. The reasoning
> below is left as it was written.

## Status

Accepted

## Context

The Manyfold Platform requires a cloud environment for production deployment
(Phase 3). The platform has been developed locally using Kind with a focus on
GitOps patterns, Kubernetes-native tooling, and infrastructure automation. The
cloud provider selection affects cost, available services, networking options,
and operational complexity.

### Requirements

1. **Cost-effective**: budget target of about EUR 50/month for a minimal HA
   Kubernetes cluster
2. **Kubernetes support**: ability to run self-managed Kubernetes (Talos Linux)
3. **API-driven**: full API for infrastructure automation with OpenTofu
4. **Block storage**: persistent volume support via CSI driver
5. **Load balancing**: L4 load balancer for Kubernetes API and ingress
6. **DNS integration**: API-driven DNS for External DNS integration (or use a
   separate provider)
7. **European data centers**: GDPR compliance and low latency for European users
8. **Generous bandwidth**: avoid high egress costs that dominate cloud bills

### Providers evaluated

| Provider | Pros | Cons |
|----------|------|------|
| **Hetzner Cloud** | 70-80% cheaper, excellent bandwidth (20TB included), European DCs, Talos ISO available | Smaller ecosystem, fewer managed services, limited regions |
| **AWS** | Most mature, EKS managed K8s, extensive services, global | Expensive (about 10x Hetzner), complex pricing, high egress costs |
| **GCP** | Strong K8s heritage (GKE), good free tier, excellent DX | Expensive for sustained workloads, complex pricing |
| **Azure** | Enterprise integration, AKS, Microsoft ecosystem | Expensive, complex, overkill for personal platform |
| **DigitalOcean** | Simple, developer-friendly, DOKS managed K8s | More expensive than Hetzner, less mature K8s support |

### Cost comparison (3-node Talos cluster)

| Configuration | Hetzner | AWS | Azure | GCP |
|--------------|---------|-----|-------|-----|
| 3x 2 vCPU, 4GB | ~EUR 11/mo | ~EUR 200/mo | ~EUR 240/mo | ~EUR 180/mo |
| 3x 4 vCPU, 8GB | ~EUR 35/mo | ~EUR 400/mo | ~EUR 480/mo | ~EUR 350/mo |
| Load balancer | EUR 5/mo | ~EUR 20/mo | ~EUR 20/mo | ~EUR 20/mo |
| 1TB egress | Included | ~EUR 80 | ~EUR 80 | ~EUR 80 |
| **Total (small)** | **~EUR 50/mo** | ~EUR 300/mo | ~EUR 340/mo | ~EUR 280/mo |

## Decision

We will use **Hetzner Cloud** as the cloud provider for the Manyfold Platform.

### Target architecture

A highly available Talos control plane, separate worker nodes with more memory
for the observability stack and applications, a load balancer for the Kubernetes
API and ingress, block storage for persistent volumes, and a private network
between the nodes.

### Services used

| Hetzner service | Purpose |
|-----------------|---------|
| Cloud Servers | Control plane and worker nodes |
| Load Balancers | Kubernetes API server, ingress traffic |
| Volumes | Persistent storage via Hetzner CSI |
| Private Networks | Inter-node communication |

### DNS strategy

Hetzner DNS is basic and lacks some features. We will use **Cloudflare** for
DNS:

- Free tier with excellent API
- DDoS protection included
- External DNS controller has mature Cloudflare support
- Proxy capability for additional security

### Resource allocation

The initial sizing came to about EUR 43 a month against the EUR 50 budget. The
remaining EUR 7 is a buffer for additional storage and for egress beyond the
20TB included.

## Rationale

### Why Hetzner Cloud?

**Cost efficiency:**

- 70-80% cheaper than hyperscalers for equivalent compute
- 20TB included egress (vs about EUR 80/TB on AWS, Azure and GCP)
- No surprise bills from bandwidth overages
- Budget allows for a proper 3-node HA setup instead of a single-node compromise

**Talos Linux support:**

- Talos ISO directly available in Hetzner Cloud
- No need for custom image uploads or workarounds
- Active community using Talos on Hetzner
- Excellent guides and documentation available

**Kubernetes ecosystem:**

- Hetzner Cloud Controller Manager (load balancers, node metadata)
- Hetzner CSI Driver (block storage as PVCs)
- External DNS support for Hetzner DNS
- Cluster API provider for advanced automation

**European focus:**

- Data centers in Germany (Falkenstein, Nuremberg) and Finland (Helsinki)
- GDPR-compliant by default
- Low latency for European users
- Recently added US location (Ashburn) if needed

**Simplicity:**

- Straightforward pricing (no reserved instances complexity)
- Clean API and Terraform/OpenTofu provider
- Good documentation and responsive support
- Focus on core compute services without a sprawling service catalog

### Why not hyperscalers?

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

**For this project:**

- Goal is learning platform engineering patterns, not specific cloud services
- Same Kubernetes patterns work on any provider
- Can migrate to a hyperscaler later if needed (Talos is provider-agnostic)
- Hetzner constraints encourage good architecture decisions

### Why not managed Kubernetes?

While Hetzner doesn't offer managed Kubernetes, this aligns with project goals:

- **Learning**: operating Talos provides deeper Kubernetes understanding
- **Portability**: Talos clusters are identical across any infrastructure
- **Cost**: no managed K8s premium (about EUR 70-100/mo on other providers)
- **Control**: full control over cluster configuration, upgrades, and security

## Consequences

### Positive

- **Budget friendly**: full HA cluster within the EUR 50/month target
- **Learning value**: self-managed K8s provides deep operational experience
- **Generous bandwidth**: 20TB included eliminates egress cost concerns
- **Simple pricing**: predictable monthly costs, no surprise bills
- **European compliance**: GDPR-friendly by default
- **Talos native**: first-class Talos support with official ISO

### Negative

- **Limited regions**: only EU (Germany, Finland) and US (Ashburn)
- **No managed services**: must self-host databases, caches, etc.
- **Smaller ecosystem**: fewer integrations than hyperscalers
- **Less enterprise features**: no advanced compliance certifications
- **Operational burden**: self-managed everything requires more effort

### Risks and mitigations

| Risk | Mitigation |
|------|------------|
| Hetzner service issues | Regular backups, documented recovery procedures |
| Limited regions | Cloudflare CDN for global edge caching |
| No managed databases | PostgreSQL on Kubernetes with proper backups (Phase 4) |
| Provider lock-in | Talos is provider-agnostic, easy to migrate |
| Scaling limits | Can add servers easily, consider dedicated servers for growth |

## Alternatives considered

### AWS with EKS

- **Pros**: managed control plane, extensive services, most job-relevant
- **Cons**: EUR 300+/month minimum, complex pricing, high egress
- **Rejected**: budget constraint, learning goals better served by self-managed

### DigitalOcean with DOKS

- **Pros**: simple, managed K8s included, developer-friendly
- **Cons**: more expensive than Hetzner, less mature CSI/CCM
- **Rejected**: Hetzner offers better value for the self-managed approach

### Hetzner Robot (dedicated servers)

- **Pros**: better price/performance for sustained workloads
- **Cons**: slower provisioning, more complex networking, minimum commits
- **Deferred**: can migrate from Cloud to Robot later for cost optimization

### Hybrid (local + cloud)

- **Pros**: keep heavy workloads local, use cloud for public-facing only
- **Cons**: complexity of multi-cluster, networking challenges
- **Rejected**: single cloud cluster simpler for learning

## Related decisions

None of these are published yet.

- ADR 0012: Local vs cloud environment parity -- same tools locally and in cloud
- ADR 0015: Kubernetes distribution -- Talos Linux selection
- ADR 0016: Infrastructure as code tool -- OpenTofu for provisioning

## References

- [Hetzner Cloud Pricing](https://www.hetzner.com/cloud)
- [Hetzner Cloud API Documentation](https://docs.hetzner.cloud/)
- [Hetzner Cloud Controller Manager](https://github.com/hetznercloud/hcloud-cloud-controller-manager)
- [Hetzner CSI Driver](https://github.com/hetznercloud/csi-driver)
- [Cloudflare External DNS](https://github.com/kubernetes-sigs/external-dns/blob/master/docs/tutorials/cloudflare.md)
- [Bare-metal Kubernetes with Talos on Hetzner](https://datavirke.dk/posts/bare-metal-kubernetes-part-1-talos-on-hetzner/)
