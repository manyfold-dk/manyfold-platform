package dk.manyfold.accounting.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DevIdentityAugmentorTest {

  private static final SecurityIdentity ANONYMOUS =
      QuarkusSecurityIdentity.builder().setAnonymous(true).build();

  private static DevIdentityAugmentor augmentor(boolean enabled) {
    DevIdentityAugmentor augmentor = new DevIdentityAugmentor();
    augmentor.devEnabled = enabled;
    augmentor.devSub = "dev-agent";
    augmentor.devRoles =
        Optional.of(
            List.of(IdentityResolver.INTEGRATION_READER, IdentityResolver.INTEGRATION_WRITER));
    return augmentor;
  }

  @Test
  void theAnonymousCallerBecomesTheDevIdentityInDevelopment() {
    SecurityIdentity identity = augmentor(true).augment(ANONYMOUS, LaunchMode.DEVELOPMENT);

    assertFalse(identity.isAnonymous());
    assertEquals("dev-agent", identity.getPrincipal().getName());
    assertEquals(
        Set.of(IdentityResolver.INTEGRATION_READER, IdentityResolver.INTEGRATION_WRITER),
        identity.getRoles());
  }

  @Test
  void aProductionLaunchNeverGetsTheDevIdentityEvenWithTheFlagSet() {
    assertSame(ANONYMOUS, augmentor(true).augment(ANONYMOUS, LaunchMode.NORMAL));
  }

  @Test
  void theFlagOffLeavesTheCallerAnonymous() {
    assertSame(ANONYMOUS, augmentor(false).augment(ANONYMOUS, LaunchMode.DEVELOPMENT));
  }

  @Test
  void anAuthenticatedCallerIsNeverReplaced() {
    SecurityIdentity reader =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("reader"))
            .addRole(IdentityResolver.INTEGRATION_READER)
            .build();

    assertSame(reader, augmentor(true).augment(reader, LaunchMode.DEVELOPMENT));
  }
}
