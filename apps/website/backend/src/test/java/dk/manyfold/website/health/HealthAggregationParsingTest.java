package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import dk.manyfold.website.api.v1.model.LayeredHealthResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class HealthAggregationParsingTest {

	private final HealthAggregationService service = serviceWithMapper();

	private static HealthAggregationService serviceWithMapper() {
		HealthAggregationService s = new HealthAggregationService();
		s.objectMapper = new ObjectMapper();
		return s;
	}

	@Test
	void readsTheTopLevelStatusNotTheFirstStatusInTheText() {
		String json = "{\"self\":{\"status\":\"degraded\"},\"status\":\"healthy\",\"dependencies\":[]}";
		assertThat(service.extractStatusFromJson(json)).isEqualTo("healthy");
	}

	@Test
	void namesTheFirstUnhealthyDependency() {
		String json = "{\"status\":\"unhealthy\",\"self\":{\"status\":\"unhealthy\"},"
				+ "\"dependencies\":[{\"name\":\"redis\",\"status\":\"healthy\"},"
				+ "{\"name\":\"prometheus\",\"status\":\"unhealthy\"}]}";
		assertThat(service.extractDependencyIssues(json)).isEqualTo("Dependency unhealthy: prometheus");
	}

	@Test
	void reportsNothingWhenOnlyTheSelfCheckIsUnhealthy() {
		String json = "{\"status\":\"unhealthy\",\"self\":{\"status\":\"unhealthy\"},\"dependencies\":[]}";
		assertThat(service.extractDependencyIssues(json)).isEmpty();
	}

	@Test
	void invalidJsonFailsTheCheckInsteadOfReadingAsHealthy() {
		assertThatThrownBy(() -> service.extractStatusFromJson("<html>502</html>"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void anEmptyNodeListIsUnknownNotHealthy() {
		HealthAggregationService s = serviceWithMapper();
		s.kubernetesClient = new KubernetesHealthClient(null) {
			@Override
			public ClusterHealth getClusterHealth() {
				return new ClusterHealth(0, 0, 0, 0, 0, 0, List.of());
			}
		};
		assertThat(s.collectInfrastructureHealth().status()).isEqualTo(LayeredHealthResponse.STATUS_UNKNOWN);
	}

	@Test
	void parsesAPodAppEntry() {
		assertThat(HealthAggregationService.PodApp.parse("Shop|shop|app=shop-api"))
				.isEqualTo(new HealthAggregationService.PodApp("Shop", "shop", "app", "shop-api"));
	}

	@Test
	void rejectsMalformedPodAppEntries() {
		assertThat(HealthAggregationService.PodApp.parse("Shop|shop")).isNull();
		assertThat(HealthAggregationService.PodApp.parse("Shop|shop|app")).isNull();
		assertThat(HealthAggregationService.PodApp.parse("|shop|app=x")).isNull();
	}
}
