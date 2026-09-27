package dk.manyfold.website.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dk.manyfold.website.api.v1.model.StatusResponse;
import dk.manyfold.website.api.v1.model.StatusResponse.ServiceStatus;
import dk.manyfold.website.cache.Cached;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/**
 * The four public checks behind /api/v1/status.
 *
 * <p>
 * Two of them are recorded views of the same site from different places: the
 * external probe is the only check in the platform that sees what a visitor
 * sees, while the internal journey crosses the gateway and the frontend but not
 * the edge. A disagreement between the two says where the fault is.
 */
@ApplicationScoped
public class PublicStatusService {

	private static final Logger LOG = Logger.getLogger(PublicStatusService.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	static final String STATUS_OPERATIONAL = "operational";
	static final String STATUS_DEGRADED = "degraded";
	static final String STATUS_OUTAGE = "outage";

	/**
	 * The check produced no usable reading. It is reported rather than hidden, and
	 * it never moves the overall status: a probe that stopped running is not
	 * evidence of an outage.
	 */
	static final String STATUS_UNKNOWN = "unknown";

	/** The public site, as the synthetic probes label it (ADR-0036). */
	private static final String PUBLIC_FRONT = "www";

	/** Cloudflare Worker, every 2 minutes: five missed runs and it is stale. */
	private static final double EDGE_PROBE_MAX_AGE_SECONDS = 600;

	/** In-cluster browser journey, every 5 minutes. */
	private static final double JOURNEY_PROBE_MAX_AGE_SECONDS = 1800;

	/**
	 * cert-manager renews at two thirds of a 90-day certificate's life, so roughly
	 * 30 days remain at a normal renewal. Under a week means renewal has failed
	 * repeatedly and somebody should look.
	 */
	private static final double CERT_WARN_DAYS = 7;

	private static final int SECONDS_PER_MINUTE = 60;

	private static final int HTTP_SUCCESS_MIN = 200;
	private static final int HTTP_SUCCESS_MAX = 300;
	private static final int HTTP_SERVER_ERROR_MIN = 500;

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	@ConfigProperty(name = "manyfold.status.backend-url", defaultValue = "http://localhost:8080")
	String backendUrl;

	/**
	 * The host:port whose certificate the public site is served with, as the
	 * blackbox exporter probes it. Unset, the status page reports the certificate
	 * as not probed.
	 */
	@ConfigProperty(name = "manyfold.status.public-tls-target")
	Optional<String> publicTlsTarget;

	/**
	 * Well under the 30 second poll on the status page, so a visitor still sees
	 * movement, and far above the request rate a flood could produce.
	 */
	@ConfigProperty(name = "manyfold.status.cache-ttl-seconds", defaultValue = "15")
	long cacheTtlSeconds;

	@Inject
	PrometheusHealthClient prometheusClient;

	@Inject
	ManagedExecutor executor;

	private Cached<StatusResponse> cache;

	@PostConstruct
	void setUp() {
		cache = new Cached<>(
				"public status", Duration.ofSeconds(cacheTtlSeconds), this::collect, executor);
	}

	/** The current status, recomputed at most once per cache interval. */
	public StatusResponse currentStatus() {
		return cache.get();
	}

	private StatusResponse collect() {
		List<ServiceStatus> services = new ArrayList<>();

		// A live call the backend makes to itself.
		services.add(checkService("Backend API", backendUrl + "/health/live", "live check"));
		services.add(
				probeStatus(
						"From the internet",
						"edge",
						"external",
						EDGE_PROBE_MAX_AGE_SECONDS,
						"Cloudflare network"));
		services.add(
				probeStatus(
						"From inside the cluster",
						"smoke",
						"internal",
						JOURNEY_PROBE_MAX_AGE_SECONDS,
						"browser journey"));
		services.add(certificateStatus());

		String overallStatus = calculateOverallStatus(services);
		LOG.infof("Status check complete: %s", overallStatus);
		return StatusResponse.of(overallStatus, services);
	}

	/**
	 * Read one synthetic probe as a service check.
	 *
	 * <p>
	 * A stale sample reports unknown rather than up: the Pushgateway keeps the last
	 * value forever, so "up" from a probe that stopped running an hour ago would be
	 * a lie with a timestamp on it.
	 */
	private ServiceStatus probeStatus(
			String name, String journey, String vantage, double maxAgeSeconds, String source) {
		var probe = prometheusClient.getSyntheticProbe(PUBLIC_FRONT, journey, vantage);
		if (!probe.fresh(maxAgeSeconds)) {
			return new ServiceStatus(name, STATUS_UNKNOWN, null, source + ", no recent run");
		}

		long latency = Math.round(probe.durationSeconds() * 1000);
		String detail = String.format("%s, %s", source, describeAge(probe.ageSeconds()));
		return new ServiceStatus(name, probe.up() ? STATUS_OPERATIONAL : STATUS_OUTAGE, latency, detail);
	}

	private ServiceStatus certificateStatus() {
		var days = publicTlsTarget.flatMap(prometheusClient::getCertificateDaysRemaining);
		if (days.isEmpty()) {
			return new ServiceStatus("TLS certificate", STATUS_UNKNOWN, null, "not probed");
		}

		long remaining = (long) Math.floor(days.get());
		if (remaining <= 0) {
			return new ServiceStatus("TLS certificate", STATUS_OUTAGE, null, "expired");
		}

		String status = remaining <= CERT_WARN_DAYS ? STATUS_DEGRADED : STATUS_OPERATIONAL;
		return new ServiceStatus(
				"TLS certificate", status, null, String.format("valid for another %d days", remaining));
	}

	private static String describeAge(double ageSeconds) {
		long minutes = Math.round(ageSeconds / SECONDS_PER_MINUTE);
		if (minutes < 1) {
			return "less than a minute ago";
		}
		return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
	}

	private ServiceStatus checkService(String name, String url, String detail) {
		long startTime = System.currentTimeMillis();
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(url))
					.timeout(TIMEOUT)
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			long latency = System.currentTimeMillis() - startTime;
			int statusCode = response.statusCode();

			if (statusCode >= HTTP_SUCCESS_MIN && statusCode < HTTP_SUCCESS_MAX) {
				return new ServiceStatus(name, STATUS_OPERATIONAL, latency, detail);
			} else if (statusCode >= HTTP_SERVER_ERROR_MIN) {
				return new ServiceStatus(name, STATUS_OUTAGE, latency, detail);
			} else {
				return new ServiceStatus(name, STATUS_DEGRADED, latency, detail);
			}
		} catch (java.io.IOException e) {
			LOG.warnf("Health check failed for %s: %s", name, e.getMessage());
			return new ServiceStatus(name, STATUS_OUTAGE, System.currentTimeMillis() - startTime, detail);
		} catch (InterruptedException e) {
			LOG.warnf("Health check interrupted for %s: %s", name, e.getMessage());
			Thread.currentThread().interrupt();
			return new ServiceStatus(name, STATUS_OUTAGE, System.currentTimeMillis() - startTime, detail);
		}
	}

	private String calculateOverallStatus(List<ServiceStatus> services) {
		boolean hasOutage = services.stream().anyMatch(s -> STATUS_OUTAGE.equals(s.status()));
		boolean hasDegraded = services.stream().anyMatch(s -> STATUS_DEGRADED.equals(s.status()));

		if (hasOutage) {
			return STATUS_OUTAGE;
		} else if (hasDegraded) {
			return STATUS_DEGRADED;
		}
		return STATUS_OPERATIONAL;
	}
}
