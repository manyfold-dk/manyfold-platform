package dk.manyfold.accounting.integrations.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dk.manyfold.accounting.integrations.CapturingAudit;
import dk.manyfold.accounting.integrations.CapturingAudit.Write;
import dk.manyfold.accounting.integrations.mcp.WriteToolRefusalAudit.Operation;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.security.spi.runtime.AuthorizationFailureEvent;
import io.quarkus.security.spi.runtime.MethodDescription;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@link WriteToolRefusalAudit} records and what it leaves alone. The event reaches it for
 * every refusal in the application, so it must pick out the write tools. {@link
 * WriteToolRefusalAuditIT} proves the events a real refusal fires.
 */
class WriteToolRefusalAuditTest {

  private static final String TOOL_CLASS = IntegrationWriteMcpTool.class.getName();

  private final CapturingAudit audit = CapturingAudit.recording();
  private final WriteToolRefusalAudit observer = new WriteToolRefusalAudit(audit);

  @Test
  void aRefusedWriteToolIsRecordedWithItsOperationSubjectAndStatus() {
    observer.onAuthorizationFailure(
        event(caller("outsider"), new ForbiddenException(), TOOL_CLASS, "dineroBookInvoice"));
    observer.onAuthorizationFailure(
        event(anonymous(), new UnauthorizedException(), TOOL_CLASS, "dineroUpsertContact"));

    assertEquals(
        List.of(
            new Write("dinero", "outsider", "book-invoice", null, 403, null),
            new Write("dinero", "anonymous", "upsert-contact", null, 401, null)),
        audit.writes());
  }

  @Test
  void aRefusalOfAnotherClassIsNotRecorded() {
    observer.onAuthorizationFailure(
        event(
            caller("outsider"), new ForbiddenException(), "some.other.Bean", "dineroBookInvoice"));

    assertEquals(List.of(), audit.writes());
  }

  /** The shared envelope is not a tool: only a tool method stands for a write attempt. */
  @Test
  void aRefusalOfAToolClassMethodThatIsNotAToolIsNotRecorded() {
    observer.onAuthorizationFailure(
        event(caller("outsider"), new ForbiddenException(), TOOL_CLASS, "run"));

    assertEquals(List.of(), audit.writes());
  }

  /** An HTTP permission check names no method. */
  @Test
  void aRefusalWithoutASecuredMethodIsNotRecorded() {
    observer.onAuthorizationFailure(
        new AuthorizationFailureEvent(
            anonymous(), new UnauthorizedException(), "HttpSecurityPolicy", Map.of()));

    assertEquals(List.of(), audit.writes());
  }

  @Test
  void anAuditFailureDoesNotEscapeTheObserver() {
    WriteToolRefusalAudit failing = new WriteToolRefusalAudit(CapturingAudit.failing());

    assertDoesNotThrow(
        () ->
            failing.onAuthorizationFailure(
                event(
                    caller("outsider"),
                    new ForbiddenException(),
                    TOOL_CLASS,
                    "dineroBookInvoice")));
  }

  @Test
  void aToolNameOutsideTheRuleIsRecordedWhole() {
    assertEquals(new Operation(null, "upload"), WriteToolRefusalAudit.operation("upload"));
    assertEquals(new Operation(null, "_upload"), WriteToolRefusalAudit.operation("_upload"));
  }

  private static AuthorizationFailureEvent event(
      SecurityIdentity identity, Throwable failure, String className, String methodName) {
    return new AuthorizationFailureEvent(
        identity,
        failure,
        "RolesAllowedCheck",
        Map.of(),
        new MethodDescription(className, methodName, new String[0]));
  }

  private static SecurityIdentity caller(String name) {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal(name))
        .addRole("integration-reader")
        .build();
  }

  private static SecurityIdentity anonymous() {
    return QuarkusSecurityIdentity.builder().setAnonymous(true).build();
  }
}
