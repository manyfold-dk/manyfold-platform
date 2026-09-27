package dk.manyfold.website.health;

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

	@Inject
	public RedisEventPublisher(RedisDataSource ds) {
		this.streamCommands = ds.stream(String.class, String.class, String.class);
	}

	/**
	 * Publish an alert event (firing or resolved) to the platform:alerts stream.
	 */
	public void publishAlert(StoredAlert alert, String status) {
		try {
			streamCommands.xadd(ALERT_STREAM,
					new XAddArgs().maxlen(MAX_STREAM_LENGTH),
					alertFields(alert, status));
			LOG.infof("Published alert event: %s status=%s",
					alert.name(), status);
		} catch (Exception e) {
			LOG.errorf(e,
					"Failed to publish alert event: %s",
					alert.name());
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
