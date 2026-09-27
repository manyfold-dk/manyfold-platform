package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

/** Tests for DeepHealthResource. */
@QuarkusTest
class DeepHealthResourceTest {

	@Test
	void testDeepHealthEndpoint() {
		given()
				.when()
				.get("/api/v1/health/deep")
				.then()
				.statusCode(200)
				.body("status", notNullValue())
				.body("service", notNullValue())
				.body("timestamp", notNullValue())
				.body("self", notNullValue())
				.body("self.status", notNullValue())
				.body("self.uptimeSeconds", greaterThanOrEqualTo(0))
				.body("self.resources", notNullValue())
				.body("dependencies", notNullValue());
	}

	@Test
	void testDeepHealthReportsPrometheusDependency() {
		// Spec B2: Prometheus reachability is reported as a /health/deep
		// dependency (assert on presence, not status -- status depends on
		// whether a Prometheus is reachable from the test profile).
		given()
				.when()
				.get("/api/v1/health/deep")
				.then()
				.statusCode(200)
				.body("dependencies.name", hasItem("prometheus"));
	}

	@Test
	void testDeepHealthReturnsHealthyStatus() {
		// Under normal test conditions, should be healthy
		given()
				.when()
				.get("/api/v1/health/deep")
				.then()
				.statusCode(200)
				.body("status", equalTo("healthy"));
	}
}
