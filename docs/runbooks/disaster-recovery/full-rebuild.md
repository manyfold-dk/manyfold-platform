# Full Cluster Rebuild

Procedure for rebuilding the entire cluster from scratch.

## Table of Contents

- [Placeholders](#placeholders)
- [When to Use](#when-to-use)
- [Prerequisites](#prerequisites)
- [Time Estimate](#time-estimate)
- [Procedure](#procedure)
  - [Step 1: Destroy Existing Infrastructure (If Applicable)](#step-1-destroy-existing-infrastructure-if-applicable)
  - [Step 2: Provision New Infrastructure](#step-2-provision-new-infrastructure)
  - [Step 3: Get Kubeconfig](#step-3-get-kubeconfig)
  - [Step 4: Install Cilium CNI](#step-4-install-cilium-cni)
  - [Step 5: Install ArgoCD](#step-5-install-argocd)
  - [Step 6: Configure SOPS Age Key](#step-6-configure-sops-age-key)
  - [Step 7: Apply App-of-Apps](#step-7-apply-app-of-apps)
  - [Step 8: Verify Deployment](#step-8-verify-deployment)
- [Post-Rebuild Tasks](#post-rebuild-tasks)
- [Data Recovery](#data-recovery)
  - [What Git Restores (after age key)](#what-git-restores-after-age-key)
  - [What Requires Backup Restore](#what-requires-backup-restore)
  - [What May Need Manual Recreation](#what-may-need-manual-recreation)
- [Troubleshooting](#troubleshooting)
- [Infrastructure State](#infrastructure-state)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|-------------|-------|
| `<cloud-kubeconfig>` | The absolute path of the kubeconfig file for the cloud cluster. Use the path in the `KUBECONFIG_FILE` variable of the setup scripts in `infrastructure/clusters/cloud/scripts`: the scripts read only that path and ignore `KUBECONFIG` |
| `<website-host>` | The public hostname of the website (the homepage) |
| `<argocd-host>` | The public hostname of the ArgoCD UI |
| `<auth-host>` | The public hostname of Keycloak |
| `<auth-admin-host>` | The private hostname of the Keycloak admin console |
| `<realm>` | The name of the Keycloak realm |
| `<admin-user>` | The Keycloak admin user that the Helm values define |
| `<protected-app>` | An application behind its own OAuth2 proxy, where every page requires a login |
| `<protected-app-host>` | The public hostname of `<protected-app>` |
| `<protected-app-oauth2-secret>` | The secret that the OAuth2 proxy values of `<protected-app>` reference |
| `<grafana-tailnet-host>` | The Tailscale hostname of Grafana |

## When to Use

- Catastrophic failure affecting all nodes
- Complete etcd data loss without backup
- Major corruption requiring clean start
- Intentional rebuild for testing/updates

## Prerequisites

- Access to OpenTofu state (in Cloudflare R2)
- Access to Git repository with manifests
- Hetzner Cloud API token
- Cloudflare API token

## Time Estimate

Full rebuild typically involves:
- Infrastructure provisioning: ~10-15 min
- Talos bootstrap: ~5-10 min
- Cilium + ArgoCD: ~5 min
- ArgoCD sync all apps: ~10-15 min
- **Total: ~30-45 min**

## Procedure

### Step 1: Destroy Existing Infrastructure (If Applicable)

If infrastructure still exists and needs clean rebuild:

```bash
cd infrastructure/clusters/cloud/bootstrap
source ../../../.env.cloud

# Destroy everything
tofu destroy

# Wait for completion
```

### Step 2: Provision New Infrastructure

```bash
cd infrastructure/clusters/cloud/bootstrap
source ../../../.env.cloud

# Initialize (if fresh clone)
tofu init -backend-config=backend.hcl

# Apply infrastructure
tofu apply
```

This creates:
- 3 control plane servers
- 2 worker servers
- Load balancer
- Private network
- Firewall rules
- DNS records

### Step 3: Get Kubeconfig

```bash
tofu output -raw kubeconfig > <cloud-kubeconfig>
export KUBECONFIG=<cloud-kubeconfig>

# Verify connection (nodes will be NotReady without CNI)
kubectl get nodes
```

### Step 4: Install Cilium CNI

Nodes need CNI before becoming Ready:

```bash
cd ../scripts
./setup-cilium.sh install

# Wait for nodes to become Ready
kubectl get nodes -w
```

### Step 5: Install ArgoCD

```bash
./setup-argocd.sh install
```

### Step 6: Configure SOPS Age Key

```bash
# Provision age private key for secret decryption
./setup-sops-age.sh create
```

### Step 7: Apply App-of-Apps

```bash
./setup-argocd.sh apps

# Monitor sync status
./setup-argocd.sh status
```

### Step 8: Verify Deployment

```bash
# Check all ArgoCD applications
kubectl get applications -n argocd

# Check all pods
kubectl get pods -A | grep -v Running | grep -v Completed

# Verify endpoints
curl -k https://<argocd-host>
curl https://<website-host>
```

## Post-Rebuild Tasks

### 1. Verify Secret Decryption

After Step 6 (SOPS age key), ArgoCD + KSOPS can decrypt all Git-managed secrets. Verify the `cloud-secrets` app syncs and creates:

```bash
# SOPS-encrypted secrets restored by ArgoCD/KSOPS
kubectl get secret -n keycloak keycloak-admin-credentials
kubectl get secret -n keycloak keycloak-postgresql-credentials
kubectl get secret -n keycloak keycloak-config-cli-client-secrets
kubectl get secret -n keycloak keycloak-logout-client-secret
kubectl get secret -n auth-system oauth2-proxy-homepage-secrets
kubectl get secret -n auth-system <protected-app-oauth2-secret>
kubectl get secret -n argocd argocd-oidc-secret
kubectl get secret -n observability grafana-oidc-secret
```

These secrets are fully recoverable from Git because they are SOPS-encrypted. Restoring the age key is the only prerequisite.

### 2. Verify Identity and Auth Stack

> **Important:** Keycloak's realm structure, clients, and the `<admin-user>` user are bootstrapped from `keycloak-config-cli` (embedded in Helm values). However, **any users or configuration added manually through the Keycloak admin console exist only in PostgreSQL** and are NOT in Git. After a full rebuild without database restore, only the Git-defined realm state exists.

```bash
# Keycloak pods running
kubectl get pods -n keycloak

# OIDC issuer responding
curl -s https://<auth-host>/realms/<realm>/.well-known/openid-configuration | head -1

# Keycloak admin console accessible (private split DNS)
curl -sk -o /dev/null -w "%{http_code}" https://<auth-admin-host>/admin/
# Expected: 200

# OpenBao unsealed (auto-unseal sidecar should handle this)
kubectl exec -n platform-ops openbao-0 -c openbao -- bao status | grep Sealed
# Expected: Sealed false
```

### 3. Verify OAuth2 Login Flows

```bash
# Homepage login (should redirect to Keycloak)
curl -s -o /dev/null -w "%{http_code}" https://<website-host>/
# Expected: 302 or 200 (depending on auth state)

# Protected application login (all pages require login)
curl -s -o /dev/null -w "%{http_code}" https://<protected-app-host>/
# Expected: 302 (redirect to Keycloak)
```

Then manually verify in a browser:
- [ ] Homepage: login via OAuth2 proxy → Keycloak → redirect back
- [ ] Protected application: login via OAuth2 proxy → Keycloak → redirect back
- [ ] ArgoCD: SSO login via Keycloak OIDC
- [ ] Grafana: SSO login via Keycloak OIDC

### 4. Verify Observability

- Check Grafana: `https://<grafana-tailnet-host>` (via Tailscale)
- Verify Prometheus targets
- Check Alertmanager receivers

### 5. Verify Website

- Test frontend: `https://<website-host>`
- Test API: `https://<website-host>/api/v1/status`

### 6. Verify CI

GitHub Actions build the images. No CI engine runs in the cluster.
Confirm that the latest workflow runs succeeded:

```bash
gh run list --limit 5
```

### 7. Run Smoke Tests

```bash
# Basic connectivity
kubectl run smoketest --image=busybox:1.28 --rm -it --restart=Never -- \
  wget -O- http://website-frontend.website.svc.cluster.local

# DNS resolution
kubectl run dnstest --image=busybox:1.28 --rm -it --restart=Never -- \
  nslookup kubernetes.default
```

## Data Recovery

### What Git Restores (after age key)

Restoring the SOPS age key (`setup-sops-age.sh create`) enables ArgoCD/KSOPS to decrypt and recreate all platform secrets from Git. Combined with Helm values and Kustomize overlays, this recovers:

- All SOPS-encrypted secrets (auth credentials, API tokens, TLS material)
- Keycloak realm structure, clients, and roles (via `keycloak-config-cli`)
- The `<admin-user>` user account (defined in Helm values)
- OAuth2 proxy configuration and session secrets
- ArgoCD and Grafana OIDC client configuration

### What Requires Backup Restore

The following contain runtime state that only exists in databases, not in Git:

| Data | Location | Backup Coverage |
|------|----------|-----------------|
| Keycloak users (beyond `<admin-user>`) | PostgreSQL in keycloak namespace | Daily fs-backup (RPO: 24h) |
| Keycloak sessions, events, login history | PostgreSQL in keycloak namespace | Daily fs-backup (RPO: 24h) |
| Data of applications with their own PostgreSQL database | PostgreSQL in the application namespace | Velero fs-backup (RPO: the interval of the schedule that includes the namespace) |
| OpenBao secrets/policies | Raft storage in platform-ops | Daily fs-backup + R2 Raft snapshot CronJob |
| Prometheus metrics history | PVC in observability namespace | Daily resource backup (PVC data not backed up — regeneratable) |
| Loki log history | PVC in observability namespace | Daily resource backup (PVC data not backed up — regeneratable) |

Database PVCs are backed up via Velero fs-backup using opt-in pod annotations (`backup.velero.io/backup-volumes`). See [ADR-0020](../../adr/0020-backup-strategy.md) for details.

### What May Need Manual Recreation

After a full rebuild without backup restore:
- Keycloak users created through the admin console (not in `keycloak-config-cli`)
- Keycloak realm configuration changes made manually (not reflected in Helm values)
- Application data in PostgreSQL databases

For backup restores, see [restore-backup.md](restore-backup.md).

## Troubleshooting

### Nodes stuck in NotReady

```bash
# Check Cilium status
kubectl get pods -n kube-system -l k8s-app=cilium

# Check node logs
talosctl logs kubelet --nodes <node-ip>
```

### ArgoCD apps not syncing

```bash
# Check repo-server has KSOPS
kubectl get pods -n argocd -l app.kubernetes.io/component=repo-server

# Check SOPS key
kubectl get secret sops-age -n argocd

# Manual sync
argocd app sync cloud-secrets
```

### DNS not resolving externally

```bash
# Check external-dns
kubectl get pods -n external-dns
kubectl logs -n external-dns -l app.kubernetes.io/name=external-dns

# Verify Cloudflare token
kubectl get secret cloudflare-api-token-externaldns -n external-dns
```

### TLS certificates not issuing

```bash
# Check cert-manager
kubectl get pods -n cert-manager
kubectl get certificate -A
kubectl describe certificate <cert-name> -n <namespace>
```

## Infrastructure State

OpenTofu state is stored in Cloudflare R2:
- **Bucket**: The `bucket` in the `backend "s3"` block of `versions.tf`
- **Path**: The `key` in the same block

To access state from a new machine:
```bash
cd infrastructure/clusters/cloud/bootstrap
cp backend.hcl.example backend.hcl
# Edit with R2 credentials
tofu init -backend-config=backend.hcl
```

## Related

- [restore-backup.md](restore-backup.md) - Restore from backups
- [../talos/etcd-recovery.md](../talos/etcd-recovery.md) - etcd issues
- `infrastructure/clusters/cloud/bootstrap/README.md` - Bootstrap guide
