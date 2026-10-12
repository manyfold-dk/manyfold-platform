# ADR 0017: Secrets Management Strategy

## Table of Contents

- [Status](#status)
- [Context](#context)
  - [Requirements](#requirements)
  - [Current State](#current-state)
  - [Tools Evaluated](#tools-evaluated)
  - [Kubernetes Integration Options](#kubernetes-integration-options)
- [Decision](#decision)
  - [Cloud Environment (Phase 3)](#cloud-environment-phase-3)
  - [Future Enhancement (Optional)](#future-enhancement-optional)
  - [Architecture](#architecture)
  - [Key Management](#key-management)
- [Rationale](#rationale)
  - [Why SOPS + Age as Primary?](#why-sops--age-as-primary)
  - [Why External Secrets Operator?](#why-external-secrets-operator)
  - [Why Not OpenBao/Vault Initially?](#why-not-openbaovault-initially)
  - [Migration Path to OpenBao](#migration-path-to-openbao)
  - [Why Not Doppler?](#why-not-doppler)
  - [Why Not Sealed Secrets?](#why-not-sealed-secrets)
- [Consequences](#consequences)
  - [Positive](#positive)
  - [Negative](#negative)
  - [Risks and Mitigations](#risks-and-mitigations)
  - [Operational Amendment (2026-07-12): OpenBao Root-Token Retirement](#operational-amendment-2026-07-12-openbao-root-token-retirement)
- [Amendment 2026-10-12: OpenBao custody tier for service adapters](#amendment-2026-10-12-openbao-custody-tier-for-service-adapters)
- [Implementation](#implementation)
  - [Prerequisites](#prerequisites)
  - [Phase 1: Key Generation and Distribution](#phase-1-key-generation-and-distribution)
  - [Phase 2: Secret Store Configuration](#phase-2-secret-store-configuration)
  - [Phase 3: Create Encrypted Secrets](#phase-3-create-encrypted-secrets)
  - [Phase 4: Consume Secrets via ESO](#phase-4-consume-secrets-via-eso)
  - [Phase 5: Distribute Age Private Key](#phase-5-distribute-age-private-key)
  - [Directory Structure](#directory-structure)
  - [Rotation Procedure](#rotation-procedure)
- [Alternatives Considered](#alternatives-considered)
- [Related Decisions](#related-decisions)
- [References](#references)

## Status

Accepted — OpenBao deployed to cloud cluster (2026-02-21)

**Amended 2026-10-12:** OpenBao is the custody tier for service adapters, with exact
per-consumer paths and Keycloak-issued JWT authentication; SOPS stays for rendered platform
secrets; the unseal material moves to the operator recovery bundle's custody. See the
[amendment of 2026-10-12](#amendment-2026-10-12-openbao-custody-tier-for-service-adapters).

## Context

The Manyfold Platform requires a secrets management strategy for Phase 3 cloud deployment. Secrets include API tokens, database credentials, TLS certificates, and other sensitive configuration. The strategy must handle secrets storage, distribution to applications, rotation, and audit capabilities.

### Requirements

1. **Secure Storage**: Encrypt secrets at rest with strong cryptography
2. **Kubernetes Integration**: Seamlessly provide secrets to applications as K8s Secrets
3. **GitOps Compatible**: Fit into declarative, Git-based workflow
4. **Rotation Support**: Enable credential rotation without application restarts
5. **Audit Trail**: Track who accessed what secrets and when
6. **Cost Effective**: Fit within budget constraints (~€50/month total)
7. **Open Source Preference**: Align with project philosophy of open-source tooling
8. **Operational Simplicity**: Minimize operational overhead for personal platform

### Current State

- **Local (Kind)**: Kubernetes Secrets in plain text, acceptable for local development
- **Phase 1 Gap**: Local secrets management (Issue #5) deferred
- **Cloud Need**: Production secrets require proper management

### Tools Evaluated

| Tool | Type | Pros | Cons |
|------|------|------|------|
| **OpenBao** | Self-hosted | Open source (Vault fork), full-featured, dynamic secrets | Operational overhead, resource usage |
| **HashiCorp Vault** | Self-hosted/SaaS | Most mature, extensive docs, dynamic secrets | BSL license, resource heavy |
| **Doppler** | SaaS | Zero ops, good free tier, simple | Vendor lock-in, external dependency |
| **SOPS + Age** | GitOps-native | No infrastructure, encrypted in Git | No dynamic secrets, manual rotation |
| **Sealed Secrets** | K8s-native | Simple, GitOps-native | Single cluster, no external integration |
| **Infisical** | Self-hosted/SaaS | Modern UI, open source | Newer project, less mature |

### Kubernetes Integration Options

| Approach | Description | Best For |
|----------|-------------|----------|
| **External Secrets Operator (ESO)** | Syncs external secrets to K8s Secrets | Most backends |
| **CSI Secrets Store Driver** | Mounts secrets as volumes | Vault, cloud KMS |
| **Sealed Secrets Controller** | Encrypts secrets for Git storage | GitOps-only workflows |

## Decision

We will use a **tiered approach** based on environment:

### Cloud Environment (Phase 3)

**Primary**: **SOPS + Age** for encrypted secrets in Git
**Integration**: **External Secrets Operator** with Kubernetes provider

This approach:
- Stores encrypted secrets in Git (full GitOps)
- No external infrastructure required
- ESO syncs decrypted secrets to K8s Secrets
- Age keys managed via Talos machine config or bootstrap secret

### Future Enhancement (Optional)

**OpenBao** can be added later if dynamic secrets or more advanced features become necessary.

> Superseded by the
> [amendment of 2026-10-12](#amendment-2026-10-12-openbao-custody-tier-for-service-adapters):
> OpenBao is no longer optional. It is the custody tier for service adapters, and SOPS keeps
> the rendered platform secrets.

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Git Repository                          │
│  platform/resources/local/secrets/*.yaml (SOPS-encrypted)                   │
└────────────────────────┬────────────────────────────────────┘
                         │ ArgoCD syncs
                         ▼
┌─────────────────────────────────────────────────────────────┐
│               External Secrets Operator                      │
│  - Reads SOPS-encrypted secrets from Git/K8s                │
│  - Decrypts using Age private key                           │
│  - Creates native Kubernetes Secrets                        │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│                  Kubernetes Secrets                          │
│  - Applications consume via env vars or volume mounts       │
│  - Standard K8s secret access patterns                      │
└─────────────────────────────────────────────────────────────┘
```

### Key Management

| Key Type | Storage Location | Purpose |
|----------|------------------|---------|
| Age Public Key | Git (`.sops.yaml`) | Encrypt secrets locally |
| Age Private Key | Talos machine config / K8s Secret | Decrypt in cluster |
| Backup | Secure offline storage | Disaster recovery |

## Rationale

### Why SOPS + Age as Primary?

**GitOps Native:**
- Encrypted secrets stored directly in Git
- Full audit trail via Git history
- No external infrastructure to manage
- Secrets deployed alongside applications

**Zero Infrastructure Cost:**
- No additional servers or services
- No SaaS subscription fees
- Fits within tight budget constraint
- Reduces operational complexity

**Age over GPG:**
- Modern, simple, and secure
- Single binary, easy to use
- No key server complexity
- Designed specifically for file encryption

**Proven Pattern:**
- SOPS widely used in GitOps workflows
- Well-documented with ArgoCD and Flux
- Mozilla-developed, battle-tested
- Active maintenance

### Why External Secrets Operator?

**Universal Integration:**
- Abstracts secret backends from applications
- Applications just consume K8s Secrets
- Can switch backends without app changes
- Supports SOPS via Kubernetes provider

**ESO + SOPS Pattern:**
```yaml
# ExternalSecret references SOPS-encrypted ConfigMap
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: app-secrets
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: kubernetes-store
    kind: SecretStore
  target:
    name: app-secrets
  data:
    - secretKey: database-password
      remoteRef:
        key: sops-secrets
        property: database-password
```

### Why Not OpenBao/Vault Initially?

**Resource Overhead:**
- Vault/OpenBao requires dedicated resources
- At minimum: 256-512MB RAM, etcd/storage backend
- Competes with workloads for limited resources
- Budget better spent on application capacity

**Operational Complexity:**
- Requires HA setup for production reliability
- Unsealing process after restarts
- Certificate management for TLS
- Backup and disaster recovery

**Overkill for Current Scale:**
- Dynamic secrets not needed for initial workloads
- PostgreSQL can use static credentials initially
- SOPS handles current secret complexity well
- Can migrate to OpenBao when needed

### Migration Path to OpenBao

When dynamic secrets or advanced features become necessary:

1. Deploy OpenBao alongside existing setup
2. Configure ESO to use OpenBao as additional provider
3. Migrate secrets gradually (ESO supports multiple providers)
4. Keep SOPS for bootstrap/infrastructure secrets
5. Use OpenBao for application secrets with rotation

### Why Not Doppler?

**External Dependency:**
- SaaS introduces availability risk
- Secrets must leave your infrastructure
- Dependent on Doppler's security posture
- Free tier limitations may become restrictive

**Cost Trajectory:**
- Free tier: 5 team members, limited environments
- Paid tiers add up for growth
- SOPS is free forever

**For This Project:**
- Self-hosted preference for learning
- No need for team collaboration features
- Budget prioritizes compute over SaaS

### Why Not Sealed Secrets?

**Limited Flexibility:**
- Single cluster scope
- No integration with external backends
- Migration to other solutions harder
- ESO provides same GitOps benefits plus more

## Consequences

### Positive

- **Zero Cost**: No additional infrastructure or SaaS fees
- **Full GitOps**: Secrets version-controlled with applications
- **Simple Operations**: No secrets infrastructure to maintain
- **Audit Trail**: Git history shows all secret changes
- **Portable**: Works with any Kubernetes cluster
- **Migration Ready**: ESO makes backend migration easy

### Negative

- **No Dynamic Secrets**: Static credentials only (acceptable for initial scale)
- **Manual Rotation**: Must manually update and re-encrypt secrets
- **Key Management**: Age private key is critical, must be protected
- **Local Encryption**: Developers need Age key for local secret creation

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Age private key compromise | Rotate key, re-encrypt all secrets |
| Forgotten key | Multiple backups in secure locations |
| Developer key access | Document secure key distribution |
| Scaling beyond SOPS | Migration path to OpenBao documented |

### Operational Amendment (2026-07-12): OpenBao Root-Token Retirement

Recorded per the OpenBao root-token and credential-rotation plan of 2026-06-12 (private). This
amends operational practice in place -- it does not change the Decision above or require a
new ADR. This ADR (manyfold-platform ADR-0017) nowhere required a stored root token, so
retiring it is a tightening, not a reversal.

> **Transition status:** EFFECTIVE 2026-07-19. Phase B authored 2026-07-12; Phase C
> executed 2026-07-19 (C1-C8 done + verified: roles provisioned, root token revoked,
> unseal keys rotated, no root-token at rest, auto-unseal proven). C9 escrow local half
> done; the umbrella break-glass push is the operator's remaining step.

- **No root token stored at rest.** The OpenBao root token is not stored anywhere -- not
  in Git, not in a cluster Secret, not in break-glass escrow. As of 2026-07-19 no root
  token exists at all (the last one was revoked).
- **Privileged operations use a scoped role.** Day-2 admin operations (policy/mount/role
  management, `manyclaw/` and tenant KV access, Transit provisioning) authenticate via
  the `openbao-admin` Kubernetes-auth role, provisioned by
  `infrastructure/clusters/cloud/scripts/setup-openbao-k8s-auth.sh create-ops-roles`, not
  the root token. Two further narrowly scoped roles (`raft-backup`, `codex-seeder`)
  replace the root token for the nightly Raft-snapshot backup and the Codex OAuth token
  seeder respectively.
- **Unseal-key rotation is authenticated + quorum-gated.** OpenBao 2.4+ disabled the
  unauthenticated `sys/rekey` and `sys/generate-root` endpoints. Unseal-key rotation uses
  the authenticated `sys/rotate/root` (a 3-of-5 quorum is still required), authorised by a
  transient rotation-only token minted via `openbao-admin` and destroyed after -- no role
  carries standing rotate privilege.
- **Break-glass access is the `openbao-admin` role** (Kubernetes-auth, cluster-access
  gated), which covers all day-2 admin. **DECIDED 2026-07-19: the admin-only ceiling is
  the accepted end state.** True root is deliberately NOT routinely obtainable on OpenBao
  2.5 (`generate-root` disabled; `token create -policy=root` refused). This is intentional:
  it is what breaks the chain where the SOPS age key transitively granted full vault
  control -- someone holding the age key plus cluster access now obtains `openbao-admin`
  (broad but bounded), never root. `openbao-admin` covers every routine operation and can
  mint transient tokens for the authenticated rotate; the config-based audit device
  re-applies on pod restart, so no root is needed to recover it. On the rare occasion true
  root is genuinely required, it is recovered under change control -- a git-committed
  `disable_unauthed_rekey_endpoints = false` flip (quorum-gated, auditable; revert after)
  or a Raft-snapshot restore -- see the
  [OpenBao runbook](../runbooks/applications/openbao.md#break-glass-access). Permanently
  re-enabling `generate-root` was rejected because it would rebuild the age-key-to-root
  chain this retirement removed.

This amendment does not change the auto-unseal sidecar mechanism or the SOPS-encrypted
unseal-key secret described above, which remain exactly as designed; moving them out of
the single age-key trust tier is tracked by a separate, follow-on trust-tier plan that
this retirement unblocks.

> The [amendment of 2026-10-12](#amendment-2026-10-12-openbao-custody-tier-for-service-adapters)
> decides that move: the unseal material leaves the age-key tier for the recovery bundle's
> custody.

## Amendment 2026-10-12: OpenBao custody tier for service adapters

Recorded per the operator's access-plane design (private), accepted 2026-10-12. This amends
the Decision in place. It extends the operational amendment of 2026-07-12 (effective
2026-07-19) and keeps its boundary. Where this section and the text above disagree, this
section holds.

**OpenBao is the custody tier for service adapters.** A service adapter executes bounded
operations with credentials held outside agent-readable processes and storage. It returns
filtered results, never credential values. It does not export credentials into an agent's
environment or files. Executor leases use the separate custody interface, which is
inaccessible through the agent-facing gateway. The adapters' credentials live in OpenBao.
OpenBao moves from "Future Enhancement (Optional)" to a required tier for this class of
secret.

**Each consumer gets exact paths.** An OpenBao policy names every path its consumer reads,
one by one. A policy grants no wildcard and no shared prefix across consumers. A consumer that
needs a new secret gets a new path in its own policy, never access to another consumer's path.

**Keycloak-issued JWTs replace AppRole for adapters.** Adapters replacing AppRole
authenticate through OpenBao's JWT auth method using short-lived Keycloak-issued tokens
([ADR-0018 amendment of 2026-10-12](0018-identity-management-strategy.md#amendment-2026-10-12-keycloak-as-the-access-plane-authorization-server)).
Validation binds issuer, audience and subject to the intended consumer. AppRole leaves the
design, because its secret ID is a stored credential that needs custody of its own. Human
access uses OIDC against Keycloak. In-cluster workloads retain projected ServiceAccount
authentication to exact OpenBao roles, so the Kubernetes-auth roles of the 2026-07-12
amendment stay. Human administrative access migrates to OIDC only after independent recovery
has been proven.

**Each operational secret has one authoritative custody tier.** That tier is SOPS for
rendered platform secrets or OpenBao for adapter credentials. A rendered platform secret is
one that GitOps renders into the cluster as a Kubernetes Secret, including the secrets the
platform needs before OpenBao is up; these stay SOPS-encrypted in Git, as the Decision above
describes. Required recovery copies are separately protected and recorded.

**Unseal material belongs to the operator recovery bundle.** Unseal material belongs to the
client-side encrypted operator recovery bundle, with a versioned online copy and offline
copies. Decryption identities are held separately from ciphertext. Recovery must work without
the cluster, Keycloak, OpenBao, Tailscale or GitHub. The bundle is never on the day-to-day
path. This is the trust-tier move that the 2026-07-12 amendment left open. Prove independent
recovery and the post-restart unseal procedure before removing the existing unseal Secret.
Any automated replacement must preserve these custody boundaries. The mechanics of unsealing
after a restart are left to the implementation plan.

**The no-root-token boundary stands.** No root token exists or is stored, and true root is
recovered only under change control, as the 2026-07-12 amendment decided. Nothing in this
section reintroduces a standing root path.

**A consumer register is a required artefact.** The register lists, for every credential:

| Field | Content |
|---|---|
| Credential | Its name and the system it opens, never its value |
| Consumer | The consuming actor or device |
| Root or derived | Whether it is a service root credential or derived from one |
| Every copy | Each place the credential exists: the OpenBao path, a SOPS file, the issuing system, the recovery bundle |
| Restore or reissue | Whether recovery restores it from a copy or reissues it at the issuer |
| Rotation procedure | A link to the runbook section that rotates it |
| Last rotation | The date of the last completed rotation |
| Predecessor revoked at | The date the previous credential was revoked |
| Issuer-side revocation | The procedure that revokes it at the issuer |

The register produces the service-root inventory and its count. The register holds no value.
It names consumers, so it belongs to the instance and not to this repository. A credential
that is not in the register is a finding, not an exception.

### Consequences of the amendment

- The platform SOPS age key no longer directly decrypts adapter credentials or unseal
  material. Preventing indirect access also depends on the identity and execution boundaries.
- A compromised consumer exposes its own paths only, because no policy reaches beyond them.
- No adapter holds a static OpenBao login secret.
- An agent never sees an adapter's credential values; it sees filtered results.
- OpenBao becomes a run-time dependency of every service adapter. Its availability, backup
  and restore now gate those services.
- Every credential has a known set of copies, so a rotation can prove it reached all of them.

## Implementation

> Note (2026-09-27): the local kind cluster is retired, see [ADR-0054's amendment of 2026-09-27](0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead); the `platform/resources/local/secrets/` paths in this section and in the architecture diagram above are gone, and the SOPS files live under `platform/resources/cloud/secrets/`.

### Prerequisites

1. Install SOPS CLI (`brew install sops`)
2. Install Age CLI (`brew install age`)
3. Install External Secrets Operator in cluster
4. Generate Age keypair

### Phase 1: Key Generation and Distribution

```bash
# Generate Age keypair
age-keygen -o keys.txt

# keys.txt contains:
# # created: 2024-01-01T00:00:00Z
# # public key: age1...
# AGE-SECRET-KEY-1...

# Store public key in .sops.yaml (commit to Git)
# Store private key securely (never in Git!)
```

**.sops.yaml:**
```yaml
creation_rules:
  - path_regex: platform/resources/local/secrets/.*\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Phase 2: Secret Store Configuration

**SecretStore for SOPS-encrypted Kubernetes secrets:**
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata:
  name: kubernetes-sops
spec:
  provider:
    kubernetes:
      remoteNamespace: secrets
      server:
        caProvider:
          type: ConfigMap
          name: kube-root-ca.crt
          key: ca.crt
          namespace: kube-system
      auth:
        serviceAccount:
          name: external-secrets
          namespace: external-secrets
```

### Phase 3: Create Encrypted Secrets

```bash
# Create secret file
cat > platform/resources/local/secrets/database.yaml << EOF
apiVersion: v1
kind: Secret
metadata:
  name: database-credentials
  namespace: secrets
type: Opaque
stringData:
  username: app
  password: supersecretpassword
EOF

# Encrypt with SOPS
sops --encrypt --in-place platform/resources/local/secrets/database.yaml

# Commit encrypted secret to Git
git add platform/resources/local/secrets/database.yaml
git commit -m "feat(secrets): add database credentials"
```

### Phase 4: Consume Secrets via ESO

**ExternalSecret:**
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: database-credentials
  namespace: app
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: kubernetes-sops
    kind: ClusterSecretStore
  target:
    name: database-credentials
    creationPolicy: Owner
  dataFrom:
    - extract:
        key: database-credentials
```

### Phase 5: Distribute Age Private Key

Options for getting the Age private key into the cluster:

**Option A: Talos Machine Config (Recommended)**
```yaml
# In Talos machine config (encrypted section)
machine:
  files:
    - path: /var/run/secrets/age/keys.txt
      permissions: 0400
      content: |
        AGE-SECRET-KEY-1...
```

**Option B: Bootstrap Secret**
```bash
# Create during cluster bootstrap (before ArgoCD)
kubectl create secret generic sops-age \
  --namespace=external-secrets \
  --from-file=keys.txt=./age-private-key.txt
```

### Directory Structure

```
platform/
├── secrets/
│   ├── .sops.yaml              # SOPS configuration
│   ├── database.yaml           # Encrypted database credentials
│   ├── api-keys.yaml           # Encrypted API keys
│   └── tls-certs.yaml          # Encrypted TLS certificates
└── external-secrets/
    ├── kustomization.yaml
    ├── namespace.yaml
    ├── cluster-secret-store.yaml
    └── external-secrets/       # ESO Helm chart values
```

### Rotation Procedure

```bash
# 1. Update secret value
sops platform/resources/local/secrets/database.yaml
# (edit password in decrypted view)

# 2. Commit change
git add platform/resources/local/secrets/database.yaml
git commit -m "chore(secrets): rotate database password"

# 3. Push to trigger ArgoCD sync
git push

# 4. ESO refreshes K8s Secret automatically
# 5. Restart pods if needed (or use reloader)
```

## Alternatives Considered

### OpenBao (Self-Hosted Vault Fork)

- **Pros**: Dynamic secrets, automatic rotation, full-featured, open source
- **Cons**: Resource overhead, operational complexity
- **Status**: Deferred for future enhancement when scale justifies

### HashiCorp Vault

- **Pros**: Most mature, extensive documentation
- **Cons**: BSL license, same resource overhead as OpenBao
- **Rejected**: OpenBao preferred if self-hosted secrets vault needed

### Doppler

- **Pros**: Zero operations, good DX, free tier
- **Cons**: SaaS dependency, cost at scale, external trust
- **Rejected**: Self-hosted preference, budget constraints

### Infisical

- **Pros**: Modern UI, open source option
- **Cons**: Newer project, less proven
- **Rejected**: SOPS more mature for GitOps pattern

### Native Kubernetes Secrets Only

- **Pros**: Simplest approach
- **Cons**: No encryption at rest (depends on etcd encryption), no Git storage
- **Rejected**: Need encryption and version control

## Related Decisions

- [ADR-0014: Cloud Provider Selection](0014-cloud-provider-selection.md) - Infrastructure context
- [ADR-0015: Kubernetes Distribution](0015-kubernetes-distribution.md) - Talos secret handling
- [ADR-0016: Infrastructure as Code Tool](0016-infrastructure-as-code-tool.md) - OpenTofu state encryption

## References

- [SOPS Documentation](https://github.com/getsops/sops)
- [Age Encryption](https://age-encryption.org/)
- [External Secrets Operator](https://external-secrets.io/)
- [OpenBao Project](https://openbao.org/)
- [OpenBao vs Vault Comparison](https://digitalis.io/post/choosing-a-secrets-storage-hashicorp-vault-vs-openbao)
- [ESO with SOPS Guide](https://external-secrets.io/latest/guides/using-sops/)
- [Secrets Management in 2026](https://www.javacodegeeks.com/2025/12/secrets-management-in-2026-vault-aws-secrets-manager-and-beyond-a-developers-guide.html)
