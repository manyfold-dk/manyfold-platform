package dk.manyfold.accounting.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import java.security.Principal;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Resolves the calling agent/user for the accounting integration (ADR-0043) and gates the two
 * operator-owned capability roles that authorize the Dinero passthrough and writes. This is a
 * standalone MCP service (no product SPA), so unlike a product portal there is no {@code user <
 * editor < admin} hierarchy -- only the flat {@code integration-reader} / {@code
 * integration-writer} realm roles matter.
 *
 * <p>Auth wiring is profile-dependent (see application.properties):
 *
 * <ul>
 *   <li><b>prod</b>: the {@code /mcp} bearer-only OIDC tenant (and the default service tenant for
 *       the REST passthrough) has already validated the Keycloak access token, so {@link
 *       SecurityIdentity} carries {@code realm_access.roles}.
 *   <li><b>dev</b>: OIDC is disabled and a config-driven stub identity stands in, so the tools run
 *       end to end locally against live Dinero creds without a real IdP.
 *   <li><b>test</b>: {@code @TestSecurity} supplies a synthetic identity.
 * </ul>
 */
@ApplicationScoped
public class IdentityResolver {

  /**
   * Operator-owned Keycloak realm role that gates the Dinero read passthrough (ADR-0043). Flat
   * capability role, deliberately outside any product hierarchy; held only by claude-agent.
   */
  public static final String INTEGRATION_READER = "integration-reader";

  /**
   * Operator-owned Keycloak realm role that gates the scoped Dinero write/booking operations
   * (ADR-0043). Flat capability role, same shape as {@link #INTEGRATION_READER}.
   */
  public static final String INTEGRATION_WRITER = "integration-writer";

  @Inject SecurityIdentity identity;

  @ConfigProperty(name = "accounting.dev.identity.enabled", defaultValue = "false")
  boolean devEnabled;

  @ConfigProperty(name = "accounting.dev.identity.sub", defaultValue = "dev-agent")
  String devSub;

  @ConfigProperty(name = "accounting.dev.identity.email", defaultValue = "dev@manyfold.dk")
  String devEmail;

  @ConfigProperty(name = "accounting.dev.identity.name", defaultValue = "Dev Agent")
  String devName;

  @ConfigProperty(name = "accounting.dev.identity.roles")
  Optional<List<String>> devRoles;

  public record Profile(String sub, String email, String name, List<String> roles) {
    public Profile {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  /**
   * The authenticated caller's profile (used to attribute the audit trail). Throws 401 if
   * unauthenticated (and the dev stub is not enabled).
   */
  public Profile current() {
    if (identity == null || identity.isAnonymous()) {
      if (devEnabled) {
        return new Profile(devSub, devEmail, devName, devRoles.orElse(List.of()));
      }
      throw new NotAuthorizedException("authentication required");
    }
    String sub = null;
    String email = null;
    String name = null;
    Principal p = identity.getPrincipal();
    if (p instanceof JsonWebToken jwt) {
      sub = jwt.getSubject();
      email = jwt.getClaim("email");
      name = jwt.getClaim("name");
      if (name == null) {
        name = jwt.getClaim("preferred_username");
      }
    }
    // Fall back to the principal name when the token carries no `sub` (e.g. @TestSecurity, or a
    // non-JWT identity). Real Keycloak tokens always have a `sub`, so prod uses that.
    if ((sub == null || sub.isBlank()) && p != null) {
      sub = p.getName();
    }
    return new Profile(sub, email, name, List.copyOf(identity.getRoles()));
  }

  /**
   * Require the operator-owned {@link #INTEGRATION_READER} realm role -- gates the Dinero read
   * passthrough (ADR-0043). Checks the raw realm roles directly; honours the dev stub. Returns the
   * caller profile so the gateway can attribute the audit record. 401 if unauthenticated, 403 if
   * authenticated without the role.
   */
  public Profile requireIntegrationReader() {
    Profile pr = current();
    if (hasRole(INTEGRATION_READER)) {
      return pr;
    }
    throw new ForbiddenException(INTEGRATION_READER + " role required");
  }

  /**
   * Require the operator-owned {@link #INTEGRATION_WRITER} realm role -- gates the scoped Dinero
   * write/booking operations (ADR-0043). Same shape as {@link #requireIntegrationReader()}.
   */
  public Profile requireIntegrationWriter() {
    Profile pr = current();
    if (hasRole(INTEGRATION_WRITER)) {
      return pr;
    }
    throw new ForbiddenException(INTEGRATION_WRITER + " role required");
  }

  private boolean hasRole(String role) {
    if (identity != null && !identity.isAnonymous() && identity.getRoles().contains(role)) {
      return true;
    }
    // dev stub: roles are configured in accounting.dev.identity.roles
    return devEnabled && devRoles.map(r -> r.contains(role)).orElse(false);
  }
}
