package dk.manyfold.website.api.v1;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import dk.manyfold.website.api.v1.model.StatusResponse;
import dk.manyfold.website.health.PublicStatusService;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoint for platform status.
 *
 * <p>
 * Returns aggregated health status of all platform services for use by
 * monitoring dashboards and iPhone widgets. The checks themselves live in
 * {@link PublicStatusService}, which caches them so that the load this endpoint
 * puts on Prometheus does not follow the load the internet puts on it.
 */
@Path("/api/v1/status")
@Tag(name = "Status", description = "Platform status operations")
public class StatusResource {

	@Inject
	PublicStatusService publicStatusService;

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get platform status", description = "Returns aggregated health status")
	@APIResponse(responseCode = "200", description = "Status returned successfully")
	public Uni<StatusResponse> getStatus() {
		return Uni.createFrom()
				.item(() -> publicStatusService.currentStatus())
				.runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
	}
}
