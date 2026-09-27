package dk.manyfold.accounting.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteOutcome;
import dk.manyfold.accounting.integrations.IntegrationWriteExecutor.WriteResult;
import dk.manyfold.accounting.integrations.model.IntegrationWrite;
import dk.manyfold.accounting.integrations.model.IntegrationWriteStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The write ledger against a real database: one vendor call per idempotency key, a retry only after
 * a recorded failure, and a ledger that tells a vendor rejection from a local refusal.
 */
@QuarkusTest
class IntegrationWriteExecutorTest {

  private static final String VENDOR = "dinero";
  private static final String OP = "create-invoice";

  @Inject IntegrationWriteExecutor executor;

  private static String freshKey() {
    return "test-" + UUID.randomUUID();
  }

  private static IntegrationWrite row(String key) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                IntegrationWrite.<IntegrationWrite>find(
                        "vendor=?1 and operation=?2 and idempotencyKey=?3", VENDOR, OP, key)
                    .firstResult());
  }

  private WriteOutcome write(String key, String hash, IntegrationWriteExecutor.WriteAction action) {
    return executor.execute(VENDOR, OP, key, hash, "op=create-invoice", action);
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void firstWriteCallsTheVendorOnceAndRecordsSuccess() {
    String key = freshKey();
    AtomicInteger calls = new AtomicInteger();

    WriteOutcome out =
        write(
            key,
            "h1",
            () -> {
              calls.incrementAndGet();
              return new WriteResult(201, "inv-1");
            });

    assertEquals(1, calls.get());
    assertFalse(out.deduplicated());
    assertEquals("inv-1", out.vendorId());
    IntegrationWrite r = row(key);
    assertEquals(IntegrationWriteStatus.SUCCESS, r.status);
    assertEquals(201, r.vendorStatus);
    assertEquals("agent", r.actor);
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aRepeatWithTheSameRequestReturnsTheFirstResultWithoutCallingTheVendor() {
    String key = freshKey();
    write(key, "h1", () -> new WriteResult(201, "inv-1"));
    AtomicInteger calls = new AtomicInteger();

    WriteOutcome again =
        write(
            key,
            "h1",
            () -> {
              calls.incrementAndGet();
              return new WriteResult(201, "inv-2");
            });

    assertEquals(0, calls.get());
    assertTrue(again.deduplicated());
    assertEquals("inv-1", again.vendorId());
    assertEquals(201, again.vendorStatus());
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void theSameKeyWithADifferentRequestIsAConflict() {
    String key = freshKey();
    write(key, "h1", () -> new WriteResult(201, "inv-1"));

    GuardrailException e =
        assertThrows(
            GuardrailException.class, () -> write(key, "h2", () -> new WriteResult(201, "x")));
    assertEquals(409, e.getResponse().getStatus());
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aVendorRejectionIsRecordedWithItsStatusAndTheKeyCanBeRetried() {
    String key = freshKey();

    assertThrows(
        VendorWriteException.class,
        () ->
            write(
                key,
                "h1",
                () -> {
                  throw new VendorWriteException(400, "dinero returned 400", "Name: required");
                }));
    IntegrationWrite failed = row(key);
    assertEquals(IntegrationWriteStatus.FAILED, failed.status);
    assertEquals(400, failed.vendorStatus);

    WriteOutcome retry = write(key, "h1", () -> new WriteResult(201, "inv-9"));

    assertFalse(retry.deduplicated());
    assertEquals("inv-9", retry.vendorId());
    assertEquals(IntegrationWriteStatus.SUCCESS, row(key).status);
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aLocalRefusalLeavesTheVendorStatusEmpty() {
    String key = freshKey();

    assertThrows(
        GuardrailException.class,
        () ->
            write(
                key,
                "h1",
                () -> {
                  throw new GuardrailException("expectedTotal does not match", 409);
                }));

    IntegrationWrite failed = row(key);
    assertEquals(IntegrationWriteStatus.FAILED, failed.status);
    assertNull(failed.vendorStatus);
    assertTrue(failed.error.startsWith("GuardrailException"));
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void anArbitraryExceptionIsStoredByClassNameOnly() {
    String key = freshKey();

    assertThrows(
        IllegalStateException.class,
        () ->
            write(
                key,
                "h1",
                () -> {
                  throw new IllegalStateException("customer Jane Doe, card 4111");
                }));

    assertEquals("IllegalStateException", row(key).error);
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void anInvalidKeyIsRejectedBeforeAnythingIsRecorded() {
    WebApplicationException e =
        assertThrows(
            WebApplicationException.class,
            () -> write("bad key with spaces", "h1", () -> new WriteResult(201, "x")));
    assertEquals(400, e.getResponse().getStatus());
  }

  @Test
  @TestSecurity(user = "reader", roles = "integration-reader")
  void aReaderCannotWrite() {
    AtomicInteger calls = new AtomicInteger();
    assertThrows(
        ForbiddenException.class,
        () ->
            write(
                freshKey(),
                "h1",
                () -> {
                  calls.incrementAndGet();
                  return new WriteResult(201, "x");
                }));
    assertEquals(0, calls.get());
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void anUnknownVendorOutcomeKeepsTheKeyReservedSoARetryCannotPostTwice() {
    String key = freshKey();

    GuardrailException first =
        assertThrows(
            GuardrailException.class,
            () ->
                write(
                    key,
                    "h1",
                    () -> {
                      throw new VendorOutcomeUnknownException(
                          null, "connection lost after sending");
                    }));
    assertTrue(first.getMessage().contains("do NOT retry"));
    IntegrationWrite row = row(key);
    assertEquals(IntegrationWriteStatus.PENDING, row.status);
    assertTrue(row.error.startsWith("outcome unknown"));

    AtomicInteger calls = new AtomicInteger();
    GuardrailException retry =
        assertThrows(
            GuardrailException.class,
            () ->
                write(
                    key,
                    "h1",
                    () -> {
                      calls.incrementAndGet();
                      return new WriteResult(201, "dup");
                    }));
    assertEquals(409, retry.getResponse().getStatus());
    assertTrue(retry.getMessage().contains("unknown outcome"));
    assertTrue(retry.getCause() instanceof VendorOutcomeUnknownException);
    assertEquals(0, calls.get());
    assertNull(row(key).vendorStatus); // no vendor response was read
  }

  /**
   * The vendor call succeeded, then the SUCCESS update failed: PostgreSQL rejects a NUL character
   * in text, which stands in for any database failure on that commit. The document exists at the
   * vendor, so the row must stay PENDING and a same-key retry must never reach the vendor again.
   */
  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aVendorSuccessTheLedgerCannotRecordKeepsTheKeyReserved() {
    String key = freshKey();

    UnrecordedWriteException first =
        assertThrows(
            UnrecordedWriteException.class,
            () -> write(key, "h1", () -> new WriteResult(201, "file\u0000guid")));
    assertEquals(500, first.getResponse().getStatus());
    assertTrue(first.getMessage().contains("do NOT retry"));
    assertEquals(IntegrationWriteStatus.PENDING, row(key).status);

    AtomicInteger calls = new AtomicInteger();
    GuardrailException retry =
        assertThrows(
            GuardrailException.class,
            () ->
                write(
                    key,
                    "h1",
                    () -> {
                      calls.incrementAndGet();
                      return new WriteResult(201, "dup");
                    }));
    assertEquals(409, retry.getResponse().getStatus());
    assertEquals(0, calls.get());
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aKeyRecordedUnderTheOlderHashStillDeduplicates() {
    String key = freshKey();
    executor.execute(VENDOR, OP, key, "old-hash", "op", () -> new WriteResult(201, "file-1"));

    WriteOutcome again =
        executor.execute(
            VENDOR, OP, key, "new-hash", "old-hash", "op", () -> new WriteResult(201, "file-2"));

    assertTrue(again.deduplicated());
    assertEquals("file-1", again.vendorId());
  }

  @Test
  @TestSecurity(user = "agent", roles = "integration-writer")
  void aPendingRowNobodyFinishedIsTreatedAsAnUnknownOutcome() {
    String key = freshKey();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              IntegrationWrite r = new IntegrationWrite();
              r.vendor = VENDOR;
              r.operation = OP;
              r.idempotencyKey = key;
              r.requestHash = "h1";
              r.actor = "agent";
              r.status = IntegrationWriteStatus.PENDING;
              r.createdAt = java.time.Instant.now().minusSeconds(3600);
              r.updatedAt = r.createdAt;
              r.persist();
            });

    GuardrailException retry =
        assertThrows(
            GuardrailException.class, () -> write(key, "h1", () -> new WriteResult(201, "x")));

    assertEquals(409, retry.getResponse().getStatus());
    assertTrue(retry.getMessage().contains("unknown outcome"));
  }
}
