package dk.manyfold.website.api.v1;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import dk.manyfold.website.api.v1.model.LayeredHealthResponse;
import dk.manyfold.website.health.HealthAggregationService;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * REST endpoint for layered platform health.
 *
 * <p>
 * Provides comprehensive health information across all platform layers:
 * infrastructure, cluster, platform components, pipelines, and applications.
 */
@Path("/api/v1/health")
@Tag(name = "Health", description = "Platform health operations")
public class LayeredHealthResource {

	private static final Logger LOG = Logger.getLogger(LayeredHealthResource.class);

	@Inject
	HealthAggregationService healthAggregationService;

	/** Get layered platform health. */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get layered platform health", description = "Returns health across all layers")
	@APIResponse(responseCode = "200", description = "Health status returned successfully")
	public Uni<LayeredHealthResponse> getLayeredHealth() {
		return Uni.createFrom()
				.item(() -> {
					LOG.debug("Collecting layered health...");
					LayeredHealthResponse response = healthAggregationService.aggregateHealth();
					LOG.infof("Layered health check complete: %s", response.overallStatus());
					return response;
				})
				.runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
	}

	/** Get health summary. */
	@GET
	@Path("/summary")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get health summary", description = "Returns simplified health for status pages")
	@APIResponse(responseCode = "200", description = "Health summary returned successfully")
	public Uni<HealthSummary> getHealthSummary() {
		return getLayeredHealth()
				.map(
						health -> new HealthSummary(
								health.overallStatus(),
								health.infrastructure().status(),
								health.network().status(),
								health.cluster().status(),
								health.applications().status(),
								health.platform().status(),
								health.platform().edge().status(),
								health.platform().identity().status(),
								deliveryStatus(health),
								health.platform().observability().status(),
								health.pipelines().status(),
								health.firingAlerts(),
								health.timestamp()));
	}

	/**
	 * Delivery is GitOps and CI together.
	 *
	 * <p>
	 * Argo CD reconciling a stale revision and a pipeline that cannot build are
	 * both "nothing new reaches production", so the public page shows one signal
	 * for the pair rather than making a visitor combine them.
	 */
	private static String deliveryStatus(LayeredHealthResponse health) {
		String argocd = health.platform().argocd().status();
		String pipelines = health.pipelines().status();
		if (LayeredHealthResponse.STATUS_UNHEALTHY.equals(argocd)
				|| LayeredHealthResponse.STATUS_UNHEALTHY.equals(pipelines)) {
			return LayeredHealthResponse.STATUS_UNHEALTHY;
		}
		if (LayeredHealthResponse.STATUS_DEGRADED.equals(argocd)
				|| LayeredHealthResponse.STATUS_DEGRADED.equals(pipelines)) {
			return LayeredHealthResponse.STATUS_DEGRADED;
		}
		return LayeredHealthResponse.STATUS_HEALTHY;
	}

	/**
	 * Simplified health summary for status pages.
	 *
	 * <p>
	 * One field per row of the public picture: the four stack layers
	 * (infrastructure, network, cluster, applications) and the four services that
	 * run on them (edge, identity, delivery, observability), plus the roll-ups the
	 * phone widget and the header pill already read. Fields are only ever added
	 * here -- the iPhone widget and /status.html read this by key.
	 */
	public record HealthSummary(
			String overall,
			String infrastructure,
			String network,
			String cluster,
			String applications,
			String platform,
			String edge,
			String identity,
			String delivery,
			String observability,
			String pipelines,
			int activeAlerts,
			String timestamp) {
	}
}
