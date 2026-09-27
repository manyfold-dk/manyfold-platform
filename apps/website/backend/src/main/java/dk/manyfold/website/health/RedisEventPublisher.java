package dk.manyfold.website.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
			Map<String, String> fields = new LinkedHashMap<>();
			fields.put("fingerprint", alert.fingerprint());
			fields.put("status", status);
			fields.put("alertName", alert.name());
			fields.put("severity", nullSafe(alert.severity()));
			fields.put("namespace", nullSafe(alert.namespace()));
			fields.put("summary", nullSafe(alert.summary()));
			fields.put("timestamp", Instant.now().toString());

			streamCommands.xadd(ALERT_STREAM,
					new XAddArgs().maxlen(MAX_STREAM_LENGTH),
					fields);
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

	private static String nullSafe(String value) {
		return value != null ? value : "";
	}
}
