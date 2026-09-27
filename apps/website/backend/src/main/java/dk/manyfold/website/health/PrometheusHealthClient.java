package dk.manyfold.website.health;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/** Client for querying Prometheus metrics for health checks. */
@ApplicationScoped
public class PrometheusHealthClient {

	private static final Logger LOG = Logger.getLogger(PrometheusHealthClient.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	@ConfigProperty(name = "manyfold.prometheus.url")
	String prometheusUrl;

	public PrometheusHealthClient() {
		this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		this.objectMapper = new ObjectMapper();
	}

	/** Represents a metric value result. */
	public record MetricValue(String metric, double value) {
	}

	/**
	 * One pushed synthetic probe sample.
	 *
	 * <p>
	 * The probes live in the Pushgateway, which never expires a series: a Worker or
	 * CronJob that stops running leaves its last value in place forever. So a
	 * sample is only worth reading while it is fresh, and {@code ageSeconds} is
	 * what decides that -- never {@code present} alone.
	 */
	public record SyntheticProbe(boolean present, boolean up, double durationSeconds, double ageSeconds) {

		/** Absent probe: nothing has been pushed for this front and vantage. */
		public static SyntheticProbe absent() {
			return new SyntheticProbe(false, false, 0, Double.MAX_VALUE);
		}

		/** True when the sample was pushed recently enough to mean anything. */
		public boolean fresh(double maxAgeSeconds) {
			return present && ageSeconds <= maxAgeSeconds;
		}
	}

	/**
	 * Query a single instant metric value.
	 *
	 * @return the first series' value, or empty when the query matched no series
	 * @throws PrometheusQueryException
	 *             when Prometheus gave no usable answer -- unreachable, an error
	 *             status, or a body that is not a query result. Empty is a
	 *             measurement (nothing matched); this is the absence of one, and a
	 *             caller has to be able to tell them apart.
	 */
	public Optional<Double> queryInstant(String query) throws PrometheusQueryException {
		String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
		String url = prometheusUrl + "/api/v1/query?query=" + encodedQuery;

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(TIMEOUT)
				.GET()
				.build();

		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new PrometheusQueryException("Prometheus did not answer: " + e, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new PrometheusQueryException("Prometheus query interrupted", e);
		}
		int code = response.statusCode();

		JsonNode root;
		try {
			root = objectMapper.readTree(response.body());
		} catch (JsonProcessingException e) {
			throw new PrometheusQueryException(
					String.format("Prometheus answered %d with a body that is not JSON", code), e);
		}

		JsonNode result = root.path("data").path("result");
		boolean answered = code == 200
				&& "success".equals(root.path("status").asText())
				&& result.isArray();
		if (!answered) {
			throw new PrometheusQueryException(
					String.format("Prometheus answered %d: %s", code,
							root.path("error").asText("no result")));
		}

		if (result.isEmpty()) {
			return Optional.empty();
		}

		JsonNode value = result.get(0).path("value");
		if (!value.isArray() || value.size() < 2) {
			throw new PrometheusQueryException("Prometheus answered with a sample that has no value");
		}
		try {
			return Optional.of(Double.parseDouble(value.get(1).asText()));
		} catch (NumberFormatException e) {
			throw new PrometheusQueryException(
					"Prometheus answered with an unreadable sample: " + value, e);
		}
	}

	/**
	 * Query an instant vector and return every sample keyed by its metric name.
	 *
	 * <p>
	 * Lets one round trip collect a whole probe -- liveness, duration and push
	 * timestamp -- instead of three.
	 */
	public Map<String, Double> queryInstantByName(String query) {
		Map<String, Double> values = new HashMap<>();
		try {
			String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
			String url = prometheusUrl + "/api/v1/query?query=" + encodedQuery;

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(TIMEOUT)
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				LOG.warnf("Prometheus query failed with status %d", response.statusCode());
				return values;
			}

			JsonNode result = objectMapper.readTree(response.body()).path("data").path("result");
			for (JsonNode series : result) {
				String name = series.path("metric").path("__name__").asText("");
				JsonNode value = series.path("value");
				if (!name.isEmpty() && value.isArray() && value.size() > 1) {
					values.put(name, Double.parseDouble(value.get(1).asText()));
				}
			}
		} catch (Exception e) {
			LOG.warnf(e, "Prometheus query failed");
		}
		return values;
	}

	/**
	 * Evaluate a query over a time window and return one value per step.
	 *
	 * <p>
	 * Prometheus omits the steps it has no data for, so the result is aligned back
	 * onto the requested grid and the gaps come back as nulls. A chart has to be
	 * able to tell "nothing was recorded" from "recorded as zero" -- one is a gap
	 * in the line, the other is an outage.
	 *
	 * @return one entry per step, oldest first, null where there was no sample
	 */
	public List<Double> queryRange(String query, long startEpochSeconds, long endEpochSeconds, long stepSeconds) {
		int points = (int) ((endEpochSeconds - startEpochSeconds) / stepSeconds) + 1;
		List<Double> grid = new ArrayList<>(points);
		for (int i = 0; i < points; i++) {
			grid.add(null);
		}

		try {
			String url = prometheusUrl + "/api/v1/query_range"
					+ "?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
					+ "&start=" + startEpochSeconds
					+ "&end=" + endEpochSeconds
					+ "&step=" + stepSeconds;

			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(TIMEOUT)
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				LOG.warnf("Prometheus range query failed with status %d", response.statusCode());
				return grid;
			}

			JsonNode result = objectMapper.readTree(response.body()).path("data").path("result");
			if (!result.isArray() || result.isEmpty()) {
				return grid;
			}

			for (JsonNode sample : result.get(0).path("values")) {
				if (!sample.isArray() || sample.size() < 2) {
					continue;
				}
				long at = sample.get(0).asLong();
				int index = (int) Math.round((double) (at - startEpochSeconds) / stepSeconds);
				if (index >= 0 && index < points) {
					grid.set(index, Double.parseDouble(sample.get(1).asText()));
				}
			}
		} catch (Exception e) {
			LOG.warnf(e, "Prometheus range query failed");
		}

		return grid;
	}

	/** Check if Prometheus is reachable. */
	public boolean isAvailable() {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(prometheusUrl + "/-/ready"))
					.timeout(TIMEOUT)
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	/** Get ArgoCD application sync status count. */
	public int getArgoCDSyncedApps() throws PrometheusQueryException {
		return queryInstant("count(argocd_app_info{sync_status=\"Synced\"})")
				.map(Double::intValue)
				.orElse(0);
	}

	/** Get ArgoCD degraded application count. */
	public int getArgoCDDegradedApps() throws PrometheusQueryException {
		return queryInstant("count(argocd_app_info{health_status=\"Degraded\"})")
				.map(Double::intValue)
				.orElse(0);
	}

	/**
	 * One series per Argo CD Application. argocd_app_info is one series per label
	 * combination, and several labels change while the Application stays the same:
	 * health_status whenever its health moves, pod and instance when the
	 * application controller restarts. The Application's own namespace arrives as
	 * exported_namespace, because the scrape target's namespace takes the namespace
	 * label.
	 */
	private static final String PER_APPLICATION = "max by (exported_namespace, name) ";

	/**
	 * Count the Argo CD Applications that have been OutOfSync without a break for
	 * at least {@code minutes}.
	 *
	 * <p>
	 * Every merge leaves some Application OutOfSync until Argo CD has applied it,
	 * so OutOfSync on its own is the normal state of a sync window, not a fault. An
	 * Application that stays OutOfSync is one Argo CD cannot bring into line.
	 *
	 * <p>
	 * The query reads, per Application: OutOfSync now, and OutOfSync
	 * {@code minutes} ago, unless it reported any other sync status at any scrape
	 * in between.
	 * <ul>
	 * <li>The {@code offset} term is what makes a new Application wait its turn: it
	 * has no history of being in sync, so without it it would count from its first
	 * scrape.
	 * <li>The {@code max_over_time} term reads the raw samples in the window, not a
	 * sampled subquery, so a Synced scrape anywhere in it resets the clock however
	 * briefly the Application was in sync.
	 * <li>A gap in the metrics (the controller restarting) neither starts nor
	 * resets the clock: nothing in it says the Application was in sync.
	 * </ul>
	 *
	 * @return the number of such Applications; zero when there are none
	 */
	public int getArgoCDAppsOutOfSyncFor(long minutes) throws PrometheusQueryException {
		String query = String.format(
				"count(%1$s(argocd_app_info{sync_status=\"OutOfSync\"})"
						+ " and on (exported_namespace, name)"
						+ " %1$s(argocd_app_info{sync_status=\"OutOfSync\"} offset %2$dm)"
						+ " unless on (exported_namespace, name)"
						+ " %1$s(max_over_time("
						+ "argocd_app_info{sync_status!=\"OutOfSync\"}[%2$dm])))",
				PER_APPLICATION, minutes);
		return queryInstant(query).map(Double::intValue).orElse(0);
	}

	/**
	 * Count the alerts a human would act on.
	 *
	 * <p>
	 * Scoped to warning and critical on purpose. Everything below that is
	 * permanently firing by design and would make the count meaningless: Watchdog
	 * is the dead-man's-switch that proves the delivery chain is alive,
	 * InfoInhibitor exists only to suppress its namespace's info alerts, and the
	 * info alerts themselves (CPUThrottlingHigh and friends) are inhibited and
	 * never notify anyone. A status page that counted those would read "5 active
	 * alerts" on a completely healthy platform.
	 */
	public int getFiringAlerts() {
		try {
			return queryInstant("count(ALERTS{alertstate=\"firing\",severity=~\"warning|critical\"})")
					.map(Double::intValue)
					.orElse(0);
		} catch (PrometheusQueryException e) {
			LOG.warnf("Firing alert count unavailable, reporting none: %s", e.getMessage());
			return 0;
		}
	}

	private static final String SYNTHETIC_METRICS = "synthetic_check_up|synthetic_check_duration_seconds"
			+ "|synthetic_run_timestamp_seconds";

	/**
	 * Read one synthetic probe result (ADR-0036) pushed to the Pushgateway.
	 *
	 * @param front
	 *            the probed site, e.g. "www"
	 * @param journey
	 *            the probe, e.g. "edge" for the Cloudflare check or "smoke" for the
	 *            in-cluster browser journey
	 * @param vantage
	 *            "external" (Cloudflare Worker) or "internal" (in-cluster runner)
	 */
	public SyntheticProbe getSyntheticProbe(String front, String journey, String vantage) {
		String selector = String.format(
				"{__name__=~\"%s\",front=\"%s\",journey=\"%s\",vantage=\"%s\"}",
				SYNTHETIC_METRICS, front, journey, vantage);

		Map<String, Double> samples = queryInstantByName(selector);
		Double up = samples.get("synthetic_check_up");
		Double pushedAt = samples.get("synthetic_run_timestamp_seconds");
		if (up == null || pushedAt == null) {
			return SyntheticProbe.absent();
		}

		double duration = samples.getOrDefault("synthetic_check_duration_seconds", 0.0);
		double age = (System.currentTimeMillis() / 1000.0) - pushedAt;
		return new SyntheticProbe(true, up >= 1.0, duration, Math.max(age, 0));
	}

	/**
	 * Days until the earliest certificate on a blackbox target expires, or empty
	 * when the target is not probed.
	 */
	public Optional<Double> getCertificateDaysRemaining(String instance) {
		String query = String.format(
				"min(probe_ssl_earliest_cert_expiry{instance=\"%s\"} - time()) / 86400", instance);
		try {
			return queryInstant(query);
		} catch (PrometheusQueryException e) {
			LOG.warnf("Certificate expiry unavailable: %s", e.getMessage());
			return Optional.empty();
		}
	}
}
