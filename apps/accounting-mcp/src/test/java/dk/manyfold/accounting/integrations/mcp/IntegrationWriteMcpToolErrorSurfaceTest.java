package dk.manyfold.accounting.integrations.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.manyfold.accounting.integrations.GuardrailException;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteAction;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteOutcome;
import dk.manyfold.accounting.integrations.VendorWriteException;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

/**
 * How a failed write is reported back to the MCP caller.
 *
 * <p>Regression cover for the 2026-08-02 phantom outage: every vendor write failure -- including a
 * plain Dinero 400 naming the offending field -- reached the agent as an indistinguishable {@code
 * HTTP 502}, because {@link VendorWriteException} pins its client-facing status at 502 and the tool
 * printed only that status. The agent read "502" as an upstream outage and stopped, when Dinero had
 * in fact answered "PaymentDate should not be set on cash purchases". A vendor 4xx is a rejection
 * of the caller's OWN request, so its reason must travel back; a vendor 5xx is an upstream fault
 * whose body is vendor internals, so it stays opaque.
 */
class IntegrationWriteMcpToolErrorSurfaceTest {

  private static IntegrationWriteMcpTool toolFailingWith(RuntimeException failure) {
    IntegrationWriteMcpTool tool = new IntegrationWriteMcpTool();
    tool.mapper = new ObjectMapper();
    tool.executor =
        new IntegrationWriteExecutor(null, null) {
          @Override
          public WriteOutcome execute(
              String vendor,
              String operation,
              String idempotencyKey,
              String requestHash,
              String inputsSummary,
              WriteAction action) {
            throw failure;
          }
        };
    return tool;
  }

  private static String messageOf(RuntimeException failure) {
    ToolCallException thrown =
        assertThrows(
            ToolCallException.class,
            () ->
                toolFailingWith(failure)
                    .run(
                        "dinero", "create-purchase-voucher", "key", "hash", "summary", () -> null));
    return thrown.getMessage();
  }

  @Test
  void vendorValidationRejectionReachesTheCallerWithItsReason() {
    assertEquals(
        "dinero/create-purchase-voucher rejected by vendor (HTTP 400):"
            + " PaymentDate: PaymentDate should not be set on cash purchases",
        messageOf(
            new VendorWriteException(
                400,
                "dinero v1.2/50096/vouchers/purchase returned 400",
                "PaymentDate: PaymentDate should not be set on cash purchases")));
  }

  /** A genuine upstream fault must NOT leak the vendor's error page back to the agent. */
  @Test
  void vendorOutageStaysOpaque() {
    assertEquals(
        "dinero/create-purchase-voucher rejected (HTTP 502)",
        messageOf(
            new VendorWriteException(
                503, "dinero returned 503", "<html><body>Service Unavailable</body></html>")));
  }

  /** No parsable reason still beats today's behaviour: the TRUE upstream status gets named. */
  @Test
  void vendorRejectionWithoutAParsableReasonStillNamesTheTrueStatus() {
    assertEquals(
        "dinero/create-purchase-voucher rejected by vendor (HTTP 429)",
        messageOf(new VendorWriteException(429, "dinero returned 429", null)));
  }

  /**
   * Local guardrails never reached the vendor, so they keep their OWN status -- and, being messages
   * this codebase authored over the caller's own values, they carry that message through. Without
   * it "expectedTotal 136.00 does not match ... 140.00" is just a bare 409.
   */
  @Test
  void localGuardrailKeepsItsOwnStatusAndCarriesItsMessage() {
    assertEquals(
        "dinero/create-purchase-voucher rejected (HTTP 409): expectedTotal 136.00 does not match"
            + " the created purchase voucher total 140.00 DKK -- the draft was deleted",
        messageOf(
            new GuardrailException(
                "expectedTotal 136.00 does not match the created purchase voucher total 140.00 DKK"
                    + " -- the draft was deleted",
                409)));
  }

  /**
   * The most dangerous message in the service: the financial document EXISTS at the vendor but was
   * not recorded. An agent that saw only "HTTP 500" would retry and double-post it.
   */
  @Test
  void doNotRetryWarningReachesTheCaller() {
    assertEquals(
        "dinero/create-purchase-voucher rejected (HTTP 500): vendor write succeeded but recording"
            + " it failed; do NOT retry -- reconcile with the vendor",
        messageOf(
            new GuardrailException(
                "vendor write succeeded but recording it failed; do NOT retry -- reconcile with the"
                    + " vendor",
                500)));
  }

  /** An unexpected framework WebApplicationException stays opaque -- provenance unknown. */
  @Test
  void unknownWebApplicationExceptionStaysOpaque() {
    assertEquals(
        "dinero/create-purchase-voucher rejected (HTTP 409)",
        messageOf(new WebApplicationException("something internal", 409)));
  }
}
