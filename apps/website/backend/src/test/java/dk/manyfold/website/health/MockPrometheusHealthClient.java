package dk.manyfold.website.health;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.test.Mock;

/** Mock Prometheus health client for testing. */
@Mock
@Alternative
@Priority(1)
@ApplicationScoped
public class MockPrometheusHealthClient extends PrometheusHealthClient {

	@Override
	public Optional<Double> queryInstant(String query) {
		return Optional.of(1.0);
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public int getArgoCDSyncedApps() {
		return 15;
	}

	@Override
	public int getArgoCDDegradedApps() {
		return 0;
	}

	@Override
	public int getArgoCDAppsOutOfSyncFor(long minutes) {
		return 0;
	}

	@Override
	public int getFiringAlerts() {
		return 0;
	}

	@Override
	public Map<String, Double> queryInstantByName(String query) {
		return Map.of(
				"synthetic_check_up", 1.0,
				"synthetic_check_duration_seconds", 0.5,
				"synthetic_run_timestamp_seconds", System.currentTimeMillis() / 1000.0);
	}

	@Override
	public SyntheticProbe getSyntheticProbe(String front, String journey, String vantage) {
		return new SyntheticProbe(true, true, 0.5, 30);
	}

	@Override
	public Optional<Double> getCertificateDaysRemaining(String instance) {
		return Optional.of(45.0);
	}

	@Override
	public List<Double> queryRange(String query, long startEpochSeconds, long endEpochSeconds, long stepSeconds) {
		int points = (int) ((endEpochSeconds - startEpochSeconds) / stepSeconds) + 1;
		List<Double> values = new ArrayList<>(points);
		for (int i = 0; i < points; i++) {
			// One gap, so tests see that a missing sample survives as null rather
			// than being flattened into a zero.
			values.add(i == 1 ? null : 1.0);
		}
		return values;
	}
}
