package dk.manyfold.website.health;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the restarts asked for instead of making them. */
class RecordingRemediationService extends RemediationService {

	private final List<String> restarts = new CopyOnWriteArrayList<>();

	/** Runs after each recorded restart: what happens while one is under way. */
	private volatile Runnable afterEachRestart = () -> {
	};

	RecordingRemediationService() {
		super(null);
	}

	@Override
	public RemediationResult restartPod(String namespace, String podName) {
		restarts.add("pod " + namespace + "/" + podName);
		afterEachRestart.run();
		return new RemediationResult(true, "restart_pod", namespace + "/" + podName, "recorded");
	}

	@Override
	public RemediationResult rolloutRestartDeployment(String namespace, String deploymentName) {
		restarts.add("deployment " + namespace + "/" + deploymentName);
		afterEachRestart.run();
		return new RemediationResult(true, "rollout_restart", namespace + "/deployment/" + deploymentName,
				"recorded");
	}

	void afterEachRestart(Runnable action) {
		afterEachRestart = action;
	}

	/** Every restart asked for, in order: "pod ns/name" or "deployment ns/name". */
	List<String> restarts() {
		return List.copyOf(restarts);
	}
}
