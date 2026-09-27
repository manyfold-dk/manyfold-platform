package dk.manyfold.accounting.integrations.dinero;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for Dinero host-pinning, {organizationId} substitution, SSRF rejection, inert.
 */
class DineroProxyClientTest {

  private static DineroProxyClient configured() {
    DineroConfig c = DineroTestConfig.configured();
    return new DineroProxyClient(
        c,
        new DineroTokenProvider(c, new DineroHttp()),
        new DineroRateLimiter(c),
        new DineroHttp());
  }

  private static int statusOf(Runnable r) {
    try {
      r.run();
      return 0;
    } catch (WebApplicationException e) {
      return e.getResponse().getStatus();
    }
  }

  @Test
  void buildsPinnedTargetWithPathAndQuery() {
    URI t = configured().buildTarget("v1/organizations", "page=1");
    assertEquals("https://api.dinero.dk/v1/organizations?page=1", t.toString());
    assertEquals("api.dinero.dk", t.getHost());
  }

  @Test
  void substitutesOrganizationIdPlaceholder() {
    URI t = configured().buildTarget("v1/{organizationId}/accountingyears", null);
    assertEquals("https://api.dinero.dk/v1/123456/accountingyears", t.toString());
  }

  @Test
  void substitutesShortOrgIdPlaceholder() {
    URI t = configured().buildTarget("v1/{orgId}/2024/reports/saldo", null);
    assertEquals("https://api.dinero.dk/v1/123456/2024/reports/saldo", t.toString());
  }

  @Test
  void rejectsAbsoluteUrl() {
    assertEquals(400, statusOf(() -> configured().buildTarget("http://evil.example/x", null)));
  }

  @Test
  void rejectsAuthorityInjectionViaLeadingSlash() {
    assertEquals(400, statusOf(() -> configured().buildTarget("/evil.example/x", null)));
  }

  @Test
  void rejectsPathTraversal() {
    assertEquals(400, statusOf(() -> configured().buildTarget("../../secret", null)));
  }

  @Test
  void inertWhenAnyCredentialBlank() {
    DineroConfig c = DineroTestConfig.of("https://api.dinero.dk", "id", "secret", "apikey", "");
    DineroProxyClient inert =
        new DineroProxyClient(
            c,
            new DineroTokenProvider(c, new DineroHttp()),
            new DineroRateLimiter(c),
            new DineroHttp());
    assertEquals(503, statusOf(() -> inert.forward("GET", "v1/organizations", null)));
  }
}
