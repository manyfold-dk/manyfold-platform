package dk.manyfold.accounting.integrations;

import dk.manyfold.accounting.security.IdentityResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The single in-process integration layer (ADR-0043). Both front doors -- the REST passthrough
 * ({@code /api/integrations/<vendor>/**}) and the MCP {@code integration_get} tool -- delegate
 * here, so the generic guardrails are defined exactly once:
 *
 * <ul>
 *   <li><b>Role gate</b> -- {@link IdentityResolver#requireIntegrationReader()} (the operator-owned
 *       {@code integration-reader} realm role; honours the dev stub). One role-resolution path.
 *   <li><b>Vendor allowlist</b> -- only a registered {@link VendorProxy} can be reached; an unknown
 *       {@code service} is rejected (404), never a default/guess.
 *   <li><b>Read-only</b> -- only GET/HEAD reach a vendor; any other method is rejected (405). The
 *       MCP tool only ever issues GET, so the read connector is read-only by construction.
 *   <li><b>Audit</b> -- every call, including one the role gate or a guardrail rejects, is
 *       attributed via {@link IntegrationAudit}.
 * </ul>
 *
 * <p>The per-vendor guardrails (credential injection, host pinning, rate/size caps, inert-503) live
 * in the {@link VendorProxy} implementation, so they too are defined once per vendor.
 */
@ApplicationScoped
public class IntegrationGateway {

  /**
   * The only methods a vendor proxy may be asked to forward -- this layer is read-only (ADR-0043).
   */
  private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");

  private final IdentityResolver identity;
  private final IntegrationAudit audit;
  private final Map<String, VendorProxy> proxies;

  @Inject
  public IntegrationGateway(
      IdentityResolver identity, IntegrationAudit audit, Instance<VendorProxy> vendorProxies) {
    this.identity = identity;
    this.audit = audit;
    Map<String, VendorProxy> byVendor = new HashMap<>();
    for (VendorProxy p : vendorProxies) {
      byVendor.put(p.vendor(), p);
    }
    this.proxies = Map.copyOf(byVendor);
  }

  /**
   * Perform a role-gated, allowlist-checked, audited read against {@code service}'s upstream.
   *
   * @param service the vendor key (e.g. {@code "dinero"}); unknown -&gt; 404
   * @param method the HTTP method; must be GET or HEAD, else 405
   * @param path the upstream sub-path only (no scheme/host); validated by the vendor proxy
   * @param query the raw query string or {@code null} (pagination/filters pass through)
   * @return the upstream {@link ProxyResult}
   * @throws WebApplicationException 401 unauthenticated, 403 missing role, 404 unknown vendor, 405
   *     non-read method, or whatever guardrail status the vendor proxy raises
   */
  public ProxyResult read(String service, String method, String path, String query) {
    long startNanos = System.nanoTime();
    String svc = service == null ? "" : service;
    // Resolved without the gate, which runs inside the audited block: a caller the gate turns away
    // (401/403) is in the audit trail too.
    String subject = identity.auditSubject();
    int status = 500;
    long bytes = 0;
    try {
      identity.requireIntegrationReader();
      if (!READ_METHODS.contains(method)) {
        throw new WebApplicationException("method not allowed: " + method, 405);
      }
      VendorProxy proxy = proxies.get(svc);
      if (proxy == null) {
        throw new WebApplicationException("unknown integration vendor: " + svc, 404);
      }
      ProxyResult res = proxy.forward(method, path, query);
      status = res.status();
      bytes = res.body() == null ? 0 : res.body().length;
      return res;
    } catch (WebApplicationException wae) {
      status = wae.getResponse() != null ? wae.getResponse().getStatus() : 500;
      throw wae;
    } finally {
      audit.record(
          svc, subject, method, path, status, bytes, (System.nanoTime() - startNanos) / 1_000_000);
    }
  }
}
