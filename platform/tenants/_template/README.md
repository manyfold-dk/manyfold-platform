# Tenant Landing-Zone Template

Parameterized blueprint for onboarding a new tenant into the platform: two namespaces,
quotas, a default-deny network baseline, an Argo CD AppProject and ApplicationSet for the
tenant's own repository, and admission policies for the tenant's self-service claims.
Copy this directory, render the placeholders, and follow the checklist below.

The steps after rendering name paths in the installation that runs the platform (its Argo CD
Applications, its component values, its scripts); an installation keeps them next to its
tenants' rendered landing zones. The installation's tenant-onboarding runbook carries the
operator context.

## Table of Contents

- [Template Parameters](#template-parameters)
  - [ArgoCD goTemplate Runtime Variables](#argocd-gotemplate-runtime-variables)
  - [Future Parameters](#future-parameters)
- [File Inventory](#file-inventory)
- [Onboarding Tenant N](#onboarding-tenant-n)
  - [Step 1: Render the Template](#step-1-render-the-template)
  - [Step 2: Create the Tenant Repo](#step-2-create-the-tenant-repo)
  - [Step 3: Register the Landing-Zone Application in ArgoCD](#step-3-register-the-landing-zone-application-in-argocd)
  - [Step 4: Wire Shared Platform Services](#step-4-wire-shared-platform-services)
  - [Step 5: Ingress / DNS / TLS (deferred)](#step-5-ingress--dns--tls-deferred)
- [Validation](#validation)

## Template Parameters

| Placeholder | Meaning | Example |
|-------------|---------|---------|
| `{{TENANT}}` | Tenant name token — used everywhere (resource names, labels, repo path, namespace prefix). Must equal the Keycloak realm name. | `acmecorp` |
| `{{TENANT}}-prod` | Production namespace (literal suffix; do not use an `{{ENV}}` placeholder). | `acmecorp-prod` |
| `{{TENANT}}-dev` | Development namespace (literal suffix; do not use an `{{ENV}}` placeholder). | `acmecorp-dev` |

### ArgoCD goTemplate Runtime Variables

The `applicationset.yaml.tmpl` file contains `{{.path.basename}}` and `{{.path.path}}`.
These are **ArgoCD runtime goTemplate variables** — they are evaluated by the ArgoCD
ApplicationSet controller at runtime, NOT at copy time.

**Do NOT replace them when rendering the template.** Leave them exactly as-is:

```yaml
# In applicationset.yaml.tmpl — do NOT touch these:
name: '{{TENANT}}-{{.path.basename}}'   # {{TENANT}} is replaced; {{.path.basename}} is not
path: '{{.path.path}}'                  # left as-is
namespace: '{{TENANT}}-{{.path.basename}}'  # {{TENANT}} is replaced; {{.path.basename}} is not
```

### Future Parameters

The following placeholders are reserved for future use and are NOT yet present in the
template files. They are documented here to guide the operator when the relevant
resources are added:

| Placeholder | Intended Use |
|-------------|-------------|
| `{{DOMAINS}}` | Tenant domain(s) for ingress/DNS/TLS wiring (Step 5) |
| `{{QUOTA_TIER}}` | Quota preset (small / medium / large) when quota.yaml.tmpl gains tier variants |

## File Inventory

| Template file | Rendered name | Purpose |
|---------------|---------------|---------|
| `kustomization.yaml.tmpl` | `kustomization.yaml` | Kustomize root for the landing zone |
| `namespace-prod.yaml.tmpl` | `namespace-prod.yaml` | `{{TENANT}}-prod` namespace + labels |
| `namespace-dev.yaml.tmpl` | `namespace-dev.yaml` | `{{TENANT}}-dev` namespace + labels |
| `quota.yaml.tmpl` | `quota.yaml` | ResourceQuota + LimitRange for both namespaces |
| `network-policy.yaml.tmpl` | `network-policy.yaml` | Default-deny + baseline allows (CiliumNetworkPolicy) |
| `appproject.yaml.tmpl` | `appproject.yaml` | ArgoCD AppProject with namespace boundary |
| `applicationset.yaml.tmpl` | `applicationset.yaml` | Operator-owned ApplicationSet (git generator) |
| `objectbucket-policy.yaml.tmpl` | `objectbucket-policy.yaml` | ValidatingAdmissionPolicy guard-railing tenant `ObjectBucket` self-service claims (ADR-0033 Amendment 2026-06-05) |
| `egressrule-policy.yaml.tmpl` | `egressrule-policy.yaml` | ValidatingAdmissionPolicy guard-railing tenant `EgressRule` claims: named FQDNs only, port 443, the tenant label required (ADR-0041) |

## Onboarding Tenant N

### Step 1: Render the Template

```bash
TENANT=<tenant-name>

# Copy the template directory
cp -r platform/tenants/_template platform/tenants/$TENANT

# Rename .yaml.tmpl -> .yaml
for f in platform/tenants/$TENANT/*.yaml.tmpl; do
  mv "$f" "${f%.tmpl}"
done

# Replace all {{TENANT}} placeholders (macOS-compatible)
find platform/tenants/$TENANT -name '*.yaml' \
  -exec sed -i '' "s/{{TENANT}}/$TENANT/g" {} +

# Verify no unreplaced placeholders remain (ArgoCD runtime vars are OK)
grep -rn '{{TENANT}}' platform/tenants/$TENANT && echo "UNREPLACED PLACEHOLDERS" || echo "OK"

# Remove this README from the rendered directory
rm platform/tenants/$TENANT/README.md
```

> The `{{.path.basename}}` and `{{.path.path}}` strings in `applicationset.yaml` are
> ArgoCD runtime variables — they must NOT be replaced. The grep above checks only for
> `{{TENANT}}`, so leftover ArgoCD vars will not trigger the warning.

### Step 2: Create the Tenant Repo

1. Create the tenant's repository (`manyfold-dk/$TENANT`, the repository the AppProject and
   ApplicationSet name) from the installation's tenant repository scaffold.
2. Ensure ArgoCD has read access to the repo. The git generator errors silently if the
   repo URL is unreachable or uncredentialed — add deploy-key credentials before syncing.
3. Add `gitops/prod/` to the tenant repo so the ApplicationSet generates at least one
   Application on first sync.

### Step 3: Register the Landing-Zone Application in ArgoCD

Create an ArgoCD Application in `platform/argocd/cloud/applications/`:

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
    repoURL: <the installation's repository>
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

Register it in `platform/argocd/cloud/applications/kustomization.yaml`:

```yaml
resources:
  # ... existing entries ...
  - <tenant>-landing-zone.yaml
```

Commit and push. ArgoCD will pick up the new Application and create the tenant
namespaces, quota, network policies, AppProject, and ApplicationSet.

### Step 4: Wire Shared Platform Services

Each of the following is a separate commit/PR once the landing-zone Application is
synced and healthy:

1. **Keycloak realm** — add a realm block for `$TENANT` in
   `platform/components/keycloak/values-cloud.yaml`. The realm config-cli job applies
   it on next ArgoCD sync.

2. **OpenBao mount + policy + role** — run:
   ```bash
   cd infrastructure/clusters/cloud/scripts
   ./setup-openbao-k8s-auth.sh create-tenant <tenant>
   ```
   This creates the `<tenant>/` KV v2 mount, a `<tenant>-reader` policy, and a Kubernetes
   auth role binding the tenant service accounts. The subcommand is parameterized — no
   script change per tenant.

3. **Velero backup schedule** — add a `Schedule` manifest to
   `platform/resources/cloud/velero-schedules/` targeting `$TENANT-prod` and
   `$TENANT-dev` namespaces. Register in that directory's `kustomization.yaml`.

4. **Observability** — add a `ServiceMonitor` in
   `platform/observability/overlays/cloud/servicemonitors/` and alert rules in
   `platform/observability/overlays/cloud/alerts/`. Register in each directory's
   `kustomization.yaml`.

### Step 5: Ingress / DNS / TLS (deferred)

This wiring is deferred until the tenant has a domain. When ready:

1. Add an HTTPS listener `https-<tenant>` to
   `platform/components/cilium-gateway/cloud/gateway.yaml`.
2. Add `<tenant>` domain(s) to the external-dns `domainFilters` and add a cert-issuer
   solver for the tenant DNS zone (cert-manager ClusterIssuer).
3. The tenant's `HTTPRoute` lives in the tenant repo (`gitops/prod/`) and attaches to the
   shared Gateway by `parentRefs.sectionName`.

## Validation

Validate the rendered landing zone before pushing. The installation runs the harness from
its own checkout (in this platform's installation, `scripts/ci/validate-tenant-manifests.sh`;
its CI runs it on every change under `platform/tenants/`):

```bash
./scripts/ci/validate-tenant-manifests.sh
```

The harness auto-discovers and builds every `platform/tenants/<tenant>/` directory that
has a `kustomization.yaml`, validating the output with `kubeconform` plus guardrail
assertions. Exit 0 = all checks passed. The `_template/` directory itself is never built
because it has no `kustomization.yaml` (only `*.yaml.tmpl`).
