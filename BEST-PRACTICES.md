# Best Practices

Lessons learned and best practices for working with this platform.

## Table of Contents

- [Security / Supply Chain](#security--supply-chain)
  - [Helm Install Script Requires openssl](#helm-install-script-requires-openssl)
- [Kubernetes / ArgoCD](#kubernetes--argocd)
  - [Alloy Log Collection: Use File-Based, Not Apiserver Proxy](#alloy-log-collection-use-file-based-not-apiserver-proxy)
  - [Grafana Admin Password Drift](#grafana-admin-password-drift-kube-prometheus-stack)
  - [Keycloak Config-CLI Drift for Logout Clients](#keycloak-config-cli-drift-for-logout-clients)
  - [oauth2-proxy: cookie-secret Must Decode to 16/24/32 Bytes](#oauth2-proxy-cookie-secret-must-decode-to-162432-bytes)
  - [oauth2-proxy: "Sign out" Needs --backend-logout-url to End Keycloak SSO](#oauth2-proxy-sign-out-needs---backend-logout-url-to-end-keycloak-sso)
  - [Talos Linux NTP and NodeClockNotSynchronising](#talos-linux-ntp-and-nodeclocknotsynchronising)
  - [Config Syncs in Seconds, Images Go Through CI -- Ship Consumers First](#config-syncs-in-seconds-images-go-through-ci----ship-consumers-first)
  - [PodSecurity `restricted`: runAsNonRoot Needs a *Numeric* UID](#podsecurity-restricted-runasnonroot-needs-a-numeric-uid)
  - [`Replace=true` Wedges Any Chart Whose Hooks Are Not Jobs](#replacetrue-wedges-any-chart-whose-hooks-are-not-jobs)
- [external-dns](#external-dns)
  - [`external-dns.alpha.kubernetes.io/exclude` Is Ignored by the Gateway Sources](#external-dnsalphakubernetesioexclude-is-ignored-by-the-gateway-sources)
  - [Removing One Record Candidate Promotes the Other](#removing-one-record-candidate-promotes-the-other)
  - [`--gateway-label-filter` Rejects `!=`, and kubectl Will Not Warn You](#--gateway-label-filter-rejects--and-kubectl-will-not-warn-you)
  - [A Failed Record Leaves a Live Ownership TXT Behind](#a-failed-record-leaves-a-live-ownership-txt-behind)
- [Shell / Verification](#shell--verification)
  - [zsh Does Not Word-Split Unquoted Variables -- Verification Loops Lie](#zsh-does-not-word-split-unquoted-variables----verification-loops-lie)
- [GitHub Packages (GHCR)](#github-packages-ghcr)
  - ["Actions and Packages Storage" Is One Combined Bucket](#actions-and-packages-storage-is-one-combined-bucket)
  - [$0 Spending Limit Hard-Blocks at 100% Included](#0-spending-limit-hard-blocks-at-100-included)
  - [The Storage Meter Is Cumulative and Resets at Period End](#the-storage-meter-is-cumulative-and-resets-at-period-end)
  - [Cleanup Tokens Need Org-Wide Visibility + delete:packages](#cleanup-tokens-need-org-wide-visibility--deletepackages)
  - [snok Retention: "Selected N" = Candidates After cut-off](#snok-retention-selected-n--candidates-after-cut-off)
- [Renovate](#renovate)
  - [`platform-unknown-error` Means a Missing Fine-Grained PAT Permission](#platform-unknown-error-means-a-missing-fine-grained-pat-permission)
- [Helm Charts](#helm-charts)
- [ArgoCD](#argocd)
- [API Design](#api-design)
- [Database Schema and Migrations](#database-schema-and-migrations)
- [Vendor Integrations](#vendor-integrations)
  - [Never Trust a Vendor Write Response -- Read It Back](#never-trust-a-vendor-write-response----read-it-back)
  - [Twilio Regional Accounts: a 401 Usually Means Wrong Host, Not Wrong Secret](#twilio-regional-accounts-a-401-usually-means-wrong-host-not-wrong-secret)
  - [Cloudflare Workers: `secrets.required` Silently Filters `.dev.vars`](#cloudflare-workers-secretsrequired-silently-filters-devvars)
  - [Cloudflare Workers: the First Deploy of a Secret-Bearing Worker Is a Deadlock](#cloudflare-workers-the-first-deploy-of-a-secret-bearing-worker-is-a-deadlock)
  - [Cloudflare Workers: A Deploy Is Not Live Everywhere the Second It Returns](#cloudflare-workers-a-deploy-is-not-live-everywhere-the-second-it-returns)
  - [Cloudflare API Tokens: Account-Scoped Workers Permissions Do Not Attach Custom Domains](#cloudflare-api-tokens-account-scoped-workers-permissions-do-not-attach-custom-domains)
- [Known Platform Limitations](#known-platform-limitations)
  - [Hetzner CSI Driver Does Not Support VolumeSnapshots](#hetzner-csi-driver-does-not-support-volumesnapshots)
  - [Talos Snapshot Boot Failure with hcloud-upload-image](#talos-snapshot-boot-failure-with-hcloud-upload-image-v1121)
  - [Talos Upgrade TLS Handshake Timeout](#talos-upgrade-tls-handshake-timeout)
  - [Hetzner Server Limits for Snapshot Building](#hetzner-server-limits-for-snapshot-building)

## Security / Supply Chain

### Pin Container Images by Digest

**Problem:** Mutable tags like `latest` or even versioned tags like `alpine:3.21` can be overwritten, creating supply-chain attack vectors. This is especially critical for privileged workloads.

**Solution:** Pin images by SHA256 digest:
```yaml
# Good - immutable reference
image: alpine:3.21@sha256:5405e8f36ce1878720f71217d664aa3dea32e5e5df11acbf07fc78ef5661465b

# Bad - mutable tag (can be overwritten)
image: alpine:latest
image: alpine:3.21
```

**Getting digests:**
```bash
# Multi-arch manifest digest (preferred for portable configs)
docker pull alpine:3.21 2>&1 | grep Digest

# Architecture-specific digest
docker manifest inspect alpine:3.21 | jq -r '.manifests[] | "\(.platform.architecture): \(.digest)"'
```

### Multi-Arch Considerations for Init Containers

**Problem:** Pinning to an architecture-specific digest (e.g., arm64) breaks portability. The same image may run on Intel x86_64 or Apple Silicon/ARM arm64 nodes, for example a throwaway test instance on a different server type.

**Solution:** Use multi-arch manifest digests and detect architecture at runtime:
```yaml
# Multi-arch Alpine manifest (works on both arm64 and amd64)
image: alpine:3.21@sha256:5405e8f36ce1878720f71217d664aa3dea32e5e5df11acbf07fc78ef5661465b
args:
  - |
    ARCH=$(uname -m)
    case "$ARCH" in
      x86_64|amd64) BINARY_ARCH="x86_64" ;;
      aarch64|arm64) BINARY_ARCH="arm64" ;;
      *) echo "Unsupported: $ARCH" && exit 1 ;;
    esac
    # Download architecture-specific binary...
```

For environment-specific patches (e.g., cloud-only), architecture-specific digests are acceptable since the target architecture is known.

### Verify Downloaded Binaries with Checksums

**Problem:** Downloading binaries without verification allows MITM attacks or compromised releases.

**Solution:** Always verify SHA256 checksums:
```bash
# Download
wget -q -O /tmp/tool.tar.gz "${DOWNLOAD_URL}"

# Verify (fails if mismatch)
echo "${EXPECTED_SHA256}  /tmp/tool.tar.gz" | sha256sum -c -

# Extract only after verification
tar -xzf /tmp/tool.tar.gz -C /target
```

**Finding checksums:** Most projects publish `checksums.txt` or `SHA256SUMS` files alongside releases:
```bash
curl -sL "https://github.com/org/repo/releases/download/${TAG}/checksums.txt"
```

### Helm Install Script Requires openssl

**Problem:** The official Helm install script (`get-helm-3`) verifies downloaded binaries using `openssl`. On minimal images like Alpine, `openssl` is not installed by default. This causes the init container to fail with:
```
In order to verify checksum, openssl must first be installed.
```

**Solution:** Include `openssl` when installing dependencies before running the Helm install script:
```bash
apk add --no-cache curl bash git jq ncurses openssl
curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

## Kubernetes / ArgoCD

### Alloy Log Collection: Use File-Based, Not Apiserver Proxy

**Problem:** Alloy's `loki.source.kubernetes` component streams pod logs by opening persistent HTTP CONNECT requests through the Kubernetes apiserver (`pods/log` subresource). Each pod gets its own long-lived connection. Across a cluster's pods this adds up to hundreds of active CONNECT streams, unevenly distributed across apiservers -- one apiserver ends up with most connections, its memory grows by gigabytes and node memory pressure alerts fire.

**Solution:** Use `loki.source.file` with `local.file_match` instead. This reads log files directly from `/var/log/pods/` on each node's filesystem. Combined with a `discovery.relabel` rule that filters to local-node pods only (`keep` on `__meta_kubernetes_pod_node_name` matching `env("HOSTNAME")`), each Alloy instance only reads logs for pods on its own node.

**Key config elements:**
- `alloy.mounts.varlog: true` -- mounts `/var/log` from the host
- `discovery.relabel` with `keep` rule to filter to local node
- `__path__` label constructed from pod namespace/name/uid/container metadata
- `local.file_match` + `loki.source.file` for tailing

**Why this matters:** File-based collection is the standard pattern used by all production log shippers (Promtail, Vector, Fluent Bit). The apiserver-proxy method (`loki.source.kubernetes`) should only be used for quick dev setups where host filesystem access isn't available.

See: [ADR-0013: Observability Stack](docs/adr/0013-observability-stack.md)

### CPU Resource Values

**Problem:** Kubernetes normalizes CPU values (e.g., `1000m` → `"1"`, `2000m` → `"2"`), which causes ArgoCD to report OutOfSync when comparing with git values.

**Solution:** Use normalized CPU values in manifests:
```yaml
# Good - matches Kubernetes normalization
resources:
  limits:
    cpu: "1"      # Not 1000m
    cpu: "2"      # Not 2000m

# Bad - causes ArgoCD drift
resources:
  limits:
    cpu: 1000m
    cpu: 2000m
```

Memory values (`Mi`, `Gi`) don't have this issue.

### Controller-Managed Field Drift

ArgoCD may show `OutOfSync` for resources with controller-managed fields (common with CRDs, Tekton resources, etc.) even after successful sync. Common causes:

1. **CRD `preserveUnknownFields`** - Deprecated field stripped by K8s API but present in upstream manifests
2. **Controller modifications** - Controllers like `tekton-pipelines-controller` modify resources post-apply
3. **Normalized values** - API server normalizes certain fields differently than manifests

**Solution:** This platform uses global `ignoreDifferences` configuration in `platform/argocd/base/argocd-cm.yaml`:

```yaml
# Ignore preserveUnknownFields on CRDs (stripped by API)
resource.customizations.ignoreDifferences.apiextensions.k8s.io_CustomResourceDefinition: |
  jsonPointers:
  - /spec/preserveUnknownFields

# Ignore controller-managed fields on all resources
resource.customizations.ignoreDifferences.all: |
  managedFieldsManagers:
  - kube-controller-manager
  - tekton-pipelines-controller
  - tekton-triggers-controller
```

**Per-application alternative** (when global config isn't suitable):
```yaml
spec:
  ignoreDifferences:
    - group: tekton.dev
      kind: '*'
      managedFieldsManagers:
        - tekton-pipelines-controller
  syncPolicy:
    syncOptions:
      - RespectIgnoreDifferences=true
```

### StatefulSet VolumeClaimTemplates Drift

Kubernetes API adds fields to StatefulSet `volumeClaimTemplates` that don't exist in Helm charts:
- `apiVersion`, `kind` (added by API)
- `metadata.creationTimestamp: null` (added by API)
- `status` (added by API)

**Solution:** Use explicit jsonPointers in the Application:
```yaml
spec:
  ignoreDifferences:
    - group: apps
      kind: StatefulSet
      jsonPointers:
        - /spec/persistentVolumeClaimRetentionPolicy
        - /spec/volumeClaimTemplates/0/apiVersion
        - /spec/volumeClaimTemplates/0/kind
        - /spec/volumeClaimTemplates/0/status
        - /spec/volumeClaimTemplates/0/metadata/creationTimestamp
  syncPolicy:
    syncOptions:
      - RespectIgnoreDifferences=true
```

### Config Syncs in Seconds, Images Go Through CI -- Ship Consumers First

**Problem:** ArgoCD applies a changed values file within seconds of the push, while an application change in the same commit range has to clear CI first (and can sit queued for many minutes when hosted runners are busy). Push both together and you get a window where new *configuration* is live against *old code*.

A typical case: an Alertmanager route sending the `Watchdog` heartbeat to a backend webhook goes live immediately, while the consumer changes that special-case Watchdog are still queued. Until they roll out, the old consumers treat the heartbeat as an ordinary alert -- posting it into the alert channel and inflating the alert store behind the backend's API.

**Rule:** when a config change only makes sense to code that does not exist yet, land the **consumers before the producer**:

1. Merge the application change; wait for CI and confirm the new image is actually rolled out (`kubectl get deploy <app> -o jsonpath='{.spec.template.spec.containers[0].image}'`).
2. Only then enable the config that routes traffic to it.

Commenting the config block out with a `DISABLED PENDING ROLLOUT` note is a perfectly good intermediate state -- it keeps the intent and the reasoning in the file instead of in someone's memory. This applies to any producer/consumer split across the GitOps and CI paths: new alert routes, new stream/queue topics, new webhook targets, new feature-flag payload shapes.

### PodSecurity `restricted`: runAsNonRoot Needs a *Numeric* UID

`runAsNonRoot: true` makes the kubelet verify the user is not root, and it can only do
that with a numeric UID. An image whose Dockerfile ends `USER quarkus` fails at container
creation:

```
container has runAsNonRoot and image has non-numeric user (quarkus),
cannot verify user is non-root
```

**A server-side dry-run does not catch this** -- it is a runtime check, so
`kubectl apply --dry-run=server` reports the manifest as clean and the pod then fails to
start. Any image with a named `USER` needs an explicit `runAsUser: <uid>` alongside
`runAsNonRoot`.

Before hardening a workload, check what it actually runs as rather than trusting the
Dockerfile:

```bash
kubectl exec <pod> -n <ns> -- id
```

Two categories that look alike but are not:

- **Declaration gap** -- image already runs non-root (`uid=1000`), the manifest just
  never said so. Adding `securityContext` changes nothing at runtime. Safe.
- **Actual migration** -- the container really runs as `uid=0` (common for stateful
  workloads such as `postgres` with a PVC). Hardening means changing the runtime user
  *and* chowning the data directory. Not a batchable change.

The cluster's warning source is Talos's apiserver admission default
(`enforce=baseline`, `warn/audit=restricted`), not a namespace label -- so violations are
advisory, and because it is not repo-managed it never shows up in a GitOps diff.

### `Replace=true` Wedges Any Chart Whose Hooks Are Not Jobs

An Application deploying an Envoy Gateway sat with **one** deployment record for four
months. It reported `Synced` and `Healthy` the entire time, so nothing looked wrong --
but no sync ever completed, and every change under
its resource directory silently failed to reach the cluster. The
symptom that exposed it was a Gateway label edit that simply never appeared.

```
opPhase=Running   opStarted=<months earlier>   opFinished=
msg=waiting for completion of hook
    /ServiceAccount/<release>-gateway-helm-certgen and 1 more hooks
```

The gateway-helm chart ships its certgen RBAC as helm hooks, and **none of them is a
Job** -- ServiceAccount, Role, RoleBinding, ClusterRole, ClusterRoleBinding and a
MutatingWebhookConfiguration. `Replace=true` drives create/replace semantics that
collide with the hook lifecycle: the RBAC hooks failed `already exists` while the
ServiceAccount and webhook hooks stayed in `Running` forever, because those kinds have
no completion semantics to satisfy. The operation never reached a terminal phase, and a
non-terminal operation blocks every later sync.

**`Synced` + `Healthy` is not evidence that syncing works.** Both describe desired-vs-live
state, not whether the sync machinery is functioning. Check `.status.history` -- a single
entry with an old `deployedAt`, or a `.status.operationState.phase` that has been
`Running` for hours, is the real signal:

```bash
kubectl -n argocd get application <app> -o jsonpath='{.status.operationState.phase} {.status.operationState.startedAt}'
kubectl -n argocd get application <app> -o json | jq '.status.history[] | {id, deployedAt}'
```

Only reach for `Replace=true` when something genuinely needs it (`snapshot-crds` does --
oversized CRDs exceed the 262144-byte annotation limit, and it has no helm hooks). It is
not a general-purpose fix, and setting it alongside `ServerSideApply=true` silently wins:
ArgoCD gives Replace precedence, so the SSA option does nothing.

**Unwedging a stranded operation.** Deleting `.operation` is not enough -- the controller
leaves `.status.operationState` non-terminal, and a new sync request just queues behind
it. Force it terminal first, which is what `argocd app terminate-op` does:

```bash
kubectl -n argocd patch application <app> --type=merge \
  -p '{"status":{"operationState":{"phase":"Terminating"}}}'
```

It settles to `Failed` within seconds, after which normal syncs resume.

### Grafana Admin Password Drift (kube-prometheus-stack)

**Problem:** When the values set neither `grafana.adminPassword` nor `grafana.admin.existingSecret`, the Grafana subchart of kube-prometheus-stack renders its own admin Secret with a random password (`randAlphaNum`). The chart tries a `lookup` of the live Secret first, but ArgoCD renders charts with `helm template`, where `lookup` returns nothing. Every render therefore produces a new password, and the Secret stays OutOfSync.

**Solution:** Point `grafana.admin.existingSecret` at a Secret that is managed outside the chart, for example a SOPS-encrypted Secret that the platform's secrets application deploys:

```yaml
grafana:
  admin:
    existingSecret: <grafana-admin-secret>  # holds the two keys below
    userKey: admin-user
    passwordKey: admin-password
```

With `existingSecret` set, the chart renders no admin Secret at all, so nothing drifts, and the password never appears in a values file. The platform's cloud values use this pattern. Deploy the Secret in an earlier sync wave than the chart; until it exists, the Grafana pod stays in `CreateContainerConfigError`.

A fixed `grafana.adminPassword` also stops the drift, but it puts the password in Git as plain text. Use it only for a throwaway cluster that holds nothing of value.

### Keycloak Config-CLI Drift for Logout Clients

**Problem:** `keycloak-config-cli` does not reliably reconcile all fields on existing service clients. In practice, the `website-backend-logout` client drifted in two ways:
- the generated service-account role mapping was not applied reliably when only `serviceAccountClientId` was present
- the live Keycloak client secret diverged from the SOPS-managed Kubernetes secrets, causing `invalid_client_credentials` during logout

**Solution:** For service clients used by backend automation:

```yaml
clients:
  - clientId: website-backend-logout
    serviceAccountsEnabled: true
    fullScopeAllowed: true

users:
  - serviceAccountClientId: website-backend-logout
    username: service-account-website-backend-logout
    clientRoles:
      realm-management:
        - query-users
        - view-users
        - manage-users
```

**Verification:**
- Compare the secret hash in Kubernetes with the live Keycloak client secret from the admin API.
- Mint a fresh client-credentials token and confirm it includes `resource_access.realm-management.roles`.
- If logout still fails with `401` or `403`, check `website-backend` logs and Keycloak event logs together.
- If the public auth host has been hardened to expose only OIDC paths, backend automation that uses `/admin/...` must call Keycloak through the in-cluster service URL, not the public issuer URL.
- When `oauth2-proxy` runs inside the cluster, keep the browser-facing issuer/login/logout URLs on the public auth host, but point token redemption and JWKS/profile fetches at the in-cluster Keycloak service so login does not depend on external DNS or IPv6 egress.
- If you want browser sign-out to end the Keycloak SSO session as well as the local oauth2-proxy session, redirect users to `/oauth2/sign_out?rd=<urlencoded end_session_endpoint>` and include `{id_token}` plus a valid `post_logout_redirect_uri` in the encoded IdP logout URL.

**Operational note:** Re-running config-cli may not rotate an already-drifted client secret. If the live client is wrong, repair it once through the Keycloak admin API and then keep Git as the source of truth.

**Related (keycloak-config-cli 6.4 / KC 26.1):** `${VAR}` substitution in client `secret:` fields does NOT engage even with `IMPORT_VARSUBSTITUTION_ENABLED=true`, the alternative `IMPORT_VAR_SUBSTITUTION_ENABLED=true`, or `JAVA_TOOL_OPTIONS=-Dimport.var-substitution.enabled=true` (the JVM logs "Picked up JAVA_TOOL_OPTIONS" -- the property is reaching it; it just doesn't change behaviour). Confirmed on an existing client (it held a literal `${<CLIENT>_CLIENT_SECRET}` as its secret, surviving every config-cli re-run) and on a freshly-created client in a second realm (created with the literal placeholder as its initial secret). Same workaround applies: set the live secret once via `kcadm.sh update clients/<id> -r <realm> -s 'secret=<sops-value>'`; config-cli does NOT revert it on subsequent runs.

### oauth2-proxy: cookie-secret Must Decode to 16/24/32 Bytes

**Problem:** `openssl rand -base64 32` is a tempting one-liner for the oauth2-proxy `cookie-secret`, but it produces a 44-character / 44-byte value. oauth2-proxy interprets `cookie-secret` as the raw AES key, requires exactly 16/24/32 bytes for AES-128/192/256, and the pod crash-loops at startup with:

```
invalid configuration:
  cookie_secret must be 16, 24, or 32 bytes to create an AES cipher, but is 44 bytes
```

**Solution:** use a length that decodes to a valid AES key size:

```bash
openssl rand -base64 24    # 32 chars, 24 raw bytes  → AES-192
openssl rand -hex 32       # 64 chars, 32 raw bytes  → AES-256
openssl rand -hex 16       # 32 chars, 16 raw bytes  → AES-128
```

A working instance's cookie secret was generated with `openssl rand -base64 24` and authenticates cleanly. When extracting from a working instance, treat the **byte length** of the stored value as the validation check, not its visual appearance.

### oauth2-proxy: "Sign out" Needs --backend-logout-url to End Keycloak SSO

**Problem:** With just `/oauth2/sign_out` the oauth2-proxy session cookie is cleared, but Keycloak's SSO session is untouched. The redirect-back to a protected path re-authenticates silently -- from the user's perspective "sign out" appears to do nothing.

**Solution:** Configure OIDC RP-initiated logout via the proxy's `--backend-logout-url`, templated with `{id_token}`:

```yaml
# values-<app>.yaml (any oauth2-proxy you front a UI with)
extraArgs:
  backend-logout-url: https://auth.<domain>/realms/<realm>/protocol/openid-connect/logout?id_token_hint={id_token}
```

After clearing its own cookie, oauth2-proxy makes a server-to-server call to that URL substituting the session's stored `id_token`. Keycloak ends the SSO session; the redirect back to `/ui/` then bounces through `/protocol/openid-connect/auth` and shows the real Keycloak login form -- confirming the logout actually happened. If "sign out" silently re-authenticates, this is the first knob to check; the second is whether the OIDC client has the matching `postLogoutRedirectUris` (when omitting `{id_token}`).

### Talos Linux NTP and NodeClockNotSynchronising

**Problem:** The `NodeClockNotSynchronising` Prometheus alert fires when the node clock is not synchronized. On Talos Linux, NTP is built-in but defaults to `time.cloudflare.com`. Transient network issues can cause temporary clock drift.

**Solution:** If the alert recurs frequently, add explicit NTP configuration in the Talos machine config:
```yaml
machine:
  time:
    servers:
      - time.cloudflare.com
      - ntp.hetzner.com
    bootTimeout: 2m0s
```

Using a geographically close NTP server (e.g., `ntp.hetzner.com` for Hetzner Cloud) reduces latency and improves sync reliability.

## external-dns

### `external-dns.alpha.kubernetes.io/exclude` Is Ignored by the Gateway Sources

The annotation does nothing on an HTTPRoute under the `gateway-httproute` source
(verified against external-dns v0.21). It is accepted silently -- no warning, no log
line -- and the record is published regardless.

It is easy to believe otherwise when a directory carries the annotation on several
HTTPRoutes and those names genuinely are not published. In the case that taught this, they
were excluded by an `excludeDomains: [internal.<domain>]` entry in the external-dns
values, which covered every one of them. The annotation there had never done anything.

**A pattern that appears to work is not evidence it works.** Confirm the mechanism that
is actually responsible before copying it.

### Removing One Record Candidate Promotes the Other

A Gateway can advertise both address types at once:

```
status.addresses:
  - {type: IPAddress, value: <tailnet-ip>}
  - {type: Hostname,  value: <gateway>.<tailnet-domain>}
```

external-dns derives an A candidate from the first and a CNAME candidate from the
second, then logs `contains conflicting record type candidates; discarding CNAME
record` and publishes the A.

Suppressing the A record does **not** stop publication -- it hands the win to the CNAME,
which is then created. Target-based filters (`--exclude-target-net`) cannot help, because
the surviving target is a hostname, not an IP. Filtering has to happen at the Gateway
(`--gateway-namespace` or `--gateway-label-filter`), which removes every candidate the
Gateway produces.

### `--gateway-label-filter` Rejects `!=`, and kubectl Will Not Warn You

external-dns parses that flag with `metav1.ParseToLabelSelector`, which handles `=`,
`==`, `in`, `notin`, `key` and `!key` -- but has no case for `!=` and exits immediately:

```
level=fatal msg="\"!=\" is not a valid label selector operator"
```

`kubectl get gateway -A -l 'key!=value'` accepts it happily, so pre-validating the
selector with kubectl proves the match set but **not** that external-dns can start. Use
`notin (value)`, which has the semantics usually wanted anyway: it matches objects that
lack the key entirely.

Worth checking, because with `policy: sync` a selector matching no Gateway would delete
every owned record in the zone. The crash is at least fail-safe -- external-dns exits
before constructing the provider, so nothing is created or deleted while it is down.

### A Failed Record Leaves a Live Ownership TXT Behind

When record creation fails but the registry write succeeds, external-dns is left owning
a record that does not exist, and retries forever. Here Cloudflare rejected a proxied A
record targeting CGNAT space (`code 9003, Target ... is not allowed for a proxied
record`) while `_externaldns.a-<host>.<domain>` was written successfully.

Once the source goes out of scope, external-dns stops seeing the name at all and will
**not** clean that TXT up -- it deletes records it can still see, so an orphan whose
paired record never existed survives and needs deleting by hand.

## Shell / Verification

### zsh Does Not Word-Split Unquoted Variables -- Verification Loops Lie

The default shell here is **zsh**, where unquoted parameter expansion does *not* undergo
word splitting (unlike bash). This silently breaks the common verification-loop idiom:

```bash
# BROKEN in zsh: $1 becomes the whole string "my-api platform-ops"
for p in "my-api platform-ops" "slack-bot platform-ops"; do
  set -- $p
  kubectl get deploy "$1" -n "$2" ...   # queries a deployment that does not exist
done

# BROKEN in zsh for the same reason
apps="a:ns1 b:ns2"
for pair in $apps; do ... done          # iterates ONCE, with the whole string
```

The failure mode is the dangerous one: `kubectl` is handed a nonsense name, returns
**empty output rather than an error**, and the loop reports the field as missing. That
reads as "the change did not deploy" when it deployed fine.

Safe forms:

```bash
for p in "my-api platform-ops" "slack-bot platform-ops"; do
  set -- ${=p}          # zsh: explicit word-splitting flag
done

for pair in ${(z)apps}; do ... done     # zsh: split into words

check() { kubectl get deploy "$1" -n "$2" ...; }   # portable: pass real arguments
check my-api platform-ops
```

General rule: **when a verification step reports "missing", confirm the command itself is
well-formed before concluding the deploy failed.** An empty result and a broken query are
indistinguishable in the output.

## GitHub Packages (GHCR)

### "Actions and Packages Storage" Is One Combined Bucket

The Free-plan included storage (500 MB) is **shared** between Actions artifacts and GHCR package storage -- the billing UI shows a single "Actions **and** Packages storage" bar. Packages are often the smaller share; Actions artifacts can dominate. Deleting images barely moves the bar if artifacts are the real consumer -- always check both:

```bash
# Storage split this period (GigabyteHours): Actions vs Packages
curl -s -H "Authorization: token $TOK" \
  "https://api.github.com/organizations/$ORG/settings/billing/usage?year=YYYY&month=M" \
  | jq -r '.usageItems|map(select(.sku|test("storage";"i")))|group_by(.sku)|.[]|"\(.[0].sku): \(map(.quantity)|add)"'
# Current Actions artifacts (bytes)
gh api "repos/$ORG/$REPO/actions/artifacts" --jq '[.artifacts[]|select(.expired==false).size_in_bytes]|add'
```

### $0 Spending Limit Hard-Blocks at 100% Included

On the Free plan the default spending limit is **$0**, so the moment usage exceeds the included tier (even by $0.001) GitHub blocks **all** package push/pull -- `ImagePullBackOff`, failed CI image pushes. Raising the limit (e.g. to $10) lets the tiny overage be billed instead of blocked. Overage pricing is cents, so a small buffer is effectively unlimited headroom.

### The Storage Meter Is Cumulative and Resets at Period End

Usage is measured in gigabyte-**hours** accrued over the billing period. Deleting images does **not** retroactively lower the current bar -- the saving shows up in the *next* period after the reset. Don't expect the needle to drop immediately after a cleanup.

### Cleanup Tokens Need Org-Wide Visibility + delete:packages

The repo-scoped `GITHUB_TOKEN` / `GH_TOKEN` only sees packages **linked** to the repo (the package's `repository` field is set). Unlinked org packages (often most of an organisation's images) are invisible to it, so a retention job using it **silently skips them** (reports `0` versions). Use a classic PAT with org-wide `read:packages` + `delete:packages` (stored as the `GHCR_CLEANUP_TOKEN` secret). The `snok/container-retention-policy` action validates `delete:packages` even for `dry-run`. See [GHCR package linking](docs/runbooks/applications/ghcr-package-linking.md) and the repository's GHCR cleanup workflow.

### snok Retention: "Selected N" = Candidates After cut-off

In `snok/container-retention-policy` logs, "Selected N package versions" is the count **after** the `cut-off` filter -- not the total fetched. `0` usually just means everything is newer than the cut-off (common for fast-churning packages), not a token/visibility problem.

## Renovate

### `platform-unknown-error` Means a Missing Fine-Grained PAT Permission

**Problem:** Renovate fails every repo with a completely opaque error and no HTTP detail at the default log level:

```
Error: platform-unknown-error
    at Proxy.initRepo (lib/modules/platform/github/index.ts:523)
```

This is not a token expiry or a network fault. It is how Renovate reports *any* unexpected failure during `initRepo`, and with a fine-grained PAT the cause is almost always a missing permission. Diagnosing it from the outside is misleading: `GET /user`, `GET /repos/{owner}/{repo}`, and a plain GraphQL repo query all return `200`, so the token looks perfectly healthy.

**Diagnosis:** re-run the job with `LOG_LEVEL=debug` and `RENOVATE_DRY_RUN=full` (dry-run raises no PRs). The real cause appears one frame below the error:

```
DEBUG: Unexpected Graph QL errors
  "errors": [{"type": "FORBIDDEN", "path": ["repository", "issues"],
              "message": "Resource not accessible by personal access token"}]
```

A `FORBIDDEN` on any single field nulls the **entire** `repository` object in the GraphQL response, so one missing permission fails the whole repo. Probe permissions individually against REST -- each maps to one fine-grained scope:

```bash
for ep in issues pulls contents/README.md actions/workflows commits; do
  curl -s -o /dev/null -w "$ep %{http_code}\n" \
    -H "Authorization: Bearer $TOK" "https://api.github.com/repos/$REPO/$ep"
done
```

**Required fine-grained PAT permissions** (Renovate 39):

| Scope | Level | Why |
|-------|-------|-----|
| Metadata | Read | Mandatory baseline |
| Contents | Read & write | Read manifests, push branches |
| Pull requests | Read & write | Raise update PRs |
| **Issues** | Read & write | Dependency Dashboard -- queried in `initRepo`, so its absence blocks *everything* |
| **Workflows** | Read & write | Required to update `.github/workflows/**` (we pin action digests) |
| Account → Email addresses | Read | Only if `gitAuthor` is unset -- see below |

**Avoid the email permission entirely:** with no `gitAuthor` configured, Renovate derives the commit identity at startup via `GET /user/emails`, which fine-grained PATs cannot call without the account-level "Email addresses" permission. Setting `RENOVATE_GIT_AUTHOR` skips that lookup, keeping the token's scope narrower. Set it in the Renovate CronJob's environment.

**Watch out:** editing an existing fine-grained PAT's permissions does **not** change the token value, so no secret rotation and no ArgoCD impact, even where the Renovate PAT is shared with ArgoCD's org-wide repo-creds. Also note the failure is near-silent by design: a failing CronJob only surfaces as a generic `KubeJobFailed` warning, so Renovate can be dead for weeks while alerts look routine. Confirm real liveness by the date of the most recent Renovate PR, not by the absence of alerts.

## Helm Charts

### Check Value Paths

**Problem:** Helm values must be placed under the correct parent key. Wrong nesting results in values being silently ignored.

**Example - Grafana Alloy:**
```yaml
# Correct - tolerations under controller
controller:
  type: daemonset
  tolerations:
    - key: "node-role.kubernetes.io/control-plane"
      operator: "Exists"
      effect: "NoSchedule"

# Wrong - tolerations at root level (ignored)
controller:
  type: daemonset
tolerations:  # Silently ignored!
  - key: "node-role.kubernetes.io/control-plane"
```

**How to verify:** Check the chart's `values.yaml` for the correct structure:
- GitHub: `https://github.com/<org>/<chart>/blob/main/charts/<name>/values.yaml`
- Helm: `helm show values <repo>/<chart>`

## ArgoCD

### Force Refresh for Stuck Syncs

When ArgoCD shows stale data or won't pick up new commits:

```bash
# Hard refresh (re-fetch from git)
kubectl patch application <app-name> -n argocd --type merge \
  -p '{"metadata":{"annotations":{"argocd.argoproj.io/refresh":"hard"}}}'

# If still stuck, restart repo-server to clear cache
kubectl rollout restart deployment argocd-repo-server -n argocd
```

### Multi-Source Applications

When using `sources` (multiple sources), revisions are tracked per-source:
```yaml
spec:
  sources:
    - chart: alloy
      repoURL: https://grafana.github.io/helm-charts
      targetRevision: <chart-version>  # Chart version
    - ref: values
      repoURL: https://github.com/org/repo.git
      targetRevision: HEAD    # Git revision
```

Check both revisions when debugging sync issues:
```bash
kubectl get application <app> -n argocd \
  -o jsonpath='{.status.operationState.syncResult.revisions}'
```

## API Design

### Validate String Field Sizes

**Problem:** Unbounded string fields in API models can lead to:
- Memory exhaustion from large payloads
- High-cardinality metrics when used as labels
- Database storage issues

**Solution:** Add `@Size` constraints to all string fields:
```java
public record WebVitalEntry(
    @NotBlank @Size(max = 64) String id,
    @Size(max = 32) String navigationType,
    @NotBlank @Size(max = 256) String route,
    // ...
) {}

public record WebVitalsBatch(
    @Size(max = 512) String userAgent,
    @Size(max = 2048) String url,
    // ...
) {}
```

**Recommended limits:**
| Field Type | Max Size | Rationale |
|------------|----------|-----------|
| IDs/tokens | 64 | UUIDs are 36 chars |
| Route paths | 256 | Reasonable URL path |
| User agents | 512 | Some browsers have long UAs |
| Full URLs | 2048 | Common browser limit |
| Free text | 1024+ | Depends on use case |

### Sanitize Metric Labels to Control Cardinality

**Problem:** Using raw user input as Prometheus metric labels creates unbounded cardinality, which can crash Prometheus or cause excessive memory usage.

**Solution:** Sanitize labels before recording:
```java
private String sanitizeRoute(String route) {
    if (route == null || route.isBlank()) {
        return "unknown";
    }
    // Strip query strings and fragments (high cardinality)
    String sanitized = route.split("\\?")[0];
    sanitized = sanitized.split("#")[0];

    // Replace dynamic path segments with placeholders
    return sanitized
        .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", ":uuid")
        .replaceAll("/\\d+", "/:id");
}
```

**Common sources of cardinality explosion:**
- Query strings (`?foo=bar&baz=123`)
- URL fragments (`#section`)
- UUIDs in paths (`/users/550e8400-e29b-...`)
- Numeric IDs (`/items/12345`)
- Timestamps
- Session tokens

## Database Schema and Migrations

### A test on H2 can pass while the same code is corrupting PostgreSQL

**Cost when it was found:** months of orphan rows accumulated behind a green test.

The application's tests run on H2; production is PostgreSQL. **H2 honoured the inline
`REFERENCES ... ON DELETE CASCADE` in `V1__initial_schema.sql`. Production did not** -- production
has only a handful of foreign keys, none of them from V1.

A merge service hard-deleted a merged-away entity under the comment
`// CASCADE handles <three child tables>`. On H2 the cascade really did handle them, so the
test asserting that the child rows were gone was green -- **while production silently orphaned
every row those three tables held.** The test was exercising the database, not the code.

The fingerprint is worth internalising: the tables the service *explicitly* re-pointed had
**zero** orphans. The one table with a real FK also had **zero**. The orphaned tables were
exactly the three the comment claimed the cascade covered.

**The guard:** when the behaviour under test depends on a database constraint, write the test so it
**cannot** be satisfied by the constraint. Drop the FK first, reproducing the weaker production
schema, and only then exercise the code path:

```java
// Production has no cascade on the child table. H2 does. Drop it, or this test proves nothing.
stmt.execute("ALTER TABLE child_table DROP CONSTRAINT IF EXISTS ...");
// ... now merge, and assert the APPLICATION deleted the row.
```

Verify the test is **RED against the unfixed code**. If it passes before your fix, it is testing the
database's behaviour, not yours.

**Generalisation:** application-level integrity and database-level integrity are two independent
lines of defence, and you need both. Never let the DB be the only one -- especially when your test
DB is stricter than production. If dev/test and prod differ in engine, assume they differ in
enforcement until you have proven otherwise.

### Do not rely on `flyway.repair-at-start` in production

`quarkus.flyway.repair-at-start=true` rewrites migration history to agree with the current
repository -- including deleting history rows for migrations that have vanished.

In one application it silently absorbed an in-place rewrite of an already-applied `V1`, so
`flyway_schema_history` ended up **agreeing with a file whose constraints the database had never
received**, and the evidence of what actually executed was destroyed (a `type=DELETE` tombstone and
a missing `installed_rank` are all that survive).

A recorded `success = t` is not proof a migration's effects exist. Verify against
`pg_constraint` / `pg_indexes`, not against `flyway_schema_history`.

**Before turning `repair-at-start` off** on a live database, check that no checksum has actually
diverged, or the next deploy fails the boot. Flyway's SQL checksum is a CRC32 over each line's UTF-8
bytes with line endings stripped -- you can compute it offline and diff it against the recorded
`checksum` column.

## Vendor Integrations

### Never Trust a Vendor Write Response -- Read It Back

> **Never trust a vendor write response. Read the entity back and assert its semantic fields.**
> A 2xx and a well-formed id prove the request was accepted -- not that it meant what you intended.

Unit tests with mocked vendors cannot catch "the vendor ignores this field name", because the mock
accepts whatever the code sends. Two real defects from an integration with the Dinero accounting API, both
of which passed `mvn verify` and returned success on first live use:

| Defect | What the call returned | What actually happened |
|--------|------------------------|------------------------|
| `content_base64` receipt upload | a normal `FileGuid` | receipt corrupted mid-stream; a magic-byte check passed because the corruption was not at the start. Caught by asserting the archived `Size` equals the local byte count **exactly** |
| purchase-voucher line amount field | a normal voucher `Guid` | Dinero silently discarded unrecognised line fields (its OpenAPI even declares `additionalProperties: false`, unenforced) -> **zero-value draft**. Three field-name guesses in a row "succeeded". The create model wants `Amount`; `AmountExclVatValue` etc. belong to the READ model |

Rules that generalise to every vendor integration in this repo:

1. **After every write, read the entity back** and assert the semantic fields (amounts, currency,
   attachment linkage) -- not the HTTP status.
2. **Guard irreversible operations with an expected value** the service compares against the live
   entity (the `expected_total` pattern), and fail closed with a 409.
3. **On a failed creation guard, delete what you created** -- never strand a wrong-value entity in
   the vendor's books.
4. **Verify end-to-end against the live vendor before handback** (one live verification script
   per integration): assert byte counts, page counts, read-back totals, and guard rejections.
5. **Fetch the vendor's machine-readable schema** (e.g. `https://api.dinero.dk/openapi/v1/swagger.json`)
   instead of guessing field names from rendered docs, and pin the discovered names in a test.

### Twilio Regional Accounts: a 401 Usually Means Wrong Host, Not Wrong Secret

A Twilio account can live in Twilio's
**Ireland (`ie1`) realm**. Region-scoped credentials return **HTTP 401 / code 20003** against
the default US hosts, which is indistinguishable from a bad secret. **Check the realm before
you suspect the credential** -- and before you rotate one, which is the expensive wrong move.

Current host format is `{product}.{edge-location}.{region}.twilio.com`:
`verify.dublin.ie1.twilio.com`, `api.dublin.ie1.twilio.com`. The older `verify.ie1.twilio.com`
form was retired 2026-04-28 and no longer resolves -- so keep the base URL in deployment
config, not compiled into the code.

**One account can exist in two realms with entirely separate credentials.** The account SID
is the same in both; the auth token is not, and neither are API keys or product resources.
Measured on one account:

| Credential | 200 against | 401 / 404 against |
|------------|-------------|-------------------|
| `ie1` auth token | `api.dublin.ie1.twilio.com` | `api.twilio.com` |
| US auth token | `api.twilio.com` | `api.dublin.ie1.twilio.com` |
| API key `SK...` + secret (either realm) | that realm's product host, e.g. `verify.*.twilio.com` | `/2010-04-01/Accounts/...` on **any** host |

So account-level facts are readable **only** with the account SID and auth token, against
that realm's core host. A health check built on the API key cannot see them and reports a
401 that looks like a broken integration.

**Verify service SIDs are realm-scoped, and nothing in a `VA...` SID says which realm.** A
service created in `ie1` returns **20404 "resource not found"** against `verify.twilio.com`
-- indistinguishable from a deleted service. Listing `/v2/Services` per realm shows the
truth: each realm holds its own set. When storing credentials, keep base URL + API key +
service SID as one realm-suffixed group and never mix them; a cross-realm mix authenticates
cleanly and then 404s on every call.

**Do not build a trial-mode check on the Account resource's `type` field, and do not read a
`21608` as a regional problem.** Observed on one account: `"type": "Full"`, `"status": "active"`,
`owner_account_sid` equal to its own SID, a positive balance with a card on file -- and
Verify sends returning `403` / `21608`, *"Trial accounts cannot send messages to unverified
numbers"*, **identically from both realms** with each realm's own credentials and service.
The messaging layer enforced a restriction the Account resource denied, account-wide, and
the trial state followed the account across realms.

Two lessons: trust the send, not the field; and a stored payment method is not an upgrade --
trial credit and a paid balance look the same through the API. A corroborating signal
cheaper than a send: an empty `OutgoingCallerIds` collection means no number has ever been
console-verified, so a trial-restricted account can reach nobody at all.

`ie1` is also missing endpoints that answer normally in `us1`. Do not build monitoring on
these -- or rather, point account-level monitoring at the US host, where they work:

| Endpoint | Result in `ie1` | Result in `us1` |
|----------|-----------------|-----------------|
| `/2010-04-01/Accounts/{sid}/Balance.json` | 404 -- "Endpoint is not supported in realm 'ie1'" | 200 |
| `/2010-04-01/Accounts/{sid}/Usage/Records/...` | 404 -- same | 200 |
| Verify `/v2/Attempts` | 503 | -- |
Likewise, messaging **geographic permissions are console-only**: no API call proves an SMS
can reach a given country, so a live send to a real handset is the only evidence delivery
works. A green test suite proves the code agrees with itself, not that a hostname exists or a
message arrives.

### Cloudflare Workers: `secrets.required` Silently Filters `.dev.vars`

Declaring `secrets.required` in `wrangler.jsonc` is not just documentation. Wrangler's own
config schema (`node_modules/wrangler/config-schema.json`) says the field *"replaces
.dev.vars/.env/process.env inference"* -- and that is literal.

**An entry in `.dev.vars` whose name is not on that list is dropped in local dev.** No
warning, no row in the startup bindings table, and the Worker runs as if the file never
mentioned it. The failure looks like application logic misbehaving: in one Worker a
`TEST_VERIFY_CODE` bypass appeared to be ignored, and the code went to the real provider
instead. The tell is the bindings table wrangler prints at startup -- compare it against
the file, and the missing names are exactly the ones absent from `secrets.required`.

Two consequences worth knowing before you debug one of them:

- An **optional** secret (declared `foo?: string` in the Env type and deliberately left out
  of `secrets.required`) cannot be supplied locally through `.dev.vars` at all. Pass it as
  `wrangler dev --var NAME:VALUE`, which is not filtered.
- `secrets` accepts **only** `required` (`additionalProperties: false`) -- there is no
  `optional` list to add it to.

Deployed Workers are unaffected: secrets set with `wrangler secret put` reach the Worker
regardless of what `secrets.required` lists. It **does** gate the first deploy of a new
Worker, though -- see the next entry.

### Cloudflare Workers: the First Deploy of a Secret-Bearing Worker Is a Deadlock

`secrets.required` gates deployment after all, but only when the Worker does not exist yet.
On a first deploy the two obvious commands refuse each other:

```
$ wrangler secret put PRIVATE_ROSTER
  This Worker does not exist yet, so secrets cannot be set in advance.

$ wrangler deploy
  ✘ The following required secrets have not been set: PRIVATE_ROSTER, SESSION_SALT, ...
```

The way through is `wrangler deploy --secrets-file <path>`, which supplies them *with* the
create. The file takes `SECRET_NAME=value` lines **or JSON** -- and JSON is what you want
for any multi-line value, since a JSON blob secret cannot survive the line format. Build it
outside the repo, `chmod 600`, delete it immediately after:

```bash
jq -n --rawfile roster ~/roster.json --arg salt "$(openssl rand -hex 32)" \
  '{PRIVATE_ROSTER:$roster, SESSION_SALT:$salt}' > "$TMP/secrets.json"
wrangler deploy --secrets-file "$TMP/secrets.json" && rm -f "$TMP/secrets.json"
```

Only the first deploy needs this; afterwards `wrangler secret put` works normally.

### Cloudflare Workers: A Deploy Is Not Live Everywhere the Second It Returns

`wrangler deploy` returning success means the version is uploaded and marked active, not
that every edge location is already serving it. A new secret plus new code that reads it
can take a couple of minutes to line up.

This is worth knowing because the symptom looks exactly like a configuration fault: the
secret is listed by the API, it appears in the active version's bindings, the new code is
demonstrably live -- and the feature still behaves as though the secret were unset. Once,
that combination sent a debugging session chasing a wrong hypothesis
(that `secrets.required` gates which secrets get injected -- it does not) when the fix was
to wait.

Before diagnosing, prove the code is actually the new build: grep the live response for a
string only the new version emits. If that is present and the behaviour is still stale,
wait two minutes and re-test before changing anything.

### Cloudflare API Tokens: Account-Scoped Workers Permissions Do Not Attach Custom Domains

A token can hold enough permission to create a Worker, upload its code and set its secrets,
and still be unable to give it a hostname. `wrangler deploy` then does most of its job and
**exits non-zero**:

```
✘ A request to the Cloudflare API (/zones/<zone>/workers/routes) failed.
  Authentication error [code: 10000]
```

The Worker is live at that point and is not rolled back -- it simply has no route. Custom
domains are reachable through the *account*-level endpoint as well, which an
account-scoped token can use:

```bash
curl -X PUT -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H "Content-Type: application/json" \
  --data '{"environment":"production","hostname":"example.com","service":"my-worker","zone_id":"<zone>"}' \
  "https://api.cloudflare.com/client/v4/accounts/<account>/workers/domains"
```

Custom domains persist across deploys, so this is one-time. But the non-zero exit persists
too, which matters for CI: a deploy job will fail forever on a deploy that actually worked.
Grant the token `Zone -> Workers Routes -> Edit` rather than teaching CI to ignore the exit
code -- that exit code is also how a genuinely failed deploy reports itself.

## Known Platform Limitations

### Hetzner CSI Driver Does Not Support VolumeSnapshots

**Status:** Blocked by upstream - no workaround available

**Problem:** The Hetzner Cloud CSI driver does not implement the `ControllerCreateSnapshot` capability required for Kubernetes VolumeSnapshots. This is a missing feature in the driver, not a configuration issue.

**Impact:**
- **No CSI-based PVC backups** - Velero cannot use CSI snapshots to backup PersistentVolumeClaim data
- **No point-in-time recovery** - Cannot create consistent snapshots of volumes for disaster recovery
- **Backup gap** - Kubernetes resources are backed up to R2, but PVC data (databases, stateful apps) is not protected at the block level

**Current state:**
- VolumeSnapshot CRDs and snapshot-controller are installed (for future compatibility)
- Velero schedules have `snapshotVolumes: false`
- Only Kubernetes manifests are backed up, not volume data

**Workarounds:**
1. **Application-level backups** - Use pg_dump, mysqldump, or similar tools for databases
2. **Velero fs-backup** - File-level backup using Kopia/Restic (requires pod annotations)
3. **OpenEBS LVM** - Add LVM layer on top of Hetzner volumes for local snapshot support

**References:**
- [hetznercloud/csi-driver#849](https://github.com/hetznercloud/csi-driver/issues/849) - Feature request (opened Jan 2025, no timeline)
- [ADR-0020: Backup Strategy](docs/adr/0020-backup-strategy.md)

### Talos Snapshot Boot Failure with hcloud-upload-image (v1.12.1+)

**Status:** Open - workaround available

**Problem:** Talos v1.12.1 snapshots created by `hcloud-upload-image` fail to boot on Hetzner Cloud. The server starts but Talos never initializes -- connection refused on port 50000. Both custom schematic and vanilla images are affected. A snapshot of an older release (from the v1.9 line) boots fine.

**Workaround:** Bootstrap new nodes from an older snapshot that boots, then upgrade in-place one minor version at a time up to the target using `talosctl upgrade --preserve`.

**References:** an issue in the installation's tracker, and the completion notes of the
Talos Packer snapshot migration plan.

### Talos Upgrade TLS Handshake Timeout

**Problem:** When upgrading a node that has a large Kubernetes version gap compared to the cluster (e.g., a kubelet three minor versions behind the cluster), `talosctl upgrade` fails with `net/http: TLS handshake timeout` on `https://127.0.0.1:7445`. The drain phase succeeds but the cleanup phase can't list pods via the internal kubelet proxy.

**Solution:** Use `talosctl upgrade --stage --preserve` to bypass the drain/cleanup phase. The `--stage` flag writes the upgrade to the inactive partition and reboots immediately. After the node comes back, run `talosctl upgrade-k8s` to sync the kubelet version.

### Hetzner Server Limits for Snapshot Building

**Problem:** Building a Talos snapshot with `hcloud-upload-image` requires creating a temporary server. If the Hetzner project is at its server limit (e.g., 5/5), the build fails.

**Workaround:** Delete a worker node first (`hcloud server delete`), build the snapshot, then recreate the worker via `tofu apply`. The worker will rejoin the cluster automatically. Prioritize this during maintenance windows where brief downtime is acceptable.
