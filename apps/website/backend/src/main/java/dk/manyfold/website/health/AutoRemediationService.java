package dk.manyfold.website.health;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.scheduler.Scheduled;

import org.jboss.logging.Logger;

/**
 * Automated remediation trigger.
 *
 * Evaluates active alerts every 60 seconds and triggers safe remediations for
 * known issue patterns. Respects RemediationService cooldown and max-attempt
 * guards.
 *
 * Supported alert-to-action mappings: - KubePodCrashLooping → pod restart
 * (delete) - KubePodNotReady → pod restart (delete) -
 * KubeDeploymentReplicasMismatch → deployment rollout restart
 */
@ApplicationScoped
public class AutoRemediationService {

	private static final Logger LOG = Logger.getLogger(AutoRemediationService.class);

	/**
	 * Alert names that trigger a pod restart: alerts whose {@code pod} label names
	 * the pod the alert is about, and for which restarting it is the remedy.
	 *
	 * <p>
	 * This service restarts these alerts' pods without approval. The slack-bot's
	 * Restart Pod button, which goes through approval, is offered for a wider set:
	 * {@link RedisEventPublisher#SUBJECT_POD_ALERTS} includes this one.
	 */
	static final Set<String> POD_RESTART_ALERTS = Set.of(
			"KubePodCrashLooping",
			"KubePodNotReady");

	/** Alert names that trigger a deployment rollout restart. */
	private static final Set<String> DEPLOYMENT_RESTART_ALERTS = Set.of(
			"KubeDeploymentReplicasMismatch");

	@Inject
	AlertStoreService alertStoreService;

	@Inject
	RemediationService remediationService;

	@Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void scheduledEvaluate() {
		int actions = evaluate();
		if (actions > 0) {
			LOG.infof("Auto-remediation cycle complete: %d actions taken", actions);
		}
	}

	/**
	 * Evaluate active alerts and trigger remediations.
	 *
	 * @return number of remediation actions attempted
	 */
	public int evaluate() {
		var alerts = alertStoreService.getActiveAlerts();
		int actionCount = 0;

		for (var alert : alerts) {
			var labels = alert.labels();
			String alertName = alert.name();

			if (POD_RESTART_ALERTS.contains(alertName)) {
				String namespace = labels.getOrDefault("namespace", "");
				String pod = labels.getOrDefault("pod", "");
				if (!namespace.isEmpty() && !pod.isEmpty()) {
					LOG.infof("Auto-remediation: restarting pod %s/%s for alert %s",
							namespace, pod, alertName);
					var result = remediationService.restartPod(namespace, pod);
					if (result.success()) {
						actionCount++;
					} else {
						LOG.warnf("Auto-remediation failed: %s", result.message());
					}
				}
			} else if (DEPLOYMENT_RESTART_ALERTS.contains(alertName)) {
				String namespace = labels.getOrDefault("namespace", "");
				String deployment = labels.getOrDefault("deployment", "");
				if (!namespace.isEmpty() && !deployment.isEmpty()) {
					LOG.infof("Auto-remediation: restarting deployment %s/%s for alert %s",
							namespace, deployment, alertName);
					var result = remediationService.rolloutRestartDeployment(namespace, deployment);
					if (result.success()) {
						actionCount++;
					} else {
						LOG.warnf("Auto-remediation failed: %s", result.message());
					}
				}
			}
		}

		return actionCount;
	}
}
