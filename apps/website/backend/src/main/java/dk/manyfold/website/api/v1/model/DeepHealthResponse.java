package dk.manyfold.website.api.v1.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Standardized deep health response format for applications.
 *
 * <p>
 * All applications should implement the /api/v1/health/deep endpoint returning
 * this format. This enables the platform to aggregate health across all
 * services and make informed remediation decisions.
 */
public record DeepHealthResponse(
		String status,
		String service,
		String version,
		Instant timestamp,
		SelfHealth self,
		List<DependencyHealth> dependencies,
		Map<String, Object> metadata) {

	/** Health status constants. */
	public static final String STATUS_HEALTHY = "healthy";
	public static final String STATUS_DEGRADED = "degraded";
	public static final String STATUS_UNHEALTHY = "unhealthy";

	/** Self-health information for the service. */
	public record SelfHealth(
			String status,
			long uptimeSeconds,
			ResourceUsage resources,
			Map<String, Object> metrics) {
	}

	/** Resource usage information. */
	public record ResourceUsage(
			double cpuPercent,
			long memoryUsedBytes,
			long memoryMaxBytes,
			int activeThreads) {
	}

	/** Health of a dependency (database, external service, etc.). */
	public record DependencyHealth(
			String name,
			String type,
			String status,
			long latencyMs,
			String message) {

		/** Common dependency types. */
		public static final String TYPE_DATABASE = "database";
		public static final String TYPE_CACHE = "cache";
		public static final String TYPE_STORAGE = "storage";
		public static final String TYPE_API = "api";
		public static final String TYPE_QUEUE = "queue";
	}

	/**
	 * Creates a healthy response with minimal information.
	 *
	 * @param service
	 *            the service name
	 * @param version
	 *            the service version
	 * @return a healthy deep health response
	 */
	public static DeepHealthResponse healthy(String service, String version) {
		return new DeepHealthResponse(
				STATUS_HEALTHY,
				service,
				version,
				Instant.now(),
				new SelfHealth(STATUS_HEALTHY, 0, null, Map.of()),
				List.of(),
				Map.of());
	}

	/**
	 * Calculates overall status from self and dependencies.
	 *
	 * @return the worst status among self and all dependencies
	 */
	public String calculateOverallStatus() {
		if (STATUS_UNHEALTHY.equals(self.status())) {
			return STATUS_UNHEALTHY;
		}

		boolean hasDegraded = STATUS_DEGRADED.equals(self.status());
		for (DependencyHealth dep : dependencies) {
			if (STATUS_UNHEALTHY.equals(dep.status())) {
				return STATUS_UNHEALTHY;
			}
			if (STATUS_DEGRADED.equals(dep.status())) {
				hasDegraded = true;
			}
		}

		return hasDegraded ? STATUS_DEGRADED : STATUS_HEALTHY;
	}
}
