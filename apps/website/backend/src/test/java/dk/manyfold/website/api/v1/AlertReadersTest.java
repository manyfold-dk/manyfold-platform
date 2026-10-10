package dk.manyfold.website.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import dk.manyfold.website.api.v1.AlertWebhookResource.AlertSummary;
import dk.manyfold.website.health.AlertStoreService;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;
import dk.manyfold.website.health.AlertmanagerClient;
import dk.manyfold.website.health.AlertmanagerQueryException;

import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.Test;

/**
 * The alert list, the critical list and the summary read one source, so the
 * platform page's header and list agree: Alertmanager, which both replicas
 * share. This replica's webhook store answers only when Alertmanager gives no
 * answer, and the answer says so (issue #529).
 */
class AlertReadersTest {

	private static final StoredAlert DISK_FULL = alert("a1", "NodeFilesystemAlmostOutOfSpace", "critical");

	private static final StoredAlert CRASH_LOOPING = alert("b2", "KubePodCrashLooping", "warning");

	private static final StoredAlert JOB_FAILED = alert("c3", "KubeJobFailed", "warning");

	/** An alert whose resolution reached the other replica. */
	private static final StoredAlert STALE = alert("x9", "BackendPodNotReady", "critical");

	private final AlertWebhookResource resource = new AlertWebhookResource();

	@Test
	void theListTheCriticalListAndTheSummaryAgreeOnAlertmanagersAlerts() {
		resource.alertmanager = alertmanagerAnswering(List.of(DISK_FULL, CRASH_LOOPING, JOB_FAILED));
		resource.alertStore = storeHolding(List.of(STALE, CRASH_LOOPING));

		assertAnswer(resource.getActiveAlerts(), List.of(DISK_FULL, CRASH_LOOPING, JOB_FAILED), "alertmanager");
		assertAnswer(resource.getCriticalAlerts(), List.of(DISK_FULL), "alertmanager");
		assertAnswer(resource.getAlertSummary(), new AlertSummary(3, 1, 2, 0), "alertmanager");
	}

	@Test
	void anEntryAlertmanagerNoLongerReportsIsNeitherListedNorCounted() {
		resource.alertmanager = alertmanagerAnswering(List.of());
		resource.alertStore = storeHolding(List.of(STALE));

		assertAnswer(resource.getActiveAlerts(), List.of(), "alertmanager");
		assertAnswer(resource.getCriticalAlerts(), List.of(), "alertmanager");
		assertAnswer(resource.getAlertSummary(), new AlertSummary(0, 0, 0, 0), "alertmanager");
	}

	/** For display, this replica's own view beats showing nothing; it is marked. */
	@Test
	void withoutAnAnswerFromAlertmanagerThisReplicasStoreAnswersAndSaysSo() {
		resource.alertmanager = alertmanagerFailing();
		resource.alertStore = storeHolding(List.of(STALE, CRASH_LOOPING));

		assertAnswer(resource.getActiveAlerts(), List.of(STALE, CRASH_LOOPING), "replica");
		assertAnswer(resource.getCriticalAlerts(), List.of(STALE), "replica");
		assertAnswer(resource.getAlertSummary(), new AlertSummary(2, 1, 1, 0), "replica");
	}

	private static void assertAnswer(RestResponse<?> response, Object entity, String source) {
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getEntity()).isEqualTo(entity);
		assertThat(response.getHeaderString(AlertWebhookResource.SOURCE_HEADER)).isEqualTo(source);
	}

	private static StoredAlert alert(String fingerprint, String name, String severity) {
		return new StoredAlert(fingerprint, name, severity, "website", name + " summary", "", "firing",
				Instant.parse("2026-09-27T10:00:00Z"), Instant.parse("2026-09-27T10:04:00Z"),
				Map.of("alertname", name, "severity", severity, "namespace", "website"));
	}

	private static AlertmanagerClient alertmanagerAnswering(List<StoredAlert> alerts) {
		return new AlertmanagerClient() {
			@Override
			public List<StoredAlert> getActiveAlerts() {
				return alerts;
			}
		};
	}

	private static AlertmanagerClient alertmanagerFailing() {
		return new AlertmanagerClient() {
			@Override
			public List<StoredAlert> getActiveAlerts() throws AlertmanagerQueryException {
				throw new AlertmanagerQueryException("Alertmanager did not answer");
			}
		};
	}

	private static AlertStoreService storeHolding(List<StoredAlert> alerts) {
		return new AlertStoreService() {
			@Override
			public List<StoredAlert> getActiveAlerts() {
				return alerts;
			}
		};
	}
}
