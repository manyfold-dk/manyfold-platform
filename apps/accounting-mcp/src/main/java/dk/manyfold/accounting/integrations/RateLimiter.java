package dk.manyfold.accounting.integrations;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Minimal in-process sliding-window rate limiter. Guards the Dinero integration against runaway
 * call volume and keeps us below the vendor's 60 req/min personal-integration cap (ADR-0043
 * guardrail). This is our own cap; the vendor enforces its own limit independently.
 *
 * <p>Sliding, not fixed: a fixed window admits a full allowance just before its reset and another
 * just after, twice the cap inside one minute. Here at most {@code permitsPerWindow} calls fall
 * within any 60 seconds.
 *
 * <p>Deterministic: the caller supplies the clock, which keeps it unit-testable without sleeping.
 */
public final class RateLimiter {

  private static final long WINDOW_MILLIS = 60_000L;

  private final int permitsPerWindow;
  private final Deque<Long> granted = new ArrayDeque<>();

  public RateLimiter(int permitsPerWindow) {
    this.permitsPerWindow = permitsPerWindow;
  }

  /**
   * @param nowMillis the current epoch-millis clock reading
   * @return {@code true} if fewer than the allowance were granted in the last 60 seconds
   */
  public synchronized boolean tryAcquire(long nowMillis) {
    while (!granted.isEmpty() && nowMillis - granted.peekFirst() >= WINDOW_MILLIS) {
      granted.pollFirst();
    }
    if (granted.size() >= permitsPerWindow) {
      return false;
    }
    granted.addLast(nowMillis);
    return true;
  }
}
