package dk.manyfold.accounting.security;

import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The local-development identity: with no identity provider in {@code mvn quarkus:dev}, turns the
 * anonymous caller into a configured agent holding the configured roles.
 *
 * <p>It is a real {@link SecurityIdentity}, so every authorization layer sees the same caller: the
 * {@code @RolesAllowed} interceptor on the MCP write tools as well as the in-code gates of {@link
 * IdentityResolver}. A stub known only to {@code IdentityResolver} let the reads through but left
 * the write tools rejected in dev mode (issue #397).
 *
 * <p>Three independent locks keep it out of production: the bean is not part of a production build
 * ({@code @UnlessBuildProfile("prod")}); it acts only when {@code accounting.dev.identity.enabled}
 * is true and never in {@link LaunchMode#NORMAL}; and {@link DevIdentityGuard} refuses to start a
 * production launch with the flag set. It never replaces an authenticated identity.
 */
@ApplicationScoped
@UnlessBuildProfile("prod")
public class DevIdentityAugmentor implements SecurityIdentityAugmentor {

  @ConfigProperty(name = "accounting.dev.identity.enabled", defaultValue = "false")
  boolean devEnabled;

  @ConfigProperty(name = "accounting.dev.identity.sub", defaultValue = "dev-agent")
  String devSub;

  @ConfigProperty(name = "accounting.dev.identity.roles")
  Optional<List<String>> devRoles;

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    return Uni.createFrom().item(augment(identity, LaunchMode.current()));
  }

  /** The dev identity for an anonymous caller when enabled outside production; else unchanged. */
  SecurityIdentity augment(SecurityIdentity identity, LaunchMode mode) {
    if (!devEnabled || mode == LaunchMode.NORMAL || !identity.isAnonymous()) {
      return identity;
    }
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal(devSub))
        .addRoles(Set.copyOf(devRoles.orElse(List.of())))
        .setAnonymous(false)
        .build();
  }
}
