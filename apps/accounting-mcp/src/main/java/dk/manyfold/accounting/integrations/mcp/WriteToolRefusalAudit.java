package dk.manyfold.accounting.integrations.mcp;

import dk.manyfold.accounting.integrations.IntegrationAudit;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor;
import dk.manyfold.accounting.security.IdentityResolver;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.spi.runtime.AuthorizationFailureEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Records the MCP write tools' {@code @RolesAllowed} refusals in the write audit trail (issue
 * #503). The class-level {@code @RolesAllowed(integration-writer)} on {@link
 * IntegrationWriteMcpTool} turns a caller away before the tool method runs, so {@link
 * IntegrationWriteExecutor}, which audits every write it sees, never sees this one. Quarkus fires
 * an {@link AuthorizationFailureEvent} for the refusal and then rethrows it. This observer records
 * the tool's vendor and operation, the caller's subject ({@code anonymous} when unauthenticated)
 * and 401 or 403.
 *
 * <p>Only the write tools: an event whose secured method is not a {@code @Tool} method of {@link
 * IntegrationWriteMcpTool} -- an HTTP permission check, any other secured bean -- is not recorded
 * here.
 *
 * <p>The observer is synchronous: it runs on the refused call, before Quarkus rethrows the refusal.
 * It never throws, because an exception from an observer reaches the caller in place of the refusal
 * and turns a security error into an internal error. A failed audit is logged and the refusal
 * stands. Nothing here can let the call through: the check has already failed.
 */
@ApplicationScoped
public class WriteToolRefusalAudit {

  private static final Logger LOG = Logger.getLogger(WriteToolRefusalAudit.class);

  /** The vendor and operation a write tool passes to the executor. */
  record Operation(String vendor, String name) {}

  private final IntegrationAudit audit;

  /** Keyed like the event's secured method: {@code <class name>#<method name>}. */
  private final Map<String, Operation> writeTools;

  @Inject
  public WriteToolRefusalAudit(IntegrationAudit audit) {
    this.audit = audit;
    Map<String, Operation> tools = new HashMap<>();
    for (Method method : IntegrationWriteMcpTool.class.getDeclaredMethods()) {
      Tool tool = method.getAnnotation(Tool.class);
      if (tool != null) {
        tools.put(securedMethod(method), operation(tool.name()));
      }
    }
    this.writeTools = Map.copyOf(tools);
  }

  void onAuthorizationFailure(@Observes AuthorizationFailureEvent event) {
    try {
      Operation refused =
          event.getEventProperties().get(AuthorizationFailureEvent.SECURED_METHOD_KEY)
                  instanceof String method
              ? writeTools.get(method)
              : null;
      if (refused != null) {
        audit.recordWrite(
            refused.vendor(),
            IdentityResolver.auditSubject(event.getSecurityIdentity()),
            refused.name(),
            null,
            status(event),
            null,
            false,
            0);
      }
    } catch (RuntimeException auditFailure) {
      LOG.warn("could not audit a refused write tool call; the refusal stands", auditFailure);
    }
  }

  /** The secured method as the event names it (Quarkus' {@code MethodDescription} string form). */
  static String securedMethod(Method method) {
    return method.getDeclaringClass().getName() + "#" + method.getName();
  }

  /**
   * The vendor and operation of a write tool, by the naming rule {@code <vendor>_<operation>} with
   * underscores for the operation's hyphens: {@code dinero_book_invoice} is {@code dinero} / {@code
   * book-invoice}, as that tool passes them to the executor. {@code WriteToolRefusalAuditIT} pins
   * the rule against every tool. A name outside the rule is recorded whole, under no vendor.
   */
  static Operation operation(String toolName) {
    int separator = toolName.indexOf('_');
    if (separator < 1) {
      return new Operation(null, toolName);
    }
    return new Operation(
        toolName.substring(0, separator), toolName.substring(separator + 1).replace('_', '-'));
  }

  /** 401 for an unauthenticated caller, 403 for an authenticated caller without the role. */
  static int status(AuthorizationFailureEvent event) {
    return event.getAuthorizationFailure() instanceof UnauthorizedException ? 401 : 403;
  }
}
