package dk.manyfold.accounting.integrations.dinero;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import dk.manyfold.accounting.integrations.VendorOutcomeUnknownException;
import dk.manyfold.accounting.integrations.VendorWriteException;
import jakarta.ws.rs.WebApplicationException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the Dinero write guards that run BEFORE any vendor HTTP call: the
 * manual-voucher expectedTotal / balance guard and the inert-until-configured (503) posture.
 */
class DineroWriterTest {

  private static final ObjectMapper M = new ObjectMapper();
  private static final String ORG = "123456";

  private WireMockServer wireMock;

  @BeforeEach
  void startWireMock() {
    wireMock = new WireMockServer(wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterEach
  void stopWireMock() {
    wireMock.stop();
  }

  private static DineroWriter configured() {
    DineroConfig c = DineroTestConfig.configured();
    return new DineroWriter(
        c,
        new DineroTokenProvider(c, new DineroHttp()),
        new DineroRateLimiter(c),
        new DineroHttp());
  }

  private DineroWriter stubbedWriter() {
    DineroConfig c = DineroTestConfig.of(wireMock.baseUrl(), "id", "secret", "apikey", ORG);
    DineroTokenProvider tokens =
        new DineroTokenProvider(c, new DineroHttp()) {
          @Override
          public synchronized String accessToken(long nowMillis) {
            return "test-token";
          }
        };
    return new DineroWriter(c, tokens, new DineroRateLimiter(c), new DineroHttp());
  }

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static int statusOf(Runnable r) {
    try {
      r.run();
      return 0;
    } catch (WebApplicationException e) {
      return e.getResponse().getStatus();
    }
  }

  @Test
  void manualVoucherRejectsExpectedTotalMismatchBeforePosting() {
    JsonNode body =
        json(
            "{\"Lines\":[{\"Amount\":100.0,\"AccountNumber\":1000,"
                + "\"BalancingAccountNumber\":5820}]}");
    assertEquals(
        409, statusOf(() -> configured().createManualVoucher(body, new BigDecimal("200.00"))));
  }

  @Test
  void manualVoucherRejectsEmptyLines() {
    assertEquals(
        400,
        statusOf(() -> configured().createManualVoucher(json("{\"Lines\":[]}"), BigDecimal.TEN)));
  }

  @Test
  void manualVoucherRejectsLineWithoutNumericAmount() {
    JsonNode body = json("{\"Lines\":[{\"AccountNumber\":1000}]}");
    assertEquals(400, statusOf(() -> configured().createManualVoucher(body, BigDecimal.ZERO)));
  }

  @Test
  void bookInvoiceRejectsGuidWithPathMetacharacters() {
    assertEquals(400, statusOf(() -> configured().bookInvoice("abc/../v1/x", new BigDecimal("1"))));
  }

  @Test
  void uploadRejectsUnsupportedMagicBytesBeforePosting() {
    assertEquals(
        400,
        statusOf(
            () ->
                configured()
                    .uploadFile(
                        "not-a-receipt".getBytes(StandardCharsets.UTF_8),
                        "receipt.pdf",
                        "application/pdf")));
  }

  @Test
  void uploadRejectsClaimedContentTypeThatDoesNotMatchMagicBytes() {
    assertEquals(
        400,
        statusOf(() -> DineroWriter.validateClaimedContentType("image/png", "application/pdf")));
  }

  @Test
  void uploadAcceptsJpgAliasForDetectedJpeg() {
    assertEquals(
        0, statusOf(() -> DineroWriter.validateClaimedContentType("image/jpg", "image/jpeg")));
  }

  @Test
  void uploadStrictlyRejectsContentTypeParameters() {
    assertEquals(
        400,
        statusOf(
            () ->
                DineroWriter.validateClaimedContentType(
                    "application/pdf; charset=UTF-8", "application/pdf")));
  }

  @Test
  void uploadRejectsDecodedContentAboveSixMegabytesBeforePosting() {
    assertEquals(6_000_000, DineroWriter.MAX_UPLOAD_BYTES);
    byte[] oversized = new byte[DineroWriter.MAX_UPLOAD_BYTES + 1];
    byte[] pdfMagic = "%PDF".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(pdfMagic, 0, oversized, 0, pdfMagic.length);

    assertEquals(
        400, statusOf(() -> configured().uploadFile(oversized, "receipt.pdf", "application/pdf")));
  }

  @Test
  void uploadRejectsPathLikeFileNameBeforePosting() {
    assertEquals(
        400,
        statusOf(
            () ->
                configured()
                    .uploadFile(
                        "%PDF-1.7".getBytes(StandardCharsets.US_ASCII),
                        "../receipt.pdf",
                        "application/pdf")));
  }

  @Test
  void uploadPostsMultipartWithDetectedContentType() {
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/files"))
            .willReturn(okJson("{\"FileGuid\":\"file-guid-123\"}")));
    byte[] content = "%PDF-1.7\nreceipt".getBytes(StandardCharsets.US_ASCII);

    DineroWriter.Result result = stubbedWriter().uploadFile(content, "receipt.pdf", null);

    assertEquals(200, result.status());
    assertEquals("file-guid-123", result.id());
    wireMock.verify(
        postRequestedFor(urlEqualTo("/v1/" + ORG + "/files"))
            .withHeader("Content-Type", matching("multipart/form-data;\\s*boundary=.*"))
            .withRequestBody(containing("name=\"file\"; filename=\"receipt.pdf\""))
            .withRequestBody(containing("Content-Type: application/pdf"))
            .withRequestBody(containing("%PDF-1.7")));
  }

  @Test
  void bookPurchaseVoucherRejectsExpectedTotalMismatchBeforePosting() {
    String guid = "voucher-guid-123";
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(okJson(purchaseVoucher("100.00"))));

    assertEquals(
        409, statusOf(() -> stubbedWriter().bookPurchaseVoucher(guid, new BigDecimal("99.00"))));
    wireMock.verify(
        0, postRequestedFor(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid + "/book")));
  }

  @Test
  void bookPurchaseVoucherUsesLiveTimestampWhenTotalMatches() {
    String guid = "voucher-guid-123";
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(okJson(purchaseVoucher("100.00"))));
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid + "/book"))
            .willReturn(okJson("{}")));

    DineroWriter.Result result = stubbedWriter().bookPurchaseVoucher(guid, new BigDecimal("100.0"));

    assertEquals(200, result.status());
    assertEquals(guid, result.id());
    wireMock.verify(
        postRequestedFor(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid + "/book"))
            .withRequestBody(equalToJson("{\"Timestamp\":\"timestamp-1\"}")));
  }

  /**
   * The authoritative create-line amount field is {@code Amount} (Dinero OpenAPI
   * PurchaseVoucherLineCreateModel, fetched 2026-07-19 from /openapi/v1/swagger.json; the only
   * required property). Read-model names (AmountExclVatValue, BaseAmountValue, AmountExclVat) are
   * silently discarded by Dinero and produce a zero-value voucher. This fixture pins the field name
   * and the read-back guard together so neither can regress silently.
   */
  @Test
  void createPurchaseVoucherPostsLineAmountFieldAndReturnsGuidWhenReadBackMatches() {
    String guid = "voucher-guid-123";
    JsonNode body =
        json(
            "{\"PurchaseType\":\"credit\",\"CurrencyKey\":\"EUR\","
                + "\"Lines\":[{\"Description\":\"Hetzner\",\"AccountNumber\":7301,"
                + "\"VatCode\":\"IEUY\",\"Amount\":15.24}]}");
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(okJson("{\"Guid\":\"" + guid + "\"}")));
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(okJson(purchaseVoucher("15.24"))));

    DineroWriter.Result result =
        stubbedWriter().createPurchaseVoucher(body, new BigDecimal("15.24"));

    assertEquals(guid, result.id());
    wireMock.verify(
        postRequestedFor(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .withRequestBody(containing("\"Amount\":15.24")));
    wireMock.verify(0, deleteRequestedFor(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid)));
  }

  @Test
  void createPurchaseVoucherDeletesDraftAndRejectsWhenReadBackTotalMismatches() {
    String guid = "voucher-guid-123";
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(okJson("{\"Guid\":\"" + guid + "\"}")));
    // The silent-zero defect shape: Dinero accepted the create but discarded the amounts.
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(okJson(purchaseVoucher("0.0"))));
    wireMock.stubFor(
        delete(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid)).willReturn(okJson("{}")));

    assertEquals(
        409,
        statusOf(
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"credit\"}"), new BigDecimal("15.24"))));
    // Dinero's voucher DELETE demands the entity Timestamp in the body (an empty-body DELETE is
    // rejected 400), so the guard must send the read-back draft's Timestamp.
    wireMock.verify(
        1,
        deleteRequestedFor(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .withRequestBody(equalToJson("{\"Timestamp\":\"timestamp-1\"}")));
  }

  @Test
  void createPurchaseVoucherReportsAnUnknownOutcomeWhenTheWrongDraftIsStranded() {
    String guid = "voucher-guid-123";
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(okJson("{\"Guid\":\"" + guid + "\"}")));
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(okJson(purchaseVoucher("0.0"))));
    wireMock.stubFor(
        delete(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(500)));

    // The wrong draft stays at Dinero, so the ledger must not treat this as a clean failure a
    // retry could repeat.
    VendorOutcomeUnknownException e =
        assertThrows(
            VendorOutcomeUnknownException.class,
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"credit\"}"), new BigDecimal("15.24")));
    assertEquals(null, e.vendorStatus()); // no vendor response to record
    assertTrue(e.getMessage().contains("could NOT be deleted"));
  }

  @Test
  void registerPurchasePaymentRejectsExpectedAmountMismatchBeforePosting() {
    JsonNode body = json("{\"Amount\":750.00,\"AmountInForeignCurrency\":100.00}");

    assertEquals(
        409,
        statusOf(
            () ->
                configured()
                    .registerPurchasePayment("voucher-guid-123", body, new BigDecimal("749.00"))));
  }

  @Test
  void registerPurchasePaymentPassesDocumentedBodyThrough() {
    String guid = "voucher-guid-123";
    JsonNode body =
        json(
            "{\"Amount\":750.00,\"AmountInForeignCurrency\":100.00,"
                + "\"DepositAccountNumber\":5820,\"PaymentDate\":\"2026-07-19\","
                + "\"Description\":\"Payment\",\"RemainderIsFee\":false,"
                + "\"Timestamp\":\"timestamp-1\"}");
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/purchase-vouchers/" + guid + "/payments"))
            .willReturn(okJson("{\"Guid\":\"payment-guid-123\"}")));

    DineroWriter.Result result =
        stubbedWriter().registerPurchasePayment(guid, body, new BigDecimal("750.0"));

    assertEquals(200, result.status());
    assertEquals("payment-guid-123", result.id());
    wireMock.verify(
        postRequestedFor(urlEqualTo("/v1/" + ORG + "/purchase-vouchers/" + guid + "/payments"))
            .withRequestBody(equalToJson(body.toString())));
  }

  @Test
  void upsertContactRejectsGuidWithPathMetacharacters() {
    assertEquals(
        400, statusOf(() -> configured().upsertContact(json("{\"Name\":\"x\"}"), "a/b?c")));
  }

  @Test
  void inertWhenAnyCredentialBlank() {
    DineroConfig c = DineroTestConfig.of("https://api.dinero.dk", "id", "secret", "", "123456");
    DineroWriter inert =
        new DineroWriter(
            c,
            new DineroTokenProvider(c, new DineroHttp()),
            new DineroRateLimiter(c),
            new DineroHttp());
    assertEquals(503, statusOf(() -> inert.createInvoiceDraft(json("{\"x\":1}"))));
  }

  /**
   * The 2026-08-02 Bolt bodies: PurchaseType "cash" together with PaymentDate. Dinero's own OpenAPI
   * says PaymentDate is "do not set on cash purchases, payment deadline for credit purchases", and
   * enforces it with a 400. A body that contradicts its own PurchaseType is caught here, before a
   * vendor round trip.
   */
  @Test
  void cashPurchaseWithPaymentDateIsRejectedBeforeCallingDinero() {
    assertEquals(
        400,
        statusOf(
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"cash\",\"PaymentDate\":\"2026-02-06\"}"),
                        new BigDecimal("136.00"))));
    wireMock.verify(0, postRequestedFor(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase")));
  }

  @Test
  void cashPurchaseWithCurrencyKeyIsRejectedBeforeCallingDinero() {
    assertEquals(
        400,
        statusOf(
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"cash\",\"CurrencyKey\":\"EUR\"}"),
                        new BigDecimal("136.00"))));
    wireMock.verify(0, postRequestedFor(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase")));
  }

  /**
   * The mirror rule: PaymentDate is REQUIRED on a credit voucher for it to be bookable later, so
   * the guard must not touch it there. Guards that over-reach block real work.
   */
  @Test
  void creditPurchaseWithPaymentDateIsAccepted() {
    String guid = "voucher-guid-123";
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(okJson("{\"Guid\":\"" + guid + "\"}")));
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(okJson(purchaseVoucher("15.24"))));

    DineroWriter.Result result =
        stubbedWriter()
            .createPurchaseVoucher(
                json("{\"PurchaseType\":\"credit\",\"PaymentDate\":\"2026-02-16\"}"),
                new BigDecimal("15.24"));

    assertEquals(guid, result.id());
  }

  /**
   * The 2026-08-02 cash-voucher rejection, verbatim from the pod log. Dinero answers a malformed
   * write with 400 + a {@code validationErrors} map naming each offending property. The writer must
   * carry that reason out ON THE EXCEPTION, not merely log it server-side: an MCP caller that sees
   * only a status code cannot tell a fixable body error from an upstream outage, and on this day
   * read it as the latter.
   */
  @Test
  void vendorValidationRejectionCarriesTheOffendingPropertyOutOnTheException() {
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"code\":42,\"message\":\"Validation Error\",\"validationErrors\":"
                            + "{\"PaymentDate\":\"PaymentDate should not be set on cash"
                            + " purchases\"}}")));

    // A credit body, so the local cash/credit guard does not short-circuit before the vendor call
    // -- this test is about relaying whatever Dinero says, not about that guard.
    VendorWriteException thrown =
        assertThrows(
            VendorWriteException.class,
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"credit\"}"), new BigDecimal("136.00")));

    assertEquals(400, thrown.upstreamStatus());
    assertEquals(502, thrown.getResponse().getStatus(), "client-facing status stays 502");
    assertEquals(
        "PaymentDate: PaymentDate should not be set on cash purchases", thrown.vendorDetail());
  }

  /** The 2026-08-02 payment rejection: several properties at once, joined in a stable order. */
  @Test
  void vendorValidationRejectionJoinsEveryOffendingProperty() {
    String guid = "voucher-guid-123";
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/purchase-vouchers/" + guid + "/payments"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"code\":42,\"message\":\"Validation Error\",\"validationErrors\":"
                            + "{\"timestamp\":\"The Timestamp field is required.\","
                            + "\"description\":\"The Description field is required.\"}}")));

    VendorWriteException thrown =
        assertThrows(
            VendorWriteException.class,
            () ->
                stubbedWriter()
                    .registerPurchasePayment(
                        guid, json("{\"Amount\":1439.20}"), new BigDecimal("1439.20")));

    assertEquals(400, thrown.upstreamStatus());
    assertEquals(
        "timestamp: The Timestamp field is required.;"
            + " description: The Description field is required.",
        thrown.vendorDetail());
  }

  /** No validationErrors map -- fall back to Dinero's top-level message. */
  @Test
  void vendorRejectionWithoutValidationErrorsFallsBackToTheTopLevelMessage() {
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(
                aResponse()
                    .withStatus(403)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":7,\"message\":\"Din konto mangler abonnement\"}")));

    VendorWriteException thrown =
        assertThrows(
            VendorWriteException.class,
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"credit\"}"), new BigDecimal("136.00")));

    assertEquals(403, thrown.upstreamStatus());
    assertEquals("Din konto mangler abonnement", thrown.vendorDetail());
  }

  /** A non-JSON body (an outage's HTML error page) yields no detail rather than a leak. */
  @Test
  void vendorErrorPageYieldsNoDetail() {
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(
                aResponse()
                    .withStatus(502)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body>Bad Gateway</body></html>")));

    VendorWriteException thrown =
        assertThrows(
            VendorWriteException.class,
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"credit\"}"), new BigDecimal("136.00")));

    assertEquals(502, thrown.upstreamStatus());
    assertNull(thrown.vendorDetail());
  }

  /** An unbounded vendor body must not become an unbounded tool error. */
  @Test
  void vendorDetailIsBounded() {
    String detail = DineroWriter.vendorDetail("{\"message\":\"" + "x".repeat(1000) + "\"}");

    assertEquals(DineroWriter.MAX_VENDOR_DETAIL + 3, detail.length());
  }

  private static String purchaseVoucher(String total) {
    return "{\"CurrencyKey\":\"EUR\",\"Timestamp\":\"timestamp-1\","
        + "\"Lines\":[{\"AmountExclVatValue\":"
        + total
        + ",\"AmountInclVatValue\":"
        + total
        + ",\"VatAmountValue\":0.00}],"
        + "\"VoucherTotals\":[{\"Type\":\"SubTotalWithoutVat\",\"Total\":"
        + total
        + "},{\"Type\":\"Total\",\"Label\":\"Total EUR\",\"Total\":"
        + total
        + "}]}";
  }

  @Test
  void aVendorServerErrorOnAWriteIsAnUnknownOutcome() {
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/vouchers/manuel"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(500)));

    assertThrows(
        VendorOutcomeUnknownException.class,
        () ->
            stubbedWriter()
                .createManualVoucher(
                    json("{\"Lines\":[{\"Amount\":100.00}]}"), new BigDecimal("100.00")));
  }

  @Test
  void anAcceptedWriteWithoutItsIdIsAnUnknownOutcome() {
    wireMock.stubFor(post(urlEqualTo("/v1/" + ORG + "/vouchers/manuel")).willReturn(okJson("{}")));

    assertThrows(
        VendorOutcomeUnknownException.class,
        () ->
            stubbedWriter()
                .createManualVoucher(
                    json("{\"Lines\":[{\"Amount\":100.00}]}"), new BigDecimal("100.00")));
  }

  @Test
  void aVendorRefusalIsAPlainFailure() {
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/vouchers/manuel"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(400)));

    VendorWriteException e =
        assertThrows(
            VendorWriteException.class,
            () ->
                stubbedWriter()
                    .createManualVoucher(
                        json("{\"Lines\":[{\"Amount\":100.00}]}"), new BigDecimal("100.00")));
    assertFalse(e instanceof VendorOutcomeUnknownException);
  }

  @Test
  void aCreatedVoucherWhoseReadBackFailsIsAnUnknownOutcome() {
    String guid = "voucher-guid-rb";
    wireMock.stubFor(
        post(urlEqualTo("/v1.2/" + ORG + "/vouchers/purchase"))
            .willReturn(okJson("{\"Guid\":\"" + guid + "\"}")));
    wireMock.stubFor(
        get(urlEqualTo("/v1/" + ORG + "/vouchers/purchase/" + guid))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(503)));

    VendorOutcomeUnknownException e =
        assertThrows(
            VendorOutcomeUnknownException.class,
            () ->
                stubbedWriter()
                    .createPurchaseVoucher(
                        json("{\"PurchaseType\":\"credit\"}"), new BigDecimal("15.24")));
    assertTrue(e.getMessage().contains(guid));
  }

  @Test
  void aVendorServerErrorRecordsTheStatusTheVendorSent() {
    wireMock.stubFor(
        post(urlEqualTo("/v1/" + ORG + "/vouchers/manuel"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.status(503)));

    VendorOutcomeUnknownException e =
        assertThrows(
            VendorOutcomeUnknownException.class,
            () ->
                stubbedWriter()
                    .createManualVoucher(
                        json("{\"Lines\":[{\"Amount\":100.00}]}"), new BigDecimal("100.00")));
    assertEquals(503, e.vendorStatus());
  }
}
