package dk.manyfold.accounting.integrations.mcp;

import dk.manyfold.accounting.integrations.IntegrationGateway;
import dk.manyfold.accounting.integrations.ProxyResult;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;

/**
 * The MCP read front door for the accounting integration (ADR-0043): a single generic, read-only
 * tool served over the Streamable HTTP transport at {@code /mcp}. It adds no guardrails of its own
 * but delegates every call to the shared {@link IntegrationGateway}, the same in-process layer that
 * backs the REST passthrough -- so credential injection and the guardrails (role gate, vendor
 * allowlist, read-only, host pinning, rate/size caps, audit) stay defined once.
 *
 * <p>Auth is handled at the transport: {@code /mcp} is a bearer-only OIDC tenant (OAuth 2.1
 * Authorization Code + PKCE; see application.properties) requiring {@code aud=accounting-mcp}; the
 * gateway then enforces the {@code integration-reader} role. Vendor secrets never reach the agent.
 */
@ApplicationScoped
public class IntegrationMcpTool {

  @Inject IntegrationGateway gateway;

  @Tool(
      name = "integration_get",
      description =
          "Read-only GET against the Dinero accounting API of the configured organisation. The service "
              + "injects the Dinero credentials server-side and returns the upstream JSON verbatim. "
              + "Supply 'service' (the vendor key -- use \"dinero\"), 'path' (the upstream sub-path "
              + "only, no scheme or host), and an optional 'query' (raw query string for pagination/"
              + "filters). The literal token {organizationId} in the path is substituted server-side "
              + "with the configured OrganizationId (FirmaId), so you never need "
              + "the id itself. Examples: path \"v1/organizations\"; path "
              + "\"v1/{organizationId}/accountingyears\"; path "
              + "\"v1/{organizationId}/2024/reports/saldo\" (saldobalance); path "
              + "\"v1/{organizationId}/entries\" with query \"fromDate=2024-01-01&toDate=2024-12-31\". "
              + "Reporting only -- writes are separate named tools.",
      annotations =
          @Tool.Annotations(readOnlyHint = true, destructiveHint = false, openWorldHint = true))
  public String integrationGet(
      @ToolArg(name = "service", description = "Integration vendor key -- use \"dinero\".")
          String service,
      @ToolArg(
              name = "path",
              description =
                  "Upstream sub-path only, no scheme/host. {organizationId} is substituted "
                      + "server-side, e.g. \"v1/{organizationId}/accountingyears\".")
          String path,
      @ToolArg(
              name = "query",
              required = false,
              description =
                  "Optional raw query string, e.g. \"fromDate=2024-01-01&toDate=2024-12-31\".")
          String query) {
    ProxyResult res;
    try {
      res = gateway.read(service, "GET", path == null ? "" : path, blankToNull(query));
    } catch (WebApplicationException wae) {
      // Guardrail / auth rejection (401/403/404/405/429/502/503): surface the status, never the
      // vendor secret or arbitrary upstream bytes.
      int status = wae.getResponse() != null ? wae.getResponse().getStatus() : 500;
      throw new ToolCallException(
          "integration_get %s/%s rejected (HTTP %d)".formatted(service, path, status), wae);
    }
    if (res.status() < 200 || res.status() >= 300) {
      // The upstream itself returned a non-success status. Surface a capped snippet of the upstream
      // body too: for a 4xx that is the vendor's validation message, which is what a caller needs
      // to fix the request. It echoes our request, not our injected credentials, and the proxy
      // already size-caps the body.
      throw new ToolCallException(
          "integration_get %s/%s -> upstream HTTP %d%s"
              .formatted(service, path, res.status(), bodySnippet(res.body())));
    }
    return res.body() == null ? "" : new String(res.body(), StandardCharsets.UTF_8);
  }

  /** A trimmed, length-capped rendering of an upstream error body for the tool error message. */
  private static String bodySnippet(byte[] body) {
    if (body == null || body.length == 0) {
      return "";
    }
    String text = new String(body, StandardCharsets.UTF_8).strip();
    if (text.isEmpty()) {
      return "";
    }
    int cap = 1000;
    return ": " + (text.length() > cap ? text.substring(0, cap) + "...(truncated)" : text);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
