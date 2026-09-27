package dk.manyfold.website.health;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dk.manyfold.website.api.v1.model.StatusHistoryResponse;
import dk.manyfold.website.api.v1.model.StatusHistoryResponse.CheckHistory;
import dk.manyfold.website.api.v1.model.StatusHistoryResponse.Latency;
import dk.manyfold.website.api.v1.model.StatusHistoryResponse.Uptime;
import dk.manyfold.website.cache.Cached;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

/**
 * Recent history for the two synthetic checks on the public status page.
 *
 * <p>
 * Four range queries produce the whole thing, and they run at most once per
 * cache interval however much traffic arrives. The window is bounded by what
 * Prometheus keeps: retention is 15 days, so a 14 day strip is the honest
 * maximum and the 90 day bar a public status page usually shows is not
 * available here.
 */
@ApplicationScoped
public class StatusHistoryService {

	/** 28 buckets of 12 hours: a fortnight, at a width that reads on a phone. */
	private static final int UPTIME_BUCKETS = 28;
	private static final long UPTIME_BUCKET_SECONDS = 12 * 3600L;

	/** 48 points of 30 minutes: a day. */
	private static final int LATENCY_POINTS = 48;
	private static final long LATENCY_STEP_SECONDS = 30 * 60L;

	private static final String PUBLIC_FRONT = "www";
	private static final int SECONDS_PER_HOUR = 3600;

	private record Probe(String name, String journey, String vantage) {
	}

	private static final List<Probe> PROBES = List.of(
			new Probe("From the internet", "edge", "external"),
			new Probe("From inside the cluster", "smoke", "internal"));

	/**
	 * The underlying probes run every two and five minutes, so anything shorter
	 * than a minute or two of cache would re-read Prometheus for a picture that
	 * cannot have changed.
	 */
	@ConfigProperty(name = "manyfold.status.history-cache-ttl-seconds", defaultValue = "120")
	long cacheTtlSeconds;

	@Inject
	PrometheusHealthClient prometheusClient;

	@Inject
	ManagedExecutor executor;

	private Cached<StatusHistoryResponse> cache;

	@PostConstruct
	void setUp() {
		cache = new Cached<>(
				"status history", Duration.ofSeconds(cacheTtlSeconds), this::collect, executor);
	}

	/** Recent history, recomputed at most once per cache interval. */
	public StatusHistoryResponse currentHistory() {
		return cache.get();
	}

	private StatusHistoryResponse collect() {
		long now = System.currentTimeMillis() / 1000;
		List<CheckHistory> checks = new ArrayList<>();
		for (Probe probe : PROBES) {
			checks.add(new CheckHistory(probe.name(), uptimeOf(probe, now), latencyOf(probe, now)));
		}
		return StatusHistoryResponse.of(checks);
	}

	private Uptime uptimeOf(Probe probe, long now) {
		String query = String.format(
				"avg_over_time(synthetic_check_up{front=\"%s\",journey=\"%s\",vantage=\"%s\"}[%ds])",
				PUBLIC_FRONT, probe.journey(), probe.vantage(), UPTIME_BUCKET_SECONDS);

		long start = now - (UPTIME_BUCKETS - 1L) * UPTIME_BUCKET_SECONDS;
		List<Double> buckets = prometheusClient.queryRange(query, start, now, UPTIME_BUCKET_SECONDS);

		int days = (int) (UPTIME_BUCKETS * UPTIME_BUCKET_SECONDS / (24 * SECONDS_PER_HOUR));
		return new Uptime(days, (int) (UPTIME_BUCKET_SECONDS / SECONDS_PER_HOUR), buckets, mean(buckets));
	}

	private Latency latencyOf(Probe probe, long now) {
		String query = String.format(
				"avg_over_time(synthetic_check_duration_seconds"
						+ "{front=\"%s\",journey=\"%s\",vantage=\"%s\"}[%ds])",
				PUBLIC_FRONT, probe.journey(), probe.vantage(), LATENCY_STEP_SECONDS);

		long start = now - (LATENCY_POINTS - 1L) * LATENCY_STEP_SECONDS;
		List<Double> seconds = prometheusClient.queryRange(query, start, now, LATENCY_STEP_SECONDS);

		List<Double> millis = new ArrayList<>(seconds.size());
		for (Double value : seconds) {
			millis.add(value == null ? null : Math.round(value * 1000) * 1.0);
		}

		int hours = (int) (LATENCY_POINTS * LATENCY_STEP_SECONDS / SECONDS_PER_HOUR);
		return new Latency(hours, (int) (LATENCY_STEP_SECONDS / 60), millis, latest(millis));
	}

	private static Double mean(List<Double> values) {
		double total = 0;
		int counted = 0;
		for (Double value : values) {
			if (value != null) {
				total += value;
				counted++;
			}
		}
		return counted == 0 ? null : total / counted;
	}

	private static Double latest(List<Double> values) {
		for (int i = values.size() - 1; i >= 0; i--) {
			if (values.get(i) != null) {
				return values.get(i);
			}
		}
		return null;
	}
}
