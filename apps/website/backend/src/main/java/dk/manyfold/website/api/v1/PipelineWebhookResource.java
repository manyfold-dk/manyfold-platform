package dk.manyfold.website.api.v1;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dk.manyfold.website.api.v1.model.PipelineEvent;
import dk.manyfold.website.auth.WebhookAuthenticator;
import dk.manyfold.website.health.RedisEventPublisher;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;

/**
 * Receives pipeline completion webhooks from a CI engine and publishes to Redis
 * Streams.
 */
@Path("/api/v1/pipeline")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PipelineWebhookResource {

	private static final Logger LOG = Logger.getLogger(PipelineWebhookResource.class);

	@Inject
	RedisEventPublisher publisher;

	@Inject
	WebhookAuthenticator webhookAuth;

	/** Receive a pipeline completion event. */
	@POST
	@Path("/webhook")
	@Operation(summary = "Pipeline webhook", description = "Receives pipeline completion events from a CI engine")
	public Response receivePipelineEvent(
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, PipelineEvent event) {
		if (!webhookAuth.isAuthorized(authorization)) {
			LOG.warn("Pipeline webhook rejected: missing or wrong token");
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}
		LOG.infof("Pipeline webhook received: pipeline=%s status=%s", event.pipeline(), event.status());
		publisher.publishDeployment(event);
		return Response.ok().build();
	}
}
