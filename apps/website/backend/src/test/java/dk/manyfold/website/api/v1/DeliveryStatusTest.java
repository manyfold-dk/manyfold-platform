package dk.manyfold.website.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import dk.manyfold.website.api.v1.model.LayeredHealthResponse;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.ComponentHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.PipelinesHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.PlatformHealth;

import org.junit.jupiter.api.Test;

/** The public delivery signal: Argo CD and the pipelines rolled into one. */
class DeliveryStatusTest {

	private static final String HEALTHY = LayeredHealthResponse.STATUS_HEALTHY;
	private static final String DEGRADED = LayeredHealthResponse.STATUS_DEGRADED;
	private static final String UNKNOWN = LayeredHealthResponse.STATUS_UNKNOWN;

	private static String delivery(String argocd, String pipelines) {
		PlatformHealth platform = new PlatformHealth(
				HEALTHY, null, null, new ComponentHealth("ArgoCD", argocd, ""), null, null);
		PipelinesHealth pipelineHealth = new PipelinesHealth(pipelines, 0, 0, 0, 0, "", "");
		LayeredHealthResponse health = new LayeredHealthResponse(
				HEALTHY, null, null, null, platform, pipelineHealth, null, List.of(), 0, "");
		return LayeredHealthResource.deliveryStatus(health);
	}

	@Test
	void deliveryIsUnknownWhenNeitherHalfWasMeasured() {
		// Prometheus unreachable: both halves unknown. That is not a healthy delivery.
		assertThat(delivery(UNKNOWN, UNKNOWN)).isEqualTo(UNKNOWN);
	}

	@Test
	void anUnknownHalfDefersToTheMeasuredOne() {
		assertThat(delivery(HEALTHY, UNKNOWN)).isEqualTo(HEALTHY);
		assertThat(delivery(DEGRADED, UNKNOWN)).isEqualTo(DEGRADED);
		assertThat(delivery(UNKNOWN, HEALTHY)).isEqualTo(HEALTHY);
	}

	@Test
	void aDegradedHalfDegradesDelivery() {
		assertThat(delivery(DEGRADED, HEALTHY)).isEqualTo(DEGRADED);
	}
}
