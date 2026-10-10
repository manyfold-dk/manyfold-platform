package dk.manyfold.website.health;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.scheduler.Scheduled;

import dk.manyfold.website.health.AlertStoreService.StoredAlert;
import dk.manyfold.website.health.RemediationService.RemediationResult;

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
 *
 * <p>
 * The active alerts come from Alertmanager, which both replicas share, and
 * never from this replica's webhook store: a replica that missed an alert's
 * resolution would keep restarting for an alert that is over (issue #529). When
 * Alertmanager gives no answer, the cycle acts on nothing.
 *
 * <p>
 * Only the replica that holds the {@link RemediationWorkerLease} acts, so the
 * cooldown and attempt limit, which each replica keeps in memory, hold as they
 * would for a single replica.
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
	AlertmanagerClient alertmanager;

	@Inject
	RemediationService remediationService;

	@Inject
	RemediationWorkerLease workerLease;

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
	 * <p>
	 * Fails closed: a replica that does not hold the worker lease, or cannot tell,
	 * acts on nothing, and the lease is checked again before every restart; without
	 * an answer from Alertmanager nothing is restarted in this cycle. The next
	 * cycle asks again.
	 *
	 * @return number of remediation actions that succeeded
	 */
	public int evaluate() {
		if (!workerLease.acquire()) {
			return 0;
		}
		List<StoredAlert> alerts;
		try {
			alerts = alertmanager.getActiveAlerts();
		} catch (AlertmanagerQueryException e) {
			LOG.warnf("Auto-remediation skipped this cycle, Alertmanager gave no answer: %s",
					e.getMessage());
			return 0;
		}
		int actionCount = 0;

		for (var alert : alerts) {
			Optional<Supplier<RemediationResult>> action = actionFor(alert);
			if (action.isEmpty()) {
				continue;
			}
			// Renewed before every restart: a cycle that outlasts the lease (a slow
			// Kubernetes API) must stop once the other replica may have taken it over.
			if (!workerLease.acquire()) {
				LOG.warn("Auto-remediation stopped mid-cycle: this replica no longer holds the lease");
				break;
			}
			RemediationResult result = action.get().get();
			if (result.success()) {
				actionCount++;
			} else {
				LOG.warnf("Auto-remediation failed: %s", result.message());
			}
		}

		return actionCount;
	}

	/** The restart an alert calls for, or empty when it calls for none. */
	private Optional<Supplier<RemediationResult>> actionFor(StoredAlert alert) {
		var labels = alert.labels();
		String alertName = alert.name();
		String namespace = labels.getOrDefault("namespace", "");

		if (POD_RESTART_ALERTS.contains(alertName)) {
			String pod = labels.getOrDefault("pod", "");
			if (namespace.isEmpty() || pod.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(() -> {
				LOG.infof("Auto-remediation: restarting pod %s/%s for alert %s",
						namespace, pod, alertName);
				return remediationService.restartPod(namespace, pod);
			});
		}
		if (DEPLOYMENT_RESTART_ALERTS.contains(alertName)) {
			String deployment = labels.getOrDefault("deployment", "");
			if (namespace.isEmpty() || deployment.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(() -> {
				LOG.infof("Auto-remediation: restarting deployment %s/%s for alert %s",
						namespace, deployment, alertName);
				return remediationService.rolloutRestartDeployment(namespace, deployment);
			});
		}
		return Optional.empty();
	}
}
