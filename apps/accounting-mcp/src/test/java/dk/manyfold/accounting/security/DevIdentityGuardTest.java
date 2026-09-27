package dk.manyfold.accounting.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.runtime.LaunchMode;
import org.junit.jupiter.api.Test;

class DevIdentityGuardTest {

  @Test
  void refusesAProductionStartWithTheDevIdentity() {
    assertThrows(
        IllegalStateException.class, () -> DevIdentityGuard.check(LaunchMode.NORMAL, true));
  }

  @Test
  void allowsProductionWithoutItAndDevelopmentWithIt() {
    assertDoesNotThrow(() -> DevIdentityGuard.check(LaunchMode.NORMAL, false));
    assertDoesNotThrow(() -> DevIdentityGuard.check(LaunchMode.DEVELOPMENT, true));
    assertDoesNotThrow(() -> DevIdentityGuard.check(LaunchMode.TEST, true));
  }
}
