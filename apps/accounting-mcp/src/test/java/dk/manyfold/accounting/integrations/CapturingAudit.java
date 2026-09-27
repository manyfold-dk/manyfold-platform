package dk.manyfold.accounting.integrations;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the writes the audit is asked to record instead of logging them, or refuses to record them.
 * Thread-safe: an MCP tool call records on a worker thread, and the test reads on its own.
 */
public class CapturingAudit extends IntegrationAudit {

  /** One recorded write, without its timing. */
  public record Write(
      String vendor, String actor, String operation, String key, int status, String vendorId) {}

  private final List<Write> writes = new CopyOnWriteArrayList<>();
  private final boolean failing;

  private CapturingAudit(boolean failing) {
    this.failing = failing;
  }

  /** An audit that records. */
  public static CapturingAudit recording() {
    return new CapturingAudit(false);
  }

  /** An audit whose every write throws, as a broken log handler would. */
  public static CapturingAudit failing() {
    return new CapturingAudit(true);
  }

  /** The writes recorded so far, oldest first. */
  public List<Write> writes() {
    return List.copyOf(writes);
  }

  @Override
  public void recordWrite(
      String vendor,
      String actor,
      String operation,
      String idempotencyKey,
      int vendorStatus,
      String vendorId,
      boolean deduplicated,
      long durationMs) {
    if (failing) {
      throw new IllegalStateException("audit unavailable");
    }
    writes.add(new Write(vendor, actor, operation, idempotencyKey, vendorStatus, vendorId));
  }
}
