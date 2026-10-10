package dk.manyfold.website.health;

/**
 * Alertmanager gave no usable answer to a query for its active alerts.
 *
 * <p>
 * Unreachable, an error status, or a body that is not a list of alerts. This is
 * the absence of an answer, and it is kept apart from an answer that lists no
 * alerts: "nothing is firing" lets auto-remediation rest, while "Alertmanager
 * did not say" must stop it acting on anything.
 */
public class AlertmanagerQueryException extends Exception {

	public AlertmanagerQueryException(String message) {
		super(message);
	}

	public AlertmanagerQueryException(String message, Throwable cause) {
		super(message, cause);
	}
}
