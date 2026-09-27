package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AlertWebhookResourceTest {

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
	 * store, or it would permanently inflate the counts behind /summary and the
	 * public status page.
	 */
	@Test
	void testWatchdogIsNotStoredAsAnActiveAlert() {
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
				.body("findAll { it.name == 'Watchdog' }", hasSize(0));
	}

	@Test
	void testGetAlertSummary() {
		given().when()
				.get("/api/v1/alerts/summary")
				.then()
				.statusCode(200)
				.body("total", greaterThanOrEqualTo(0));
	}
}
