package dk.manyfold.accounting.integrations.dinero;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Acquires and caches Dinero's personal-integration bearer token (ADR-0043). Dinero's token
 * endpoint expects HTTP Basic client authentication ({@code base64(client_id:client_secret)}) with
 * a form-encoded <b>password</b> grant whose username and password are both the organization API
 * key -- so this does not use {@code quarkus-oidc-client}. The response carries no refresh token,
 * so the provider simply re-runs the password grant when the (1h) token nears expiry.
 */
@ApplicationScoped
public class DineroTokenProvider {

  private static final long REFRESH_SKEW_SECONDS = 60;

  private final DineroConfig config;
  private final DineroHttp http;

  private String cachedToken;
  private long expiresAtMillis;

  @Inject
  public DineroTokenProvider(DineroConfig config, DineroHttp http) {
    this.config = config;
    this.http = http;
  }

  /** Subset of Dinero's token response consumed by the passthrough. */
  record TokenResponse(
      @JsonProperty("access_token") String accessToken,
      @JsonProperty("expires_in") long expiresIn) {}

  /**
   * Return a valid bearer, fetching or refreshing it when needed. The clock is supplied by the
   * caller so cache behavior stays deterministic in unit tests.
   */
  public synchronized String accessToken(long nowMillis) {
    if (cachedToken != null && nowMillis < expiresAtMillis) {
      return cachedToken;
    }
    TokenResponse tr = fetchToken();
    if (tr == null || tr.accessToken() == null || tr.accessToken().isBlank()) {
      throw new WebApplicationException("dinero token fetch returned no access_token", 502);
    }
    cachedToken = tr.accessToken();
    long ttlSeconds = Math.max(0, tr.expiresIn() - REFRESH_SKEW_SECONDS);
    expiresAtMillis = nowMillis + ttlSeconds * 1000L;
    return cachedToken;
  }

  /**
   * Test-support: clear the cached bearer so a strict-token integration test starts from a cold
   * cache. This bean is {@code @ApplicationScoped}, so a token cached by one test class otherwise
   * leaks into the next in the same JVM. Package-private -- only Dinero tests in this package use
   * it.
   */
  @SuppressWarnings("PMD.NullAssignment") // null is the cache's "empty" state
  synchronized void resetCacheForTests() {
    cachedToken = null;
    expiresAtMillis = 0;
  }

  /** Package-private so the cache logic can be unit-tested with a fake token fetch. */
  TokenResponse fetchToken() {
    Form form =
        new Form()
            .param("grant_type", "password")
            .param("scope", "read write")
            .param("username", config.apiKey().orElse(""))
            .param("password", config.apiKey().orElse(""));
    try (Response resp =
        http.get()
            .target(config.authUrl())
            .request(MediaType.APPLICATION_JSON)
            .header("Authorization", basicAuthHeader())
            .post(Entity.form(form))) {
      if (resp.getStatus() != 200) {
        throw new WebApplicationException(
            "dinero token endpoint returned " + resp.getStatus(), 502);
      }
      return resp.readEntity(TokenResponse.class);
    }
  }

  String basicAuthHeader() {
    String credentials = config.clientId().orElse("") + ":" + config.clientSecret().orElse("");
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }
}
