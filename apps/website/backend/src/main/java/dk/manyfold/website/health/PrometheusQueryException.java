package dk.manyfold.website.health;

/**
 * Prometheus gave no usable answer to a query.
 *
 * <p>
 * Unreachable, an error status, or a body that is not a query result. This is
 * the absence of a measurement, and it is kept apart from a query that matched
 * no series, which is a measurement: counting nothing is often exactly the
 * right answer. Collapsing the two is what let an unreachable Prometheus read
 * as an idle, healthy pipeline.
 */
public class PrometheusQueryException extends Exception {

	public PrometheusQueryException(String message) {
		super(message);
	}

	public PrometheusQueryException(String message, Throwable cause) {
		super(message, cause);
	}
}
