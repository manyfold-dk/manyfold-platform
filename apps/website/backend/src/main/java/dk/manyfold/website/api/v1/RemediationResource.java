package dk.manyfold.website.api.v1;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dk.manyfold.website.health.RemediationService;
import dk.manyfold.website.health.RemediationService.RemediationResult;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * REST endpoint for platform remediation actions.
 *
 * <p>
 * Restarts change workloads anywhere in the cluster, so they require the realm
 * role {@value #ADMIN_ROLE} on a verified access token (see the OIDC settings
 * in {@code application.properties}). A logged-in user without it gets 403; a
 * request without a token, or a deployment without the OIDC tenant configured,
 * gets 401.
 */
@Path("/api/v1/remediation")
@Tag(name = "Remediation", description = "Platform remediation actions")
public class RemediationResource {

	private static final Logger LOG = Logger.getLogger(RemediationResource.class);

	/** Realm role that may trigger a restart. */
	static final String ADMIN_ROLE = "admin";

	@Inject
	RemediationService remediationService;

	/** Request to restart a pod. */
	public record RestartPodRequest(String namespace, String podName) {
	}

	/** Request to restart a deployment. */
	public record RestartDeploymentRequest(String namespace, String deploymentName) {
	}

	/** Summary of remediation activity. */
	public record RemediationSummary(int recentActions, String status) {
	}

	/** Restart a pod by deleting it. */
	@POST
	@Path("/pod/restart")
	@RolesAllowed(ADMIN_ROLE)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Restart a pod", description = "Delete pod to trigger recreation")
	@APIResponse(responseCode = "200", description = "Remediation attempted")
	public Response restartPod(@Valid @NotNull RestartPodRequest request) {
		LOG.infof("Remediation request: restart pod %s/%s", request.namespace(), request.podName());
		RemediationResult result = remediationService.restartPod(
				request.namespace(), request.podName());
		return Response.ok(result).build();
	}

	/** Trigger a deployment rollout restart. */
	@POST
	@Path("/deployment/restart")
	@RolesAllowed(ADMIN_ROLE)
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Restart deployment", description = "Trigger rolling restart")
	@APIResponse(responseCode = "200", description = "Remediation attempted")
	public Response restartDeployment(@Valid @NotNull RestartDeploymentRequest request) {
		LOG.infof("Remediation request: restart deployment %s/%s",
				request.namespace(), request.deploymentName());
		RemediationResult result = remediationService.rolloutRestartDeployment(
				request.namespace(), request.deploymentName());
		return Response.ok(result).build();
	}

	/** Get remediation summary. */
	@GET
	@Path("/summary")
	@Produces(MediaType.APPLICATION_JSON)
	@Operation(summary = "Get remediation summary", description = "Returns recent activity")
	@APIResponse(responseCode = "200", description = "Summary returned")
	public RemediationSummary getSummary() {
		int recentCount = remediationService.getRecentRemediationCount();
		String status = recentCount > 5 ? "high_activity" : "normal";
		return new RemediationSummary(recentCount, status);
	}
}
