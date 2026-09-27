package dk.manyfold.website.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The PromQL behind the status history. What the queries return was checked
 * against the live Prometheus; this pins down what they ask.
 */
class StatusHistoryQueriesTest {

	private static final String EDGE = "{front=\"www\",journey=\"edge\",vantage=\"external\"}";
	private static final String SMOKE = "{front=\"www\",journey=\"smoke\",vantage=\"internal\"}";

	/** Every window is an average over a subquery. */
	private static final String AVERAGE = "avg_over_time((";

	/** The current status's freshness rule, which the history must share. */
	private static final double EDGE_MAX_AGE = PublicStatusService.EDGE_PROBE_MAX_AGE_SECONDS;
	private static final double JOURNEY_MAX_AGE = PublicStatusService.JOURNEY_PROBE_MAX_AGE_SECONDS;

	/** Every range query one history refresh sends. */
	private static List<String> queriesOfOneRefresh() {
		List<String> queries = new ArrayList<>();
		StatusHistoryService service = new StatusHistoryService();
		service.prometheusClient = new PrometheusHealthClient() {
			@Override
			public List<Double> queryRange(String query, long start, long end, long step) {
				queries.add(query);
				return Collections.nCopies((int) ((end - start) / step) + 1, null);
			}
		};
		service.collect();
		return queries;
	}

	private static String freshWithin(String selector, double maxAgeSeconds) {
		return "and (time() - synthetic_run_timestamp_seconds" + selector
				+ " <= " + Math.round(maxAgeSeconds) + ")";
	}

	@Test
	void everyQueryCountsOnlySamplesAsFreshAsTheCurrentStatusDemands() {
		List<String> queries = queriesOfOneRefresh();

		assertThat(queries).hasSize(4);
		assertThat(queries)
				.filteredOn(q -> q.contains(EDGE))
				.hasSize(2)
				.allSatisfy(q -> assertThat(q).contains(freshWithin(EDGE, EDGE_MAX_AGE)));
		assertThat(queries)
				.filteredOn(q -> q.contains(SMOKE))
				.hasSize(2)
				.allSatisfy(q -> assertThat(q).contains(freshWithin(SMOKE, JOURNEY_MAX_AGE)));
	}

	@Test
	void windowsAreReadMinuteByMinuteSoStaleMinutesDropOut() {
		List<String> queries = queriesOfOneRefresh();

		assertThat(queries).filteredOn(q -> q.contains("synthetic_check_up"))
				.hasSize(2)
				.allSatisfy(q -> assertThat(q).startsWith(AVERAGE).endsWith(")[43200s:60s])"));
		assertThat(queries).filteredOn(q -> q.contains("synthetic_check_duration_seconds"))
				.hasSize(2)
				.allSatisfy(q -> assertThat(q).startsWith(AVERAGE).endsWith(")[1800s:60s])"));
	}

	@Test
	void aPushgatewayRestartDoesNotSplitAProbeInTwo() {
		// The series carry the Pushgateway pod's name, and the range query reads
		// one series: without the fold, a restart hid everything before it.
		assertThat(queriesOfOneRefresh())
				.allSatisfy(q -> assertThat(q).contains("(max by (front, journey, vantage) ("));
	}
}
