package dk.manyfold.accounting.integrations;

import jakarta.ws.rs.WebApplicationException;

/**
 * A rejection the connector itself decided, whose message is authored here and therefore safe to
 * return to the caller verbatim (ADR-0043): an {@code expectedTotal} guard, an idempotency
 * conflict, a malformed argument, the rate limiter, the inert-503 posture.
 *
 * <p>These messages are the actionable half of a refusal and were previously discarded -- the tool
 * printed the status alone, so "expectedTotal 136.00 does not match the created purchase voucher
 * total 140.00 DKK -- the draft was deleted" reached the agent as a bare {@code HTTP 409}. Two of
 * the dropped messages were outright dangerous: one reports that a wrong-value draft could NOT be
 * cleaned up and is stranded in Dinero, and one warns that a vendor write SUCCEEDED but was not
 * recorded, so retrying would double-post a real financial document.
 *
 * <p>Safe by construction: every message is a literal composed here, over values the caller
 * supplied (amounts, ids, file names) or totals it is entitled to read back. No vendor body, no
 * credential, no PII. A vendor's own rejection is {@link VendorWriteException} instead, which
 * carries the true upstream status and is relayed only for a 4xx.
 */
public class GuardrailException extends WebApplicationException {

  public GuardrailException(String message, int status) {
    super(message, status);
  }

  /** The cause is kept for the server log; the caller sees only {@code message}. */
  public GuardrailException(String message, int status, Throwable cause) {
    super(message, cause, status);
  }
}
