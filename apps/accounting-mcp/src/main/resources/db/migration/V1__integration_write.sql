-- Scoped integration write ledger + audit (ADR-0043). One row per
-- (vendor, operation, idempotency_key); the UNIQUE constraint is the atomic claim/dedupe guarantee.
-- request_hash binds a key to its request body; version is the optimistic lock for concurrent
-- re-claims. The row doubles as the durable write-audit (actor, NON-IDENTIFYING inputs summary,
-- vendor status, created id, error). NEVER store customer names / card data / identifying PII here.
CREATE TABLE integration_write (
  id               UUID PRIMARY KEY,
  vendor           TEXT NOT NULL,
  operation        TEXT NOT NULL,
  idempotency_key  TEXT NOT NULL,
  request_hash     TEXT NOT NULL,
  actor            TEXT NOT NULL,
  inputs_summary   TEXT,
  status           TEXT NOT NULL DEFAULT 'PENDING',
  vendor_status    INT,
  vendor_id        TEXT,
  error            TEXT,
  version          BIGINT NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_integration_write_key UNIQUE (vendor, operation, idempotency_key)
);
CREATE INDEX idx_integration_write_vendor_op ON integration_write (vendor, operation);
