package dk.manyfold.accounting.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read audit trail covers the callers the role gate turns away (issue #397): the gate used to
 * throw before the audited block, so an unauthorized read left no record.
 */
@QuarkusTest
class IntegrationGatewayTest {

  @Inject IntegrationGateway gateway;

  private CapturingAudit audit;

  /** One recorded read. */
  record Entry(String vendor, String subject, String method, String path, int status) {}

  /** Keeps what the gateway records instead of logging it. */
  static class CapturingAudit extends IntegrationAudit {
    final List<Entry> entries = new ArrayList<>();

    @Override
    public void record(
        String vendor,
        String subject,
        String method,
        String path,
        int status,
        long bytes,
        long durationMs) {
      entries.add(new Entry(vendor, subject, method, path, status));
    }
  }

  @BeforeEach
  void captureAudit() {
    audit = new CapturingAudit();
    QuarkusMock.installMockForType(audit, IntegrationAudit.class);
  }

  @Test
  @TestSecurity(user = "outsider")
  void aCallerWithoutTheReaderRoleIsRecordedAs403() {
    assertThrows(
        ForbiddenException.class,
        () -> gateway.read("dinero", "GET", "v1/{organizationId}/contacts", null));

    assertEquals(
        List.of(new Entry("dinero", "outsider", "GET", "v1/{organizationId}/contacts", 403)),
        audit.entries);
  }

  @Test
  void anUnauthenticatedCallerIsRecordedAs401() {
    assertThrows(
        NotAuthorizedException.class,
        () -> gateway.read("dinero", "GET", "v1/{organizationId}/contacts", null));

    assertEquals(
        List.of(new Entry("dinero", "anonymous", "GET", "v1/{organizationId}/contacts", 401)),
        audit.entries);
  }

  @Test
  @TestSecurity(user = "reader", roles = "integration-reader")
  void aReaderRejectedByAGuardrailIsRecordedWithItsStatus() {
    WebApplicationException e =
        assertThrows(
            WebApplicationException.class,
            () -> gateway.read("no-such-vendor", "GET", "v1/organizations", null));

    assertEquals(404, e.getResponse().getStatus());
    assertEquals(
        List.of(new Entry("no-such-vendor", "reader", "GET", "v1/organizations", 404)),
        audit.entries);
  }
}
