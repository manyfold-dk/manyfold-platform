package dk.manyfold.website.health;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.Alert;
import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.WebhookPayload;

import org.jboss.logging.Logger;

/** In-memory store for active alerts received from Alertmanager webhooks. */
@ApplicationScoped
public class AlertStoreService {

	private static final Logger LOG = Logger.getLogger(AlertStoreService.class);

	/**
	 * Prometheus' always-firing dead-man's-switch alert.
	 *
	 * <p>
	 * It is routed here on purpose so it traverses the full delivery chain
	 * (Alertmanager -> this webhook -> Redis -> slack-bot -> ops-fleet), letting
	 * the VPS detect a severed chain by its absence. It is a liveness marker, not
	 * an incident, so it is published to the stream but never stored: leaving it in
	 * activeAlerts would permanently inflate the alert counts behind
	 * /api/v1/alerts, /summary and the public status page.
	 */
	private static final String WATCHDOG_ALERT = "Watchdog";

	@Inject
	RedisEventPublisher redisEventPublisher;

	// Key: alert fingerprint, Value: alert with metadata
	private final Map<String, StoredAlert> activeAlerts = new ConcurrentHashMap<>();

	/** Stored alert with additional metadata. */
	public record StoredAlert(
			String fingerprint,
			String name,
			String severity,
			String namespace,
			String summary,
			String description,
			String status,
			Instant startsAt,
			Instant receivedAt,
			Map<String, String> labels) {
	}

	/** Process incoming webhook payload from Alertmanager. */
	public void processWebhook(WebhookPayload payload) {
		LOG.infof("Processing webhook: status=%s, alerts=%d", payload.status(), payload.alerts().size());

		for (Alert alert : payload.alerts()) {
			if (WATCHDOG_ALERT.equals(alert.getName())) {
				publishHeartbeat(alert);
			} else if ("firing".equals(alert.status())) {
				addAlert(alert);
			} else if ("resolved".equals(alert.status())) {
				resolveAlert(fingerprintOf(alert));
			}
		}
	}

	/** Forward the watchdog down the stream without storing it. */
	private void publishHeartbeat(Alert alert) {
		redisEventPublisher.publishAlert(toStored(alert), "firing");
		// Debug, not info: this fires every few minutes by design.
		LOG.debugf("Watchdog heartbeat forwarded (not stored)");
	}

	private static String fingerprintOf(Alert alert) {
		String fingerprint = alert.fingerprint();
		if (fingerprint == null || fingerprint.isEmpty()) {
			int hash = alert.getName().hashCode() + alert.labels().hashCode();
			fingerprint = String.valueOf(hash);
		}
		return fingerprint;
	}

	private void addAlert(Alert alert) {
		StoredAlert stored = toStored(alert);
		activeAlerts.put(stored.fingerprint(), stored);
		redisEventPublisher.publishAlert(stored, "firing");
		LOG.infof("Alert added: %s (severity=%s)", alert.getName(), alert.getSeverity());
	}

	private StoredAlert toStored(Alert alert) {
		return new StoredAlert(
				fingerprintOf(alert),
				alert.getName(),
				alert.getSeverity(),
				alert.getNamespace(),
				alert.getSummary(),
				alert.getDescription(),
				alert.status(),
				parseInstant(alert.startsAt()),
				Instant.now(),
				alert.labels());
	}

	private void resolveAlert(String fingerprint) {
		StoredAlert removed = activeAlerts.remove(fingerprint);
		if (removed != null) {
			redisEventPublisher.publishAlert(removed, "resolved");
			LOG.infof("Alert resolved: %s", removed.name());
		}
	}

	/** Get all currently active alerts. */
	public List<StoredAlert> getActiveAlerts() {
		return List.copyOf(activeAlerts.values());
	}

	/** Get active alerts filtered by severity. */
	public List<StoredAlert> getAlertsBySeverity(String severity) {
		return activeAlerts.values().stream()
				.filter(a -> severity.equals(a.severity()))
				.collect(Collectors.toList());
	}

	/** Get count of active alerts by severity. */
	public Map<String, Long> getAlertCountsBySeverity() {
		return activeAlerts.values().stream()
				.collect(Collectors.groupingBy(StoredAlert::severity, Collectors.counting()));
	}

	/** Get critical alerts that may need remediation. */
	public List<StoredAlert> getCriticalAlerts() {
		return getAlertsBySeverity("critical");
	}

	/** Clear all alerts (for testing). */
	public void clearAll() {
		activeAlerts.clear();
	}

	private Instant parseInstant(String timestamp) {
		try {
			return Instant.parse(timestamp);
		} catch (Exception e) {
			return Instant.now();
		}
	}
}
