package dk.manyfold.website.metrics;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import dk.manyfold.website.api.v1.model.WebVitalEntry;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for recording Web Vital metrics to Prometheus.
 *
 * <p>
 * The endpoint that feeds it is public, so every tag value is bounded (review
 * finding W3): the navigation type is one of the values the browser library
 * emits, and at most {@value #MAX_ROUTES} distinct normalised routes get a
 * series of their own. Anything else is still recorded, under {@value #OTHER}.
 */
@ApplicationScoped
public class WebVitalsMetrics {

	/** Distinct route tags kept; the site has about fifteen routes. */
	static final int MAX_ROUTES = 50;

	/** Tag value for anything outside the known or admitted set. */
	static final String OTHER = "other";

	private static final Set<String> NAVIGATION_TYPES = Set.of(
			"navigate", "reload", "back-forward", "back-forward-cache", "prerender", "restore");

	private static final int MAX_ROUTE_LENGTH = 64;

	private final MeterRegistry registry;

	private final Set<String> admittedRoutes = ConcurrentHashMap.newKeySet();

	/** Guards the size check and the insert as one step. */
	private final Object admitLock = new Object();

	// LCP buckets in milliseconds (Google thresholds: good < 2500, poor > 4000)
	private static final double[] LCP_BUCKETS = {100, 500, 1000, 1500, 2000, 2500, 3000, 4000, 5000, 10000};

	// INP buckets in milliseconds (Google thresholds: good < 200, poor > 500)
	private static final double[] INP_BUCKETS = {50, 100, 150, 200, 300, 400, 500, 750, 1000};

	// CLS buckets (unitless, Google thresholds: good < 0.1, poor > 0.25)
	private static final double[] CLS_BUCKETS = {0.01, 0.025, 0.05, 0.075, 0.1, 0.15, 0.2, 0.25, 0.5};

	// FCP/TTFB buckets in milliseconds
	private static final double[] FCP_TTFB_BUCKETS = {100, 200, 500, 1000, 1500, 1800, 2000, 3000, 5000};

	@Inject
	public WebVitalsMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/** Records a Web Vital metric. */
	public void record(WebVitalEntry entry) {
		String metricName = "web_vitals_" + entry.name().toLowerCase(Locale.ROOT);
		double[] buckets = getBucketsForMetric(entry.name());

		DistributionSummary summary = DistributionSummary.builder(metricName)
				.description("Web Vital: " + entry.name())
				.baseUnit(getUnitForMetric(entry.name()))
				.tags(
						"route",
						routeTag(entry.route()),
						"rating",
						entry.rating(),
						"navigation_type",
						navigationTypeTag(entry.navigationType()))
				.serviceLevelObjectives(buckets)
				.register(registry);

		summary.record(entry.value());
	}

	private double[] getBucketsForMetric(String name) {
		double[] buckets = switch (name) {
			case "LCP" -> LCP_BUCKETS;
			case "INP" -> INP_BUCKETS;
			case "CLS" -> CLS_BUCKETS;
			case "FCP", "TTFB" -> FCP_TTFB_BUCKETS;
			default -> FCP_TTFB_BUCKETS;
		};
		return buckets.clone();
	}

	/**
	 * CLS is a unitless score; a base unit would add a suffix such as {@code _1} to
	 * its name.
	 */
	private String getUnitForMetric(String name) {
		return "CLS".equals(name) ? null : "milliseconds";
	}

	private static String navigationTypeTag(String navigationType) {
		if (navigationType == null || navigationType.isBlank()) {
			return "unknown";
		}
		return NAVIGATION_TYPES.contains(navigationType) ? navigationType : OTHER;
	}

	private String routeTag(String route) {
		String sanitized = sanitizeRoute(route);
		if ("unknown".equals(sanitized)) {
			return sanitized;
		}
		if (sanitized.length() > MAX_ROUTE_LENGTH || !sanitized.matches("/[a-z0-9:/_-]*")) {
			return OTHER;
		}
		if (admittedRoutes.contains(sanitized)) {
			return sanitized;
		}
		synchronized (admitLock) {
			// Recheck: another request may have admitted this route since the check above.
			if (admittedRoutes.contains(sanitized) || admittedRoutes.size() < MAX_ROUTES) {
				admittedRoutes.add(sanitized);
				return sanitized;
			}
		}
		return OTHER;
	}

	private String sanitizeRoute(String route) {
		if (route == null || route.isBlank()) {
			return "unknown";
		}
		// Strip query strings to prevent high cardinality from URL parameters
		String sanitized = route.split("\\?")[0];
		// Also strip fragments
		sanitized = sanitized.split("#")[0];
		// Replace UUIDs and numeric IDs with placeholders to limit cardinality
		return sanitized
				.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", ":uuid")
				.replaceAll("/\\d+", "/:id");
	}
}
