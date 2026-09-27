package dk.manyfold.website.metrics;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Pins the exported metric names that the Grafana dashboards and the alert
 * rules query. A rename here breaks panels silently, so the names are asserted
 * on the real endpoint.
 */
@QuarkusTest
class MetricsExpositionTest {

	private static String scrape() {
		return given().when().get("/metrics").then().statusCode(200).extract().asString();
	}

	@Test
	void httpServerRequestsExportLatencyBuckets() {
		given().when().get("/api/v1/status").then().statusCode(200);

		String metrics = scrape();

		assertThat(metrics).contains("http_server_requests_seconds_bucket{");
		assertThat(metrics).containsPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.5\"");
	}

	@Test
	void hashedAssetPathsShareOneUriTag() {
		given().when().get("/assets/test.js").then().statusCode(200);

		String metrics = scrape();

		assertThat(metrics).contains("uri=\"/assets/*\"");
		assertThat(metrics).doesNotContain("uri=\"/assets/test.js\"");
	}

	@Test
	void webVitalsExportTheNamesTheDashboardQueries() {
		String batch = """
				{"entries": [
				  {"name": "LCP", "value": 1200.0, "rating": "good", "delta": 1200.0, "id": "v1-lcp",
				   "navigationType": "navigate", "route": "/", "timestamp": 1705000000000},
				  {"name": "CLS", "value": 0.02, "rating": "good", "delta": 0.02, "id": "v1-cls",
				   "navigationType": "navigate", "route": "/", "timestamp": 1705000000000}
				]}
				""";
		given().contentType(ContentType.JSON).body(batch)
				.when().post("/api/v1/metrics/vitals")
				.then().statusCode(204);

		String metrics = scrape();

		assertThat(metrics).contains("web_vitals_lcp_milliseconds_bucket{");
		assertThat(metrics).contains("web_vitals_cls_bucket{");
		assertThat(metrics).doesNotContain("web_vitals_cls_1");
	}
}
