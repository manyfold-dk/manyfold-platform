package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * Authorisation of the restart endpoints. The admin cases send no body on
 * purpose: the role check runs before validation, so a 400 proves the caller
 * was let through without touching the Kubernetes API.
 */
@QuarkusTest
class RemediationResourceTest {

	@Test
	void podRestartWithoutIdentityIsUnauthorized() {
		given().contentType(ContentType.JSON)
				.body("{\"namespace\":\"default\",\"podName\":\"p\"}")
				.when().post("/api/v1/remediation/pod/restart")
				.then().statusCode(401);
	}

	@Test
	void deploymentRestartWithoutIdentityIsUnauthorized() {
		given().contentType(ContentType.JSON)
				.body("{\"namespace\":\"default\",\"deploymentName\":\"d\"}")
				.when().post("/api/v1/remediation/deployment/restart")
				.then().statusCode(401);
	}

	@Test
	void forgedIdentityHeadersAreNotEnough() {
		given().contentType(ContentType.JSON)
				.header("X-Forwarded-Email", "someone@example.com")
				.header("X-Forwarded-Groups", "admin")
				.body("{\"namespace\":\"default\",\"podName\":\"p\"}")
				.when().post("/api/v1/remediation/pod/restart")
				.then().statusCode(401);
	}

	@Test
	@TestSecurity(user = "viewer", roles = "user")
	void podRestartWithoutAdminRoleIsForbidden() {
		given().contentType(ContentType.JSON)
				.body("{\"namespace\":\"default\",\"podName\":\"p\"}")
				.when().post("/api/v1/remediation/pod/restart")
				.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "viewer", roles = "user")
	void deploymentRestartWithoutAdminRoleIsForbidden() {
		given().contentType(ContentType.JSON)
				.body("{\"namespace\":\"default\",\"deploymentName\":\"d\"}")
				.when().post("/api/v1/remediation/deployment/restart")
				.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "operator", roles = "admin")
	void podRestartWithAdminRoleIsAuthorized() {
		given().contentType(ContentType.JSON)
				.when().post("/api/v1/remediation/pod/restart")
				.then().statusCode(400);
	}

	@Test
	@TestSecurity(user = "operator", roles = "admin")
	void deploymentRestartWithAdminRoleIsAuthorized() {
		given().contentType(ContentType.JSON)
				.when().post("/api/v1/remediation/deployment/restart")
				.then().statusCode(400);
	}

	@Test
	void summaryStaysReadableWithoutRole() {
		given().when().get("/api/v1/remediation/summary")
				.then().statusCode(200);
	}
}
