# ADR-0040: Tenant Application-Managed Secrets via OpenBao Transit

* Status: accepted
* Date: 2026-06-07
* Decider: Thomas
* Amends: ADR-0033 (advances the deferred per-app/per-ServiceAccount OpenBao scoping)
* Cross-ref: ADR-0017 (secrets management strategy), ADR-0029 (token broker);
  the tenant's own secrets & credentials management ADR (private tenant repository);
  the tenant's portal credential-vault handoff of 2026-06-07 (private tenant repository)

## Table of Contents

- [Context](#context)
- [Decision](#decision)
- [Why Transit, not the existing static field key](#why-transit-not-the-existing-static-field-key)
- [Isolation model](#isolation-model)
- [Operator procedure](#operator-procedure)
- [Tenant contract](#tenant-contract)
- [Consequences](#consequences)
- [Rollback](#rollback)

## Context

The existing tenant secrets model (ADR-0017, ADR-0033) is **operator-managed and
read-only for tenants**: secret material is written into the per-tenant OpenBao KV mount
(`<tenant>/`) by the operator, and tenant pods get a tenant-wide read-only policy
(`<tenant>-reader`) via a wildcard-ServiceAccount Kubernetes auth role. Tenant repos
cannot write secrets, and tenant CI cannot reach the internal-only OpenBao.

A new requirement does not fit that model: the **first tenant's portal** wants to let
the tenant's authenticated business owners **store, share, reveal, and rotate credentials
to external services** (e.g. a single shared streaming-service account, supplier logins)
**at runtime, through the portal UI**. The actors are end users acting through an
in-cluster application, not the operator and not CI. The data is reversibly encrypted (owners must read the actual
password back), shared between owners, and subject to the tenant's secrets ADR's hard
requirement that **every access be individually attributable and revocable**.

This is an **application feature** (a shared-credential vault), not platform deployment
configuration. The credential records belong in the tenant's own application database;
only the secret field needs cryptographic protection.

The portal already ships `CryptoService` + `EncryptedStringConverter`: AES-256-GCM field
encryption using a static base64 key (`FIELD_ENCRYPTION_KEY`) delivered in the
portal's application secret from the tenant's `<tenant>/` KV mount. So
encryption-at-rest is already solved with a static key -- which forces the decision
below.

## Decision

Tenant application-managed secrets use **envelope encryption with the OpenBao Transit
secrets engine**, with the ciphertext stored in the tenant's own database:

1. **Storage** -- the credential record (name, service, username, notes, owner, sharing,
   audit columns) is a row in the tenant's application Postgres. Only the secret value is
   protected.
2. **Encryption** -- the secret value is encrypted/decrypted by OpenBao Transit
   (encryption-as-a-service). Plaintext key material never leaves OpenBao; the
   application holds only ciphertext (`vault:v1:...`) and an OpenBao token.
3. **Authentication** -- the application authenticates to OpenBao with the existing
   Kubernetes auth method, using a **per-app role bound to a single ServiceAccount** --
   not the tenant-wide wildcard role.
4. **Authorization** -- the per-app role carries a policy scoped to **one Transit key**
   (encrypt/decrypt/rewrap only). It cannot manage keys, and cannot touch any other
   tenant's or app's key.
5. **Audit** -- every encrypt/decrypt is recorded by the OpenBao audit device
   (`audit "file" "stdout"`, values-cloud.yaml) and lands in Loki, satisfying the tenant
   secrets ADR's attributability requirement at the platform layer. End-user attribution
   (who triggered a reveal) remains the application's responsibility.

This is the **first per-app/per-ServiceAccount OpenBao scoping** on the platform -- the
concrete first step of the per-app scoping that ADR-0033 deferred to "when apps with
differing secret sensitivity exist." That condition is now met (a shared-credential
vault sits beside onboarding PII).

## Why Transit, not the existing static field key

The portal could simply reuse `CryptoService` (AES-256-GCM with the static
`FIELD_ENCRYPTION_KEY`). Transit is chosen anyway, for reasons specific to this use case:

| Property | Static field key (`CryptoService`) | OpenBao Transit |
|----------|-----------------------------------|-----------------|
| Per-access audit trail | None -- decryption is silent in-pod | Every encrypt/decrypt is an OpenBao audit event -> Loki |
| Key location | Key material sits in the pod env/memory | Key never leaves OpenBao; pod holds only a token |
| Key separation | One key encrypts onboarding PII **and** shared credentials | Independent key per app/purpose |
| Rotation | Re-encrypt all rows in-app; rotating the key touches every encrypted field | `rotate` + `rewrap`, central, no app redeploy, scoped to this key |

The decisive factor is the **per-access audit trail**: the tenant's secrets ADR requires
that access to shared credentials be individually attributable, and only Transit
produces a per-reveal audit record. The independent key (rotating the credential-vault key must not disturb
onboarding PII) and key-never-in-app properties reinforce the choice.

The existing `CryptoService` is **retained as the dev/test fallback**: OpenBao is not
reachable in the `%dev`/`%test` Quarkus profiles, so those profiles encrypt with the
static key while `%prod` uses Transit. This reuses proven code and keeps the local
developer loop free of an OpenBao dependency.

## Isolation model

Consistent with the existing pattern (a single shared `kubernetes/` auth method; the
token broker's policy (ADR-0029) scoped to specific paths within a shared mount):

- **One shared `transit/` engine**, mounted once -- crypto-as-a-service for all tenants.
- **Per-app keys**, named `<tenant>-<app>-credvault` (e.g. `<tenant>-portal-credvault`).
- **Per-key policies**, named identically, granting `update` on
  `transit/encrypt/<key>`, `transit/decrypt/<key>`, `transit/rewrap/<key>` -- and nothing
  else. A `<tenant>-portal` token cannot decrypt another tenant's or app's key.
- **Per-app Kubernetes auth roles**, named `<tenant>-<app>`, bound to exactly one
  ServiceAccount (`bound_service_account_names=<sa>`) in `<tenant>-prod,<tenant>-dev`,
  carrying only the per-key policy, `ttl=1h`.

Transit keys live in Raft and are therefore covered by the existing OpenBao backup story
(`raft-backup-cronjob` + Velero fs-backup of the PVC). They are created non-exportable
with no plaintext backup, so a Raft snapshot never contains extractable key material.

## Operator procedure

Provisioned by the parameterized subcommands added to
`infrastructure/clusters/cloud/scripts/setup-openbao-k8s-auth.sh`:

```bash
cd infrastructure/clusters/cloud/scripts
: "${TENANT:?set TENANT to the tenant name}"   # stops here until TENANT is set
# Enable the shared Transit engine (idempotent; also done implicitly by create-app-vault):
./setup-openbao-k8s-auth.sh enable-transit
# Provision the per-app vault: Transit key + key-scoped policy + per-SA auth role:
./setup-openbao-k8s-auth.sh create-app-vault "$TENANT" portal portal
# Verify:
./setup-openbao-k8s-auth.sh status-app-vault "$TENANT" portal
```

`create-app-vault <tenant> <app> <sa>` creates the key `<tenant>-<app>-credvault`, the
identically named policy, and the `<tenant>-<app>` role bound to ServiceAccount `<sa>`.

## Tenant contract

What a tenant application can rely on after provisioning (for `<tenant>`/`portal`/SA
`portal`):

- **OpenBao address (in-cluster):** `http://openbao.platform-ops.svc:8200`
- **Login:** `POST /v1/auth/kubernetes/login` with `{"role":"<tenant>-portal","jwt":"<SA token>"}`
- **Encrypt:** `POST /v1/transit/encrypt/<tenant>-portal-credvault` with
  `{"plaintext":"<base64(secret)>"}` -> `{"data":{"ciphertext":"vault:v1:..."}}`
- **Decrypt:** `POST /v1/transit/decrypt/<tenant>-portal-credvault` with
  `{"ciphertext":"vault:v1:..."}` -> `{"data":{"plaintext":"<base64(secret)>"}}`
- The application stores the `vault:v1:...` ciphertext in its own database and never
  persists plaintext.

## Consequences

**Positive**

- Tenants get a runtime, user-driven secret store without exposing OpenBao externally or
  writing secrets to Git -- the in-cluster app is the writer.
- Per-access audit satisfies the tenant secrets ADR's attributability; key separation
  and central rotation are gained for free.
- Establishes the reusable per-app OpenBao scoping primitive (`create-app-vault`) that
  ADR-0033 deferred; tenant #2 is a one-line invocation.

**Negative**

- The feature has a **runtime dependency on OpenBao** for every encrypt/decrypt. Mitigated:
  OpenBao is in-cluster, auto-unsealed (values-cloud.yaml sidecar), and reveals are
  low-volume interactive actions. Dev/test sidestep it via the static-key fallback.
- One more crypto mechanism in the portal (Transit in prod, `CryptoService` in dev/test);
  the two must agree on the stored-format discriminator so rows are decryptable by the
  right path.
- OpenBao audit gives "the portal app decrypted key X at time T"; mapping that to the
  end user still requires application-side audit logging.

## Rollback

Delete the per-app role, policy, and Transit key
(`bao delete auth/kubernetes/role/<tenant>-<app>`, `bao policy delete <key>`,
`bao delete transit/keys/<key>` after disabling deletion protection if set). The shared
`transit/` engine may remain (harmless if unused). The tenant application falls back to
the static-key `CryptoService` path, or the feature is removed. Note: deleting the
Transit key makes all ciphertext encrypted under it permanently unreadable -- export and
re-encrypt the credential rows first if the data must survive.
