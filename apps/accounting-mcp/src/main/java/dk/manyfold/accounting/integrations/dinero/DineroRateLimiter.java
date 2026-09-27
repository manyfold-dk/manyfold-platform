package dk.manyfold.accounting.integrations.dinero;

import dk.manyfold.accounting.integrations.RateLimiter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * One shared fixed-window rate limiter for ALL outbound Dinero traffic (ADR-0043). The read proxy
 * and the writer both acquire from this single bucket so their combined request rate stays below
 * Dinero's 60 req/min personal-integration cap -- two separate per-bean limiters would each allow
 * ~55/min and could sum past 60 (reads + a booking, which is itself 2 calls), tripping the vendor
 * 429 the proxy promises to avoid.
 *
 * <p><b>The bucket is in-JVM and holds no shared state, so the cap is per replica.</b> That is why
 * {@code accounting-mcp} runs {@code replicas: 1} with no HPA: a second pod would quietly permit
 * 110 req/min and Dinero would start answering 429 under load, with nothing failing at deploy time
 * to show why. Scaling out means moving this bucket to shared state (Redis, or the in-namespace
 * Postgres) BEFORE adding the replica -- exactly the same "two allowances sum past the cap" mistake
 * as the per-bean limiters above, one level up.
 */
@ApplicationScoped
public class DineroRateLimiter {

  private final RateLimiter limiter;

  @Inject
  public DineroRateLimiter(DineroConfig config) {
    this.limiter = new RateLimiter(config.rateLimitPerMinute());
  }

  /**
   * @return true if a call is permitted in the current 60s window, else false (caller -&gt; 429).
   */
  public boolean tryAcquire(long nowMillis) {
    return limiter.tryAcquire(nowMillis);
  }
}
