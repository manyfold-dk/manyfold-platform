package dk.manyfold.accounting.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import java.security.Principal;
import java.util.List;
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
 *   <li><b>dev</b>: OIDC is disabled and {@link DevIdentityAugmentor} turns the anonymous caller
 *       into a config-driven {@link SecurityIdentity} holding both roles, so the tools -- including
 *       the {@code @RolesAllowed} write tools -- run end to end locally against live Dinero creds
 *       without a real IdP. This class has no dev branch of its own.
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

  /** The audit subject of an unauthenticated caller. */
  public static final String ANONYMOUS = "anonymous";

  @Inject SecurityIdentity identity;

  public record Profile(String sub, String email, String name, List<String> roles) {
    public Profile {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  /**
   * The authenticated caller's profile (used to attribute the audit trail). Throws 401 if
   * unauthenticated.
   */
  public Profile current() {
    if (identity == null || identity.isAnonymous()) {
      throw new NotAuthorizedException("authentication required");
    }
    String email = null;
    String name = null;
    if (identity.getPrincipal() instanceof JsonWebToken jwt) {
      email = jwt.getClaim("email");
      name = jwt.getClaim("name");
      if (name == null) {
        name = jwt.getClaim("preferred_username");
      }
    }
    return new Profile(subject(identity), email, name, List.copyOf(identity.getRoles()));
  }

  /**
   * The subject to attribute an audit record to, whether or not the caller then passes a role gate:
   * {@link #ANONYMOUS} for an unauthenticated caller. Never throws, so a front door can record a
   * call the gate turns away.
   */
  public String auditSubject() {
    return auditSubject(identity);
  }

  /**
   * As {@link #auditSubject()}, for the identity a security check saw rather than the current
   * request's: a security event carries the identity it refused.
   */
  public static String auditSubject(SecurityIdentity caller) {
    return caller == null || caller.isAnonymous() ? ANONYMOUS : subject(caller);
  }

  /** The token's {@code sub}, else the principal name. */
  private static String subject(SecurityIdentity caller) {
    Principal p = caller.getPrincipal();
    String sub = p instanceof JsonWebToken jwt ? jwt.getSubject() : null;
    // Fall back to the principal name when the token carries no `sub` (e.g. @TestSecurity, or a
    // non-JWT identity). Real Keycloak tokens always have a `sub`, so prod uses that.
    if ((sub == null || sub.isBlank()) && p != null) {
      sub = p.getName();
    }
    return sub;
  }

  /**
   * Require the operator-owned {@link #INTEGRATION_READER} realm role -- gates the Dinero read
   * passthrough (ADR-0043). Checks the raw realm roles directly. Returns the caller profile so the
   * gateway can attribute the audit record. 401 if unauthenticated, 403 if authenticated without
   * the role.
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
    return identity != null && !identity.isAnonymous() && identity.getRoles().contains(role);
  }
}
