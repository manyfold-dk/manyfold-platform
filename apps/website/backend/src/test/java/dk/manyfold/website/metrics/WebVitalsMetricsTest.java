package dk.manyfold.website.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dk.manyfold.website.api.v1.model.WebVitalEntry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebVitalsMetricsTest {

	private static final String METRIC_NAME = "LCP";
	private static final String METRIC_NAME_LOWER = "web_vitals_lcp";
	private static final String RATING_GOOD = "good";
	private static final String TEST_ID = "test-id";
	private static final double TEST_VALUE = 2500.0;
	private static final long TEST_TIMESTAMP = 1705000000000L;

	private MeterRegistry registry;
	private WebVitalsMetrics metrics;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = new WebVitalsMetrics(registry);
	}

	private WebVitalEntry createEntry(String navigationType, String route) {
		return new WebVitalEntry(
				METRIC_NAME, TEST_VALUE, RATING_GOOD, TEST_VALUE,
				TEST_ID, navigationType, route, TEST_TIMESTAMP);
	}

	@Test
	void testRecordCreatesMetric() {
		WebVitalEntry entry = createEntry("navigate", "/home");

		metrics.record(entry);

		Meter meter = registry.find(METRIC_NAME_LOWER).meter();
		assertThat(meter).isNotNull();
		assertThat(meter.getId().getName()).isEqualTo(METRIC_NAME_LOWER);
	}

	@ParameterizedTest
	@CsvSource({
			"'/path?query=value', '/path'",
			"'/path?a=1&b=2', '/path'",
			"'/path#fragment', '/path'",
			"'/path?query=value#fragment', '/path'",
			"'/users/123', '/users/:id'",
			"'/items/550e8400-e29b-41d4-a716-446655440000', '/items/:uuid'",
			"'/', '/'",
			"'/about', '/about'"
	})
	void testSanitizeRouteStripsQueryAndFragment(String input, String expected) {
		WebVitalEntry entry = createEntry("navigate", input);

		metrics.record(entry);

		Meter meter = registry.find(METRIC_NAME_LOWER).meter();
		assertThat(meter).isNotNull();
		String routeTag = meter.getId().getTag("route");
		assertThat(routeTag).isEqualTo(expected);
	}

	@Test
	void testBlankRouteReturnsUnknown() {
		// Note: @NotBlank validation prevents blank routes at the API level,
		// but the sanitizeRoute method still handles this case defensively
		WebVitalEntry entry = createEntry("navigate", "   ");

		metrics.record(entry);

		Meter meter = registry.find(METRIC_NAME_LOWER).meter();
		assertThat(meter).isNotNull();
		String routeTag = meter.getId().getTag("route");
		assertThat(routeTag).isEqualTo("unknown");
	}

	@Test
	void testNullNavigationTypeRecordsAsUnknown() {
		WebVitalEntry entry = createEntry(null, "/home");

		metrics.record(entry);

		Meter meter = registry.find(METRIC_NAME_LOWER).meter();
		assertThat(meter).isNotNull();
		String navTypeTag = meter.getId().getTag("navigation_type");
		assertThat(navTypeTag).isEqualTo("unknown");
	}

	@Test
	void testUnknownNavigationTypeIsFoldedIntoOther() {
		metrics.record(createEntry("attacker-controlled-value", "/home"));

		Meter meter = registry.find(METRIC_NAME_LOWER).tag("navigation_type", WebVitalsMetrics.OTHER).meter();
		assertThat(meter).isNotNull();
	}

	@Test
	void testRouteWithUnexpectedCharactersIsFoldedIntoOther() {
		metrics.record(createEntry("navigate", "/<script>alert(1)</script>"));

		assertThat(registry.find(METRIC_NAME_LOWER).tag("route", WebVitalsMetrics.OTHER).meter()).isNotNull();
	}

	@Test
	void testDistinctRoutesAreBounded() {
		for (int i = 0; i < WebVitalsMetrics.MAX_ROUTES + 25; i++) {
			metrics.record(createEntry("navigate", "/page-" + Integer.toString(i, 36) + "x"));
		}

		long routes = registry.find(METRIC_NAME_LOWER).meters().stream()
				.map(meter -> meter.getId().getTag("route"))
				.distinct()
				.count();
		assertThat(routes).isEqualTo(WebVitalsMetrics.MAX_ROUTES + 1L);
	}
}
