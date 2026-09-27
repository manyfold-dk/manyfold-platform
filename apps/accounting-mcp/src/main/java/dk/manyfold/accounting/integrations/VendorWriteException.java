package dk.manyfold.accounting.integrations;

import jakarta.ws.rs.WebApplicationException;

/**
 * Thrown by a {@link VendorWriter} when an upstream vendor write fails after the request reached
 * the vendor (ADR-0043). It carries the <b>true upstream HTTP status</b> so the durable
 * ledger/audit can distinguish a client-side reject (400/403) from rate-limiting (429) from an
 * outage (5xx): {@link IntegrationWriteExecutor} persists {@link #upstreamStatus()} as {@code
 * vendor_status} on the FAILED row.
 *
 * <p>The client-facing response is a {@code 502 Bad Gateway} -- an upstream failure is not the
 * caller's fault, so we never reflect the vendor's 4xx back as the caller's own status. Local
 * failures (rate limit, inert-503, host-pin, a connection that never opened, a 2xx answer the
 * connector cannot use) are NOT vendor failures: they are a {@link GuardrailException} with their
 * own status instead of this type, so the ledger records no vendor status for them.
 *
 * <p><b>The status alone is not enough to act on.</b> Pinning the client-facing status at 502 once
 * cost a whole booking session (2026-08-02): two ordinary Dinero {@code 400 Validation Error}
 * responses -- "PaymentDate should not be set on cash purchases" and "The Timestamp field is
 * required." -- were indistinguishable from an outage, so a fixable request body was reported as a
 * vendor incident. The exception therefore also carries {@link #vendorDetail()}: the vendor's own
 * bounded rejection reason, which callers may surface for a vendor <b>4xx</b> (a rejection of the
 * request the caller itself just sent) but not for a 5xx (vendor internals).
 */
public class VendorWriteException extends WebApplicationException {

  private final int upstreamStatus;
  private final String vendorDetail;

  public VendorWriteException(int upstreamStatus, String message) {
    this(upstreamStatus, message, null, null);
  }

  public VendorWriteException(int upstreamStatus, String message, String vendorDetail) {
    this(upstreamStatus, message, vendorDetail, null);
  }

  protected VendorWriteException(
      int upstreamStatus, String message, String vendorDetail, Throwable cause) {
    super(message, cause, 502);
    this.upstreamStatus = upstreamStatus;
    this.vendorDetail = vendorDetail;
  }

  /** The real HTTP status the vendor returned (e.g. 400/403/409/429/500). */
  public int upstreamStatus() {
    return upstreamStatus;
  }

  /**
   * The vendor's short, bounded reason for rejecting the write (e.g. "PaymentDate: PaymentDate
   * should not be set on cash purchases"), or {@code null} when the vendor gave none in a form we
   * can safely condense. Only meaningful for a vendor 4xx.
   */
  public String vendorDetail() {
    return vendorDetail;
  }
}
