package dk.manyfold.website.api.v1;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

import dk.manyfold.website.api.v1.model.StatusHistoryResponse;
import dk.manyfold.website.health.StatusHistoryService;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Recent history for the public checks, for the charts on the status page.
 *
 * <p>
 * Public, so it must also be listed in the oauth2-proxy allowlist
 * (platform/components/oauth2-proxy/values-homepage.yaml). That regex is an
 * allowlist: a path it does not match is sent to Keycloak.
 */
@Path("/api/v1/status/history")
@Tag(name = "Status", description = "Platform status operations")
public class StatusHistoryResource {

	@Inject
	StatusHistoryService statusHistoryService;

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get status history", description = "Returns uptime and latency series")
	@APIResponse(responseCode = "200", description = "History returned successfully")
	public Uni<StatusHistoryResponse> getHistory() {
		return Uni.createFrom()
				.item(() -> statusHistoryService.currentHistory())
				.runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
	}
}
