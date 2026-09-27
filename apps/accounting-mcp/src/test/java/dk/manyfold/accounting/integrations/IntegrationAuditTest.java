package dk.manyfold.accounting.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The vendor a refused upload names is recorded before any lookup resolves it (issue #503), so the
 * audit keeps it to one bounded token of the key charset.
 */
class IntegrationAuditTest {

  @Test
  void aKnownVendorAndOperationAreRecordedAsGiven() {
    assertEquals("dinero", IntegrationAudit.label("dinero"));
    assertEquals("create-manual-voucher", IntegrationAudit.label("create-manual-voucher"));
  }

  @Test
  void aCallerNamedVendorCannotBreakTheAuditLine() {
    assertEquals(
        "x_integration-write_vendor_dinero_actor_thomas",
        IntegrationAudit.label("x\nintegration-write vendor=dinero actor=thomas"));
  }

  @Test
  void aLongVendorIsBounded() {
    assertEquals("a".repeat(64) + "...", IntegrationAudit.label("a".repeat(500)));
  }

  @Test
  void aMissingValueIsADash() {
    assertEquals("-", IntegrationAudit.label(null));
  }
}
