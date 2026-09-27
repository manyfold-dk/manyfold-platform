package dk.manyfold.website.health;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Process-only liveness (spec B2): reports UP whenever the JVM and event loop
 * can serve this request. No external dependency may be added here.
 */
@Liveness
@ApplicationScoped
public class ProcessLivenessCheck implements HealthCheck {

	@Override
	public HealthCheckResponse call() {
		return HealthCheckResponse.up("process-up");
	}
}
