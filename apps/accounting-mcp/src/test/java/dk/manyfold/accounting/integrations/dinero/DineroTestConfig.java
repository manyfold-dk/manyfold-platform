package dk.manyfold.accounting.integrations.dinero;

import java.util.Optional;

/** Test helper: hand-built {@link DineroConfig} instances for the pure-logic Dinero tests. */
final class DineroTestConfig {

  private DineroTestConfig() {}

  static DineroConfig of(String base, String id, String secret, String apiKey, String orgId) {
    return new DineroConfig() {
      @Override
      public String baseUrl() {
        return base;
      }

      @Override
      public String authUrl() {
        return base + "/oauth/token";
      }

      @Override
      public Optional<String> clientId() {
        return Optional.ofNullable(id);
      }

      @Override
      public Optional<String> clientSecret() {
        return Optional.ofNullable(secret);
      }

      @Override
      public Optional<String> apiKey() {
        return Optional.ofNullable(apiKey);
      }

      @Override
      public Optional<String> organizationId() {
        return Optional.ofNullable(orgId);
      }

      @Override
      public long maxResponseBytes() {
        return 1024;
      }

      @Override
      public int rateLimitPerMinute() {
        return 1000;
      }
    };
  }

  static DineroConfig configured() {
    return of("https://api.dinero.dk", "id", "secret", "apikey", "123456");
  }
}
