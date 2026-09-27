package dk.manyfold.website.health;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.jboss.logging.Logger;

/** Service for automated remediation of known platform issues. */
@ApplicationScoped
public class RemediationService {

	private static final Logger LOG = Logger.getLogger(RemediationService.class);

	/**
	 * Minimum time between remediation attempts for the same resource (5 minutes).
	 */
	private static final long COOLDOWN_SECONDS = 300;

	/** Maximum number of remediation attempts per resource before backing off. */
	private static final int MAX_ATTEMPTS = 3;

	/**
	 * Tracks remediation attempts: key = "namespace/podName", value = list of
	 * attempt times. An attempt is reserved under the map's per-key lock before the
	 * action runs, so two concurrent requests cannot both pass the cooldown; the
	 * lists are copy-on-write so readers never see one mid-update.
	 */
	private final Map<String, List<Instant>> remediationHistory = new ConcurrentHashMap<>();

	private final KubernetesClient kubernetesClient;

	/** Result of a remediation action. */
	public record RemediationResult(
			boolean success,
			String action,
			String resource,
			String message) {
	}

	/** A recorded remediation action. */
	public record RemediationRecord(
			Instant timestamp,
			String action,
			String namespace,
			String resource,
			boolean success,
			String message) {
	}

	@Inject
	public RemediationService(KubernetesClient kubernetesClient) {
		this.kubernetesClient = kubernetesClient;
	}

	/**
	 * Attempts to restart a pod by deleting it (letting the controller recreate
	 * it).
	 *
	 * @param namespace
	 *            the pod namespace
	 * @param podName
	 *            the pod name
	 * @return the result of the remediation attempt
	 */
	public RemediationResult restartPod(String namespace, String podName) {
		String resourceKey = namespace + "/" + podName;

		Optional<Instant> attempt = reserveAttempt(resourceKey);
		if (attempt.isEmpty()) {
			String msg = "Pod restart blocked by cooldown or max attempts reached";
			LOG.infof("Remediation blocked for %s: %s", resourceKey, msg);
			return new RemediationResult(false, "restart_pod", resourceKey, msg);
		}

		try {
			// Delete the pod - the deployment/replicaset controller will recreate it
			boolean deleted = !kubernetesClient.pods()
					.inNamespace(namespace)
					.withName(podName)
					.delete()
					.isEmpty();

			if (deleted) {
				String msg = "Pod deleted successfully, controller will recreate it";
				LOG.infof("Remediation successful for %s: %s", resourceKey, msg);
				return new RemediationResult(true, "restart_pod", resourceKey, msg);
			} else {
				releaseAttempt(resourceKey, attempt.get());
				String msg = "Pod not found or already deleted";
				LOG.warnf("Remediation failed for %s: %s", resourceKey, msg);
				return new RemediationResult(false, "restart_pod", resourceKey, msg);
			}
		} catch (Exception e) {
			releaseAttempt(resourceKey, attempt.get());
			String msg = "Failed to delete pod: " + e.getMessage();
			LOG.errorf(e, "Remediation error for %s", resourceKey);
			return new RemediationResult(false, "restart_pod", resourceKey, msg);
		}
	}

	/**
	 * Scales a deployment to trigger a rolling restart.
	 *
	 * @param namespace
	 *            the deployment namespace
	 * @param deploymentName
	 *            the deployment name
	 * @return the result of the remediation attempt
	 */
	public RemediationResult rolloutRestartDeployment(String namespace, String deploymentName) {
		String resourceKey = namespace + "/deployment/" + deploymentName;

		Optional<Instant> attempt = reserveAttempt(resourceKey);
		if (attempt.isEmpty()) {
			String msg = "Deployment restart blocked by cooldown or max attempts";
			LOG.infof("Remediation blocked for %s: %s", resourceKey, msg);
			return new RemediationResult(false, "rollout_restart", resourceKey, msg);
		}

		try {
			// Trigger a rollout restart by patching the deployment
			kubernetesClient.apps().deployments()
					.inNamespace(namespace)
					.withName(deploymentName)
					.rolling()
					.restart();

			String msg = "Rollout restart initiated";
			LOG.infof("Remediation successful for %s: %s", resourceKey, msg);
			return new RemediationResult(true, "rollout_restart", resourceKey, msg);
		} catch (Exception e) {
			releaseAttempt(resourceKey, attempt.get());
			String msg = "Failed to restart deployment: " + e.getMessage();
			LOG.errorf(e, "Remediation error for %s", resourceKey);
			return new RemediationResult(false, "rollout_restart", resourceKey, msg);
		}
	}

	/**
	 * Returns remediation history for a specific resource.
	 *
	 * @param namespace
	 *            the resource namespace
	 * @param resource
	 *            the resource name
	 * @return list of attempt timestamps
	 */
	public List<Instant> getRemediationHistory(String namespace, String resource) {
		String key = namespace + "/" + resource;
		return Collections.unmodifiableList(
				remediationHistory.getOrDefault(key, Collections.emptyList()));
	}

	/**
	 * Clears remediation history (useful for testing or manual reset).
	 */
	public void clearHistory() {
		remediationHistory.clear();
		LOG.info("Remediation history cleared");
	}

	/**
	 * Returns the count of recent remediation attempts across all resources.
	 *
	 * @return count of remediations in the last hour
	 */
	public int getRecentRemediationCount() {
		Instant oneHourAgo = Instant.now().minusSeconds(3600);
		return (int) remediationHistory.values().stream()
				.flatMap(List::stream)
				.filter(instant -> instant.isAfter(oneHourAgo))
				.count();
	}

	/**
	 * Reserves an attempt for the resource if the cooldown and the hourly limit
	 * allow one. The check and the reservation are one step under the map's per-key
	 * lock.
	 *
	 * @return the reserved attempt's time, or empty when blocked
	 */
	Optional<Instant> reserveAttempt(String resourceKey) {
		Instant now = Instant.now();
		Instant[] reserved = new Instant[1];
		remediationHistory.compute(resourceKey, (key, attempts) -> {
			List<Instant> list = attempts != null ? attempts : new CopyOnWriteArrayList<>();
			if (canRemediate(list, now)) {
				list.add(now);
				reserved[0] = now;
			}
			return list;
		});
		return Optional.ofNullable(reserved[0]);
	}

	/**
	 * Returns a reserved attempt whose action did not happen, so a failed or missed
	 * restart does not hold the cooldown.
	 */
	void releaseAttempt(String resourceKey, Instant attempt) {
		remediationHistory.computeIfPresent(resourceKey, (key, attempts) -> {
			attempts.remove(attempt);
			return attempts;
		});
	}

	private static boolean canRemediate(List<Instant> attempts, Instant now) {
		if (attempts.isEmpty()) {
			return true;
		}

		// Check if max attempts exceeded
		Instant oneHourAgo = now.minusSeconds(3600);
		long recentAttempts = attempts.stream()
				.filter(instant -> instant.isAfter(oneHourAgo))
				.count();
		if (recentAttempts >= MAX_ATTEMPTS) {
			return false;
		}

		// Check cooldown
		Instant lastAttempt = attempts.get(attempts.size() - 1);
		return lastAttempt.plusSeconds(COOLDOWN_SECONDS).isBefore(now);
	}
}
