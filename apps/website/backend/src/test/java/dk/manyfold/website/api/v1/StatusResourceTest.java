package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StatusResourceTest {

	@Test
	void testStatusEndpointReturnsValidResponse() {
		Response response = given()
				.when()
				.get("/api/v1/status");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.jsonPath().getString("status")).isNotNull();
		assertThat(response.jsonPath().getList("services")).isNotEmpty();
		assertThat(response.jsonPath().getString("timestamp")).isNotNull();
	}

	@Test
	void testStatusEndpointReturnsValidServiceStatuses() {
		Response response = given()
				.when()
				.get("/api/v1/status");

		assertThat(response.statusCode()).isEqualTo(200);

		// Verify overall status is one of the expected values
		String overallStatus = response.jsonPath().getString("status");
		assertThat(overallStatus).isIn("operational", "degraded", "outage");

		// Verify each service has required fields
		var services = response.jsonPath().getList("services");
		assertThat(services).isNotEmpty();
		for (int i = 0; i < services.size(); i++) {
			String serviceName = response.jsonPath().getString("services[" + i + "].name");
			String serviceStatus = response.jsonPath().getString("services[" + i + "].status");

			assertThat(serviceName).isNotBlank();
			assertThat(serviceStatus).isIn("operational", "degraded", "outage", "unknown");
		}
	}

	@Test
	void testStatusEndpointReportsBothVantagesAndTheCertificate() {
		Response response = given()
				.when()
				.get("/api/v1/status");

		var names = response.jsonPath().getList("services.name", String.class);
		assertThat(names).contains(
				"Backend API", "From the internet", "From inside the cluster", "TLS certificate");
	}

	@Test
	void testEveryCheckSaysWhereItsReadingCameFrom() {
		Response response = given()
				.when()
				.get("/api/v1/status");

		var details = response.jsonPath().getList("services.detail", String.class);
		assertThat(details).doesNotContainNull().allSatisfy(d -> assertThat(d).isNotBlank());
	}

	@Test
	void testCertificateReportsDaysRemainingAndNoLatency() {
		Response response = given()
				.when()
				.get("/api/v1/status");

		int index = response.jsonPath().getList("services.name", String.class).indexOf("TLS certificate");
		assertThat(response.jsonPath().getString("services[" + index + "].detail"))
				.isEqualTo("valid for another 45 days");
		assertThat(response.jsonPath().getString("services[" + index + "].latencyMs")).isNull();
	}

	@Test
	void testStatusEndpointReturnsTimestamp() {
		Response response = given()
				.when()
				.get("/api/v1/status");

		assertThat(response.statusCode()).isEqualTo(200);

		// Timestamp should be ISO-8601 format
		String timestamp = response.jsonPath().getString("timestamp");
		assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
	}
}
