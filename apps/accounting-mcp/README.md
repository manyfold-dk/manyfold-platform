# accounting-mcp

Quarkus service that fronts the [Dinero](https://dinero.dk) accounting API for one
organisation and exposes it to Cowork/Claude as an OAuth 2.1 MCP connector. See
ADR-0043 (not yet published).

## Table of Contents

- [Architecture](#architecture)
- [Endpoints](#endpoints)
- [MCP tools](#mcp-tools)
- [Receipt ingestion](#receipt-ingestion)
- [Configuration](#configuration)
- [Local development](#local-development)
- [Guardrails](#guardrails)
- [Live verification](#live-verification)

## Architecture

Three front doors over one in-process integration layer:

- **MCP connector** `/mcp` (Streamable HTTP) -- the agent front door for Cowork/Claude.
- **REST passthrough** `/api/integrations/dinero/**` -- GET/HEAD only, kept internal for now.
- **Receipt upload** `/api/integrations/dinero/files` -- named multipart write resource, exposed
  publicly but bearer- and writer-gated.

The MCP read tool and REST passthrough delegate to `IntegrationGateway`, which owns the generic
read guardrails (role gate, vendor allowlist, read-only method policy, audit). The upload resource
does not relax that read-only gateway: like MCP writes, it goes directly through
`IntegrationWriteExecutor` (atomic idempotency ledger + audit) to a named `DineroWriter` operation.
Per-vendor credential injection and host pinning live in `DineroProxyClient`.

## Endpoints

| Path | Purpose | Auth |
|------|---------|------|
| `/mcp`, `/mcp/*` | MCP Streamable HTTP transport | Bearer (OIDC `mcp` tenant, `aud=accounting-mcp`) |
| `/.well-known/oauth-protected-resource/mcp` | RFC 9728 protected-resource metadata | Public |
| `/api/integrations/dinero/**` | REST read passthrough (GET/HEAD) | Bearer (default OIDC tenant) |
| `POST /api/integrations/dinero/files` | Multipart receipt upload (`file` part) | Bearer (`aud=accounting-mcp`) + `integration-writer` |
| `/q/health/live`, `/q/health/ready` | Health probes | Public |
| `/q/metrics` | Prometheus metrics | Public |

## MCP tools

- `integration_get(service, path, query)` -- read-only Dinero passthrough (gated on
  `integration-reader`). The `{organizationId}` placeholder in `path` is substituted with the
  configured FirmaId server-side.
- Named write tools (gated on `integration-writer`) -- `dinero_upsert_contact`,
  `dinero_create_invoice_draft`, `dinero_book_invoice`, `dinero_create_purchase_voucher`,
  `dinero_create_manual_voucher`, `dinero_book_purchase_voucher`, and
  `dinero_register_purchase_payment`.

The purchase lifecycle is:

1. Upload each receipt with `./scripts/dinero-upload.sh`; retain the printed `FileGuid`.
2. Call `dinero_create_purchase_voucher` and put that guid in the body's `FileGuid` field.
   `PurchaseType` is required. Foreign-currency purchases must be `credit`; do not set `CurrencyKey`
   for `cash` purchases, and use `RegionKey` only for `cash` purchases.
3. Call `dinero_book_purchase_voucher` with the draft guid and its own-currency
   `VoucherTotals[Type=Total].Total`. Booking is irreversible; correction uses a credit note.
4. For a credit purchase, call `dinero_register_purchase_payment` with Dinero's documented payment
   body. `Amount` and `expected_amount` are DKK; foreign-currency payments also carry
   `AmountInForeignCurrency` so Dinero can calculate the rate difference.

## Receipt ingestion

The uploader is an operator script of the instance repository -- the private repository that
holds an installation's configuration and credentials -- because it reads that installation's
agent credentials. Run it from that repository's root with one or more PDF, PNG, or JPEG
receipts:

```bash
./scripts/dinero-upload.sh ~/path/to/receipt.pdf [more-receipts ...]
```

The script sources the gitignored root `.env.agents`, performs the Keycloak password-grant token
exchange as `claude-agent`, and uploads each file as multipart form part `file`. It derives a stable
idempotency key from the file's SHA-256 digest, so retrying the same bytes does not create a second
vendor upload. `ACCOUNTING_MCP_URL` overrides the script's default service URL.

Successful output contains no receipt bytes or credentials -- one line per file only:

```text
receipt.pdf 00000000-0000-0000-0000-000000000000
```

The receipt byte path is disk -> uploader -> accounting-mcp -> Dinero. The agent only consumes the
returned `FileGuid`, which it can bind with `dinero_create_purchase_voucher`.

## Configuration

Credentials come from the environment (12-factor); blank keeps the service inert (503):

| Env var | Purpose |
|---------|---------|
| `DINERO_CLIENT_ID` / `DINERO_CLIENT_SECRET` | Personal-integration client credentials (Basic auth) |
| `DINERO_API_KEY` | Organization API key (password grant username + password) |
| `DINERO_ORGANIZATION_ID` | OrganizationId / FirmaId of the organisation |
| `DINERO_BASE_URL` / `DINERO_AUTH_URL` | Overridable API base + token endpoint |
| `DB_URL`, or in production `DB_HOST` / `DB_NAME` / `DB_PORT` (5432); `DB_USERNAME` / `DB_PASSWORD` | Postgres for the write ledger. Dev mode reads `DB_URL` only; unset, Dev Services start a database |
| `OIDC_AUTH_SERVER_URL` / `MCP_TOKEN_AUDIENCE` | Keycloak realm + MCP audience |

## Local development

```bash
cd apps/accounting-mcp
mvn quarkus:dev          # OIDC disabled in %dev; a config identity holds both integration roles
mvn verify               # tests, Checkstyle and the format check (google-java-format)
mvn spotless:apply       # format; verify only checks
```

Dinero creds are read from the gitignored root `.env.cloud` (`DINERO_*`). The write ledger needs a
Postgres (Quarkus Dev Services starts one automatically when Docker is available).

## Guardrails

Read-only reads (GET/HEAD), named write allowlist (no generic write), idempotency key on every
write, `expectedTotal` guard on voucher creation and before booking (creation reads the draft back
and deletes it + 409s on mismatch, so a silent-wrong-value draft is never stranded), durable audit
ledger, host-pinned upstream, response size cap, rate limit below the vendor's 60 req/min, and
server-side credential injection (tokens never reach the client).

A write whose outcome at the vendor is unknown -- a 5xx, a 2xx whose answer cannot be read, a
connection lost after sending, a created draft that cannot be read back or removed -- is never
marked failed: its ledger row stays PENDING, so a retry with the same key answers 409 instead of
posting a second financial document. The caller is told to reconcile with the vendor. Only a
vendor 4xx, a local refusal or a connection that never opened counts as a clean failure a retry
may repeat. An upload's idempotency key binds its bytes and its claimed name and type.

### Single replica is a correctness constraint, not a capacity choice

`DineroRateLimiter` is one shared in-JVM sliding-window limit (55 calls in any 60 seconds) covering **both** the
read proxy and the writer, sized against Dinero's 60 req/min personal-integration cap. It holds no
shared state, so the cap is **per replica**: running two pods means 110 req/min and Dinero starts
returning 429 under load. Nothing detects this at deploy time.

So `replicas` stays **1** and there is **no HPA**. Scaling out requires moving the limiter to
shared state (Redis, or the existing in-namespace Postgres) first. For reference, the busiest
session to date (2026-08-02, a full expense backlog) peaked at ~18 tool calls -- roughly 25-30
vendor requests, since creation and booking each make two -- in a 60s window, about half the cap.
Headroom is not the constraint; correctness of the cap is.

## Live verification

Unit tests mock Dinero and therefore cannot catch "the vendor silently ignores this field"
(see BEST-PRACTICES.md, Vendor Integrations). After any change to the write path, run the live
end-to-end harness, an operator script in the instance repository, before handing back:

```bash
./scripts/verify-dinero-mcp.sh   # from the instance repository's root
```

It runs the whole receipt->voucher lifecycle against the production org using a pre-verified real
invoice fixture, asserts read-back semantics (exact byte count, own-currency totals, guard 409s,
no stranded drafts, exact MCP tool surface), stops at the first failure, and books the fixture
invoice only after the read-back proves the draft total. Requires `.env.cloud` (`DINERO_*`) and
`.env.agents` (agent Keycloak login). The payment leg needs an optional second fixture.

The authoritative Dinero request models come from the machine-readable spec at
`https://api.dinero.dk/openapi/v1/swagger.json` -- never guess field names from the rendered docs;
the create-line amount field is `Amount` (read-model names are silently discarded).
