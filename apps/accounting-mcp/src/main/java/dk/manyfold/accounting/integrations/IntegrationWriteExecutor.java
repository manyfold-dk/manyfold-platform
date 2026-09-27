package dk.manyfold.accounting.integrations;

import dk.manyfold.accounting.integrations.model.IntegrationWrite;
import dk.manyfold.accounting.integrations.model.IntegrationWriteStatus;
import dk.manyfold.accounting.security.IdentityResolver;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The single cross-cutting envelope for every scoped integration write (ADR-0043): role gate
 * ({@code integration-writer}), atomic idempotency claim and durable audit. The vendor HTTP call
 * runs <b>outside</b> any DB transaction.
 *
 * <p>Concurrency/idempotency model: a fresh {@code PENDING} row is inserted under the {@code
 * UNIQUE(vendor, operation, idempotency_key)} constraint (only one caller wins the claim); a losing
 * insert resolves the pre-existing row -- a request-hash mismatch is a 409, a prior {@code SUCCESS}
 * dedupes (returns the earlier result), a prior {@code FAILED} is re-claimed for retry, and a fresh
 * {@code PENDING} is rejected 409 as in-flight. The FAILED row records the TRUE upstream status so
 * the ledger distinguishes a vendor reject from rate-limiting from an outage.
 */
@ApplicationScoped
public class IntegrationWriteExecutor {

  private static final String UNKNOWN_NOTE = "outcome unknown: ";
  private static final Duration STALE_PENDING = Duration.ofMinutes(5);
  private static final String UNKNOWN_WARNING =
      "The outcome at the vendor is unknown; do NOT retry -- check the vendor and reconcile."
          + " This key stays reserved.";

  private final IdentityResolver identity;
  private final IntegrationAudit audit;

  @Inject
  public IntegrationWriteExecutor(IdentityResolver identity, IntegrationAudit audit) {
    this.identity = identity;
    this.audit = audit;
  }

  /** The vendor HTTP call. Returns upstream status + created id on success; throws on failure. */
  @FunctionalInterface
  public interface WriteAction {
    WriteResult run();
  }

  public record WriteResult(int vendorStatus, String vendorId) {}

  public record WriteOutcome(
      boolean deduplicated, boolean success, int vendorStatus, String vendorId, String error) {}

  /**
   * Fails fast on the writer role before a tool performs expensive request decoding. The full write
   * still goes through {@link #execute} so authorization, actor attribution, idempotency, and audit
   * remain one envelope.
   */
  public void requireWriter() {
    identity.requireIntegrationWriter();
  }

  private enum ClaimKind {
    OWNED,
    DEDUP,
    CONFLICT
  }

  private record Claim(
      ClaimKind kind,
      UUID rowId,
      String vendorId,
      int vendorStatus,
      String message,
      boolean outcomeUnknown) {
    static Claim owned(UUID id) {
      return new Claim(ClaimKind.OWNED, id, null, 0, null, false);
    }

    static Claim dedup(String vid, int vs) {
      return new Claim(ClaimKind.DEDUP, null, vid, vs, null, false);
    }

    static Claim conflict(String msg) {
      return new Claim(ClaimKind.CONFLICT, null, null, 0, msg, false);
    }

    static Claim unknownOutcome(String msg) {
      return new Claim(ClaimKind.CONFLICT, null, null, 0, msg, true);
    }
  }

  /** User-driven write: gates {@code integration-writer} and attributes to the caller subject. */
  public WriteOutcome execute(
      String vendor,
      String operation,
      String idempotencyKey,
      String requestHash,
      String inputsSummary,
      WriteAction action) {
    return execute(vendor, operation, idempotencyKey, requestHash, null, inputsSummary, action);
  }

  /**
   * As {@link #execute(String, String, String, String, String, WriteAction)}, also accepting an
   * earlier form of the request hash as the same request: for a key recorded before its hash gained
   * more of the request, so a retry after the upgrade still deduplicates.
   */
  public WriteOutcome execute(
      String vendor,
      String operation,
      String idempotencyKey,
      String requestHash,
      String legacyRequestHash,
      String inputsSummary,
      WriteAction action) {
    String actor = identity.requireIntegrationWriter().sub();
    return run(
        vendor,
        operation,
        idempotencyKey,
        requestHash,
        legacyRequestHash,
        actor,
        inputsSummary,
        action);
  }

  private WriteOutcome run(
      String vendor,
      String operation,
      String key,
      String hash,
      String legacyHash,
      String actor,
      String inputsSummary,
      WriteAction action) {
    IntegrationWriteKeys.validateKey(key);
    long start = System.nanoTime();

    Claim claim;
    try {
      UUID rowId =
          QuarkusTransaction.requiringNew()
              .call(() -> insertClaim(vendor, operation, key, hash, actor, inputsSummary));
      claim = Claim.owned(rowId);
    } catch (RuntimeException insertRace) {
      // The row already exists (unique violation) and the failed tx is poisoned -- resolve in a
      // fresh transaction. A lost optimistic re-claim surfaces here too; treat it as in-flight.
      try {
        claim =
            QuarkusTransaction.requiringNew()
                .call(() -> resolveExisting(vendor, operation, key, hash, legacyHash));
      } catch (RuntimeException resolveRace) {
        resolveRace.addSuppressed(insertRace);
        audit.recordWrite(vendor, actor, operation, key, 409, null, false, millis(start));
        throw new GuardrailException(
            "a write with this Idempotency-Key is already in progress", 409, resolveRace);
      }
    }

    switch (claim.kind()) {
      case DEDUP -> {
        audit.recordWrite(
            vendor,
            actor,
            operation,
            key,
            claim.vendorStatus(),
            claim.vendorId(),
            true,
            millis(start));
        return new WriteOutcome(true, true, claim.vendorStatus(), claim.vendorId(), null);
      }
      case CONFLICT -> {
        audit.recordWrite(vendor, actor, operation, key, 409, null, false, millis(start));
        throw claim.outcomeUnknown()
            ? new GuardrailException(
                claim.message(), 409, new VendorOutcomeUnknownException(null, claim.message()))
            : new GuardrailException(claim.message(), 409);
      }
      default -> {
        /* OWNED -- fall through to the vendor call */
      }
    }

    UUID rowId = claim.rowId();

    // Phase 1: the vendor call. A failure HERE means the vendor side effect did NOT happen, so the
    // row is safely marked FAILED (reclaimable -- a later retry with the same key may call the
    // vendor again). The TRUE upstream status is persisted only for a real vendor response
    // (VendorWriteException); local pre-flight guards (inert-503, rate-limit 429, expectedTotal
    // 409, malformed-body 400) never reached the vendor, so vendor_status stays NULL to keep the
    // ledger honest.
    WriteResult res;
    try {
      res = action.run();
    } catch (VendorOutcomeUnknownException unknown) {
      // The document may exist at the vendor. The row stays PENDING -- never FAILED, which a
      // same-key retry could reclaim and post a second time -- and records why, for whoever
      // reconciles it. A retry with this key is refused with the same warning until then.
      String note = bound(UNKNOWN_NOTE + unknown.getMessage());
      try {
        QuarkusTransaction.requiringNew()
            .run(
                () ->
                    finalizeRow(
                        rowId, IntegrationWriteStatus.PENDING, unknown.vendorStatus(), null, note));
      } catch (RuntimeException ledgerFailure) {
        unknown.addSuppressed(ledgerFailure); // the row is PENDING either way
      }
      int auditStatus = unknown.vendorStatus() == null ? 502 : unknown.vendorStatus();
      audit.recordWrite(vendor, actor, operation, key, auditStatus, null, false, millis(start));
      throw new GuardrailException(unknown.getMessage() + ". " + UNKNOWN_WARNING, 502, unknown);
    } catch (RuntimeException ex) {
      Integer vendorStatus = vendorStatus(ex);
      int effectiveStatus = effectiveStatus(ex);
      String err = failureNote(ex);
      QuarkusTransaction.requiringNew()
          .run(() -> finalizeRow(rowId, IntegrationWriteStatus.FAILED, vendorStatus, null, err));
      audit.recordWrite(vendor, actor, operation, key, effectiveStatus, null, false, millis(start));
      throw ex;
    }

    // Phase 2: the vendor side effect HAS happened. From here we must NEVER mark the row FAILED --
    // a FAILED row is reclaimable and a same-key retry would call the vendor again and duplicate
    // the invoice/voucher/booking (financial double-post). Best-effort finalize SUCCESS; if that DB
    // write throws (e.g. a transient Postgres failure while committing), leave the row PENDING -- a
    // same-key retry then resolves to 409 "in progress", never a second vendor call -- and surface
    // a 500 telling the caller to reconcile with the vendor rather than retry.
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  finalizeRow(
                      rowId,
                      IntegrationWriteStatus.SUCCESS,
                      res.vendorStatus(),
                      res.vendorId(),
                      null));
    } catch (RuntimeException finalizeEx) {
      audit.recordWrite(
          vendor, actor, operation, key, res.vendorStatus(), res.vendorId(), false, millis(start));
      // MUST reach the caller: the financial document EXISTS at the vendor. An agent that reads
      // this as a generic 500 and retries would double-post it.
      throw new GuardrailException(
          "vendor write succeeded but recording it failed; do NOT retry -- reconcile with the vendor",
          500,
          finalizeEx);
    }
    audit.recordWrite(
        vendor, actor, operation, key, res.vendorStatus(), res.vendorId(), false, millis(start));
    return new WriteOutcome(false, true, res.vendorStatus(), res.vendorId(), null);
  }

  /** The vendor's own status, only when the failure is a real vendor response. */
  private static Integer vendorStatus(RuntimeException ex) {
    return ex instanceof VendorWriteException vwe ? vwe.upstreamStatus() : null;
  }

  /** The status the audit records: the vendor's, a framework status, or 500. */
  private static int effectiveStatus(RuntimeException ex) {
    if (ex instanceof VendorWriteException vwe) {
      return vwe.upstreamStatus();
    }
    if (ex instanceof WebApplicationException wae && wae.getResponse() != null) {
      return wae.getResponse().getStatus();
    }
    return 500;
  }

  /**
   * The error note stored on the FAILED row. Only framework exceptions carry a safe message (status
   * codes, not PII). A plain RuntimeException may carry arbitrary user-supplied data in its
   * message, so it is stored by class name only -- enough for diagnosis without risking a
   * PII/secret leak.
   */
  private static String failureNote(RuntimeException ex) {
    return ex instanceof WebApplicationException
        ? bound(ex.getClass().getSimpleName() + ": " + ex.getMessage())
        : bound(ex.getClass().getSimpleName());
  }

  /** Atomic claim: insert a fresh PENDING row. The UNIQUE constraint means only one caller wins. */
  private UUID insertClaim(
      String vendor,
      String operation,
      String key,
      String hash,
      String actor,
      String inputsSummary) {
    IntegrationWrite r = new IntegrationWrite();
    r.vendor = vendor;
    r.operation = operation;
    r.idempotencyKey = key;
    r.requestHash = hash;
    r.actor = actor;
    r.inputsSummary = inputsSummary;
    r.status = IntegrationWriteStatus.PENDING;
    r.persistAndFlush(); // throws on the UNIQUE violation -> caller resolves the existing row
    return r.id;
  }

  /** Resolve a pre-existing row for this key (fresh tx). */
  private Claim resolveExisting(
      String vendor, String operation, String key, String hash, String legacyHash) {
    IntegrationWrite row =
        IntegrationWrite.find(
                "vendor=?1 and operation=?2 and idempotencyKey=?3", vendor, operation, key)
            .firstResult();
    if (row == null) {
      return Claim.conflict("write ledger row vanished after a key collision");
    }
    if (!row.requestHash.equals(hash) && !row.requestHash.equals(legacyHash)) {
      return Claim.conflict("Idempotency-Key reused with a different request");
    }
    return switch (row.status) {
      case SUCCESS -> Claim.dedup(row.vendorId, row.vendorStatus == null ? 200 : row.vendorStatus);
      case FAILED -> {
        row.status = IntegrationWriteStatus.PENDING; // re-claim for retry (@Version guards races)
        row.updatedAt = Instant.now();
        yield Claim.owned(row.id);
      }
      case PENDING ->
          (row.error != null && row.error.startsWith(UNKNOWN_NOTE)) || isStale(row)
              ? Claim.unknownOutcome(
                  "an earlier write with this Idempotency-Key has an unknown outcome at the vendor"
                      + " ("
                      + (row.error != null && row.error.startsWith(UNKNOWN_NOTE)
                          ? row.error.substring(UNKNOWN_NOTE.length())
                          : "the attempt never finished")
                      + "). "
                      + UNKNOWN_WARNING)
              : Claim.conflict("a write with this Idempotency-Key is already in progress");
    };
  }

  /**
   * A PENDING row nobody has touched for longer than any vendor call can take (60 s read timeout)
   * is not in flight: its attempt ended without a recorded outcome, so it is treated as unknown.
   */
  private static boolean isStale(IntegrationWrite row) {
    Instant touched = row.updatedAt != null ? row.updatedAt : row.createdAt;
    return touched != null && touched.isBefore(Instant.now().minus(STALE_PENDING));
  }

  private void finalizeRow(
      UUID rowId,
      IntegrationWriteStatus status,
      Integer vendorStatus,
      String vendorId,
      String error) {
    IntegrationWrite r = IntegrationWrite.findById(rowId);
    if (r == null) {
      return;
    }
    r.status = status;
    r.vendorStatus = vendorStatus;
    r.vendorId = vendorId;
    r.error = error;
    r.updatedAt = Instant.now();
  }

  private static String bound(String s) {
    return s == null ? null : (s.length() > 1000 ? s.substring(0, 1000) : s);
  }

  private static long millis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
