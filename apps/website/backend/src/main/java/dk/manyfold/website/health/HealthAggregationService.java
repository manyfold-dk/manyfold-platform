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

import dk.manyfold.website.api.v1.model.LayeredHealthResponse;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.ActiveAlert;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.AppHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.ApplicationsHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.ClusterHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.ComponentHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.InfrastructureHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.NetworkHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.PipelinesHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.PlatformHealth;
import dk.manyfold.website.cache.Cached;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

/** Aggregates health information from all platform layers. */
@ApplicationScoped
public class HealthAggregationService {

	private static final Logger LOG = Logger.getLogger(HealthAggregationService.class);
	private static final Duration TIMEOUT = Duration.ofSeconds(5);
	private static final String KUBE_SYSTEM = "kube-system";
	private static final String NAME_LABEL = "app.kubernetes.io/name";

	/**
	 * What a failed check reports. The summary endpoints are public, so an
	 * exception message (host names, client internals) goes to the log, not the
	 * response (review finding W4).
	 */
	static final String CHECK_FAILED = "check failed";

	/**
	 * How old the edge probe may be before it stops counting as a signal. The
	 * Cloudflare Worker pushes every two minutes; five missed runs is a broken
	 * probe, not a broken edge.
	 */
	private static final double EDGE_PROBE_MAX_AGE_SECONDS = 600;

	private final HttpClient httpClient;

	@Inject
	KubernetesHealthClient kubernetesClient;

	@Inject
	PrometheusHealthClient prometheusClient;

	@Inject
	ManagedExecutor executor;

	/**
	 * One pass over this costs several Prometheus queries and a cluster-wide pod
	 * listing, and the endpoints that use it are public. Caching is what keeps the
	 * load on those backends a function of time rather than of how many people --
	 * or how many bots -- are looking at the status page.
	 */
	@ConfigProperty(name = "manyfold.health.cache-ttl-seconds", defaultValue = "15")
	long cacheTtlSeconds;

	/**
	 * This backend's own deep-health endpoint. The pod reads it over loopback; it
	 * is the same process either way.
	 */
	@ConfigProperty(name = "manyfold.health.deep-url", defaultValue = "http://localhost:8080/api/v1/health/deep")
	String deepHealthUrl;

	/**
	 * Applications checked by pod phase through the Kubernetes API, each as
	 * {@code Display name|namespace|label=value}. For applications in other
	 * namespaces whose NetworkPolicy keeps this backend out, so a cross-namespace
	 * HTTP probe is not possible. Which applications a cluster runs is instance
	 * configuration: the overlay sets the list.
	 */
	@ConfigProperty(name = "manyfold.health.pod-apps")
	Optional<List<String>> podApps;

	@Inject
	ObjectMapper objectMapper;

	private Cached<LayeredHealthResponse> cache;

	public HealthAggregationService() {
		this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	}

	@PostConstruct
	void setUp() {
		cache = new Cached<>(
				"layered health", Duration.ofSeconds(cacheTtlSeconds), this::collectHealth, executor);
	}

	/** Platform health, recomputed at most once per cache interval. */
	public LayeredHealthResponse aggregateHealth() {
		return cache.get();
	}

	/** Aggregate health from all platform layers. */
	private LayeredHealthResponse collectHealth() {
		InfrastructureHealth infrastructure = collectInfrastructureHealth();
		NetworkHealth network = collectNetworkHealth();
		ClusterHealth cluster = collectClusterHealth();
		PlatformHealth platform = collectPlatformHealth();
		PipelinesHealth pipelines = collectPipelinesHealth();
		ApplicationsHealth applications = collectApplicationsHealth();
		int firingAlerts = prometheusClient.getFiringAlerts();

		String overallStatus = calculateOverallStatus(
				infrastructure.status(),
				network.status(),
				cluster.status(),
				platform.status(),
				pipelines.status(),
				applications.status());

		return LayeredHealthResponse.of(
				overallStatus,
				infrastructure,
				network,
				cluster,
				platform,
				pipelines,
				applications,
				summariseAlerts(firingAlerts),
				firingAlerts);
	}

	/**
	 * Network layer health.
	 *
	 * <p>
	 * Cilium is the data plane: if its agents are not running, nothing on the
	 * cluster talks to anything. Hubble only observes those flows, so it is
	 * reported beside Cilium rather than being allowed to speak for the layer.
	 */
	private NetworkHealth collectNetworkHealth() {
		ComponentHealth cilium = checkComponentHealth("Cilium", KUBE_SYSTEM, "k8s-app", "cilium");
		ComponentHealth hubble = checkComponentHealth("Hubble", KUBE_SYSTEM, "k8s-app", "hubble-relay");
		return new NetworkHealth(calculateComponentsStatus(cilium, hubble), cilium, hubble);
	}

	InfrastructureHealth collectInfrastructureHealth() {
		try {
			var clusterHealth = kubernetesClient.getClusterHealth();
			// No nodes means the listing failed (this backend runs on one), not a healthy
			// empty cluster.
			String status = clusterHealth.totalNodes() == 0
					? LayeredHealthResponse.STATUS_UNKNOWN
					: clusterHealth.readyNodes() == clusterHealth.totalNodes()
							? LayeredHealthResponse.STATUS_HEALTHY
							: clusterHealth.readyNodes() > 0
									? LayeredHealthResponse.STATUS_DEGRADED
									: LayeredHealthResponse.STATUS_UNHEALTHY;

			return new InfrastructureHealth(
					status, clusterHealth.totalNodes(), clusterHealth.readyNodes(), 0L, 0L, 0L, 0L);
		} catch (Exception e) {
			LOG.warnf(e, "Failed to collect infrastructure health");
			return new InfrastructureHealth(LayeredHealthResponse.STATUS_UNHEALTHY, 0, 0, 0, 0, 0, 0);
		}
	}

	private ClusterHealth collectClusterHealth() {
		try {
			var health = kubernetesClient.getClusterHealth();
			boolean controlPlaneHealthy = health.readyNodes() > 0;
			boolean hasFailedPods = health.failedPods() > 0;

			String status = controlPlaneHealthy && !hasFailedPods
					? LayeredHealthResponse.STATUS_HEALTHY
					: controlPlaneHealthy
							? LayeredHealthResponse.STATUS_DEGRADED
							: LayeredHealthResponse.STATUS_UNHEALTHY;

			return new ClusterHealth(
					status,
					controlPlaneHealthy,
					true,
					true,
					health.totalPods(),
					health.runningPods(),
					health.pendingPods(),
					health.failedPods());
		} catch (Exception e) {
			LOG.warnf(e, "Failed to collect cluster health");
			return new ClusterHealth(
					LayeredHealthResponse.STATUS_UNHEALTHY, false, false, false, 0, 0, 0, 0);
		}
	}

	private PlatformHealth collectPlatformHealth() {
		ComponentHealth edge = checkEdgeHealth();
		ComponentHealth identity = checkIdentityHealth();
		ComponentHealth argocd = checkArgoCDHealth();
		ComponentHealth registry = checkComponentHealth("Registry", "registry-system");
		ComponentHealth observability = checkComponentHealth("Observability", "observability");

		String status = calculateComponentsStatus(edge, identity, argocd, registry, observability);

		return new PlatformHealth(status, edge, identity, argocd, registry, observability);
	}

	/**
	 * The edge, measured from the edge.
	 *
	 * <p>
	 * Every other check in this class runs inside the cluster and therefore cannot
	 * see a failure that happens in front of it -- a DNS change, a Cloudflare
	 * incident, an expired certificate. This one reads the synthetic probe that a
	 * Cloudflare Worker runs against the public site every two minutes (ADR-0036),
	 * which is the only signal here with an outside view.
	 */
	private ComponentHealth checkEdgeHealth() {
		var probe = prometheusClient.getSyntheticProbe("www", "edge", "external");
		if (!probe.fresh(EDGE_PROBE_MAX_AGE_SECONDS)) {
			return new ComponentHealth("Edge", LayeredHealthResponse.STATUS_UNKNOWN, "no recent probe");
		}

		long ms = Math.round(probe.durationSeconds() * 1000);
		return probe.up()
				? new ComponentHealth(
						"Edge",
						LayeredHealthResponse.STATUS_HEALTHY,
						String.format("answered in %d ms from outside", ms))
				: new ComponentHealth(
						"Edge",
						LayeredHealthResponse.STATUS_UNHEALTHY,
						"public site did not answer the edge probe");
	}

	/**
	 * Identity and secrets: the login and the unseal path everything depends on.
	 */
	private ComponentHealth checkIdentityHealth() {
		ComponentHealth keycloak = checkComponentHealth("Keycloak", "keycloak", NAME_LABEL, "keycloak");
		ComponentHealth openbao = checkComponentHealth("OpenBao", "platform-ops", NAME_LABEL, "openbao");

		return new ComponentHealth(
				"Identity and secrets",
				calculateComponentsStatus(keycloak, openbao),
				String.format("Keycloak %s, OpenBao %s", keycloak.status(), openbao.status()));
	}

	private ComponentHealth checkArgoCDHealth() {
		try {
			int synced = prometheusClient.getArgoCDSyncedApps();
			int degraded = prometheusClient.getArgoCDDegradedApps();

			if (degraded > 0) {
				return new ComponentHealth(
						"ArgoCD",
						LayeredHealthResponse.STATUS_DEGRADED,
						String.format("%d apps synced, %d degraded", synced, degraded));
			}
			return new ComponentHealth(
					"ArgoCD",
					LayeredHealthResponse.STATUS_HEALTHY,
					String.format("%d apps synced", synced));
		} catch (Exception e) {
			LOG.warnf(e, "ArgoCD health check failed");
			return new ComponentHealth("ArgoCD", LayeredHealthResponse.STATUS_UNHEALTHY, CHECK_FAILED);
		}
	}

	private ComponentHealth checkComponentHealth(String name, String namespace) {
		try {
			return summarisePods(name, kubernetesClient.getNamespacePodHealth(namespace));
		} catch (Exception e) {
			LOG.warnf(e, "Health check failed for %s", name);
			return new ComponentHealth(name, LayeredHealthResponse.STATUS_UNHEALTHY, CHECK_FAILED);
		}
	}

	/**
	 * Component health scoped to one workload's pods.
	 *
	 * <p>
	 * Needed wherever the namespace holds more than the component: platform-ops
	 * also runs the Slack bot and the OpenBao backup CronJob, whose Succeeded pods
	 * would otherwise read as "not Running" and report a healthy component as
	 * degraded.
	 */
	private ComponentHealth checkComponentHealth(
			String name, String namespace, String labelKey, String labelValue) {
		try {
			var pods = kubernetesClient.getNamespacePodHealth(namespace, labelKey, labelValue);
			return summarisePods(name, pods);
		} catch (Exception e) {
			LOG.warnf(e, "Health check failed for %s", name);
			return new ComponentHealth(name, LayeredHealthResponse.STATUS_UNHEALTHY, CHECK_FAILED);
		}
	}

	private ComponentHealth summarisePods(String name, List<KubernetesHealthClient.PodHealth> pods) {
		long running = pods.stream().filter(p -> "Running".equals(p.phase())).count();
		long total = pods.size();

		if (total == 0) {
			return new ComponentHealth(name, LayeredHealthResponse.STATUS_UNHEALTHY, "No pods found");
		}

		String status = running == total
				? LayeredHealthResponse.STATUS_HEALTHY
				: running > 0
						? LayeredHealthResponse.STATUS_DEGRADED
						: LayeredHealthResponse.STATUS_UNHEALTHY;

		return new ComponentHealth(name, status, String.format("%d/%d pods running", running, total));
	}

	private PipelinesHealth collectPipelinesHealth() {
		try {
			double successRate = prometheusClient.getTektonSuccessRate();
			int totalRuns = prometheusClient.getTektonTotalRuns();
			int successfulRuns = prometheusClient.getTektonSuccessfulRuns();
			int failedRuns = prometheusClient.getTektonFailedRuns();

			String status;
			String lastRunStatus;
			if (successRate < 0 || totalRuns == 0) {
				status = LayeredHealthResponse.STATUS_HEALTHY;
				successRate = 0.0;
				lastRunStatus = "none";
			} else {
				status = successRate >= 90
						? LayeredHealthResponse.STATUS_HEALTHY
						: successRate >= 70
								? LayeredHealthResponse.STATUS_DEGRADED
								: LayeredHealthResponse.STATUS_UNHEALTHY;
				lastRunStatus = failedRuns > 0 ? "failed" : "success";
			}

			long roundedRate = Math.round(successRate);
			return new PipelinesHealth(
					status, totalRuns, successfulRuns, failedRuns, roundedRate, lastRunStatus, "");
		} catch (Exception e) {
			return new PipelinesHealth(LayeredHealthResponse.STATUS_UNHEALTHY, 0, 0, 0, 0, "unknown", "");
		}
	}

	private ApplicationsHealth collectApplicationsHealth() {
		List<AppHealth> apps = new ArrayList<>();

		apps.add(checkDeepHealth("Website Backend", "website", deepHealthUrl));
		for (String entry : podApps.orElse(List.of())) {
			PodApp app = PodApp.parse(entry);
			if (app == null) {
				LOG.warnf("Ignoring manyfold.health.pod-apps entry %s: want name|namespace|label=value",
						entry);
				continue;
			}
			apps.add(checkAppPodHealth(app.name(), app.namespace(), app.labelKey(), app.labelValue()));
		}

		String healthy = LayeredHealthResponse.STATUS_HEALTHY;
		String unhealthy = LayeredHealthResponse.STATUS_UNHEALTHY;
		boolean allHealthy = apps.stream().allMatch(a -> healthy.equals(a.status()));
		boolean anyUnhealthy = apps.stream().anyMatch(a -> unhealthy.equals(a.status()));

		String status = allHealthy
				? LayeredHealthResponse.STATUS_HEALTHY
				: anyUnhealthy
						? LayeredHealthResponse.STATUS_UNHEALTHY
						: LayeredHealthResponse.STATUS_DEGRADED;

		return new ApplicationsHealth(status, apps);
	}

	private AppHealth checkDeepHealth(String name, String namespace, String deepUrl) {
		long startTime = System.currentTimeMillis();
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(deepUrl))
					.timeout(TIMEOUT)
					.GET()
					.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			long latency = System.currentTimeMillis() - startTime;

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				// Parse the status from the deep health response JSON
				String body = response.body();
				String status = extractStatusFromJson(body);
				String message = extractDependencyIssues(body);
				return new AppHealth(name, status, namespace, latency, message);
			}

			return new AppHealth(
					name, LayeredHealthResponse.STATUS_UNHEALTHY, namespace, latency, "HTTP error");
		} catch (Exception e) {
			long latency = System.currentTimeMillis() - startTime;
			LOG.warnf(e, "Deep health check failed for %s", name);
			String unhealthy = LayeredHealthResponse.STATUS_UNHEALTHY;
			return new AppHealth(name, unhealthy, namespace, latency, CHECK_FAILED);
		}
	}

	private AppHealth checkAppPodHealth(String name, String namespace, String labelKey, String labelValue) {
		long startTime = System.currentTimeMillis();
		String unhealthy = LayeredHealthResponse.STATUS_UNHEALTHY;
		try {
			var pods = kubernetesClient.getNamespacePodHealth(namespace, labelKey, labelValue);
			long latency = System.currentTimeMillis() - startTime;
			long running = pods.stream().filter(p -> "Running".equals(p.phase())).count();
			long total = pods.size();

			if (total == 0) {
				return new AppHealth(name, unhealthy, namespace, latency, "No pods found");
			}

			String status = running == total
					? LayeredHealthResponse.STATUS_HEALTHY
					: running > 0 ? LayeredHealthResponse.STATUS_DEGRADED : unhealthy;

			String detail = String.format("%d/%d pods running", running, total);
			return new AppHealth(name, status, namespace, latency, detail);
		} catch (Exception e) {
			long latency = System.currentTimeMillis() - startTime;
			LOG.warnf(e, "Pod health check failed for %s", name);
			return new AppHealth(name, unhealthy, namespace, latency, CHECK_FAILED);
		}
	}

	/** The deep-health response's overall status; healthy when it names none. */
	String extractStatusFromJson(String json) {
		JsonNode status = readTree(json).path("status");
		return status.isTextual() ? status.asText() : LayeredHealthResponse.STATUS_HEALTHY;
	}

	/**
	 * The first unhealthy dependency, as an operator-readable line; empty when
	 * none.
	 */
	String extractDependencyIssues(String json) {
		for (JsonNode dependency : readTree(json).path("dependencies")) {
			if (LayeredHealthResponse.STATUS_UNHEALTHY.equals(dependency.path("status").asText())) {
				return "Dependency unhealthy: " + dependency.path("name").asText("unknown");
			}
		}
		return "";
	}

	private JsonNode readTree(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("deep health returned invalid JSON", e);
		}
	}

	/** One {@code manyfold.health.pod-apps} entry. */
	record PodApp(String name, String namespace, String labelKey, String labelValue) {
		static PodApp parse(String entry) {
			String[] parts = entry.split("\\|");
			if (parts.length != 3) {
				return null;
			}
			String[] label = parts[2].split("=", 2);
			if (label.length != 2 || parts[0].isBlank() || parts[1].isBlank() || label[0].isBlank()) {
				return null;
			}
			return new PodApp(parts[0].trim(), parts[1].trim(), label[0].trim(), label[1].trim());
		}
	}

	/**
	 * Summarise the firing alerts without naming them.
	 *
	 * <p>
	 * This response is public, so it carries the count and nothing else -- which
	 * alert is firing in which namespace is exactly the detail an outsider should
	 * not get for free. Operators read the named alerts from /api/v1/alerts, which
	 * is behind the login. The count is also returned separately as
	 * {@code firingAlerts}, because the size of this list is the number of
	 * summaries, not the number of alerts.
	 */
	private List<ActiveAlert> summariseAlerts(int firingAlerts) {
		if (firingAlerts == 0) {
			return List.of();
		}
		return List.of(
				new ActiveAlert(
						"Multiple Alerts",
						"warning",
						String.format("%d alerts firing", firingAlerts),
						"",
						""));
	}

	/**
	 * Roll several signals into one.
	 *
	 * <p>
	 * A signal nobody measured this pass must not be read as a fault, or a paused
	 * probe would take the whole platform red; it must not be read as health
	 * either, so a group with no measured signal at all stays unknown.
	 */
	private String calculateOverallStatus(String... statuses) {
		boolean anyMeasured = false;
		for (String status : statuses) {
			if (LayeredHealthResponse.STATUS_UNHEALTHY.equals(status)) {
				return LayeredHealthResponse.STATUS_UNHEALTHY;
			}
			if (!LayeredHealthResponse.STATUS_UNKNOWN.equals(status)) {
				anyMeasured = true;
			}
		}
		for (String status : statuses) {
			if (LayeredHealthResponse.STATUS_DEGRADED.equals(status)) {
				return LayeredHealthResponse.STATUS_DEGRADED;
			}
		}
		return anyMeasured
				? LayeredHealthResponse.STATUS_HEALTHY
				: LayeredHealthResponse.STATUS_UNKNOWN;
	}

	private String calculateComponentsStatus(ComponentHealth... components) {
		String[] statuses = new String[components.length];
		for (int i = 0; i < components.length; i++) {
			statuses[i] = components[i].status();
		}
		return calculateOverallStatus(statuses);
	}
}
