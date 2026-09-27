package dk.manyfold.website.api.v1.model;

import java.time.Instant;
import java.util.List;

/**
 * Response model for platform status endpoint.
 *
 * @param status
 *            Overall status: "operational", "degraded", or "outage"
 * @param services
 *            Individual service statuses
 * @param timestamp
 *            ISO-8601 timestamp of the status check
 */
public record StatusResponse(
		String status,
		List<ServiceStatus> services,
		String timestamp) {

	/**
	 * Individual service status.
	 *
	 * @param name
	 *            Service name
	 * @param status
	 *            Status: "operational", "degraded", "outage", or "unknown" when the
	 *            check produced no usable reading
	 * @param latencyMs
	 *            Response latency in milliseconds (null when the check has no
	 *            meaningful duration, e.g. a certificate expiry)
	 * @param detail
	 *            One short phrase saying where the reading came from and how old it
	 *            is, or null
	 */
	public record ServiceStatus(
			String name,
			String status,
			Long latencyMs,
			String detail) {

		/** A live check with no extra provenance to report. */
		public ServiceStatus(String name, String status, Long latencyMs) {
			this(name, status, latencyMs, null);
		}
	}

	/** Creates a new StatusResponse with the current timestamp. */
	public static StatusResponse of(String status, List<ServiceStatus> services) {
		return new StatusResponse(status, services, Instant.now().toString());
	}
}
