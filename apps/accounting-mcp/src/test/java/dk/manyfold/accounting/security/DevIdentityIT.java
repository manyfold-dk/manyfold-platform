package dk.manyfold.accounting.security;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.manyfold.accounting.integrations.model.IntegrationWrite;
import dk.manyfold.accounting.integrations.model.IntegrationWriteStatus;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The dev identity as {@code mvn quarkus:dev} uses it: an anonymous caller must pass every
 * authorization layer, including the class-level {@code @RolesAllowed} of the MCP write tools,
 * which the former {@code IdentityResolver}-only stub could not satisfy (issue #397).
 */
@QuarkusTest
@TestProfile(DevIdentityIT.DevIdentityProfile.class)
class DevIdentityIT {

  /** The %dev identity settings, applied to the test launch. */
  public static class DevIdentityProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "accounting.dev.identity.enabled", "true",
          "accounting.dev.identity.sub", "dev-agent",
          "accounting.dev.identity.roles", "integration-reader,integration-writer");
    }
  }

  /**
   * A mismatching {@code expected_total} is refused by the writer, after the {@code @RolesAllowed}
   * interceptor and the executor's writer gate and before any vendor call: the refusal text proves
   * the call got through both gates.
   */
  @Test
  void anAnonymousCallerReachesTheWriteToolsAsTheDevIdentity() {
    String key = "dev-identity-" + UUID.randomUUID();
    try (McpStreamableTestClient client = McpAssured.newConnectedStreamableClient()) {
      client
          .when()
          .toolsCall("dinero_create_manual_voucher")
          .withArguments(
              Map.of(
                  "idempotency_key",
                  key,
                  "expected_total",
                  "200.00",
                  "body",
                  "{\"Lines\":[{\"Amount\":100.00,\"AccountNumber\":1000,"
                      + "\"BalancingAccountNumber\":5820}]}"))
          .withAssert(
              response -> {
                assertTrue(response.isError());
                String text = response.firstContent().asText().text();
                assertTrue(text.contains("expectedTotal 200.00 does not match"), text);
              })
          .send()
          .thenAssertResults();
    }

    IntegrationWrite row =
        QuarkusTransaction.requiringNew()
            .call(
                () -> IntegrationWrite.<IntegrationWrite>find("idempotencyKey", key).firstResult());
    assertEquals("dev-agent", row.actor);
    assertEquals(IntegrationWriteStatus.FAILED, row.status);
  }

  /** The reader gate passes too: an unknown vendor is the gateway's 404, not a 401 or 403. */
  @Test
  void anAnonymousCallerPassesTheReaderGateAsTheDevIdentity() {
    given().when().get("/api/integrations/no-such-vendor/v1/organizations").then().statusCode(404);
  }
}
