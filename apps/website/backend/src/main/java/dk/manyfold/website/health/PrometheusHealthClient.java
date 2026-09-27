package dk.manyfold.website.health;

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

	/** Query a single instant metric value. */
	public Optional<Double> queryInstant(String query) {
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
				return Optional.empty();
			}

			JsonNode root = objectMapper.readTree(response.body());
			JsonNode result = root.path("data").path("result");

			if (result.isArray() && !result.isEmpty()) {
				JsonNode value = result.get(0).path("value");
				if (value.isArray() && value.size() > 1) {
					return Optional.of(Double.parseDouble(value.get(1).asText()));
				}
			}

			return Optional.empty();
		} catch (Exception e) {
			LOG.warnf(e, "Prometheus query failed");
			return Optional.empty();
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
	public int getArgoCDSyncedApps() {
		return queryInstant("count(argocd_app_info{sync_status=\"Synced\"})")
				.map(Double::intValue)
				.orElse(0);
	}

	/** Get ArgoCD degraded application count. */
	public int getArgoCDDegradedApps() {
		return queryInstant("count(argocd_app_info{health_status=\"Degraded\"})")
				.map(Double::intValue)
				.orElse(0);
	}

	private static final String TEKTON_METRIC = "tekton_pipelines_controller_pipelinerun_total";

	/** Get Tekton pipeline success rate (last 24h). Returns -1 if no data. */
	public double getTektonSuccessRate() {
		String success = "sum(increase(" + TEKTON_METRIC + "{status=\"success\"}[24h]))";
		String total = "sum(increase(" + TEKTON_METRIC + "[24h]))";
		String query = "100 * " + success + " / " + total;
		return queryInstant(query).orElse(-1.0);
	}

	/** Get total Tekton pipeline runs in the last 24h. */
	public int getTektonTotalRuns() {
		String query = "sum(increase(" + TEKTON_METRIC + "[24h]))";
		return queryInstant(query).map(Double::intValue).orElse(0);
	}

	/** Get successful Tekton pipeline runs in the last 24h. */
	public int getTektonSuccessfulRuns() {
		String query = "sum(increase(" + TEKTON_METRIC + "{status=\"success\"}[24h]))";
		return queryInstant(query).map(Double::intValue).orElse(0);
	}

	/** Get failed Tekton pipeline runs in the last 24h. */
	public int getTektonFailedRuns() {
		String query = "sum(increase(" + TEKTON_METRIC + "{status=\"failed\"}[24h]))";
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
		return queryInstant("count(ALERTS{alertstate=\"firing\",severity=~\"warning|critical\"})")
				.map(Double::intValue)
				.orElse(0);
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
		return queryInstant(query);
	}
}
