# Hetzner Cloud Bootstrap

OpenTofu configuration to provision the base Hetzner Cloud infrastructure for the cloud Kubernetes cluster.

## Table of Contents

- [Resources Created](#resources-created)
- [Prerequisites](#prerequisites)
- [Inputs](#inputs)
- [Setup](#setup)
- [Outputs](#outputs)
- [Lockdown Mode](#lockdown-mode)
- [Talos Installation Approach](#talos-installation-approach)
- [Next Steps](#next-steps)
- [Registry Mirrors](#registry-mirrors)
- [Bootstrap Scripts Reference](#bootstrap-scripts-reference)
- [Destroy](#destroy)

## Resources Created

| Resource | Type | Purpose | Sized by |
|----------|------|---------|----------|
| Control plane servers | VM | Control plane nodes | `controlplane_count` x `controlplane_node_type` |
| Worker servers | VM | Worker nodes | `worker_count` x `worker_node_type` |
| Load balancer | LB | Kubernetes API endpoint (public interface off in [lockdown mode](#lockdown-mode)) | `api_load_balancer_type` |
| Private network | Network | Inter-node communication | `network_cidr`, `subnet_cidr` |
| Firewall | Firewall | Security rules | `lockdown_mode`, `admin_ips` |
| DNS record | Cloudflare | `<api_subdomain>.<domain>` | -- |
| R2 buckets | Cloudflare R2 | Backup destinations (`r2.tf`) | the `*_bucket_name` inputs |

Server backups are enabled on every server, which adds to the server price.

## Prerequisites

1. Hetzner Cloud API token (`HCLOUD_TOKEN`)
2. Cloudflare API token with DNS edit permissions (`CLOUDFLARE_API_TOKEN`)
3. Cloudflare R2 bucket for state storage
4. `hcloud-upload-image` binary installed (included in devcontainer)
5. Talos snapshot created on Hetzner (see [Setup step 3](#3-create-talos-snapshot))
6. `talosctl` and `kubectl` for the steps after the first apply

## Inputs

`variables.tf` has no installation defaults: every value that describes one installation
(names, location, server types and counts, versions, CIDRs, domain, bucket names, lockdown)
is a required input. The one exception is `gateway_ip`, which defaults to `null`: its value
exists only after the first apply (see [Setting gateway_ip](#setting-gateway_ip)).

| Source | Holds | Committed |
|--------|-------|-----------|
| `instance.auto.tfvars` | The installation's non-secret values. OpenTofu loads `*.auto.tfvars` from this directory automatically. It exists only in the instance repository. | Yes, in the instance repository only |
| `terraform.tfvars` or a `-var-file` | Zone and account IDs, the gateway IP (after the first apply), and every value a fresh installation has not committed yet (start from `terraform.tfvars.example`) | No |
| `backend.hcl` | The state bucket, the R2 endpoint and the credentials (start from `backend.hcl.example`) | No |

Precedence, lowest first: `terraform.tfvars` in this directory, then `*.auto.tfvars`, then
`-var-file` and `-var` on the command line. So `instance.auto.tfvars` overrides a
`terraform.tfvars` in this directory, and a `-var-file` overrides `instance.auto.tfvars`.

Some files in the instance repository describe only that installation (for example its
redirect DNS zones). The public repository receives this directory without them.

## Setup

### 1. Create configuration files

```bash
cd infrastructure/clusters/cloud/bootstrap

# Create backend config (bucket, endpoint, credentials)
cp backend.hcl.example backend.hcl
# Edit backend.hcl with your values

# Create terraform.tfvars
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your values
```

The instance repository keeps both files outside the tree, in the git-ignored
`infrastructure/.working/cloud/bootstrap/`, and passes them with `-backend-config` and
`-var-file` (steps 4 and 5).

### 2. Set environment variables

```bash
# The values come from the installation's environment file, <cloud-env-file>, one
# NAME=value per line. The names in that file are the installation's own (a
# per-project Hetzner token, for example); the providers read the two names below.
# Do not source the file: inline comments and quoted values break shell sourcing.
export HCLOUD_TOKEN="your-hetzner-token"
export CLOUDFLARE_API_TOKEN="your-cloudflare-token"
```

### 3. Create Talos snapshot

Before provisioning servers, create a Hetzner snapshot containing the Talos image:

```bash
# The schematic ID of the image (see "Snapshot details" below) and the
# talos_version input
SCHEMATIC_ID="ce4c980550dd2ab1b17bbf2b08801c7eb59418eafe8f279833297925d67c7515"
TALOS_VERSION="<talos-version>"

hcloud-upload-image upload \
  --image-url "https://factory.talos.dev/image/${SCHEMATIC_ID}/${TALOS_VERSION}/hcloud-amd64.raw.xz" \
  --architecture x86 \
  --compression xz \
  --labels "os=talos,version=${TALOS_VERSION}" \
  --description "talos-${TALOS_VERSION}"

# Verify the snapshot was created
curl -s -H "Authorization: Bearer $HCLOUD_TOKEN" \
  "https://api.hetzner.cloud/v1/images?type=snapshot&label_selector=os=talos" | \
  jq '.images[] | {id, description, labels}'
```

[hcloud-upload-image](https://github.com/apricote/hcloud-upload-image) downloads the Talos image from factory.talos.dev and creates a Hetzner snapshot with the labels that the `hcloud_image.talos` data source selects. This only needs to be done once per Talos version.

> **Known issue:** snapshots of some Talos versions do not boot on Hetzner Cloud. When the target version's snapshot does not boot, bootstrap new nodes from an older snapshot that does, and upgrade them in place with `talosctl upgrade`.

### 4. Initialize OpenTofu

```bash
tofu init -backend-config=../../../.working/cloud/bootstrap/backend.hcl
```

### 5. Plan and apply

```bash
# Review changes
tofu plan -var-file=../../../.working/cloud/bootstrap/terraform.tfvars

# Apply infrastructure
tofu apply -var-file=../../../.working/cloud/bootstrap/terraform.tfvars
```

Servers boot directly from the Talos snapshot and read their machine configuration from Hetzner's metadata service (`user_data`). No SSH or rescue mode is needed.

Leave `gateway_ip` unset on the first apply. Set it after the cluster runs (see [Setting gateway_ip](#setting-gateway_ip)).

## Outputs

After apply, you'll get:
- `controlplane_public_ips` / `worker_public_ips` - Public IPs of the servers
- `controlplane_private_ips` / `worker_private_ips` - Private IPs for Talos config
- `kubernetes_api_endpoint` - API endpoint URL (`https://<api_subdomain>.<domain>:6443`)
- `talos_config_inputs` - Values needed for Talos machine configs
- `talosconfig` - Talos client configuration (save to ~/.talos/config)
- `kubeconfig` - Kubernetes kubeconfig (save to `<cloud-kubeconfig>`)

## Lockdown Mode

`lockdown_mode = true` restricts access to the cluster to `admin_ips`:

| Path | `lockdown_mode = false` | `lockdown_mode = true` |
|------|-------------------------|------------------------|
| SSH (22), Kubernetes API (6443) and Talos API (50000, 50001) on the servers' public addresses | Open | `admin_ips` only (firewall) |
| HTTP and HTTPS (80, 443) on the servers' public addresses | Open | Closed (firewall) |
| Kubernetes API through the API load balancer (`<api_subdomain>.<domain>`) | Open | Closed when `lockdown_closes_api_load_balancer = true` (public interface of the load balancer disabled); open otherwise |

The API load balancer reaches the control plane over the private network. The Hetzner
Cloud firewall does not filter the private network, so no firewall rule can restrict the
path through the load balancer. `lockdown_closes_api_load_balancer = true` (with lockdown
on) disables the public interface of the load balancer instead. The load balancer keeps its
targets and health checks on the private network, and switching either variable off enables
the public interface again. The two variables are separate so that an installation already in
lockdown mode patches its nodes with the API host entry below first and closes the load
balancer's public path as a deliberate second step, from an address in `admin_ips`.

With the load balancer's public interface disabled, the `kubernetes_api_endpoint` name does not answer from the internet.
Reach the API on a control plane node's public address from an address in `admin_ips`:

```bash
kubectl --kubeconfig <cloud-kubeconfig> --server "https://<controlplane-public-ip>:6443" get nodes
```

The nodes do not use the public interface. Their machine configuration resolves
`<api_subdomain>.<domain>` to the load balancer's private address (host `.2` of
`subnet_cidr`) through `extraHostEntries`, so they reach the API over the private network in
both modes. The servers ignore `user_data` changes, so a node created before this entry
existed does not have it. Before you switch lockdown on, add the entry to such nodes:

```bash
cat > api-host.yaml <<'EOF'
machine:
  network:
    extraHostEntries:
      - ip: <api-load-balancer-private-ip>  # the api_lb_private_ip output
        aliases:
          - <api_subdomain>.<domain>
EOF
talosctl -n <node-addresses> patch machineconfig --patch @api-host.yaml
```

Lockdown mode does not reach the load balancer of the Cilium Gateway. The Hetzner CCM
manages that load balancer, not this root, and it forwards HTTP and HTTPS to the nodes over
the private network.

## Talos Installation Approach

### Current Approach: Snapshot + user_data

We use [hcloud-upload-image](https://github.com/apricote/hcloud-upload-image) to create a Hetzner snapshot from the official Talos factory image, then provision servers from that snapshot with machine config delivered via Hetzner's `user_data` metadata service.

**Workflow:**
1. **Create snapshot** (one-time per Talos version): `hcloud-upload-image upload` (see [Setup step 3](#3-create-talos-snapshot))
2. **`tofu apply`**: Creates servers from snapshot with `user_data` containing machine config
3. **Server boots**: Talos reads config from metadata service and joins the cluster

**Benefits:**
- **Fully declarative**: Standard `hcloud_server` lifecycle, no provisioners
- **Idempotent**: `tofu apply` is safe to re-run
- **No SSH dependency**: No rescue mode, no SSH keys needed for provisioning
- **Fast**: Servers boot directly into a configured Talos instance
- **Platform**: Uses `hcloud` platform (reads config from metadata) instead of `metal`

**Snapshot details:**
- Built from factory.talos.dev with schematic ID `ce4c980550dd2ab1b17bbf2b08801c7eb59418eafe8f279833297925d67c7515`
- Schematic includes: `siderolabs/qemu-guest-agent` (Hetzner VM integration)
- Labeled `os=talos,version=vX.Y.Z` for lookup by OpenTofu data source
- The `user_data` field has a 32 KB limit on Hetzner; Talos configs fit well within this

### Comparison of Available Methods

| Method | Automation | Complexity | Reliability | Notes |
|--------|------------|------------|-------------|-------|
| **hcloud-upload-image + user_data** (current) | Full OpenTofu | Low | High | Snapshot once, declarative provisioning |
| Rescue Mode + dd (previous) | Full OpenTofu | Medium | High | Complex provisioners, SSH-dependent |
| Hetzner Public ISO | Manual/hcloud CLI | Low | High | Available since April 2025, but no Terraform support |
| boot-to-talos | Full OpenTofu | Low | **Low on Hetzner** | kexec unreliable on Hetzner VMs |
| Community Terraform Modules | Full OpenTofu | Very Low | High | [hcloud-talos](https://github.com/hcloud-talos/terraform-hcloud-talos) — monolithic, replaces all infra |

### Upgrading Talos Versions

To upgrade Talos on the existing cluster:

1. **Build new snapshot** (if supported): see [Setup step 3](#3-create-talos-snapshot)
2. **Upgrade in-place**: Use `talosctl upgrade` per node (see [upgrade runbook](../../../../docs/runbooks/talos/upgrade-os.md))
3. **Upgrade Kubernetes**: `talosctl upgrade-k8s --to <version>`
4. **Update variables**: Set `talos_version` and `kubernetes_version` in `instance.auto.tfvars` (and in any `-var-file` that also sets them)
5. **Clean up old snapshots**: Delete old version snapshots from Hetzner

> **Note:** The `hcloud_image.talos` data source selects the snapshot labelled with `talos_version`, so `talos_version` must name a version for which a snapshot exists. If that version's snapshot does not boot (see the known issue in [Setup step 3](#3-create-talos-snapshot)), new servers bootstrap from an older snapshot and are upgraded in place.

## Next Steps

After `tofu apply` completes, follow these steps to complete the cluster setup.

### 1. Get kubeconfig

```bash
tofu output -raw kubeconfig > <cloud-kubeconfig>
export KUBECONFIG=<cloud-kubeconfig>
kubectl get nodes  # Should show nodes in NotReady state
```

### 2. Install Cilium CNI

Nodes will be `NotReady` until a CNI is installed. Install Cilium with Talos-compatible settings:

```bash
helm repo add cilium https://helm.cilium.io/
helm repo update cilium

# Pre-install Gateway API CRDs (required before enabling gatewayAPI)
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.4.1/standard-install.yaml

helm upgrade --install cilium cilium/cilium \
    --version "1.19.1" \
    --namespace kube-system \
    --set operator.replicas=1 \
    --set hubble.relay.enabled=true \
    --set hubble.ui.enabled=true \
    --set hubble.metrics.enableOpenMetrics=true \
    --set hubble.metrics.enabled="{dns,drop,tcp,flow,icmp,http}" \
    --set ipam.mode=kubernetes \
    --set kubeProxyReplacement=true \
    --set k8sServiceHost="localhost" \
    --set k8sServicePort="7445" \
    --set hostPort.enabled=true \
    --set nodePort.enabled=true \
    --set securityContext.capabilities.ciliumAgent="{CHOWN,KILL,NET_ADMIN,NET_RAW,IPC_LOCK,SYS_ADMIN,SYS_RESOURCE,DAC_OVERRIDE,FOWNER,SETGID,SETUID}" \
    --set securityContext.capabilities.cleanCiliumState="{NET_ADMIN,SYS_ADMIN,SYS_RESOURCE}" \
    --set cgroup.autoMount.enabled=false \
    --set cgroup.hostRoot=/sys/fs/cgroup \
    --set bpf.hostLegacyRouting=true \
    --set gatewayAPI.enabled=true \
    --set gatewayAPI.enableProxyProtocol=true \
    --wait \
    --timeout 5m
```

**Important Talos-specific settings:**
- `k8sServiceHost=localhost` + `k8sServicePort=7445`: Uses Talos KubePrism for API access
- `securityContext.capabilities.*`: Talos doesn't allow SYS_MODULE, so we explicitly set allowed caps
- `cgroup.autoMount.enabled=false`: Talos already mounts cgroupv2
- `bpf.hostLegacyRouting=true`: Required for DNS to work with Talos 1.8+

After installation, nodes should become `Ready`:
```bash
kubectl get nodes  # All nodes should show Ready
```

### 3. Install ArgoCD

Use the cloud setup scripts:

```bash
cd ../scripts
./setup-argocd.sh install
```

This installs ArgoCD and configures it for kustomize plugins (needed for KSOPS).

### 4. Configure SOPS Age Key (Required Before Apps)

**Important:** This step must be completed before applying the app-of-apps. The `cloud-secrets` application needs the age private key to decrypt secrets for external-dns and cert-manager.

```bash
# Check if you have an age keypair
./setup-sops-age.sh status

# If needed, generate a keypair at ~/.config/sops/age/keys.txt (or $SOPS_AGE_KEY_FILE)
./setup-sops-age.sh generate

# Provision the private key to the cloud cluster
./setup-sops-age.sh create
```

### 5. Apply KSOPS Repo-Server Patch

Configure the ArgoCD repo-server to use KSOPS for decrypting secrets:

```bash
kubectl --kubeconfig <cloud-kubeconfig> apply -f ../../../../platform/argocd/components/ksops/repo-server-patch.yaml
kubectl --kubeconfig <cloud-kubeconfig> rollout restart deployment/argocd-repo-server -n argocd
kubectl --kubeconfig <cloud-kubeconfig> rollout status deployment/argocd-repo-server -n argocd --timeout=120s
```

### 6. Apply App-of-Apps

Now apply the app-of-apps to deploy all platform applications:

```bash
./setup-argocd.sh apps
```

This will deploy (in order via sync waves):
1. **cloud-secrets** (wave -4): Decrypts and creates Cloudflare API token secrets
2. **hetzner-ccm** (wave -3): Hetzner Cloud Controller Manager
3. **hetzner-csi** (wave -3): Hetzner CSI driver for volumes
4. **ingress-nginx** (wave -2): Ingress controller
5. **cert-manager** (wave -1): TLS certificate management (uses secrets)
6. **external-dns** (wave -1): DNS record management (uses secrets)
7. **registry** (wave 0): Pull-through cache registries with TLS

### 7. Configure Slack Webhooks (Optional)

Configure the Slack webhook for alert notifications:

```bash
# Check status
./setup-slack-webhook.sh status

# Configure the alerts webhook (for Alertmanager notifications)
./setup-slack-webhook.sh create alerts

# Test the webhook
./setup-slack-webhook.sh test alerts
```

The script reads the webhook URL from the `.env` file (`SLACK_WEBHOOK_ALERTS_URL` or `SLACK_WEBHOOK_URL_ALERTS`).
Deployment notifications come from GitHub Actions, which read the `SLACK_WEBHOOK_DEPLOYMENTS_URL` repository secret.

### 8. Configure GitHub Webhooks

Create the GitHub webhook for ArgoCD sync:

```bash
# Check status (shows the existing webhook and secret)
./setup-github-webhooks.sh status

# Create the webhook (idempotent - safe to run multiple times)
./setup-github-webhooks.sh create
```

This script:
- Reads the webhook secret from the cluster (deployed via GitOps)
- Creates the webhook via GitHub API
- ArgoCD webhook: triggers immediate sync on push

**Prerequisites:**
- GitHub CLI authenticated: `gh auth login`
- Cluster secrets deployed (SOPS-encrypted secrets synced by ArgoCD)

### 9. Verify Deployment

```bash
./setup-argocd.sh status
kubectl --kubeconfig <cloud-kubeconfig> get applications -n argocd
```

All applications should show `Synced` and `Healthy`.

### Access ArgoCD

- **URL**: `https://argocd.<domain>` (after external-dns creates the record)
- **Username**: admin
- **Password**: `./setup-argocd.sh password`

## Registry Mirrors

The cloud cluster includes pull-through cache registries for improved performance and reliability. These are configured at two levels:

### Talos Machine Configuration

Registry mirrors are configured in `talos.tf` and delivered to nodes via `user_data` on server creation:

```yaml
machine:
  registries:
    mirrors:
      docker.io:
        endpoints:
          - https://mirror-docker-io.<internal_subdomain>.<domain>
      ghcr.io:
        endpoints:
          - https://mirror-ghcr-io.<internal_subdomain>.<domain>
      quay.io:
        endpoints:
          - https://mirror-quay-io.<internal_subdomain>.<domain>
      registry.k8s.io:
        endpoints:
          - https://mirror-registry-k8s-io.<internal_subdomain>.<domain>
```

The nodes resolve these names to `gateway_ip` through `extraHostEntries`; they are not in public DNS.

This tells containerd on each node to use the internal mirrors before falling back to upstream registries.

### Setting gateway_ip

`gateway_ip` is the external address of the Cilium Gateway's load balancer. The Hetzner
CCM creates that load balancer only after the cluster runs the platform applications (see
[Next Steps](#next-steps)), so the first `tofu apply` runs without `gateway_ip`. Until
`gateway_ip` is set, the nodes have no host entries for the mirror names and containerd
pulls from the upstream registries.

After the Gateway has its external address:

1. Read the address: `kubectl --kubeconfig <cloud-kubeconfig> get gateway -A` shows it in
   the `ADDRESS` column.
2. Set `gateway_ip` in `terraform.tfvars` (or in the `-var-file`) and run `tofu apply`.
   Servers created from now on receive the host entries through `user_data`. The existing
   servers ignore `user_data` changes, so this apply does not change them.
3. Add the host entries to the existing nodes:

```bash
cat > registry-hosts.yaml <<'EOF'
machine:
  network:
    extraHostEntries:
      - ip: <gateway-ip>
        aliases:
          - mirror-docker-io.<internal_subdomain>.<domain>
          - mirror-ghcr-io.<internal_subdomain>.<domain>
          - mirror-quay-io.<internal_subdomain>.<domain>
          - mirror-registry-k8s-io.<internal_subdomain>.<domain>
          - local-registry.<internal_subdomain>.<domain>
EOF
talosctl -n <node-addresses> patch machineconfig --patch @registry-hosts.yaml
```

### Registry Kubernetes Deployment

The `registry` ArgoCD application deploys the actual mirror services:

| Component | Internal DNS | Purpose |
|-----------|--------------|---------|
| mirror-docker-io | `mirror-docker-io.<internal_subdomain>.<domain>` | Docker Hub cache |
| mirror-ghcr-io | `mirror-ghcr-io.<internal_subdomain>.<domain>` | GitHub Container Registry cache |
| mirror-quay-io | `mirror-quay-io.<internal_subdomain>.<domain>` | Quay.io cache |
| mirror-registry-k8s-io | `mirror-registry-k8s-io.<internal_subdomain>.<domain>` | Kubernetes registry cache |
| local-registry | `local-registry.<internal_subdomain>.<domain>` | In-cluster built images |

Resources are in `platform/resources/cloud/registry/`.

### Verifying Mirrors

```bash
# Check pods are running
kubectl --kubeconfig <cloud-kubeconfig> get pods -n registry-system

# Expected output:
# NAME                                      READY   STATUS
# local-registry-xxx                        1/1     Running
# mirror-docker-io-xxx                      1/1     Running
# mirror-ghcr-io-xxx                        1/1     Running
# mirror-quay-io-xxx                        1/1     Running
# mirror-registry-k8s-io-xxx                1/1     Running

# Test mirror accessibility
curl -I https://mirror-docker-io.<internal_subdomain>.<domain>/v2/

# Check ArgoCD app status
./setup-argocd.sh status | grep registry
```

### Updating Mirror Configuration

To add or modify registry mirrors:

1. Update `talos.tf` with new mirror endpoints
2. Run `tofu apply` to update machine configs (affects new servers only)
3. For existing nodes, use `talosctl edit machineconfig` or `talosctl patch`
4. Update `platform/resources/cloud/registry/` with new deployments/ingresses
5. ArgoCD will sync the new resources

See [ADR 0008: Container Registry Strategy](../../../../docs/adr/0008-container-registry-strategy.md) for the full registry architecture.

## Bootstrap Scripts Reference

| Script | Purpose |
|--------|---------|
| `setup-argocd.sh install` | Install ArgoCD to the cluster |
| `setup-argocd.sh apps` | Apply app-of-apps (with prerequisite checks) |
| `setup-argocd.sh status` | Show ArgoCD and application status |
| `setup-argocd.sh password` | Get admin password |
| `setup-sops-age.sh create` | Provision age private key for SOPS decryption |
| `setup-sops-age.sh status` | Show SOPS configuration status |
| `setup-slack-webhook.sh create` | Configure the Slack webhook for alert notifications |
| `setup-slack-webhook.sh status` | Show Slack webhook configuration status |
| `setup-slack-webhook.sh test` | Send test notification to verify webhook |
| `setup-github-webhooks.sh create` | Create the GitHub webhook for ArgoCD |
| `setup-github-webhooks.sh status` | Show GitHub webhook configuration status |

## Destroy

```bash
tofu destroy -var-file=../../../.working/cloud/bootstrap/terraform.tfvars
```

**Warning**: This will destroy all infrastructure including any data on the servers.
