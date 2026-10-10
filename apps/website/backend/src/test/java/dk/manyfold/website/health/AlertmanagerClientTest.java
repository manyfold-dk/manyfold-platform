package dk.manyfold.website.health;

import static dk.manyfold.website.health.StandInAlertmanager.alert;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import dk.manyfold.website.api.v1.model.AlertmanagerWebhook.Alert;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;
import dk.manyfold.website.health.StandInAlertmanager.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The client against a stand-in Alertmanager: which alerts it asks for, how an
 * alert becomes a {@link StoredAlert}, and what counts as no answer.
 */
class AlertmanagerClientTest {

	private static final String POD = "website-7d9f8b6c5-x2x4q";

	private static final Map<String, String> CRASH_LOOPING = Map.of(
			"alertname", "KubePodCrashLooping",
			"severity", "warning",
			"namespace", "website",
			"pod", POD,
			"container", "backend",
			"job", "kube-state-metrics");

	private StandInAlertmanager alertmanager;

	@BeforeEach
	void startAlertmanager() throws IOException {
		alertmanager = new StandInAlertmanager();
	}

	@AfterEach
	void stopAlertmanager() {
		alertmanager.close();
	}

	/** Firing, not silenced, not inhibited, routed to the webhook's receiver. */
	@Test
	void asksForTheAlertsTheWebhookWouldBeSent() throws AlertmanagerQueryException {
		alertmanager.answerAlerts();
		alertmanager.client().getActiveAlerts();

		assertThat(alertmanager.lastRequest().getPath()).isEqualTo("/api/v2/alerts");
		String query = URLDecoder.decode(alertmanager.lastRequest().getRawQuery(), StandardCharsets.UTF_8);
		assertThat(query.split("&"))
				.containsExactlyInAnyOrder("active=true", "silenced=false", "inhibited=false",
						"unprocessed=false", "receiver=backend-webhook");
	}

	/**
	 * An alert from the API becomes the same StoredAlert the webhook store makes of
	 * the same alert: same fingerprint, names, texts and start, and the labels
	 * whole, the pod label included. Whether a pod label may be acted on is decided
	 * where a pod is acted on, not here.
	 */
	@Test
	void anAlertBecomesWhatTheWebhookStoreMakesOfIt() throws AlertmanagerQueryException {
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING));

		List<StoredAlert> alerts = alertmanager.client().getActiveAlerts();

		Instant updatedAt = Instant.parse(StandInAlertmanager.UPDATED_AT);
		StoredAlert fromWebhook = AlertStoreService.toStored(new Alert(
				"firing", CRASH_LOOPING,
				Map.of("summary", "KubePodCrashLooping summary",
						"description", "KubePodCrashLooping description"),
				StandInAlertmanager.STARTS_AT, "", "", "3f1c9a"), updatedAt);
		assertThat(alerts).containsExactly(fromWebhook);
		assertThat(alerts.get(0)).satisfies(alert -> {
			assertThat(alert.fingerprint()).isEqualTo("3f1c9a");
			assertThat(alert.name()).isEqualTo("KubePodCrashLooping");
			assertThat(alert.severity()).isEqualTo("warning");
			assertThat(alert.namespace()).isEqualTo("website");
			assertThat(alert.summary()).isEqualTo("KubePodCrashLooping summary");
			assertThat(alert.status()).isEqualTo("firing");
			assertThat(alert.startsAt()).isEqualTo(Instant.parse(StandInAlertmanager.STARTS_AT));
			assertThat(alert.receivedAt()).isEqualTo(updatedAt);
			assertThat(alert.labels()).containsEntry("pod", POD).isEqualTo(CRASH_LOOPING);
		});
	}

	/** Missing labels read as the webhook store reads them. */
	@Test
	void anAlertWithoutSeverityOrNamespaceReadsAsTheWebhookStoreReadsIt() throws AlertmanagerQueryException {
		alertmanager.answerAlerts(alert("77aa", Map.of("alertname", "TargetDown")));

		assertThat(alertmanager.client().getActiveAlerts()).singleElement().satisfies(alert -> {
			assertThat(alert.severity()).isEqualTo("warning");
			assertThat(alert.namespace()).isEmpty();
		});
	}

	/** The heartbeat is routed to the webhook but is not an alert. */
	@Test
	void leavesOutTheWatchdog() throws AlertmanagerQueryException {
		alertmanager.answerAlerts(
				alert("24cbc8", Map.of("alertname", "Watchdog", "severity", "none")),
				alert("3f1c9a", CRASH_LOOPING));

		assertThat(alertmanager.client().getActiveAlerts())
				.extracting(StoredAlert::name)
				.containsExactly("KubePodCrashLooping");
	}

	/**
	 * Alertmanager filters these out itself; an answer that still carries one (a
	 * version or a proxy that dropped the filters) must not pass it through.
	 */
	@Test
	void leavesOutSilencedInhibitedAndMutedAlertsEvenWhenTheAnswerCarriesThem() throws AlertmanagerQueryException {
		Status muted = new Status("suppressed", List.of(), List.of(), List.of("weekends"));
		alertmanager.answerAlerts(
				alert("s1", CRASH_LOOPING, Status.SILENCED, List.of(StandInAlertmanager.RECEIVER)),
				alert("i1", CRASH_LOOPING, Status.INHIBITED, List.of(StandInAlertmanager.RECEIVER)),
				alert("m1", CRASH_LOOPING, muted, List.of(StandInAlertmanager.RECEIVER)),
				alert("u1", CRASH_LOOPING, new Status("unprocessed", List.of(), List.of(), List.of()),
						List.of(StandInAlertmanager.RECEIVER)));

		assertThat(alertmanager.client().getActiveAlerts()).isEmpty();
	}

	/** An alert the webhook is never sent is not one of its alerts. */
	@Test
	void leavesOutAnAlertNotRoutedToTheWebhook() throws AlertmanagerQueryException {
		alertmanager.answerAlerts(
				alert("c1", Map.of("alertname", "CPUThrottlingHigh", "severity", "info"), Status.ACTIVE,
						List.of("default-receiver")),
				alert("3f1c9a", CRASH_LOOPING, Status.ACTIVE,
						List.of("slack", StandInAlertmanager.RECEIVER)));

		assertThat(alertmanager.client().getActiveAlerts())
				.extracting(StoredAlert::fingerprint)
				.containsExactly("3f1c9a");
	}

	@Test
	void nothingFiringIsAnEmptyAnswerNotAFailure() throws AlertmanagerQueryException {
		alertmanager.answerAlerts();
		assertThat(alertmanager.client().getActiveAlerts()).isEmpty();
	}

	@Test
	void anErrorStatusIsNoAnswer() {
		alertmanager.answer(503, "{\"code\":503,\"message\":\"unavailable\"}");
		assertThatThrownBy(() -> alertmanager.client().getActiveAlerts())
				.isInstanceOf(AlertmanagerQueryException.class)
				.hasMessageContaining("503");
	}

	/** A wrong path prefix lands on the UI, which answers 200 with HTML. */
	@Test
	void aBodyThatIsNotJsonIsNoAnswer() {
		alertmanager.answer(200, "<!DOCTYPE html><html><title>Alertmanager</title></html>");
		assertThatThrownBy(() -> alertmanager.client().getActiveAlerts())
				.isInstanceOf(AlertmanagerQueryException.class);
	}

	@Test
	void jsonThatIsNotAListOfAlertsIsNoAnswer() {
		alertmanager.answer(200, "{\"status\":\"success\",\"data\":[]}");
		assertThatThrownBy(() -> alertmanager.client().getActiveAlerts())
				.isInstanceOf(AlertmanagerQueryException.class);

		alertmanager.answer(200, "");
		assertThatThrownBy(() -> alertmanager.client().getActiveAlerts())
				.isInstanceOf(AlertmanagerQueryException.class);
	}

	/** An answer that cannot be read whole is not used in part. */
	@Test
	void anAnswerWithAnUnreadableAlertIsNoAnswer() {
		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING), "{\"fingerprint\":\"x\",\"status\":{}}");
		assertThatThrownBy(() -> alertmanager.client().getActiveAlerts())
				.isInstanceOf(AlertmanagerQueryException.class);

		alertmanager.answerAlerts(alert("3f1c9a", CRASH_LOOPING), "\"KubePodCrashLooping\"");
		assertThatThrownBy(() -> alertmanager.client().getActiveAlerts())
				.isInstanceOf(AlertmanagerQueryException.class);
	}

	@Test
	void anUnreachableAlertmanagerIsNoAnswer() {
		AlertmanagerClient client = alertmanager.client();
		alertmanager.close();
		assertThatThrownBy(client::getActiveAlerts).isInstanceOf(AlertmanagerQueryException.class);
	}
}
