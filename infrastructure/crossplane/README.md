# Crossplane

The platform's own Kubernetes APIs for resources outside a workload's namespace. An
application asks for an object storage bucket or an egress permission with a namespaced
resource; a Composition chosen by labels provisions it for the cluster it runs on. Argo CD
installs the APIs and the claims; Crossplane reconciles what they describe.

The local cluster's parts (a SeaweedFS Composition, its packages, ProviderConfig and example
claim) were retired on 2026-09-27 (decision 11); Git history keeps them.

## Contents

- [APIs](#apis)
- [How a claim becomes a resource](#how-a-claim-becomes-a-resource)
- [Layout](#layout)
- [Sync order](#sync-order)
- [Adding a bucket](#adding-a-bucket)
- [Cloud credentials](#cloud-credentials)
- [Troubleshooting](#troubleshooting)
- [References](#references)

## APIs

Both are Crossplane v2 namespaced composite resources (`apiextensions.crossplane.io/v2`,
`scope: Namespaced`), so a claim and its connection Secret live in the application's namespace.

| Kind | Group | Provisions |
|---|---|---|
| `ObjectBucket` | `storage.manyfold.dk/v1alpha1` | An S3-compatible bucket and a Secret with its connection details |
| `EgressRule` | `network.manyfold.dk/v1alpha1` | A CiliumNetworkPolicy that lets the namespace's workloads reach named DNS hosts on TLS |

`ObjectBucket` parameters:

| Parameter | Required | Meaning |
|---|---|---|
| `bucketName` | yes | The bucket's name at the provider |
| `provider` | yes | `cloudflare-r2` or `hetzner` |
| `region` | no | Defaults to `auto`; a Hetzner bucket reads `auto` as `hel1` |
| `jurisdiction` | no | R2 data residency boundary; defaults to `eu` |
| `retentionPrefix` | no | R2: the key prefix the lock and lifecycle rules apply to |
| `lockRetentionDays` | no | R2 Bucket Lock (write once, read many) in days; `0` for none |
| `lifecycleRetentionDays` | no | R2 lifecycle expiry in days; `0` for none |

The XRD still accepts `seaweedfs`, the retired local cluster's object store; no Composition
serves it.

The connection Secret named in `writeConnectionSecretToRef` holds `endpoint`, `bucket`,
`region`, `access-key` and `secret-key`; an R2 bucket adds `jurisdiction`.

## How a claim becomes a resource

A claim selects its Composition by labels: `environment: cloud` and, for a bucket, `provider`.

| Composition | Labels | Pipeline |
|---|---|---|
| `compositions/cloud/objectbucket-r2.yaml` | `environment: cloud`, `provider: cloudflare-r2` | an OpenTofu Workspace creates the R2 bucket, with Bucket Lock and lifecycle rules when the claim asks for them |
| `compositions/cloud/objectbucket-hetzner.yaml` | `environment: cloud`, `provider: hetzner` | an OpenTofu Workspace creates the bucket in Hetzner Object Storage |
| `compositions/cloud/egressrule-kubernetes.yaml` | `environment: cloud` | function-go-templating renders the CiliumNetworkPolicy, applied through provider-kubernetes |

The OpenTofu Workspaces keep their state in Kubernetes Secrets, in the namespace their
ProviderConfig's `kubernetes` backend names.

## Layout

| Path | Contents |
|---|---|
| `xrds/` | The two APIs |
| `compositions/cloud/` | The Compositions above |
| `providers-cloud/` | The Crossplane packages the cluster installs: provider-opentofu, function-patch-and-transform, provider-kubernetes, function-go-templating and a restricted DeploymentRuntimeConfig for package pods |

A cloud installation adds its own ProviderConfigs (one per namespace that claims buckets, since
OpenTofu ProviderConfigs are namespaced) and its claims.

## Sync order

Argo CD sync waves order the pieces so that every claim meets an installed API and a ready
Composition:

| Step | Wave |
|---|---|
| Crossplane (Helm chart) | -3 |
| Packages, XRDs, Compositions | -2 |
| ProviderConfigs | -1 |
| Claims | 2 |

## Adding a bucket

1. Write an `ObjectBucket` in the application's namespace, with `environment: cloud` and
   `provider` in `compositionSelector.matchLabels`.
2. Make sure the namespace has an OpenTofu ProviderConfig of the name the Composition uses.
3. Mount the connection Secret in the application's Deployment.

For an R2 backup bucket that must resist deletion, set `lockRetentionDays`,
`lifecycleRetentionDays` and `retentionPrefix` (for example 30, 31 and `history/`). Keep the
lifecycle retention longer than the lock, so that expiry never tries to delete a locked object.

## Cloud credentials

The cloud ProviderConfigs read their API tokens from Secrets in `crossplane-system`:
`crossplane-cloudflare-credentials` for R2 and `crossplane-hetzner-credentials` for Hetzner.
The installation supplies them; they are never in this repository in plain text.

## Troubleshooting

```bash
kubectl get providers,functions                  # packages installed and healthy?
kubectl get xrd                                  # APIs established?
kubectl get objectbuckets,egressrules -A -o wide # claims synced and ready?
kubectl describe objectbucket <name> -n <namespace>
kubectl get workspaces.opentofu.m.upbound.io -A  # the OpenTofu runs behind the buckets
kubectl logs -n crossplane-system -l pkg.crossplane.io/revision
```

- **No Composition selected:** the claim's `compositionSelector.matchLabels` must match a
  Composition's `metadata.labels` exactly.
- **No connection Secret:** a v2 composite needs `writeConnectionSecretToRef` in its XRD
  schema and in the Composition's function input; function-patch-and-transform then composes
  the Secret.
- **EgressRule not Ready:** the Composition marks the claim Ready only when the composed Object
  carries the manifest rendered from the claim's current spec and provider-kubernetes reports it
  `Ready` and `Synced` for its current generation, that is, when the CiliumNetworkPolicy is
  applied as rendered. The Object's conditions carry the apply error:
  `kubectl describe objects.kubernetes.m.crossplane.io <name>-egress -n <namespace>`.
- **EgressRule not Synced, "needs a non-empty manyfold.dk/tenant label":** the Composition names the
  CiliumNetworkPolicy `<tenant>-egress-<name>` after the claim's `manyfold.dk/tenant` label and
  refuses to render without it. Add the label with the owning tenant's name.

## References

- [Crossplane documentation](https://docs.crossplane.io/latest/)
- [Composition functions: patch and transform](https://docs.crossplane.io/latest/guides/function-patch-and-transform/)
- [Connection details in compositions](https://docs.crossplane.io/latest/guides/connection-details-composition/)
- [provider-opentofu](https://marketplace.upbound.io/providers/upbound/provider-opentofu/)
