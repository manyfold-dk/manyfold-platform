package dk.manyfold.accounting.integrations;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.manyfold.accounting.integrations.CapturingAudit.Write;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteResult;
import dk.manyfold.accounting.integrations.model.IntegrationWrite;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The executor's writer gate records the callers it turns away (issue #503), as the reader gate
 * does since #502: the receipt upload, which asks the gate before it reads the file, and a write
 * that reaches {@link IntegrationWriteExecutor#execute} without the role.
 */
@QuarkusTest
class WriterGateAuditTest {

  private static final byte[] RECEIPT = "%PDF-1.7\nreceipt".getBytes(StandardCharsets.US_ASCII);

  @Inject IntegrationWriteExecutor executor;

  private CapturingAudit audit;

  @BeforeEach
  void captureAudit() {
    install(CapturingAudit.recording());
  }

  private void install(CapturingAudit capturing) {
    audit = capturing;
    QuarkusMock.installMockForType(audit, IntegrationAudit.class);
  }

  @Test
  void anUnauthenticatedUploadIsRecordedAs401() {
    String key = freshKey();

    upload(key).then().statusCode(401);

    assertEquals(
        List.of(new Write("dinero", "anonymous", "upload-file", null, 401, null)), audit.writes());
    assertNull(ledgerRow(key), "a refused upload must not claim its key");
  }

  @Test
  @TestSecurity(user = "reader-only", roles = "integration-reader")
  void anUploadWithoutTheWriterRoleIsRecordedAs403() {
    String key = freshKey();

    upload(key).then().statusCode(403);

    assertEquals(
        List.of(new Write("dinero", "reader-only", "upload-file", null, 403, null)),
        audit.writes());
    assertNull(ledgerRow(key), "a refused upload must not claim its key");
  }

  /** The refusal stands and keeps its status: the audit failure does not become a 500. */
  @Test
  @TestSecurity(user = "reader-only", roles = "integration-reader")
  void aFailingAuditLeavesTheUploadRefusalIntact() {
    install(CapturingAudit.failing());
    String key = freshKey();

    upload(key).then().statusCode(403);

    assertNull(ledgerRow(key));
  }

  @Test
  @TestSecurity(user = "reader-only", roles = "integration-reader")
  void aFailingAuditIsAttachedToTheRefusalNotThrownInItsPlace() {
    install(CapturingAudit.failing());

    ForbiddenException refused =
        assertThrows(
            ForbiddenException.class, () -> executor.requireWriter("dinero", "upload-file"));

    assertEquals(1, refused.getSuppressed().length);
    assertTrue(refused.getSuppressed()[0] instanceof IllegalStateException);
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aWriterPassesTheGateUnrecorded() {
    executor.requireWriter("dinero", "upload-file");

    assertEquals(List.of(), audit.writes());
  }

  @Test
  @TestSecurity(user = "reader-only", roles = "integration-reader")
  void aWriteTheExecutorRefusesIsRecordedBeforeAnyVendorCall() {
    AtomicInteger vendorCalls = new AtomicInteger();

    assertThrows(
        ForbiddenException.class,
        () ->
            executor.execute(
                "dinero",
                "create-invoice-draft",
                freshKey(),
                "h1",
                "op=create-invoice-draft",
                () -> {
                  vendorCalls.incrementAndGet();
                  return new WriteResult(201, "x");
                }));

    assertEquals(0, vendorCalls.get());
    assertEquals(
        List.of(new Write("dinero", "reader-only", "create-invoice-draft", null, 403, null)),
        audit.writes());
  }

  private static io.restassured.response.Response upload(String key) {
    return given()
        .header("Idempotency-Key", key)
        .multiPart("file", "receipt.pdf", RECEIPT, "application/pdf")
        .when()
        .post("/api/integrations/dinero/files");
  }

  private static String freshKey() {
    return "writer-gate-" + UUID.randomUUID();
  }

  private static IntegrationWrite ledgerRow(String key) {
    return QuarkusTransaction.requiringNew()
        .call(() -> IntegrationWrite.<IntegrationWrite>find("idempotencyKey", key).firstResult());
  }
}
