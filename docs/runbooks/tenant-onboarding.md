# Tenant Onboarding

Step-by-step procedure for onboarding a new D1 tenant into the Manyfold platform.

The template files live in `platform/tenants/_template/`. This runbook covers the full
operator workflow from rendering manifests to post-onboarding activation, including
known gotchas from the first D1 tenant build.

## Table of Contents

- [Placeholders](#placeholders)
- [Prerequisites](#prerequisites)
- [Procedure](#procedure)
  - [Step 1: Render the Template](#step-1-render-the-template)
  - [Step 2: Create the Tenant Repo](#step-2-create-the-tenant-repo)
  - [Step 3: Register the Landing-Zone Application in ArgoCD](#step-3-register-the-landing-zone-application-in-argocd)
  - [Step 4: Wire Shared Platform Services](#step-4-wire-shared-platform-services)
  - [Step 5: Ingress / DNS / TLS (deferred)](#step-5-ingress--dns--tls-deferred)
  - [Step 6: Tenant CI & Image Deployment (D2)](#step-6-tenant-ci--image-deployment-d2)
- [Verification](#verification)
- [Post-Onboarding Activation and Gotchas](#post-onboarding-activation-and-gotchas)
  - [ArgoCD Deployer Token is Inert Until Activated](#argocd-deployer-token-is-inert-until-activated)
  - [ArgoCD Admin RBAC is Inert Until Realm Brokering](#argocd-admin-rbac-is-inert-until-realm-brokering)
  - [ApplicationSet Deletion Cascades](#applicationset-deletion-cascades)
  - [ServiceMonitor Scrape Contract](#servicemonitor-scrape-contract)
  - [PV Backup Opt-In Required](#pv-backup-opt-in-required)
- [Related](#related)

## Placeholders

Replace these placeholders with the values of your installation:

| Placeholder | Value |
|---|---|
| `<tenant>`, `$TENANT` | The name of the new tenant. The name must match the Keycloak realm name. |
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster. |
| `<github-org>` | The GitHub organisation that holds the platform and tenant repositories. |
| `<instance-repo-url>` | The Git URL of the repository that holds `platform/tenants/<tenant>`. |
| `<keycloak-admin-url>` | The URL of the Keycloak admin console. |
| `<ghcr-source-secret-file>` | The SOPS-encrypted file in `platform/resources/cloud/secrets/` that holds the shared GHCR pull secret. |
| `<tenant-ghcr-secret-file>` | The name of the new SOPS-encrypted file for the GHCR pull secret of the tenant. Use the naming pattern of the existing per-tenant files. |

## Prerequisites

- `kubectl` configured for the cloud cluster (`<cloud-kubeconfig>`)
- `argocd` CLI authenticated (or access to the ArgoCD UI via Tailscale)
- SOPS age key present at `~/.config/sops/age/keys.txt`
- `kustomize` and `kubeconform` installed (for local validation)
- GitHub personal access token with repo-create permission in the `<github-org>` org
- **GitHub Team plan** — the org must be on a Team (or higher) plan before granting any
  external collaborator access to private repos. Upgrade the plan before proceeding if
  the tenant's CI identity is an external GitHub account.

## Procedure

### Step 1: Render the Template

```bash
TENANT=<tenant-name>   # must match the Keycloak realm name

# Copy the template directory
cp -r platform/tenants/_template platform/tenants/$TENANT

# Rename .yaml.tmpl -> .yaml
for f in platform/tenants/$TENANT/*.yaml.tmpl; do
  mv "$f" "${f%.tmpl}"
done

# Replace all {{TENANT}} placeholders (macOS-compatible)
find platform/tenants/$TENANT -name '*.yaml' \
  -exec sed -i '' "s/{{TENANT}}/$TENANT/g" {} +

# Verify no unreplaced {{TENANT}} placeholders remain
# (ArgoCD runtime vars {{.path.basename}} / {{.path.path}} are NOT flagged by this check)
grep -rn '{{TENANT}}' platform/tenants/$TENANT \
  && echo "ERROR: unreplaced placeholders" || echo "OK: all placeholders rendered"

# Remove the template README from the rendered directory
rm platform/tenants/$TENANT/README.md
```

Run the validation harness immediately after rendering:

```bash
./scripts/ci/validate-tenant-manifests.sh
```

Exit 0 = all checked directories passed `kustomize build` + `kubeconform` plus the
guardrail assertions (default-deny rule fields, AppProject allowlist, forced project).
The harness auto-discovers every `platform/tenants/<name>/` directory that contains a
`kustomization.yaml`, so a newly rendered tenant directory is picked up automatically —
no harness edit is needed (`_template/` is skipped because it has only `*.yaml.tmpl`).

Commit the rendered manifests before proceeding to Step 3.

### Step 2: Create the Tenant Repo

1. Create `<github-org>/$TENANT` on GitHub. Use the repo of an existing tenant as a
   template if that repo is a GitHub template repository; otherwise create manually and
   copy the skeleton `gitops/prod/` directory structure.

2. Ensure ArgoCD has read access to the new repo. The ApplicationSet git generator
   silently errors and generates no Applications if the repo URL is unreachable or
   uncredentialed. Add deploy-key credentials via:

   ```bash
   # Add a deploy key to the GitHub repo (read-only), then register with ArgoCD:
   argocd repo add https://github.com/<github-org>/$TENANT.git \
     --ssh-private-key-path ~/.ssh/<deploy-key>
   # Or use the ArgoCD UI: Settings -> Repositories -> Connect Repo
   ```

3. Add `gitops/prod/` to the tenant repo containing at least one Kubernetes manifest so
   the ApplicationSet generates an Application on first sync. The directory must exist
   and be non-empty, otherwise the git generator silently skips it.

> **GitHub Team plan note**: if the tenant's CI identity is an external GitHub account
> (not an org member), the org must be on the Team plan or higher before granting
> collaborator access to private repos. Confirm the plan tier before sending invitations.

### Step 3: Register the Landing-Zone Application in ArgoCD

Create an Application manifest in `platform/argocd/cloud/applications/`:

```yaml
# platform/argocd/cloud/applications/<tenant>-landing-zone.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: <tenant>-landing-zone
  namespace: argocd
  annotations:
    argocd.argoproj.io/sync-wave: "1"
spec:
  project: default
  source:
    repoURL: <instance-repo-url>
    targetRevision: HEAD
    path: platform/tenants/<tenant>
  destination:
    server: https://kubernetes.default.svc
    namespace: argocd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

Register the new file in `platform/argocd/cloud/applications/kustomization.yaml`:

```yaml
resources:
  # ... existing entries ...
  - <tenant>-landing-zone.yaml
```

Commit and push to `main`. ArgoCD detects the commit and creates the landing-zone
Application (sync-wave 1), which in turn creates:

- `<tenant>-prod` and `<tenant>-dev` namespaces
- ResourceQuota and LimitRange for both namespaces
- CiliumNetworkPolicy default-deny + baseline allows
- ArgoCD AppProject with namespace boundary and deployer role
- Operator-owned ApplicationSet (git generator)

### Step 4: Wire Shared Platform Services

Each item below is a separate commit and PR. Proceed after the landing-zone Application
is Synced and Healthy in ArgoCD.

**Keycloak realm**

Add a realm block for `$TENANT` in `platform/components/keycloak/values-cloud.yaml`.
The `keycloak-config-cli` job applies it on the next ArgoCD sync of the `keycloak`
Application.

```bash
# Confirm the realm was applied after ArgoCD syncs keycloak:
kubectl --kubeconfig <cloud-kubeconfig> \
  logs -n keycloak -l app.kubernetes.io/component=keycloak-config-cli --tail=100 | grep -i $TENANT
```

**OpenBao mount, policy, and role**

```bash
cd infrastructure/clusters/cloud/scripts
./setup-openbao-k8s-auth.sh create-tenant "$TENANT"
# verify:
./setup-openbao-k8s-auth.sh status-tenant "$TENANT"
```

This creates:
- `$TENANT/` KV v2 secrets engine mount
- `$TENANT-reader` policy (read-only at day-one scope)
- Kubernetes auth role binding `$TENANT-prod` and `$TENANT-dev` service accounts

> The `create-tenant <name>` / `status-tenant <name>` subcommands are parameterized,
> so no script change is needed per tenant. (Older tenant-specific aliases can remain
> for the first tenant.)

**App-managed credential vault (optional -- only for tenants with a runtime secret-store feature)**

If a tenant application lets end users store/reveal/rotate secrets at runtime (for
example, a shared-credential vault in a tenant portal), provision a per-app crypto vault using OpenBao
Transit. The app authenticates with a dedicated ServiceAccount and a per-app role scoped
to a single Transit key -- it encrypts/decrypts via OpenBao but stores the ciphertext in
its own database. See [ADR-0040](../adr/0040-tenant-app-managed-secrets-via-openbao-transit.md).

```bash
cd infrastructure/clusters/cloud/scripts
# create-app-vault <tenant> <app> <serviceaccount>
./setup-openbao-k8s-auth.sh create-app-vault "$TENANT" portal portal
# verify:
./setup-openbao-k8s-auth.sh status-app-vault "$TENANT" portal
```

This creates the Transit key `$TENANT-portal-credvault`, the identically named key-scoped
policy (encrypt/decrypt/rewrap only), and the per-SA auth role `$TENANT-portal`. The
tenant-side implementation contract is in the handoff document of the tenant repo.

> **Network egress (required for the app to reach OpenBao at runtime):** under the tenant
> default-deny, add an egress allow to the tenant's `*-allow-baseline` CiliumNetworkPolicy
> for `io.kubernetes.pod.namespace: platform-ops` + `app.kubernetes.io/name: openbao` on
> port 8200. See the `network-policy.yaml` of an existing tenant with a vault for the
> reference rule. Without it the
> app's `kubernetes/login` call is silently dropped.

**GHCR image-pull credential**

The tenant's app image is a **private** GHCR package (it inherits the private tenant
repo's visibility), so `$TENANT-prod` needs a `ghcr-credentials` dockerconfigjson secret
or its pods `ImagePullBackOff` even after a successful push. Register the namespace in
the rotation fan-out and generate the secret from the existing PAT:

```bash
cd platform/resources/cloud/secrets

# 1. Add the namespace to the TARGETS array in rotate-ghcr-credentials.sh:
#      "$TENANT-prod:<tenant-ghcr-secret-file>"
# 2. Generate just the new file from the existing source secret (no rotation):
export SOPS_AGE_KEY_FILE="$HOME/.config/sops/age/keys.txt"
sops -d <ghcr-source-secret-file> \
  | sed -E "s/^([[:space:]]*)namespace:.*/\1namespace: $TENANT-prod/" \
  > .plain.yaml
sops -e .plain.yaml > "<tenant-ghcr-secret-file>" && rm -f .plain.yaml
# 3. Register the file in ksops-generator.yaml, then commit + push.
```

ArgoCD's `cloud-secrets` app syncs it into `$TENANT-prod`. The tenant's pod spec must
then reference it with `imagePullSecrets: [{name: ghcr-credentials}]` (a tenant-repo
change — see [Step 6](#step-6-tenant-ci--image-deployment-d2)).

**Velero backup schedule**

Add a Velero `Schedule` manifest to `platform/resources/cloud/velero-schedules/`
targeting `$TENANT-prod` and `$TENANT-dev` namespaces (daily at 03:00 UTC, 7-day
retention). Register in that directory's `kustomization.yaml`.

See the schedule file of an existing tenant in that directory as the reference
implementation.

**Observability**

Add a `ServiceMonitor` in `platform/observability/overlays/cloud/servicemonitors/` and alert
rules in `platform/observability/overlays/cloud/alerts/`. Register each file in the corresponding
directory's `kustomization.yaml`. The directories without `overlays/` hold only generic rules
and monitors, which are published; a tenant's belong to the installation.

See the ServiceMonitor and PrometheusRule of an existing tenant as reference implementations.

**ArgoCD admin RBAC**

Add a project-scoped admin role for the new tenant in
`platform/argocd/overlays/cloud/argocd-rbac-cm.yaml`, mirroring the `<tenant>-admin` block
of an existing tenant:

```yaml
# In argocd-rbac-cm data.policy.csv — add these three lines:
p, role:<tenant>-admin, applications, *, <tenant>/*, allow
p, role:<tenant>-admin, logs, get, <tenant>/*, allow
g, <tenant>-admin, role:<tenant>-admin
```

This role is **inert until realm brokering** is configured (see
[ArgoCD Admin RBAC is Inert Until Realm Brokering](#argocd-admin-rbac-is-inert-until-realm-brokering)).
Without this step the tenant gets no admin RBAC entry at all — not even an inert one.

### Step 5: Ingress / DNS / TLS (deferred)

This step is deferred until the tenant has a confirmed domain. When ready:

1. Add an HTTPS listener `https-$TENANT` to
   `platform/components/cilium-gateway/cloud/gateway.yaml`.

2. Add the tenant domain(s) to the external-dns `domainFilters` and add a cert-issuer
   solver for the tenant DNS zone (cert-manager ClusterIssuer).

3. The tenant's `HTTPRoute` lives in the tenant repo (`gitops/prod/`) and attaches to
   the shared Gateway via `parentRefs.sectionName: https-$TENANT`.

### Step 6: Tenant CI & Image Deployment (D2)

Wire the tenant repo to build, publish, and deploy its app image. The full decision and
rationale are in ADR-0037 (tenant image build, publish and deploy); the
copy-able recipe is `platform/tenants/_tenant-repo-scaffold/`.

1. **CI workflow** — copy `platform/tenants/_tenant-repo-scaffold/ci.yml` into the tenant
   repo at `.github/workflows/ci.yml`. Replace the `<app>` placeholder in the `paths:` filter
   (`apps/<app>/**`) and edit the `env:` block (`APP_DIR`, `DOCKERFILE`, `IMAGE_BASE`,
   `DEPLOY_MANIFEST`). A placeholder left in `paths:` means later app changes never trigger
   CI. It builds + tests, pushes
   `ghcr.io/<github-org>/$TENANT/<app>:main-<sha>` with the repo's `GITHUB_TOKEN`, then
   commits the tag bump to `main` (`[skip ci]`) for ArgoCD to sync.

2. **Pull secret reference** — add `imagePullSecrets: [{name: ghcr-credentials}]` to the
   workload's pod spec in the tenant repo (pairs with the platform-side secret from
   Step 4). Mirrors `apps/website/overlays/cloud/backend-deployment.yaml`.

3. **Package creation policy** — confirm the org allows a repo's `GITHUB_TOKEN` to create
   packages (default-on). If disabled, pre-create the package and grant the tenant repo
   `write`, or supply a `write:packages` PAT and swap the `docker/login-action` password.

> **No ArgoCD deployer token is required for this flow.** CI commits the tag and the
> operator-owned ApplicationSet (`selfHeal`) syncs it — CI never calls `argocd`. The
> inert `deployer` role (below) is only needed if a tenant later wants CI to trigger an
> explicit `argocd app sync`.

## Verification

Run these checks after each major step:

```bash
# After Step 1: harness passes
./scripts/ci/validate-tenant-manifests.sh

# After Step 3: namespaces exist
kubectl --kubeconfig <cloud-kubeconfig> get ns ${TENANT}-prod ${TENANT}-dev

# After Step 3: AppProject present
kubectl --kubeconfig <cloud-kubeconfig> \
  get appproject $TENANT -n argocd

# After Step 3: ApplicationSet present and generating Apps
kubectl --kubeconfig <cloud-kubeconfig> \
  get applicationset ${TENANT}-tenant -n argocd

# After Step 4 (Keycloak): realm exists in admin console
# <keycloak-admin-url> -> Realms list

# After Step 4 (OpenBao): KV mount accessible
bao kv list $TENANT/

# After Step 4 (Velero): schedule registered
velero schedule get | grep $TENANT

# After Step 4 (Observability): ServiceMonitor present
kubectl --kubeconfig <cloud-kubeconfig> \
  get servicemonitor -n observability | grep $TENANT
```

## Post-Onboarding Activation and Gotchas

These are dependencies and silent failure modes found during the first D1 tenant build.
Read this section before starting D2 work on any tenant.

### ArgoCD Deployer Token is Inert Until Activated

The AppProject `deployer` role is created by the template but produces no usable
credentials until a CI token is explicitly generated. The role policies exist but there
is no token to authenticate with.

**When a tenant repo gains a CI pipeline (D2)**, activate by generating a project-scoped
token:

```bash
argocd proj role create-token $TENANT deployer
# Save the output token — it is only shown once.
# Store it as a CI secret in the tenant repo.
```

Alternatively, add a group binding to `argocd-rbac-cm` for a machine identity:

```yaml
# In argocd-rbac-cm policy.csv:
g, <ci-identity>, proj:<tenant>:deployer
```

Until activated, the tenant CI pipeline cannot trigger ArgoCD syncs even if credentials
are passed — the request will be rejected as unauthorized.

### ArgoCD Admin RBAC is Inert Until Realm Brokering

The `<tenant>-admin` ArgoCD RBAC role lives in the shared platform file
`platform/argocd/overlays/cloud/argocd-rbac-cm.yaml` (added during Step 4 above) — it is NOT
part of the per-tenant AppProject. The AppProject defines only the `deployer` role.

Even after the role entry is added, it does not function until the tenant Keycloak realm
is brokered into ArgoCD's OIDC provider. ArgoCD authenticates against the single shared
platform realm; a role granted in a tenant-specific realm does not propagate to
ArgoCD's `groups` claim.

**Consequence**: Until realm brokering is configured, tenant admin users fall back to
readonly access in ArgoCD (`policy.default: role:readonly`). They can observe their
Applications but cannot trigger syncs or modify settings via the UI or CLI.

This is a known deferred item — see ADR-0033 Blueprint DoD for the brokering approach.
Do not tell tenants they have admin access in ArgoCD until realm brokering is live.

### ApplicationSet Deletion Cascades

Generated tenant Applications carry the cascade-delete finalizer
(`resources-finalizer.argocd.argoproj.io`) and are created with `prune: true`. This
means:

- Removing a `gitops/<env>` directory from the tenant repo causes ArgoCD to delete all
  resources in the corresponding namespace.
- Deleting the ApplicationSet itself causes ArgoCD to cascade-delete all generated
  Applications and their managed resources.
- Removing the landing-zone Application (or the AppProject) prunes the tenant's live
  infrastructure.

**Before removing any of these resources**: drain workloads, trigger a Velero backup,
and confirm the backup completed successfully. See the tenant restore runbook of the
installation for the restore bundle procedure, and
[restore-backup.md](disaster-recovery/restore-backup.md) for the general restore procedure.

### ServiceMonitor Scrape Contract

For the operator-owned ServiceMonitor to scrape tenant workloads under the default-deny
network policy, two conditions must both be met:

1. **Label**: tenant Services must carry the label `manyfold.dk/tenant: <tenant>`.
   The ServiceMonitor selector matches on this label.

2. **Port name**: tenant Services must expose a port named `http` (path `/metrics`).
   The ServiceMonitor targets the port by name, not by number.

Additionally, the default-deny CiliumNetworkPolicy in the template already includes an
allow rule for Prometheus in the `observability` namespace — this rule is
operator-owned and must NOT be removed by tenant operators. If scraping is broken,
check that the network policy is in place:

```bash
kubectl --kubeconfig <cloud-kubeconfig> \
  get ciliumnetworkpolicies -n ${TENANT}-prod
```

Both conditions must be satisfied simultaneously. Missing the label means the
ServiceMonitor does not select the Service. Missing the `http` port name means
Prometheus cannot connect even if the Service is selected.

### PV Backup Opt-In Required

The Velero schedule captures all namespaced Kubernetes objects in `<tenant>-prod` and
`<tenant>-dev`, but **volume (PV) data is only backed up for pods that opt in**.

Tenant workloads must annotate their pods:

```yaml
metadata:
  annotations:
    backup.velero.io/backup-volumes: <comma-separated-volume-names>
```

Database pods additionally require pre/post hooks (e.g., `pg_dump`) to ensure
consistent snapshots. Without these annotations, the Velero backup contains the
PersistentVolumeClaim object (recoverable) but not the actual data on the volume (lost
on cluster rebuild).

Inform tenant operators of this requirement during D1 handoff. Verify opt-in status
by checking backup details after the first scheduled run:

```bash
velero backup describe <backup-name> --details | grep -A5 "PersistentVolume"
```

If PVC details are absent or show "skipped", volume data is not being captured.

## Related

- [platform/tenants/_template/README.md](../../platform/tenants/_template/README.md) — template parameter reference and quick-start
- The tenant restore runbook of the installation — restore bundle procedure (reference implementation)
- [docs/adr/0033-multi-tenancy-model-and-tenant-isolation.md](../adr/0033-multi-tenancy-model-and-tenant-isolation.md) — architecture decision and D2 deferred items
- `platform/tenants/<tenant>/` of an existing tenant — landing zone (reference implementation)
- `platform/resources/cloud/velero-schedules/` — Velero schedule manifests
- `platform/observability/overlays/cloud/servicemonitors/` — tenant ServiceMonitor manifests
