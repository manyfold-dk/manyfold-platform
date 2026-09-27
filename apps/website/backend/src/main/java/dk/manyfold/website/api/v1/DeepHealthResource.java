package dk.manyfold.website.api.v1;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;

import dk.manyfold.website.api.v1.model.DeepHealthResponse;
import dk.manyfold.website.api.v1.model.DeepHealthResponse.DependencyHealth;
import dk.manyfold.website.api.v1.model.DeepHealthResponse.ResourceUsage;
import dk.manyfold.website.api.v1.model.DeepHealthResponse.SelfHealth;
import dk.manyfold.website.health.PrometheusHealthClient;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Deep health endpoint for website backend service. */
@Path("/api/v1/health/deep")
@Tag(name = "Health", description = "Service health endpoints")
public class DeepHealthResource {

	@ConfigProperty(name = "quarkus.application.name", defaultValue = "website-backend")
	String serviceName;

	@ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
	String serviceVersion;

	@Inject
	PrometheusHealthClient prometheusHealthClient;

	/** Returns deep health including JVM metrics and dependencies. */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Blocking
	@Operation(summary = "Deep health check", description = "Health with dependencies")
	@APIResponse(responseCode = "200", description = "Health returned")
	public DeepHealthResponse getDeepHealth() {
		SelfHealth selfHealth = collectSelfHealth();
		List<DependencyHealth> dependencies = collectDependencyHealth();

		String overallStatus = calculateStatus(selfHealth, dependencies);

		return new DeepHealthResponse(
				overallStatus,
				serviceName,
				serviceVersion,
				Instant.now(),
				selfHealth,
				dependencies,
				collectMetadata());
	}

	private SelfHealth collectSelfHealth() {
		RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
		MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();

		long uptimeSeconds = runtime.getUptime() / 1000;
		long memUsed = memory.getHeapMemoryUsage().getUsed();
		long memMax = memory.getHeapMemoryUsage().getMax();
		int threadCount = threads.getThreadCount();

		// Estimate CPU - JVM doesn't provide easy CPU percentage
		double cpuPercent = estimateCpuUsage();

		ResourceUsage resources = new ResourceUsage(cpuPercent, memUsed, memMax, threadCount);

		// Determine self status based on resource usage
		String status = DeepHealthResponse.STATUS_HEALTHY;
		double memoryPercent = (double) memUsed / memMax * 100;
		if (memoryPercent > 90) {
			status = DeepHealthResponse.STATUS_UNHEALTHY;
		} else if (memoryPercent > 75) {
			status = DeepHealthResponse.STATUS_DEGRADED;
		}

		Map<String, Object> metrics = new HashMap<>();
		metrics.put("heapUsedPercent", Math.round(memoryPercent));
		metrics.put("gcCount", getGcCount());

		return new SelfHealth(status, uptimeSeconds, resources, metrics);
	}

	private List<DependencyHealth> collectDependencyHealth() {
		List<DependencyHealth> deps = new ArrayList<>();

		// Prometheus reachability (spec B2): reported here as informational so a
		// Prometheus outage degrades /health/deep without ever failing the
		// kubelet live/ready probes. queryInstant is synchronous, hence @Blocking
		// on the endpoint.
		long start = System.currentTimeMillis();
		boolean up = prometheusHealthClient.queryInstant("vector(1)").isPresent();
		long latencyMs = System.currentTimeMillis() - start;
		deps.add(new DependencyHealth(
				"prometheus",
				DependencyHealth.TYPE_API,
				up ? DeepHealthResponse.STATUS_HEALTHY : DeepHealthResponse.STATUS_DEGRADED,
				latencyMs,
				up ? "reachable" : "unreachable"));

		return deps;
	}

	private String calculateStatus(SelfHealth self, List<DependencyHealth> deps) {
		if (DeepHealthResponse.STATUS_UNHEALTHY.equals(self.status())) {
			return DeepHealthResponse.STATUS_UNHEALTHY;
		}

		boolean hasDegraded = DeepHealthResponse.STATUS_DEGRADED.equals(self.status());
		for (DependencyHealth dep : deps) {
			if (DeepHealthResponse.STATUS_UNHEALTHY.equals(dep.status())) {
				return DeepHealthResponse.STATUS_UNHEALTHY;
			}
			if (DeepHealthResponse.STATUS_DEGRADED.equals(dep.status())) {
				hasDegraded = true;
			}
		}

		return hasDegraded
				? DeepHealthResponse.STATUS_DEGRADED
				: DeepHealthResponse.STATUS_HEALTHY;
	}

	private Map<String, Object> collectMetadata() {
		Map<String, Object> meta = new HashMap<>();
		RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
		meta.put("javaVersion", System.getProperty("java.version"));
		meta.put("vmName", runtime.getVmName());
		meta.put("startTime", Instant.ofEpochMilli(runtime.getStartTime()).toString());
		return meta;
	}

	private double estimateCpuUsage() {
		// Get CPU load if available via com.sun.management extension
		try {
			var bean = ManagementFactory.getOperatingSystemMXBean();
			var osBean = (com.sun.management.OperatingSystemMXBean) bean;
			double cpuLoad = osBean.getProcessCpuLoad();
			if (cpuLoad >= 0) {
				return Math.round(cpuLoad * 100 * 10) / 10.0;
			}
		} catch (Exception e) {
			// Ignore - not all JVMs support this
		}
		return -1;
	}

	private long getGcCount() {
		return ManagementFactory.getGarbageCollectorMXBeans().stream()
				.mapToLong(gc -> gc.getCollectionCount())
				.filter(count -> count >= 0)
				.sum();
	}
}
