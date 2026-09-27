package dk.manyfold.accounting.integrations.dinero;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.manyfold.accounting.integrations.GuardrailException;
import dk.manyfold.accounting.integrations.VendorOutcomeUnknownException;
import dk.manyfold.accounting.integrations.VendorWriteException;
import dk.manyfold.accounting.integrations.VendorWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The Dinero write half (ADR-0043): a small, named, host-pinned, bearer-injecting allowlist of
 * write operations for the configured organisation -- never a generic write. Each operation is
 * bound to exactly one Dinero endpoint + method; the agent supplies the documented Dinero request
 * body (validated for the envelope, not re-modelled field by field) plus, for the irreversible
 * booking operations, an {@code expectedTotal} the writer checks against the live document total
 * before posting. The cross-cutting envelope (integration-writer gate, idempotency ledger, audit)
 * lives once in {@link dk.manyfold.accounting.integrations.IntegrationWriteExecutor}. Inert (503)
 * until creds present.
 */
@ApplicationScoped
public class DineroWriter implements VendorWriter {

  static final String VENDOR = "dinero";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final org.jboss.logging.Logger LOG =
      org.jboss.logging.Logger.getLogger(DineroWriter.class);

  /**
   * Dinero contact/invoice/voucher ids are GUID-shaped; reject anything with path metacharacters
   * ('/', '?', '#', '.', '..', ':', '@', whitespace) so an agent-supplied id cannot escape its
   * named endpoint shape while still landing on the pinned host.
   */
  private static final Pattern DINERO_GUID = Pattern.compile("[A-Za-z0-9-]{1,100}");

  private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  // Dinero documents 6 MB without defining MiB. Use the conservative decimal interpretation so a
  // payload rejected here is never deferred to the vendor's size guard.
  public static final int MAX_UPLOAD_BYTES = 6_000_000;

  /** Cap on the vendor reason relayed to the caller -- a diagnosis, never a payload dump. */
  static final int MAX_VENDOR_DETAIL = 300;

  private final DineroConfig config;
  private final DineroTokenProvider tokens;
  private final DineroRateLimiter rateLimiter;
  private final String pinnedHost;
  private final DineroHttp http;

  @Inject
  public DineroWriter(
      DineroConfig config,
      DineroTokenProvider tokens,
      DineroRateLimiter rateLimiter,
      DineroHttp http) {
    this.config = config;
    this.http = http;
    this.tokens = tokens;
    this.rateLimiter = rateLimiter;
    this.pinnedHost = URI.create(config.baseUrl()).getHost();
  }

  @Override
  public String vendor() {
    return VENDOR;
  }

  @Override
  public Set<String> operations() {
    return Set.of(
        "upsert-contact",
        "create-invoice-draft",
        "book-invoice",
        "create-purchase-voucher",
        "create-manual-voucher",
        "upload-file",
        "book-purchase-voucher",
        "register-purchase-payment");
  }

  /** The observed vendor HTTP status + the created/affected Dinero id. */
  public record Result(int status, String id) {}

  /** Create ({@code POST /contacts}) or update ({@code PUT /contacts/{guid}}) a Dinero contact. */
  public Result upsertContact(JsonNode body, String contactGuid) {
    if (contactGuid == null || contactGuid.isBlank()) {
      return post("v1/" + org() + "/contacts", body, "ContactGuid");
    }
    requireGuid(contactGuid);
    Result r = put("v1/" + org() + "/contacts/" + contactGuid, body);
    return new Result(r.status(), contactGuid);
  }

  /** Create an unbooked sales invoice ({@code POST /invoices}) -> its Guid. */
  public Result createInvoiceDraft(JsonNode body) {
    return post("v1/" + org() + "/invoices", body, "Guid");
  }

  /**
   * Book a draft invoice ({@code POST /invoices/{guid}/book}). Reads the live draft first to (a)
   * guard its {@code TotalInclVat} against {@code expectedTotal} and (b) obtain the current
   * TimeStamp that Dinero requires in the book request (optimistic concurrency, error 58 if stale).
   */
  public Result bookInvoice(String invoiceGuid, BigDecimal expectedTotal) {
    requireGuid(invoiceGuid);
    JsonNode draft = getJson("v1/" + org() + "/invoices/" + invoiceGuid);
    BigDecimal total = decimal(draft, "TotalInclVat");
    if (total == null) {
      throw new VendorWriteException(502, "could not read invoice TotalInclVat before booking");
    }
    if (expectedTotal.compareTo(total) != 0) {
      throw new GuardrailException(
          "expectedTotal "
              + expectedTotal.toPlainString()
              + " does not match the invoice total "
              + total.toPlainString()
              + " -- refusing to book",
          409);
    }
    String timestamp = text(draft, "TimeStamp");
    if (timestamp == null) {
      throw new VendorWriteException(502, "could not read invoice TimeStamp before booking");
    }
    Result r =
        postRaw(
            "v1/" + org() + "/invoices/" + invoiceGuid + "/book",
            Map.of("Timestamp", timestamp),
            null);
    return new Result(r.status(), invoiceGuid);
  }

  /**
   * Register a purchase/expense voucher ({@code POST /v1.2/vouchers/purchase}) -> its Guid.
   *
   * <p>Dinero silently drops unrecognised line fields and accepts a zero-value voucher, so a 2xx +
   * Guid proves nothing about the amounts. The writer therefore reads the created draft back and
   * compares its own-currency {@code VoucherTotals} {@code Type=Total} against {@code
   * expectedTotal}; on mismatch it deletes the draft (never stranding a wrong-value draft) and
   * throws 409 naming both amounts.
   */
  public Result createPurchaseVoucher(JsonNode body, BigDecimal expectedTotal) {
    requireConsistentPurchaseType(body);
    Result created = post("v1.2/" + org() + "/vouchers/purchase", body, "Guid");
    requireGuid(created.id());
    JsonNode draft;
    try {
      draft = getJson("v1/" + org() + "/vouchers/purchase/" + created.id());
    } catch (RuntimeException e) {
      // The draft exists; without its Timestamp it cannot be removed here.
      throw new VendorOutcomeUnknownException(
          null,
          "purchase voucher "
              + created.id()
              + " was created, but reading it back failed -- check it in Dinero",
          e);
    }
    BigDecimal total = purchaseVoucherTotal(draft);
    if (total == null) {
      String deletion = deleteCreatedDraft(created.id(), text(draft, "Timestamp"));
      // Not a vendor rejection -- Dinero accepted the create and answered the read-back 2xx. What
      // the caller must act on is the cleanup outcome (a draft may be stranded), so this carries
      // its message rather than collapsing into an opaque 502.
      throw refusalAfterCreate(
          "could not read purchase voucher VoucherTotals Total after creation", deletion, 502);
    }
    if (expectedTotal.compareTo(total) != 0) {
      String currency = text(draft, "CurrencyKey");
      String currencySuffix = currency == null ? "" : " " + currency;
      String deletion = deleteCreatedDraft(created.id(), text(draft, "Timestamp"));
      throw refusalAfterCreate(
          "expectedTotal "
              + expectedTotal.toPlainString()
              + " does not match the created purchase voucher total "
              + total.toPlainString()
              + currencySuffix,
          deletion,
          409);
    }
    return created;
  }

  /**
   * Reject a purchase-voucher body that contradicts its own {@code PurchaseType} before spending a
   * vendor round trip on it.
   *
   * <p>Deliberately narrow. Dinero's OpenAPI states exactly these two prohibitively -- PaymentDate
   * "do not set on cash purchases", CurrencyKey "should not be set for cash purchases" -- and
   * enforces PaymentDate with a 400 (observed 2026-08-02, where the description's credit-purchase
   * rule was over-generalized to cash). The neighbouring cash/credit fields (DepositAccountNumber,
   * RegionKey, ContactGuid) are documented only as "used for X", with no stated prohibition, so
   * they remain Dinero's call: schema conformance is the vendor's job, and a local mirror that
   * drifts would reject bodies Dinero accepts.
   */
  private static void requireConsistentPurchaseType(JsonNode body) {
    if (!"cash".equalsIgnoreCase(text(body, "PurchaseType"))) {
      return;
    }
    if (body.hasNonNull("PaymentDate")) {
      throw new GuardrailException(
          "PaymentDate must not be set on a cash purchase -- it is the credit-purchase payment"
              + " deadline. Omit it, or use PurchaseType credit.",
          400);
    }
    if (body.hasNonNull("CurrencyKey")) {
      throw new GuardrailException(
          "CurrencyKey must not be set on a cash purchase -- omit it, or use PurchaseType credit"
              + " (a foreign-currency purchase must be credit).",
          400);
    }
  }

  /**
   * Best-effort removal of a just-created draft that failed the read-back guard, so a wrong-value
   * draft is never stranded. Internal only -- deletion is deliberately NOT an MCP tool. Dinero's
   * voucher DELETE requires the entity's current Timestamp in the body (optimistic concurrency,
   * same TimestampObject as booking) -- an empty-body DELETE is rejected with 400.
   */
  private String deleteCreatedDraft(String guid, String timestamp) {
    try {
      delete("v1/" + org() + "/vouchers/purchase/" + guid, timestamp);
      return DRAFT_DELETED;
    } catch (RuntimeException e) {
      return "the draft " + guid + " could NOT be deleted and must be removed manually";
    }
  }

  private static final String DRAFT_DELETED = "the draft was deleted";

  /**
   * The refusal after a create: a plain guardrail when the draft was removed (nothing remains at
   * the vendor, so a retry is safe), an unknown outcome when it is stranded there.
   */
  private static WebApplicationException refusalAfterCreate(
      String message, String deletion, int status) {
    if (!DRAFT_DELETED.equals(deletion)) {
      return new VendorOutcomeUnknownException(null, message + " -- " + deletion);
    }
    return new GuardrailException(message + " -- " + deletion, status);
  }

  /** Upload a validated PDF/PNG/JPEG receipt ({@code POST /files}) -> its FileGuid. */
  public Result uploadFile(byte[] content, String fileName, String contentType) {
    validateFileName(fileName);
    if (content == null || content.length > MAX_UPLOAD_BYTES) {
      throw new GuardrailException("file must be no larger than 6 MB", 400);
    }
    String detectedContentType = detectContentType(content);
    validateClaimedContentType(contentType, detectedContentType);

    ensureConfigured();
    acquire();
    String path = "v1/" + org() + "/files";
    String boundary = "manyfold-" + UUID.randomUUID().toString().replace("-", "");
    byte[] body = multipartFile(boundary, content, fileName, detectedContentType);
    // The token first: a failure fetching it happens before any write is sent.
    String bearer = "Bearer " + tokens.accessToken(System.currentTimeMillis());
    Response resp =
        send(
            path,
            () ->
                http.get()
                    .target(target(path))
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer)
                    .post(
                        Entity.entity(
                            body, MediaType.valueOf("multipart/form-data; boundary=" + boundary))));
    return readResult(resp, "FileGuid", path);
  }

  /**
   * Book a purchase-voucher draft. The live voucher supplies both the own-currency total and the
   * optimistic-concurrency Timestamp required by Dinero's BookModel.
   */
  public Result bookPurchaseVoucher(String voucherGuid, BigDecimal expectedTotal) {
    requireGuid(voucherGuid);
    JsonNode draft = getJson("v1/" + org() + "/vouchers/purchase/" + voucherGuid);
    BigDecimal total = purchaseVoucherTotal(draft);
    if (total == null) {
      throw new VendorWriteException(
          502, "could not read purchase voucher VoucherTotals Total before booking");
    }
    if (expectedTotal.compareTo(total) != 0) {
      String currency = text(draft, "CurrencyKey");
      String currencySuffix = currency == null ? "" : " " + currency;
      throw new GuardrailException(
          "expectedTotal "
              + expectedTotal.toPlainString()
              + " does not match the purchase voucher total "
              + total.toPlainString()
              + currencySuffix
              + " -- refusing to book",
          409);
    }
    String timestamp = text(draft, "Timestamp");
    if (timestamp == null) {
      throw new VendorWriteException(
          502, "could not read purchase voucher Timestamp before booking");
    }
    Result r =
        postRaw(
            "v1/" + org() + "/vouchers/purchase/" + voucherGuid + "/book",
            Map.of("Timestamp", timestamp),
            null);
    return new Result(r.status(), voucherGuid);
  }

  /** Add a payment to a booked credit purchase voucher -> the created payment Guid. */
  public Result registerPurchasePayment(
      String voucherId, JsonNode body, BigDecimal expectedAmount) {
    requireGuid(voucherId);
    BigDecimal amount = decimal(body, "Amount");
    if (amount == null) {
      throw new GuardrailException("purchase payment body must contain a numeric Amount", 400);
    }
    if (expectedAmount.compareTo(amount) != 0) {
      throw new GuardrailException(
          "expectedAmount "
              + expectedAmount.toPlainString()
              + " does not match the purchase payment Amount "
              + amount.toPlainString()
              + " -- refusing to post",
          409);
    }
    return post("v1/" + org() + "/purchase-vouchers/" + voucherId + "/payments", body, "Guid");
  }

  /**
   * Post a manual ledger voucher ({@code POST /vouchers/manuel}). Guards the sum of the body's line
   * {@code Amount}s against {@code expectedTotal} before posting (a malformed-post guard; the
   * voucher is self-balancing via each line's AccountNumber/BalancingAccountNumber).
   */
  public Result createManualVoucher(JsonNode body, BigDecimal expectedTotal) {
    BigDecimal sum = BigDecimal.ZERO;
    JsonNode lines = body.get("Lines");
    if (lines == null || !lines.isArray() || lines.isEmpty()) {
      throw new GuardrailException("manual voucher body must contain a non-empty Lines array", 400);
    }
    for (JsonNode line : lines) {
      BigDecimal amount = decimal(line, "Amount");
      if (amount == null) {
        throw new GuardrailException("each manual voucher line must have a numeric Amount", 400);
      }
      sum = sum.add(amount);
    }
    if (expectedTotal.compareTo(sum) != 0) {
      throw new GuardrailException(
          "expectedTotal "
              + expectedTotal.toPlainString()
              + " does not match the summed line total "
              + sum.toPlainString()
              + " -- refusing to post",
          409);
    }
    return post("v1/" + org() + "/vouchers/manuel", body, "Guid");
  }

  // === vendor HTTP ===

  private Result post(String path, Object body, String idField) {
    return postRaw(path, body, idField);
  }

  private Result postRaw(String path, Object body, String idField) {
    ensureConfigured();
    acquire();
    // The token first: a failure fetching it happens before any write is sent.
    String bearer = "Bearer " + tokens.accessToken(System.currentTimeMillis());
    Response resp =
        send(
            path,
            () ->
                http.get()
                    .target(target(path))
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer)
                    .post(jsonEntity(body)));
    return readResult(resp, idField, path);
  }

  private Result put(String path, Object body) {
    ensureConfigured();
    acquire();
    // The token first: a failure fetching it happens before any write is sent.
    String bearer = "Bearer " + tokens.accessToken(System.currentTimeMillis());
    Response resp =
        send(
            path,
            () ->
                http.get()
                    .target(target(path))
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", bearer)
                    .put(jsonEntity(body)));
    return readResult(resp, null, path);
  }

  private void delete(String path, String timestamp) {
    ensureConfigured();
    acquire();
    try (Response resp =
        http.get()
            .target(target(path))
            .request(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + tokens.accessToken(System.currentTimeMillis()))
            .method(
                "DELETE", jsonEntity(Map.of("Timestamp", timestamp == null ? "" : timestamp)))) {
      int status = resp.getStatus();
      if (status < 200 || status >= 300) {
        String detail = logVendorError("DELETE", path, resp);
        throw new VendorWriteException(
            status, "dinero DELETE " + path + " returned " + status, detail);
      }
    }
  }

  private JsonNode getJson(String path) {
    ensureConfigured();
    acquire();
    try (Response resp =
        http.get()
            .target(target(path))
            .request(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + tokens.accessToken(System.currentTimeMillis()))
            .get()) {
      int status = resp.getStatus();
      if (status < 200 || status >= 300) {
        String detail = logVendorError("GET", path, resp);
        throw new VendorWriteException(
            status, "dinero GET " + path + " returned " + status, detail);
      }
      return readJson(resp, "dinero GET " + path);
    }
  }

  /**
   * Reads a write's result and closes the response, which it takes over from the caller. A 4xx is
   * the vendor refusing the write. A 5xx, or a 2xx whose body cannot be read, leaves the outcome
   * unknown: the document may exist.
   */
  private Result readResult(Response resp, String idField, String path) {
    try (resp) {
      int status = resp.getStatus();
      if (status >= 500) {
        logVendorError("POST/PUT", path, resp);
        throw new VendorOutcomeUnknownException(
            status, "dinero " + path + " answered " + status + " to a write");
      }
      if (status < 200 || status >= 300) {
        String detail = logVendorError("POST/PUT", path, resp);
        throw new VendorWriteException(status, "dinero " + path + " returned " + status, detail);
      }
      if (idField == null) {
        return new Result(status, null);
      }
      JsonNode node;
      try {
        node = readJson(resp, "dinero " + path);
      } catch (RuntimeException e) { // an unreadable body, or the connection lost while reading it
        throw new VendorOutcomeUnknownException(
            null, "dinero " + path + " accepted the write, but its answer could not be read", e);
      }
      String id = text(node, idField);
      if (id == null) {
        throw new VendorOutcomeUnknownException(
            null, "dinero " + path + " accepted the write, but returned no " + idField);
      }
      return new Result(status, id);
    }
  }

  /**
   * Sends a write. A connection that could not be opened never reached the vendor; any other
   * transport failure may have come after the request was sent, so its outcome is unknown.
   */
  private static Response send(String path, Supplier<Response> call) {
    try {
      return call.get();
    } catch (ProcessingException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
        // Local: nothing was sent and Dinero gave no answer, so no vendor status is recorded.
        throw new GuardrailException("could not reach dinero for " + path, 502, e);
      }
      throw new VendorOutcomeUnknownException(
          null, "the connection to dinero failed during " + path, e);
    }
  }

  /**
   * Log the vendor's error body server-side (truncated) so a rejected write is diagnosable from the
   * service logs, and return its condensed reason for {@link VendorWriteException#vendorDetail()}
   * so the caller is not left guessing at a bare status code.
   */
  private static String logVendorError(String method, String path, Response resp) {
    String body;
    try {
      resp.bufferEntity();
      body = resp.readEntity(String.class);
    } catch (RuntimeException e) {
      LOG.warnf("dinero %s %s returned %d (error body unreadable)", method, path, resp.getStatus());
      return null;
    }
    String logged = body != null && body.length() > 500 ? body.substring(0, 500) : body;
    LOG.warnf("dinero %s %s returned %d: %s", method, path, resp.getStatus(), logged);
    return vendorDetail(body);
  }

  /**
   * Condense a Dinero error body into the short reason handed back to the MCP caller on a vendor
   * 4xx. Dinero answers a rejected write with {@code {"code":..,"message":"Validation Error",
   * "validationErrors":{"<property>":"<reason>"},..}}; those property/reason pairs are Dinero's own
   * validation strings describing the very body the caller just sent, so returning them discloses
   * nothing the caller did not already supply. Falls back to the top-level message, and yields
   * {@code null} for a non-JSON body (an outage's HTML error page) rather than echoing it.
   */
  static String vendorDetail(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (JsonProcessingException e) {
      return null;
    }
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode errors = node.get("validationErrors");
    if (errors == null || !errors.isObject() || errors.isEmpty()) {
      return bound(text(node, "message"));
    }
    StringBuilder joined = new StringBuilder();
    Iterator<String> properties = errors.fieldNames();
    while (properties.hasNext()) {
      String property = properties.next();
      if (joined.length() > 0) {
        joined.append("; ");
      }
      joined.append(property).append(": ").append(text(errors, property));
    }
    return bound(joined.toString());
  }

  private static String bound(String detail) {
    if (detail == null || detail.isBlank()) {
      return null;
    }
    return detail.length() > MAX_VENDOR_DETAIL
        ? detail.substring(0, MAX_VENDOR_DETAIL) + "..."
        : detail;
  }

  private void acquire() {
    if (!rateLimiter.tryAcquire(System.currentTimeMillis())) {
      throw new GuardrailException("dinero write rate limit exceeded", 429);
    }
  }

  private static JsonNode readJson(Response resp, String context) {
    String body = resp.readEntity(String.class);
    if (body == null || body.isBlank()) {
      throw new VendorWriteException(502, context + " returned an empty body");
    }
    try {
      JsonNode node = JSON.readTree(body);
      if (node == null) {
        throw new VendorWriteException(502, context + " returned an empty body");
      }
      return node;
    } catch (JsonProcessingException e) {
      throw VendorWriteException.unreadable(502, context + " returned invalid JSON", e);
    }
  }

  private static Entity<String> jsonEntity(Object body) {
    try {
      return Entity.entity(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON_TYPE);
    } catch (JsonProcessingException e) {
      throw new GuardrailException("could not serialize Dinero request", 500, e);
    }
  }

  private void ensureConfigured() {
    if (isBlank(config.clientId())
        || isBlank(config.clientSecret())
        || isBlank(config.apiKey())
        || isBlank(config.organizationId())) {
      throw new GuardrailException("dinero integration not configured", 503);
    }
  }

  private String org() {
    return config.organizationId().orElse("");
  }

  private URI target(String path) {
    String base = config.baseUrl();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    URI uri = URI.create(base + "/" + path);
    if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(pinnedHost)) {
      throw new GuardrailException("upstream host not allowed", 400);
    }
    return uri;
  }

  private static BigDecimal decimal(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v == null || !v.isNumber() ? null : v.decimalValue();
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node == null ? null : node.get(field);
    return v == null || v.isNull() ? null : v.asText();
  }

  private static boolean isBlank(Optional<String> value) {
    return value.isEmpty() || value.get().isBlank();
  }

  /**
   * Reject agent-supplied ids that carry path metacharacters, so a write cannot escape its named
   * endpoint shape (e.g. a guid of "a/../x" or "a?b") even though it stays on the pinned host.
   */
  private static void requireGuid(String guid) {
    if (guid == null || !DINERO_GUID.matcher(guid).matches()) {
      throw new GuardrailException("invalid Dinero id (expected a GUID-shaped value)", 400);
    }
  }

  /** Detect the safe upload media type from magic bytes, never from caller metadata. */
  public static String detectContentType(byte[] content) {
    if (startsWith(content, 0x25, 0x50, 0x44, 0x46)) {
      return "application/pdf";
    }
    if (startsWith(content, 0x89, 0x50, 0x4E, 0x47)) {
      return "image/png";
    }
    if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
      return "image/jpeg";
    }
    throw new GuardrailException("file magic bytes must identify PDF, PNG, or JPEG", 400);
  }

  private static boolean startsWith(byte[] content, int... prefix) {
    if (content == null || content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((content[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  private static void validateFileName(String fileName) {
    if (fileName == null
        || !SAFE_FILE_NAME.matcher(fileName).matches()
        || fileName.contains("..")) {
      throw new GuardrailException(
          "fileName must be 1-128 safe ASCII characters without path metacharacters", 400);
    }
  }

  static void validateClaimedContentType(String claimed, String detected) {
    if (claimed == null || claimed.isBlank()) {
      return;
    }
    String normalized = claimed.trim().toLowerCase(Locale.ROOT);
    if ("image/jpg".equals(normalized)) {
      normalized = "image/jpeg";
    }
    if (!normalized.equals(detected)) {
      throw new GuardrailException("contentType does not match the file magic bytes", 400);
    }
  }

  private static byte[] multipartFile(
      String boundary, byte[] content, String fileName, String contentType) {
    ByteArrayOutputStream body = new ByteArrayOutputStream(content.length + 512);
    body.writeBytes(
        ("--"
                + boundary
                + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName
                + "\"\r\n"
                + "Content-Type: "
                + contentType
                + "\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    body.writeBytes(content);
    body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    return body.toByteArray();
  }

  private static BigDecimal purchaseVoucherTotal(JsonNode draft) {
    // Dinero's OpenAPI property text currently calls this DKK, but a live GET of a persisted EUR
    // purchase voucher on 2026-07-19 returned CurrencyKey=EUR and Label="Total EUR" with this value
    // equal to the voucher's EUR total. Select by the stable Type rather than the localized Label.
    JsonNode totals = draft == null ? null : draft.get("VoucherTotals");
    if (totals == null || !totals.isArray()) {
      return null;
    }
    BigDecimal total = null;
    for (JsonNode candidate : totals) {
      if (!"Total".equals(text(candidate, "Type"))) {
        continue;
      }
      BigDecimal value = decimal(candidate, "Total");
      if (value == null || total != null) {
        return null;
      }
      total = value;
    }
    return total;
  }
}
