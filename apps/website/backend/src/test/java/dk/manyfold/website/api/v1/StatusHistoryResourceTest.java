package dk.manyfold.website.api.v1;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StatusHistoryResourceTest {

	@Test
	void returnsHistoryForBothVantages() {
		Response response = given().when().get("/api/v1/status/history");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.jsonPath().getList("checks.name", String.class))
				.containsExactly("From the internet", "From inside the cluster");
		assertThat(response.jsonPath().getString("timestamp")).isNotBlank();
	}

	@Test
	void uptimeCoversAFortnightInTwelveHourBuckets() {
		Response response = given().when().get("/api/v1/status/history");

		assertThat(response.jsonPath().getInt("checks[0].uptime.days")).isEqualTo(14);
		assertThat(response.jsonPath().getInt("checks[0].uptime.bucketHours")).isEqualTo(12);
		assertThat(response.jsonPath().getList("checks[0].uptime.buckets")).hasSize(28);
	}

	@Test
	void latencyCoversADayInHalfHourSteps() {
		Response response = given().when().get("/api/v1/status/history");

		assertThat(response.jsonPath().getInt("checks[0].latency.hours")).isEqualTo(24);
		assertThat(response.jsonPath().getInt("checks[0].latency.stepMinutes")).isEqualTo(30);
		assertThat(response.jsonPath().getList("checks[0].latency.points")).hasSize(48);
		// The client reports seconds; the page should not have to know that.
		assertThat(response.jsonPath().getFloat("checks[0].latency.latestMs")).isEqualTo(1000f);
	}

	@Test
	void keepsGapsAsGapsRatherThanZeroes() {
		// A step Prometheus has no sample for is not an outage and must not be
		// drawn as one, so it survives the whole way to the page as null.
		Response response = given().when().get("/api/v1/status/history");

		assertThat(response.jsonPath().getList("checks[0].uptime.buckets", Float.class).get(1)).isNull();
		assertThat(response.jsonPath().getList("checks[0].latency.points", Float.class).get(1)).isNull();
		// The ratio ignores the gap rather than counting it as downtime.
		assertThat(response.jsonPath().getFloat("checks[0].uptime.ratio")).isEqualTo(1.0f);
	}
}
