package dk.manyfold.website.health;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.Alert;
import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.WebhookPayload;

import org.jboss.logging.Logger;

/**
 * In-memory store for active alerts received from Alertmanager webhooks, and
 * the publisher of those alerts to the platform:alerts stream.
 *
 * <p>
 * The store is this replica's own. Alertmanager delivers each webhook call to
 * one of the backend's replicas, so a replica holds only the alerts it was
 * sent, and a resolution can reach a replica that never saw the alert fire.
 * That is why the stream events are built from the webhook payload rather than
 * from the store, and why nothing reads the store while Alertmanager answers:
 * the alert list, its counts and auto-remediation read Alertmanager's own
 * active alerts ({@link AlertmanagerClient}, issue #529). The store is the
 * alert list's fallback when Alertmanager gives no answer, never
 * auto-remediation's.
 */
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
	 * activeAlerts would permanently inflate the alert list behind /api/v1/alerts.
	 */
	static final String WATCHDOG_ALERT = "Watchdog";

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
				resolveAlert(alert);
			}
		}
	}

	/** Forward the watchdog down the stream without storing it. */
	private void publishHeartbeat(Alert alert) {
		redisEventPublisher.publishAlert(toStored(alert, Instant.now()), "firing");
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
		StoredAlert stored = toStored(alert, Instant.now());
		activeAlerts.put(stored.fingerprint(), stored);
		redisEventPublisher.publishAlert(stored, "firing");
		LOG.infof("Alert added: %s (severity=%s)", alert.getName(), alert.getSeverity());
	}

	/**
	 * An alert as the store and the alert list hold it, whether it came from a
	 * webhook call or from Alertmanager's API: both carry the same fingerprint,
	 * labels and annotations. The labels are kept whole; what may act on the
	 * {@code pod} label is decided where a pod is acted on
	 * ({@link AutoRemediationService#POD_RESTART_ALERTS},
	 * {@link RedisEventPublisher#SUBJECT_POD_ALERTS}).
	 *
	 * @param receivedAt
	 *            when the alert reached the platform: the webhook call's arrival,
	 *            or Alertmanager's last update of the alert
	 */
	static StoredAlert toStored(Alert alert, Instant receivedAt) {
		return new StoredAlert(
				fingerprintOf(alert),
				alert.getName(),
				alert.getSeverity(),
				alert.getNamespace(),
				alert.getSummary(),
				alert.getDescription(),
				alert.status(),
				parseInstant(alert.startsAt()),
				receivedAt,
				alert.labels());
	}

	/**
	 * Drop the alert from this replica's store and publish its resolution.
	 *
	 * <p>
	 * The event is built from the webhook payload, which carries the fingerprint,
	 * labels and annotations, exactly as a firing event is. It must not depend on
	 * the store: the replica that saw the alert fire may not be the one the
	 * resolution reaches, and a resolution that is never published leaves the alert
	 * open for every consumer of the stream (issue #518). The publisher keeps a
	 * resolution delivered twice, to either replica, from being published twice.
	 */
	private void resolveAlert(Alert alert) {
		StoredAlert resolved = toStored(alert, Instant.now());
		activeAlerts.remove(resolved.fingerprint());
		redisEventPublisher.publishResolution(resolved, episodeOf(alert, resolved.fingerprint()));
		LOG.infof("Alert resolved: %s", resolved.name());
	}

	/**
	 * One firing of an alert: its fingerprint and its start time.
	 *
	 * <p>
	 * The start time is the raw payload value, the same in the firing and the
	 * resolved notification. An alert that fires again later starts a new episode,
	 * which gets a resolution of its own.
	 */
	private static String episodeOf(Alert alert, String fingerprint) {
		return fingerprint + "@" + alert.startsAt();
	}

	/**
	 * The alerts this replica was sent and has not seen resolve.
	 *
	 * <p>
	 * Only the alert list's fallback reads this: an entry whose resolution reached
	 * the other replica stays here until this replica restarts.
	 */
	public List<StoredAlert> getActiveAlerts() {
		return List.copyOf(activeAlerts.values());
	}

	/** Clear all alerts (for testing). */
	public void clearAll() {
		activeAlerts.clear();
	}

	private static Instant parseInstant(String timestamp) {
		try {
			return Instant.parse(timestamp);
		} catch (Exception e) {
			return Instant.now();
		}
	}
}
