package dk.manyfold.accounting.integrations;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteAction;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteOutcome;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteResult;
import dk.manyfold.accounting.integrations.dinero.DineroConfig;
import dk.manyfold.accounting.integrations.dinero.DineroWriter;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IntegrationFileUploadResourceTest {

  @TempDir Path tempDir;

  @Test
  void uploadsDecodedBytesThroughWriteExecutorWithoutIdentifyingSummary() throws Exception {
    byte[] content = "%PDF-1.7\nreceipt".getBytes(StandardCharsets.US_ASCII);
    FileUpload upload = upload("Supplier_Receipt.pdf", "application/pdf", content);
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();
    IntegrationFileUploadResource resource = resource(executor, writer);

    Reply response = Reply.of(resource.upload("dinero", "receipt-2026-07", upload));

    assertEquals(200, response.status());
    assertEquals(Map.of("FileGuid", "file-guid", "deduplicated", false), response.entity());
    assertTrue(executor.writerAuthorized);
    assertEquals("dinero", executor.vendor);
    assertEquals("upload-file", executor.operation);
    assertEquals("receipt-2026-07", executor.idempotencyKey);
    // The key binds the bytes and the claimed name and type together.
    assertEquals(
        IntegrationWriteKeys.hash(
            IntegrationWriteKeys.hash(content) + "|Supplier_Receipt.pdf|application/pdf"),
        executor.requestHash);
    assertEquals(IntegrationWriteKeys.hash(content), executor.legacyRequestHash);
    assertEquals(
        "op=upload-file;bytes=" + content.length + ";type=application/pdf", executor.summary);
    assertFalse(executor.summary.contains("Supplier"));
    assertArrayEquals(content, writer.content);
    assertEquals("Supplier_Receipt.pdf", writer.fileName);
    assertEquals("application/pdf", writer.contentType);
  }

  @Test
  void rejectsUnknownWriterWithStatusOnly() throws Exception {
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response =
        Reply.of(
            resource(executor, writer)
                .upload(
                    "unknown",
                    "receipt-2026-07",
                    upload(
                        "receipt.pdf",
                        "application/pdf",
                        "%PDF".getBytes(StandardCharsets.US_ASCII))));

    assertEquals(404, response.status());
    assertFalse(response.hasEntity());
    assertTrue(executor.writerAuthorized);
    assertFalse(executor.executed);
    assertFalse(writer.uploaded);
  }

  @Test
  void rejectsMalformedIdempotencyKeyWithStatusOnly() throws Exception {
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response =
        Reply.of(
            resource(executor, writer)
                .upload(
                    "dinero",
                    "contains spaces",
                    upload(
                        "receipt.pdf",
                        "application/pdf",
                        "%PDF".getBytes(StandardCharsets.US_ASCII))));

    assertEquals(400, response.status());
    assertFalse(response.hasEntity());
    assertFalse(executor.executed);
    assertFalse(writer.uploaded);
  }

  @Test
  void rejectsUnsupportedMagicServerSideWithStatusOnly() throws Exception {
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response =
        Reply.of(
            resource(executor, writer)
                .upload(
                    "dinero",
                    "receipt-2026-07",
                    upload(
                        "receipt.txt",
                        "text/plain",
                        "not a receipt".getBytes(StandardCharsets.UTF_8))));

    assertEquals(400, response.status());
    assertFalse(response.hasEntity());
    assertFalse(executor.executed);
    assertFalse(writer.uploaded);
  }

  @Test
  void rejectsMissingWriterRoleBeforeReadingFile() {
    CapturingExecutor executor = new CapturingExecutor();
    executor.authorizationFailure = new ForbiddenException();
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response = Reply.of(resource(executor, writer).upload("dinero", "receipt-2026-07", null));

    assertEquals(403, response.status());
    assertFalse(response.hasEntity());
    assertFalse(executor.executed);
    assertFalse(writer.uploaded);
  }

  @Test
  void rejectsMissingFileWithStatusOnly() {
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response = Reply.of(resource(executor, writer).upload("dinero", "receipt-2026-07", null));

    assertEquals(400, response.status());
    assertFalse(response.hasEntity());
    assertTrue(executor.writerAuthorized);
    assertFalse(executor.executed);
    assertFalse(writer.uploaded);
  }

  @Test
  void rejectsOversizedFileWithStatusOnly() throws Exception {
    byte[] content = "%PDF".getBytes(StandardCharsets.US_ASCII);
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();
    FileUpload upload = upload("receipt.pdf", "application/pdf", content);
    upload =
        new TestFileUpload(
            upload.uploadedFile(),
            upload.fileName(),
            upload.contentType(),
            DineroWriter.MAX_UPLOAD_BYTES + 1L);

    Reply response =
        Reply.of(resource(executor, writer).upload("dinero", "receipt-2026-07", upload));

    assertEquals(413, response.status());
    assertFalse(response.hasEntity());
    assertFalse(executor.executed);
    assertFalse(writer.uploaded);
  }

  @Test
  void sanitizesCosmeticFilenameBeforeCallingWriter() throws Exception {
    byte[] content = "%PDF".getBytes(StandardCharsets.US_ASCII);
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response =
        Reply.of(
            resource(executor, writer)
                .upload(
                    "dinero",
                    "receipt-2026-07",
                    upload("Kvittering 2026-07-01 æøå.pdf", "application/pdf", content)));

    assertEquals(200, response.status());
    assertEquals("Kvittering_2026-07-01_a.pdf", writer.fileName);
  }

  @Test
  void rejectsMissingFileGuidWithStatusOnly() throws Exception {
    CapturingExecutor executor = new CapturingExecutor();
    CapturingDineroWriter writer = new CapturingDineroWriter();
    writer.returnsNoFileGuid = true;

    Reply response =
        Reply.of(
            resource(executor, writer)
                .upload(
                    "dinero",
                    "receipt-2026-07",
                    upload(
                        "receipt.pdf",
                        "application/pdf",
                        "%PDF".getBytes(StandardCharsets.US_ASCII))));

    assertEquals(502, response.status());
    assertFalse(response.hasEntity());
  }

  @Test
  void removesVendorErrorDetailsFromResponse() throws Exception {
    CapturingExecutor executor = new CapturingExecutor();
    executor.executionFailure = new WebApplicationException("vendor secret detail", 502);
    CapturingDineroWriter writer = new CapturingDineroWriter();

    Reply response =
        Reply.of(
            resource(executor, writer)
                .upload(
                    "dinero",
                    "receipt-2026-07",
                    upload(
                        "receipt.pdf",
                        "application/pdf",
                        "%PDF".getBytes(StandardCharsets.US_ASCII))));

    assertEquals(502, response.status());
    assertFalse(response.hasEntity());
  }

  @Test
  void anUnknownVendorOutcomeTellsTheCallerNotToRetry() throws Exception {
    CapturingExecutor executor = new CapturingExecutor();
    executor.executionFailure =
        new GuardrailException(
            "outcome unknown", 502, new VendorOutcomeUnknownException(502, "dinero answered 502"));

    Reply response =
        Reply.of(
            resource(executor, new CapturingDineroWriter())
                .upload(
                    "dinero",
                    "receipt-2026-07",
                    upload(
                        "receipt.pdf",
                        "application/pdf",
                        "%PDF".getBytes(StandardCharsets.US_ASCII))));

    assertEquals(502, response.status());
    assertEquals(
        Map.of(
            "outcome",
            "unknown",
            "message",
            "The upload may have reached the vendor. Do not retry; check the vendor."),
        response.entity());
  }

  private IntegrationFileUploadResource resource(
      CapturingExecutor executor, CapturingDineroWriter writer) {
    return new IntegrationFileUploadResource(executor, Map.of("dinero", writer));
  }

  private FileUpload upload(String fileName, String contentType, byte[] content) throws Exception {
    Path path = tempDir.resolve(fileName);
    Files.write(path, content);
    return new TestFileUpload(path, fileName, contentType, content.length);
  }

  private static final class CapturingExecutor extends IntegrationWriteExecutor {
    private boolean writerAuthorized;
    private boolean executed;
    private RuntimeException authorizationFailure;
    private RuntimeException executionFailure;
    private String vendor;
    private String operation;
    private String idempotencyKey;
    private String requestHash;
    private String legacyRequestHash;
    private String summary;

    private CapturingExecutor() {
      super(null, null);
    }

    @Override
    public void requireWriter() {
      writerAuthorized = true;
      if (authorizationFailure != null) {
        throw authorizationFailure;
      }
    }

    @Override
    public WriteOutcome execute(
        String vendor,
        String operation,
        String idempotencyKey,
        String requestHash,
        String legacyRequestHash,
        String inputsSummary,
        WriteAction action) {
      this.legacyRequestHash = legacyRequestHash;
      executed = true;
      this.vendor = vendor;
      this.operation = operation;
      this.idempotencyKey = idempotencyKey;
      this.requestHash = requestHash;
      this.summary = inputsSummary;
      if (executionFailure != null) {
        throw executionFailure;
      }
      WriteResult result = action.run();
      return new WriteOutcome(false, true, result.vendorStatus(), result.vendorId(), null);
    }
  }

  /** What a test asserts on; reading it closes the resource's response. */
  private record Reply(int status, Object entity, boolean hasEntity) {
    static Reply of(Response response) {
      try (response) {
        return new Reply(
            response.getStatus(),
            response.hasEntity() ? response.getEntity() : null,
            response.hasEntity());
      }
    }
  }

  private static final class CapturingDineroWriter extends DineroWriter {
    private byte[] content;
    private String fileName;
    private String contentType;
    private boolean uploaded;
    private boolean returnsNoFileGuid;

    private CapturingDineroWriter() {
      super(testConfig(), null, null, null);
    }

    @Override
    public Result uploadFile(byte[] content, String fileName, String contentType) {
      uploaded = true;
      this.content = content.clone();
      this.fileName = fileName;
      this.contentType = contentType;
      return new Result(201, returnsNoFileGuid ? null : "file-guid");
    }
  }

  private static DineroConfig testConfig() {
    return new DineroConfig() {
      @Override
      public String baseUrl() {
        return "https://api.dinero.dk";
      }

      @Override
      public String authUrl() {
        return "https://authz.dinero.dk/dineroapi/oauth/token";
      }

      @Override
      public Optional<String> clientId() {
        return Optional.empty();
      }

      @Override
      public Optional<String> clientSecret() {
        return Optional.empty();
      }

      @Override
      public Optional<String> apiKey() {
        return Optional.empty();
      }

      @Override
      public Optional<String> organizationId() {
        return Optional.empty();
      }

      @Override
      public long maxResponseBytes() {
        return 1024;
      }

      @Override
      public int rateLimitPerMinute() {
        return 1000;
      }
    };
  }

  private record TestFileUpload(Path filePath, String fileName, String contentType, long size)
      implements FileUpload {

    @Override
    public String name() {
      return "file";
    }

    @Override
    public String charSet() {
      return null;
    }

    @Override
    public MultivaluedMap<String, String> getHeaders() {
      return new MultivaluedHashMap<>();
    }
  }
}
