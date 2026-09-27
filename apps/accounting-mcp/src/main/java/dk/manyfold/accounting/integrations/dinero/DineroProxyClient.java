package dk.manyfold.accounting.integrations.dinero;

import dk.manyfold.accounting.integrations.ProxyResult;
import dk.manyfold.accounting.integrations.VendorProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;

/**
 * Credential-injecting, host-pinned outbound half of the Dinero passthrough (ADR-0043). Pins the
 * upstream host to {@code api.dinero.dk}, injects a Bearer from {@link DineroTokenProvider}, caps
 * the response size and paces below Dinero's 60 req/min. Inert (503) until all personal-integration
 * credentials are present.
 *
 * <p>Org-scoped Dinero paths carry the OrganizationId (FirmaId). To keep the generic passthrough
 * ergonomic (and to avoid leaking the id to the agent), the literal placeholder {@code
 * {organizationId}} (or {@code {orgId}}) in the caller's path is substituted server-side with the
 * configured id before the path is validated and forwarded.
 */
@ApplicationScoped
public class DineroProxyClient implements VendorProxy {

  public static final String VENDOR = "dinero";

  private final DineroConfig config;
  private final DineroTokenProvider tokens;
  private final DineroRateLimiter rateLimiter;
  private final String pinnedHost;
  private final DineroHttp http;

  @Inject
  public DineroProxyClient(
      DineroConfig config,
      DineroTokenProvider tokens,
      DineroRateLimiter rateLimiter,
      DineroHttp http) {
    this.config = config;
    this.http = http;
    this.tokens = tokens;
    this.rateLimiter = rateLimiter;
    this.pinnedHost = URI.create(config.baseUrl()).getHost();
  }

  @Override
  public String vendor() {
    return VENDOR;
  }

  @Override
  public ProxyResult forward(String method, String rawPath, String rawQuery) {
    if (isBlank(config.clientId())
        || isBlank(config.clientSecret())
        || isBlank(config.apiKey())
        || isBlank(config.organizationId())) {
      throw new WebApplicationException("dinero integration not configured", 503);
    }
    if (!rateLimiter.tryAcquire(System.currentTimeMillis())) {
      throw new WebApplicationException("rate limit exceeded", 429);
    }

    URI target = buildTarget(rawPath, rawQuery);
    String bearer = tokens.accessToken(System.currentTimeMillis());

    try (Response upstream =
        http.get()
            .target(target)
            .request()
            .header("Authorization", "Bearer " + bearer)
            .header("Accept", MediaType.APPLICATION_JSON)
            .method(method)) {
      byte[] body = readCapped(upstream);
      return new ProxyResult(upstream.getStatus(), upstream.getMediaType(), body);
    }
  }

  /**
   * Substitute the OrganizationId placeholder, then append the client-supplied path to the fixed
   * base URL. The client may supply only a path, never a scheme or authority, so the upstream host
   * cannot change.
   */
  URI buildTarget(String rawPath, String rawQuery) {
    String path = rawPath == null ? "" : rawPath;
    String orgId = config.organizationId().orElse("");
    path = path.replace("{organizationId}", orgId).replace("{orgId}", orgId);
    if (path.startsWith("/")
        || path.startsWith("\\")
        || path.contains("://")
        || path.contains("..")) {
      throw new WebApplicationException("invalid upstream path", 400);
    }

    String base = config.baseUrl();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    String url = base + "/" + path + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery);

    URI target;
    try {
      target = URI.create(url);
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException("invalid upstream path", e, 400);
    }
    if (target.getHost() == null || !target.getHost().equalsIgnoreCase(pinnedHost)) {
      throw new WebApplicationException("upstream host not allowed", 400);
    }
    return target;
  }

  private byte[] readCapped(Response upstream) {
    long cap = config.maxResponseBytes();
    try (InputStream in = upstream.readEntity(InputStream.class)) {
      if (in == null) {
        return new byte[0];
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      long total = 0;
      for (int n = in.read(buf); n != -1; n = in.read(buf)) {
        total += n;
        if (total > cap) {
          throw new WebApplicationException("upstream response too large", 502);
        }
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new WebApplicationException("upstream read failed", e, 502);
    }
  }

  private static boolean isBlank(Optional<String> value) {
    return value.isEmpty() || value.get().isBlank();
  }
}
