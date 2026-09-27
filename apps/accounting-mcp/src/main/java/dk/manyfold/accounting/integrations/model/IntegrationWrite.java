package dk.manyfold.accounting.integrations.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable idempotency ledger + audit for one scoped integration write (ADR-0043). The {@code
 * UNIQUE(vendor, operation, idempotency_key)} constraint is the atomic claim/dedupe guarantee;
 * {@link #requestHash} binds the key to the request body (a reused key with a different body is
 * rejected); the row doubles as the write-audit (actor, op, non-PII summary, vendor status, id,
 * error).
 *
 * <p>NEVER store card data or identifying PII (e.g. customer names) here -- {@link #inputsSummary}
 * is a coarse, non-identifying description only (counts / pseudonymous refs / amounts).
 */
@Entity
@Table(
    name = "integration_write",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_integration_write_key",
            columnNames = {"vendor", "operation", "idempotency_key"}))
public class IntegrationWrite extends PanacheEntityBase {

  @Id public UUID id = UUID.randomUUID();

  @Column(nullable = false)
  public String vendor;

  @Column(nullable = false)
  public String operation;

  @Column(name = "idempotency_key", nullable = false)
  public String idempotencyKey;

  @Column(name = "request_hash", nullable = false)
  public String requestHash;

  @Column(nullable = false)
  public String actor;

  @Column(name = "inputs_summary")
  public String inputsSummary;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public IntegrationWriteStatus status = IntegrationWriteStatus.PENDING;

  @Column(name = "vendor_status")
  public Integer vendorStatus;

  @Column(name = "vendor_id")
  public String vendorId;

  @Column public String error;

  @Version
  @Column(nullable = false)
  public long version;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt = Instant.now();
}
