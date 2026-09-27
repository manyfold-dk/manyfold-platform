package dk.manyfold.accounting.integrations.dinero;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Configuration for the Dinero personal-integration passthrough + writes (ADR-0043). Secret values
 * are read from the environment and never live in Git: prod from the SOPS-encrypted {@code
 * dinero-credentials} secret, local/cloud dev from the gitignored root {@code .env.cloud} ({@code
 * DINERO_*}).
 *
 * <p>Blank {@code clientId}/{@code clientSecret}/{@code apiKey}/{@code organizationId} keep the
 * integration inert (503), so the service ships dark until the personal-integration credentials are
 * present.
 */
@ConfigMapping(prefix = "accounting.integrations.dinero")
public interface DineroConfig {

  /** Dinero API base URL; its host is hard-pinned by the proxy. */
  String baseUrl();

  /** Dinero personal-integration OAuth token endpoint (authz.dinero.dk). */
  String authUrl();

  /** Client id issued by Dinero for the personal integration. Blank means inert. */
  Optional<String> clientId();

  /** Client secret issued by Dinero for the personal integration. Blank means inert. */
  Optional<String> clientSecret();

  /**
   * Organization API key (used as both username and password in the password grant). Blank = inert.
   */
  Optional<String> apiKey();

  /**
   * OrganizationId (Dinero "FirmaId") of the organisation this service serves; injected into
   * org-scoped paths.
   */
  Optional<String> organizationId();

  /** Hard cap on a single upstream response we buffer and return, in bytes. */
  @WithDefault("10485760")
  long maxResponseBytes();

  /** Fixed-window rate cap across all callers, in requests per minute (kept below Dinero's 60). */
  @WithDefault("55")
  int rateLimitPerMinute();
}
