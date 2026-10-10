package dk.manyfold.website.health;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.Alert;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads the active alerts from Alertmanager's API (issue #529).
 *
 * <p>
 * Both backend replicas read the same Alertmanager, so both see the same
 * alerts, whichever replica each webhook call reached. The query keeps the
 * alerts the webhook would deliver: firing, not silenced, not inhibited, and
 * routed to the backend's receiver. Prometheus' {@code ALERTS} would be shared
 * too, but it knows nothing of silences or inhibitions, so auto-remediation
 * reading it would act on alerts an operator has silenced.
 */
@ApplicationScoped
public class AlertmanagerClient {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/**
	 * The filters that reproduce what the webhook delivers, before the receiver.
	 * {@code unprocessed=false} drops an alert Alertmanager has not yet checked
	 * against its silences and inhibitions.
	 */
	static final String ACTIVE_ALERTS_PATH = "/api/v2/alerts"
			+ "?active=true&silenced=false&inhibited=false&unprocessed=false&receiver=";

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	@ConfigProperty(name = "manyfold.alertmanager.url")
	String alertmanagerUrl;

	/**
	 * The receiver the backend's webhook is configured as in Alertmanager.
	 * Alertmanager reads the {@code receiver} filter as a regular expression
	 * anchored at both ends; the answer is checked against the exact name as well.
	 */
	@ConfigProperty(name = "manyfold.alertmanager.receiver", defaultValue = "backend-webhook")
	String receiver;

	public AlertmanagerClient() {
		this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
		this.objectMapper = new ObjectMapper();
	}

	/**
	 * The alerts Alertmanager would deliver to the backend's webhook now.
	 *
	 * <p>
	 * The Watchdog heartbeat is routed to the webhook but is not an alert, so it is
	 * left out, as the webhook's store leaves it out. The answer is checked again
	 * against the filters, so an Alertmanager or a proxy that ignored them cannot
	 * pass a silenced, inhibited or muted alert through.
	 *
	 * @return the active alerts; empty when none is firing
	 * @throws AlertmanagerQueryException
	 *             when Alertmanager gave no usable answer -- unreachable, an error
	 *             status, or a body that is not a list of alerts. Empty is an
	 *             answer; this is the absence of one.
	 */
	public List<StoredAlert> getActiveAlerts() throws AlertmanagerQueryException {
		List<StoredAlert> alerts = new ArrayList<>();
		for (JsonNode node : query()) {
			Optional<StoredAlert> alert = activeAlertOf(node);
			alert.ifPresent(alerts::add);
		}
		return alerts;
	}

	private JsonNode query() throws AlertmanagerQueryException {
		String url = alertmanagerUrl + ACTIVE_ALERTS_PATH + URLEncoder.encode(receiver, StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.timeout(TIMEOUT)
				.GET()
				.build();

		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new AlertmanagerQueryException("Alertmanager did not answer: " + e, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AlertmanagerQueryException("Alertmanager query interrupted", e);
		}

		int code = response.statusCode();
		if (code != 200) {
			throw new AlertmanagerQueryException(String.format("Alertmanager answered %d", code));
		}
		JsonNode root;
		try {
			root = objectMapper.readTree(response.body());
		} catch (JsonProcessingException e) {
			throw new AlertmanagerQueryException("Alertmanager answered with a body that is not JSON", e);
		}
		if (root == null || !root.isArray()) {
			throw new AlertmanagerQueryException(
					"Alertmanager answered with something other than a list of alerts");
		}
		return root;
	}

	/**
	 * One alert of the answer, or empty when it is not one the webhook would
	 * deliver.
	 *
	 * @throws AlertmanagerQueryException
	 *             when the entry lacks the labels, status or fingerprint every
	 *             alert of the API has: an answer that cannot be read whole is not
	 *             acted on in part
	 */
	private Optional<StoredAlert> activeAlertOf(JsonNode node) throws AlertmanagerQueryException {
		JsonNode labels = node.path("labels");
		JsonNode status = node.path("status");
		String fingerprint = node.path("fingerprint").asText("");
		if (!labels.isObject() || !status.isObject() || fingerprint.isEmpty()) {
			throw new AlertmanagerQueryException(
					"Alertmanager answered with an alert without labels, status or fingerprint");
		}
		if (!isActive(status) || !isRoutedToReceiver(node)) {
			return Optional.empty();
		}

		Alert alert = new Alert(
				"firing",
				textValues(labels),
				textValues(node.path("annotations")),
				node.path("startsAt").asText(""),
				node.path("endsAt").asText(""),
				node.path("generatorURL").asText(""),
				fingerprint);
		if (AlertStoreService.WATCHDOG_ALERT.equals(alert.getName())) {
			return Optional.empty();
		}
		return Optional.of(AlertStoreService.toStored(alert, instantOf(node.path("updatedAt"))));
	}

	/**
	 * Firing, and neither silenced, inhibited nor muted by a time interval: the
	 * webhook is sent none of those.
	 */
	private static boolean isActive(JsonNode status) {
		return "active".equals(status.path("state").asText())
				&& status.path("silencedBy").isEmpty()
				&& status.path("inhibitedBy").isEmpty()
				&& status.path("mutedBy").isEmpty();
	}

	private boolean isRoutedToReceiver(JsonNode node) {
		for (JsonNode routedTo : node.path("receivers")) {
			if (receiver.equals(routedTo.path("name").asText())) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, String> textValues(JsonNode object) {
		Map<String, String> values = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> field : object.properties()) {
			values.put(field.getKey(), field.getValue().asText());
		}
		return Collections.unmodifiableMap(values);
	}

	/** When Alertmanager last heard of the alert; now when it does not say. */
	private static Instant instantOf(JsonNode timestamp) {
		try {
			return Instant.parse(timestamp.asText(""));
		} catch (DateTimeParseException e) {
			return Instant.now();
		}
	}
}
