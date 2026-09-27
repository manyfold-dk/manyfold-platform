package dk.manyfold.website.metrics;

import java.time.Duration;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

/**
 * Gives the HTTP server timer fixed latency buckets.
 *
 * <p>
 * Without buckets the timer exports only a count, a sum and a maximum, and
 * every {@code histogram_quantile} over
 * {@code http_server_requests_seconds_bucket} (the backend dashboards and the
 * slow-response alert) has nothing to read. Fixed buckets keep the series count
 * small: one per bucket for each method, URI template, status and outcome.
 */
@Singleton
public class HttpServerLatencyHistogram {

	static final String HTTP_SERVER_REQUESTS = "http.server.requests";

	private static final double[] BUCKETS_NANOS = Stream.of(
			Duration.ofMillis(5),
			Duration.ofMillis(10),
			Duration.ofMillis(25),
			Duration.ofMillis(50),
			Duration.ofMillis(100),
			Duration.ofMillis(250),
			Duration.ofMillis(500),
			Duration.ofSeconds(1),
			Duration.ofMillis(2500),
			Duration.ofSeconds(5),
			Duration.ofSeconds(10))
			.mapToDouble(Duration::toNanos)
			.toArray();

	/** The filter Quarkus applies to every meter registry. */
	@Produces
	@Singleton
	public MeterFilter httpServerLatencyBuckets() {
		return new MeterFilter() {
			@Override
			public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
				if (!HTTP_SERVER_REQUESTS.equals(id.getName())) {
					return config;
				}
				return DistributionStatisticConfig.builder()
						.serviceLevelObjectives(BUCKETS_NANOS.clone())
						.build()
						.merge(config);
			}
		};
	}
}
