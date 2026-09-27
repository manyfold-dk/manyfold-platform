package dk.manyfold.accounting.integrations;

import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteOutcome;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteResult;
import dk.manyfold.accounting.integrations.dinero.DineroWriter;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * Named multipart front door for receipt uploads. This is deliberately separate from the generic
 * read-only {@link IntegrationGateway}: it resolves only a registered writer that explicitly
 * supports {@code upload-file}, then routes the operation through {@link IntegrationWriteExecutor}.
 */
@Path("/api/integrations/{vendor}/files")
public class IntegrationFileUploadResource {

  static final String OPERATION = "upload-file";
  private static final Logger LOG = Logger.getLogger(IntegrationFileUploadResource.class);

  private final IntegrationWriteExecutor executor;
  private final Map<String, DineroWriter> writers;

  @Inject
  public IntegrationFileUploadResource(
      IntegrationWriteExecutor executor, Instance<VendorWriter> registeredWriters) {
    this(executor, uploadWriters(registeredWriters));
  }

  IntegrationFileUploadResource(
      IntegrationWriteExecutor executor, Map<String, DineroWriter> writers) {
    this.executor = executor;
    this.writers = Map.copyOf(writers);
  }

  @POST
  @Consumes(MediaType.MULTIPART_FORM_DATA)
  @Produces(MediaType.APPLICATION_JSON)
  @Blocking
  public Response upload(
      @PathParam("vendor") String vendor,
      @HeaderParam("Idempotency-Key") String idempotencyKey,
      @RestForm("file") FileUpload file) {
    try {
      // Authorize before reading the staged multipart file into application memory.
      executor.requireWriter();
      IntegrationWriteKeys.validateKey(idempotencyKey);
      DineroWriter writer = writer(vendor);
      if (file == null) {
        throw new BadRequestException();
      }
      if (file.size() > DineroWriter.MAX_UPLOAD_BYTES) {
        throw new WebApplicationException(413);
      }

      byte[] content = Files.readAllBytes(file.uploadedFile());
      String detectedContentType = DineroWriter.detectContentType(content);
      String safeFileName = sanitizeFileName(file.fileName(), detectedContentType);
      String summary =
          "op=" + OPERATION + ";bytes=" + content.length + ";type=" + detectedContentType;
      WriteOutcome outcome =
          executor.execute(
              vendor,
              OPERATION,
              idempotencyKey,
              // The key binds the whole upload: the bytes, and the name and type the caller
              // claims, so a reused key with a different name is a conflict, not a silent dedup.
              IntegrationWriteKeys.hash(
                  IntegrationWriteKeys.hash(content)
                      + "|"
                      + safeFileName
                      + "|"
                      + file.contentType()),
              // Keys recorded before that bound the bytes alone; a retry of one still dedups.
              IntegrationWriteKeys.hash(content),
              summary,
              () -> toResult(writer.uploadFile(content, safeFileName, file.contentType())));

      String fileGuid = outcome.vendorId();
      if (fileGuid == null || fileGuid.isBlank()) {
        throw new WebApplicationException(502);
      }
      return Response.ok(Map.of("FileGuid", fileGuid, "deduplicated", outcome.deduplicated()))
          .build();
    } catch (WebApplicationException exception) {
      if (exception.getCause() instanceof VendorOutcomeUnknownException) {
        // A fixed message, no vendor detail: the key stays reserved (on the first attempt and on
        // every retry), and the caller must not upload the same file under a new key before
        // checking the vendor.
        return Response.status(exception.getResponse().getStatus())
            .entity(
                Map.of(
                    "outcome",
                    "unknown",
                    "message",
                    "The upload may have reached the vendor. Do not retry; check the vendor."))
            .build();
      }
      return statusOnly(exception.getResponse());
    } catch (IOException exception) {
      LOG.error("Could not read the staged receipt upload", exception);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    } catch (RuntimeException exception) {
      LOG.error("Unexpected receipt upload failure", exception);
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
  }

  /** Convert cosmetic caller metadata to the safe ASCII filename accepted by DineroWriter. */
  static String sanitizeFileName(String fileName, String detectedContentType) {
    String name = fileName == null ? "" : fileName;
    int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (separator >= 0) {
      name = name.substring(separator + 1);
    }
    int extension = name.lastIndexOf('.');
    if (extension > 0) {
      name = name.substring(0, extension);
    }

    String normalized = Normalizer.normalize(name, Normalizer.Form.NFKD);
    StringBuilder stem = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i++) {
      char character = normalized.charAt(i);
      if (isAsciiAlphaNumeric(character) || character == '-' || character == '_') {
        stem.append(character);
      } else if (Character.getType(character) != Character.NON_SPACING_MARK
          && stem.length() > 0
          && stem.charAt(stem.length() - 1) != '_') {
        stem.append('_');
      }
    }
    while (!stem.isEmpty() && stem.charAt(stem.length() - 1) == '_') {
      stem.setLength(stem.length() - 1);
    }
    if (stem.isEmpty()) {
      stem.append("receipt");
    } else if (!isAsciiAlphaNumeric(stem.charAt(0))) {
      stem.insert(0, "receipt_");
    }

    String safeExtension =
        switch (detectedContentType) {
          case "application/pdf" -> ".pdf";
          case "image/png" -> ".png";
          case "image/jpeg" -> ".jpg";
          default -> throw new BadRequestException();
        };
    int maxStemLength = 128 - safeExtension.length();
    if (stem.length() > maxStemLength) {
      stem.setLength(maxStemLength);
    }
    return stem + safeExtension;
  }

  private static boolean isAsciiAlphaNumeric(char character) {
    return (character >= 'A' && character <= 'Z')
        || (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9');
  }

  private DineroWriter writer(String vendor) {
    DineroWriter writer = writers.get(vendor == null ? "" : vendor);
    if (writer == null) {
      throw new NotFoundException();
    }
    return writer;
  }

  private static Map<String, DineroWriter> uploadWriters(Instance<VendorWriter> registeredWriters) {
    Map<String, DineroWriter> byVendor = new HashMap<>();
    for (VendorWriter writer : registeredWriters) {
      if (writer instanceof DineroWriter dinero && writer.operations().contains(OPERATION)) {
        byVendor.put(writer.vendor(), dinero);
      }
    }
    return byVendor;
  }

  private static WriteResult toResult(DineroWriter.Result result) {
    return new WriteResult(result.status(), result.id());
  }

  private static Response statusOnly(Response error) {
    int status = error == null ? 500 : error.getStatus();
    return Response.status(status).build();
  }
}
