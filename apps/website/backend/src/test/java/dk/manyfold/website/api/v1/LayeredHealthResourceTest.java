package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LayeredHealthResourceTest {

	@Test
	void testHealthSummaryEndpoint() {
		given().when()
				.get("/api/v1/health/summary")
				.then()
				.statusCode(200)
				.body("overall", notNullValue())
				.body("timestamp", notNullValue());
	}

	@Test
	void summaryCarriesOneSignalPerRowOfThePublicPicture() {
		Response response = given().when().get("/api/v1/health/summary");

		assertThat(response.statusCode()).isEqualTo(200);
		for (String row : new String[]{
				"infrastructure", "network", "cluster", "applications",
				"edge", "identity", "delivery", "observability"}) {
			assertThat(response.jsonPath().getString(row))
					.as("summary row %s", row)
					.isIn("healthy", "degraded", "unhealthy", "unknown");
		}
	}

	@Test
	void alertCountIsTheNumberOfAlertsNotTheNumberOfSummaries() {
		// The mock reports no firing alerts. The old code returned the size of a
		// list that held a single "Multiple Alerts" placeholder, so any number of
		// firing alerts rendered as "1 active alert(s)" on the public page.
		Response response = given().when().get("/api/v1/health/summary");

		assertThat(response.jsonPath().getInt("activeAlerts")).isZero();
	}

	@Test
	void layeredHealthReportsTheNetworkLayer() {
		Response response = given().when().get("/api/v1/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.jsonPath().getString("network.status")).isNotBlank();
		assertThat(response.jsonPath().getString("network.cilium.name")).isEqualTo("Cilium");
		assertThat(response.jsonPath().getString("network.hubble.name")).isEqualTo("Hubble");
	}

	@Test
	void layeredHealthNamesNoAlertOnThePublicResponse() {
		// The response is public: it carries how many alerts are firing, never
		// which ones or where.
		Response response = given().when().get("/api/v1/health");

		assertThat(response.jsonPath().getInt("firingAlerts")).isZero();
		assertThat(response.jsonPath().getList("activeAlerts")).isEmpty();
	}
}
