package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The machine webhooks refuse callers without the shared token (review finding
 * W2).
 */
@QuarkusTest
class WebhookAuthorizationTest {

	private static final String ALERT = """
			{"version":"4","groupKey":"k","truncatedAlerts":0,"status":"firing","receiver":"r",
			 "groupLabels":{},"commonLabels":{},"commonAnnotations":{},"externalURL":"",
			 "alerts":[{"status":"firing",
			 "labels":{"alertname":"KubePodCrashLooping","namespace":"x","pod":"y"},
			 "annotations":{},"startsAt":"2024-01-01T00:00:00Z","endsAt":"0001-01-01T00:00:00Z",
			 "generatorURL":"","fingerprint":"f"}]}
			""";

	@Test
	void alertWebhookWithoutTokenIsRefused() {
		given().contentType(ContentType.JSON).body(ALERT)
				.when().post("/api/v1/alerts/webhook")
				.then().statusCode(401);
	}

	@Test
	void alertWebhookWithWrongTokenIsRefused() {
		given().contentType(ContentType.JSON).header("Authorization", "Bearer wrong").body(ALERT)
				.when().post("/api/v1/alerts/webhook")
				.then().statusCode(401);
	}

	@Test
	void pipelineWebhookWithoutTokenIsRefused() {
		given().contentType(ContentType.JSON).body("{\"pipeline\":\"p\",\"status\":\"succeeded\"}")
				.when().post("/api/v1/pipeline/webhook")
				.then().statusCode(401);
	}
}
