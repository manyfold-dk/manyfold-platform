# Secrets Management with SOPS + Age

This guide covers how to manage encrypted secrets in the Manyfold Platform using SOPS (Secrets OPerationS) with age encryption.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Initial Setup](#initial-setup)
- [Creating Encrypted Secrets](#creating-encrypted-secrets)
- [Common Operations](#common-operations)
- [Rotation Cadence and Triggers](#rotation-cadence-and-triggers)
- [Troubleshooting](#troubleshooting)
- [Security Best Practices](#security-best-practices)
- [File Structure](#file-structure)
- [Scripts Reference](#scripts-reference)
- [Related Documentation](#related-documentation)

## Overview

The platform uses a GitOps approach for secrets management:

1. **Encryption**: Secrets are encrypted locally using SOPS with age encryption
2. **Storage**: Encrypted secrets are safely committed to Git
3. **Decryption**: ArgoCD decrypts secrets at sync time using KSOPS (Kustomize-SOPS plugin)

```
┌─────────────────────┐     ┌─────────────────────────────────────┐
│  Developer Machine  │     │          Cloud Cluster              │
├─────────────────────┤     ├─────────────────────────────────────┤
│                     │     │  ArgoCD (patched repo-server)       │
│  .env file          │     │    - KSOPS plugin installed         │
│  (source secrets)   │     │    - Age private key mounted        │
│        │            │     │           │                         │
│        ▼            │     │           ▼                         │
│  sops encrypt       │     │  Decrypts at sync time              │
│        │            │     │           │                         │
│        ▼            │     │           ▼                         │
│  Encrypted YAML ────┼────►│  Native K8s Secrets created         │
│  (committed to Git) │     │                                     │
└─────────────────────┘     └─────────────────────────────────────┘
```

Paths in this guide are relative to the root of the repository that holds the encrypted
secrets and the cluster scripts. Run each command from that root unless the step changes into
a directory itself.

The cloud cluster is the only cluster. The local kind cluster and its secrets directory were
retired on 2026-09-27 (see
[ADR-0054's amendment of 2026-09-27](../adr/0054-public-platform-and-private-instance-repositories.md#amendment-2026-09-27-no-demo-tree-throwaway-cloud-instances-instead)).
`<cloud-kubeconfig>` below is the path of the kubeconfig file for the cloud cluster; the cloud
scripts read their own fixed path and ignore `KUBECONFIG`.

## Prerequisites

The following tools are installed in the devcontainer:

- **sops** (v3.10.x) - Encrypts/decrypts YAML files
- **age** (v1.3.x) - Modern encryption tool used by SOPS

## Initial Setup

### 1. Generate Age Keypair

Generate a new age keypair:

```bash
# From devcontainer or host with age installed
cd infrastructure/clusters/cloud/scripts
./setup-sops-age.sh generate
```

This creates:
- Private key: `~/.config/sops/age/keys.txt`
- Outputs the public key (starts with `age1...`)

**Important**: Never commit the private key to version control!

### 2. Configure SOPS

Update the `.sops.yaml` file with your public key. The `path_regex` must match the plain
file you encrypt (`secret.yaml`), not only the encrypted output: `sops -e` matches the rule
against its input path and fails with `no matching creation rules found` otherwise.

`platform/resources/cloud/secrets/.sops.yaml`:
```yaml
creation_rules:
  - path_regex: .*\.yaml$
    encrypted_regex: ^(data|stringData)$
    age: age1your-public-key-here
```

### 3. Provision Key to Cluster

```bash
(cd infrastructure/clusters/cloud/scripts && ./setup-sops-age.sh create && ./setup-sops-age.sh status)
```

Then, from the repository root, apply the KSOPS patch and restart the repo-server:
```bash
kubectl --kubeconfig <cloud-kubeconfig> apply -f platform/argocd/cloud/repo-server-patch.yaml
kubectl --kubeconfig <cloud-kubeconfig> rollout restart deployment/argocd-repo-server -n argocd
```

**Important:** The SOPS age key must be configured BEFORE applying the app-of-apps. The `cloud-secrets` application (sync-wave -4) deploys before `external-dns` and `cert-manager` (sync-wave -1), so the decryption key must be available first.

See `infrastructure/clusters/cloud/bootstrap/README.md` for the complete bootstrap sequence.

## Creating Encrypted Secrets

### Example: Slack Alerts Webhook Secret

The `slack-webhook-alerts` secret holds the webhook URL for Alertmanager alerts (namespace:
`observability`).

1. **Create the plain file** in the secrets directory:

   ```bash
   cd platform/resources/cloud/secrets
   ```

   ```yaml
   # slack-webhook-alerts.yaml
   apiVersion: v1
   kind: Secret
   metadata:
     name: slack-webhook-alerts
     namespace: observability
   type: Opaque
   stringData:
     webhook-url: "https://hooks.slack.com/services/YOUR/ACTUAL/WEBHOOK"
   ```

2. **Encrypt the secret**:

   ```bash
   sops -e slack-webhook-alerts.yaml > slack-webhook-alerts.enc.yaml
   ```

3. **Delete the plain file**:

   ```bash
   rm slack-webhook-alerts.yaml
   ```

4. **Check the KSOPS generator**:

   The encrypted file is already referenced in `platform/resources/cloud/secrets/ksops-generator.yaml`.
   A new secret needs its own entry in the `files` list.

5. **Commit the encrypted file** and open a pull request; Argo CD syncs it after the merge:

   ```bash
   git add slack-webhook-alerts.enc.yaml
   git commit -m "feat(secrets): update encrypted slack alerts webhook"
   ```

## Common Operations

The commands in this section run inside the secrets directory that holds the file and its
`.sops.yaml` (`platform/resources/cloud/secrets/`): `cd` there first,
because sops reads the creation rules from the working directory.

### Viewing Encrypted Secret Contents

To see the decrypted content of an encrypted file:

```bash
sops -d slack-webhook-alerts.enc.yaml
```

### Editing Encrypted Secrets

SOPS provides in-place editing:

```bash
sops slack-webhook-alerts.enc.yaml
```

This opens the decrypted content in your editor. When you save and close, SOPS re-encrypts automatically.

### Adding a New Secret

1. Create a plain YAML secret file
2. Encrypt it: `sops -e secret.yaml > secret.enc.yaml`
3. Delete the plain file: `rm secret.yaml`
4. Add to `ksops-generator.yaml` files list
5. Commit the encrypted file

### Re-encrypting After Key Rotation

`sops updatekeys` needs the old private key to open each file, and ArgoCD needs a key that
opens every file it syncs. Keep the old key available until every encrypted file and the
cluster have moved to the new one. Run the commands from the repository root.

1. Generate the new keypair in a separate file. `setup-sops-age.sh generate` refuses to
   overwrite an existing `~/.config/sops/age/keys.txt`:

   ```bash
   age-keygen -o ~/.config/sops/age/new-keys.txt   # prints the new public key
   ```

2. Give the cluster both keys. An age key file can hold several identities, and SOPS tries
   each of them. The `umask` keeps the combined file private (mode 0600):

   ```bash
   (umask 077 && cat ~/.config/sops/age/keys.txt ~/.config/sops/age/new-keys.txt \
     > ~/.config/sops/age/transition-keys.txt)
   (cd infrastructure/clusters/cloud/scripts && ./setup-sops-age.sh create ~/.config/sops/age/transition-keys.txt)
   ```

   Then restart the repo-server, as in
   [Provision Key to Cluster](#3-provision-key-to-cluster).

3. Replace the public key in every `.sops.yaml` that governs encrypted files. List them with
   `git ls-files '*.sops.yaml'`; today it is the cloud secrets directory.

4. Re-encrypt every encrypted file in the repository. SOPS looks for
   `.sops.yaml` from the current directory upwards, not from the file's directory, so each
   file is updated from its own directory. The transition key file holds both identities:
   `updatekeys` needs the old one to open each file, and the `sops rotate` below needs the new
   one after `updatekeys` has replaced the recipient:

   ```bash
   export SOPS_AGE_KEY_FILE=~/.config/sops/age/transition-keys.txt
   git ls-files -z '*.enc.yaml' | while IFS= read -r -d '' f; do
     (cd "$(dirname "$f")" && sops updatekeys --yes "$(basename "$f")") || echo "FAILED: $f"
   done
   ```

   `updatekeys` replaces the recipients but keeps each file's data key. After a suspected
   compromise that is not enough: whoever holds the old key can recover the data key from an
   old revision in Git history. In that case also run
   `sops rotate --in-place "$(basename "$f")"` after `updatekeys` inside the same subshell,
   which generates a new data key, and rotate the secret values themselves, because Git
   history keeps the old ciphertext.

5. Verify that no file is left on the old key. The loop decrypts every file with the new key
   alone and discards the output, so it prints no secret content. sops also loads the default
   key file (`$XDG_CONFIG_HOME/sops/age/keys.txt`) next to `SOPS_AGE_KEY_FILE`, so the loop
   points `XDG_CONFIG_HOME` at an empty directory; without that, a default file that still
   holds the old key makes the check pass for every file:

   ```bash
   export XDG_CONFIG_HOME="$(mktemp -d)"
   git ls-files -z '*.enc.yaml' | while IFS= read -r -d '' f; do
     SOPS_AGE_KEY_FILE=~/.config/sops/age/new-keys.txt sops -d "$f" >/dev/null 2>&1 \
       || echo "NOT ON NEW KEY: $f"
   done
   ```

   No output means every file opens with the new key. Run the same loop with the old key file
   (same `XDG_CONFIG_HOME`) and expect every file to be reported: the old key opens nothing.
   Then `unset XDG_CONFIG_HOME`. `sops filestatus` does not help here: it reports only whether
   a file is encrypted, not with which key.

6. Commit and push, then wait until ArgoCD has synced the secrets applications on the
   cluster.

7. Provision the new key alone on the cluster (`./setup-sops-age.sh create
   ~/.config/sops/age/new-keys.txt` from `infrastructure/clusters/cloud/scripts`, then restart
   the repo-server). Replace `~/.config/sops/age/keys.txt` with the new key file, run
   `unset SOPS_AGE_KEY_FILE` (step 4 pointed it at the transition file, and sops does not fall
   back to the default file when that path is gone), delete the transition file, and destroy
   the old key once nothing needs it.

## Rotation Cadence and Triggers

Honest inventory of what triggers rotation for each secret category in this platform, and
whether anything enforces it today. Every row below is a manual, operator-triggered
procedure -- nothing runs on an automated schedule yet.

| Secret | Trigger | Cadence | Procedure |
|--------|---------|---------|-----------|
| OpenBao root token | On demand (break-glass need only -- day-to-day ops use the scoped `openbao-admin` role) | None at rest -- retired 2026-07-19; no root token exists and true-root is deliberately not routinely obtainable on OpenBao 2.5 | [OpenBao runbook](../runbooks/applications/openbao.md#break-glass-access) |
| OpenBao unseal keys | Suspected compromise or trust-tier migration | No fixed cadence; last rotated 2026-07-19 | [OpenBao runbook](../runbooks/applications/openbao.md#rekey-procedure) |
| R2 bootstrap + backup credentials | On exposure | Annual + on-exposure; first rotation done 2026-07-19 (the account-wide API token was rolled) | `infrastructure/.working/*/bootstrap/backend.hcl` (the uncommitted OpenTofu working directory, described in the instance's cloud-infrastructure agent rules) |
| Renovate executor PAT (`git-credentials`) | On exposure or expiry | Fine-grained PAT with a finite expiry (rotate before it lapses), scoped to the repositories Renovate updates with Contents, Pull requests and Workflows | Regenerate the fine-grained PAT (see the CI supply-chain plan S11), update `password` in `platform/resources/cloud/secrets/git-credentials.enc.yaml`, revoke the old token |
| SOPS age key | On compromise only | No fixed cadence; owned by the secrets trust-tier plan | [Re-encrypting After Key Rotation](#re-encrypting-after-key-rotation) |
| Broker bearer and webhook tokens | On compromise | No fixed cadence | The broker application's runbook, section "Secret Rotation" |
| Talos machine secrets (every copy kept outside the cluster, for example in an operator's archive) | On compromise; otherwise at the next cluster rebuild | No fixed cadence. Treat an exposure of any copy as an exposure of the cluster's machine secrets | N/A -- regenerated as part of a cluster machine-config rebuild, not independently rotatable |

Source: the OpenBao root-token and credential-rotation plan (2026-06-12). Keep this
table honest -- update it whenever a rotation procedure's owner or trigger changes, and do
not reintroduce "every N days" language that nothing enforces.

## Troubleshooting

The `kubectl` commands in this section run against the cloud cluster: run
`export KUBECONFIG=<cloud-kubeconfig>` first.

### "Failed to get the data key" Error

The age private key is not available or doesn't match the public key used for encryption.

**Fix**: Ensure the sops-age secret exists and contains the correct private key. Run the
script from `infrastructure/clusters/cloud/scripts`:

```bash
./setup-sops-age.sh status
./setup-sops-age.sh create
kubectl rollout restart deployment/argocd-repo-server -n argocd
```

### "cannot find KRM exec plugin" Error

KSOPS is not properly installed in the ArgoCD repo-server.

**Fix**: Reapply the repo-server patch from the repository root:

```bash
kubectl apply -f platform/argocd/cloud/repo-server-patch.yaml
kubectl rollout restart deployment/argocd-repo-server -n argocd
```

Verify the init container ran:

```bash
kubectl logs -n argocd deployment/argocd-repo-server -c install-ksops
```

### ArgoCD Shows "Sync Failed" for Secrets App

Check the repo-server logs:

```bash
kubectl logs -n argocd deployment/argocd-repo-server -c argocd-repo-server | tail -50
```

Common issues:
- Missing sops-age secret
- Incorrect public key in `.sops.yaml`
- File referenced in `ksops-generator.yaml` doesn't exist

### Verifying KSOPS is Working

Check if kustomize plugins are enabled:

```bash
kubectl get cm argocd-cm -n argocd -o jsonpath='{.data.kustomize\.buildOptions}'
# Should output: --enable-alpha-plugins --enable-exec
```

Check if the sops-age volume is mounted:

```bash
kubectl get deployment argocd-repo-server -n argocd -o jsonpath='{.spec.template.spec.volumes[?(@.name=="sops-age")].name}'
# Should output: sops-age
```

## Security Best Practices

1. **Never commit unencrypted secrets** - Use `.gitignore` patterns or pre-commit hooks
2. **Rotate the key when it may be compromised** - The platform has no fixed rotation cadence (see [Rotation Cadence and Triggers](#rotation-cadence-and-triggers)); follow [Re-encrypting After Key Rotation](#re-encrypting-after-key-rotation)
3. **Limit access to private keys** - Only provision to systems that need to decrypt
4. **Keep separate keys per environment** - Only the cloud cluster holds the production key; a throwaway instance built from the public repository generates its own key at bootstrap and never receives the production key (ADR-0054)
5. **Audit secret access** - Monitor who can access the decryption keys

## File Structure

The cloud secrets directory (excerpt):

```
platform/resources/cloud/secrets/
├── .sops.yaml                              # SOPS configuration with age public key
├── kustomization.yaml                      # Kustomize config
├── ksops-generator.yaml                    # KSOPS plugin configuration
├── git-credentials.template.yaml           # Template for the Git credentials secret
├── git-credentials.enc.yaml                # Encrypted Git credentials secret
└── slack-webhook-alerts.enc.yaml           # Encrypted alerts webhook secret
```

## Scripts Reference

Run from `infrastructure/clusters/cloud/scripts`:

| Script | Purpose |
|--------|---------|
| `setup-sops-age.sh generate` | Generate new age keypair |
| `setup-sops-age.sh create` | Provision private key to cluster |
| `setup-sops-age.sh status` | Show configuration status |
| `setup-sops-age.sh delete` | Remove private key from cluster |

## Related Documentation

- [SOPS Documentation](https://github.com/getsops/sops)
- [Age Encryption](https://github.com/FiloSottile/age)
- [KSOPS (Kustomize-SOPS)](https://github.com/viaduct-ai/kustomize-sops)
- [ArgoCD KSOPS Guide](https://argo-cd.readthedocs.io/en/stable/operator-manual/custom_tools/)
