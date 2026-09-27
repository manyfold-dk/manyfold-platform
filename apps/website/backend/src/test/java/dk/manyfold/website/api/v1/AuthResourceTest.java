package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;

import dk.manyfold.website.auth.MockKeycloakLogoutService;
import dk.manyfold.website.auth.MockKeycloakLogoutService.FailureMode;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthResourceTest {

	@BeforeEach
	void resetMock() {
		MockKeycloakLogoutService.reset();
	}

	@Test
	void testLogoutRedirectsToProxyWhenKeycloakLogoutIsDisabled() {
		MockKeycloakLogoutService.configured = false;

		Response response = given()
				.redirects().follow(false)
				.when()
				.post("/api/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(303);
		assertThat(response.getHeader("Location")).endsWith("/oauth2/sign_out");
	}

	@Test
	void testLogoutRequiresForwardedIdentityHeaders() {
		Response response = given()
				.when()
				.post("/api/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.asString()).isEqualTo("Missing authenticated user identity");
	}

	@Test
	void testLogoutEndsKeycloakSessionBeforeClearingProxySession() {
		Response response = given()
				.header("X-Forwarded-Email", "admin@example.com")
				.header("X-Forwarded-Preferred-Username", "manyfold-admin")
				.redirects().follow(false)
				.when()
				.post("/api/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(303);
		assertThat(response.getHeader("Location")).endsWith("/oauth2/sign_out");
		assertThat(MockKeycloakLogoutService.lastEmail).isEqualTo("admin@example.com");
		assertThat(MockKeycloakLogoutService.lastUsername).isEqualTo("manyfold-admin");
	}

	@Test
	void testLogoutReturnsBadGatewayWhenKeycloakLogoutFails() {
		MockKeycloakLogoutService.failureMode = FailureMode.IO;

		Response response = given()
				.header("X-Forwarded-Email", "admin@example.com")
				.when()
				.post("/api/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(502);
		assertThat(response.asString()).isEqualTo("Failed to end Keycloak session");
	}

	@Test
	void testLogoutReturnsBadGatewayWhenInterrupted() {
		MockKeycloakLogoutService.failureMode = FailureMode.INTERRUPTED;

		Response response = given()
				.header("X-Forwarded-User", "manyfold-admin")
				.when()
				.post("/api/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(502);
		assertThat(response.asString()).isEqualTo("Interrupted while ending Keycloak session");
	}

	@Test
	void testLogoutRejectsGet() {
		Response response = given()
				.redirects().follow(false)
				.when()
				.get("/api/v1/auth/logout");

		assertThat(response.statusCode()).isEqualTo(405);
	}
}
