package dk.manyfold.website.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.jboss.logging.Logger;

/**
 * A value recomputed at most once per interval, however many callers ask for
 * it.
 *
 * <p>
 * The public status endpoints read Prometheus and the Kubernetes API. Without
 * this, the work those backends see is proportional to the traffic on
 * manyfold.dk/status, which is the wrong thing to make proportional: a flood of
 * requests would become a flood of queries. With it, one refresh per interval
 * is the ceiling no matter what arrives at the front door.
 *
 * <p>
 * Stale reads are served rather than delayed. When the value has aged out, the
 * caller that notices starts a single refresh and is handed the previous value
 * immediately, so no visitor ever waits for Prometheus. Only the very first
 * call of the process computes inline, because there is nothing yet to serve. A
 * refresh that throws leaves the last good value in place and is logged;
 * callers keep getting the older reading, and its timestamp tells the page how
 * old it is.
 */
public final class Cached<T> {

	private static final Logger LOG = Logger.getLogger(Cached.class);

	private final String name;
	private final Supplier<T> supplier;
	private final long ttlMillis;
	private final Runnable refreshExecutor;

	private final AtomicBoolean refreshing = new AtomicBoolean();
	private volatile Entry<T> entry;

	private record Entry<T>(T value, long computedAtMillis) {
	}

	/**
	 * @param name
	 *            what is cached, for log messages
	 * @param ttl
	 *            how long a computed value is served before a refresh is started
	 * @param supplier
	 *            the expensive computation; must be thread-safe
	 * @param async
	 *            runs a background refresh. Pass a direct executor to refresh
	 *            inline instead, which is what the tests do.
	 */
	public Cached(String name, Duration ttl, Supplier<T> supplier, java.util.concurrent.Executor async) {
		this.name = name;
		this.ttlMillis = ttl.toMillis();
		this.supplier = supplier;
		this.refreshExecutor = () -> async.execute(this::refresh);
	}

	/** The current value, computing it only if nothing has been computed yet. */
	public T get() {
		Entry<T> current = entry;

		if (current == null) {
			return firstValue();
		}

		if (System.currentTimeMillis() - current.computedAtMillis() >= ttlMillis
				&& refreshing.compareAndSet(false, true)) {
			refreshExecutor.run();
		}

		return current.value();
	}

	/** Age of the value being served, or null when nothing is cached yet. */
	public Duration age() {
		Entry<T> current = entry;
		if (current == null) {
			return null;
		}
		return Duration.ofMillis(System.currentTimeMillis() - current.computedAtMillis());
	}

	private synchronized T firstValue() {
		if (entry == null) {
			entry = new Entry<>(supplier.get(), System.currentTimeMillis());
		}
		return entry.value();
	}

	private void refresh() {
		try {
			entry = new Entry<>(supplier.get(), System.currentTimeMillis());
		} catch (RuntimeException e) {
			// Deliberately not rethrown: the previous value is still the best
			// answer available, and dropping it would turn a transient
			// Prometheus hiccup into an outage on the status page.
			LOG.warnf(e, "Refresh of %s failed; serving the previous value", name);
		} finally {
			refreshing.set(false);
		}
	}
}
