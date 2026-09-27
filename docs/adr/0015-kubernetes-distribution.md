# ADR 0015: Kubernetes Distribution

## Table of Contents

- [Status](#status)
- [Context](#context)
  - [Requirements](#requirements)
  - [Distributions Evaluated](#distributions-evaluated)
  - [Security Comparison](#security-comparison)
- [Decision](#decision)
  - [Architecture](#architecture)
  - [Node Configuration](#node-configuration)
  - [Management Approach](#management-approach)
- [Rationale](#rationale)
  - [Why Talos Linux?](#why-talos-linux)
  - [Why Not k3s?](#why-not-k3s)
  - [Why Not Managed Kubernetes?](#why-not-managed-kubernetes)
  - [Talos-Specific Benefits for This Project](#talos-specific-benefits-for-this-project)
- [Consequences](#consequences)
  - [Positive](#positive)
  - [Negative](#negative)
  - [Risks and Mitigations](#risks-and-mitigations)
  - [Operational Differences](#operational-differences)
- [Implementation](#implementation)
  - [Prerequisites](#prerequisites)
  - [Installation Method: boot-to-talos](#installation-method-boot-to-talos)
  - [Phase 1: Generate Configuration](#phase-1-generate-configuration)
  - [Phase 2: Customize Configuration](#phase-2-customize-configuration)
  - [Phase 3: Install Talos via boot-to-talos](#phase-3-install-talos-via-boot-to-talos)
  - [Phase 4: Apply Configuration](#phase-4-apply-configuration)
  - [Phase 5: Verify and Install CNI](#phase-5-verify-and-install-cni)
  - [Directory Structure](#directory-structure)
  - [Upgrade Strategy](#upgrade-strategy)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)

## Status

Accepted

## Context

The Manyfold Platform requires a Kubernetes distribution for the cloud environment (Phase 3). The choice of distribution affects security posture, operational complexity, upgrade paths, and alignment with GitOps practices. The platform already uses Kind for local development, which provides standard Kubernetes APIs.

### Requirements

1. **Security**: Minimal attack surface, hardened by default
2. **Immutability**: Prevent configuration drift across nodes
3. **API-Driven Management**: No SSH required, GitOps-compatible
4. **Minimal Footprint**: Efficient resource usage for budget-constrained deployment
5. **Self-Managed**: Full control over cluster lifecycle (not managed K8s service)
6. **Provider Agnostic**: Portable across cloud providers and bare metal
7. **Active Community**: Good documentation, regular updates, community support
8. **Production Ready**: Suitable for production workloads with HA capabilities

### Distributions Evaluated

| Distribution | Pros | Cons |
|--------------|------|------|
| **Talos Linux** | Immutable, API-only, minimal (12 binaries), purpose-built for K8s | No SSH (learning curve), newer project |
| **k3s** | Lightweight, simple, single binary, good for edge | Mutable OS underneath, requires base OS management |
| **kubeadm** | Standard tool, well-documented, flexible | Requires full OS management, configuration drift risk |
| **Flatcar + kubeadm** | Immutable base, familiar tooling | More complex than Talos, still needs SSH for some ops |
| **RKE2** | Rancher-backed, FIPS compliant, good docs | Heavier than k3s, requires base OS |
| **MicroK8s** | Snap-based, easy install | Ubuntu-specific, less production-focused |

### Security Comparison

| Aspect | Talos | k3s/kubeadm | Flatcar |
|--------|-------|-------------|---------|
| SSH Access | None (API only) | Yes | Yes |
| Shell Access | None | Yes | Limited |
| Package Manager | None | Yes | None |
| Kernel Modules | Signed, immutable | Standard | Immutable |
| Root Filesystem | Read-only | Read-write | Read-only |
| Attack Surface | Minimal (12 binaries) | Standard Linux | Reduced |

## Decision

We will use **Talos Linux** as the Kubernetes distribution for the Manyfold Platform cloud environment.

### Architecture

```
Talos Linux Cluster
├── Control Plane (3 nodes for HA)
│   ├── etcd (integrated)
│   ├── kube-apiserver
│   ├── kube-controller-manager
│   └── kube-scheduler
├── Workers (combined with control plane for budget)
│   └── kubelet + container runtime
└── Management
    ├── talosctl (CLI)
    └── Talos API (gRPC with mTLS)
```

### Node Configuration

For the ~€50/month budget on Hetzner:

| Node Type | Count | Resources | Role |
|-----------|-------|-----------|------|
| Combined | 3 | 2 vCPU, 4GB RAM | Control plane + workloads |

All three nodes run both control plane components and workloads. This provides:
- HA for the control plane (etcd quorum)
- Workload scheduling across all nodes
- Cost efficiency within budget constraints

### Management Approach

| Aspect | Approach |
|--------|----------|
| **Initial Setup** | `talosctl` CLI with machine configs |
| **Configuration** | Declarative YAML, version controlled |
| **Upgrades** | Atomic OS updates via `talosctl upgrade` |
| **Access** | mTLS-authenticated API only |
| **Troubleshooting** | `talosctl logs`, `talosctl dmesg`, `talosctl dashboard` |

## Rationale

### Why Talos Linux?

**Immutability Eliminates Drift:**
- Read-only root filesystem prevents ad-hoc changes
- Configuration is declarative and version-controlled
- Every node is identical, no "snowflake" servers
- Rebuilding a node produces identical result

**Minimal Attack Surface:**
- Only 12 binaries in the entire OS
- No SSH daemon, no shell, no package manager
- No way for attackers to install malware
- Signed kernel modules prevent tampering
- mTLS authentication for all API access

**Purpose-Built for Kubernetes:**
- machined (Go-based init) replaces systemd
- Optimized for running kubelet and nothing else
- No legacy Linux compatibility baggage
- Fast boot times (~30 seconds to ready)

**API-Driven Operations (GitOps Native):**
- All management via gRPC API
- Configuration as YAML files in Git
- No imperative SSH commands to document
- Audit trail of all changes via Git history

**Atomic Updates:**
- OS updates applied atomically (A/B partition scheme)
- Rollback possible if update fails
- No partial update states
- Kubernetes version and OS updated together

**Provider Agnostic:**
- Same Talos configuration works on any infrastructure
- Easy migration between providers (Hetzner today, elsewhere tomorrow)
- Consistent experience across local (VM) and cloud deployments
- Supports bare metal, cloud VMs, and virtualization platforms

### Why Not k3s?

k3s is excellent for edge and lightweight deployments, but:

| Aspect | k3s | Talos |
|--------|-----|-------|
| Base OS | Requires separate OS (Ubuntu, etc.) | IS the OS |
| SSH | Available (attack vector) | None |
| Configuration Drift | Possible | Impossible |
| Security Posture | Depends on base OS hardening | Hardened by design |
| Operational Model | Traditional Linux admin | API-only |

For a learning-focused platform, Talos provides deeper understanding of secure K8s operations.

### Why Not Managed Kubernetes?

Hetzner doesn't offer managed Kubernetes, but even if available:

- **Learning**: Self-managed provides deeper operational understanding
- **Control**: Full control over versions, configurations, security
- **Cost**: No managed K8s premium (~€70-100/month elsewhere)
- **Portability**: Skills transfer to any environment

### Talos-Specific Benefits for This Project

**Aligns with GitOps Philosophy:**
- Machine configs are YAML files stored in Git
- Changes require Git commits (audit trail)
- No ad-hoc SSH sessions to undo

**Excellent Hetzner Support:**
- Official Talos ISO available directly in Hetzner Cloud
- Active community with Hetzner deployment guides
- Hetzner CCM and CSI work seamlessly with Talos

**Matches Project Goals:**
- Production-grade patterns, not shortcuts
- Security-first approach
- Modern operational practices

## Consequences

### Positive

- **Security**: Dramatically reduced attack surface
- **Consistency**: No configuration drift between nodes
- **GitOps Native**: All configuration version-controlled
- **Fast Recovery**: Rebuild node from config in minutes
- **Learning Value**: Modern K8s operational practices
- **Portability**: Same approach works on any infrastructure

### Negative

- **No SSH**: Can't "just SSH in" to debug (intentional, but learning curve)
- **Newer Project**: Less institutional knowledge than traditional distros
- **Different Tooling**: `talosctl` instead of familiar `ssh`/`kubectl exec`
- **All-or-Nothing**: Can't selectively adopt, it's the entire OS

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Debugging difficulty without SSH | `talosctl logs`, `talosctl dmesg`, proper observability stack |
| Talos-specific issues | Active Discord community, good documentation |
| Learning curve | Start with local Talos cluster before cloud |
| Breaking changes | Pin Talos version, test upgrades in staging |

### Operational Differences

Traditional Linux vs Talos:

| Task | Traditional | Talos |
|------|-------------|-------|
| View logs | `ssh node && journalctl` | `talosctl logs -n node` |
| Check disk | `ssh node && df -h` | `talosctl dashboard -n node` |
| Install package | `ssh && apt install` | Not possible (use containers) |
| Debug networking | `ssh && tcpdump` | `talosctl pcap -n node` |
| Update OS | `ssh && apt upgrade` | `talosctl upgrade -n node` |
| Reset node | Manual reinstall | `talosctl reset -n node` |

## Implementation

### Prerequisites

1. `talosctl` CLI installed locally
2. `boot-to-talos` tool for installation (from [Cozystack](https://github.com/cozystack/boot-to-talos))
3. Understanding of Talos machine config structure
4. PKI for cluster authentication (generated by `talosctl`)

### Installation Method: boot-to-talos

> **Update (2026-01-31):** The bootstrap method has since been replaced with a snapshot + user_data approach using `hcloud-upload-image`. See the cloud bootstrap README (private) and the migration plan of 2026-01-30 (private) for the current approach.

The original plan used [boot-to-talos](https://github.com/cozystack/boot-to-talos) instead of ISO-based installation because:

- **No custom images needed**: Works with standard Hetzner Debian images
- **Fully automatable**: Can be scripted via SSH or cloud-init
- **No Packer/snapshots**: Simpler infrastructure, no image management
- **Userspace installation**: Uses kexec, no physical reboot required
- **Provider agnostic**: Same method works on any cloud or bare metal

The process:
1. OpenTofu creates servers with standard Debian image
2. SSH into each server and run boot-to-talos
3. boot-to-talos downloads Talos image and installs to disk
4. Server reboots into Talos in maintenance mode
5. Apply Talos machine config via `talosctl apply-config`

### Phase 1: Generate Configuration

```bash
# Generate secrets (keep secure!)
talosctl gen secrets -o secrets.yaml

# Generate machine configs
talosctl gen config manyfold-cluster https://k8s.<domain>:6443 \
  --with-secrets secrets.yaml \
  --output-dir infrastructure/clusters/cloud/talos/

# Files created:
# - controlplane.yaml (for control plane nodes)
# - worker.yaml (for worker nodes, if separate)
# - talosconfig (client configuration)
```

### Phase 2: Customize Configuration

Key customizations needed for Hetzner:

```yaml
# controlplane.yaml patches
machine:
  install:
    disk: /dev/sda
    image: factory.talos.dev/installer/<schematic-id>:v1.12.1
  network:
    hostname: cp-1
  certSANs:
    - k8s.<domain>
    - <load-balancer-ip>

cluster:
  controlPlane:
    endpoint: https://k8s.<domain>:6443
  network:
    cni:
      name: none  # We'll install Cilium
```

### Phase 3: Install Talos via boot-to-talos

```bash
# SSH into each server (created by OpenTofu with Debian)
ssh root@<node-ip>

# Download and run boot-to-talos
curl -sSL https://github.com/cozystack/boot-to-talos/raw/refs/heads/main/hack/install.sh | sh -s
boot-to-talos -yes -disk /dev/sda -image factory.talos.dev/installer/<schematic-id>:v1.12.1

# Server reboots into Talos maintenance mode
# Repeat for all nodes
```

### Phase 4: Apply Configuration

```bash
# Apply config to each node (now running Talos in maintenance mode)
talosctl apply-config --insecure -n <node-ip> -f controlplane.yaml

# Bootstrap the cluster (only once, on first control plane)
talosctl bootstrap -n <first-node-ip>

# Get kubeconfig
talosctl kubeconfig -n <node-ip>
```

### Phase 5: Verify and Install CNI

```bash
# Check cluster health
talosctl health -n <node-ip>

# Verify Kubernetes
kubectl get nodes

# Install Cilium (nodes will become Ready)
cilium install --version 1.16.x
```

### Directory Structure

```
infrastructure/
└── cloud/
    ├── bootstrap/             # OpenTofu for Hetzner resources
    │   ├── versions.tf
    │   ├── variables.tf
    │   ├── network.tf
    │   ├── servers.tf
    │   ├── loadbalancer.tf
    │   └── outputs.tf
    └── talos/
        ├── secrets.yaml           # Encrypted with SOPS (never commit plain!)
        ├── controlplane.yaml      # Control plane machine config
        ├── worker.yaml            # Worker config (if separate)
        ├── talosconfig            # Client config (also sensitive)
        └── patches/
            ├── hetzner-ccm.yaml   # Cloud controller manager config
            └── cilium.yaml        # CNI configuration
```

### Upgrade Strategy

```bash
# Check current version
talosctl version -n <node-ip>

# Upgrade Talos (rolling, one node at a time)
talosctl upgrade -n <node-ip> \
  --image factory.talos.dev/installer/<schematic-id>:v1.13.0

# Upgrade Kubernetes (after Talos upgrade)
talosctl upgrade-k8s -n <node-ip> --to 1.36.0
```

## Alternatives Considered

### k3s on Ubuntu

- **Pros**: Lightweight K8s, familiar Linux base, easy setup
- **Cons**: Mutable OS, SSH access, configuration drift possible
- **Rejected**: Talos provides better security and GitOps alignment

### Flatcar Linux + kubeadm

- **Pros**: Immutable base, standard kubeadm tooling
- **Cons**: More complex than Talos, SSH still available, more components
- **Rejected**: Talos is more purpose-built and simpler

### RKE2

- **Pros**: Rancher ecosystem, FIPS compliant, good docs
- **Cons**: Requires base OS, heavier than alternatives
- **Rejected**: Talos provides better security posture for this use case

### Managed Kubernetes (if available)

- **Pros**: Less operational burden, provider-managed upgrades
- **Cons**: Less learning value, less control, higher cost
- **Rejected**: Self-managed aligns with learning goals

## Related Decisions

- [ADR-0014: Cloud Provider Selection](0014-cloud-provider-selection.md) - Hetzner Cloud chosen
- [ADR-0007: Cilium CNI](0007-cilium-cni-and-network-policy-strategy.md) - CNI selection
- [ADR-0012: Local vs Cloud Parity](0012-local-vs-cloud-environment-parity.md) - Consistent tooling

## References

- [Talos Linux Documentation](https://www.talos.dev/latest/)
- [Talos GitHub Repository](https://github.com/siderolabs/talos)
- [Talos on Hetzner Guide](https://www.talos.dev/latest/talos-guides/install/cloud-platforms/hetzner/)
- [boot-to-talos](https://github.com/cozystack/boot-to-talos) - Convert any OS to Talos Linux
- [boot-to-talos Documentation](https://cozystack.io/docs/install/talos/boot-to-talos/)
- [Talos Linux: Bringing Immutability and Security to Kubernetes](https://www.infoq.com/news/2025/10/talos-linux-kubernetes/)
- [Sidero Labs (Talos creators)](https://www.siderolabs.com/)
- [Talos Community Discord](https://slack.dev.talos-systems.io/)
