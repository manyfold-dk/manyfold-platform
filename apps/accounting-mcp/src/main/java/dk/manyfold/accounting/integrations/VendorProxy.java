package dk.manyfold.accounting.integrations;

/**
 * The credential-injecting, host-pinned outbound half of one vendor integration (ADR-0043).
 *
 * <p>Each vendor contributes one {@code @ApplicationScoped} {@code VendorProxy} bean. The {@link
 * IntegrationGateway} discovers them all by CDI and keys them on {@link #vendor()}, so a new vendor
 * (or a second Manyfold organization) is "creds + egress rule + one bean" with no change to the
 * gateway, the REST passthrough or the MCP {@code integration_get} tool.
 *
 * <p>Implementations own the per-vendor guardrails that cannot be generic: which credentials to
 * inject, which upstream host to pin, response-size and rate caps, and the inert-until-configured
 * (503) posture. The generic guardrails (role gate, vendor allowlist, read-only method, audit) live
 * once in the gateway.
 */
public interface VendorProxy {

  /**
   * The vendor key this proxy serves, e.g. {@code "dinero"} -- the {@code service} a caller names.
   */
  String vendor();

  /**
   * Forward {@code method} for {@code path} (+ raw {@code query}) to this vendor's pinned upstream,
   * injecting credentials server-side. Throws {@link jakarta.ws.rs.WebApplicationException}
   * carrying the guardrail status on rejection (e.g. 503 inert, 429 rate, 400 bad path, 502
   * oversized).
   *
   * @param method the HTTP method (the gateway only ever passes a read method)
   * @param path the upstream sub-path only -- never a scheme/authority (no SSRF)
   * @param query the raw query string, or {@code null} (pagination/filters pass through verbatim)
   */
  ProxyResult forward(String method, String path, String query);
}
