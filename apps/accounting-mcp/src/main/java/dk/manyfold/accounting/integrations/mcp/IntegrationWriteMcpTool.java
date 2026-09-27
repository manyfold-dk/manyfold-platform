package dk.manyfold.accounting.integrations.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.manyfold.accounting.integrations.GuardrailException;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteAction;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteOutcome;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteResult;
import dk.manyfold.accounting.integrations.IntegrationWriteKeys;
import dk.manyfold.accounting.integrations.VendorWriteException;
import dk.manyfold.accounting.integrations.dinero.DineroWriter;
import dk.manyfold.accounting.security.IdentityResolver;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The MCP write front door for Dinero (ADR-0043): one named {@code @Tool} per allowlisted write
 * operation, each routed through {@link IntegrationWriteExecutor} so the {@code integration-writer}
 * gate, the atomic-claim idempotency ledger and the durable audit apply uniformly. There is no
 * generic write tool: every tool is bound server-side to exactly one Dinero endpoint + method. The
 * three irreversible booking tools additionally require an {@code expected_total} guard.
 *
 * <p>Failures surface as a {@link ToolCallException}. Local guardrail rejections carry only their
 * HTTP status; a vendor rejection additionally carries the vendor's own bounded reason when the
 * vendor answered <b>4xx</b> -- see {@link #vendorMessage} for why that is safe and why a bare
 * status was not. Never a secret, never PII, never a vendor 5xx body.
 */
@ApplicationScoped
@RolesAllowed(IdentityResolver.INTEGRATION_WRITER)
public class IntegrationWriteMcpTool {

  @Inject IntegrationWriteExecutor executor;
  @Inject DineroWriter dinero;
  @Inject ObjectMapper mapper;

  record WriteResponse(String vendorId, boolean deduplicated) {}

  @Tool(
      name = "dinero_upsert_contact",
      description =
          "Create or update a Dinero contact (debitor/creditor) for the configured organisation. Supply the"
              + " Dinero ContactCreateModel as a JSON object in 'body' (required fields: Name,"
              + " CountryKey e.g. \"DK\", IsPerson, IsMember, UseCvr; optional: Email, Phone,"
              + " Street, ZipCode, City, VatNumber, etc.). Omit 'contact_guid' to create (POST"
              + " /contacts); pass an existing 'contact_guid' to update (PUT /contacts/{guid})."
              + " Supply a unique 'idempotency_key' and reuse it verbatim to retry."
              + " Non-destructive.",
      annotations =
          @Tool.Annotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public String dineroUpsertContact(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(name = "body", description = "Dinero ContactCreateModel as a JSON object.")
          String body,
      @ToolArg(
              name = "contact_guid",
              required = false,
              description = "Existing contact Guid to update; omit to create.")
          String contactGuid) {
    JsonNode node = parseBody(body);
    String hash = hash("dinero:upsert-contact", contactGuid, canonical(node));
    String summary =
        "source=agent;op=upsert-contact;update="
            + (contactGuid != null && !contactGuid.isBlank())
            + ";bodyKeys="
            + keys(node);
    return run(
        "dinero",
        "upsert-contact",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.upsertContact(node, contactGuid)));
  }

  @Tool(
      name = "dinero_create_invoice_draft",
      description =
          "Create an UNBOOKED sales invoice draft in Dinero (POST /invoices) -> its Guid. Supply"
              + " the Dinero InvoiceCreateModel as a JSON object in 'body'. ProductLines is the"
              + " only REQUIRED field; ContactGuid, Date, Currency, Description,"
              + " PaymentConditionType/NumberOfDays are optional. The draft is not posted to the"
              + " ledger -- call"
              + " dinero_book_invoice to book it. Supply a unique 'idempotency_key'."
              + " Non-destructive (a draft can be deleted).",
      annotations =
          @Tool.Annotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public String dineroCreateInvoiceDraft(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(name = "body", description = "Dinero InvoiceCreateModel as a JSON object.")
          String body) {
    JsonNode node = parseBody(body);
    String hash = hash("dinero:create-invoice-draft", canonical(node));
    String summary = "source=agent;op=create-invoice-draft;bodyKeys=" + keys(node);
    return run(
        "dinero",
        "create-invoice-draft",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.createInvoiceDraft(node)));
  }

  @Tool(
      name = "dinero_book_invoice",
      description =
          "BOOK an existing Dinero invoice draft (POST /invoices/{guid}/book): assigns a voucher"
              + " number and posts it to the ledger + VAT. IRREVERSIBLE (correct later with a"
              + " credit note). Pass the draft 'invoice_guid' and an 'expected_total' equal to the"
              + " draft's TotalInclVat -- the service reads the live draft and refuses to book"
              + " (409) if they differ. Supply a unique 'idempotency_key'.",
      annotations =
          @Tool.Annotations(destructiveHint = true, idempotentHint = true, openWorldHint = true))
  public String dineroBookInvoice(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(name = "invoice_guid", description = "Guid of the draft invoice to book.")
          String invoiceGuid,
      @ToolArg(
              name = "expected_total",
              description =
                  "Expected TotalInclVat of the draft (decimal); guards against a wrong post.")
          String expectedTotal) {
    BigDecimal total = parseTotal(expectedTotal);
    requireValue(invoiceGuid, "invoice_guid");
    String hash = hash("dinero:book-invoice", invoiceGuid, total.toPlainString());
    String summary =
        "source=agent;op=book-invoice;guid="
            + invoiceGuid
            + ";expectedTotal="
            + total.toPlainString();
    return run(
        "dinero",
        "book-invoice",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.bookInvoice(invoiceGuid, total)));
  }

  @Tool(
      name = "dinero_create_purchase_voucher",
      description =
          "Register a purchase/expense voucher in Dinero (POST /v1.2/vouchers/purchase) -> its"
              + " Guid."
              + " PRE-FLIGHT: before creating, check the document is not already booked --"
              + " 'idempotency_key' only dedupes repeats of this same call, it cannot see a"
              + " voucher booked by another route. Call integration_get on"
              + " 'v1/{organizationId}/files?extensions=pdf&fileStatus=All&pageSize=60"
              + "&fields=FileGuid,Name,Size,Linked' and match your receipt by Name + Size. A"
              + " non-empty Linked array means it is already bound to a voucher -- do NOT"
              + " re-book. Two traps: (a) the same invoice may already be booked under a"
              + " DIFFERENT account with no contact, so it will not look like a repeat; (b) a"
              + " Linked entry can be STALE if that voucher was later deleted -- confirm the"
              + " voucher still exists (a GET returning 404 means the link is dead and the"
              + " document is NOT booked)."
              + " Supply the Dinero PurchaseVoucherCreateModelV2 as a JSON object in 'body'."
              + " The ONLY required field is PurchaseType ('cash' or 'credit'), and it partitions"
              + " the rest -- sending a field on the wrong side is the most common rejection:"
              + " CASH ONLY -> DepositAccountNumber (the account that paid), RegionKey (DK/EU/World);"
              + " CREDIT ONLY -> ContactGuid (the creditor), CurrencyKey (defaults DKK),"
              + " PaymentDate (forfaldsdato/due date);"
              + " EITHER -> VoucherDate, FileGuid, ExternalReference, Lines."
              + " Do NOT set PaymentDate on a cash purchase (rejected: 'PaymentDate should not be"
              + " set on cash purchases') -- a cash purchase is already settled by its"
              + " DepositAccountNumber. Conversely a CREDIT voucher cannot later be BOOKED without"
              + " PaymentDate, so set it at creation. A foreign-currency purchase must be credit."
              + " Each Lines[] entry is a PurchaseVoucherLineCreateModel with EXACTLY these fields:"
              + " Amount (required decimal, VAT-INCLUSIVE -- pass the GROSS figure and Dinero"
              + " derives net and VAT from the VatCode; this is invisible on reverse-charge"
              + " lines where gross == net, and only surfaces on Danish I25 lines. It is also"
              + " the ONLY accepted amount field; names like AmountExclVatValue/BaseAmountValue"
              + " belong to the READ model and are silently DISCARDED by Dinero, producing a"
              + " zero-value voucher), Description, AccountNumber,"
              + " VatCode, and optionally AccountTagName. Worked reverse-charge EU example:"
              + " Lines[{\"Description\":\"#Hjemmeside - Hetzner, faktura 086000664773 (01/2026)\","
              + "\"AccountNumber\":7301,\"VatCode\":\"IEUY\",\"Amount\":15.24}]."
              + " A voucher may carry multiple lines: for a mixed-VAT document (e.g. a Danish"
              + " phone bill with a 25% part and a VAT-exempt part) post one line per VAT rate,"
              + " taking the figures from the invoice's own momsspecifikation rather than"
              + " computing them."
              + " 'expected_total' must equal the voucher's own-currency total; the service reads"
              + " the created draft back, and on mismatch DELETES it and returns 409, so a"
              + " wrong-value draft is never left behind."
              + " Supply a unique 'idempotency_key'. Partially reversible (delete/credit-note).",
      annotations =
          @Tool.Annotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public String dineroCreatePurchaseVoucher(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(
              name = "expected_total",
              description =
                  "Expected own-currency voucher total (decimal); the created draft is read back"
                      + " and deleted + 409 on mismatch.")
          String expectedTotal,
      @ToolArg(name = "body", description = "Dinero PurchaseVoucherCreateModelV2 as a JSON object.")
          String body) {
    JsonNode node = parseBody(body);
    BigDecimal total = parseTotal(expectedTotal);
    String hash = hash("dinero:create-purchase-voucher", total.toPlainString(), canonical(node));
    String summary =
        "source=agent;op=create-purchase-voucher;expectedTotal="
            + total.toPlainString()
            + ";bodyKeys="
            + keys(node);
    return run(
        "dinero",
        "create-purchase-voucher",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.createPurchaseVoucher(node, total)));
  }

  @Tool(
      name = "dinero_book_purchase_voucher",
      description =
          "BOOK an existing Dinero purchase voucher draft (POST /vouchers/purchase/{guid}/book):"
              + " assigns a voucher number and posts it to the ledger + VAT. IRREVERSIBLE (correct"
              + " later with a credit note). Pass 'voucher_guid' and an 'expected_total' equal to"
              + " the live VoucherTotals entry whose Type is Total, in the voucher's CurrencyKey;"
              + " the service refuses to book (409) if they differ. Booking a credit voucher"
              + " requires PaymentDate (forfaldsdato) to be set on the draft, or Dinero rejects"
              + " with a validation error. Supply a unique 'idempotency_key'.",
      annotations =
          @Tool.Annotations(destructiveHint = true, idempotentHint = true, openWorldHint = true))
  public String dineroBookPurchaseVoucher(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(name = "voucher_guid", description = "Guid of the purchase voucher draft to book.")
          String voucherGuid,
      @ToolArg(
              name = "expected_total",
              description = "Expected own-currency Total from VoucherTotals (decimal).")
          String expectedTotal) {
    BigDecimal total = parseTotal(expectedTotal);
    requireValue(voucherGuid, "voucher_guid");
    String hash = hash("dinero:book-purchase-voucher", voucherGuid, total.toPlainString());
    String summary =
        "source=agent;op=book-purchase-voucher;guid="
            + voucherGuid
            + ";expectedTotal="
            + total.toPlainString();
    return run(
        "dinero",
        "book-purchase-voucher",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.bookPurchaseVoucher(voucherGuid, total)));
  }

  @Tool(
      name = "dinero_register_purchase_payment",
      description =
          "Register payment on a booked credit purchase voucher (POST"
              + " /purchase-vouchers/{id}/payments) -> payment Guid. Supply Dinero's"
              + " PurchaseVoucherCreditPaymentCreateModel in 'body'. FIVE fields are REQUIRED and"
              + " omitting any is a 400: Amount (the DKK payment, non-zero), DepositAccountNumber"
              + " (the account paying), Description (e.g. 'Betaling af faktura 12345'),"
              + " RemainderIsFee (boolean -- false unless any shortfall is a fee), and Timestamp."
              + " Timestamp is NOT a date: it is the booked voucher's current version string, so"
              + " read it from integration_get 'v1/{organizationId}/vouchers/purchase/{guid}'"
              + " immediately before posting (it changes on every mutation of the voucher)."
              + " OPTIONAL: PaymentDate (defaults to today), ExternalReference, and"
              + " AmountInForeignCurrency -- which becomes REQUIRED when the voucher's CurrencyKey"
              + " is not DKK, so check CurrencyKey on that same read."
              + " To find the payment on the bank statement, match by AMOUNT, not by"
              + " description -- vendor names rarely appear on the bank line (Hi3G posts as"
              + " 'Mob.Pay*3', Anthropic as 'Claude.ai Subscription'). Take PaymentDate from"
              + " the statement's posting/completed date, not its transaction date: the two can"
              + " differ by 3-5 days, and the wrong one stops Dinero's bankafstemning"
              + " auto-matching the payment, which then has to be matched by hand in the web"
              + " UI."
              + " 'expected_amount' must equal body Amount. For foreign-currency credit"
              + " purchases, this registration makes Dinero post the rate difference to account"
              + " 2400 Valutakursdifferencer. Non-destructive: Dinero exposes a payment DELETE"
              + " escape hatch, but that delete is manual and outside this MCP surface.",
      annotations =
          @Tool.Annotations(destructiveHint = false, idempotentHint = true, openWorldHint = true))
  public String dineroRegisterPurchasePayment(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(name = "voucher_id", description = "Guid of the booked credit purchase voucher.")
          String voucherId,
      @ToolArg(
              name = "expected_amount",
              description = "Expected DKK payment Amount (decimal); guards the posted body.")
          String expectedAmount,
      @ToolArg(
              name = "body",
              description = "Dinero PurchaseVoucherCreditPaymentCreateModel as a JSON object.")
          String body) {
    JsonNode node = parseBody(body);
    BigDecimal amount = parseAmount(expectedAmount);
    requireValue(voucherId, "voucher_id");
    String hash =
        hash(
            "dinero:register-purchase-payment", voucherId, amount.toPlainString(), canonical(node));
    String summary =
        "source=agent;op=register-purchase-payment;guid="
            + voucherId
            + ";expectedAmount="
            + amount.toPlainString()
            + ";bodyKeys="
            + keys(node);
    return run(
        "dinero",
        "register-purchase-payment",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.registerPurchasePayment(voucherId, node, amount)));
  }

  @Tool(
      name = "dinero_create_manual_voucher",
      description =
          "Post a manual ledger voucher (kassekladde) in Dinero (POST /vouchers/manuel) -> its"
              + " Guid. IRREVERSIBLE once posted. Supply the Dinero ManuelVoucherCreateModel as a"
              + " JSON object in 'body' (VoucherDate, Lines[{Amount, AccountNumber,"
              + " BalancingAccountNumber, ...}]) and an 'expected_total' equal to the sum of the"
              + " line Amounts -- the service refuses to post (409) if they differ. Supply a unique"
              + " 'idempotency_key'.",
      annotations =
          @Tool.Annotations(destructiveHint = true, idempotentHint = true, openWorldHint = true))
  public String dineroCreateManualVoucher(
      @ToolArg(name = "idempotency_key", description = "Unique, stable key; reuse to retry.")
          String idempotencyKey,
      @ToolArg(
              name = "expected_total",
              description =
                  "Expected sum of the line Amounts (decimal); guards against a malformed post.")
          String expectedTotal,
      @ToolArg(name = "body", description = "Dinero ManuelVoucherCreateModel as a JSON object.")
          String body) {
    JsonNode node = parseBody(body);
    BigDecimal total = parseTotal(expectedTotal);
    String hash = hash("dinero:create-manual-voucher", total.toPlainString(), canonical(node));
    String summary =
        "source=agent;op=create-manual-voucher;expectedTotal="
            + total.toPlainString()
            + ";lines="
            + (node.has("Lines") && node.get("Lines").isArray() ? node.get("Lines").size() : 0);
    return run(
        "dinero",
        "create-manual-voucher",
        idempotencyKey,
        hash,
        summary,
        () -> toResult(dinero.createManualVoucher(node, total)));
  }

  // === shared envelope ===

  private static WriteResult toResult(DineroWriter.Result r) {
    return new WriteResult(r.status(), r.id());
  }

  /**
   * Run the action through the executor and serialize the outcome; map failures to a tool error.
   */
  String run(
      String vendor, String op, String key, String hash, String summary, WriteAction action) {
    try {
      WriteOutcome out = executor.execute(vendor, op, key, hash, summary, action);
      return mapper.writeValueAsString(new WriteResponse(out.vendorId(), out.deduplicated()));
    } catch (ToolCallException tce) {
      throw tce;
    } catch (VendorWriteException vwe) {
      throw new ToolCallException(vendorMessage(vendor, op, vwe), vwe);
    } catch (GuardrailException ge) {
      int status = ge.getResponse() != null ? ge.getResponse().getStatus() : 500;
      String message = ge.getMessage();
      throw new ToolCallException(
          message == null || message.isBlank()
              ? "%s/%s rejected (HTTP %d)".formatted(vendor, op, status)
              : "%s/%s rejected (HTTP %d): %s".formatted(vendor, op, status, message),
          ge);
    } catch (WebApplicationException wae) {
      int status = wae.getResponse() != null ? wae.getResponse().getStatus() : 500;
      throw new ToolCallException("%s/%s rejected (HTTP %d)".formatted(vendor, op, status), wae);
    } catch (JsonProcessingException e) {
      throw new ToolCallException("could not serialize write result", e);
    } catch (RuntimeException e) {
      // Broad catch: any unexpected RuntimeException must not surface raw exception messages
      // (which may contain PII) to the MCP caller. The cause is chained for the server log only:
      // the MCP server answers with the ToolCallException's own message.
      throw new ToolCallException("%s/%s failed (internal error)".formatted(vendor, op), e);
    }
  }

  /**
   * Report a vendor write failure in the terms the caller can act on.
   *
   * <p>A vendor <b>4xx</b> is a rejection of the request the caller itself just composed, so it
   * names the TRUE upstream status and the vendor's own bounded reason -- returning the vendor's
   * complaint about the caller's body discloses nothing the caller did not already send. Reporting
   * these as a bare 502 (as this did until 2026-08-02) makes a one-line body fix look like an
   * upstream incident. A vendor <b>5xx</b> is a genuine upstream fault whose body is vendor
   * internals, so it stays opaque.
   */
  private static String vendorMessage(String vendor, String op, VendorWriteException vwe) {
    int upstream = vwe.upstreamStatus();
    if (upstream < 400 || upstream >= 500) {
      return "%s/%s rejected (HTTP 502)".formatted(vendor, op);
    }
    String detail = vwe.vendorDetail();
    return detail == null || detail.isBlank()
        ? "%s/%s rejected by vendor (HTTP %d)".formatted(vendor, op, upstream)
        : "%s/%s rejected by vendor (HTTP %d): %s".formatted(vendor, op, upstream, detail);
  }

  private JsonNode parseBody(String body) {
    if (body == null || body.isBlank()) {
      throw new ToolCallException("body is required (a Dinero request JSON object)");
    }
    JsonNode node;
    try {
      node = mapper.readTree(body);
    } catch (JsonProcessingException e) {
      throw new ToolCallException("body is not valid JSON", e);
    }
    if (node == null || !node.isObject()) {
      throw new ToolCallException("body must be a JSON object");
    }
    return node;
  }

  private static BigDecimal parseTotal(String expectedTotal) {
    if (expectedTotal == null || expectedTotal.isBlank()) {
      throw new ToolCallException("expected_total is required");
    }
    try {
      return new BigDecimal(expectedTotal.trim());
    } catch (NumberFormatException e) {
      throw new ToolCallException("expected_total must be a decimal number", e);
    }
  }

  private static BigDecimal parseAmount(String expectedAmount) {
    if (expectedAmount == null || expectedAmount.isBlank()) {
      throw new ToolCallException("expected_amount is required");
    }
    try {
      return new BigDecimal(expectedAmount.trim());
    } catch (NumberFormatException e) {
      throw new ToolCallException("expected_amount must be a decimal number", e);
    }
  }

  private static void requireValue(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new ToolCallException(name + " is required");
    }
  }

  /**
   * Stable re-serialization of the parsed body so whitespace/formatting does not change the hash.
   */
  private String canonical(JsonNode node) {
    try {
      return mapper.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new ToolCallException("could not canonicalize request body", e);
    }
  }

  /** SHA-256 over the JSON-canonicalized inputs (so a pipe in a value cannot merge fields). */
  String hash(String prefix, String... values) {
    List<String> canonical = new ArrayList<>();
    canonical.add(prefix);
    for (String v : values) {
      canonical.add(v == null ? "" : v);
    }
    try {
      return IntegrationWriteKeys.hash(mapper.writeValueAsString(canonical));
    } catch (JsonProcessingException e) {
      throw new ToolCallException("could not canonicalize request", e);
    }
  }

  /** The body's top-level field NAMES (not values) -- a non-identifying inputs summary. */
  private static String keys(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return String.join(",", names);
  }
}
