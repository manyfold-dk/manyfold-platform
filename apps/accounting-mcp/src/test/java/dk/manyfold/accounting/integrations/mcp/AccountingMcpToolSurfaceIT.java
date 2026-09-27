package dk.manyfold.accounting.integrations.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.annotation.security.RolesAllowed;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact MCP tool surface (ADR-0043): the read tool {@code integration_get} plus exactly
 * the seven allowlisted Dinero write tools -- no generic write passthrough. A failure here means
 * the agent surface changed: that is a guardrail review, not a flaky test.
 */
@QuarkusTest
class AccountingMcpToolSurfaceIT {

  private static final Set<String> EXPECTED_WRITE_TOOLS =
      Set.of(
          "dinero_upsert_contact",
          "dinero_create_invoice_draft",
          "dinero_book_invoice",
          "dinero_create_purchase_voucher",
          "dinero_create_manual_voucher",
          "dinero_book_purchase_voucher",
          "dinero_register_purchase_payment");

  /** The three irreversible booking tools are the only ones flagged destructive (ADR-0043). */
  private static final Map<String, Boolean> EXPECTED_DESTRUCTIVE =
      Map.ofEntries(
          Map.entry("dinero_upsert_contact", false),
          Map.entry("dinero_create_invoice_draft", false),
          Map.entry("dinero_book_invoice", true),
          Map.entry("dinero_create_purchase_voucher", false),
          Map.entry("dinero_create_manual_voucher", true),
          Map.entry("dinero_book_purchase_voucher", true),
          Map.entry("dinero_register_purchase_payment", false));

  @Test
  @TestSecurity(
      user = "claude-agent",
      roles = {"integration-writer", "integration-reader"})
  void exposesExactlyTheReadToolAndTheAllowlistedWrites() {
    try (McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
      client
          .when()
          .toolsList(
              page -> {
                assertNotNull(page.findByName("integration_get")); // read tool present
                for (String name : EXPECTED_WRITE_TOOLS) {
                  assertNotNull(page.findByName(name), "missing allowlisted write tool: " + name);
                }
                // No generic write passthrough ever leaks to the agent.
                assertTrue(
                    page.tools().stream().noneMatch(t -> "integration_write".equals(t.name())));
                assertTrue(
                    page.tools().stream().noneMatch(t -> "integration_post".equals(t.name())));
                // EXACTLY seven write tools -- every tool except integration_get is a write tool.
                long writeToolCount =
                    page.tools().stream().filter(t -> !"integration_get".equals(t.name())).count();
                assertEquals(
                    7L,
                    writeToolCount,
                    "MCP surface must expose EXACTLY 7 write tools; got "
                        + writeToolCount
                        + " -- add the new tool to EXPECTED_WRITE_TOOLS or remove it from the"
                        + " surface");
              })
          .thenAssertResults();
    }
  }

  @Test
  void destructiveHintsMatchTheBookingPosture() {
    int checked = 0;
    for (Method m : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool t = m.getAnnotation(Tool.class);
      if (t == null) {
        continue;
      }
      Boolean expected = EXPECTED_DESTRUCTIVE.get(t.name());
      assertNotNull(expected, "unexpected write tool on the surface: " + t.name());
      assertEquals(
          expected,
          t.annotations().destructiveHint(),
          "@Tool " + t.name() + " destructiveHint must be " + expected + " (ADR-0043)");
      checked++;
    }
    assertEquals(EXPECTED_DESTRUCTIVE.size(), checked, "did not see every expected write tool");
    // The draft/upsert tools must not be flagged destructive.
    assertFalse(EXPECTED_DESTRUCTIVE.get("dinero_create_invoice_draft"));
  }

  @Test
  void purchaseVoucherDescriptionPinsForeignCurrencyRules() {
    Tool purchaseVoucher = null;
    for (Method method : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool candidate = method.getAnnotation(Tool.class);
      if (candidate != null && "dinero_create_purchase_voucher".equals(candidate.name())) {
        purchaseVoucher = candidate;
        break;
      }
    }

    assertNotNull(purchaseVoucher);
    String description = purchaseVoucher.description();
    assertTrue(description.contains("required field is PurchaseType"));
    // The cash/credit partition, from Dinero's own OpenAPI property descriptions.
    assertTrue(description.contains("CASH ONLY"));
    assertTrue(description.contains("CREDIT ONLY"));
    assertTrue(description.contains("RegionKey (DK/EU/World)"));
    assertTrue(description.contains("foreign-currency purchase must be credit"));
    // The 2026-08-02 rejection: the description previously stated only the credit-side rule
    // ("a credit voucher cannot be booked without PaymentDate"), which was over-generalized to
    // cash purchases. Both directions must now be stated, or the same mistake recurs.
    assertTrue(description.contains("Do NOT set PaymentDate on a cash purchase"));
    assertTrue(description.contains("cannot later be BOOKED without"));
  }

  /**
   * Pins the Phase-0 discovery (Dinero OpenAPI PurchaseVoucherLineCreateModel, 2026-07-19): the
   * create-line amount field is {@code Amount}, read-model names are silently discarded, and the
   * description must warn about it plus document the read-back delete + 409 guard -- the tool
   * description is the only place a calling agent can learn this.
   *
   * <p>Also pins the two operating rules promoted onto this tool from the bookkeeping playbook
   * (plan {@code 2026-07-20-dinero-tool-description-hardening}): the duplicate PRE-FLIGHT that
   * would have caught the double-booked Anthropic invoice KHEXIHSU-0001, and that {@code Amount} is
   * VAT-INCLUSIVE. Both were silently absent for a month because the 2026-08-02 description rewrite
   * ({@code 1c13cd79}) reworked this text without carrying them; these assertions are what stops
   * the next rewrite doing the same.
   */
  @Test
  void purchaseVoucherDescriptionPinsLineModelAndReadBackGuard() {
    Tool purchaseVoucher = null;
    for (Method method : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool candidate = method.getAnnotation(Tool.class);
      if (candidate != null && "dinero_create_purchase_voucher".equals(candidate.name())) {
        purchaseVoucher = candidate;
        break;
      }
    }

    assertNotNull(purchaseVoucher);
    String description = purchaseVoucher.description();
    assertTrue(description.contains("Amount (required decimal"));
    assertTrue(description.contains("AmountExclVatValue"));
    assertTrue(description.contains("\"Amount\":15.24"));
    assertTrue(description.contains("DELETES it and returns 409"));

    // Rule 1: duplicate pre-flight. The Linked array on GET /files is the only signal that a
    // document is already bound to a voucher; idempotency_key cannot see a booking made by
    // another route, so the description must say both.
    assertTrue(description.contains("PRE-FLIGHT"), "must state the duplicate pre-flight");
    assertTrue(description.contains("Linked"), "must name the Linked array");
    assertTrue(
        description.contains("fields=FileGuid,Name,Size,Linked"),
        "must give the files query that returns Linked");
    assertTrue(
        description.contains("it cannot see a"), "must say idempotency_key does not cover this");
    // Trap (b): a Linked entry survives deletion of the voucher it pointed at.
    assertTrue(description.contains("STALE"), "must warn that a Linked entry can be stale");

    // Rule 2: Amount is gross. Invisible on reverse-charge lines (gross == net at 0% VAT), so an
    // agent that learns the model from the EU example alone gets Danish I25 lines wrong.
    assertTrue(description.contains("VAT-INCLUSIVE"), "must state Amount is VAT-inclusive");
    assertTrue(description.contains("GROSS"), "must say which figure to pass");
    assertTrue(
        description.contains("multiple lines"), "must state a voucher may carry multiple lines");
    assertTrue(
        description.contains("momsspecifikation"),
        "must say to take mixed-VAT figures from the invoice, not compute them");
  }

  /**
   * Pins every REQUIRED field of Dinero's PurchaseVoucherCreditPaymentCreateModel (OpenAPI {@code
   * required: [Amount, DepositAccountNumber, Description, RemainderIsFee, Timestamp]}, fetched
   * 2026-08-02). On that day the description listed these in flat prose alongside the OPTIONAL
   * PaymentDate, giving no signal which were mandatory; the caller sent PaymentDate and omitted
   * Description and Timestamp, and every payment failed. The description is the only place a
   * calling agent can learn this, so each required field is pinned by name.
   */
  @Test
  void purchasePaymentDescriptionPinsEveryRequiredField() {
    Tool payment = null;
    for (Method method : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool candidate = method.getAnnotation(Tool.class);
      if (candidate != null && "dinero_register_purchase_payment".equals(candidate.name())) {
        payment = candidate;
        break;
      }
    }

    assertNotNull(payment);
    String description = payment.description();
    assertTrue(description.contains("REQUIRED"));
    for (String required :
        java.util.List.of(
            "Amount", "DepositAccountNumber", "Description", "RemainderIsFee", "Timestamp")) {
      assertTrue(description.contains(required), "must document required field " + required);
    }
    // Timestamp is the voucher's version string, not a date -- and it must be read fresh.
    assertTrue(description.contains("Timestamp is NOT a date"));
    assertTrue(description.contains("immediately before posting"));
    // PaymentDate is OPTIONAL; mislabelling it as required is what misled the caller.
    assertTrue(description.contains("OPTIONAL: PaymentDate"));
  }

  /**
   * Pins the bank-matching rule promoted onto the payment tool (plan {@code
   * 2026-07-20-dinero-tool-description-hardening}, rule 3). Vendor names rarely reach the bank line
   * -- Hi3G posts as {@code Mob.Pay*3}, Anthropic as {@code Claude.ai Subscription} -- so searching
   * the statement by vendor name caused three separate wrong turns in the first live run. The
   * second half is which date to put in PaymentDate: the statement's posting/completed date, not
   * its transaction date, or Dinero's bankafstemning will not auto-match.
   *
   * <p>The phrase pinned is {@code "posting/completed date"} and deliberately not bare {@code
   * "posting"}: this description already says "immediately before posting" about reading Timestamp,
   * so a bare "posting" assertion would stay green with the whole rule deleted.
   */
  @Test
  void purchasePaymentDescriptionPinsBankMatchingRules() {
    Tool payment = null;
    for (Method method : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool candidate = method.getAnnotation(Tool.class);
      if (candidate != null && "dinero_register_purchase_payment".equals(candidate.name())) {
        payment = candidate;
        break;
      }
    }

    assertNotNull(payment);
    String description = payment.description();
    assertTrue(description.contains("by AMOUNT"), "must say to match the statement by amount");
    assertTrue(
        description.contains("not by"), "must say NOT to match the statement by description");
    assertTrue(
        description.contains("posting/completed date"),
        "must say which statement date PaymentDate takes");
    assertTrue(
        description.contains("transaction date"), "must name the date that must NOT be used");
    assertTrue(
        description.contains("bankafstemning"), "must state the consequence of the wrong date");
  }

  /**
   * Without the dev identity (every launch but {@code mvn quarkus:dev}), an anonymous caller is
   * turned away by {@code @RolesAllowed} before the tool method runs: no refusal from the writer.
   */
  @Test
  void anAnonymousCallerCannotUseAWriteTool() {
    try (McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
      client
          .when()
          .toolsCall("dinero_create_manual_voucher")
          .withArguments(
              Map.of(
                  "idempotency_key",
                  "anonymous-" + java.util.UUID.randomUUID(),
                  "expected_total",
                  "200.00",
                  "body",
                  "{\"Lines\":[{\"Amount\":100.00}]}"))
          .withErrorAssert(
              error -> {
                assertEquals(-32001, error.code());
                assertTrue(error.message().contains("UnauthorizedException"), error.message());
              })
          .send()
          .thenAssertResults();
    }
  }

  @Test
  void writeToolClassRequiresWriterRoleBeforeMethodInvocation() {
    RolesAllowed roles = IntegrationWriteMcpTool.class.getAnnotation(RolesAllowed.class);
    assertNotNull(roles);
    assertArrayEquals(new String[] {"integration-writer"}, roles.value());
  }
}
