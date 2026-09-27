package dk.manyfold.website.api.v1.model;

import java.time.Instant;
import java.util.List;

/**
 * Recent history for the public checks, as series rather than pictures.
 *
 * <p>
 * The page draws these as inline SVG using its own colour tokens, which is why
 * the server sends numbers and not images: an image would be fixed-width,
 * blurry on a high-density screen, invisible to a screen reader and styled by
 * whatever drew it rather than by the page.
 *
 * @param checks
 *            one entry per check that has history worth drawing
 * @param timestamp
 *            when these series were read from Prometheus
 */
public record StatusHistoryResponse(List<CheckHistory> checks, String timestamp) {

	/**
	 * @param name
	 *            matches the check name in /api/v1/status
	 */
	public record CheckHistory(String name, Uptime uptime, Latency latency) {
	}

	/**
	 * Availability, as a fraction per bucket.
	 *
	 * @param buckets
	 *            0.0 to 1.0 per bucket, oldest first, null where nothing was
	 *            recorded -- a gap in the monitoring is not an outage and must not
	 *            be drawn as one
	 * @param ratio
	 *            availability across every bucket that has a reading, or null
	 */
	public record Uptime(int days, int bucketHours, List<Double> buckets, Double ratio) {
	}

	/**
	 * Response time in milliseconds.
	 *
	 * @param points
	 *            milliseconds per step, oldest first, null where there was no
	 *            sample
	 * @param latestMs
	 *            the most recent reading, or null
	 */
	public record Latency(int hours, int stepMinutes, List<Double> points, Double latestMs) {
	}

	/** Creates a response stamped with the time the series were read. */
	public static StatusHistoryResponse of(List<CheckHistory> checks) {
		return new StatusHistoryResponse(checks, Instant.now().toString());
	}
}
