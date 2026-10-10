package dk.manyfold.website.health;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.stream.StreamCommands;
import io.quarkus.redis.datasource.stream.XAddArgs;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;

import dk.manyfold.website.api.v1.model.PipelineEvent;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;

import org.jboss.logging.Logger;

/**
 * Publishes alert and deployment events to Redis Streams for the Slack bot to
 * consume.
 */
@ApplicationScoped
public class RedisEventPublisher {

	private static final Logger LOG = Logger.getLogger(RedisEventPublisher.class);

	private static final String ALERT_STREAM = "platform:alerts";
	private static final String DEPLOY_STREAM = "platform:deployments";
	private static final long MAX_STREAM_LENGTH = 1000;

	/** Where a published resolution is recorded, one key per alert episode. */
	private static final String RESOLUTION_KEY_PREFIX = "platform:alerts:resolved:";

	/**
	 * How long a published resolution is remembered: far longer than Alertmanager
	 * delivers one again (a failed notification is retried, and a resolved alert
	 * can ride along in the group's next notification, minutes apart).
	 */
	private static final Duration RESOLUTION_MEMORY = Duration.ofHours(24);

	/** Alerts on a workload's own pod series whose pod nothing restarts unasked. */
	private static final Set<String> APPROVAL_ONLY_POD_ALERTS = Set.of(
			"KubeContainerWaiting", "BackendPodRestarts", "BackendPodNotReady", "VeleroPodNotReady");

	/**
	 * Alerts whose {@code pod} label names the pod the alert is about: the
	 * auto-remediator's {@link AutoRemediationService#POD_RESTART_ALERTS}, plus
	 * alerts on a pod's own series whose restart is left to a human.
	 *
	 * <p>
	 * Only these alerts carry their pod to the slack-bot, whose Restart Pod button
	 * goes through the approval flow. Many other alerts carry the pod of the
	 * exporter behind the metric (kube-state-metrics, the Argo CD application
	 * controller, node-exporter), not of the workload in trouble.
	 */
	static final Set<String> SUBJECT_POD_ALERTS = Stream
			.concat(AutoRemediationService.POD_RESTART_ALERTS.stream(), APPROVAL_ONLY_POD_ALERTS.stream())
			.collect(Collectors.toUnmodifiableSet());

	private final StreamCommands<String, String, String> streamCommands;

	private final ValueCommands<String, String> valueCommands;

	@Inject
	public RedisEventPublisher(RedisDataSource ds) {
		this.streamCommands = ds.stream(String.class, String.class, String.class);
		this.valueCommands = ds.value(String.class);
	}

	/**
	 * Publish an alert event (firing or resolved) to the platform:alerts stream.
	 */
	public void publishAlert(StoredAlert alert, String status) {
		appendAlert(alert, status);
	}

	/** @return true when the event reached the stream */
	private boolean appendAlert(StoredAlert alert, String status) {
		try {
			streamCommands.xadd(ALERT_STREAM,
					new XAddArgs().maxlen(MAX_STREAM_LENGTH),
					alertFields(alert, status));
			LOG.infof("Published alert event: %s status=%s",
					alert.name(), status);
			return true;
		} catch (Exception e) {
			LOG.errorf(e,
					"Failed to publish alert event: %s",
					alert.name());
			return false;
		}
	}

	/**
	 * Publish an alert's resolution, unless it was published already.
	 *
	 * <p>
	 * Alertmanager delivers each webhook call to one of the backend's replicas and
	 * can deliver a resolution more than once, to either of them: a retry of a call
	 * it could not confirm, or the resolved alert riding along in the group's next
	 * notification. So "already" is recorded in Redis, which both replicas share,
	 * not in a replica's memory.
	 *
	 * <p>
	 * The record is written only after the event reached the stream. Every failure
	 * then errs toward a second resolution, never toward none: a failed stream
	 * write leaves no record, so the next delivery publishes it, and a replica that
	 * stops before recording leaves the next delivery to publish it again. A lost
	 * resolution leaves the alert open for every consumer; a duplicate costs a
	 * second message. The check and the record are not atomic, so two deliveries at
	 * the same moment could both publish, but Alertmanager sends one group's
	 * notifications one after another.
	 *
	 * @param episode
	 *            one firing of the alert: its fingerprint and start time. An alert
	 *            that fires again later is a new episode, with a resolution of its
	 *            own.
	 */
	public void publishResolution(StoredAlert alert, String episode) {
		String key = RESOLUTION_KEY_PREFIX + episode;
		if (resolutionPublished(key)) {
			LOG.debugf("Resolution already published, not repeated: %s", alert.name());
			return;
		}
		if (appendAlert(alert, "resolved")) {
			recordResolution(key);
		}
	}

	/** When Redis cannot answer, the resolution counts as not yet published. */
	private boolean resolutionPublished(String key) {
		try {
			return valueCommands.get(key) != null;
		} catch (Exception e) {
			LOG.warnf("Could not read the resolution record %s, publishing it: %s", key, e.getMessage());
			return false;
		}
	}

	private void recordResolution(String key) {
		try {
			valueCommands.set(key, Instant.now().toString(), new SetArgs().ex(RESOLUTION_MEMORY));
		} catch (Exception e) {
			LOG.warnf("Could not record the resolution %s; its next delivery publishes it again: %s",
					key, e.getMessage());
		}
	}

	/**
	 * Publish a deployment event to the platform:deployments stream.
	 */
	public void publishDeployment(PipelineEvent event) {
		try {
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put("pipeline", nullSafe(event.pipeline()));
			fields.put("runName", nullSafe(event.runName()));
			fields.put("status", nullSafe(event.status()));
			fields.put("gitRevision", nullSafe(event.gitRevision()));
			fields.put("gitUrl", nullSafe(event.gitUrl()));
			fields.put("imageTag", nullSafe(event.imageTag()));
			fields.put("environment", nullSafe(event.environment()));
			fields.put("duration", nullSafe(event.duration()));
			fields.put("timestamp", Instant.now().toString());

			streamCommands.xadd(DEPLOY_STREAM,
					new XAddArgs().maxlen(MAX_STREAM_LENGTH),
					fields);
			LOG.infof(
					"Published deployment event: pipeline=%s status=%s",
					event.pipeline(), event.status());
		} catch (Exception e) {
			LOG.errorf(e,
					"Failed to publish deployment event: %s",
					event.pipeline());
		}
	}

	/**
	 * The stream fields of an alert event: the slack-bot's {@code AlertEvent}.
	 *
	 * <p>
	 * {@code pod} and {@code deployment} are optional there, so they are written
	 * only when the alert carries the label. The slack-bot posts an alert, with its
	 * Restart Pod button, only when the event names a pod, so {@code pod} is
	 * written only for {@link #SUBJECT_POD_ALERTS}.
	 */
	private static Map<String, String> alertFields(StoredAlert alert, String status) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("fingerprint", alert.fingerprint());
		fields.put("status", status);
		fields.put("alertName", alert.name());
		fields.put("severity", nullSafe(alert.severity()));
		fields.put("namespace", nullSafe(alert.namespace()));
		if (alert.name() != null && SUBJECT_POD_ALERTS.contains(alert.name())) {
			putLabelIfPresent(fields, alert.labels(), "pod");
		}
		putLabelIfPresent(fields, alert.labels(), "deployment");
		fields.put("summary", nullSafe(alert.summary()));
		fields.put("timestamp", Instant.now().toString());
		return fields;
	}

	private static void putLabelIfPresent(Map<String, String> fields,
			Map<String, String> labels, String name) {
		String value = labels != null ? labels.get(name) : null;
		if (value != null && !value.isBlank()) {
			fields.put(name, value);
		}
	}

	private static String nullSafe(String value) {
		return value != null ? value : "";
	}
}
