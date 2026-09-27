package dk.manyfold.website.api.v1.model;

import java.time.Instant;
import java.util.List;

/** Layered health response model providing health at all platform layers. */
public record LayeredHealthResponse(
		String overallStatus,
		InfrastructureHealth infrastructure,
		NetworkHealth network,
		ClusterHealth cluster,
		PlatformHealth platform,
		PipelinesHealth pipelines,
		ApplicationsHealth applications,
		List<ActiveAlert> activeAlerts,
		int firingAlerts,
		String timestamp) {

	public static final String STATUS_HEALTHY = "healthy";
	public static final String STATUS_DEGRADED = "degraded";
	public static final String STATUS_UNHEALTHY = "unhealthy";

	/**
	 * Nothing measured this signal on this pass.
	 *
	 * <p>
	 * It is neither health nor a fault: a roll-up ignores it while any sibling is
	 * measured, and stays unknown when none is. Public status pages render it as
	 * "not measured" rather than borrowing a neighbour's colour.
	 */
	public static final String STATUS_UNKNOWN = "unknown";

	/** Infrastructure health including nodes and storage. */
	public record InfrastructureHealth(
			String status,
			int totalNodes,
			int healthyNodes,
			long totalMemoryBytes,
			long usedMemoryBytes,
			long totalStorageBytes,
			long usedStorageBytes) {
	}

	/** Network health: the CNI and its flow visibility. */
	public record NetworkHealth(String status, ComponentHealth cilium, ComponentHealth hubble) {
	}

	/** Cluster health including control plane and pods. */
	public record ClusterHealth(
			String status,
			boolean controlPlaneHealthy,
			boolean networkingHealthy,
			boolean dnsHealthy,
			int totalPods,
			int runningPods,
			int pendingPods,
			int failedPods) {
	}

	/** Platform health: the services that run on the stack. */
	public record PlatformHealth(
			String status,
			ComponentHealth edge,
			ComponentHealth identity,
			ComponentHealth argocd,
			ComponentHealth registry,
			ComponentHealth observability) {
	}

	/** Individual component health. */
	public record ComponentHealth(String name, String status, String details) {
	}

	/** Pipelines health including run statistics. */
	public record PipelinesHealth(
			String status,
			int totalRuns24h,
			int successfulRuns24h,
			int failedRuns24h,
			double successRate,
			String lastRunStatus,
			String lastRunTime) {
	}

	/** Applications health with per-app details. */
	public record ApplicationsHealth(String status, List<AppHealth> apps) {
	}

	/** Individual application health. */
	public record AppHealth(String name, String status, String namespace, long latencyMs, String message) {
	}

	/** Active alert information. */
	public record ActiveAlert(String name, String severity, String message, String namespace, String since) {
	}

	/** Factory method to create a response with current timestamp. */
	public static LayeredHealthResponse of(
			String overallStatus,
			InfrastructureHealth infrastructure,
			NetworkHealth network,
			ClusterHealth cluster,
			PlatformHealth platform,
			PipelinesHealth pipelines,
			ApplicationsHealth applications,
			List<ActiveAlert> activeAlerts,
			int firingAlerts) {
		return new LayeredHealthResponse(
				overallStatus,
				infrastructure,
				network,
				cluster,
				platform,
				pipelines,
				applications,
				activeAlerts,
				firingAlerts,
				Instant.now().toString());
	}
}
