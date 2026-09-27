package dk.manyfold.accounting.integrations.dinero;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dk.manyfold.accounting.integrations.dinero.DineroTokenProvider.TokenResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for the Dinero token: Basic client-auth header + cache/refresh-skew behaviour.
 */
class DineroTokenProviderTest {

  @Test
  void basicAuthHeaderIsBase64OfClientColonSecret() {
    DineroConfig c = DineroTestConfig.of("https://api.dinero.dk", "cid", "csecret", "k", "1");
    String header = new DineroTokenProvider(c, new DineroHttp()).basicAuthHeader();
    String expected =
        "Basic "
            + Base64.getEncoder().encodeToString("cid:csecret".getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, header);
  }

  @Test
  void cachesTokenUntilRefreshSkewThenRefetches() {
    AtomicInteger fetches = new AtomicInteger();
    DineroConfig c = DineroTestConfig.configured();
    DineroTokenProvider p =
        new DineroTokenProvider(c, new DineroHttp()) {
          @Override
          TokenResponse fetchToken() {
            return new TokenResponse("tok-" + fetches.incrementAndGet(), 3600);
          }
        };
    // First call fetches; a call well within the TTL (minus 60s skew) reuses the cached token.
    assertEquals("tok-1", p.accessToken(0L));
    assertEquals("tok-1", p.accessToken(3_500_000L));
    // Past (expiresIn - 60s) * 1000 the cache is stale and a new grant runs (no refresh token).
    assertNotEquals("tok-1", p.accessToken(3_541_000L));
    assertEquals(2, fetches.get());
  }
}
