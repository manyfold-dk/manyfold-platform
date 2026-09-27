package dk.manyfold.website.api.v1;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.WebhookPayload;
import dk.manyfold.website.auth.WebhookAuthenticator;
import dk.manyfold.website.health.AlertStoreService;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/** REST endpoint for receiving Alertmanager webhooks and querying alerts. */
@Path("/api/v1/alerts")
@Tag(name = "Alerts", description = "Alert management operations")
public class AlertWebhookResource {

	private static final Logger LOG = Logger.getLogger(AlertWebhookResource.class);

	@Inject
	AlertStoreService alertStore;

	@Inject
	WebhookAuthenticator webhookAuth;

	/** Receive webhook from Alertmanager. */
	@POST
	@Path("/webhook")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Receive Alertmanager webhook", description = "Endpoint for alert notifications")
	@APIResponse(responseCode = "200", description = "Webhook processed successfully")
	@APIResponse(responseCode = "401", description = "Missing or wrong webhook token")
	public Response receiveWebhook(
			@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
			@Valid @NotNull WebhookPayload payload) {
		if (!webhookAuth.isAuthorized(authorization)) {
			LOG.warn("Alertmanager webhook rejected: missing or wrong token");
			return Response.status(Response.Status.UNAUTHORIZED).build();
		}
		LOG.infof("Received Alertmanager webhook: %d alerts", payload.alerts().size());
		alertStore.processWebhook(payload);
		return Response.ok(Map.of("status", "received", "alerts", payload.alerts().size())).build();
	}

	/** Get all active alerts. */
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get active alerts", description = "Returns all currently firing alerts")
	@APIResponse(responseCode = "200", description = "Alerts returned successfully")
	public List<StoredAlert> getActiveAlerts() {
		return alertStore.getActiveAlerts();
	}

	/** Get critical alerts. */
	@GET
	@Path("/critical")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get critical alerts", description = "Returns only critical severity alerts")
	@APIResponse(responseCode = "200", description = "Critical alerts returned successfully")
	public List<StoredAlert> getCriticalAlerts() {
		return alertStore.getCriticalAlerts();
	}

	/** Get alert summary. */
	@GET
	@Path("/summary")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get alert summary", description = "Returns alert counts by severity")
	@APIResponse(responseCode = "200", description = "Summary returned successfully")
	public AlertSummary getAlertSummary() {
		Map<String, Long> counts = alertStore.getAlertCountsBySeverity();
		return new AlertSummary(
				alertStore.getActiveAlerts().size(),
				counts.getOrDefault("critical", 0L).intValue(),
				counts.getOrDefault("warning", 0L).intValue(),
				counts.getOrDefault("info", 0L).intValue());
	}

	/** Alert summary record. */
	public record AlertSummary(int total, int critical, int warning, int info) {
	}
}
