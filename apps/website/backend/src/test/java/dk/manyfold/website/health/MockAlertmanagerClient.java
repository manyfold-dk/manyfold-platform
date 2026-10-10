package dk.manyfold.website.health;

import java.util.List;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.test.Mock;

import dk.manyfold.website.health.AlertStoreService.StoredAlert;

/**
 * Stand-in Alertmanager for the application tests: answers the alerts a test
 * sets, or gives no answer. Answers with no alerts until a test says otherwise.
 */
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class MockAlertmanagerClient extends AlertmanagerClient {

	private volatile List<StoredAlert> alerts = List.of();

	private volatile boolean answering = true;

	/** Answer with these active alerts. */
	public void answer(List<StoredAlert> active) {
		alerts = List.copyOf(active);
		answering = true;
	}

	/** Give no answer, as an unreachable Alertmanager does. */
	public void fail() {
		answering = false;
	}

	@Override
	public List<StoredAlert> getActiveAlerts() throws AlertmanagerQueryException {
		if (!answering) {
			throw new AlertmanagerQueryException("Alertmanager did not answer (test)");
		}
		return alerts;
	}
}
