package dk.manyfold.accounting.integrations;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Audit logging for the Dinero integration (ADR-0043 guardrail): every call is attributable to a
 * caller subject, with the path and resulting status. The passthrough can read debitor/creditor
 * PII, so this trail is mandatory.
 *
 * <p>Emitted to the dedicated {@code dk.manyfold.accounting.integrations.audit} category so it can
 * be routed/retained separately. Structured logging is the day-one control; a durable audit store
 * for reads is a deliberate follow-up (writes already persist the durable integration_write
 * ledger).
 */
@ApplicationScoped
public class IntegrationAudit {

  private static final Logger LOG = Logger.getLogger("dk.manyfold.accounting.integrations.audit");
  private static final int MAX_PATH = 512;
  private static final int MAX_KEY = 128;
  private static final int MAX_LABEL = 64;
  private static final Pattern UNSAFE_LABEL = Pattern.compile("[^A-Za-z0-9._:-]");

  /**
   * Record one passthrough call. Called for every request, including rejections (the {@code status}
   * carries the rejection code: 401/403 from the role gate, 400/429/502/503 etc.); an
   * unauthenticated caller's subject is {@code anonymous}.
   */
  public void record(
      String vendor,
      String subject,
      String method,
      String path,
      int status,
      long bytes,
      long durationMs) {
    LOG.infof(
        "integration-access vendor=%s subject=%s method=%s path=/%s status=%d bytes=%d durationMs=%d",
        label(vendor), subject, method, sanitize(path), status, bytes, durationMs);
  }

  /**
   * Record one scoped write (ADR-0043) to the shared audit category. NEVER logs the inputs (which
   * may carry PII) -- only op, the bounded/sanitized idempotency key, vendor status and created id.
   * Also called for a write the writer gate refused (issue #503): {@code vendorStatus} is then the
   * refusal (401 unauthenticated, 403 without the role) and there is no key or id.
   */
  public void recordWrite(
      String vendor,
      String actor,
      String operation,
      String idempotencyKey,
      int vendorStatus,
      String vendorId,
      boolean deduplicated,
      long durationMs) {
    LOG.infof(
        "integration-write vendor=%s actor=%s op=%s key=%s status=%d id=%s dedup=%s durationMs=%d",
        label(vendor),
        actor,
        label(operation),
        sanitizeKey(idempotencyKey),
        vendorStatus,
        vendorId == null ? "-" : vendorId,
        deduplicated,
        durationMs);
  }

  private static String sanitizeKey(String key) {
    if (key == null) {
      return "-";
    }
    String k = key.replaceAll("[\\r\\n\\t ]", "_");
    return k.length() > MAX_KEY ? k.substring(0, MAX_KEY) + "..." : k;
  }

  /**
   * One bounded token of the key charset. The vendor is the caller's own word until a lookup
   * resolves it, and a refused call is recorded before any lookup.
   */
  static String label(String value) {
    if (value == null) {
      return "-";
    }
    String v = UNSAFE_LABEL.matcher(value).replaceAll("_");
    return v.length() > MAX_LABEL ? v.substring(0, MAX_LABEL) + "..." : v;
  }

  /** Keep the audit line single-line and bounded -- the path is attacker-influenced. */
  private static String sanitize(String path) {
    if (path == null) {
      return "";
    }
    String p = path.replaceAll("[\\r\\n\\t]", "_");
    return p.length() > MAX_PATH ? p.substring(0, MAX_PATH) + "..." : p;
  }
}
