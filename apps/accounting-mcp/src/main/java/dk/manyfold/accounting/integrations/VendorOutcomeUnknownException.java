package dk.manyfold.accounting.integrations;

/**
 * A vendor write whose outcome is not known: the request may have taken effect (a 2xx whose body
 * could not be read, a vendor 5xx, a connection lost after sending, a created draft that could not
 * be read back or removed). {@link IntegrationWriteExecutor} leaves such a ledger row PENDING
 * instead of FAILED, so a same-key retry answers 409 and can never create a second financial
 * document; the caller reconciles with the vendor.
 *
 * <p>The client sees 502. {@link #vendorStatus()} is the status the vendor actually sent, when it
 * sent one (a 5xx); it is null otherwise, so the ledger never records a response that did not
 * happen.
 */
public class VendorOutcomeUnknownException extends VendorWriteException {

  private final Integer vendorStatus;

  public VendorOutcomeUnknownException(Integer vendorStatus, String message) {
    this(vendorStatus, message, null);
  }

  public VendorOutcomeUnknownException(Integer vendorStatus, String message, Throwable cause) {
    super(502, message, null, cause);
    this.vendorStatus = vendorStatus;
  }

  /** The status the vendor sent, or null when no vendor response was read. */
  public Integer vendorStatus() {
    return vendorStatus;
  }
}
