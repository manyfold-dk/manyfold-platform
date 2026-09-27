package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dk.manyfold.website.api.v1.model.LayeredHealthResponse;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.ComponentHealth;
import dk.manyfold.website.api.v1.model.LayeredHealthResponse.PipelinesHealth;

import org.junit.jupiter.api.Test;

/** What the Argo CD and pipeline signals make of what Prometheus says. */
class HealthAggregationSignalsTest {

	private static HealthAggregationService serviceWith(PrometheusHealthClient prometheus) {
		HealthAggregationService service = new HealthAggregationService();
		service.prometheusClient = prometheus;
		service.argocdOutOfSyncMinutes = 15;
		return service;
	}

	/**
	 * Argo CD metrics with the given counts; records the grace it was asked about.
	 */
	private static PrometheusHealthClient argocd(int synced, int degraded, int stuck, List<Long> askedMinutes) {
		return new PrometheusHealthClient() {
			@Override
			public int getArgoCDSyncedApps() {
				return synced;
			}

			@Override
			public int getArgoCDDegradedApps() {
				return degraded;
			}

			@Override
			public int getArgoCDAppsOutOfSyncFor(long minutes) {
				askedMinutes.add(minutes);
				return stuck;
			}
		};
	}

	/** A Prometheus that matches no series for any query. */
	private static PrometheusHealthClient noSeries() {
		return new PrometheusHealthClient() {
			@Override
			public Optional<Double> queryInstant(String query) {
				return Optional.empty();
			}
		};
	}

	/** A Prometheus that does not answer. */
	private static PrometheusHealthClient unreachable() {
		return new PrometheusHealthClient() {
			@Override
			public Optional<Double> queryInstant(String query) throws PrometheusQueryException {
				throw new PrometheusQueryException("connection refused");
			}
		};
	}

	@Test
	void anApplicationOutOfSyncForLongerThanTheGraceDegradesArgoCD() {
		ComponentHealth argo = serviceWith(argocd(64, 0, 1, new ArrayList<>())).checkArgoCDHealth();

		assertThat(argo.status()).isEqualTo(LayeredHealthResponse.STATUS_DEGRADED);
		assertThat(argo.details()).isEqualTo("64 apps synced, 1 out of sync for over 15 min");
	}

	@Test
	void outOfSyncWithinTheGraceIsANormalSyncWindow() {
		// The query only counts Applications stuck for the whole grace; one that is
		// merely syncing is not counted, and Argo CD stays healthy.
		ComponentHealth argo = serviceWith(argocd(64, 0, 0, new ArrayList<>())).checkArgoCDHealth();

		assertThat(argo.status()).isEqualTo(LayeredHealthResponse.STATUS_HEALTHY);
		assertThat(argo.details()).isEqualTo("64 apps synced");
	}

	@Test
	void theGraceIsTheConfiguredOne() {
		List<Long> asked = new ArrayList<>();
		HealthAggregationService service = serviceWith(argocd(65, 0, 0, asked));
		service.argocdOutOfSyncMinutes = 30;

		service.checkArgoCDHealth();

		assertThat(asked).containsExactly(30L);
	}

	@Test
	void degradedAndStuckApplicationsAreBothNamed() {
		ComponentHealth argo = serviceWith(argocd(63, 1, 2, new ArrayList<>())).checkArgoCDHealth();

		assertThat(argo.status()).isEqualTo(LayeredHealthResponse.STATUS_DEGRADED);
		assertThat(argo.details()).isEqualTo("63 apps synced, 1 degraded, 2 out of sync for over 15 min");
	}

	@Test
	void argoCDWithoutMetricsIsUnknownNotHealthy() {
		ComponentHealth argo = serviceWith(unreachable()).checkArgoCDHealth();

		assertThat(argo.status()).isEqualTo(LayeredHealthResponse.STATUS_UNKNOWN);
		assertThat(argo.details()).isEqualTo("no metrics");
	}

	@Test
	void pipelinesReadUnknownWithoutASource() {
		// No engine reports pipeline runs since the Tekton controller was retired, so
		// the block
		// asks Prometheus nothing and is unmeasured whether Prometheus answers or not;
		// the
		// roll-ups ignore it while Argo CD is measured.
		for (PrometheusHealthClient prometheus : List.of(unreachable(), noSeries())) {
			PipelinesHealth pipelines = serviceWith(prometheus).collectPipelinesHealth();
			assertThat(pipelines.status()).isEqualTo(LayeredHealthResponse.STATUS_UNKNOWN);
			assertThat(pipelines.totalRuns24h()).isZero();
			assertThat(pipelines.lastRunStatus()).isEqualTo("none");
		}
	}
}
