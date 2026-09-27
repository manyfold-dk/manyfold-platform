package dk.manyfold.accounting.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Refuses to start a production build with the dev identity stub switched on.
 *
 * <p>The stub answers every unauthenticated request as a configured agent with both integration
 * roles. It exists for local work without an identity provider; in a production build it would turn
 * the Dinero passthrough into an open door. A runtime flag alone was the only thing keeping it off
 * (review finding M2), so a production start with the flag set now fails instead.
 */
@ApplicationScoped
public class DevIdentityGuard {

  @ConfigProperty(name = "accounting.dev.identity.enabled", defaultValue = "false")
  boolean devEnabled;

  void onStart(@Observes StartupEvent event) {
    check(LaunchMode.current(), devEnabled);
  }

  /** Throws when a production launch has the dev identity enabled. */
  static void check(LaunchMode mode, boolean devIdentityEnabled) {
    if (mode == LaunchMode.NORMAL && devIdentityEnabled) {
      throw new IllegalStateException(
          "accounting.dev.identity.enabled must not be true in a production build");
    }
  }
}
