package dk.manyfold.accounting.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Idempotency-key validation + canonical hashing tests. */
class IntegrationWriteKeysTest {

  @Test
  void acceptsWellFormedKeys() {
    IntegrationWriteKeys.validateKey("inv-2024_001:draft");
  }

  @Test
  void rejectsBlankOrOverlongOrIllegalKeys() {
    assertThrows(WebApplicationException.class, () -> IntegrationWriteKeys.validateKey(null));
    assertThrows(WebApplicationException.class, () -> IntegrationWriteKeys.validateKey(""));
    assertThrows(
        WebApplicationException.class, () -> IntegrationWriteKeys.validateKey("has space"));
    assertThrows(
        WebApplicationException.class, () -> IntegrationWriteKeys.validateKey("x".repeat(129)));
  }

  @Test
  void hashIsStableAndContentSensitive() {
    assertEquals(IntegrationWriteKeys.hash("a"), IntegrationWriteKeys.hash("a"));
    assertNotEquals(IntegrationWriteKeys.hash("a"), IntegrationWriteKeys.hash("b"));
    assertEquals(64, IntegrationWriteKeys.hash("a").length());
    assertEquals(
        IntegrationWriteKeys.hash("a"),
        IntegrationWriteKeys.hash("a".getBytes(StandardCharsets.UTF_8)));
  }
}
