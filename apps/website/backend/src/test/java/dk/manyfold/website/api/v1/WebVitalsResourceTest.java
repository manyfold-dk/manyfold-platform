package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;

import dk.manyfold.website.metrics.WebVitalsStore;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WebVitalsResourceTest {

	private static final String VITALS_ENDPOINT = "/api/v1/metrics/vitals";
	private static final String VITALS_RECENT_ENDPOINT = VITALS_ENDPOINT + "/recent";

	@Inject
	WebVitalsStore store;

	@BeforeEach
	void resetStore() {
		store.clear();
	}

	@Test
	void testSubmitValidBatch() {
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-1234567890",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(204);
	}

	@Test
	void testSubmitMultipleEntries() {
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-lcp",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						},
						{
							"name": "CLS",
							"value": 0.05,
							"rating": "good",
							"delta": 0.05,
							"id": "v4-cls",
							"navigationType": "navigate",
							"route": "/about",
							"timestamp": 1705000000001
						},
						{
							"name": "INP",
							"value": 150.0,
							"rating": "good",
							"delta": 150.0,
							"id": "v4-inp",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000002
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(204);
	}

	@Test
	void testSubmitEmptyBatchReturns400() {
		String json = """
				{
					"entries": [],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testSubmitInvalidMetricNameReturns400() {
		String json = """
				{
					"entries": [
						{
							"name": "INVALID",
							"value": 100.0,
							"rating": "good",
							"delta": 100.0,
							"id": "v4-1234567890",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testSubmitInvalidRatingReturns400() {
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 100.0,
							"rating": "invalid-rating",
							"delta": 100.0,
							"id": "v4-1234567890",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testNullBodyReturns400() {
		Response response = given()
				.contentType(ContentType.JSON)
				.body("null")
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testNullNavigationTypeIsAccepted() {
		String json = """
				{
					"entries": [
						{
							"name": "TTFB",
							"value": 500.0,
							"rating": "good",
							"delta": 500.0,
							"id": "v4-ttfb",
							"navigationType": null,
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(204);
	}

	@Test
	void testOversizedRouteReturns400() {
		String oversizedRoute = "x".repeat(257); // Max is 256
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-test",
							"navigationType": "navigate",
							"route": "%s",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""".formatted(oversizedRoute);

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testOversizedUserAgentReturns400() {
		String oversizedUserAgent = "x".repeat(513); // Max is 512
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-test",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "%s",
					"url": "https://example.com/"
				}
				""".formatted(oversizedUserAgent);

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testOversizedUrlReturns400() {
		String oversizedUrl = "https://example.com/" + "x".repeat(2030); // Max is 2048
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-test",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "%s"
				}
				""".formatted(oversizedUrl);

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testOversizedIdReturns400() {
		String oversizedId = "x".repeat(65); // Max is 64
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "%s",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""".formatted(oversizedId);

		Response response = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(400);
	}

	@Test
	void testGetRecentVitals() {
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-lcp",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000000
						},
						{
							"name": "CLS",
							"value": 0.05,
							"rating": "good",
							"delta": 0.05,
							"id": "v4-cls",
							"navigationType": "navigate",
							"route": "/about",
							"timestamp": 1705000000001
						},
						{
							"name": "INP",
							"value": 150.0,
							"rating": "good",
							"delta": 150.0,
							"id": "v4-inp",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000000002
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response submit = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);
		assertThat(submit.statusCode()).isEqualTo(204);

		Response response = given()
				.queryParam("limit", 2)
				.when()
				.get(VITALS_RECENT_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(200);
		var names = response.jsonPath().getList("name", String.class);
		assertThat(names).containsExactly("INP", "CLS");
	}

	@Test
	void testGetRecentVitalsDefaultLimitIsCappedToResults() {
		String json = """
				{
					"entries": [
						{
							"name": "LCP",
							"value": 2500.0,
							"rating": "good",
							"delta": 2500.0,
							"id": "v4-1",
							"navigationType": "navigate",
							"route": "/",
							"timestamp": 1705000001000
						},
						{
							"name": "FCP",
							"value": 1200.0,
							"rating": "good",
							"delta": 1200.0,
							"id": "v4-2",
							"navigationType": "navigate",
							"route": "/status",
							"timestamp": 1705000002000
						},
						{
							"name": "TTFB",
							"value": 200.0,
							"rating": "good",
							"delta": 200.0,
							"id": "v4-3",
							"navigationType": "navigate",
							"route": "/platform",
							"timestamp": 1705000003000
						}
					],
					"userAgent": "Mozilla/5.0",
					"url": "https://example.com/"
				}
				""";

		Response submit = given()
				.contentType(ContentType.JSON)
				.body(json)
				.when()
				.post(VITALS_ENDPOINT);
		assertThat(submit.statusCode()).isEqualTo(204);

		Response response = given().when().get(VITALS_RECENT_ENDPOINT);

		assertThat(response.statusCode()).isEqualTo(200);
		var names = response.jsonPath().getList("name", String.class);
		assertThat(names).hasSize(3);
		assertThat(names.get(0)).isEqualTo("TTFB");
	}
}
