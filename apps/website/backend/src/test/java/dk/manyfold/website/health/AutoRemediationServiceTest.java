package dk.manyfold.website.health;

import static dk.manyfold.website.health.StandInAlertmanager.alert;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dk.manyfold.website.health.StandInAlertmanager.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What auto-remediation restarts for the alerts Alertmanager reports, that it
 * restarts nothing when Alertmanager gives no usable answer (issue #529), and
 * that of two replicas reading the same alerts only the lease holder acts. The
 * alerts go through the real client, so its filters are part of what is tested.
 */
class AutoRemediationServiceTest {

	private static final String POD = "website-7d9f8b6c5-x2x4q";

	private static final String KSM_POD = "observability-kube-state-metrics-86448cbf45-dhdk2";

	private static final Map<String, String> CRASH_LOOPING = Map.of(
			"alertname", "KubePodCrashLooping",
			"severity", "warning",
			"namespace", "website",
			"pod", POD);

	private StandInAlertmanager alertmanager;

	/** The Redis both replicas share. */
	private final StandInKeys redis = new StandInKeys();

	private final RecordingRemediationService remediation = new RecordingRemediationService();

	private AutoRemediationService autoRemediation;

	@BeforeEach
	void setUp() throws IOException {
		alertmanager = new StandInAlertmanager();
		autoRemediation = replica("backend-a/1", remediation);
	}

	/** A backend replica on the shared Alertmanager and Redis. */
	private AutoRemediationService replica(String name, RemediationService remediationService) {
		AutoRemediationService replica = new AutoRemediationService();
		replica.alertmanager = alertmanager.client();
		replica.remediationService = remediationService;
		replica.workerLease = new RemediationWorkerLease(redis.dataSource(), name);
		return replica;
	}

	@AfterEach
	void stopAlertmanager() {
		alertmanager.close();
	}

	@Test
	void nothingFiringRestartsNothing() {
		alertmanager.answerAlerts();

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void aCrashLoopingPodAlertmanagerReportsIsRestarted() {
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING));

		assertThat(autoRemediation.evaluate()).isEqualTo(1);
		assertThat(remediation.restarts()).containsExactly("pod website/" + POD);
	}

	/**
	 * A deployment alert carries kube-state-metrics' pod, not the deployment's
	 * (#513): the deployment is restarted, the exporter is left alone.
	 */
	@Test
	void aReplicasMismatchRestartsItsDeploymentNotTheExportersPod() {
		alertmanager.answerAlerts(alert("9d2e", Map.of(
				"alertname", "KubeDeploymentReplicasMismatch",
				"severity", "warning",
				"namespace", "website",
				"deployment", "website-backend",
				"pod", KSM_POD)));

		assertThat(autoRemediation.evaluate()).isEqualTo(1);
		assertThat(remediation.restarts()).containsExactly("deployment website/website-backend");
	}

	/** A pod label outside the pod-restart alerts names no pod to restart. */
	@Test
	void anAlertCarryingTheExportersPodRestartsNothing() {
		alertmanager.answerAlerts(alert("5b7c", Map.of(
				"alertname", "KubeJobFailed",
				"severity", "warning",
				"namespace", "synthetic-monitoring",
				"job_name", "smoke-29311260",
				"pod", KSM_POD)));

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	/**
	 * The webhook is never sent a silenced or inhibited alert, so auto-remediation
	 * never acted on one; reading Alertmanager must not start now, even when the
	 * answer carries it.
	 */
	@Test
	void aSilencedOrInhibitedAlertIsNotRemediated() {
		alertmanager.answerAlerts(
				alert("s1", CRASH_LOOPING, Status.SILENCED, List.of(StandInAlertmanager.RECEIVER)),
				alert("i1", CRASH_LOOPING, Status.INHIBITED, List.of(StandInAlertmanager.RECEIVER)));

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void anUnreachableAlertmanagerRestartsNothing() {
		alertmanager.close();

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void anErrorFromAlertmanagerRestartsNothing() {
		alertmanager.answer(500, "internal error");

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	/** An answer that cannot be read whole is not acted on in part. */
	@Test
	void anAnswerWithAnUnreadableAlertRestartsNothing() {
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING), "{\"labels\":\"KubePodCrashLooping\"}");

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	/**
	 * Both replicas read the same alerts; each keeps its cooldown in its own
	 * memory, so only the lease holder may act on them.
	 */
	@Test
	void ofTwoReplicasOnlyTheLeaseHolderRestarts() {
		RecordingRemediationService otherRemediation = new RecordingRemediationService();
		AutoRemediationService other = replica("backend-b/2", otherRemediation);
		alertmanager.answerAlerts(alert("9d2e", Map.of(
				"alertname", "KubeDeploymentReplicasMismatch",
				"severity", "warning",
				"namespace", "website",
				"deployment", "website-backend")));

		assertThat(autoRemediation.evaluate()).isEqualTo(1);
		assertThat(other.evaluate()).isZero();
		redis.advance(Duration.ofSeconds(60));
		assertThat(other.evaluate()).isZero();

		assertThat(remediation.restarts()).containsExactly("deployment website/website-backend");
		assertThat(otherRemediation.restarts()).isEmpty();
	}

	/** A replica that stops is replaced by the other once its lease expires. */
	@Test
	void theOtherReplicaActsOnceTheHoldersLeaseExpires() {
		RecordingRemediationService otherRemediation = new RecordingRemediationService();
		AutoRemediationService other = replica("backend-b/2", otherRemediation);
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING));

		assertThat(autoRemediation.evaluate()).isEqualTo(1);
		redis.advance(RemediationWorkerLease.TTL);

		assertThat(other.evaluate()).isEqualTo(1);
		assertThat(otherRemediation.restarts()).containsExactly("pod website/" + POD);
	}

	/**
	 * A cycle that outlasts its lease stops acting: the other replica may have
	 * taken it over and act on the same alerts.
	 */
	@Test
	void aCycleThatLosesTheLeaseStopsActing() {
		RemediationWorkerLease otherLease = new RemediationWorkerLease(redis.dataSource(), "backend-b/2");
		remediation.afterEachRestart(() -> {
			redis.advance(RemediationWorkerLease.TTL);
			otherLease.acquire();
		});
		alertmanager.answerAlerts(
				alert("3f1c9a", CRASH_LOOPING),
				alert("9d2e", Map.of(
						"alertname", "KubeDeploymentReplicasMismatch",
						"severity", "warning",
						"namespace", "website",
						"deployment", "website-backend")));

		assertThat(autoRemediation.evaluate()).isEqualTo(1);
		assertThat(remediation.restarts()).hasSize(1);
	}

	/** A replica without the lease does not even ask Alertmanager. */
	@Test
	void aReplicaWithoutTheLeaseRestartsNothing() {
		RecordingRemediationService otherRemediation = new RecordingRemediationService();
		AutoRemediationService other = replica("backend-b/2", otherRemediation);
		new RemediationWorkerLease(redis.dataSource(), "backend-a/1").acquire();
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING));

		assertThat(other.evaluate()).isZero();
		assertThat(otherRemediation.restarts()).isEmpty();
		assertThat(alertmanager.lastRequest()).isNull();
	}

	/** Without Redis no replica can know the other is not acting. */
	@Test
	void withoutRedisNoReplicaRestarts() {
		redis.goDown();
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING));

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void aPodRestartAlertWithoutAPodLabelRestartsNothing() {
		alertmanager.answerAlerts(alert("fp4", Map.of(
				"alertname", "KubePodCrashLooping",
				"severity", "warning",
				"namespace", "website")));

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}

	@Test
	void anAlertWithNoRemediationRestartsNothing() {
		alertmanager.answerAlerts(alert("fp2", Map.of(
				"alertname", "SomeRandomAlert",
				"severity", "critical",
				"namespace", "default",
				"pod", POD)));

		assertThat(autoRemediation.evaluate()).isZero();
		assertThat(remediation.restarts()).isEmpty();
	}
}
