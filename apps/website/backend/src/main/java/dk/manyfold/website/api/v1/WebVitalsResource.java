package dk.manyfold.website.api.v1;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

import dk.manyfold.website.api.v1.model.WebVitalEntry;
import dk.manyfold.website.api.v1.model.WebVitalsBatch;
import dk.manyfold.website.metrics.WebVitalsMetrics;
import dk.manyfold.website.metrics.WebVitalsStore;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/** REST endpoint for receiving Web Vital metrics from the frontend. */
@Path("/api/v1/metrics/vitals")
@Tag(name = "Metrics", description = "Frontend metrics collection")
public class WebVitalsResource {

	private static final Logger LOG = Logger.getLogger(WebVitalsResource.class);

	@Inject
	private WebVitalsMetrics metrics;
	@Inject
	private WebVitalsStore store;

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Submit Web Vitals", description = "Receives batched Web Vital metrics from the frontend")
	@APIResponse(responseCode = "204", description = "Metrics recorded successfully")
	@APIResponse(responseCode = "400", description = "Invalid request body")
	public Uni<Response> submitVitals(@Valid @NotNull WebVitalsBatch batch) {
		LOG.debugf("Received %d Web Vital entries", batch.entries().size());

		for (var entry : batch.entries()) {
			metrics.record(entry);
			store.add(entry);
		}

		return Uni.createFrom().item(Response.noContent().build());
	}

	@GET
	@Path("/recent")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Recent web vitals", description = "Returns recent recorded web vitals")
	@APIResponse(responseCode = "200", description = "Recent entries returned")
	public List<WebVitalEntry> getRecentVitals(
			@QueryParam("limit") @DefaultValue("50") int limit) {
		return List.copyOf(store.getRecent(limit));
	}
}
