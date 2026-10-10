package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.Alert;
import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.WebhookPayload;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The application's auto-remediation ignores this replica's webhook store
 * (issue #529). A replica that never received an alert's resolution keeps it in
 * the store; auto-remediation must act only on what Alertmanager reports, and
 * on nothing when Alertmanager gives no answer.
 */
@QuarkusTest
class AutoRemediationStaleEntryTest {

	private static final String POD = "website-backend-abc123";

	@Inject
	AutoRemediationService autoRemediation;

	@Inject
	AlertStoreService store;

	@Inject
	MockAlertmanagerClient alertmanager;

	private final RecordingRemediationService remediation = new RecordingRemediationService();

	@BeforeEach
	void setUp() {
		store.clearAll();
		QuarkusMock.installMockForType(remediation, RemediationService.class);
		QuarkusMock.installMockForType(new HeldLease(), RemediationWorkerLease.class);
		store.processWebhook(new WebhookPayload(
				"4", "group1", 0, "firing", "backend-webhook",
				Map.of(), Map.of(), Map.of(), "", List.of(crashLooping())));
	}

	@Test
	void aStaleEntryAlertmanagerNoLongerReportsIsNotRemediated() {
		alertmanager.answer(List.of());

		assertThat(store.getActiveAlerts())
				.extracting(StoredAlert::name)
				.containsExactly("KubePodCrashLooping");
		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void withoutAnAnswerFromAlertmanagerTheStoreIsNotReadEither() {
		alertmanager.fail();

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void anAlertAlertmanagerReportsIsRemediated() {
		alertmanager.answer(List.of(AlertStoreService.toStored(crashLooping(), Instant.now())));

		assertThat(autoRemediation.evaluate()).isEqualTo(1);
		assertThat(remediation.restarts()).containsExactly("pod website/" + POD);
	}

	/** This replica is the one that acts; there is no Redis in the tests. */
	private static final class HeldLease extends RemediationWorkerLease {

		HeldLease() {
			super(null, "test");
		}

		@Override
		public boolean acquire() {
			return true;
		}
	}

	private static Alert crashLooping() {
		return new Alert(
				"firing",
				Map.of("alertname", "KubePodCrashLooping",
						"namespace", "website",
						"pod", POD,
						"severity", "warning"),
				Map.of("summary", "Pod is crash looping"),
				"2026-09-27T10:00:00Z", "", "", "fp1");
	}
}
