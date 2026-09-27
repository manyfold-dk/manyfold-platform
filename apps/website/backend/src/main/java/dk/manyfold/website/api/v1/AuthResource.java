package dk.manyfold.website.api.v1;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import dk.manyfold.website.auth.KeycloakLogoutService;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

@Path("/api/v1/auth")
@Tag(name = "Auth", description = "Authentication operations")
public class AuthResource {

	private static final Logger LOG = Logger.getLogger(AuthResource.class);
	private static final String LOGOUT_DESCRIPTION = "Ends Keycloak SSO and clears the local oauth2-proxy session";

	private static final List<String> EMAIL_HEADERS = List.of("X-Forwarded-Email", "X-Auth-Request-Email");
	private static final List<String> USER_HEADERS = List.of(
			"X-Forwarded-Preferred-Username",
			"X-Forwarded-User",
			"X-Auth-Request-User");
	private static final String DEFAULT_PROXY_LOGOUT_URL = "/oauth2/sign_out";

	@Inject
	KeycloakLogoutService keycloakLogoutService;

	@ConfigProperty(name = "manyfold.auth.proxy-logout-url", defaultValue = DEFAULT_PROXY_LOGOUT_URL)
	String proxyLogoutUrl;

	// POST, not GET: a link or image on another site must not be able to sign a
	// user out
	// (review finding W4). The 303 turns the browser's follow-up into a GET of the
	// proxy logout.
	@POST
	@Path("/logout")
	@Produces(MediaType.TEXT_PLAIN)
	@Operation(summary = "Log out the current user", description = LOGOUT_DESCRIPTION)
	@APIResponse(responseCode = "303", description = "Logout initiated")
	@APIResponse(responseCode = "502", description = "Failed to end the Keycloak session")
	public Response logout(@Context HttpHeaders headers) {
		if (!keycloakLogoutService.isConfigured()) {
			return Response.seeOther(proxyLogoutUri()).build();
		}

		String email = firstHeader(headers, EMAIL_HEADERS);
		String username = firstHeader(headers, USER_HEADERS);

		if ((email == null || email.isBlank()) && (username == null || username.isBlank())) {
			LOG.warn("Logout requested without forwarded user identity headers");
			return Response.status(Response.Status.UNAUTHORIZED)
					.type(MediaType.TEXT_PLAIN)
					.entity("Missing authenticated user identity")
					.build();
		}

		try {
			keycloakLogoutService.logoutUser(email, username);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LOG.error("Interrupted while ending Keycloak session", e);
			return Response.status(Response.Status.BAD_GATEWAY)
					.type(MediaType.TEXT_PLAIN)
					.entity("Interrupted while ending Keycloak session")
					.build();
		} catch (IOException e) {
			LOG.error("Failed to end Keycloak session", e);
			return Response.status(Response.Status.BAD_GATEWAY)
					.type(MediaType.TEXT_PLAIN)
					.entity("Failed to end Keycloak session")
					.build();
		}

		return Response.seeOther(proxyLogoutUri()).build();
	}

	private static String firstHeader(HttpHeaders headers, List<String> candidates) {
		for (String candidate : candidates) {
			String value = headers.getHeaderString(candidate);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private URI proxyLogoutUri() {
		return URI.create(proxyLogoutUrl);
	}
}
