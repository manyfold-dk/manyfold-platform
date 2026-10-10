package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;

import dk.manyfold.website.health.AlertStoreService;
import dk.manyfold.website.health.AlertStoreService.StoredAlert;
import dk.manyfold.website.health.MockAlertmanagerClient;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AlertWebhookResourceTest {

	@Inject
	MockAlertmanagerClient alertmanager;

	@Inject
	AlertStoreService alertStore;

	@BeforeEach
	void setUp() {
		alertStore.clearAll();
		alertmanager.answer(List.of());
	}

	@Test
	void testWebhookReceivesAlert() {
		String payload = """
				{
				    "version": "4",
				    "groupKey": "test",
				    "truncatedAlerts": 0,
				    "status": "firing",
				    "receiver": "test",
				    "groupLabels": {},
				    "commonLabels": {},
				    "commonAnnotations": {},
				    "externalURL": "http://alertmanager",
				    "alerts": [
				        {
				            "status": "firing",
				            "labels": {"alertname": "TestAlert", "severity": "warning"},
				            "annotations": {"summary": "Test alert"},
				            "startsAt": "2024-01-01T00:00:00Z",
				            "endsAt": "0001-01-01T00:00:00Z",
				            "generatorURL": "",
				            "fingerprint": "abc123"
				        }
				    ]
				}
				""";

		given().contentType(ContentType.JSON)
				.header("Authorization", "Bearer test-webhook-token")
				.body(payload)
				.when()
				.post("/api/v1/alerts/webhook")
				.then()
				.statusCode(200)
				.body("status", is("received"))
				.body("alerts", is(1));
	}

	/**
	 * The watchdog traverses this endpoint every few minutes as a liveness
	 * heartbeat. It must be forwarded to the stream but never enter the alert
	 * store, or it would permanently inflate the alert list whenever the store
	 * answers for Alertmanager.
	 */
	@Test
	void testWatchdogIsNotStoredAsAnActiveAlert() {
		alertmanager.fail();
		String payload = """
				{
				    "version": "4",
				    "groupKey": "watchdog",
				    "truncatedAlerts": 0,
				    "status": "firing",
				    "receiver": "backend-webhook",
				    "groupLabels": {},
				    "commonLabels": {},
				    "commonAnnotations": {},
				    "externalURL": "http://alertmanager",
				    "alerts": [
				        {
				            "status": "firing",
				            "labels": {"alertname": "Watchdog", "severity": "none"},
				            "annotations": {"summary": "always firing"},
				            "startsAt": "2024-01-01T00:00:00Z",
				            "endsAt": "0001-01-01T00:00:00Z",
				            "generatorURL": "",
				            "fingerprint": "watchdog0000beef"
				        }
				    ]
				}
				""";

		given().contentType(ContentType.JSON)
				.header("Authorization", "Bearer test-webhook-token")
				.body(payload)
				.when()
				.post("/api/v1/alerts/webhook")
				.then()
				.statusCode(200);

		given().when()
				.get("/api/v1/alerts")
				.then()
				.statusCode(200)
				.header(AlertWebhookResource.SOURCE_HEADER, "replica")
				.body("findAll { it.name == 'Watchdog' }", hasSize(0));
	}

	/** The page's header (summary) and list read the same alerts. */
	@Test
	void theListTheCriticalListAndTheSummaryServeAlertmanagersAlerts() {
		alertmanager.answer(List.of(alert("a1", "NodeFilesystemAlmostOutOfSpace", "critical"),
				alert("b2", "KubePodCrashLooping", "warning")));

		given().when()
				.get("/api/v1/alerts")
				.then()
				.statusCode(200)
				.header(AlertWebhookResource.SOURCE_HEADER, "alertmanager")
				.body("fingerprint", contains("a1", "b2"))
				.body("[1].labels.pod", is("website-7d9f8b6c5-x2x4q"));
		given().when()
				.get("/api/v1/alerts/critical")
				.then()
				.statusCode(200)
				.header(AlertWebhookResource.SOURCE_HEADER, "alertmanager")
				.body("fingerprint", contains("a1"));
		given().when()
				.get("/api/v1/alerts/summary")
				.then()
				.statusCode(200)
				.header(AlertWebhookResource.SOURCE_HEADER, "alertmanager")
				.body("total", is(2))
				.body("critical", is(1))
				.body("warning", is(1))
				.body("info", is(0));
	}

	@Test
	void testGetAlertSummary() {
		given().when()
				.get("/api/v1/alerts/summary")
				.then()
				.statusCode(200)
				.header(AlertWebhookResource.SOURCE_HEADER, "alertmanager")
				.body("total", is(0));
	}

	private static StoredAlert alert(String fingerprint, String name, String severity) {
		return new StoredAlert(fingerprint, name, severity, "website", name + " summary", "", "firing",
				Instant.parse("2026-09-27T10:00:00Z"), Instant.parse("2026-09-27T10:04:00Z"),
				Map.of("alertname", name, "severity", severity, "namespace", "website",
						"pod", "website-7d9f8b6c5-x2x4q"));
	}
}
