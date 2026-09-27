package dk.manyfold.website.health;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;

import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

/**
 * Spec B2 (2026-07-07): kubelet probes must be decoupled from external
 * dependencies. Liveness is process-only; readiness must not include the
 * auto-registered Redis or Kubernetes-client extension checks.
 */
@QuarkusTest
class ProbeDecouplingTest {

	@Test
	void livenessIsProcessOnly() {
		given().when().get("/health/live")
				.then().statusCode(200)
				.body("checks.name", hasItem("process-up"));
	}

	@Test
	void readinessExcludesExternalDependencyChecks() {
		String body = given().when().get("/health/ready")
				.then().statusCode(200)
				.extract().asString();
		org.junit.jupiter.api.Assertions.assertFalse(
				body.contains("Redis"),
				"Redis extension check must not be wired into readiness: " + body);
		org.junit.jupiter.api.Assertions.assertFalse(
				body.contains("Kubernetes"),
				"Kubernetes-client check must not be wired into readiness: " + body);
	}
}
