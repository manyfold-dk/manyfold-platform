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
import dk.manyfold.website.health.AlertmanagerClient;
import dk.manyfold.website.health.AlertmanagerQueryException;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * REST endpoint for receiving Alertmanager webhooks and querying alerts.
 *
 * <p>
 * The list, the critical list and the summary read one source, so the platform
 * page's header and list agree: Alertmanager's active alerts, which both
 * replicas share (issue #529). This replica's webhook store holds only the
 * calls it was sent, so two replicas' stores can disagree, and an entry whose
 * resolution reached the other replica stays in it. It is read only when
 * Alertmanager gives no answer, and the {@value #SOURCE_HEADER} header says
 * which source answered.
 */
@Path("/api/v1/alerts")
@Tag(name = "Alerts", description = "Alert management operations")
public class AlertWebhookResource {

	private static final Logger LOG = Logger.getLogger(AlertWebhookResource.class);

	/**
	 * Where the alert readers' answer came from: {@value #FROM_ALERTMANAGER}, or
	 * {@value #FROM_REPLICA} when Alertmanager gave no answer and this replica's
	 * webhook store answered instead.
	 */
	public static final String SOURCE_HEADER = "X-Alert-Source";

	static final String FROM_ALERTMANAGER = "alertmanager";

	static final String FROM_REPLICA = "replica";

	@Inject
	AlertStoreService alertStore;

	@Inject
	WebhookAuthenticator webhookAuth;

	@Inject
	AlertmanagerClient alertmanager;

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
	public RestResponse<List<StoredAlert>> getActiveAlerts() {
		ActiveAlerts active = activeAlerts();
		return answer(active.alerts(), active);
	}

	/** Get critical alerts. */
	@GET
	@Path("/critical")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get critical alerts", description = "Returns only critical severity alerts")
	@APIResponse(responseCode = "200", description = "Critical alerts returned successfully")
	public RestResponse<List<StoredAlert>> getCriticalAlerts() {
		ActiveAlerts active = activeAlerts();
		return answer(active.alerts().stream()
				.filter(alert -> "critical".equals(alert.severity()))
				.toList(), active);
	}

	/**
	 * Get alert summary.
	 *
	 * <p>
	 * Counted from the same alerts the list shows. /api/v1/health counts
	 * Prometheus' firing alerts instead, which include silenced and inhibited ones,
	 * so the two differ while a silence holds.
	 */
	@GET
	@Path("/summary")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get alert summary", description = "Returns alert counts by severity")
	@APIResponse(responseCode = "200", description = "Summary returned successfully")
	public RestResponse<AlertSummary> getAlertSummary() {
		ActiveAlerts active = activeAlerts();
		return answer(AlertSummary.of(active.alerts()), active);
	}

	/** The active alerts, and which source answered. */
	record ActiveAlerts(List<StoredAlert> alerts, String source) {
	}

	private ActiveAlerts activeAlerts() {
		try {
			return new ActiveAlerts(alertmanager.getActiveAlerts(), FROM_ALERTMANAGER);
		} catch (AlertmanagerQueryException e) {
			LOG.warnf("Alerts unavailable from Alertmanager, answering from this replica's store: %s",
					e.getMessage());
			return new ActiveAlerts(alertStore.getActiveAlerts(), FROM_REPLICA);
		}
	}

	private static <T> RestResponse<T> answer(T entity, ActiveAlerts active) {
		return RestResponse.ResponseBuilder.ok(entity)
				.header(SOURCE_HEADER, active.source())
				.build();
	}

	/**
	 * Alert summary record.
	 *
	 * <p>
	 * {@code info} stays for the readers that key on it. Alertmanager routes only
	 * warning and critical alerts to the webhook, and the alerts are read for that
	 * receiver, so it reads 0.
	 */
	public record AlertSummary(int total, int critical, int warning, int info) {

		static AlertSummary of(List<StoredAlert> alerts) {
			return new AlertSummary(
					alerts.size(),
					count(alerts, "critical"),
					count(alerts, "warning"),
					count(alerts, "info"));
		}

		private static int count(List<StoredAlert> alerts, String severity) {
			return (int) alerts.stream().filter(alert -> severity.equals(alert.severity())).count();
		}
	}
}
