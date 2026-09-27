package dk.manyfold.accounting.integrations.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.manyfold.accounting.integrations.CapturingAudit;
import dk.manyfold.accounting.integrations.CapturingAudit.Write;
import dk.manyfold.accounting.integrations.GuardrailException;
import dk.manyfold.accounting.integrations.IntegrationAudit;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor;
import dk.manyfold.accounting.integrations.mcp.WriteToolRefusalAudit.Operation;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A write tool call the class-level {@code @RolesAllowed} turns away is in the write audit trail
 * (issue #503). The calls go through the real MCP transport and the real security interceptor, so a
 * record here proves Quarkus fires the {@code AuthorizationFailureEvent} for a refused tool method
 * and names it the way {@link WriteToolRefusalAudit} expects.
 */
@QuarkusTest
class WriteToolRefusalAuditIT {

  private static final String TOOL = "dinero_create_manual_voucher";

  @Inject IntegrationWriteMcpTool tools;

  private CapturingAudit audit;
  private RecordingExecutor executor;

  /** Stands in for the executor: records what a tool hands it, then refuses the write. */
  static class RecordingExecutor extends IntegrationWriteExecutor {
    final List<Operation> calls = new CopyOnWriteArrayList<>();

    RecordingExecutor() {
      super(null, null);
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
      calls.add(new Operation(vendor, operation));
      throw new GuardrailException("recorded by the test", 409);
    }
  }

  @BeforeEach
  void installDoubles() {
    install(CapturingAudit.recording());
    executor = new RecordingExecutor();
    QuarkusMock.installMockForType(executor, IntegrationWriteExecutor.class);
  }

  private void install(CapturingAudit capturing) {
    audit = capturing;
    QuarkusMock.installMockForType(audit, IntegrationAudit.class);
  }

  @Test
  void anUnauthenticatedCallIsRecordedAs401() {
    callExpectingSecurityError("UnauthorizedException");

    assertEquals(
        List.of(new Write("dinero", "anonymous", "create-manual-voucher", null, 401, null)),
        audit.writes());
    assertEquals(List.of(), executor.calls, "the tool body must not run");
  }

  @Test
  @TestSecurity(user = "reader-only", roles = "integration-reader")
  void aCallerWithoutTheWriterRoleIsRecordedAs403() {
    callExpectingSecurityError("ForbiddenException");

    assertEquals(
        List.of(new Write("dinero", "reader-only", "create-manual-voucher", null, 403, null)),
        audit.writes());
    assertEquals(List.of(), executor.calls, "the tool body must not run");
  }

  /** The observer swallows the audit failure: the caller still gets the security error. */
  @Test
  @TestSecurity(user = "reader-only", roles = "integration-reader")
  void aFailingAuditLeavesTheRefusalIntact() {
    install(CapturingAudit.failing());

    callExpectingSecurityError("ForbiddenException");

    assertEquals(List.of(), executor.calls, "the tool body must not run");
  }

  /** A writer passes the interceptor; nothing is recorded as refused. */
  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aWriterIsNotRecordedAsRefused() {
    try (McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
      client
          .when()
          .toolsCall(TOOL)
          .withArguments(arguments())
          .withAssert(response -> assertTrue(response.isError()))
          .send()
          .thenAssertResults();
    }

    assertEquals(List.of(new Operation("dinero", "create-manual-voucher")), executor.calls);
    assertEquals(List.of(), audit.writes());
  }

  /**
   * The refusal record names each tool by the vendor and operation the tool itself hands the
   * executor, so a refused call and an admitted one of the same tool share one {@code op} in the
   * audit trail. Covers every {@code @Tool} method: a new tool whose name breaks the rule fails
   * here.
   */
  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void everyWriteToolIsRecordedUnderTheOperationItHandsTheExecutor() throws Exception {
    int checked = 0;
    for (Method method : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool tool = method.getAnnotation(Tool.class);
      if (tool == null) {
        continue;
      }
      int before = executor.calls.size();

      InvocationTargetException refused =
          assertThrows(
              InvocationTargetException.class,
              () -> method.invoke(tools, arguments(method)),
              tool.name());
      assertInstanceOf(ToolCallException.class, refused.getCause(), tool.name());

      assertEquals(before + 1, executor.calls.size(), tool.name() + " did not reach the executor");
      assertEquals(
          executor.calls.get(before), WriteToolRefusalAudit.operation(tool.name()), tool.name());
      checked++;
    }
    assertEquals(7, checked);
  }

  private void callExpectingSecurityError(String exception) {
    try (McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
      client
          .when()
          .toolsCall(TOOL)
          .withArguments(arguments())
          .withErrorAssert(
              error -> {
                assertEquals(-32001, error.code(), error.message());
                assertTrue(error.message().contains(exception), error.message());
                assertFalse(error.message().contains("audit unavailable"), error.message());
              })
          .send()
          .thenAssertResults();
    }
  }

  private static Map<String, Object> arguments() {
    return Map.of(
        "idempotency_key",
        "refusal-" + UUID.randomUUID(),
        "expected_total",
        "100.00",
        "body",
        "{\"Lines\":[{\"Amount\":100.00}]}");
  }

  /** Values each tool accepts far enough to reach the executor. */
  private static Object[] arguments(Method method) {
    Parameter[] parameters = method.getParameters();
    Object[] values = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      String name = parameters[i].getAnnotation(ToolArg.class).name();
      values[i] =
          switch (name) {
            case "idempotency_key" -> "mapping-" + UUID.randomUUID();
            case "body" -> "{}";
            case "expected_total", "expected_amount" -> "1.00";
            default -> "00000000-0000-0000-0000-000000000000";
          };
    }
    return values;
  }
}
