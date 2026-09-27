# OpenBao Operations Runbook

**Placeholders.** Replace each placeholder with the value of your installation before you run a command:

| Placeholder | Value |
|---|---|
| `<cloud-kubeconfig>` | The path of the kubeconfig file for the cloud cluster |
| `<unseal-keys-file>` | The name of the SOPS-encrypted file in `platform/resources/cloud/secrets/` that holds the `openbao-unseal-keys` Secret |
| `<openbao-env-file>` | The path of the local, untracked file with the unseal keys that the break-glass escrow holds |

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Scoped-Role Provisioning](#scoped-role-provisioning)
- [Break-Glass Access](#break-glass-access)
- [Rekey Procedure](#rekey-procedure)
- [Sealed-State Recovery](#sealed-state-recovery)
- [Break-Glass Escrow Refresh](#break-glass-escrow-refresh)
- [Troubleshooting](#troubleshooting)
- [Related](#related)

## Overview

OpenBao (`openbao-0`, a single-pod StatefulSet in `platform-ops`) is the platform's
secrets/crypto engine: KV storage for `manyclaw/` and tenant mounts, the Transit
encryption-as-a-service engine (ADR-0040), and a Raft storage backend backed up nightly to
R2. It authenticates workloads via a single `kubernetes/` auth mount with per-consumer
scoped roles (`raft-backup`, `codex-seeder`, `openbao-admin`, plus per-tenant and
per-app roles).

The root token is **not stored at rest anywhere** -- see the [manyfold-platform ADR-0017
operational
amendment](../../adr/0017-secrets-management-strategy.md#operational-amendment-2026-07-12-openbao-root-token-retirement)
and the OpenBao root-token and credential-rotation plan of 2026-06-12.
Day-to-day privileged operations authenticate via the scoped `openbao-admin` role. There
is no root token at rest (revoked 2026-07-19). On OpenBao 2.5 the classic
`generate-root`/`rekey` break-glass endpoints are disabled -- see
[Break-Glass Access](#break-glass-access) for the working admin and true-root paths.

> **Transition status (2026-07-12):** this Overview describes the TARGET state. Until
> the retirement plan's Phase C completes, the legacy root token still sits in the
> SOPS-encrypted `openbao-unseal-keys` secret (step C7 removes it), and the
> `raft-backup`/`codex-seeder`/`openbao-admin` roles exist only after the operator runs
> `setup-openbao-k8s-auth.sh create-ops-roles` (step C1). The raft-backup CronJob
> carries a transitional root-token fallback until step C3b removes it.

## Prerequisites

- `kubectl` with the cloud kubeconfig (`<cloud-kubeconfig>`)
- For any procedure that touches root access or rekeying: access to the unseal-key quorum
  (3-of-5) -- `sops -d platform/resources/cloud/secrets/<unseal-keys-file>`, or
  the break-glass escrow copy
- Every procedure below runs `bao`/`sops` in a live pod or against the live encrypted
  secret. **These are operator-only actions** -- an agent must never execute them, read a
  key or token value, or print one.

## Scoped-Role Provisioning

`setup-openbao-k8s-auth.sh create-ops-roles` provisions the three scoped operator roles
that retire the root token as a day-to-day credential: `raft-backup` (Raft snapshot read
only), `codex-seeder` (write access to one KV path), and `openbao-admin` (day-2 admin
surface -- explicitly not root: no rekey, no generate-root, no unseal). It is idempotent
and only needs to run once, the first time (plan step C1), using the root token as a
one-time break-glass bootstrap:

```bash
cd infrastructure/clusters/cloud/scripts
OPENBAO_ROOT_TOKEN=<current-root-token> ./setup-openbao-k8s-auth.sh create-ops-roles
```

Expected output: three policies (`raft-backup`, `codex-oauth-seeder`, `platform-admin`)
and three Kubernetes-auth roles (`raft-backup`, `codex-seeder`, `openbao-admin`) reported
created.

After this, the script's own default auth -- and the raft-backup CronJob's primary auth
path -- uses the scoped roles. Confirm with:

```bash
./setup-openbao-k8s-auth.sh status
```

run *without* `OPENBAO_ROOT_TOKEN` exported; it should succeed via the scoped
`openbao-admin` login.

## Break-Glass Access

> **OpenBao 2.4+ change (verified live on 2.5, 2026-07-19):** the classic
> unauthenticated break-glass endpoints -- `sys/generate-root` AND `sys/rekey` -- are
> **disabled by default** (they return HTTP 405 "unsupported operation"). The old
> "regenerate a root token with the unseal-key quorum" procedure documented here no
> longer works on this cluster. After the rotation plan's C5 revoked the last root token,
> **there is no root token anywhere and no enabled path to mint one.** This is deliberate;
> the sections below are the working break-glass paths.

### Admin break-glass (covers almost everything)

`openbao-admin` is the top of the day-to-day privilege chain. It authenticates via
Kubernetes auth (so it requires cluster access -- itself gated by the kubeconfig), and its
`platform-admin` policy covers auth methods, mounts, policies, Transit, and all tenant KV.
This handles every routine emergency (fix a policy, re-provision roles, read/repair a
secret):

```bash
AJWT=$(kubectl --kubeconfig <cloud-kubeconfig> create token openbao-admin -n platform-ops --duration=15m)
ADMIN=$(kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
  bao write -field=token auth/kubernetes/login role=openbao-admin jwt="$AJWT")
# use "$ADMIN" as BAO_TOKEN for the operation; it expires on its own TTL
```

Key rotation is done the same way -- via a transient rotation-only token minted by
`openbao-admin` -- see [Rekey Procedure](#rekey-procedure).

### True-root break-glass (rarely needed)

A handful of operations genuinely need root (they are refused for `openbao-admin` with
"root tokens may not be created without parent token being root", or return "unsupported
operation"). Because `generate-root` is disabled, obtaining root now requires **one** of:

1. **Re-enable the unauthenticated endpoints, use, then re-disable.** Set
   `disable_unauthed_rekey_endpoints = false` in the OpenBao listener/config
   (`platform/components/openbao/values-cloud.yaml`), let ArgoCD sync + the pod restart,
   run the classic `bao operator generate-root -init` / feed 3 unseal keys / `-decode`
   flow, then revert the config. Quorum-gated (needs 3 unseal keys = the SOPS age key).
2. **Restore from a Raft snapshot** taken while a root path existed
   (see [openbao-raft-restore.md](../disaster-recovery/openbao-raft-restore.md)).

If you never actually need root (only admin), you never need either -- prefer the admin
break-glass above. **Standing policy (ADR-0017, decided 2026-07-19): the admin-only ceiling
is the accepted end state -- true root is deliberately not routinely reachable.** When it is
genuinely required, use option 1 (config flip) under change control, or option 2
(raft-restore).

## Rekey Procedure

Rekey when the unseal-key quorum is suspected compromised, or as part of a trust-tier
migration (see the rotation cadence
table (the secrets-management setup guide, private)). No fixed
cadence otherwise.

> **OpenBao 2.4+ change:** `bao operator rekey` (unauthenticated) is disabled -- use
> `bao operator rotate-keys`, which is the **authenticated** replacement. It still requires
> a 3-of-5 unseal-key quorum on the update phase (the quorum gate is preserved); it just
> also needs a token that can call `sys/rotate/root`. Do NOT grant that capability to a
> standing role -- mint a transient rotation-only token via `openbao-admin` and destroy it
> after (this is how the 2026-07-19 rotation was performed).

1. Authenticate as `openbao-admin` (see [Admin break-glass](#admin-break-glass-covers-almost-everything)),
   then write a transient `key-rotation` policy and mint a short-TTL token bound to it:

   ```bash
   # ADMIN from the admin break-glass block above
   kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- sh -c 'cat > /tmp/kr.hcl << EOF
   path "sys/rotate/root"   { capabilities = ["read","update","sudo"] }
   path "sys/rotate/root/*" { capabilities = ["create","read","update","delete","sudo"] }
   EOF'
   kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
     env BAO_TOKEN="$ADMIN" bao policy write key-rotation /tmp/kr.hcl
   ROT=$(kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
     env BAO_TOKEN="$ADMIN" bao token create -policy=key-rotation -ttl=15m -orphan -field=token)
   ```

2. Initialize + supply three current unseal keys with `$ROT` (note: `-format=json` must
   precede the positional key, and each key needs the `-nonce` from init):

   ```bash
   NONCE=$(kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- env BAO_TOKEN="$ROT" \
     bao operator rotate-keys -init -key-shares=5 -key-threshold=3 -format=json | jq -r .nonce)
   # feed 3 distinct current keys; the LAST update returns .keys_base64 (the 5 new keys)
   kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- env BAO_TOKEN="$ROT" \
     bao operator rotate-keys -format=json -nonce="$NONCE" <unseal-key>
   ```

3. **Capture the five new keys (`keys_base64` from the final update) directly into the SOPS
   editing session** of the next step -- never into a plain file or shell history. Then
   revoke `$ROT` and delete the `key-rotation` policy and its file:

   ```bash
   kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
     env BAO_TOKEN="$ROT" bao token revoke -self
   kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
     env BAO_TOKEN="$ADMIN" bao policy delete key-rotation
   kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
     rm -f /tmp/kr.hcl
   ```

4. Re-encrypt the secret from within `platform/resources/cloud/secrets/` (so `.sops.yaml`
   applies) with the new keys and NO root token. **KSOPS/ArgoCD gotcha:** syncing a Secret
   whose desired state dropped a key does NOT prune that key from the live Secret -- after
   the sync, `kubectl --kubeconfig <cloud-kubeconfig> patch secret openbao-unseal-keys -n platform-ops --type=json -p
   '[{"op":"remove","path":"/data/root-token"}]'` to remove any leftover field.

   ```bash
   sops platform/resources/cloud/secrets/<unseal-keys-file>
   ```

   Replace the five `unseal-key-*` values with the new keys. Commit and push; ArgoCD syncs
   the updated Secret.

5. Verify that the Secret holds exactly the expected keys. This command shows the key
   names only, not the values:

   ```bash
   kubectl --kubeconfig <cloud-kubeconfig> get secret openbao-unseal-keys -n platform-ops \
     -o go-template='{{range $k, $v := .data}}{{$k}}{{"\n"}}{{end}}'
   ```

   Expected: the five `unseal-key-*` keys and no `root-token`.

6. Prove auto-unseal with the new keys (see [Sealed-State
   Recovery](#sealed-state-recovery)), then refresh break-glass escrow (see below).

## Sealed-State Recovery

OpenBao re-seals on every pod restart (it does not persist unseal state across restarts).
The auto-unseal sidecar in the `openbao-0` pod watches for the sealed state and unseals
automatically using the five keys in the `openbao-unseal-keys` secret -- no manual action
is normally needed.

To verify a restart recovered cleanly:

```bash
kubectl --kubeconfig <cloud-kubeconfig> get pods -n platform-ops -l app.kubernetes.io/name=openbao
kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- bao status
kubectl --kubeconfig <cloud-kubeconfig> logs openbao-0 -n platform-ops -c auto-unseal
```

Expected: pod `Ready`; `bao status` shows `Sealed` as `false`; the `auto-unseal` sidecar
logs show `[auto-unseal] Unseal complete` after the last restart.

If the sidecar fails to unseal automatically (e.g. after a rekey the sidecar hasn't picked
up, or the sidecar container itself is crash-looping), unseal manually with three of the
current keys:

```bash
kubectl --kubeconfig <cloud-kubeconfig> exec openbao-0 -c openbao -n platform-ops -- \
  bao operator unseal <unseal-key>
```

Repeat with two more distinct keys. `bao status` should then show `sealed: false`.

## Break-Glass Escrow Refresh

The break-glass manifest of the umbrella repository escrows the local
`<openbao-env-file>`. After any rekey (or once the root token is retired per plan step
C7), refresh that escrow so it never holds stale or revoked material:

1. Update the local `<openbao-env-file>` to carry the five current unseal keys and **no**
   root token -- note the [True-root break-glass](#true-root-break-glass-rarely-needed)
   procedure above instead of a stored token.
2. Re-run the break-glass upload per the umbrella repo's own procedure (owned there, not
   duplicated in this runbook).
3. Verify: escrow holds the current material; any previously revoked token or superseded
   keys are no longer recoverable from break-glass.

## Troubleshooting

**Privileged `bao` calls 403 after a pod restart.** The in-pod CLI has no cached login
token after a restart (unsealing does not authenticate the CLI). `setup-openbao-k8s-auth.sh`
handles this automatically via a scoped `openbao-admin` login in `check_prerequisites`; if
you're calling `bao` directly, log in first with the appropriate role.

**"Scoped login as role 'openbao-admin' failed" / "role probably not provisioned yet".**
The scoped ops roles haven't been created yet -- a chicken-and-egg case, since
`create-ops-roles` normally authenticates AS `openbao-admin`. On a fresh cluster where no
role exists and no root token is available (root is disabled on 2.5), bootstrap the roles
by temporarily re-enabling the unauthenticated endpoints (option 1 under
[True-root break-glass](#true-root-break-glass-rarely-needed)) to mint a one-shot root, or
restore from a Raft snapshot that already has the roles.

**Raft-backup CronJob logs a "falling back to root token" warning.** No longer possible --
the transitional fallback was removed 2026-07-19 (plan C3b); the CronJob now uses scoped
`raft-backup` auth only. A failure here means the `raft-backup` role is missing (see
`platform/resources/cloud/openbao/raft-backup-cronjob.yaml`
header comment). If it persists after C1 has run, the `raft-backup` Kubernetes-auth role or
the `openbao-raft-backup` ServiceAccount binding is misconfigured -- re-run
`create-ops-roles` and check `auth/kubernetes/role/raft-backup`.

**OpenBao sealed and staying sealed.** See [Sealed-State
Recovery](#sealed-state-recovery). If manual unseal also fails, the unseal keys in the
secret may be stale relative to the last rekey -- check SOPS metadata `lastmodified` on
`<unseal-keys-file>` against the last known rekey date.

## Related

- The OpenBao root-token and credential-rotation plan of 2026-06-12 (instance documentation)
- [manyfold-platform ADR-0017: Secrets Management Strategy](../../adr/0017-secrets-management-strategy.md)
- [manyfold-platform ADR-0040: Tenant App-Managed Secrets via OpenBao Transit](../../adr/0040-tenant-app-managed-secrets-via-openbao-transit.md)
- Secrets Management (SOPS + Age) (the secrets-management setup guide, private)
- `infrastructure/clusters/cloud/scripts/setup-openbao-k8s-auth.sh`
- `infrastructure/clusters/cloud/scripts/renew-codex-token.sh`
- `platform/resources/cloud/openbao/raft-backup-cronjob.yaml`
- The token broker runbook (instance documentation)
