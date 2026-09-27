# ADR 0016: Infrastructure as Code Tool

## Table of Contents

- [Status](#status)
- [Context](#context)
  - [Requirements](#requirements)
  - [Tools Evaluated](#tools-evaluated)
  - [Licensing Comparison](#licensing-comparison)
- [Decision](#decision)
  - [Architecture](#architecture)
  - [State Management](#state-management)
  - [Module Structure](#module-structure)
- [Rationale](#rationale)
  - [Why OpenTofu?](#why-opentofu)
  - [Why Not Terraform?](#why-not-terraform)
  - [Why Not Pulumi?](#why-not-pulumi)
  - [Why Not Crossplane for Bootstrap?](#why-not-crossplane-for-bootstrap)
  - [OpenTofu-Specific Features We'll Use](#opentofu-specific-features-well-use)
- [Consequences](#consequences)
  - [Positive](#positive)
  - [Negative](#negative)
  - [Risks and Mitigations](#risks-and-mitigations)
- [Implementation](#implementation)
  - [Prerequisites](#prerequisites)
  - [Initial Configuration](#initial-configuration)
  - [Workflow](#workflow)
  - [GitOps Integration](#gitops-integration)
  - [Security Considerations](#security-considerations)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)

## Status

Accepted

## Context

The Manyfold Platform requires infrastructure as code (IaC) tooling for Phase 3 cloud deployment. The IaC tool will provision and manage Hetzner Cloud resources (servers, networks, load balancers, storage) before Talos and Kubernetes take over application deployment. The choice affects licensing implications, community support, and long-term maintenance.

### Requirements

1. **Hetzner Provider**: Mature provider for Hetzner Cloud resources
2. **Declarative**: Infrastructure defined as code, version controlled
3. **State Management**: Track resource state for updates and drift detection
4. **Modular**: Support for reusable modules and composition
5. **Open Source**: Prefer truly open-source tooling without licensing restrictions
6. **Active Development**: Regular updates, security patches, community support
7. **GitOps Compatible**: State can be managed in a GitOps workflow

### Tools Evaluated

| Tool | Pros | Cons |
|------|------|------|
| **OpenTofu** | MPL 2.0 license, Linux Foundation governance, state encryption, community-driven | Newer, slightly less documentation |
| **Terraform** | Most mature, extensive docs, large ecosystem | BSL license restricts commercial use, vendor-controlled |
| **Pulumi** | Real programming languages, strong typing | Different paradigm, cloud-hosted state by default |
| **Crossplane** | Kubernetes-native, GitOps-native | Better for day-2 ops, complex for bootstrapping |
| **Ansible** | Agentless, good for config management | Procedural, not ideal for cloud infrastructure |

### Licensing Comparison

| Aspect | OpenTofu | Terraform |
|--------|----------|-----------|
| License | MPL 2.0 (OSI approved) | BSL 1.1 (not OSI approved) |
| Governance | Linux Foundation | HashiCorp |
| Commercial Use | Unrestricted | Restricted for competing offerings |
| Community Influence | High (TSC from multiple orgs) | Low (vendor-controlled roadmap) |
| Long-term Guarantee | Community-driven continuity | Subject to HashiCorp decisions |

## Decision

We will use **OpenTofu** as the infrastructure as code tool for provisioning Hetzner Cloud resources.

### Architecture

```
Infrastructure Provisioning Flow
┌─────────────────────────────────────────────────────┐
│                    Git Repository                    │
│  infrastructure/hetzner/bootstrap/*.tf              │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│                     OpenTofu                         │
│  - Reads HCL configuration                          │
│  - Plans changes against state                      │
│  - Applies to Hetzner Cloud API                     │
└────────────────────────┬────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────┐
│                  Hetzner Cloud                       │
│  - Servers (Talos nodes)                            │
│  - Private network                                  │
│  - Load balancer                                    │
│  - Firewall rules                                   │
│  - Volumes                                          │
└─────────────────────────────────────────────────────┘
```

### State Management

| Aspect | Approach |
|--------|----------|
| State Backend | S3-compatible (Hetzner Object Storage or Cloudflare R2) |
| State Encryption | OpenTofu native encryption with Age keys |
| State Locking | Backend-supported locking |
| Sensitive Values | Marked sensitive, encrypted at rest |

### Module Structure

```
infrastructure/
└── hetzner/
    └── bootstrap/
        ├── main.tf              # Root module, orchestrates all resources
        ├── variables.tf         # Input variables
        ├── outputs.tf           # Output values (IPs, etc.)
        ├── providers.tf         # Provider configuration
        ├── versions.tf          # Version constraints
        ├── network.tf           # Private network, firewall
        ├── servers.tf           # Talos node servers
        ├── loadbalancer.tf      # Load balancer for K8s API
        ├── storage.tf           # Block volumes
        └── dns.tf               # Cloudflare DNS records
```

## Rationale

### Why OpenTofu?

**True Open Source:**
- MPL 2.0 license is OSI-approved open source
- No restrictions on commercial use
- Linux Foundation governance ensures community-driven development
- No risk of future license changes affecting usage

**Feature Parity Plus More:**
- Compatible with Terraform 1.6.x HCL and providers
- Hetzner provider works identically
- State encryption (requested by Terraform community for 5+ years)
- Early variable evaluation in module sources

**Community Governance:**
- Technical Steering Committee from multiple organizations
- Roadmap influenced by community, not single vendor
- Transparent decision-making process
- Active development and regular releases

**Ecosystem Compatibility:**
- Uses same provider ecosystem as Terraform
- Hetzner Cloud provider fully supported
- Existing Terraform modules largely compatible
- Documentation and examples transferable

### Why Not Terraform?

**Licensing Concerns:**
- BSL 1.1 is not OSI-approved open source
- Restrictions on competitive commercial offerings
- Vendor lock-in to HashiCorp decisions
- License could change again in the future

**For This Project:**
- No benefit from HashiCorp enterprise features
- OpenTofu provides equivalent functionality
- Prefer truly open-source tooling
- Aligns with project philosophy of vendor-neutral choices

### Why Not Pulumi?

- Different paradigm (imperative programming vs declarative HCL)
- State hosted in Pulumi Cloud by default
- Steeper learning curve for HCL users
- Less Hetzner-specific documentation
- Good alternative, but OpenTofu better fits existing patterns

### Why Not Crossplane for Bootstrap?

Per ADR-0005, Crossplane is designated for day-2 cloud resource management, not initial bootstrap:

| Phase | Tool | Reason |
|-------|------|--------|
| Bootstrap | OpenTofu | Create cluster infrastructure from nothing |
| Day-2 | Crossplane | Manage cloud resources from within K8s |

Crossplane requires a running Kubernetes cluster, creating a chicken-and-egg problem for initial provisioning.

### OpenTofu-Specific Features We'll Use

**State Encryption:**
```hcl
terraform {
  encryption {
    key_provider "pbkdf2" "main" {
      passphrase = var.state_encryption_passphrase
    }
    method "aes_gcm" "default" {
      keys = key_provider.pbkdf2.main
    }
    state {
      method = method.aes_gcm.default
    }
  }
}
```

**Early Variable Evaluation:**
```hcl
# Can use variables in module sources (OpenTofu 1.8+)
module "talos" {
  source  = "github.com/example/talos?ref=${var.talos_module_version}"
}
```

## Consequences

### Positive

- **Open Source**: No licensing restrictions or vendor lock-in
- **State Encryption**: Built-in encryption for sensitive state data
- **Community-Driven**: Roadmap influenced by users, not single vendor
- **Terraform Compatible**: Existing knowledge and modules transfer
- **Active Development**: Regular releases with new features
- **Future-Proof**: Linux Foundation governance ensures continuity

### Negative

- **Newer Project**: Less historical documentation than Terraform
- **Some Tools Lag**: A few third-party tools may support Terraform first
- **Brand Recognition**: Less recognized than Terraform in job market
- **Module Compatibility**: Some newer Terraform features may not work

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| OpenTofu feature lag | Core features aligned, HCL compatible |
| Less documentation | Terraform docs 95% applicable |
| Provider issues | Same providers used, same support channels |
| Project abandonment | Linux Foundation backing, multiple sponsors |

## Implementation

### Prerequisites

1. Install OpenTofu CLI (`brew install opentofu`)
2. Hetzner Cloud API token
3. Cloudflare API token
4. S3-compatible backend for state (or local for initial testing)

### Initial Configuration

**providers.tf:**
```hcl
terraform {
  required_version = ">= 1.8.0"

  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.49"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 4.0"
    }
    talos = {
      source  = "siderolabs/talos"
      version = "~> 0.7"
    }
  }

  # State backend configuration
  backend "s3" {
    bucket                      = "<state-bucket>"
    key                         = "hetzner/terraform.tfstate"
    region                      = "auto"
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_s3_checksum            = true
  }
}

provider "hcloud" {
  token = var.hcloud_token
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
```

**variables.tf:**
```hcl
variable "hcloud_token" {
  description = "Hetzner Cloud API token"
  type        = string
  sensitive   = true
}

variable "cloudflare_api_token" {
  description = "Cloudflare API token"
  type        = string
  sensitive   = true
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID for DNS"
  type        = string
}

variable "cluster_name" {
  description = "Name of the Kubernetes cluster"
  type        = string
  default     = "manyfold"
}

variable "controlplane_count" {
  description = "Number of control plane nodes"
  type        = number
  default     = 3
}

variable "worker_count" {
  description = "Number of worker nodes"
  type        = number
  default     = 2
}

variable "controlplane_node_type" {
  description = "Hetzner server type for control plane"
  type        = string
  default     = "<server-type>"  # 2 vCPU, 4GB RAM, 40GB disk
}

variable "worker_node_type" {
  description = "Hetzner server type for workers"
  type        = string
  default     = "<server-type>"  # 2 vCPU, 8GB RAM, 80GB disk
}

variable "location" {
  description = "Hetzner datacenter location"
  type        = string
  default     = "<location>"  # an EU datacenter location
}
```

### Workflow

```bash
# Initialize (download providers, configure backend)
tofu init

# Format code
tofu fmt

# Validate configuration
tofu validate

# Plan changes (review before applying)
tofu plan -out=tfplan

# Apply changes
tofu apply tfplan

# Show current state
tofu show

# Destroy infrastructure (when needed)
tofu destroy
```

### GitOps Integration

OpenTofu state managed outside GitOps (bootstrap phase), but:
- HCL configuration stored in Git
- Changes go through PR review
- CI can run `tofu plan` on PRs
- Manual `tofu apply` for infrastructure changes
- Outputs (IPs, etc.) fed into Talos configuration

### Security Considerations

| Aspect | Approach |
|--------|----------|
| API Tokens | Environment variables or secrets manager |
| State File | Encrypted at rest (OpenTofu encryption + S3 encryption) |
| Plan Output | Review before apply, CI validation |
| Sensitive Outputs | Marked sensitive, not logged |

## Alternatives Considered

### Terraform

- **Pros**: Most mature, extensive documentation
- **Cons**: BSL license, vendor lock-in risk
- **Rejected**: OpenTofu provides same functionality with true open-source license

### Pulumi

- **Pros**: Real programming languages (Go, Python, TypeScript)
- **Cons**: Different paradigm, cloud-hosted state default
- **Rejected**: HCL is well-suited for infrastructure, prefer declarative approach

### Ansible + Hetzner Collection

- **Pros**: Familiar to many, good for config management
- **Cons**: Procedural, not ideal for infrastructure lifecycle
- **Rejected**: OpenTofu better suited for cloud resource management

### Manual + Scripts

- **Pros**: No additional tools
- **Cons**: No state management, error-prone, not reproducible
- **Rejected**: IaC is essential for production infrastructure

## Related Decisions

- [ADR-0005: ArgoCD/Crossplane Split](0005-argocd-crossplane-responsibility-split.md) - Crossplane for day-2
- [ADR-0014: Cloud Provider Selection](0014-cloud-provider-selection.md) - Hetzner Cloud
- [ADR-0015: Kubernetes Distribution](0015-kubernetes-distribution.md) - Talos Linux

## References

- [OpenTofu Documentation](https://opentofu.org/docs/)
- [OpenTofu vs Terraform Comparison](https://spacelift.io/blog/opentofu-vs-terraform)
- [Hetzner Cloud Provider](https://registry.terraform.io/providers/hetznercloud/hcloud/latest/docs)
- [Cloudflare Provider](https://registry.terraform.io/providers/cloudflare/cloudflare/latest/docs)
- [Talos Provider](https://registry.terraform.io/providers/siderolabs/talos/latest/docs)
- [OpenTofu State Encryption](https://opentofu.org/docs/language/state/encryption/)
- [Linux Foundation OpenTofu Announcement](https://www.linuxfoundation.org/press/announcing-opentofu)
