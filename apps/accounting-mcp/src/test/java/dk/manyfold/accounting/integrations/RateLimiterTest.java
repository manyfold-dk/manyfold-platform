package dk.manyfold.accounting.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Deterministic sliding-window rate-limiter tests (the caller supplies the clock). */
class RateLimiterTest {

  @Test
  void permitsUpToTheCapThenRejectsWithinTheWindow() {
    RateLimiter rl = new RateLimiter(2);
    assertTrue(rl.tryAcquire(0));
    assertTrue(rl.tryAcquire(10));
    assertFalse(rl.tryAcquire(20));
  }

  @Test
  void neverAdmitsMoreThanTheCapWithinAnySixtySeconds() {
    // A fixed window would admit two just before its reset and two just after.
    RateLimiter rl = new RateLimiter(2);
    assertTrue(rl.tryAcquire(59_000));
    assertTrue(rl.tryAcquire(59_500));
    assertFalse(rl.tryAcquire(60_500));
    assertTrue(rl.tryAcquire(119_000));
  }

  @Test
  void resetsAtTheNextWindow() {
    RateLimiter rl = new RateLimiter(1);
    assertTrue(rl.tryAcquire(0));
    assertFalse(rl.tryAcquire(59_999));
    assertTrue(rl.tryAcquire(60_000));
  }
}
